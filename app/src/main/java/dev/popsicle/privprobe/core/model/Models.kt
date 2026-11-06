package dev.popsicle.privprobe.core.model

/**
 * 隐私大类。id 是稳定标识（数据库 / 导出 / 配置都用它），其余字段只用于展示。
 */
enum class PrivacyCategory(
    val id: String,
    val title: String,
    val shortTitle: String,
    val risk: RiskLevel,
) {
    NETWORK(
        id = "network",
        title = "网络与 DNS",
        shortTitle = "网络",
        risk = RiskLevel.HIGH,
    ),
    CAMERA(
        id = "camera",
        title = "相机",
        shortTitle = "相机",
        risk = RiskLevel.CRITICAL,
    ),
    MICROPHONE(
        id = "microphone",
        title = "麦克风",
        shortTitle = "麦克风",
        risk = RiskLevel.CRITICAL,
    ),
    LOCATION(
        id = "location",
        title = "位置",
        shortTitle = "位置",
        risk = RiskLevel.HIGH,
    ),
    SENSOR(
        id = "sensor",
        title = "传感器",
        shortTitle = "传感器",
        risk = RiskLevel.MEDIUM,
    ),
    CLIPBOARD(
        id = "clipboard",
        title = "剪贴板",
        shortTitle = "剪贴板",
        risk = RiskLevel.HIGH,
    ),
    SCREEN(
        id = "screen",
        title = "屏幕采集",
        shortTitle = "屏幕",
        risk = RiskLevel.HIGH,
    ),
    TELEPHONY(
        id = "telephony",
        title = "电话与短信",
        shortTitle = "电话短信",
        risk = RiskLevel.HIGH,
    ),
    CONTACTS(
        id = "contacts",
        title = "通讯录与日历",
        shortTitle = "联系人",
        risk = RiskLevel.HIGH,
    ),
    DEVICE_ID(
        id = "device_id",
        title = "设备标识",
        shortTitle = "设备标识",
        risk = RiskLevel.MEDIUM,
    ),
    APP_ACTIVITY(
        id = "app_activity",
        title = "前台与进程",
        shortTitle = "应用活动",
        risk = RiskLevel.LOW,
    ),
    ;

    companion object {
        fun fromId(id: String): PrivacyCategory? = entries.firstOrNull { it.id == id }
    }
}

enum class RiskLevel(val label: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("严重"),
}

/** 事件来源，用于 UI 上标注可信度与「底层/系统接口」标签。 */
enum class EventSource(val label: String) {
    PACKET("数据包捕获"),
    UPROBE("uprobe 注入"),
    KERNEL_TRACE("内核跟踪"),
    DUMPSYS("系统服务转储"),
    LOGCAT("系统日志"),
    APPOPS("AppOps 审计"),
    HEURISTIC("关联推断"),
    SELF_TEST("自检事件"),
}

/** 单条隐私事件。uid/pid 尽量填；无法归因时 uid 用 [UNKNOWN_UID]。 */
data class PrivacyEvent(
    val ts: Long,
    val category: PrivacyCategory,
    val subType: String,
    val uid: Int,
    val pid: Int? = null,
    val packageName: String? = null,
    val appLabel: String? = null,
    val target: String? = null,
    val detail: String? = null,
    val source: EventSource,
    val confidence: Int = 70,
    val durationMs: Long? = null,
    val direction: String? = null,
) {
    companion object {
        const val UNKNOWN_UID = -1
    }
}

data class CallerSummary(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val count: Long,
    val lastTs: Long,
    val targetCount: Int,
    val isSystem: Boolean,
)

data class TargetSummary(
    val target: String,
    val count: Long,
    val callerCount: Int,
    val lastTs: Long,
    val kind: String,
)

data class CategorySummary(
    val category: PrivacyCategory,
    val countToday: Long,
    val callerCount: Int,
    val lastTs: Long?,
    val topCaller: CallerSummary?,
    val todayHourly: List<Int>,
)

data class DashboardState(
    val rootAvailable: Boolean = false,
    val totalToday: Long = 0,
    val appCount: Int = 0,
    val domainCount: Int = 0,
    val categories: List<CategorySummary> = emptyList(),
    val recent: List<PrivacyEvent> = emptyList(),
    val activeProbeCount: Int = 0,
    val rootHint: String? = null,
)

data class CategoryDetailState(
    val category: PrivacyCategory,
    val countToday: Long = 0,
    val countTotal: Long = 0,
    val callerCount: Int = 0,
    val targetCount: Int = 0,
    val lastTs: Long? = null,
    val callers: List<CallerSummary> = emptyList(),
    val targets: List<TargetSummary> = emptyList(),
    val todayHourly: List<Int> = emptyList(),
)

data class CallerDetailState(
    val category: PrivacyCategory,
    val caller: CallerSummary,
    val todayCount: Long = 0,
    val totalCount: Long = 0,
    val firstTs: Long? = null,
    val lastTs: Long? = null,
    val targets: List<TargetSummary> = emptyList(),
    val events: List<PrivacyEvent> = emptyList(),
)

data class TargetDetailState(
    val category: PrivacyCategory,
    val target: TargetSummary,
    val callers: List<CallerSummary> = emptyList(),
    val events: List<PrivacyEvent> = emptyList(),
)
