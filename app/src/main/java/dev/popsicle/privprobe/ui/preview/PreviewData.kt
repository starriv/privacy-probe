package dev.popsicle.privprobe.ui.preview

import dev.popsicle.privprobe.core.model.CallerDetailState
import dev.popsicle.privprobe.core.model.CallerSummary
import dev.popsicle.privprobe.core.model.CategoryDetailState
import dev.popsicle.privprobe.core.model.CategorySummary
import dev.popsicle.privprobe.core.model.DashboardState
import dev.popsicle.privprobe.core.model.EventSource
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.model.TargetDetailState
import dev.popsicle.privprobe.core.model.TargetSummary
import dev.popsicle.privprobe.ui.util.Format
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random

/**
 * 内置预览数据集：只用于「采集后端尚未就绪」时把 UI 跑起来看效果。
 *
 * 一旦 AppGraph.ready == true，UI 会自动切到真实 Flow，本文件的任何数据都不会进入真实链路。
 * 数据生成是确定性（固定随机种子），方便反复截图对比。
 */
data class PreviewApp(
    val uid: Int,
    val pkg: String,
    val label: String,
    val system: Boolean = false,
)

private data class CallerSpec(val app: PreviewApp, val count: Int)

private data class CategorySpec(
    val category: PrivacyCategory,
    val callers: List<CallerSpec>,
    val targets: List<String>,
    val sources: List<EventSource>,
    val subTypes: List<String>,
)

object PreviewDataset {

    val apps: List<PreviewApp> = listOf(
        PreviewApp(10234, "com.tencent.mm", "微信"),
        PreviewApp(10251, "com.taobao.taobao", "淘宝"),
        PreviewApp(10266, "com.ss.android.ugc.aweme", "抖音"),
        PreviewApp(10277, "com.sina.weibo", "微博"),
        PreviewApp(10288, "com.eg.android.AlipayGphone", "支付宝"),
        PreviewApp(10294, "com.xiaomi.smarthome", "米家"),
        PreviewApp(10301, "com.netease.cloudmusic", "网易云音乐"),
        PreviewApp(10312, "com.tencent.mobileqq", "QQ"),
        PreviewApp(10320, "com.zhihu.android", "知乎"),
        PreviewApp(10333, "com.xingin.xhs", "小红书"),
        PreviewApp(10092, "com.android.chrome", "Chrome", system = true),
        PreviewApp(10045, "com.xiaomi.camera", "相机", system = true),
        PreviewApp(10079, "com.miui.securitycenter", "手机管家", system = true),
        PreviewApp(1000, "com.android.settings", "设置", system = true),
        PreviewApp(10225, "com.google.android.gms", "Google Play 服务", system = true),
    )

    private val byUid: Map<Int, PreviewApp> = apps.associateBy { it.uid }

    private fun app(uid: Int): PreviewApp = byUid.getValue(uid)

    private fun cs(uid: Int, count: Int) = CallerSpec(app(uid), count)

    private fun specs(): List<CategorySpec> = listOf(
        CategorySpec(
            category = PrivacyCategory.NETWORK,
            callers = listOf(
                cs(10234, 62), cs(10266, 41), cs(10251, 38), cs(10288, 22), cs(10277, 19),
                cs(10092, 15), cs(10312, 14), cs(10301, 12), cs(10225, 12), cs(10294, 10),
                cs(10333, 8), cs(10079, 8), cs(10320, 6),
            ),
            targets = listOf(
                "api.weixin.qq.com", "log-api.tiktokv.com", "gw.alipayobjects.com",
                "cdn.sinaimg.cn", "metrics.xiaomi.com", "api.zhihu.com",
                "sns.xingin.com", "music.163.com", "api.taobao.com", "open.mobile.qq.com",
            ),
            sources = listOf(EventSource.PACKET, EventSource.UPROBE),
            subTypes = listOf("DNS 查询", "TLS SNI", "HTTP Host", "出站连接"),
        ),
        CategorySpec(
            category = PrivacyCategory.CAMERA,
            callers = listOf(cs(10045, 4), cs(10234, 1), cs(10079, 1)),
            targets = listOf("摄像头 ID 0（后置主摄）", "摄像头 ID 5（前置）"),
            sources = listOf(EventSource.DUMPSYS, EventSource.APPOPS),
            subTypes = listOf("活跃客户端", "CameraService 会话"),
        ),
        CategorySpec(
            category = PrivacyCategory.MICROPHONE,
            callers = listOf(cs(10234, 4), cs(10266, 3), cs(10312, 2), cs(10301, 2), cs(10079, 1)),
            targets = listOf("AudioRecord · 主麦克风", "AudioFlinger 输入线程", "音频设备打开"),
            sources = listOf(EventSource.DUMPSYS, EventSource.KERNEL_TRACE),
            subTypes = listOf("输入线程", "录音活动", "设备打开"),
        ),
        CategorySpec(
            category = PrivacyCategory.LOCATION,
            callers = listOf(cs(10234, 9), cs(10288, 8), cs(10251, 6), cs(10079, 6), cs(10266, 5), cs(10294, 4)),
            targets = listOf("GPS 高精度定位", "网络定位", "定位结果投递"),
            sources = listOf(EventSource.DUMPSYS, EventSource.APPOPS),
            subTypes = listOf("监听器注册", "定位投递", "高精度调用"),
        ),
        CategorySpec(
            category = PrivacyCategory.SENSOR,
            callers = listOf(cs(10266, 22), cs(10234, 18), cs(10251, 12), cs(10301, 10), cs(10294, 9), cs(10333, 8), cs(10320, 6), cs(10079, 4)),
            targets = listOf("加速度计", "陀螺仪", "光线传感器", "磁场传感器"),
            sources = listOf(EventSource.DUMPSYS),
            subTypes = listOf("客户端激活", "采样请求"),
        ),
        CategorySpec(
            category = PrivacyCategory.CLIPBOARD,
            callers = listOf(cs(10251, 3), cs(10266, 3), cs(10333, 3), cs(10277, 2), cs(10234, 2), cs(10320, 1), cs(10092, 1)),
            targets = listOf("剪贴板读取", "剪贴板写入"),
            sources = listOf(EventSource.LOGCAT, EventSource.APPOPS),
            subTypes = listOf("读取", "写入", "AppOps 访问"),
        ),
        CategorySpec(
            category = PrivacyCategory.SCREEN,
            callers = listOf(cs(10079, 1), cs(10294, 1)),
            targets = listOf("MediaProjection 会话", "截屏"),
            sources = listOf(EventSource.DUMPSYS),
            subTypes = listOf("投屏会话", "截屏权限"),
        ),
        CategorySpec(
            category = PrivacyCategory.TELEPHONY,
            callers = listOf(cs(10234, 2), cs(10288, 1), cs(10079, 1)),
            targets = listOf("READ_SMS (AppOps)", "通话状态", "READ_CALL_LOG"),
            sources = listOf(EventSource.APPOPS),
            subTypes = listOf("敏感权限", "通话状态"),
        ),
        CategorySpec(
            category = PrivacyCategory.CONTACTS,
            callers = listOf(cs(10234, 3), cs(10312, 2), cs(10288, 2), cs(10079, 2)),
            targets = listOf("读取联系人", "读取日历"),
            sources = listOf(EventSource.APPOPS),
            subTypes = listOf("联系人访问", "日历访问"),
        ),
        CategorySpec(
            category = PrivacyCategory.DEVICE_ID,
            callers = listOf(cs(10251, 6), cs(10266, 5), cs(10277, 4), cs(10288, 3), cs(10294, 3), cs(10079, 2)),
            targets = listOf("IMEI", "Android ID", "MAC 地址", "广告 ID"),
            sources = listOf(EventSource.APPOPS, EventSource.LOGCAT),
            subTypes = listOf("设备标识", "标识读取"),
        ),
        CategorySpec(
            category = PrivacyCategory.APP_ACTIVITY,
            callers = listOf(
                cs(10234, 9), cs(10266, 8), cs(10251, 7), cs(10288, 6), cs(10277, 5),
                cs(10312, 5), cs(10301, 4), cs(10320, 4), cs(10333, 4), cs(10294, 4),
                cs(10045, 3), cs(10079, 3), cs(1000, 2),
            ),
            targets = listOf("前台切换", "进程启动", "Activity 切换"),
            sources = listOf(EventSource.DUMPSYS),
            subTypes = listOf("前台应用", "进程创建"),
        ),
    )

    val now: Long = System.currentTimeMillis()

    val dayStart: Long = LocalDate.now(ZoneId.systemDefault())
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    /** 全部事件，按时间倒序。 */
    val events: List<PrivacyEvent> = buildEvents()

    private fun buildEvents(): List<PrivacyEvent> {
        val rnd = Random(20261106)
        val out = ArrayList<PrivacyEvent>(1024)
        for (spec in specs()) {
            val cat = spec.category
            spec.callers.forEachIndexed { callerIdx, caller ->
                if (caller.count <= 0) return@forEachIndexed
                val preferred = spec.targets[callerIdx % spec.targets.size]
                repeat(caller.count) { i ->
                    val target = if (i % 3 == 0) {
                        spec.targets[(callerIdx + i) % spec.targets.size]
                    } else {
                        preferred
                    }
                    val source = spec.sources[(i + callerIdx) % spec.sources.size]
                    val subType = spec.subTypes[(i + callerIdx * 2) % spec.subTypes.size]
                    val frac = rnd.nextDouble().pow(1.7)
                    val ts = maxOf(dayStart + 1_000, now - 4_000 - (frac * (now - dayStart)).toLong())
                    out += event(cat, caller.app, subType, target, source, ts, rnd)
                }
                // 历史事件：让「总次数」明显大于「今日次数」
                val older = caller.count * 2 / 3 + 1
                repeat(older) { i ->
                    val daysAgo = 1 + rnd.nextInt(6)
                    val ts = dayStart - daysAgo * 86_400_000L + rnd.nextLong(0, 86_400_000L)
                    val target = spec.targets[(callerIdx + i) % spec.targets.size]
                    val source = spec.sources[(i + callerIdx) % spec.sources.size]
                    val subType = spec.subTypes[(i + callerIdx) % spec.subTypes.size]
                    out += event(cat, caller.app, subType, target, source, ts, rnd)
                }
            }
        }

        // 制造「此刻正在发生」的活跃感：相机 / 网络 / 麦克风 各一条最近事件
        out += event(
            PrivacyCategory.CAMERA, app(10045), "活跃客户端", "摄像头 ID 0（后置主摄）",
            EventSource.DUMPSYS, now - 14_000, rnd, durationMs = 8_400,
        )
        out += event(
            PrivacyCategory.NETWORK, app(10234), "TLS SNI", "api.weixin.qq.com",
            EventSource.PACKET, now - 48_000, rnd,
        )
        out += event(
            PrivacyCategory.MICROPHONE, app(10266), "录音活动", "AudioRecord · 主麦克风",
            EventSource.KERNEL_TRACE, now - 92_000, rnd, durationMs = 4_200,
        )
        return out.sortedByDescending { it.ts }
    }

    private fun event(
        cat: PrivacyCategory,
        caller: PreviewApp,
        subType: String,
        target: String,
        source: EventSource,
        ts: Long,
        rnd: Random,
        durationMs: Long? = null,
    ): PrivacyEvent = PrivacyEvent(
        ts = ts,
        category = cat,
        subType = subType,
        uid = caller.uid,
        pid = 20_000 + (caller.uid % 1_000),
        packageName = caller.pkg,
        appLabel = caller.label,
        target = target,
        detail = detailFor(cat, target, subType, rnd),
        source = source,
        confidence = confidenceFor(source, rnd),
        durationMs = durationMs ?: if (cat == PrivacyCategory.CAMERA || cat == PrivacyCategory.MICROPHONE) {
            rnd.nextLong(800, 12_000)
        } else {
            null
        },
        direction = if (cat == PrivacyCategory.NETWORK) listOf("OUT", "IN").random(rnd) else null,
    )

    private fun confidenceFor(source: EventSource, rnd: Random): Int = when (source) {
        EventSource.PACKET -> 96
        EventSource.UPROBE -> 93
        EventSource.KERNEL_TRACE -> 90
        EventSource.APPOPS -> rnd.nextInt(75, 96)
        EventSource.DUMPSYS -> rnd.nextInt(70, 93)
        EventSource.LOGCAT -> rnd.nextInt(60, 86)
        EventSource.HEURISTIC -> rnd.nextInt(40, 70)
        EventSource.SELF_TEST -> 99
    }

    private fun detailFor(cat: PrivacyCategory, target: String, subType: String, rnd: Random): String =
        when (cat) {
            PrivacyCategory.NETWORK -> when (subType) {
                "DNS 查询" -> "查询 A/AAAA 记录 · 往返 ${rnd.nextInt(4, 60)}ms"
                "TLS SNI" -> "SNI=$target · TLS 1.3 · ${rnd.nextInt(2, 9)} 条连接"
                "HTTP Host" -> "Host=$target · ${rnd.nextInt(1, 6)} 个请求"
                else -> "出站连接 · ↑${rnd.nextInt(80, 1_500)}B ↓${rnd.nextInt(80, 6_000)}B"
            }
            PrivacyCategory.CAMERA -> "Camera ID ${"05"[rnd.nextInt(2)]} · Active · 持续 ${rnd.nextInt(1, 20)}s"
            PrivacyCategory.MICROPHONE -> "采样率 ${listOf(16_000, 44_100, 48_000).random(rnd)}Hz · 单声道"
            PrivacyCategory.LOCATION -> "精度 ±${rnd.nextInt(5, 60)}m · 高精度=${rnd.nextBoolean()}"
            PrivacyCategory.SENSOR -> "采样率 ${listOf(50, 100, 200, 400).random(rnd)}Hz · ${rnd.nextInt(1, 4)} 个监听器"
            PrivacyCategory.CLIPBOARD -> "MIME text/plain · ${rnd.nextInt(1, 60)} 字符"
            PrivacyCategory.SCREEN -> "MediaProjection 会话 · ${rnd.nextInt(2, 40)} 分钟"
            PrivacyCategory.TELEPHONY -> "AppOps op=${listOf("READ_SMS", "READ_CALL_LOG", "READ_PHONE_STATE").random(rnd)}"
            PrivacyCategory.CONTACTS -> "读取 ${rnd.nextInt(1, 400)} 条记录"
            PrivacyCategory.DEVICE_ID -> listOf(
                "37xxxxxxxxxxxxx1", "android_id=${Integer.toHexString(rnd.nextInt())}",
                "02:00:00:${rnd.nextInt(16, 99)}:${rnd.nextInt(16, 99)}:${rnd.nextInt(16, 99)}",
                "GAID · 已限制" ,
            ).random(rnd)
            PrivacyCategory.APP_ACTIVITY -> "前台切换 · ${listOf("cold", "warm", "hot").random(rnd)} start"
        }

    fun kindOf(cat: PrivacyCategory): String = when (cat) {
        PrivacyCategory.NETWORK -> "域名"
        PrivacyCategory.CAMERA -> "摄像头"
        PrivacyCategory.MICROPHONE -> "音频输入"
        PrivacyCategory.LOCATION -> "定位"
        PrivacyCategory.SENSOR -> "传感器"
        PrivacyCategory.CLIPBOARD -> "剪贴板"
        PrivacyCategory.SCREEN -> "投屏会话"
        PrivacyCategory.TELEPHONY -> "权限"
        PrivacyCategory.CONTACTS -> "数据读取"
        PrivacyCategory.DEVICE_ID -> "设备标识"
        PrivacyCategory.APP_ACTIVITY -> "应用活动"
    }

    // ---------------------------------------------------------------- 聚合

    private fun callerSummaries(cat: PrivacyCategory, evs: List<PrivacyEvent>): List<CallerSummary> {
        if (evs.isEmpty()) return emptyList()
        return evs.groupBy { it.uid }.map { (uid, list) ->
            val meta = byUid[uid]
            CallerSummary(
                uid = uid,
                packageName = meta?.pkg ?: list.firstNotNullOfOrNull { it.packageName },
                label = meta?.label ?: list.firstNotNullOfOrNull { it.appLabel } ?: "uid $uid",
                count = list.size.toLong(),
                lastTs = list.maxOf { it.ts },
                targetCount = list.mapNotNull { it.target }.distinct().size,
                isSystem = meta?.system ?: false,
            )
        }.sortedByDescending { it.count }
    }

    private fun targetSummaries(cat: PrivacyCategory, evs: List<PrivacyEvent>): List<TargetSummary> {
        if (evs.isEmpty()) return emptyList()
        return evs.groupBy { it.target ?: it.subType }.map { (target, list) ->
            TargetSummary(
                target = target,
                count = list.size.toLong(),
                callerCount = list.map { it.uid }.distinct().size,
                lastTs = list.maxOf { it.ts },
                kind = kindOf(cat),
            )
        }.sortedByDescending { it.count }
    }

    private fun hourly(evs: List<PrivacyEvent>): List<Int> {
        val buckets = IntArray(24)
        for (e in evs) buckets[Format.hourOf(e.ts)]++
        return buckets.toList()
    }

    private fun categorySummary(cat: PrivacyCategory, today: List<PrivacyEvent>): CategorySummary {
        val evs = today.filter { it.category == cat }
        val callers = callerSummaries(cat, evs)
        val lastTs = events.firstOrNull { it.category == cat }?.ts
        return CategorySummary(
            category = cat,
            countToday = evs.size.toLong(),
            callerCount = callers.size,
            lastTs = lastTs,
            topCaller = callers.firstOrNull(),
            todayHourly = hourly(evs),
        )
    }

    fun dashboard(): DashboardState {
        val today = events.filter { it.ts >= dayStart }
        val categories = PrivacyCategory.entries.map { categorySummary(it, today) }
        return DashboardState(
            rootAvailable = true,
            totalToday = categories.sumOf { it.countToday },
            appCount = today.map { it.uid }.distinct().size,
            domainCount = today.filter { it.category == PrivacyCategory.NETWORK }
                .mapNotNull { it.target }.distinct().size,
            categories = categories,
            recent = events.take(16),
            activeProbeCount = 9,
            rootHint = "KernelSU · uid=0(root) · SELinux Enforcing",
        )
    }

    fun category(cat: PrivacyCategory): CategoryDetailState {
        val catEvents = events.filter { it.category == cat }
        val today = catEvents.filter { it.ts >= dayStart }
        val basis = today.ifEmpty { catEvents }
        val callers = callerSummaries(cat, basis)
        val targets = targetSummaries(cat, basis)
        return CategoryDetailState(
            category = cat,
            countToday = today.size.toLong(),
            countTotal = catEvents.size.toLong(),
            callerCount = callers.size,
            targetCount = targets.size,
            lastTs = catEvents.firstOrNull()?.ts,
            callers = callers,
            targets = targets,
            todayHourly = hourly(today),
        )
    }

    fun caller(cat: PrivacyCategory, uid: Int): CallerDetailState {
        val evs = events.filter { it.category == cat && it.uid == uid }
        val today = evs.filter { it.ts >= dayStart }
        val meta = byUid[uid]
        val summary = callerSummaries(cat, evs).firstOrNull() ?: CallerSummary(
            uid = uid,
            packageName = meta?.pkg,
            label = meta?.label ?: "未知应用",
            count = 0,
            lastTs = 0,
            targetCount = 0,
            isSystem = meta?.system ?: false,
        )
        return CallerDetailState(
            category = cat,
            caller = summary,
            todayCount = today.size.toLong(),
            totalCount = evs.size.toLong(),
            firstTs = evs.lastOrNull()?.ts,
            lastTs = evs.firstOrNull()?.ts,
            targets = targetSummaries(cat, evs),
            events = evs.take(120),
        )
    }

    fun target(cat: PrivacyCategory, target: String): TargetDetailState {
        val evs = events.filter { it.category == cat && (it.target == target || it.subType == target) }
        val summary = targetSummaries(cat, evs).firstOrNull() ?: TargetSummary(
            target = target,
            count = 0,
            callerCount = 0,
            lastTs = 0,
            kind = kindOf(cat),
        )
        return TargetDetailState(
            category = cat,
            target = summary,
            callers = callerSummaries(cat, evs),
            events = evs.take(120),
        )
    }
}
