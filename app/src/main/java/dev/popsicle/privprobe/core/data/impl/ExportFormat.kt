package dev.popsicle.privprobe.core.data.impl

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 导出/展示用的静态格式化工具（P1-9）。
 *
 * 旧实现的 `isoTime()` 每行 `DateTimeFormatter.ofPattern(...)` 新建对象，
 * 全量导出 60 万行会反复分配 formatter。这里只创建一次。
 * CSV 转义同时做公式注入防护（P2-6）：以 = + - @ 等开头的值前加 `'`。
 */
internal object ExportFormat {

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun isoTime(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(ISO)

    fun csvEscape(v: String): String {
        if (v.isEmpty()) return ""
        val safe = if (v.first() == '=' || v.first() == '+' || v.first() == '-' ||
            v.first() == '@' || v.first() == '\t' || v.first() == '\r'
        ) {
            "'$v"
        } else {
            v
        }
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
