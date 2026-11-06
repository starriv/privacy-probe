package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * 麦克风探针。
 *
 * 主数据源：`dumpsys audio` 的 RecordActivityMonitor / Recording Activity 段，
 * 实测行格式：`11-06 01:03:48:064 rec start riid:1063 uid:10150 session:1057 src:MIC not silenced pack:com.android.camera`
 * 次数据源：录音活跃时读取 `dumpsys media.audio_flinger` 的 Input thread（音频格式/输入源），
 * 用于补充「确实打开了音频输入线程」这一事实。
 */
class DumpsysAudioProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.AUDIO

    private val seen = DedupeSet(4096)
    private val activeSessions = HashMap<String, Long>() // session/riid -> start ts
    private val seenInputThreads = DedupeSet(256)
    private var lastActiveUid = -1
    private var lastActivePkg: String? = null
    private var lastActiveTarget: String? = null
    private var lastActiveAt = 0L

    override fun intervalMs(settings: AppSettings): Long =
        // 性能 review：常规轮询降频；audio_flinger 只在有录音活动时才补读。
        settings.pollIntervalMs.coerceAtLeast(3_000L)

    override suspend fun poll(sink: ProbeSink) {
        if (!ProbeGates.isProbeEnabled(sink.settings, ProbeId.AUDIO)) {
            sink.status("麦克风采集已关闭")
            return
        }
        val lines = sink.lines(CMD_RECORD_ACTIVITY, 10_000)
        if (lines == null) {
            sink.status("dumpsys audio 失败（root 不可用）", healthy = false)
            return
        }
        val events = ArrayList<PrivacyEvent>()
        val now = ProbeTime.now()
        var startedHere = 0

        for (raw in lines) {
            val line = raw.trim()
            if (!line.contains(" rec ")) continue
            val m = REC_LINE.find(line) ?: continue
            val ts = parseRecTs(m.groupValues[1]) ?: continue
            val action = m.groupValues[2]
            val riid = m.groupValues[3]
            val uid = m.groupValues[4].toIntOrNull() ?: -1
            val session = m.groupValues[5]
            val src = m.groupValues[6]
            val pack = m.groupValues[7].takeIf { it != "null" && it.contains('.') }
            val key = "$ts|$action|$riid|$session"
            if (!seen.addIfNew(key)) continue
            if (action != "start" && action != "stop" && action != "restart") continue
            val sessionKey = "$riid|$session"
            var duration: Long? = null
            when (action) {
                "start", "restart" -> {
                    activeSessions[sessionKey] = ts
                    startedHere++
                    lastActiveUid = uid
                    lastActivePkg = pack
                    lastActiveTarget = src
                    lastActiveAt = now
                }
                "stop" -> {
                    duration = activeSessions.remove(sessionKey)?.let { (ts - it).coerceAtLeast(0) }
                    if (activeSessions.isEmpty()) {
                        lastActiveUid = -1
                        lastActivePkg = null
                    }
                }
            }
            events.add(
                PrivacyEvent(
                    ts = ts,
                    category = PrivacyCategory.MICROPHONE,
                    subType = if (action == "stop") "record_stop" else "record_start",
                    uid = uid,
                    pid = null,
                    packageName = pack,
                    appLabel = sink.label(pack, uid),
                    target = "mic:$src",
                    detail = buildString {
                        append(if (action == "stop") "停止录音" else "开始录音")
                        append("，音频源 $src，session $session")
                        pack?.let { append("，$it") }
                        if (duration != null) append("，持续 ${duration / 1000.0}s")
                    },
                    source = EventSource.DUMPSYS,
                    confidence = 92,
                    durationMs = duration,
                ),
            )
        }

        // 录音活跃时补读 audio_flinger 的输入线程
        if (activeSessions.isNotEmpty() || startedHere > 0) {
            val flinger = sink.lines(CMD_INPUT_THREADS, 8_000)
            if (flinger != null && flinger.isNotEmpty()) {
                val sourceName = flinger.firstNotNullOfOrNull { line ->
                    Regex("""Audio source:\s*\d+\s*\(([^)]+)\)""").find(line)?.groupValues?.get(1)
                }
                var threadName: String? = null
                for (line in flinger) {
                    val tm = INPUT_THREAD.find(line)
                    if (tm != null) {
                        threadName = tm.groupValues[1]
                        val tid = tm.groupValues[2]
                        val type = tm.groupValues[3]
                        if (seenInputThreads.addIfNew("$tid")) {
                            events.add(
                                PrivacyEvent(
                                    ts = now,
                                    category = PrivacyCategory.MICROPHONE,
                                    subType = "audio_input_thread",
                                    uid = if (lastActiveUid > 0) lastActiveUid else -1,
                                    packageName = lastActivePkg,
                                    appLabel = sink.label(lastActivePkg, lastActiveUid),
                                    target = "audio_in:$type",
                                    detail = "AudioFlinger 输入线程 $threadName (tid $tid)，音频源 ${sourceName ?: "未知"}",
                                    source = EventSource.DUMPSYS,
                                    confidence = 68,
                                ),
                            )
                        }
                    }
                }
                if (threadName != null) {
                    sink.status("录音输入线程活跃：$threadName，音频源 ${sourceName ?: "未知"}")
                }
            }
        }

        if (events.isNotEmpty()) sink.emitAll(events)
        val activeText = when {
            activeSessions.isNotEmpty() -> "${activeSessions.size} 路录音进行中"
            lastActiveAt > now - 120_000L -> "最近有录音（${lastActivePkg ?: "uid $lastActiveUid"}）"
            else -> "无录音活动"
        }
        sink.status("音频服务：$activeText")
    }

    /** `11-06 01:03:48:064` → epoch（本地时区，毫秒）。 */
    private fun parseRecTs(text: String): Long? {
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        val base = ProbeTime.parseMmDdHhmmss(text.substring(0, idx)) ?: return null
        val ms = text.substring(idx + 1).toLongOrNull() ?: 0L
        return base + ms
    }

    companion object {
        private val REC_LINE = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}) rec (\w+) riid:(\d+) uid:(\d+) session:(\d+) src:(\w+).*?pack:(\S+)""",
        )
        private val INPUT_THREAD = Regex("""^Input thread .*?name (\S+), tid (\d+), type \d+ \((\w+)\)""")
        const val CMD_RECORD_ACTIVITY: String =
            "dumpsys audio | sed -n '/### Recording Activity/,/mHardeningOverride/p'"
        const val CMD_INPUT_THREADS: String =
            "dumpsys media.audio_flinger | sed -n '/Input thread/,/Output thread/p'"
    }
}
