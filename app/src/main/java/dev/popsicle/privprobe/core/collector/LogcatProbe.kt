package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * logcat 长连接探针。
 *
 * `logcat -v epoch -b main -b system -b events -T "$(date ...)"` 从「当前时刻」开始跟随，
 * 解析 ClipboardService / CameraService / AudioFlinger / LocationManager / MediaProjection
 * 等带包名或 uid 的行；纯噪行丢弃。同时承担前台应用（ActivityTaskManager START）解析。
 *
 * 性能（P0-1）：设备 logcat 实测 729–835 行/s，旧实现每行跑一次 ICU Regex/Matcher，
 * 是 11% 单核 + Native Heap 的主要来源。现在：
 * 1. 每行先做**纯 indexOf 关键词预过滤**（不建 Matcher），99% 的噪行直接丢弃；
 * 2. 预过滤通过后用手写字段切分（indexOf/substring）替代 Regex 解析 logcat 头部；
 * 3. 只有真正命中的行才跑提取用正则（package/uid 等）；
 * 4. 事件在本地攒批（≥16 条或 200ms）后一次 emitAll，减少协程切换。
 */
class LogcatProbe : Probe {

    override val id: ProbeId = ProbeId.APP_FOREGROUND
    override val statusIds: List<ProbeId> =
        listOf(ProbeId.APP_FOREGROUND, ProbeId.CLIPBOARD)

    private val seen = DedupeSet(8192)
    /** 性能 review：系统进程（uid<10000）的前台切换按「包 × 分钟」采样 1 条。 */
    private val systemForegroundSeen = DedupeSet(1024)
    private var lastForegroundPkg: String? = null
    private var lastForegroundAt = 0L
    private var emittedInWindow = 0
    private var windowStart = 0L
    private var parsedLines = 0L

    override suspend fun run(sink: ProbeSink) {
        var backoff = 1_000L
        while (true) {
            val stream = sink.openStream(CMD_LOGCAT)
            if (stream == null) {
                sink.status("logcat 无法启动（root 不可用）", healthy = false)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(20_000L)
                continue
            }
            var firstLineAt = 0L
            try {
                sink.status("logcat 已连接，等待事件")
                val startFloor = ProbeTime.now() - 5_000L
                // 设置变更会触发 ProbeManager 重启探针；一次连接内用快照 gate 足够。
                val gates = Gates.from(sink.settings)
                val pending = ArrayList<PrivacyEvent>(32)
                var lastFlushAt = ProbeTime.now()
                suspend fun flush() {
                    if (pending.isEmpty()) return
                    if (allowEmit()) sink.emitAll(ArrayList(pending))
                    pending.clear()
                }
                while (true) {
                    val line = stream.reader.readLine() ?: break
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (firstLineAt == 0L) firstLineAt = ProbeTime.now()
                    // 廉价预过滤：不相关行不建任何正则对象
                    if (line.isEmpty() || !gates.quickMatch(line)) continue
                    val parsed = parseLogLine(line) ?: continue
                    if (parsed.ts < startFloor) continue
                    parsedLines++
                    val events = parseEvent(parsed, sink, gates)
                    if (events.isNotEmpty()) {
                        pending.addAll(events)
                        val now = ProbeTime.now()
                        if (pending.size >= 16 || now - lastFlushAt >= 200L) {
                            flush()
                            lastFlushAt = now
                        }
                    }
                    if (parsedLines % 500L == 0L) {
                        sink.status("logcat 运行中，命中 $parsedLines 行")
                    }
                }
                flush()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (ie: java.io.InterruptedIOException) {
                // 停止采集时 RootShell 关闭流会中断 readLine，属正常退出
            } catch (t: Throwable) {
                // 流被主动关闭（停止采集）时不再是活进程，不当作错误上报
                if (stream.isAlive) sink.log("logcat read failed", t)
            } finally {
                runCatching { stream.close() }
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            sink.status("logcat 断开，${backoff}ms 后重连", healthy = false)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(20_000L)
        }
    }

    private fun allowEmit(): Boolean {
        val now = ProbeTime.now()
        if (now - windowStart > 1_000L) {
            windowStart = now
            emittedInWindow = 0
        }
        if (emittedInWindow >= 200) return false
        emittedInWindow++
        return true
    }

    // ------------------------------------------------------------ 廉价解析

    /** 大类开关快照；logcat 一个探针覆盖多类，必须逐类 gate（P1-7）。 */
    private class Gates(
        val clipboard: Boolean,
        val camera: Boolean,
        val audio: Boolean,
        val location: Boolean,
        val screen: Boolean,
        val appForeground: Boolean,
    ) {
        /** 纯 indexOf 预过滤：不分配正则/Matcher。 */
        fun quickMatch(line: String): Boolean {
            if (clipboard && (line.contains("Clipboard") || line.contains("clipboard"))) return true
            if (camera && line.contains("Camera")) return true
            if (audio && (line.contains("AudioFlinger") || line.contains("AudioRecord") ||
                    line.contains("AudioPolicy"))
            ) {
                return true
            }
            if (location && (line.contains("Location") || line.contains("Gnss"))) return true
            if (screen && line.contains("MediaProjection")) return true
            if (appForeground && (line.contains("ActivityTaskManager") || line.contains("ActivityManager"))) {
                return true
            }
            return false
        }

        companion object {
            fun from(s: AppSettings): Gates = Gates(
                clipboard = ProbeGates.isEnabled(s, PrivacyCategory.CLIPBOARD),
                camera = ProbeGates.isEnabled(s, PrivacyCategory.CAMERA),
                audio = ProbeGates.isEnabled(s, PrivacyCategory.MICROPHONE),
                location = ProbeGates.isEnabled(s, PrivacyCategory.LOCATION),
                screen = ProbeGates.isEnabled(s, PrivacyCategory.SCREEN),
                appForeground = ProbeGates.isProbeEnabled(s, ProbeId.APP_FOREGROUND),
            )
        }
    }

    private data class LogLine(
        val ts: Long,
        val pid: Int,
        val tag: String,
        val msg: String,
    )

    /**
     * 手写切分 `-v epoch` 行：`1234.567  1234  1234 I Tag     : message`。
     * 不做任何正则；格式不符返回 null（如 "--------- beginning of main"）。
     */
    private fun parseLogLine(line: String): LogLine? {
        val n = line.length
        if (n < 20) return null
        var i = 0
        while (i < n && line[i] == ' ') i++
        val tsStart = i
        while (i < n && (line[i] in '0'..'9' || line[i] == '.')) i++
        if (i == tsStart || i - tsStart > 20) return null
        val seconds = line.substring(tsStart, i).toDoubleOrNull() ?: return null
        while (i < n && line[i] == ' ') i++
        val pidStart = i
        while (i < n && line[i] in '0'..'9') i++
        val pid = if (i > pidStart) line.substring(pidStart, i).toIntOrNull() ?: -1 else -1
        while (i < n && line[i] == ' ') i++
        val tidStart = i
        while (i < n && line[i] in '0'..'9') i++
        if (i == tidStart) return null
        while (i < n && line[i] == ' ') i++
        if (i >= n || line[i] !in "VDIWEF") return null
        i++
        while (i < n && line[i] == ' ') i++
        val tagStart = i
        val colon = line.indexOf(':', i)
        if (colon <= tagStart) return null
        var tagEnd = colon
        while (tagEnd > tagStart && line[tagEnd - 1] == ' ') tagEnd--
        val tag = line.substring(tagStart, tagEnd)
        if (tag.isEmpty()) return null
        var msgStart = colon + 1
        if (msgStart < n && line[msgStart] == ' ') msgStart++
        val msg = if (msgStart < n) line.substring(msgStart) else ""
        return LogLine((seconds * 1000).toLong(), pid, tag, msg)
    }

    // ------------------------------------------------------------- 事件解析

    private fun parseEvent(line: LogLine, sink: ProbeSink, gates: Gates): List<PrivacyEvent> {
        val ts = line.ts
        val pid = line.pid
        val tag = line.tag
        val msg = line.msg
        val out = ArrayList<PrivacyEvent>(1)
        // 系统 wm/am 的包名前后台日志会误命中 location/projection 关键词，直接丢弃
        if (BLACKLIST.containsMatchIn(msg)) return emptyList()

        // ---------- 前台应用 ----------
        if (gates.appForeground && (tag.contains("ActivityTaskManager") || tag == "ActivityManager")) {
            if (msg.contains("START u0") || msg.contains("START u1") || msg.contains("Displayed ")) {
                val pkg = Regex("""cmp=([\w.]+)/""").find(msg)?.groupValues?.get(1)
                    ?: Regex("""cmp=([\w.]+)""").find(msg)?.groupValues?.get(1)
                    ?: Regex("""Displayed ([\w.]+)/""").find(msg)?.groupValues?.get(1)
                if (pkg != null && pkg.contains('.')) {
                    val fgUid = sink.uidForPackage(pkg)
                    // 系统进程前台切换只按「包 × 分钟」留 1 条，应用保持按 3s 窗口去重
                    val sampledOut = fgUid in 0..9999 &&
                        !systemForegroundSeen.addIfNew("$pkg|${ts / 60_000L}")
                    if (!sampledOut && (lastForegroundPkg != pkg || ts - lastForegroundAt > 3_000L)) {
                        lastForegroundPkg = pkg
                        lastForegroundAt = ts
                        out.add(
                            appEvent(
                                sink, ts, pid, pkg, "app_foreground",
                                "前台切换到 $pkg" + if (fgUid in 0..9999) "（系统进程按分钟采样）" else "",
                            ),
                        )
                    }
                }
            }
        }

        // ---------- 剪贴板 ----------
        if (gates.clipboard &&
            (tag.contains("Clipboard", ignoreCase = true) || msg.contains("clipboard", ignoreCase = true))
        ) {
            val pkg = ProbeParse.packageOf(msg)
            val uid = ProbeParse.uidOf(msg) ?: (if (pkg != null) sink.uidForPackage(pkg) else -1)
            if (pkg != null || uid > 0) {
                val key = "clip|$ts|$tag|${msg.hashCode()}"
                if (seen.addIfNew(key)) {
                    val read = msg.contains("Denying", ignoreCase = true) ||
                        msg.contains("read", ignoreCase = true) ||
                        msg.contains("paste", ignoreCase = true) ||
                        msg.contains("getPrimaryClip", ignoreCase = true) ||
                        msg.contains("access", ignoreCase = true)
                    out.add(
                        PrivacyEvent(
                            ts = ts,
                            category = PrivacyCategory.CLIPBOARD,
                            subType = if (read) "clipboard_read_log" else "clipboard_write_log",
                            uid = uid,
                            pid = pid,
                            packageName = pkg,
                            appLabel = sink.label(pkg, uid),
                            target = if (read) "clipboard:read" else "clipboard:write",
                            detail = msg.trim().take(240),
                            source = EventSource.LOGCAT,
                            confidence = 74,
                            direction = if (read) "read" else "write",
                        ),
                    )
                }
            }
        }

        // ---------- 相机 ----------
        if (gates.camera && (
                tag.contains("CameraService", ignoreCase = true) ||
                    (tag.contains("Camera", ignoreCase = true) && msg.contains("CameraService", ignoreCase = true))
                )
        ) {
            val pkg = ProbeParse.packageOf(msg)
            val uid = ProbeParse.uidOf(msg) ?: (if (pkg != null) sink.uidForPackage(pkg) else -1)
            val logPid = ProbeParse.pidOf(msg)
                ?: Regex("""\(PID\s+(\d+)\)""").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                ?: pid
            val connect = msg.contains("connect", ignoreCase = true) || msg.contains("open", ignoreCase = true)
            val disconnect = msg.contains("disconnect", ignoreCase = true) || msg.contains("close", ignoreCase = true)
            if ((pkg != null || uid > 0) && (connect || disconnect)) {
                val key = "cam|$ts|$tag|${msg.hashCode()}"
                if (seen.addIfNew(key)) {
                    out.add(
                        PrivacyEvent(
                            ts = ts,
                            category = PrivacyCategory.CAMERA,
                            subType = if (disconnect) "camera_log_disconnect" else "camera_log_connect",
                            uid = uid,
                            pid = logPid,
                            packageName = pkg,
                            appLabel = sink.label(pkg, uid),
                            target = "camera",
                            detail = msg.trim().take(240),
                            source = EventSource.LOGCAT,
                            confidence = 70,
                        ),
                    )
                }
            }
        }

        // ---------- 音频输入 ----------
        if (gates.audio && (
                tag.contains("AudioFlinger", ignoreCase = true) ||
                    tag.contains("AudioRecord", ignoreCase = true) ||
                    tag.contains("AudioPolicy", ignoreCase = true)
                )
        ) {
            if (msg.contains("record", ignoreCase = true) ||
                msg.contains("input", ignoreCase = true) ||
                msg.contains("MIC", ignoreCase = true)
            ) {
                val pkg = ProbeParse.packageOf(msg)
                val uid = ProbeParse.uidOf(msg) ?: (if (pkg != null) sink.uidForPackage(pkg) else -1)
                if (pkg != null || uid > 0) {
                    val key = "aud|$ts|$tag|${msg.hashCode()}"
                    if (seen.addIfNew(key)) {
                        out.add(
                            PrivacyEvent(
                                ts = ts,
                                category = PrivacyCategory.MICROPHONE,
                                subType = "audio_record_log",
                                uid = uid,
                                pid = pid,
                                packageName = pkg,
                                appLabel = sink.label(pkg, uid),
                                target = "mic:record",
                                detail = msg.trim().take(240),
                                source = EventSource.LOGCAT,
                                confidence = 68,
                            ),
                        )
                    }
                }
            }
        }

        // ---------- 定位 ----------
        if (gates.location &&
            (tag.contains("Location", ignoreCase = true) || tag.contains("Gnss", ignoreCase = true)) &&
            LOCATION_ALLOW.containsMatchIn(msg) &&
            !BLACKLIST.containsMatchIn(msg)
        ) {
            val pkg = ProbeParse.packageOf(msg)
            val uid = ProbeParse.uidOf(msg) ?: (if (pkg != null) sink.uidForPackage(pkg) else -1)
            if (pkg != null || uid > 0) {
                val provider = Regex("""provider\s*[=:]?\s*(gps|network|fused|passive)""", RegexOption.IGNORE_CASE)
                    .find(msg)?.groupValues?.get(1)?.lowercase()
                val target = if (provider != null) "location:$provider" else "location:request"
                val key = "loc|$ts|$tag|${pid}|${msg.hashCode()}"
                if (seen.addIfNew(key)) {
                    out.add(
                        PrivacyEvent(
                            ts = ts,
                            category = PrivacyCategory.LOCATION,
                            subType = "location_log",
                            uid = uid,
                            pid = pid,
                            packageName = pkg,
                            appLabel = sink.label(pkg, uid),
                            target = target,
                            detail = msg.trim().take(240),
                            source = EventSource.LOGCAT,
                            confidence = 68,
                        ),
                    )
                }
            }
        }

        // ---------- 屏幕采集 ----------
        if (gates.screen &&
            tag.contains("MediaProjection", ignoreCase = true) &&
            PROJECTION_ALLOW.containsMatchIn(msg) &&
            !BLACKLIST.containsMatchIn(msg)
        ) {
            val pkg = ProbeParse.packageOf(msg)
            val uid = ProbeParse.uidOf(msg) ?: (if (pkg != null) sink.uidForPackage(pkg) else -1)
            val key = "scr|$ts|$tag|${pid}|${msg.hashCode()}"
            if (seen.addIfNew(key)) {
                out.add(
                    PrivacyEvent(
                        ts = ts,
                        category = PrivacyCategory.SCREEN,
                        subType = "projection_log",
                        uid = uid,
                        pid = pid,
                        packageName = pkg,
                        appLabel = sink.label(pkg, uid),
                        target = "media_projection",
                        detail = msg.trim().take(240),
                        source = EventSource.LOGCAT,
                        confidence = 70,
                    ),
                )
            }
        }
        return out
    }

    private fun appEvent(
        sink: ProbeSink,
        ts: Long,
        pid: Int?,
        pkg: String,
        subType: String,
        detail: String,
    ): PrivacyEvent {
        val uid = sink.uidForPackage(pkg)
        return PrivacyEvent(
            ts = ts,
            category = PrivacyCategory.APP_ACTIVITY,
            subType = subType,
            uid = uid,
            pid = pid,
            packageName = pkg,
            appLabel = sink.label(pkg, uid),
            target = pkg,
            detail = detail,
            source = EventSource.LOGCAT,
            confidence = 80,
        )
    }

    companion object {
        /** 非隐私访问的系统状态广播日志（logcat 里 location/projection 字样的主要噪声源）。 */
        private val BLACKLIST = Regex(
            """handleAppForeground|handleAppBackground|handleForegroundServicesChanged|newFgApp|newBgApp|ForegroundServicesChanged""",
            RegexOption.IGNORE_CASE,
        )

        /** 定位访问白名单：只认真正请求/注入定位的日志。 */
        private val LOCATION_ALLOW = Regex(
            """requestLocationUpdates|removeLocationUpdates|injectLocation|LocationManagerService|location account change|getLastLocation|registerLocationListener|requestLocation\(|deliverLocation|onLocationChanged""",
            RegexOption.IGNORE_CASE,
        )

        /** MediaProjection 会话动作白名单。 */
        private val PROJECTION_ALLOW = Regex(
            """createProjection|startProjection|registerCallback|grant|projection started|MediaProjection\{""",
            RegexOption.IGNORE_CASE,
        )

        const val CMD_LOGCAT: String =
            "logcat -v epoch -b main -b system -b events -T \"\$(date '+%m-%d %H:%M:%S.000')\""
    }
}
