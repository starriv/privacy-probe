package dev.popsicle.privprobe.ui.util

import dev.popsicle.privprobe.core.intel.PackageIntel
import dev.popsicle.privprobe.core.intel.RepoIntel
import dev.popsicle.privprobe.core.model.PrivacyCategory

/**
 * 仓库知识库（[RepoIntel]）的 UI 侧轻封装：
 * 优先用知识库里的中文名 / 分类信息，未 init 或未命中时安全回退到事件自带字段。
 */
object Intel {

    fun packageIntel(packageName: String?): PackageIntel? = RepoIntel.packageIntel(packageName)

    /** 调用者显示名：知识库中文名 > 事件里的 appLabel > 包名。 */
    fun callerLabel(packageName: String?, fallbackLabel: String?): String {
        val known = RepoIntel.packageIntel(packageName)?.label
        return known?.takeIf { it.isNotBlank() }
            ?: fallbackLabel?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: "未知应用"
    }

    /** 本仓库 Debloat 已清理的系统包。 */
    fun isDebloatCleaned(packageName: String?): Boolean = RepoIntel.isDebloatCleaned(packageName)

    /** 目标展示文本：网络类支持域名脱敏。 */
    fun displayTarget(target: String?, category: PrivacyCategory, maskDomains: Boolean): String {
        val raw = target?.takeIf { it.isNotBlank() } ?: "—"
        return if (maskDomains && category == PrivacyCategory.NETWORK) Format.maskDomain(raw) else raw
    }

    /** 目标类型的中文标签（数据层 kind 是英文枚举值，UI 统一转中文）。 */
    fun targetKindLabel(kind: String?): String = when (kind?.trim()?.lowercase()) {
        "domain" -> "域名"
        "tracker" -> "追踪器"
        "functional" -> "功能域名"
        "sensor" -> "传感器"
        "camera" -> "摄像头"
        "audio", "mic" -> "音频输入"
        "location" -> "定位"
        "screen" -> "投屏会话"
        "clipboard" -> "剪贴板"
        "telephony" -> "权限"
        "contacts" -> "数据读取"
        "device_id" -> "设备标识"
        "app", "activity" -> "应用"
        else -> kind?.takeIf { it.isNotBlank() } ?: "目标"
    }

}
