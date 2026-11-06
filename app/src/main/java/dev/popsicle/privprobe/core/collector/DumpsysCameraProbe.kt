package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * 相机探针：轮询 `dumpsys media.camera` 的活跃客户端 + Camera service events log。
 *
 * 设备事实：完整 dump 约 4.9MB / 0.9s，几乎全是 HAL 日志；用 sed 只截取前两段并在
 * variance log 处提前退出（dumpsys 收到 SIGPIPE 立即停止写），实测 0.02s。
 */
class DumpsysCameraProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.CAMERA

    private val seenLogLines = DedupeSet(2048)
    private val active = HashMap<String, Long>() // key -> 首次出现 ts
    private val connectTimes = HashMap<String, Long>() // pid|pkg|device -> connect ts
    private var firstPoll = true

    override fun intervalMs(settings: AppSettings): Long =
        settings.pollIntervalMs.coerceAtLeast(1_500L) * 2

    override suspend fun poll(sink: ProbeSink) {
        if (!ProbeGates.isProbeEnabled(sink.settings, ProbeId.CAMERA)) {
            sink.status("相机采集已关闭")
            return
        }
        val lines = sink.lines(CMD_CAMERA, 12_000)
        if (lines == null) {
            sink.status("dumpsys media.camera 失败（root/服务不可用）", healthy = false)
            return
        }
        val events = ArrayList<PrivacyEvent>()
        val now = ProbeTime.now()
        val recentFloor = now - if (firstPoll) 10 * 60_000L else 5 * 60_000L
        val stillActive = HashSet<String>()
        var section = 0
        var activeCount = 0

        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.startsWith("Active Camera Clients") -> { section = 1; continue }
                line.startsWith("Allowed user IDs") -> { section = 0; continue }
                line.startsWith("== Camera service events log") -> { section = 2; continue }
                line.startsWith("== Camera service variance log") -> { section = 0; continue }
            }
            when (section) {
                1 -> {
                    if (line.startsWith("[")) continue
                    val id = Regex("""Camera ID:\s*(\d+)""").find(line)?.groupValues?.get(1) ?: continue
                    val pid = Regex("""\bPID:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val pkg = Regex("""Client Package Name:\s*([\w.]+)""").find(line)?.groupValues?.get(1)
                    val key = activeKey(id, pid, pkg)
                    activeCount++
                    stillActive.add(key)
                    if (active.putIfAbsent(key, now) == null) {
                        events.add(
                            makeEvent(
                                sink = sink,
                                ts = now,
                                subType = "camera_active",
                                uid = uidOf(sink, pkg),
                                pid = pid,
                                pkg = pkg,
                                target = "camera $id",
                                detail = "相机 $id 正在被使用" +
                                    (pkg?.let { "：$it" } ?: "") +
                                    (pid?.let { " (PID $it)" } ?: ""),
                                confidence = 88,
                            ),
                        )
                    }
                }

                2 -> {
                    val m = EVENT_LINE.find(line) ?: continue
                    val timeText = m.groupValues[1]
                    val action = m.groupValues[2]
                    val rest = m.groupValues[3]
                    if (action !in setOf("CONNECT", "DISCONNECT", "DIED", "REJECT")) continue
                    val ts = ProbeTime.parseMmDdHhmmss(timeText) ?: continue
                    if (ts < recentFloor) continue
                    val device = Regex("""device\s+(\d+)""").find(rest)?.groupValues?.get(1)
                    val pkg = Regex("""package\s+([\w.]+)""").find(rest)?.groupValues?.get(1)
                        ?: Regex("""DIED client\s+([\w.]+)""").find(rest)?.groupValues?.get(1)
                    val pid = Regex("""\(PID\s+(\d+)\)""").find(rest)?.groupValues?.get(1)?.toIntOrNull()
                    val target = if (device != null) "camera $device" else "camera"
                    val key = "$ts|$action|$device|$pid|$pkg"
                    if (!seenLogLines.addIfNew(key)) continue
                    val connKey = "$pid|$pkg|$device"
                    val duration: Long? = when (action) {
                        "CONNECT" -> {
                            connectTimes[connKey] = ts
                            null
                        }
                        "DISCONNECT", "DIED" -> connectTimes.remove(connKey)?.let { (ts - it).coerceAtLeast(0) }
                        else -> null
                    }
                    events.add(
                        makeEvent(
                            sink = sink,
                            ts = ts,
                            subType = when (action) {
                                "CONNECT" -> "camera_connect"
                                "DISCONNECT" -> "camera_disconnect"
                                "DIED" -> "camera_died"
                                else -> "camera_reject"
                            },
                            uid = uidOf(sink, pkg),
                            pid = pid,
                            pkg = pkg,
                            target = target,
                            detail = line.trim().take(200),
                            confidence = if (action == "CONNECT") 90 else 82,
                            duration = duration,
                        ),
                    )
                }
            }
        }

        // 活跃客户端释放：补一条 closed 事件（含持有 duration）
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val (key, startTs) = iterator.next()
            if (key !in stillActive) {
                iterator.remove()
                val parts = key.split('|')
                val device = parts.getOrNull(1) ?: "?"
                val pid = parts.getOrNull(2)?.toIntOrNull()
                val pkg = parts.getOrNull(3)?.takeIf { it.isNotBlank() && it != "null" }
                events.add(
                    makeEvent(
                        sink = sink,
                        ts = now,
                        subType = "camera_closed",
                        uid = uidOf(sink, pkg),
                        pid = pid,
                        pkg = pkg,
                        target = "camera $device",
                        detail = "相机 $device 释放（持有 ${(now - startTs) / 1000}s）",
                        confidence = 82,
                        duration = (now - startTs).coerceAtLeast(0),
                    ),
                )
            }
        }

        if (events.isNotEmpty()) sink.emitAll(events)
        firstPoll = false
        val activeText = if (activeCount == 0) "无活跃客户端" else "$activeCount 个活跃客户端"
        sink.status("相机服务：$activeText，事件缓存 ${seenLogLines.size} 条")
    }

    private fun activeKey(device: String, pid: Int?, pkg: String?): String =
        "active|$device|${pid ?: -1}|${pkg ?: "null"}"

    private fun uidOf(sink: ProbeSink, pkg: String?): Int {
        if (pkg != null) {
            val uid = sink.uidForPackage(pkg)
            if (uid > 0) return uid
        }
        return -1
    }

    private fun makeEvent(
        sink: ProbeSink,
        ts: Long,
        subType: String,
        uid: Int,
        pid: Int?,
        pkg: String?,
        target: String,
        detail: String,
        confidence: Int,
        duration: Long? = null,
    ): PrivacyEvent = PrivacyEvent(
        ts = ts,
        category = PrivacyCategory.CAMERA,
        subType = subType,
        uid = uid,
        pid = pid,
        packageName = pkg,
        appLabel = if (pkg != null || uid > 0) sink.label(pkg, uid) else null,
        target = target,
        detail = detail,
        source = EventSource.DUMPSYS,
        confidence = confidence,
        durationMs = duration,
    )

    companion object {
        private val EVENT_LINE = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*:\s*(\w+)\s+(.*)$""")
        const val CMD_CAMERA: String =
            "dumpsys media.camera | sed -n " +
                "-e '/Active Camera Clients/,/Allowed user IDs/p' " +
                "-e '/Camera service events log/,/Camera service variance log/{p; /Camera service variance log/q}'"
    }
}
