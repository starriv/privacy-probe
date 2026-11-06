package dev.popsicle.privprobe.core.data

import kotlinx.coroutines.flow.StateFlow

/** 每个探针一个开关；id 与采集实现一一对应。 */
enum class ProbeId(val title: String, val description: String) {
    PCAP("网络数据包", "AF_PACKET 抓包，解析 DNS / TLS SNI / HTTP Host"),
    DNS_TRACE("DNS 调用链", "uprobe 挂 libc getaddrinfo，记录发起应用与被查询域名"),
    KERNEL_OPEN("内核打开事件", "kprobe 跟踪 /dev/video* / /dev/snd 等敏感设备打开"),
    CAMERA("相机服务", "轮询 CameraService 活跃客户端与会话事件"),
    AUDIO("音频服务", "轮询 AudioFlinger 输入线程与录音活动"),
    LOCATION("定位服务", "轮询 LocationManager 监听器与定位投递计数"),
    SENSOR("传感器服务", "轮询 SensorService 激活事件"),
    CLIPBOARD("剪贴板", "日志 + AppOps 剪贴板访问"),
    SCREEN("屏幕采集", "轮询 MediaProjection 活跃会话"),
    APPOPS("敏感权限审计", "轮询 AppOps 最近访问记录（相机/麦克风/位置/短信等）"),
    APP_FOREGROUND("前台应用", "记录应用启动与前台切换，补充上下文"),
}

data class AppSettings(
    val collectorEnabled: Boolean = false,
    val foregroundNotification: Boolean = true,
    val showSystemApps: Boolean = true,
    val retentionDays: Int = 7,
    /** 轮询类探针基础间隔。 */
    val pollIntervalMs: Long = 1500L,
    /** AppOps 全量转储较重，单独低频。 */
    val appOpsIntervalMs: Long = 15000L,
    val probes: Map<ProbeId, Boolean> = DEFAULT_PROBES,
    /** 域名展示脱敏（例如 a***.example.com），导出不受影响。 */
    val maskDomains: Boolean = false,
    /** 关闭后只保留系统服务 / 日志类探针，不启动原生低层采集。 */
    val lowLevelEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT_PROBES: Map<ProbeId, Boolean> = ProbeId.entries.associateWith { true }
    }
}

interface SettingsStore {
    val state: StateFlow<AppSettings>
    fun snapshot(): AppSettings
    fun update(transform: (AppSettings) -> AppSettings)
}
