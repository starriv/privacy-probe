package dev.popsicle.privprobe.core.collector.net

import android.content.Context
import android.os.Build
import android.util.Log
import dev.popsicle.privprobe.core.collector.ProbeStatus
import kotlinx.coroutines.flow.update
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.io.File
import java.io.IOException
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 原生采集器 privd 的流式探针（Kotlin 侧）。
 *
 * 工作方式：
 *  1. 把 `assets/bin/<abi>/privd` 解压到 `filesDir/privd-<唯一后缀>` 并 chmod 755
 *     （每次唯一文件名，避免覆盖正在执行的旧二进制导致 ETXTBSY）；
 *  2. 通过 `su -c` 启动 privd（--pcap/--uprobe/--kprobe-open/--exec/--uid-lookup）；
 *  3. 逐行读取 stdout 的 NDJSON，映射成 [PrivacyEvent] 交给 [sink]；
 *  4. 进程退出后指数退避重启，直到 [stop]。
 *
 * 本类只依赖 core/model + core/data 的只读契约，不修改其它 core 文件。
 */
class NativeStreamProbe(
    private val context: Context,
    private val settingsProvider: () -> AppSettings,
    private val sink: (PrivacyEvent) -> Unit,
) {
    companion object {
        private const val TAG = "NativeStreamProbe"
        private const val ASSET_BIN = "privd"
        private const val RESTART_MIN_MS = 1_000L
        private const val RESTART_MAX_MS = 30_000L
        private const val HEALTHY_RUN_MS = 30_000L

        /** Capture 通道才是录音；playback 是播放，不发麦克风事件。 */
        private val PCM_CAPTURE = Regex("^/dev/snd/pcmC\\d+D\\d+c$")
        private val PCM_PLAYBACK = Regex("^/dev/snd/pcmC\\d+D\\d+p$")
        /** 解压失败（含 ETXTBSY）时换唯一文件名重试次数。 */
        private const val EXTRACT_ATTEMPTS = 3

        /** exec 噪声 comm 黑名单（kprobe/tracepoint 会把 shell/dumpsys 等系统工具也报上来）。 */
        private val EXEC_NOISE = setOf(
            "dumpsys", "pm", "sh", "getprop", "cmd", "app_process", "toybox", "logcat",
            "su", "ps", "top", "kill", "am", "settings", "service", "dpm", "wm", "sm",
            "content", "screencap", "input", "monkey", "id", "env", "dd", "cat",
            "unzip", "grep", "sed", "awk", "head", "tail", "sort", "tr", "strings",
        )
        /** exec 限流：同 (uid,path) 10s 一条 */
        private const val EXEC_DEDUPE_MS = 10_000L

        /** dns_q 去重窗口：等 1s 看是否出现同名 gai（uprobe 归因更好） */
        private const val DNS_DEDUPE_MS = 1_000L
        /** open 事件限流：同 (uid,path) 10s 一次；系统媒体服务 60s 一次 */
        private const val OPEN_DEDUPE_MS = 10_000L
        private const val OPEN_DEDUPE_SYSTEM_MS = 60_000L
        private val OPEN_SYSTEM_UIDS = setOf(1041, 1047) // audioserver / cameraserver

        /** conn_domain 限流：同 (uid, domain) 60s 一条（GMS 等会每 1~2s 新建连接） */
        private const val CONN_DOMAIN_DEDUPE_MS = 60_000L

        /** gai 去重：getaddrinfo 与 android_getaddrinfofornet 可能各命中一次 */
        private const val GAI_DEDUPE_MS = 2_000L
    }

    private val _lastStatus = MutableStateFlow<String?>(null)
    val lastStatus: StateFlow<String?> = _lastStatus.asStateFlow()

    private val _featureStatuses = MutableStateFlow<Map<ProbeId, ProbeStatus>>(emptyMap())
    val featureStatuses: StateFlow<Map<ProbeId, ProbeStatus>> = _featureStatuses.asStateFlow()

    private fun resetFeatureStatuses(detail: String) {
        val settings = settingsProvider()
        _featureStatuses.value = listOf(ProbeId.PCAP, ProbeId.DNS_TRACE, ProbeId.KERNEL_OPEN).associateWith {
            ProbeStatus(healthy = false, detail = if (settings.probes[it] == false) "配置关闭" else detail)
        }
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var stopped = true
    @Volatile private var process: Process? = null
    @Volatile private var binaryPath: String? = null
    private var job: Job? = null
    private val nativePid = AtomicInteger(-1)
    private val binaryCounter = AtomicInteger(0)

    /** DNS 应答 IP → 域名（带 TTL），用于 conn/sni 的 HEURISTIC 补全。 */
    private data class CachedDomain(val name: String, val expiresAt: Long)

    private val ipCache = object : LinkedHashMap<String, CachedDomain>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDomain>?): Boolean =
            size > 256
    }

    /** 去重/限流状态（都受 gateLock 保护） */
    @Volatile private var probeScope: CoroutineScope? = null
    private val gateLock = Any()
    private val pendingDns = HashMap<Pair<Int, String>, MutableList<PendingDns>>()
    private val recentGai = HashMap<Pair<Int, String>, Long>()
    private val openRate = HashMap<String, Long>()
    private val connDomainRate = HashMap<String, Long>()
    private val gaiRate = HashMap<String, Long>()
    private val execRate = HashMap<String, Long>()

    private class PendingDns(val event: PrivacyEvent) {
        var job: Job? = null
        var cancelled = false
    }

    fun start(scope: CoroutineScope) {
        if (!stopped) return
        stopped = false
        probeScope = scope
        clearPending()
        resetFeatureStatuses("启动中")
        job = scope.launch(Dispatchers.IO) { supervise() }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        _running.value = false
        resetFeatureStatuses("采集器未运行")
        job?.cancel()
        job = null
        clearPending()
        probeScope = null

        val pid = nativePid.getAndSet(-1)
        try {
            if (pid > 0) {
                // P2-8：只杀 hello 报给我们的 pid；先 TERM，等它自己清理 tracefs，再 KILL
                runRootScript("kill-privd-pid.sh", pidKillScript(pid))
            }
            // 兜底：只清理 exe 指向本 App filesDir/privd-* 的进程（不碰无关进程）
            runRootScript("kill-privd-stale.sh", staleSweepScript())
        } catch (t: Throwable) {
            Log.w(TAG, "stop(): kill failed", t)
        }
        process?.let { p ->
            try {
                p.destroy()
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        process = null
        _lastStatus.value = null
    }

    // ---------------- 监督循环 ----------------

    private suspend fun supervise() {
        var backoff = RESTART_MIN_MS
        while (!stopped) {
            val settings = settingsProvider()
            if (!settings.lowLevelEnabled) {
                _lastStatus.value = "低层采集已关闭（设置中已禁用）"
                delay(2_000)
                continue
            }
            val flags = buildFlags(settings)
            if (flags.isEmpty()) {
                _lastStatus.value = "原生探针均已关闭"
                delay(2_000)
                continue
            }
            val startedAt = System.currentTimeMillis()
            val reason = try {
                runOnce(flags)
            } catch (t: Throwable) {
                Log.w(TAG, "privd run failed", t)
                t.message ?: t.javaClass.simpleName
            }
            if (stopped) break
            _running.value = false
            resetFeatureStatuses("采集器未运行")
            val ranMs = System.currentTimeMillis() - startedAt
            if (ranMs >= HEALTHY_RUN_MS) backoff = RESTART_MIN_MS
            _lastStatus.value = "privd 退出（$reason），${backoff / 1000}s 后重启"
            Log.i(TAG, "privd exited: $reason, restart in ${backoff}ms")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RESTART_MAX_MS)
        }
    }

    private fun buildFlags(settings: AppSettings): List<String> {
        val flags = mutableListOf<String>()
        fun on(id: ProbeId) = settings.probes[id] != false
        if (on(ProbeId.PCAP)) flags += "--pcap"
        if (on(ProbeId.DNS_TRACE)) flags += "--uprobe"
        if (on(ProbeId.KERNEL_OPEN)) flags += "--kprobe-open"
        if (on(ProbeId.APP_FOREGROUND)) flags += "--exec"
        if (flags.isNotEmpty()) flags += "--uid-lookup"
        return flags
    }

    /**
     * 启动前清掉可能残留的旧 privd（服务重启/上次崩溃），并**等待其退出**，
     * 避免与旧进程竞争 tracefs 事件名、或覆盖正在执行的二进制。
     */
    private fun killStaleInstances() {
        try {
            runRootScript("kill-privd-stale.sh", staleSweepScript())
        } catch (t: Throwable) {
            Log.w(TAG, "pre-kill stale privd failed", t)
        }
    }

    // ---------------- 精确进程清理（P2-8） ----------------

    /** 把 root 脚本写到 filesDir 再执行（避免 su -c 的复杂引号）。 */
    private fun runRootScript(name: String, content: String, timeoutSec: Long = 4) {
        try {
            val f = File(context.filesDir, name)
            if (!f.exists() || f.length() != content.length.toLong() || f.readText() != content) {
                f.writeText(content)
            }
            val p = ProcessBuilder("su", "-c", "sh '${f.absolutePath}'")
                .redirectErrorStream(true)
                .start()
            p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly()
        } catch (t: Throwable) {
            Log.w(TAG, "root script $name failed", t)
        }
    }

    /** 只清理 exe 指向本 App filesDir/privd-* 的进程（旧版本残留），先 TERM，等 ~1.5s 再 KILL。 */
    private fun staleSweepScript(): String {
        val prefix = context.filesDir.absolutePath + "/" + ASSET_BIN + "-"
        return """
            #!/system/bin/sh
            PREFIX="$prefix"
            sweep() {
              for p in /proc/[0-9]*/exe; do
                t=${'$'}(readlink "${'$'}p" 2>/dev/null)
                case "${'$'}t" in
                  "${'$'}PREFIX"*) q=${'$'}{p#/proc/}; echo ${'$'}{q%/exe} ;;
                esac
              done
            }
            pids=${'$'}(sweep)
            [ -n "${'$'}pids" ] && kill -TERM ${'$'}pids 2>/dev/null
            i=0
            while [ ${'$'}i -lt 30 ]; do
              pids=${'$'}(sweep)
              [ -z "${'$'}pids" ] && exit 0
              sleep 0.05
              i=${'$'}((i+1))
            done
            pids=${'$'}(sweep)
            [ -n "${'$'}pids" ] && kill -KILL ${'$'}pids 2>/dev/null
            exit 0
        """.trimIndent() + "\n"
    }

    /** 精确按 pid：TERM → 轮询 /proc/<pid> 最多 1.5s → KILL。 */
    private fun pidKillScript(pid: Int): String = """
        #!/system/bin/sh
        PID=$pid
        kill -TERM "${'$'}PID" 2>/dev/null
        i=0
        while [ ${'$'}i -lt 30 ]; do
          [ -d "/proc/${'$'}PID" ] || exit 0
          sleep 0.05
          i=${'$'}((i+1))
        done
        kill -KILL "${'$'}PID" 2>/dev/null
        exit 0
    """.trimIndent() + "\n"

    /** 跑一个 privd 进程直到退出；返回退出原因（用于状态展示）。 */
    private suspend fun runOnce(flags: List<String>): String {
        if (stopped) return "stopped"
        // 先杀旧进程并等它退出，再写新文件（顺序反过来会撞 ETXTBSY/事件名冲突）
        killStaleInstances()
        /* start() 后立刻 stop() 的竞态：这里再检查一次，避免清理后又把进程拉起来 */
        if (stopped) return "stopped"
        val bin = ensureBinary() ?: return "privd 二进制缺失"
        binaryPath = bin.absolutePath
        if (stopped) return "stopped"
        val cmd = buildString {
            append("exec ")
            append(bin.absolutePath)
            for (f in flags) {
                append(' ')
                append(f)
            }
            // P0-3：把 App 进程 pid 传给 privd，App 进程消失时 privd 自查退出
            append(" --owner-pid ")
            append(android.os.Process.myPid())
        }
        _lastStatus.value = "启动 privd（${flags.joinToString(" ") }）"
        val p = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            return "启动 su 失败: ${t.message}"
        }
        process = p
        nativePid.set(-1)
        if (stopped) {
            // 极小概率：su 启动过程中收到 stop()
            try {
                p.destroy()
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Throwable) {
            }
            process = null
            return "stopped"
        }
        _running.value = true

        thread(isDaemon = true, name = "privd-stderr") { drainStderr(p) }

        val code: Int? = try {
            val reader = p.inputStream.bufferedReader()
            readLoop(reader)
            p.waitFor()
        } catch (io: IOException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "read loop failed", t)
            null
        } finally {
            try {
                p.inputStream.close()
            } catch (_: Throwable) {
            }
        }
        process = null
        return if (code == null) "stdout 读取中断" else "exit=$code"
    }

    private fun drainStderr(p: Process) {
        try {
            p.errorStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) Log.i(TAG, "privd: $line")
                }
            }
        } catch (_: IOException) {
        } catch (_: Throwable) {
        }
    }

    // ---------------- 去重 / 限流 ----------------

    /** stop()/start() 时取消所有未发出的 pending dns_q，避免泄漏或迟到事件。 */
    private fun clearPending() {
        synchronized(gateLock) {
            for (list in pendingDns.values) {
                for (p in list) {
                    p.cancelled = true
                    p.job?.cancel()
                }
            }
            pendingDns.clear()
            recentGai.clear()
            openRate.clear()
            connDomainRate.clear()
            gaiRate.clear()
            execRate.clear()
        }
    }

    /** dns_q 延迟 1s 再发；窗口内出现同名 gai 就丢弃 dns_q（gai 归因更准）。 */
    private fun scheduleDnsQuery(event: PrivacyEvent) {
        val domain = event.target ?: return
        // 无 UID 的包不能证明与任意已知应用是同一行为，宁可保留证据。
        if (event.uid < 0) { emit(event); return }
        val name = event.uid to domain.lowercase()
        val now = System.currentTimeMillis()
        synchronized(gateLock) {
            val gaiAt = recentGai[name]
            if (gaiAt != null && now - gaiAt <= DNS_DEDUPE_MS) return
        }
        val scope = probeScope ?: return
        val pending = PendingDns(event)
        synchronized(gateLock) {
            pendingDns.getOrPut(name) { mutableListOf() }.add(pending)
        }
        val job = scope.launch {
            delay(DNS_DEDUPE_MS)
            var toEmit: PrivacyEvent? = null
            synchronized(gateLock) {
                val list = pendingDns[name] ?: return@launch
                list.remove(pending)
                if (list.isEmpty()) pendingDns.remove(name)
                if (!pending.cancelled) toEmit = pending.event
            }
            toEmit?.let { emit(it) }
        }
        pending.job = job
    }

    /** gai 立即发出，并取消同名 pending dns_q。 */
    private fun markGaiAndCancelPendingDns(uid: Int, domain: String) {
        if (uid < 0) return
        val name = uid to domain.lowercase()
        val now = System.currentTimeMillis()
        synchronized(gateLock) {
            recentGai[name] = now
            if (recentGai.size > 512) {
                recentGai.entries.removeAll { now - it.value > DNS_DEDUPE_MS * 2 }
            }
            val list = pendingDns[name] ?: return
            for (p in list) {
                p.cancelled = true
                p.job?.cancel()
            }
            pendingDns.remove(name)
        }
    }

    /** conn_domain 限流：同 (uid, domain) 60s 一条，避免 GMS/vending 反复连接刷屏。 */
    private fun connDomainAllowed(uid: Int, domain: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "$uid|$domain"
        synchronized(gateLock) {
            val last = connDomainRate[key]
            if (last != null && now - last < CONN_DOMAIN_DEDUPE_MS) return false
            connDomainRate[key] = now
            if (connDomainRate.size > 512) {
                connDomainRate.entries.removeAll { now - it.value > CONN_DOMAIN_DEDUPE_MS * 2 }
            }
        }
        return true
    }

    /** gai 去重：同 (uid, name) 2s 内只发先到的一条（两个 uprobe 可能各命中一次）。 */
    private fun gaiAllowed(uid: Int, name: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "$uid|$name"
        synchronized(gateLock) {
            val last = gaiRate[key]
            if (last != null && now - last < GAI_DEDUPE_MS) return false
            gaiRate[key] = now
            if (gaiRate.size > 1024) {
                gaiRate.entries.removeAll { now - it.value > GAI_DEDUPE_MS * 2 }
            }
        }
        return true
    }

    /** exec 限流：同 (uid,path) 10s 一条，避免同一进程反复 spawn 的工具刷屏。 */
    private fun execAllowed(uid: Int, path: String): Boolean {
        val now = System.currentTimeMillis()
        val key = "$uid|$path"
        synchronized(gateLock) {
            val last = execRate[key]
            if (last != null && now - last < EXEC_DEDUPE_MS) return false
            execRate[key] = now
            if (execRate.size > 512) {
                execRate.entries.removeAll { now - it.value > EXEC_DEDUPE_MS * 2 }
            }
        }
        return true
    }

    /** open 限流：同 (uid,path) 10s 一次；cameraserver/audioserver 60s。 */
    private fun openAllowed(uid: Int, path: String): Boolean {
        val ttl = if (uid in OPEN_SYSTEM_UIDS) OPEN_DEDUPE_SYSTEM_MS else OPEN_DEDUPE_MS
        val now = System.currentTimeMillis()
        val key = "$uid|$path"
        synchronized(gateLock) {
            val last = openRate[key]
            if (last != null && now - last < ttl) return false
            openRate[key] = now
            if (openRate.size > 512) {
                openRate.entries.removeAll { now - it.value > OPEN_DEDUPE_SYSTEM_MS }
            }
        }
        return true
    }

    // ---------------- NDJSON 解析 ----------------

    private fun readLoop(reader: BufferedReader) {
        while (!stopped) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            try {
                handleLine(line)
            } catch (t: Throwable) {
                // 单行坏 JSON 绝不影响整体
                Log.w(TAG, "bad NDJSON line: ${line.take(200)}", t)
            }
        }
    }

    private fun handleLine(line: String) {
        val json = JSONObject(line)
        if (json.optInt("v", 1) != 1) return
        val ts = json.optLong("ts", System.currentTimeMillis())
        when (val t = json.optString("t")) {
            "status" -> {
                val id = when (json.optString("feature")) {
                    "pcap" -> ProbeId.PCAP
                    "uprobe" -> ProbeId.DNS_TRACE
                    "kprobe-open" -> ProbeId.KERNEL_OPEN
                    else -> return
                }
                if (settingsProvider().probes[id] != false) {
                    _featureStatuses.update { it + (id to ProbeStatus(
                        healthy = json.optBoolean("ok", false),
                        detail = json.optString("msg", "状态未知"),
                    )) }
                }
            }
            "hello" -> {
                val pid = json.optInt("pid", -1)
                if (pid > 0) nativePid.set(pid)
                val features = json.optJSONArray("features")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList()
                val ifaces = json.optJSONArray("ifaces")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList()
                _lastStatus.value = "privd 已连接 pid=$pid · " +
                    (features.joinToString("+").ifEmpty { "无特性" }) +
                    (if (ifaces.isNotEmpty()) " · ${ifaces.joinToString(",")}" else "")
                if (json.optBoolean("dry_run")) {
                    val caps = json.optJSONObject("caps")
                    if (caps != null) {
                        Log.i(TAG, "dry-run caps: $caps")
                    }
                }
            }
            "warn" -> {
                val msg = json.optString("msg")
                if (msg.isNotBlank()) {
                    _lastStatus.value = "privd warn: $msg"
                    Log.w(TAG, "privd warn: $msg")
                }
            }
            "error" -> {
                val msg = json.optString("msg")
                if (msg.isNotBlank()) {
                    _lastStatus.value = "privd error: $msg"
                    Log.e(TAG, "privd error: $msg")
                }
            }
            "dns_q" -> {
                val name = json.optString("name").trim().trimEnd('.')
                if (name.isEmpty()) return
                val src = json.optString("src")
                val dst = json.optString("dst")
                scheduleDnsQuery(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.NETWORK,
                        subType = "dns_q",
                        uid = optUid(json),
                        pid = optPid(json),
                        target = name,
                        detail = if (src.isNotEmpty() || dst.isNotEmpty()) "$src → $dst" else null,
                        source = EventSource.PACKET,
                        confidence = 90,
                    ),
                )
            }
            "dns_a" -> {
                val addr = json.optString("addr")
                val name = json.optString("name")
                if (addr.isNotEmpty() && name.isNotEmpty()) {
                    cacheIp(addr, name, json.optInt("ttl", 60))
                }
            }
            "sni" -> {
                val name = json.optString("name").trim().trimEnd('.')
                if (name.isEmpty()) return
                emit(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.NETWORK,
                        subType = "tls_sni",
                        uid = optUid(json),
                        pid = optPid(json),
                        target = name,
                        detail = json.optString("dst").ifEmpty { null },
                        source = EventSource.PACKET,
                        confidence = 90,
                    ),
                )
            }
            "http" -> {
                val host = json.optString("host").trim().trimEnd('.')
                if (host.isEmpty()) return
                emit(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.NETWORK,
                        subType = "http_host",
                        uid = optUid(json),
                        pid = optPid(json),
                        target = host,
                        detail = json.optString("dst").ifEmpty { null },
                        source = EventSource.PACKET,
                        confidence = 90,
                    ),
                )
            }
            "conn" -> {
                // 裸 IP target 对「谁访问了哪些域名」没有价值：只有 DNS 缓存能解析出域名时才发
                val dst = json.optString("dst")
                if (dst.isEmpty()) return
                val ip = ipOf(dst) ?: return
                val domain = lookupIp(ip) ?: return
                val uid = optUid(json)
                // 同 (uid, domain) 60s 一条，避免 GMS/vending 每 1~2s 新建连接刷屏
                if (!connDomainAllowed(uid, domain)) return
                emit(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.NETWORK,
                        subType = "conn_domain",
                        uid = uid,
                        pid = optPid(json),
                        target = domain,
                        detail = "$dst（DNS 关联）",
                        source = EventSource.HEURISTIC,
                        confidence = 65,
                    ),
                )
            }
            "gai" -> {
                val name = json.optString("name").trim().trimEnd('.')
                if (name.isEmpty()) return
                val uid = optUid(json)
                // getaddrinfo 与 android_getaddrinfofornet 两个 uprobe 可能各命中一次：
                // 同 (uid, name) 2s 内只发先到的一条
                if (gaiAllowed(uid, name)) {
                    emit(
                        PrivacyEvent(
                            ts = ts,
                            category = PrivacyCategory.NETWORK,
                            subType = "getaddrinfo",
                            uid = uid,
                            pid = optPid(json),
                            target = name,
                            detail = json.optString("comm").ifEmpty { "libc getaddrinfo" },
                            source = EventSource.UPROBE,
                            confidence = 95,
                        ),
                    )
                }
                // 即使本条被去重，也说明 gai 已覆盖该域名 → 抑制同名 dns_q
                markGaiAndCancelPendingDns(uid, name)
            }
            "open" -> {
                val path = json.optString("path")
                val category = when {
                    path.startsWith("/dev/video") -> PrivacyCategory.CAMERA
                    PCM_CAPTURE.matches(path) -> PrivacyCategory.MICROPHONE
                    // playback 通道（pcmC*D*p）只是播放，不是录音；未知 snd 路径也忽略
                    PCM_PLAYBACK.matches(path) -> return
                    else -> return
                }
                val uid = optUid(json)
                // kprobe 在每次设备打开/轮询时都会触发，做限流：同 (uid,path) 10s 一次，
                // 系统媒体服务（cameraserver/audioserver）60s 一次
                if (!openAllowed(uid, path)) return
                emit(
                    PrivacyEvent(
                        ts = ts,
                        category = category,
                        subType = "device_open",
                        uid = uid,
                        pid = optPid(json),
                        target = path,
                        detail = json.optString("comm").ifEmpty { null },
                        source = EventSource.KERNEL_TRACE,
                        confidence = 60,
                    ),
                )
            }
            "exec" -> {
                // P1-11：exec 噪声过滤。
                // native 侧已只发 uid>=10000；这里再排除本 App、shell 工具黑名单，
                // 并用 path 的 basename 作为 target（comm 被内核截断到 15 字节，不适合展示）。
                val uid = optUid(json)
                if (uid < 10000) return
                if (uid == context.applicationInfo.uid) return
                val path = json.optString("path")
                val name = path.substringAfterLast('/').ifEmpty { json.optString("comm") }
                if (name.isEmpty()) return
                if (name.lowercase() in EXEC_NOISE) return
                if (!execAllowed(uid, path.ifEmpty { name })) return
                emit(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.APP_ACTIVITY,
                        subType = "process_exec",
                        uid = uid,
                        pid = optPid(json),
                        target = name,
                        detail = path.ifEmpty { null },
                        source = EventSource.KERNEL_TRACE,
                        confidence = 60,
                    ),
                )
            }
            else -> Log.d(TAG, "ignore event type: $t")
        }
    }

    private fun optUid(json: JSONObject): Int =
        if (json.has("uid")) json.optInt("uid", PrivacyEvent.UNKNOWN_UID) else PrivacyEvent.UNKNOWN_UID

    private fun optPid(json: JSONObject): Int? =
        if (json.has("pid")) json.optInt("pid", -1).takeIf { it > 0 } else null

    private fun emit(event: PrivacyEvent) {
        if (stopped) return
        try {
            sink(event)
        } catch (t: Throwable) {
            Log.w(TAG, "sink failed", t)
        }
    }

    // ---------------- IP → 域名缓存 ----------------

    private fun cacheIp(addr: String, name: String, ttlSeconds: Int) {
        val ttl = ttlSeconds.coerceIn(5, 24 * 3600).toLong() * 1000
        synchronized(ipCache) {
            ipCache[addr] = CachedDomain(name, System.currentTimeMillis() + ttl)
        }
    }

    private fun lookupIp(addr: String): String? = synchronized(ipCache) {
        val entry = ipCache[addr] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            ipCache.remove(addr)
            return null
        }
        entry.name
    }

    private fun ipOf(dst: String): String? {
        if (dst.isBlank()) return null
        if (dst.startsWith("[")) {
            val end = dst.indexOf(']')
            if (end > 1) return dst.substring(1, end)
            return null
        }
        val idx = dst.lastIndexOf(':')
        return if (idx > 0) dst.substring(0, idx) else dst
    }

    // ---------------- 二进制解压 ----------------

    /**
     * 解压 privd 到**每次唯一的文件名**（`filesDir/privd-<ts>-<counter>`）。
     *
     * 根因：上一轮 privd 可能仍在该路径上执行，Linux 拒绝以写方式打开正在执行的
     * 可执行文件（ETXTBSY）。唯一文件名 + 写失败换名重试 + 旧文件 unlink 可彻底规避；
     * unlink 对正在执行的文件也有效（inode 保留到进程退出）。
     */
    private fun ensureBinary(): File? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
            ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: return null
        val assetPath = "bin/$abi/$ASSET_BIN"
        repeat(EXTRACT_ATTEMPTS) { attempt ->
            val stamp = "${System.currentTimeMillis()}-${binaryCounter.incrementAndGet()}"
            val out = File(context.filesDir, "$ASSET_BIN-$stamp")
            try {
                context.assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                // 只给 App 自身（owner）读/执行权限；root 不受 DAC 限制。
                out.setReadable(true, true)
                out.setExecutable(true, true)
                cleanupOldBinaries(keep = out)
                return out
            } catch (t: Throwable) {
                try {
                    out.delete()
                } catch (_: Throwable) {
                }
                Log.w(
                    TAG,
                    "extract privd attempt ${attempt + 1}/$EXTRACT_ATTEMPTS failed: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                if (attempt == EXTRACT_ATTEMPTS - 1) {
                    _lastStatus.value = "privd 解压失败: ${t.message}"
                } else {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }
        return null
    }

    /** 删除旧的解压产物（`privd` 与 `privd-*`），保留当前正在用的文件。 */
    private fun cleanupOldBinaries(keep: File) {
        try {
            val names = context.filesDir.list() ?: return
            for (name in names) {
                if (!name.startsWith(ASSET_BIN)) continue
                val f = File(context.filesDir, name)
                if (f.absolutePath == keep.absolutePath) continue
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
