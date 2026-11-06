package dev.popsicle.privprobe.service

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.popsicle.privprobe.core.collector.CollectorControl
import dev.popsicle.privprobe.core.collector.CollectorRuntimeStatus
import dev.popsicle.privprobe.core.collector.ProbeManager
import dev.popsicle.privprobe.core.collector.ProbeStatus
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.core.root.RootShell
import dev.popsicle.privprobe.core.selftest.SelfTestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 采集控制面单例（由 AppGraph 创建/持有）：root 检测、服务启停、探针状态、自检。
 * 实际的探针协程归 [ProbeManager] 所有；本类只负责生命周期与对外状态。
 */
class CollectorControllerImpl private constructor(
    context: Context,
    private val repository: PrivacyRepository,
    private val probeManager: ProbeManager,
    private val settings: SettingsStore,
) : CollectorControl {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsWatcher: Job? = null

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _rootAvailable = MutableStateFlow(false)
    override val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    private val _rootDetail = MutableStateFlow("尚未检测 root")
    override val rootDetail: StateFlow<String> = _rootDetail.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _probeStatus = MutableStateFlow<Map<ProbeId, ProbeStatus>>(emptyMap())
    override val probeStatus: StateFlow<Map<ProbeId, ProbeStatus>> = _probeStatus.asStateFlow()

    /** 自检结果（额外扩展，UI 可选用；不在 CollectorControl 契约内）。 */
    private val _selfTestResult = MutableStateFlow<String?>(null)
    val selfTestResult: StateFlow<String?> = _selfTestResult.asStateFlow()

    private val commands = SerialCommands(scope)

    private fun enqueue(action: suspend () -> Unit): Job = commands.submit {
        try {
            action()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "collector command failed", e)
            _lastError.value = "采集操作失败：${e.message}"
        }
    }

    init {
        scope.launch {
            probeManager.statuses.collect { _probeStatus.value = it }
        }
        // 进程启动即预检一次 root（会触发 KernelSU 授权弹窗），让 UI 首屏就能显示真实状态
        refreshRootStateAsync()
    }

    /** 异步重新检测 root 并刷新状态（不重启探针）。 */
    fun refreshRootStateAsync() {
        scope.launch { refreshRootState() }
    }

    private suspend fun refreshRootState() {
        val available = withContext(Dispatchers.IO) {
            runCatching { RootShell.isAvailable(true) }.getOrDefault(false)
        }
        _rootAvailable.value = available
        _rootDetail.value = RootShell.detail
        if (!available) {
            _lastError.value = ROOT_HINT
            _rootDetail.value = RootShell.detail
        } else if (_lastError.value == ROOT_HINT) {
            _lastError.value = null
        }
    }

    /** Service 启动前台服务失败等运行时错误上报（P1-14）。 */
    fun reportError(message: String?) {
        if (message.isNullOrBlank()) return
        _lastError.value = message
        Log.w(TAG, "runtime error: $message")
    }

    // ------------------------------------------------------------ 生命周期

    override fun start(context: Context) {
        settings.update { it.copy(collectorEnabled = true) }
        _lastError.value = null
        val intent = Intent(context, CollectorService::class.java)
            .setAction(CollectorService.ACTION_START)
        runCatching { context.startForegroundService(intent) }
            .onFailure {
                _lastError.value = "启动前台服务失败：${it.message}"
                Log.w(TAG, "start collector service failed", it)
            }
    }

    override fun stop(context: Context) {
        settings.update { it.copy(collectorEnabled = false) }
        runCatching {
            context.startService(
                Intent(context, CollectorService::class.java).setAction(CollectorService.ACTION_STOP),
            )
        }.onFailure {
            reportError("停止服务失败：${it.message}")
        }
    }

    override suspend fun refresh(context: Context) {
        enqueue {
            val wasRunning = _running.value
            val available = withContext(Dispatchers.IO) {
                runCatching { RootShell.isAvailable(true) }.getOrDefault(false)
            }
            _rootAvailable.value = available
            _rootDetail.value = RootShell.detail
            if (available) {
                _lastError.value = if (_lastError.value == ROOT_HINT) null else _lastError.value
            } else {
                applyRootUnavailableStatuses()
            }
            if (wasRunning && settings.snapshot().collectorEnabled) {
                // P1-13：先 await 旧的 stop（含 join 探针 + root.close），再 start，避免异步 close 竞态
                probeManager.stop()
                if (available) {
                    probeManager.start(settings.snapshot())
                } else {
                    _lastError.value = ROOT_HINT
                }
            } else if (!available) {
                _lastError.value = ROOT_HINT
            }
        }.join()
    }

    override suspend fun runSelfTest(context: Context) {
        _selfTestResult.value = "自检运行中…"
        val runner = SelfTestRunner(appContext, repository)
        val report = runCatching { runner.run() }.getOrElse {
            Log.w(TAG, "self-test crashed", it)
            val message = "自检异常：${it.message ?: it.javaClass.simpleName}"
            _lastError.value = message
            _selfTestResult.value = message
            return
        }
        val readable = formatSelfTest(report)
        _selfTestResult.value = readable
        if (report.success) {
            _lastError.value = null
        } else {
            val failed = report.steps.filter { !it.ok }
            _lastError.value = "自检未全部通过：" +
                failed.joinToString("、") { "${stepName(it.name)}（${it.detail}）" }
        }
        // 自检产生事件后让 root 状态也刷新一次
        _rootAvailable.value = RootShell.isAvailable(false)
    }

    /** 把自检结果转成用户可读的中文报告（P2-5）。 */
    private fun formatSelfTest(report: SelfTestRunner.Report): String {
        val passed = report.steps.filter { it.ok }
        val failed = report.steps.filter { !it.ok }
        return buildString {
            if (failed.isEmpty()) append("自检通过：") else append("自检未全部通过：")
            if (passed.isNotEmpty()) append(passed.joinToString("、") { stepName(it.name) })
            if (failed.isNotEmpty()) {
                if (passed.isNotEmpty()) append("；")
                append("未通过 ").append(failed.joinToString("、") { "${stepName(it.name)}（${it.detail}）" })
            }
        }
    }

    private fun stepName(name: String): String = when (name) {
        "permissions" -> "权限"
        "dns" -> "DNS"
        "camera" -> "相机"
        "microphone" -> "麦克风"
        "location" -> "定位"
        "sensor" -> "传感器"
        "clipboard" -> "剪贴板"
        else -> name
    }

    // ------------------------------------------------- 供 Service / Repo 调用

    fun onServiceStarted(): Job = enqueue {
        if (_running.value || !settings.snapshot().collectorEnabled) return@enqueue
        val available = RootShell.isAvailable(true)
        if (!settings.snapshot().collectorEnabled) return@enqueue
        _rootAvailable.value = available
        _rootDetail.value = RootShell.detail
        if (!available) _lastError.value = ROOT_HINT
        probeManager.start(settings.snapshot())
        _running.value = true
        settingsWatcher?.cancel()
        settingsWatcher = scope.launch {
            settings.state.collect { s -> probeManager.applySettings(s) }
        }
        Log.i(TAG, "collector service started")
    }

    fun onServiceStopped(): Job = enqueue {
        _running.value = false
        settingsWatcher?.cancel()
        settingsWatcher = null
        probeManager.stop()
        Log.i(TAG, "collector service stopped")
    }

    suspend fun onServiceStoppedSuspend() { onServiceStopped().join() }

    fun statusSnapshot(): CollectorRuntimeStatus = CollectorRuntimeStatus(
        rootAvailable = _rootAvailable.value,
        activeProbeCount = probeManager.activeProbeCount(),
        rootHint = if (_rootAvailable.value) null else ROOT_HINT,
    )

    /** 无 root 时，把依赖 root 的探针标记为不可用（UI 上给出可读原因）。 */
    private fun applyRootUnavailableStatuses() {
        val needsRoot = listOf(
            ProbeId.CAMERA, ProbeId.AUDIO, ProbeId.LOCATION, ProbeId.SENSOR, ProbeId.SCREEN,
            ProbeId.APPOPS, ProbeId.CLIPBOARD, ProbeId.APP_FOREGROUND,
            ProbeId.PCAP, ProbeId.DNS_TRACE, ProbeId.KERNEL_OPEN,
        )
        _probeStatus.update { current ->
            var map = current
            needsRoot.forEach { id ->
                if (map[id]?.healthy != true) {
                    map = map + (id to ProbeStatus(
                        healthy = false,
                        detail = "需要 root：$ROOT_HINT",
                        lastEventTs = map[id]?.lastEventTs ?: 0L,
                    ))
                }
            }
            map
        }
    }

    fun settingsSnapshot(): AppSettings = settings.snapshot()

    companion object {
        private const val TAG = "CollectorController"

        const val ROOT_HINT: String =
            "未取得 root 权限：请在 KernelSU 中允许「隐私探针」，否则 dumpsys/appops 探针无法工作。"

        @Volatile
        private var instance: CollectorControllerImpl? = null

        fun create(
            context: Context,
            repository: PrivacyRepository,
            probeManager: ProbeManager,
            settings: SettingsStore,
        ): CollectorControllerImpl {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: CollectorControllerImpl(
                    context.applicationContext,
                    repository,
                    probeManager,
                    settings,
                ).also { instance = it }
            }
        }

        fun get(): CollectorControllerImpl? = instance
    }
}
