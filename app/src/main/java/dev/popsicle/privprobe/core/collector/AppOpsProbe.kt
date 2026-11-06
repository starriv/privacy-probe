package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * AppOps 审计探针：低频（默认 15s）全量 `dumpsys appops`（设备实测 ~2.1MB / 60k 行）。
 *
 * 解析结构：
 * ```
 *   Uid u0a150:                       ← 10150 = 10000 + 150
 *     Package com.android.camera:
 *       CAMERA (allow):
 *         null=[
 *           Access: [top-s] 2026-11-06 01:03:50.769 (-17m34s746ms) duration=+2s74ms
 *           Running start at: +14h30m48s155ms
 *         ]
 * ```
 * 只对敏感 op 做增量：按 (uid, 包, op) 记住「已见过的最大 access 时间」，新访问才发射事件。
 * 线程数/文件大小都很大，这里用 startsWith 快速分流后再跑正则，避免每行多正则拖慢。
 */
class AppOpsProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.APPOPS
    override val statusIds: List<ProbeId> = listOf(ProbeId.APPOPS, ProbeId.CLIPBOARD)

    private data class OpMapping(val category: PrivacyCategory, val target: String, val direction: String? = null)

    private val lastAccess = HashMap<String, Long>()
    private val seenAccess = DedupeSet(16384)
    private val runningSince = HashMap<String, Long>()
    private var firstPoll = true
    private var seenLines = 0

    override fun intervalMs(settings: AppSettings): Long =
        // 性能 review：全量 dumpsys appops 143ms/2.2MB @15s → 60s 起步，显著降低 root 串行占用。
        settings.appOpsIntervalMs.coerceAtLeast(60_000L)

    override suspend fun poll(sink: ProbeSink) {
        val lines = sink.lines(CMD_APPOPS, 25_000)
        if (lines == null) {
            sink.status("dumpsys appops 失败（root 不可用）", healthy = false)
            return
        }
        val now = ProbeTime.now()
        // 首轮最多导入最近 5min 的访问（性能 review：首轮 24h → 5min，避免一次导入几百条历史）；
        // 更早的历史只记 lastSeen，不把「最后一次访问」当新事件。
        val firstFloor = now - 5 * 60_000L
        val oldestAllowed = now - 24 * 60 * 60_000L
        val events = ArrayList<PrivacyEvent>()
        val runningNow = HashSet<String>()

        var currentUid = -1
        var currentPackage: String? = null
        var currentOp: String? = null
        var currentOpMode: String = ""
        var currentBlockRunning: String? = null

        fun blockKey(): String? {
            val op = currentOp ?: return null
            val map = mapping(op) ?: return null
            // P1-7：大类开关关闭时不再跟踪运行态（Logcat/AppOps 覆盖多类，必须逐类 gate）。
            if (!ProbeGates.isEnabled(sink.settings, map.category)) return null
            return "$currentUid|${currentPackage ?: "null"}|$op"
        }

        fun finalizeBlock() {
            val key = blockKey()
            if (key != null && currentBlockRunning != null) {
                runningNow.add(key)
                if (runningSince.putIfAbsent(key, now) == null) {
                    events.add(
                        runningEvent(sink, now, key, currentOp!!, currentOpMode, currentBlockRunning!!),
                    )
                }
            }
            currentBlockRunning = null
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            seenLines++
            when {
                line.startsWith("  Uid ") -> {
                    finalizeBlock()
                    val m = UID_LINE.find(line)
                    currentUid = when {
                        m == null -> -1
                        m.groupValues[1].startsWith("u0a") -> 10_000 + (m.groupValues[1].removePrefix("u0a").toIntOrNull() ?: 0)
                        else -> m.groupValues[1].toIntOrNull() ?: -1
                    }
                    currentPackage = null
                    currentOp = null
                }

                line.startsWith("    Package ") -> {
                    finalizeBlock()
                    currentPackage = line.trim().removePrefix("Package ").removeSuffix(":").trim()
                    currentOp = null
                }

                line.startsWith("      ") && !line.startsWith("        ") -> {
                    finalizeBlock()
                    val m = OP_LINE.find(line)
                    if (m != null) {
                        currentOp = m.groupValues[1]
                        currentOpMode = m.groupValues[2]
                    } else {
                        currentOp = null
                    }
                }

                line.startsWith("        ") -> {
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("Access") || trimmed.startsWith("Reject") -> {
                            val m = ACCESS_LINE.find(line) ?: continue
                            val kind = m.groupValues[1]
                            val flags = m.groupValues[2]
                            val tsText = m.groupValues[3]
                            val durationText = m.groupValues[5]
                            val op = currentOp ?: continue
                            val map = mapping(op) ?: continue
                            if (!ProbeGates.isEnabled(sink.settings, map.category)) continue
                            val ts = ProbeTime.parseAppOpsTs(tsText) ?: continue
                            val pkg = currentPackage
                            // 任何情况下都不导入超过 24h 的历史记录
                            if (ts < oldestAllowed) continue
                            val key = "$currentUid|${pkg ?: "null"}|$op"
                            val prev = lastAccess[key]
                            val floor = if (prev != null) prev else if (firstPoll) firstFloor else now - 10 * 60_000L
                            lastAccess[key] = maxOf(prev ?: 0L, ts)
                            // (uid, 包, op, accessTs) 只发一次，service 重启也不会重复导入
                            if (!seenAccess.addIfNew("$key|$ts|$kind")) continue
                            if (ts <= floor) continue
                            val durationMs = parseDuration(durationText)
                            val isReject = kind == "Reject"
                            events.add(
                                PrivacyEvent(
                                    ts = ts,
                                    category = map.category,
                                    subType = if (isReject) "appops_reject" else "appops_access",
                                    uid = currentUid,
                                    packageName = pkg,
                                    appLabel = sink.label(pkg, currentUid),
                                    target = map.target,
                                    detail = buildString {
                                        append("AppOps $op")
                                        append(if (isReject) " 被拒绝" else " 被访问")
                                        append("（mode=$currentOpMode, flags=$flags）")
                                        durationText?.let { append("，duration=+$it") }
                                    },
                                    source = EventSource.APPOPS,
                                    confidence = if (isReject) 70 else 90,
                                    durationMs = durationMs,
                                    direction = map.direction,
                                ),
                            )
                        }

                        trimmed.startsWith("Running start at:") -> {
                            val v = trimmed.removePrefix("Running start at:").trim()
                            if (currentOp != null && mapping(currentOp!!) != null) {
                                currentBlockRunning = v
                            }
                        }
                    }
                }
            }
        }
        finalizeBlock()

        // 运行态结束（上次在跑、这次不跑了）
        val stopped = runningSince.keys.filter { it !in runningNow }
        stopped.forEach { key ->
            val since = runningSince.remove(key) ?: return@forEach
            val parts = key.split('|')
            val op = parts.getOrNull(2) ?: return@forEach
            val map = mapping(op) ?: return@forEach
            if (!ProbeGates.isEnabled(sink.settings, map.category)) return@forEach
            val pkg = parts.getOrNull(1)?.takeIf { it != "null" }
            val uid = parts.getOrNull(0)?.toIntOrNull() ?: -1
            events.add(
                PrivacyEvent(
                    ts = now,
                    category = map.category,
                    subType = "appops_stop",
                    uid = uid,
                    packageName = pkg,
                    appLabel = sink.label(pkg, uid),
                    target = map.target,
                    detail = "AppOps $op 运行态结束（持续 ${(now - since) / 1000}s）",
                    source = EventSource.APPOPS,
                    confidence = 82,
                    durationMs = (now - since).coerceAtLeast(0),
                ),
            )
        }
        // 仍在跑但上一轮已发过 running 的不用重复
        runningSince.keys.filter { it in runningNow }.forEach { key ->
            if (runningSince[key] == null) runningSince[key] = now
        }

        firstPoll = false
        if (events.isNotEmpty()) sink.emitAll(events)
        sink.status("AppOps：解析 $seenLines 行，新增 ${events.size} 条敏感访问/运行事件")
    }

    private fun runningEvent(
        sink: ProbeSink,
        now: Long,
        key: String,
        op: String,
        mode: String,
        raw: String,
    ): PrivacyEvent {
        val parts = key.split('|')
        val pkg = parts.getOrNull(1)?.takeIf { it != "null" }
        val uid = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val map = mapping(op)!!
        return PrivacyEvent(
            ts = now,
            category = map.category,
            subType = "appops_running",
            uid = uid,
            packageName = pkg,
            appLabel = sink.label(pkg, uid),
            target = map.target,
            detail = "AppOps $op 处于运行中（mode=$mode，Running start at: $raw）",
            source = EventSource.APPOPS,
            confidence = 85,
            direction = map.direction,
        )
    }

    /** duration=+2s74ms / +14h30m48s155ms / +656ms → 毫秒。 */
    private fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var ms = 0L
        val h = Regex("""(\d+)h""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val m = Regex("""(\d+)m""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val s = Regex("""(\d+)s""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val milli = Regex("""(\d+)ms""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        ms += h * 3_600_000 + m * 60_000 + s * 1_000 + milli
        return ms.takeIf { it > 0 }
    }

    private fun mapping(op: String): OpMapping? = when (op) {
        "CAMERA" -> OpMapping(PrivacyCategory.CAMERA, "camera")
        "RECORD_AUDIO", "PHONE_CALL_MICROPHONE" ->
            OpMapping(PrivacyCategory.MICROPHONE, "record_audio")
        "FINE_LOCATION", "COARSE_LOCATION", "GPS", "ACCESS_MEDIA_LOCATION" ->
            OpMapping(PrivacyCategory.LOCATION, op.lowercase())
        "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR" ->
            OpMapping(PrivacyCategory.CONTACTS, op.lowercase())
        "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG",
        "CALL_PHONE", "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "PROCESS_OUTGOING_CALLS",
        "ANSWER_PHONE_CALLS", -> OpMapping(PrivacyCategory.TELEPHONY, op.lowercase())
        "READ_CLIPBOARD" -> OpMapping(PrivacyCategory.CLIPBOARD, "read_clipboard", "read")
        "WRITE_CLIPBOARD" -> OpMapping(PrivacyCategory.CLIPBOARD, "write_clipboard", "write")
        "GET_ACCOUNTS", "USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER", "READ_DEVICE_IDENTIFIERS" ->
            OpMapping(PrivacyCategory.DEVICE_ID, op.lowercase())
        "BODY_SENSORS" -> OpMapping(PrivacyCategory.SENSOR, "body_sensors")
        else -> null
    }

    companion object {
        private val UID_LINE = Regex("""^\s+Uid\s+(\S+):\s*$""")
        private val OP_LINE = Regex("""^\s{6}(\w+(?:\(\d+\))?)\s+\((.*)\):\s*$""")
        private val ACCESS_LINE = Regex(
            """^\s+(Access|Reject):\s*\[([^\]]*)\]\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s*\(([^)]*)\)(?:\s+duration=\+(\S+))?""",
        )
        const val CMD_APPOPS: String = "dumpsys appops"
    }
}
