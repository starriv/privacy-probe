package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * 屏幕采集探针：轮询 `dumpsys media_projection` 解析活跃 MediaProjection 会话。
 *
 * 设备事实：无会话时输出 `Media Projection: \nnull`；有会话时是 MediaProjection 的 dump，
 * 不同 ROM 字段略有差异，这里用宽松正则抽取 uid/pid/package，抽不到也保留「会话活跃」事件。
 */
class DumpsysProjectionProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.SCREEN

    private val active = HashMap<String, Long>()
    private var lastRaw: String? = null

    override fun intervalMs(settings: AppSettings): Long =
        settings.pollIntervalMs.coerceAtLeast(2_000L)

    override suspend fun poll(sink: ProbeSink) {
        if (!ProbeGates.isProbeEnabled(sink.settings, ProbeId.SCREEN)) {
            sink.status("屏幕采集已关闭")
            return
        }
        val lines = sink.lines(CMD_PROJECTION, 8_000)
        if (lines == null) {
            sink.status("dumpsys media_projection 失败（root 不可用）", healthy = false)
            return
        }
        val text = lines.joinToString("\n")
        val now = ProbeTime.now()
        val afterHeader = text.substringAfter("Media Projection:", text.substringAfter(':', ""))
        val body = afterHeader.trim()
        val isNull = body.isBlank() || body.lineSequence().firstOrNull()?.trim() == "null"
        val events = ArrayList<PrivacyEvent>()

        if (isNull) {
            // 会话结束
            val it = active.entries.iterator()
            while (it.hasNext()) {
                val (key, start) = it.next()
                it.remove()
                val parts = key.split('|')
                events.add(
                    PrivacyEvent(
                        ts = now,
                        category = PrivacyCategory.SCREEN,
                        subType = "projection_stop",
                        uid = parts.getOrNull(0)?.toIntOrNull() ?: -1,
                        pid = parts.getOrNull(1)?.toIntOrNull(),
                        packageName = parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "null" },
                        appLabel = sink.label(parts.getOrNull(2), parts.getOrNull(0)?.toIntOrNull()),
                        target = "media_projection",
                        detail = "MediaProjection 会话结束（持续 ${(now - start) / 1000}s）",
                        source = EventSource.DUMPSYS,
                        confidence = 85,
                        durationMs = (now - start).coerceAtLeast(0),
                    ),
                )
            }
        } else {
            val uid = Regex("""(?:mOwnerUid|ownerUid|owner_uid|uid)\s*[=:]\s*(\d+)""")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\(uid\s*[=:]\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val pid = Regex("""(?:mOwnerPid|ownerPid|owner_pid|pid)\s*[=:]\s*(\d+)""")
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
            val pkg = Regex("""(?:packageName|mPackageName|package|pkg)\s*[=:]\s*([\w.]+)""")
                .find(body)?.groupValues?.get(1)
                ?: Regex("""(?:^|\s)([a-z][\w]*(?:\.[\w]+){2,})(?:\s|,)""").find(body)
                    ?.groupValues?.get(1)
            val key = "${uid ?: -1}|${pid ?: -1}|${pkg ?: "null"}"
            if (active.putIfAbsent(key, now) == null) {
                events.add(
                    PrivacyEvent(
                        ts = now,
                        category = PrivacyCategory.SCREEN,
                        subType = "projection_start",
                        uid = uid ?: -1,
                        pid = pid,
                        packageName = pkg,
                        appLabel = sink.label(pkg, uid),
                        target = "media_projection",
                        detail = "MediaProjection 会话活跃" + (pkg?.let { "：$it" } ?: ""),
                        source = EventSource.DUMPSYS,
                        confidence = if (uid != null || pkg != null) 88 else 62,
                    ),
                )
            }
            lastRaw = body.take(800)
        }
        if (events.isNotEmpty()) sink.emitAll(events)
        sink.status(
            if (isNull) "屏幕采集：无活跃 MediaProjection 会话"
            else "屏幕采集：${active.size} 个活跃会话${lastRaw?.let { "" } ?: ""}",
        )
    }

    companion object {
        const val CMD_PROJECTION: String = "dumpsys media_projection"
    }
}
