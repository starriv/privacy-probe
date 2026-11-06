package dev.popsicle.privprobe.core.data

import dev.popsicle.privprobe.core.model.CallerDetailState
import dev.popsicle.privprobe.core.model.CategoryDetailState
import dev.popsicle.privprobe.core.model.DashboardState
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.model.TargetDetailState
import dev.popsicle.privprobe.core.model.TimelineCursor
import dev.popsicle.privprobe.core.model.TimelinePage
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * UI 只依赖这个接口。实现者负责 SQLite 存储与聚合查询。
 *
 * 约定：
 * - 所有 Flow 在数据变化后必须重新发射（reactive）。
 * - 「今日」以设备本地时区当天 00:00 为界。
 * - 调用者是 (uid, package) 身份：同一 uid 下不同包名（如共享 uid 1000）分别展示；
 *   系统应用是否显示由 SettingsStore.showSystemApps 在聚合层或 UI 层过滤。
 */
interface PrivacyRepository {

    fun observeDashboard(): Flow<DashboardState>

    fun observeCategory(category: PrivacyCategory): Flow<CategoryDetailState>

    fun observeCaller(
        category: PrivacyCategory,
        uid: Int,
        packageName: String?,
    ): Flow<CallerDetailState>

    fun observeTarget(category: PrivacyCategory, target: String): Flow<TargetDetailState>

    /**
     * 全保留期类别时间线，按时间倒序取一页，不受今日排行榜限制。
     *
     * [cursor] 为上一页的 [TimelinePage.nextCursor]；传 null 取最新一页。
     * 用 (ts, id) 游标而不是 offset：新事件持续写入时不会造成翻页重复/跳过。
     */
    suspend fun timelineForCategory(
        category: PrivacyCategory,
        cursor: TimelineCursor? = null,
        limit: Int = 120,
    ): TimelinePage = TimelinePage(emptyList(), null)

    /** 通知使用的轻量今日计数，遵循系统应用过滤。 */
    suspend fun todayEventCount(): Long

    /** 批量入库；实现需自行去重（同一事件可能被多个探针重复上报）。 */
    suspend fun insertEvents(events: List<PrivacyEvent>)

    suspend fun clearAll()

    /** 删除 ts < cutoff 的事件，返回删除条数。 */
    suspend fun purgeBefore(cutoff: Long): Int

    /** 导出全部事件为 CSV，返回写入条数。 */
    suspend fun exportCsv(file: File): Int

    /** 导出全部事件为 JSON Lines，返回写入条数。 */
    suspend fun exportJsonl(file: File): Int

    suspend fun totalEventCount(): Long

    /** 当前库内出现的域名（网络类）数量，用于 Dashboard 摘要。 */
    suspend fun domainCount(): Int
}
