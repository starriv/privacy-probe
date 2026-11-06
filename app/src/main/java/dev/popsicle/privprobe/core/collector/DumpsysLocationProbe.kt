package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * 定位探针：解析 `dumpsys location`。
 *
 * 两类数据：
 * 1. `Location Providers` 段的监听器注册：`10135/com.google.android.gms[fused_location_provider]/CD56E663 Request[...]`
 *    —— 每个 (uid, 包, provider, binder) 只发一次注册事件。
 * 2. `Historical Aggregate Location Provider Data` 段的 `locations = N`（开机以来累计投递数）
 *    —— 与上次轮询做差，增量 > 0 才发事件（真实拿到定位结果）。
 *
 * 注：`dumpsys location -a` 是 `dumpsys location` 的超集，这里只跑后者以节省开销。
 */
class DumpsysLocationProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.LOCATION

    private val seenListeners = DedupeSet(4096)
    private val lastCounts = HashMap<String, Long>()
    private var seeded = false

    override fun intervalMs(settings: AppSettings): Long =
        // 性能 review：location dump 162ms/1.2MB @2s → 10s + sed 只取两个目标段
        settings.pollIntervalMs.coerceAtLeast(10_000L)

    override suspend fun poll(sink: ProbeSink) {
        if (!ProbeGates.isProbeEnabled(sink.settings, ProbeId.LOCATION)) {
            sink.status("定位采集已关闭")
            return
        }
        val lines = sink.lines(CMD_LOCATION, 12_000)
        if (lines == null) {
            sink.status("dumpsys location 失败（root 不可用）", healthy = false)
            return
        }
        val events = ArrayList<PrivacyEvent>()
        val now = ProbeTime.now()
        var provider: String? = null
        var aggSection = false
        var aggProvider: String? = null
        var listenerCount = 0
        var deliveryCount = 0

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.startsWith("Location Providers:")) { aggSection = false; provider = null; continue }
            if (line.startsWith("Historical Aggregate Location Provider Data:")) {
                aggSection = true; aggProvider = null; continue
            }
            if (!aggSection) {
                // provider 段头：`  passive provider:`
                val pm = Regex("""^\s{2,6}(passive|network|fused|gps) provider:""").find(line)
                if (pm != null) {
                    provider = pm.groupValues[1]
                    continue
                }
                val lm = LISTENER.find(line) ?: continue
                val uid = lm.groupValues[1].toIntOrNull() ?: continue
                val fullPkg = lm.groupValues[2]
                val binder = lm.groupValues[3]
                val request = lm.groupValues[4]
                val pkg = fullPkg.substringBefore('[')
                val prov = provider ?: "unknown"
                val key = "listener|$uid|$pkg|$prov|$binder"
                listenerCount++
                if (!seenListeners.addIfNew(key)) continue
                // 只在首次轮询发射（之后新注册的才发），历史监听器首轮全发一次便于 UI 有数据
                events.add(
                    PrivacyEvent(
                        ts = now,
                        category = PrivacyCategory.LOCATION,
                        subType = "location_listener",
                        uid = uid,
                        packageName = pkg,
                        appLabel = sink.label(pkg, uid),
                        target = "location:$prov",
                        detail = "$pkg 注册 $prov 定位监听（${request.take(80)}）",
                        source = EventSource.DUMPSYS,
                        confidence = 86,
                    ),
                )
            } else {
                // 聚合段：`    passive:` / `    gps:` ... 再缩进的客户端行
                val provMatch = Regex("""^\s{4}(passive|network|fused|gps):\s*$""").find(line)
                if (provMatch != null) {
                    aggProvider = provMatch.groupValues[1]
                    continue
                }
                if (line.isNotBlank() && !line.startsWith("    ")) { aggSection = false; continue }
                val cm = AGG_CLIENT.find(line) ?: continue
                val uid = cm.groupValues[1].toIntOrNull() ?: continue
                val pkg = cm.groupValues[2]
                val count = cm.groupValues[3].toLongOrNull() ?: continue
                val aggProv = aggProvider ?: "unknown"
                val key = "agg|$aggProv|$uid|$pkg"
                val prev = lastCounts.put(key, count)
                if (!seeded || prev == null) {
                    if (prev == null) lastCounts[key] = count
                    continue
                }
                val delta = count - prev
                if (delta <= 0) continue
                deliveryCount += delta.toInt()
                events.add(
                    PrivacyEvent(
                        ts = now,
                        category = PrivacyCategory.LOCATION,
                        subType = "location_delivery",
                        uid = uid,
                        packageName = pkg,
                        appLabel = sink.label(pkg, uid),
                        target = "location:$aggProv",
                        detail = "$pkg 收到 $delta 次 $aggProv 定位结果（累计 $count）",
                        source = EventSource.DUMPSYS,
                        confidence = 88,
                    ),
                )
            }
        }
        seeded = true
        if (events.isNotEmpty()) sink.emitAll(events)
        sink.status(
            "定位服务：$listenerCount 个监听器" +
                if (deliveryCount > 0) "，本次新增 $deliveryCount 次定位投递" else "",
        )
    }

    companion object {
        // 10135/com.google.android.gms[fused_location_provider]/CD56E663 Request[@+1h0m0s0ms BALANCED, ...]
        private val LISTENER = Regex("""^\s+(\d+)/([^/]+)/([0-9A-Fa-f]+)\s+Request\[(.*)\]""")
        //      10135/com.google.android.gms[fused_location_provider]: min/max ... locations = 99
        private val AGG_CLIENT = Regex(
            """^\s+(\d+)/([^\[:]+?)(?:\[[^\]]*\])?:\s+.*?locations\s*=\s*(\d+)""",
        )
        const val CMD_LOCATION: String =
            // 只要 Location Providers + Historical Aggregate 两段，截到 GNSS Manager 提前结束，
            // 避免整份 dumpsys location（含 GNSS/GPS 详细日志）进入 root 管道。
            "dumpsys location | sed -n '/Location Providers:/,/GNSS Manager/p'"
    }
}
