package dev.popsicle.privprobe.ui.util

import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间 / 数字 / 域名展示格式化。UI 层唯一的格式化入口，保证全 App 文案一致。
 */
object Format {

    val zone: ZoneId get() = ZoneId.systemDefault()

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun clock(ts: Long): String = hhmm.format(Instant.ofEpochMilli(ts).atZone(zone))

    fun fileStamp(ts: Long = System.currentTimeMillis()): String =
        fileStamp.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** 24 小时桶内对应的小时下标（0..23）。 */
    fun hourOf(ts: Long): Int = Instant.ofEpochMilli(ts).atZone(zone).hour

    /** 域名脱敏：api.weixin.qq.com -> a***.weixin.qq.com；IP 保留首尾段。 */
    fun maskDomain(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        // IPv4：1.2.3.4 -> 1.*.*.4（静态 Regex）
        ipv4Regex.matchEntire(text)?.let { m ->
            return "${m.groupValues[1]}.*.*.${m.groupValues[4]}"
        }
        if (text.contains(':') || text.contains('/') || text.contains(' ')) {
            // IPv6 / URL / 带描述的 target 不强行处理
            return text
        }
        val labels = text.split('.')
        if (labels.size < 2) return text
        val head = labels.first()
        val maskedHead = if (head.length <= 1) "*" else head.first() + "***"
        return (listOf(maskedHead) + labels.drop(1)).joinToString(".")
    }

    /** 事件展示标题：target 优先，其次 subType。 */
    fun eventTitle(event: PrivacyEvent, maskDomains: Boolean): String {
        val target = event.target?.takeIf { it.isNotBlank() }
        val raw = target ?: event.subType
        return if (maskDomains && event.category == PrivacyCategory.NETWORK) maskDomain(raw) else raw
    }

    /** 精确整数（千分位）：1927 -> 1,927。 */
    fun count(value: Long): String = String.format(Locale.US, "%,d", value)

    /** 份额：0 显示 —，极小的真实份额显示 <0.1%。 */
    fun percent(part: Long, total: Long): String {
        if (total <= 0) return "—"
        val pct = part * 100.0 / total
        return when {
            part <= 0 -> "0%"
            pct >= 10 -> String.format(Locale.US, "%.0f%%", pct)
            pct > 0.1 -> String.format(Locale.US, "%.1f%%", pct)
            else -> "<0.1%"
        }
    }
}


/** 秒数展示：1500ms -> 1.5s。 */
fun formatMillis(ms: Long): String =
    if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"
