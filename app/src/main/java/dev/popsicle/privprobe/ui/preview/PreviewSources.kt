package dev.popsicle.privprobe.ui.preview

import dev.popsicle.privprobe.core.collector.CollectorControl
import dev.popsicle.privprobe.core.collector.ProbeStatus
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.core.model.CallerDetailState
import dev.popsicle.privprobe.core.model.CategoryDetailState
import dev.popsicle.privprobe.core.model.DashboardState
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.model.TargetDetailState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 预览模式下的仓库实现：只读内置数据集，支持「清空」与「导出」的演示。
 * 见 [PreviewDataset] 的说明：真实采集后端就绪后 UI 不会再走这里。
 */
class PreviewRepository : PrivacyRepository {

    private val cleared = MutableStateFlow(false)

    override fun observeDashboard(): Flow<DashboardState> = cleared.map { isCleared ->
        if (isCleared) emptyDashboard() else PreviewDataset.dashboard()
    }

    override fun observeCategory(category: PrivacyCategory): Flow<CategoryDetailState> =
        cleared.map { isCleared ->
            if (isCleared) {
                CategoryDetailState(
                    category = category,
                    todayHourly = List(24) { 0 },
                )
            } else {
                PreviewDataset.category(category)
            }
        }

    override fun observeCaller(category: PrivacyCategory, uid: Int): Flow<CallerDetailState> =
        cleared.map { isCleared ->
            if (isCleared) {
                val summary = PreviewDataset.caller(category, uid).caller
                CallerDetailState(category = category, caller = summary)
            } else {
                PreviewDataset.caller(category, uid)
            }
        }

    override fun observeTarget(category: PrivacyCategory, target: String): Flow<TargetDetailState> =
        cleared.map { isCleared ->
            if (isCleared) {
                val summary = PreviewDataset.target(category, target).target
                TargetDetailState(category = category, target = summary)
            } else {
                PreviewDataset.target(category, target)
            }
        }

    override suspend fun timelineForCategory(
        category: PrivacyCategory,
        limit: Int,
        offset: Int,
    ): List<PrivacyEvent> = if (cleared.value) {
        emptyList()
    } else {
        PreviewDataset.events
            .asSequence()
            .filter { it.category == category }
            .sortedByDescending { it.ts }
            .drop(offset)
            .take(limit)
            .toList()
    }

    override suspend fun todayEventCount(): Long = if (cleared.value) 0 else PreviewDataset.events.size.toLong()

    override suspend fun insertEvents(events: List<PrivacyEvent>) = Unit

    override suspend fun clearAll() {
        cleared.value = true
    }

    override suspend fun purgeBefore(cutoff: Long): Int = 0

    override suspend fun exportCsv(file: File): Int = withContext(Dispatchers.IO) {
        val rows = PreviewDataset.events
        val header = "ts,isoTime,category,subType,uid,pid,packageName,appLabel,target,detail,source,confidence,durationMs,direction"
        file.bufferedWriter().use { writer ->
            writer.write(header)
            writer.newLine()
            for (e in rows) {
                writer.write(
                    listOf(
                        e.ts.toString(),
                        iso(e.ts),
                        e.category.id,
                        e.subType,
                        e.uid.toString(),
                        e.pid?.toString() ?: "",
                        e.packageName.orEmpty(),
                        e.appLabel.orEmpty(),
                        e.target.orEmpty(),
                        e.detail.orEmpty(),
                        e.source.name,
                        e.confidence.toString(),
                        e.durationMs?.toString() ?: "",
                        e.direction.orEmpty(),
                    ).joinToString(",") { csvEscape(it) }
                )
                writer.newLine()
            }
        }
        rows.size
    }

    override suspend fun exportJsonl(file: File): Int = withContext(Dispatchers.IO) {
        val rows = PreviewDataset.events
        file.bufferedWriter().use { writer ->
            for (e in rows) {
                val obj = JSONObject()
                    .put("ts", e.ts)
                    .put("isoTime", iso(e.ts))
                    .put("category", e.category.id)
                    .put("subType", e.subType)
                    .put("uid", e.uid)
                    .put("pid", e.pid)
                    .put("packageName", e.packageName)
                    .put("appLabel", e.appLabel)
                    .put("target", e.target)
                    .put("detail", e.detail)
                    .put("source", e.source.name)
                    .put("confidence", e.confidence)
                    .put("durationMs", e.durationMs)
                    .put("direction", e.direction)
                writer.write(obj.toString())
                writer.newLine()
            }
        }
        rows.size
    }

    override suspend fun totalEventCount(): Long =
        if (cleared.value) 0L else PreviewDataset.events.size.toLong()

    override suspend fun domainCount(): Int =
        if (cleared.value) {
            0
        } else {
            PreviewDataset.events
                .filter { it.category == PrivacyCategory.NETWORK }
                .mapNotNull { it.target }
                .distinct()
                .size
        }

    private fun iso(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun csvEscape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun emptyDashboard(): DashboardState = DashboardState(
        rootAvailable = true,
        totalToday = 0,
        appCount = 0,
        domainCount = 0,
        categories = PrivacyCategory.entries.map {
            dev.popsicle.privprobe.core.model.CategorySummary(
                category = it,
                countToday = 0,
                callerCount = 0,
                lastTs = null,
                topCaller = null,
                todayHourly = List(24) { 0 },
            )
        },
        recent = emptyList(),
        activeProbeCount = 0,
        rootHint = "KernelSU · uid=0(root) · SELinux Enforcing",
    )
}

/** 预览设置：内存态，改了立即生效（进程内）。 */
class PreviewSettingsStore : SettingsStore {
    // 预览数据是「已采集」的假数据，因此预览设置默认开启采集，避免与未开启引导横幅矛盾。
    private val _state = MutableStateFlow(AppSettings(collectorEnabled = true))
    override val state: StateFlow<AppSettings> = _state
    override fun snapshot(): AppSettings = _state.value
    override fun update(transform: (AppSettings) -> AppSettings) {
        _state.value = transform(_state.value)
    }
}

/** 预览采集控制：假装 root 可用、采集运行中。 */
class PreviewCollectorControl : CollectorControl {

    private val _running = MutableStateFlow(true)
    private val _rootAvailable = MutableStateFlow(true)
    private val _rootDetail = MutableStateFlow("KernelSU 越狱模式 · uid=0(root) · SELinux Enforcing · u:r:ksu:s0")
    private val _lastError = MutableStateFlow<String?>(null)
    private val _probeStatus = MutableStateFlow(buildProbeStatus())

    override val running: StateFlow<Boolean> = _running
    override val rootAvailable: StateFlow<Boolean> = _rootAvailable
    override val rootDetail: StateFlow<String> = _rootDetail
    override val lastError: StateFlow<String?> = _lastError
    override val probeStatus: StateFlow<Map<ProbeId, ProbeStatus>> = _probeStatus

    private fun buildProbeStatus(): Map<ProbeId, ProbeStatus> {
        val now = System.currentTimeMillis()
        return ProbeId.entries.associateWith { id ->
            when (id) {
                ProbeId.SCREEN -> ProbeStatus(
                    healthy = true,
                    detail = "无活动投屏会话",
                    lastEventTs = now - 86_400_000,
                )
                ProbeId.CLIPBOARD -> ProbeStatus(
                    healthy = true,
                    detail = "日志缓冲正常",
                    lastEventTs = now - 420_000,
                )
                ProbeId.APPOPS -> ProbeStatus(
                    healthy = true,
                    detail = "上次轮询 8 秒前 · 2.1MB",
                    lastEventTs = now - 8_000,
                )
                else -> ProbeStatus(
                    healthy = true,
                    detail = "健康 · 最近上报 ${(2..90).random()} 秒前",
                    lastEventTs = now - (2..90).random() * 1_000L,
                )
            }
        }
    }

    override fun start(context: android.content.Context) {
        _running.value = true
        _lastError.value = null
    }

    override fun stop(context: android.content.Context) {
        _running.value = false
    }

    override suspend fun refresh(context: android.content.Context) {
        kotlinx.coroutines.delay(700)
        _rootAvailable.value = true
        _lastError.value = null
    }

    override suspend fun runSelfTest(context: android.content.Context) {
        kotlinx.coroutines.delay(1_800)
        _probeStatus.value = buildProbeStatus()
    }
}
