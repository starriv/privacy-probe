package dev.popsicle.privprobe.core.data.impl

import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.security.MessageDigest

/**
 * 逻辑事件去重键（P1-5）。
 *
 * 旧键 = `sha1(category|subType|uid|pid|ts|target|detail)`：含毫秒 ts / pid / detail，
 * 同一行为被不同探针（DUMPSYS / LOGCAT / APPOPS / 内核）在几毫秒到几秒内分别上报时
 * 永远算不同事件——示例库里 2 秒内的同 category/subType/uid/target 重复有 21 组。
 *
 * 新键 = `sha1(category | normalizedSubType | uid | target | timeBucket(ts/5000))`
 * - [normalizeSubType] 去掉 `_log` / `log_` 等来源后缀并做别名归一，
 *   让 `camera_log_connect` 与 `camera_connect` 落到同一个逻辑事件；
 * - `target` 不同不合并（不同域名/摄像头/传感器是不同事件）；
 * - `target` 为空的类别（例如 app_activity 进程创建）追加 detail 指纹，
 *   避免同 uid 下不同进程/行为被误合并；
 * - 时间分桶 5s：跨探针的同一行为通常在同一桶内；跨桶重复可接受（不追求跨秒级完全合并）。
 *
 * 冲突时保留证据更强的来源：insert 用 `source_rank` 列 + UPSERT，只有 rank 更高的一方
 * 才覆盖 source/confidence/detail（见 [sourceRank]）。
 */
internal object DedupeKey {

    /** 逻辑事件时间桶（毫秒）。 */
    const val TIME_BUCKET_MS = 5_000L

    private val aliases: Map<String, String> = mapOf(
        "camera_log_connect" to "camera_connect",
        "camera_log_disconnect" to "camera_disconnect",
        "audio_record_log" to "record_start",
        "clipboard_read_log" to "clipboard_read",
        "clipboard_write_log" to "clipboard_write",
        "location_log" to "location_request",
        "projection_log" to "projection_start",
    )

    /** subType 归一：小写、去来源后缀、别名归并。 */
    fun normalizeSubType(raw: String?): String {
        var s = (raw ?: "").trim().lowercase()
        if (s.isEmpty()) return "unknown"
        aliases[s]?.let { return it }
        s = s.removePrefix("log_")
        if (s.endsWith("_log")) s = s.removeSuffix("_log")
        s = s.replace("_log_", "_")
        if (s.isEmpty()) return "unknown"
        return aliases[s] ?: s
    }

    /** 逻辑事件键（SHA-1 hex）。 */
    fun key(e: PrivacyEvent): String {
        val bucket = e.ts / TIME_BUCKET_MS
        val target = e.target?.trim()?.lowercase()?.take(160).orEmpty()
        val raw = buildString {
            append(e.category.id).append('|')
            append(normalizeSubType(e.subType).take(80)).append('|')
            append(e.uid).append('|')
            append(target).append('|')
            append(bucket)
            if (target.isEmpty()) {
                // 没有 target 的事件（进程创建/前后台等）用 detail 指纹区分行为；
                // 不含 pid：同一事件被不同探针上报时 pid 可能不同。
                append('|').append(shortHash(e.detail?.trim()?.lowercase().orEmpty()))
            }
        }
        return sha1(raw)
    }

    /** 来源证据强度（越大越可信）；UPSERT 冲突时只有更高 rank 的来源能覆盖。 */
    fun sourceRank(source: EventSource): Int = when (source) {
        EventSource.PACKET -> 60
        EventSource.UPROBE -> 55
        EventSource.KERNEL_TRACE -> 50
        EventSource.DUMPSYS -> 40
        EventSource.APPOPS -> 30
        EventSource.LOGCAT -> 20
        EventSource.SELF_TEST -> 10
        EventSource.HEURISTIC -> 0
    }

    internal fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { b -> sb.append(String.format("%02x", b.toInt() and 0xff)) }
        return sb.toString()
    }

    private fun shortHash(text: String): String =
        if (text.isEmpty()) "" else sha1(text).take(16)
}
