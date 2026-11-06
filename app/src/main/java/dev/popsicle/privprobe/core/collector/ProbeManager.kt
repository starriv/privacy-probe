package dev.popsicle.privprobe.core.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.popsicle.privprobe.core.app.UidResolver
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.root.RootShell
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 探针调度器：按设置启停探针、批量落库、汇总健康状态。
 *
 * P1-13 修复：
 * - [applySettings] 只按 diff 启动/停止受影响的探针，不再“设置一变全量重启”；
 *   轮询间隔类设置由探针每轮从 [ProbeSink.settings] 动态读取，无需重启。
 * - 停止 logcat 时调用 [RootShell.closeStreams] 关闭旧流，协程才能立即退出；
 * - [stop] 为 suspend，顺序为「取消 → 中断长命令/关流 → join 探针 → drain flush → root.close」，
 *   `refresh` await 之后再 start，消除异步 root.close 与新连接之间的竞态。
 *
 * PERF P0-2 按屏门控：
 * - 监听 SCREEN_ON/OFF；熄屏时暂停 appops/audio/location 全量轮询（重命令），
 *   其余轮询探针在 [PollingProbe] 里 interval ×4；亮屏立即恢复并补一轮。
 *
 * PERF P1-10 丢事件：
 * - Channel 满时不再静默丢弃，丢弃数计入丢弃计数（见 [debugStats]）；
 * - flush 失败重试一次，仍失败计入落库失败计数并 Log.e，不再静默清空。
 *
 * 原生探针通过 [externalProbeFactory] 注入，本类不直接引用 net 包，避免交叉编译阻塞；
 * 原生开关（PCAP/DNS_TRACE/KERNEL_OPEN）任一变化会以 configKey 变化触发包装探针重启。
 */
class ProbeManager(
    private val root: RootShell,
    private val repository: PrivacyRepository,
    context: Context? = null,
) {
    private inner class ProbeHandle(
        val probe: Probe,
        val job: Job,
        val configKey: String,
    )

    private class ProbeSpec(
        val id: ProbeId,
        val configKey: String,
        val create: () -> Probe,
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("probe-flush"))
    private val lifecycleMutex = Mutex()
    private var probeScope: CoroutineScope? = null
    private var flusher: Job? = null
    private var batchChannel: Channel<PrivacyEvent>? = null
    private val handles = ConcurrentHashMap<ProbeId, ProbeHandle>()
    private val droppedEvents = AtomicLong(0)
    private val flushFailures = AtomicLong(0)

    private val appContext: Context? = context?.applicationContext
    private val powerManager: PowerManager? =
        appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var screenInteractive: Boolean = powerManager?.isInteractive ?: true

    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> updateScreenInteractive(true)
                Intent.ACTION_SCREEN_OFF -> updateScreenInteractive(false)
            }
        }
    }

    private val _statuses = MutableStateFlow<Map<ProbeId, ProbeStatus>>(emptyMap())
    val statuses: StateFlow<Map<ProbeId, ProbeStatus>> = _statuses.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var settingsSnapshot = AppSettings()

    /** 原生探针注入点（由 Lead / AppGraph 接线）；返回的 Probe 使用各自的 ProbeId。 */
    @Volatile
    var externalProbeFactory: (() -> List<Probe>)? = null

    /** 健康状态里的“探针”数量（按开关/状态口径，含 native 的 3 个状态）。 */
    fun activeProbeCount(): Int =
        _statuses.value.values.count { it.healthy && it.detail != "已停止" }

    /** 实际在跑的探针进程/协程数量（native 三个开关共用 1 个进程）。 */
    fun runningProbeCount(): Int = handles.size

    /** 用户打开的探针开关数量（与进程数区分：P2-5 通知文案用）。 */
    fun enabledSwitchCount(settings: AppSettings = settingsSnapshot): Int =
        settings.probes.count { it.value }

    fun debugStats(): String =
        "running=${handles.size} screen=${if (screenInteractive) "on" else "off"} " +
            "dropped=${droppedEvents.get()} flushFailed=${flushFailures.get()}"

    suspend fun start(settings: AppSettings) = withContext(NonCancellable + Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (running) {
                settingsSnapshot = settings
                syncProbes(settings)
                return@withLock
            }
            settingsSnapshot = settings
            running = true
            screenInteractive = powerManager?.isInteractive ?: true
            registerScreenReceiver()
            probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("probes"))
            startFlusher()
            syncProbes(settings)
            Log.i(TAG, "probes started: ${handles.keys} (${debugStats()})")
        }
    }

    /** 设置变化时调用：只对受影响的探针做增删/重启。 */
    suspend fun applySettings(settings: AppSettings) = withContext(NonCancellable + Dispatchers.IO) {
        lifecycleMutex.withLock {
            settingsSnapshot = settings
            if (!running) return@withLock
            syncProbes(settings)
        }
    }

    /**
     * 停止所有探针并等待清理完成（suspend，调用方 await 后再 start 可避免竞态）。
     * 停止时 drain 事件 Channel，避免已采集事件丢失。
     */
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        lifecycleMutex.withLock {
            val wasRunning = running
            running = false
            val snapshot = handles.values.toList()
            handles.clear()
            snapshot.forEach { runCatching { it.job.cancel() } }
            // 请求中断长命令（appops 等），并关闭长驻流（logcat 的 readLine 阻塞只能靠 close stream 打断）
            runCatching { root.requestInterrupt() }
            runCatching { root.closeStreams() }
            snapshot.forEach { h -> h.job.join() }
            probeScope?.cancel()
            probeScope = null
            // drain：关闭 channel 后 flusher 会把剩余事件落库再退出
            closeFlusherSuspend()
            // 同步关闭 root 长连接，确保 su 子进程不残留
            runCatching { root.close() }
            unregisterScreenReceiver()
            _statuses.update { current ->
                current.mapValues { (_, v) -> v.copy(healthy = false, detail = "已停止") }
            }
            if (wasRunning || snapshot.isNotEmpty()) {
                Log.i(TAG, "probes stopped (${snapshot.size} joined, ${debugStats()})")
            }
        }
    }

    // ------------------------------------------------------------- 探针 diff

    private suspend fun syncProbes(settings: AppSettings) {
        val scope = probeScope ?: return
        val desired = desiredSpecs(settings)
        // 1) 停掉已关闭 / 配置变化 / 熄屏需暂停的探针
        for ((id, handle) in handles.entries.toList()) {
            val spec = desired[id]
            if (spec == null || spec.configKey != handle.configKey) {
                val pausedByScreen = !screenInteractive && id in HEAVY_WHEN_SCREEN_OFF
                val reason = if (pausedByScreen) "熄屏暂停（省电）" else "配置关闭"
                stopHandle(id, handle, reason, healthyWhileStopped = pausedByScreen,
                    closeStreams = id == ProbeId.APP_FOREGROUND)
            }
        }
        // 2) 启动新增的探针（含亮屏后恢复的重探针，首个 poll 立即执行 = 补一轮）
        for ((id, spec) in desired) {
            if (!handles.containsKey(id)) {
                startProbe(scope, spec)
            }
        }
    }

    private fun desiredSpecs(settings: AppSettings): LinkedHashMap<ProbeId, ProbeSpec> {
        val specs = buildSpecs(settings)
        if (!screenInteractive && HEAVY_WHEN_SCREEN_OFF.isNotEmpty()) {
            specs.keys.removeAll(HEAVY_WHEN_SCREEN_OFF)
        }
        return specs
    }

    private fun buildSpecs(settings: AppSettings): LinkedHashMap<ProbeId, ProbeSpec> {
        val enabled = settings.probes
        val map = LinkedHashMap<ProbeId, ProbeSpec>()

        if (enabled[ProbeId.CAMERA] != false) {
            map[ProbeId.CAMERA] = ProbeSpec(ProbeId.CAMERA, "always") { DumpsysCameraProbe() }
        }
        if (enabled[ProbeId.AUDIO] != false) {
            map[ProbeId.AUDIO] = ProbeSpec(ProbeId.AUDIO, "always") { DumpsysAudioProbe() }
        }
        if (enabled[ProbeId.LOCATION] != false) {
            map[ProbeId.LOCATION] = ProbeSpec(ProbeId.LOCATION, "always") { DumpsysLocationProbe() }
        }
        if (enabled[ProbeId.SENSOR] != false) {
            map[ProbeId.SENSOR] = ProbeSpec(ProbeId.SENSOR, "always") { DumpsysSensorProbe() }
        }
        if (enabled[ProbeId.SCREEN] != false) {
            map[ProbeId.SCREEN] = ProbeSpec(ProbeId.SCREEN, "always") { DumpsysProjectionProbe() }
        }
        val appOpsOn = enabled[ProbeId.APPOPS] != false || enabled[ProbeId.CLIPBOARD] != false
        if (appOpsOn) {
            map[ProbeId.APPOPS] = ProbeSpec(ProbeId.APPOPS, "always") { AppOpsProbe() }
        }
        if (anyLogcatFeatureEnabled(settings)) {
            val coverForeground = enabled[ProbeId.APP_FOREGROUND] != false
            // P1-7：logcat 内部按连接快照 gate 类别；任一相关开关变化都要重启 logcat 才生效，
            // 但只重启 logcat 本身（P1-13：不要全量重启）。
            val gatesKey = "logcat:clip=${enabled[ProbeId.CLIPBOARD] != false}" +
                ",fg=$coverForeground" +
                ",cam=${enabled[ProbeId.CAMERA] != false}" +
                ",aud=${enabled[ProbeId.AUDIO] != false}" +
                ",loc=${enabled[ProbeId.LOCATION] != false}" +
                ",scr=${enabled[ProbeId.SCREEN] != false}"
            map[ProbeId.APP_FOREGROUND] = ProbeSpec(
                ProbeId.APP_FOREGROUND,
                gatesKey,
            ) { LogcatProbe() }
        }
        // 原生低层探针：三个开关共用一个 privd 进程，任一开关变化都重启包装探针
        if (settings.lowLevelEnabled &&
            (enabled[ProbeId.PCAP] != false ||
                enabled[ProbeId.DNS_TRACE] != false ||
                enabled[ProbeId.KERNEL_OPEN] != false)
        ) {
            val factory = externalProbeFactory
            if (factory != null) {
                val nativeKey = "native:pcap=${enabled[ProbeId.PCAP] != false}," +
                    "dns=${enabled[ProbeId.DNS_TRACE] != false}," +
                    "open=${enabled[ProbeId.KERNEL_OPEN] != false},exec=${enabled[ProbeId.APP_FOREGROUND] != false}"
                val probes = runCatching { factory() }.getOrElse {
                    Log.w(TAG, "external probe factory failed", it)
                    emptyList()
                }
                probes.forEach { probe ->
                    map[probe.id] = ProbeSpec(probe.id, nativeKey) { probe }
                }
            }
        }
        return map
    }

    private fun anyLogcatFeatureEnabled(settings: AppSettings): Boolean {
        val p = settings.probes
        return p[ProbeId.CLIPBOARD] != false ||
            p[ProbeId.APP_FOREGROUND] != false ||
            p[ProbeId.CAMERA] != false ||
            p[ProbeId.AUDIO] != false ||
            p[ProbeId.LOCATION] != false ||
            p[ProbeId.SCREEN] != false
    }

    private fun startProbe(scope: CoroutineScope, spec: ProbeSpec) {
        val probe = runCatching { spec.create() }.getOrElse {
            Log.w(TAG, "create probe ${spec.id} failed", it)
            return
        }
        val sink = SinkImpl(probe.id, probe.statusIds)
        val job = scope.launch {
            try {
                probe.run(sink)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "probe ${probe.id} crashed", t)
                sink.status("异常退出：${t.message ?: t.javaClass.simpleName}", healthy = false)
            }
        }
        handles[spec.id] = ProbeHandle(probe, job, spec.configKey)
        Log.i(TAG, "probe started: ${probe.id}")
    }

    private suspend fun stopHandle(
        id: ProbeId,
        handle: ProbeHandle,
        reason: String,
        healthyWhileStopped: Boolean,
        closeStreams: Boolean,
    ) {
        handles.remove(id, handle)
        runCatching { handle.job.cancel() }
        if (closeStreams) runCatching { root.closeStreams() }
        handle.job.join()
        val ids = handle.probe.statusIds
        _statuses.update { current ->
            var map = current
            ids.forEach { statusId ->
                map = map + (statusId to ProbeStatus(
                    healthy = healthyWhileStopped,
                    detail = reason,
                    lastEventTs = map[statusId]?.lastEventTs ?: 0L,
                ))
            }
            map
        }
        Log.i(TAG, "probe stopped: ${handle.probe.id} ($reason)")
    }

    // ------------------------------------------------------- 按屏门控 / 生命周期

    private fun registerScreenReceiver() {
        val ctx = appContext ?: return
        if (screenReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(ctx, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }.onFailure { Log.w(TAG, "register screen receiver failed", it) }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        runCatching { appContext?.unregisterReceiver(screenReceiver) }
    }

    private fun updateScreenInteractive(interactive: Boolean) {
        appScope.launch {
            lifecycleMutex.withLock {
                screenInteractive = interactive
                if (running) syncProbes(settingsSnapshot)
            }
        }
    }

    // ---------------------------------------------------------------- flusher

    private fun startFlusher() {
        if (flusher != null) return
        val channel = Channel<PrivacyEvent>(capacity = 4096)
        batchChannel = channel
        flusher = appScope.launch {
            val buffer = ArrayList<PrivacyEvent>(128)
            while (true) {
                val first = channel.receiveCatching().getOrNull() ?: break
                buffer.add(first)
                val deadline = System.currentTimeMillis() + 300L
                while (buffer.size < 200) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    val next = withTimeoutOrNull(remain) { channel.receiveCatching().getOrNull() }
                    if (next == null) break
                    buffer.add(next)
                }
                flushBuffer(buffer)
            }
            flushBuffer(buffer)
        }
    }

    /** flush 失败重试一次；仍失败计入失败计数并 Log.e，不静默丢弃。 */
    private suspend fun flushBuffer(buffer: ArrayList<PrivacyEvent>) {
        if (buffer.isEmpty()) return
        val chunk = buffer.toList()
        buffer.clear()
        var ok = runCatching { repository.insertEvents(chunk) }.isSuccess
        if (!ok) {
            delay(FLUSH_RETRY_DELAY_MS)
            ok = runCatching { repository.insertEvents(chunk) }.isSuccess
        }
        if (!ok) {
            flushFailures.addAndGet(chunk.size.toLong())
            Log.e(TAG, "flush failed after retry: ${chunk.size} events dropped (total failed=${flushFailures.get()})")
        }
    }

    private suspend fun closeFlusherSuspend() {
        val channel = batchChannel
        val job = flusher
        batchChannel = null
        flusher = null
        runCatching { channel?.close() }
        job?.join()
    }

    private fun emitToBatch(event: PrivacyEvent): Boolean {
        val channel = batchChannel ?: return false
        if (channel.trySend(event).isSuccess) return true
        val dropped = droppedEvents.incrementAndGet()
        if (dropped == 1L || dropped % 1_000L == 0L) {
            Log.w(TAG, "event channel full, dropped=$dropped (screen=${screenInteractive})")
        }
        return false
    }

    private fun statusFor(id: ProbeId, detail: String, healthy: Boolean) {
        _statuses.update { current ->
            val prev = current[id]
            current + (id to ProbeStatus(healthy = healthy, detail = detail, lastEventTs = prev?.lastEventTs ?: 0L))
        }
    }

    private fun markEvent(id: ProbeId, ts: Long) {
        _statuses.update { current ->
            val prev = current[id]
            current + (id to ProbeStatus(
                healthy = prev?.healthy ?: true,
                detail = prev?.detail ?: "",
                lastEventTs = maxOf(prev?.lastEventTs ?: 0L, ts),
            ))
        }
    }

    private inner class SinkImpl(
        override val id: ProbeId,
        private val statusIds: List<ProbeId>,
    ) : ProbeSink {
        override val settings: AppSettings get() = settingsSnapshot

        override val screenInteractive: Boolean get() = this@ProbeManager.screenInteractive

        override suspend fun emit(event: PrivacyEvent) {
            if (emitToBatch(event)) {
                statusIds.forEach { markEvent(it, event.ts) }
            }
        }

        override suspend fun emitAll(events: List<PrivacyEvent>) {
            if (events.isEmpty()) return
            var last = 0L
            events.forEach { e ->
                if (emitToBatch(e)) last = maxOf(last, e.ts)
            }
            if (last > 0) statusIds.forEach { markEvent(it, last) }
        }

        override fun statusForProbe(id: ProbeId, detail: String, healthy: Boolean) {
            if (id in statusIds) statusFor(id, detail, healthy)
        }

        override fun status(detail: String, healthy: Boolean) {
            statusIds.forEach { statusFor(it, detail, healthy) }
        }

        override suspend fun exec(command: String, timeoutMs: Long): RootShell.Result =
            root.exec(command, timeoutMs)

        override suspend fun lines(command: String, timeoutMs: Long): List<String>? {
            val r = root.exec(command, timeoutMs)
            return if (r.ok) r.lines() else null
        }

        override fun openStream(command: String): RootShell.Stream? = root.openStream(command)

        override fun uidForPackage(pkg: String?): Int = UidResolver.uidForPackage(pkg)

        override fun label(pkg: String?, uid: Int?): String = UidResolver.labelFor(pkg, uid)

        override fun log(message: String, error: Throwable?) {
            if (error != null) Log.w(TAG, "[$id] $message", error) else Log.d(TAG, "[$id] $message")
        }
    }

    companion object {
        private const val TAG = "ProbeManager"
        private const val FLUSH_RETRY_DELAY_MS = 300L

        /** 熄屏时暂停的全量 dumpsys 探针（重命令；其余轮询探针由 PollingProbe 降频 ×4）。 */
        private val HEAVY_WHEN_SCREEN_OFF: Set<ProbeId> = setOf(
            ProbeId.APPOPS,
            ProbeId.AUDIO,
            ProbeId.LOCATION,
        )
    }
}
