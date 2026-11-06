package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.root.RootShell
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 采集探针。一个探针 = 一个长驻循环；由 [ProbeManager] 按设置启停。
 * [run] 必须响应协程取消（delay / 流读取），停止后不得残留进程。
 */
interface Probe {
    val id: ProbeId

    /** 该探针负责上报状态的 ProbeId 列表（一个探针可能覆盖多个开关，例如 logcat）。 */
    val statusIds: List<ProbeId> get() = listOf(id)

    suspend fun run(sink: ProbeSink)
}

/** 探针运行环境：root 执行、uid 归因、事件发射、健康状态上报。 */
interface ProbeSink {
    val id: ProbeId
    val settings: AppSettings

    /** 屏幕是否点亮（由 ProbeManager 注入）；熄灭时轮询类探针降频，重探针由调度器直接暂停。 */
    val screenInteractive: Boolean

    suspend fun emit(event: PrivacyEvent)
    suspend fun emitAll(events: List<PrivacyEvent>)

    /** 上报探针健康状态；detail 会展示在配置页。 */
    fun status(detail: String, healthy: Boolean = true)

    fun statusForProbe(id: ProbeId, detail: String, healthy: Boolean) { status(detail, healthy) }

    suspend fun exec(command: String, timeoutMs: Long = 8_000L): RootShell.Result

    /** 执行命令并返回输出行；失败返回 null。 */
    suspend fun lines(command: String, timeoutMs: Long = 8_000L): List<String>?

    fun openStream(command: String): RootShell.Stream?

    fun uidForPackage(pkg: String?): Int
    fun label(pkg: String?, uid: Int?): String

    fun log(message: String, error: Throwable? = null)
}

/** 采集器运行态快照，注入给 Repository 用于填充 DashboardState。 */
data class CollectorRuntimeStatus(
    val rootAvailable: Boolean = false,
    val activeProbeCount: Int = 0,
    val rootHint: String? = null,
)

/** 探针通用的时间解析（全部按设备本地时区）。 */
object ProbeTime {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hhmmss: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val mmddhhmmss: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val appOpsTs: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun now(): Long = System.currentTimeMillis()

    fun dayStart(now: Long = now()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** `01:03:52` → 今天该时刻；若晚于当前时间 2 分钟则视为昨天（跨零点）。 */
    fun parseHhmmss(text: String, now: Long = now()): Long? = runCatching {
        val time = LocalTime.parse(text.trim(), hhmmss)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var ts = LocalDateTime.of(today, time).atZone(zone).toInstant().toEpochMilli()
        if (ts > now + 120_000L) ts -= 86_400_000L
        ts
    }.getOrNull()

    /** `11-06 01:03:52`（无年份）→ epoch；若结果晚于当前时间 1 天则归到上一年。 */
    fun parseMmDdHhmmss(text: String, now: Long = now()): Long? = runCatching {
        val md = LocalDateTime.parse(text.trim(), mmddhhmmss)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var year = today.year
        var ts = LocalDateTime.of(year, md.monthValue, md.dayOfMonth, md.hour, md.minute, md.second)
            .atZone(zone).toInstant().toEpochMilli()
        if (ts > now + 86_400_000L) {
            year -= 1
            ts = LocalDateTime.of(year, md.monthValue, md.dayOfMonth, md.hour, md.minute, md.second)
                .atZone(zone).toInstant().toEpochMilli()
        }
        ts
    }.getOrNull()

    /** `2026-11-06 01:03:50.723` → epoch。 */
    fun parseAppOpsTs(text: String): Long? = runCatching {
        LocalDateTime.parse(text.trim(), appOpsTs).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

}

/** 常见的行内字段提取（抗噪）。 */
object ProbeParse {
    private val uidRegex = Regex("""\buid[=: ]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val pidRegex = Regex("""\bpid[=: ]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val pkgRegex = Regex("""\b(?:package|packageName|pkg|pack|caller|clientPackage)\s*[=:]?\s*([a-zA-Z][\w]*(?:\.[\w]+){1,})""", RegexOption.IGNORE_CASE)
    private val plainPkgRegex = Regex("""\b([a-z][a-z0-9_]*(?:\.[a-z0-9_]+){2,})\b""")

    private val noisePackages = setOf(
        "android.os", "java.lang", "android.content", "android.app", "android.util",
        "android.media", "android.hardware", "android.server", "android.view",
        "com.android.internal", "com.android.server", "dalvik.system", "androidx.core",
    )

    fun uidOf(text: String): Int? = uidRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun pidOf(text: String): Int? = pidRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun packageOf(text: String): String? {
        pkgRegex.find(text)?.groupValues?.getOrNull(1)?.let { return cleanPackage(it) }
        for (m in plainPkgRegex.findAll(text)) {
            val candidate = cleanPackage(m.groupValues[1]) ?: continue
            if (noisePackages.any { candidate == it || candidate.startsWith("$it.") }) continue
            return candidate
        }
        return null
    }

    private fun cleanPackage(raw: String): String? {
        val p = raw.trimEnd('.', ',', ')', ';', ':')
        if (p.isEmpty()) return null
        if (p.contains('$') || p.any { it.isUpperCase() } || p.contains('/')) return null
        if (p.startsWith("com.android.") && p.count { it == '.' } < 2 && p.length < 14) return null
        return p
    }
}

/** 有界去重集合：探针轮询时避免同一行重复入库（重建后少量重复可接受）。 */
class DedupeSet(private val max: Int = 4096) {
    private val set = LinkedHashSet<String>()

    val size: Int
        @Synchronized get() = set.size

    @Synchronized
    fun addIfNew(key: String): Boolean {
        if (set.contains(key)) return false
        set.add(key)
        if (set.size > max) {
            val iterator = set.iterator()
            repeat(max / 8) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        return true
    }
}

/**
 * 探针基类：统一异常兜底、固定节拍轮询、按屏门控与状态上报。
 *
 * PERF P0-2 / P2-7：
 * - 用 elapsedRealtime 固定节拍（不在 poll 后再 delay，避免漂移越跑越慢）；
 * - 屏幕熄灭时 interval ×4（重探针由 ProbeManager 直接暂停）；
 * - poll 失败指数退避（最多 60s），状态里带上失败原因与重试时间；
 * - 熄屏等待按 1s 切片，屏幕点亮立即补一轮。
 */
abstract class PollingProbe : Probe {
    protected abstract fun intervalMs(settings: AppSettings): Long

    /** 一次轮询；异常会被基类捕获并上报，不退出循环。 */
    protected abstract suspend fun poll(sink: ProbeSink)

    override suspend fun run(sink: ProbeSink) {
        var failures = 0
        var nextAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val base = intervalMs(sink.settings).coerceAtLeast(MIN_INTERVAL_MS)
            try {
                poll(sink)
                failures = 0
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failures++
                val backoff = (base * (1L shl failures.coerceAtMost(4))).coerceAtMost(MAX_BACKOFF_MS)
                sink.log("poll failed (failures=$failures)", t)
                sink.status(
                    "轮询失败：${t.message ?: t.javaClass.simpleName}，${backoff / 1000}s 后重试",
                    healthy = false,
                )
                nextAt = android.os.SystemClock.elapsedRealtime() + backoff
                waitWithScreenWake(sink, nextAt)
                continue
            }
            val interval = if (sink.screenInteractive) base else base * OFF_SCREEN_INTERVAL_FACTOR
            nextAt = android.os.SystemClock.elapsedRealtime() + interval
            waitWithScreenWake(sink, nextAt)
        }
    }

    private suspend fun waitWithScreenWake(sink: ProbeSink, targetAt: Long) {
        while (true) {
            val remain = targetAt - android.os.SystemClock.elapsedRealtime()
            if (remain <= 0) return
            if (sink.screenInteractive) {
                // 亮屏状态：正常等待完整间隔
                kotlinx.coroutines.delay(remain)
                return
            }
            // 熄屏：1s 粒度等待，屏幕点亮立即补一轮
            kotlinx.coroutines.delay(minOf(remain, SCREEN_OFF_POLL_SLICE_MS))
            if (sink.screenInteractive) {
                sink.status("屏幕点亮，立即补一轮")
                return
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 300L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val OFF_SCREEN_INTERVAL_FACTOR = 4L
        private const val SCREEN_OFF_POLL_SLICE_MS = 1_000L
    }
}