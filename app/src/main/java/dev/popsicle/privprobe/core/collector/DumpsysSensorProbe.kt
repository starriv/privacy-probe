package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.app.UidResolver
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent

/**
 * 传感器探针：解析 `dumpsys sensorservice` 的 `Recent Sensor events` 段。
 *
 * 实测行格式：
 * `01:21:08 + 0x00000899 pid= 9470 uid= 1000 samplingPeriod=  200000us ... (sensor, package): (GripPostureDetection  Non-wakeup, com.miui.powerkeeper...collector)`
 * 只对 `+`（激活）发射事件；`-`（停用）用于计算持续时长并保持去重，避免系统服务把库刷爆。
 *
 * P1-2 修复：括号里最后一段经常是 **Java 类名**（如
 * `com.miui.powerkeeper.thermalcollector.collector.ScenarioCollectorUtils`）而不是包名。
 * 候选必须经 [UidResolver.validatePackageName] 校验（语法 + PackageManager/RepoIntel/包表确认），
 * 不可信时改用 uid 反查包名；原始类名放进 detail 供排查。
 */
class DumpsysSensorProbe : PollingProbe() {

    override val id: ProbeId = ProbeId.SENSOR

    private val seen = DedupeSet(4096)
    private val activatedAt = HashMap<String, Long>() // pid|sensor -> ts
    /** 性能：系统进程（uid<10000）的传感器激活按「传感器 × 分钟」采样，避免 system_server 刷屏。 */
    private val systemSampling = DedupeSet(1024)

    override fun intervalMs(settings: AppSettings): Long =
        // 性能 review：sensor dump 43ms/482KB @2s → 3s（配合 sed 只取 Recent Sensor events 段）
        settings.pollIntervalMs.coerceAtLeast(3_000L)

    override suspend fun poll(sink: ProbeSink) {
        if (!ProbeGates.isProbeEnabled(sink.settings, ProbeId.SENSOR)) {
            sink.status("传感器采集已关闭")
            return
        }
        val lines = sink.lines(CMD_SENSORS_TAIL, 10_000)
        if (lines == null) {
            sink.status("dumpsys sensorservice 失败（root 不可用）", healthy = false)
            return
        }
        val events = ArrayList<PrivacyEvent>()
        val now = ProbeTime.now()
        for (raw in lines) {
            val line = raw.trimEnd()
            val m = SENSOR_LINE.find(line) ?: continue
            val ts = ProbeTime.parseHhmmss(m.groupValues[1]) ?: continue
            val sign = m.groupValues[2]
            val pid = m.groupValues[3].toIntOrNull() ?: continue
            val uid = m.groupValues[4].toIntOrNull() ?: -1
            val body = m.groupValues[5]
            val sensorRaw = body.substringBeforeLast(',').trim()
            val rawPackage = body.substringAfterLast(',').trim()
            // sensorservice 的 "(sensor, package)" 后半段不一定是包名：先做语法清洗，
            // 再做「真实包」校验；不通过则用 uid 反查。
            val sanitized = UidResolver.sanitizePackageName(rawPackage)
            val pkg = UidResolver.validatePackageName(sanitized) ?: UidResolver.packageForUid(uid)
            val rawHint = rawPackage.takeIf { it.isNotBlank() && it != pkg }
            // 去掉 "Non-wakeup" / "Wakeup" 之类装饰
            val sensor = sensorRaw
                .replace(Regex("""\s{2,}"""), " ")
                .replace(Regex("""\s*(Non-wakeup|Wakeup|wakeup|non-wakeup)\s*""", RegexOption.IGNORE_CASE), " ")
                .trim()
                .ifBlank { sensorRaw }
            val key = "$ts|$sign|$pid|$sensor"
            if (!seen.addIfNew(key)) continue
            val activateKey = "$pid|$sensor"
            if (sign == "-") {
                activatedAt.remove(activateKey)
                continue
            }
            activatedAt[activateKey] = ts
            // 性能 review：86.8% 事件来自系统 uid（sensor/app_activity）。系统进程的传感器
            // 激活只按「传感器 × 分钟」采样 1 条，应用（uid>=10000）保持全量。
            val isSystemUid = uid < 10_000
            if (isSystemUid && !systemSampling.addIfNew("$sensor|${ts / 60_000L}")) continue
            events.add(
                PrivacyEvent(
                    ts = ts,
                    category = PrivacyCategory.SENSOR,
                    subType = "sensor_activate",
                    uid = uid,
                    pid = pid,
                    packageName = pkg,
                    appLabel = sink.label(pkg, uid),
                    target = sensor,
                    detail = buildString {
                        append("传感器 $sensor 被激活")
                        pkg?.let { append("：$it") }
                        rawHint?.let { append("（原始声明 $it）") }
                        append(" (pid $pid)")
                        if (isSystemUid) append("，系统进程按分钟采样")
                    },
                    source = EventSource.DUMPSYS,
                    confidence = 90,
                ),
            )
        }
        if (events.isNotEmpty()) sink.emitAll(events)
        sink.status("传感器服务：解析 ${lines.size} 行，新增 ${events.size} 条激活事件")
    }

    companion object {
        private val SENSOR_LINE = Regex(
            """^(\d{2}:\d{2}:\d{2})\s+([+-])\s+0x[0-9a-fA-F]+\s+pid=\s*(\d+)\s+uid=\s*(\d+).*?\(sensor, package\):\s*\((.*)\)\s*$""",
        )
        const val CMD_SENSORS_TAIL: String =
            "dumpsys sensorservice | sed -n '/Recent Sensor events/,\$p'"
    }
}
