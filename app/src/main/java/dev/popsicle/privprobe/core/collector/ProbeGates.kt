package dev.popsicle.privprobe.core.collector

import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyCategory

/**
 * 大类 → 探针开关映射（P1-7）。
 *
 * 专属探针（相机/音频/定位/传感器/屏幕）本身会被 [ProbeManager] 按开关决定是否启动，
 * 但 LogcatProbe / AppOpsProbe 一个探针覆盖多个大类：例如用户关掉「相机」但保留「剪贴板」时，
 * logcat 与 appops 仍在运行，若不逐类 gate，相机事件仍会继续入库。所有探针在发射前都应经过
 * 本对象判断（网络类由 PCAP / DNS_TRACE 控制）。
 */
object ProbeGates {

    fun probeIdFor(category: PrivacyCategory): ProbeId = when (category) {
        PrivacyCategory.NETWORK -> ProbeId.DNS_TRACE
        PrivacyCategory.CAMERA -> ProbeId.CAMERA
        PrivacyCategory.MICROPHONE -> ProbeId.AUDIO
        PrivacyCategory.LOCATION -> ProbeId.LOCATION
        PrivacyCategory.SENSOR -> ProbeId.SENSOR
        PrivacyCategory.CLIPBOARD -> ProbeId.CLIPBOARD
        PrivacyCategory.SCREEN -> ProbeId.SCREEN
        // 电话短信 / 联系人 / 设备标识没有独立开关，仅由 AppOps 审计产出，跟 APPOPS 开关走。
        PrivacyCategory.TELEPHONY,
        PrivacyCategory.CONTACTS,
        PrivacyCategory.DEVICE_ID,
        -> ProbeId.APPOPS
        PrivacyCategory.APP_ACTIVITY -> ProbeId.APP_FOREGROUND
    }

    fun isProbeEnabled(settings: AppSettings, id: ProbeId): Boolean = settings.probes[id] != false

    fun isEnabled(settings: AppSettings, category: PrivacyCategory): Boolean =
        isProbeEnabled(settings, probeIdFor(category))

}
