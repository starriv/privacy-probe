package dev.popsicle.privprobe.core.collector

import android.content.Context
import dev.popsicle.privprobe.core.data.ProbeId
import kotlinx.coroutines.flow.StateFlow

data class ProbeStatus(
    val healthy: Boolean = false,
    val detail: String = "",
    val lastEventTs: Long = 0L,
)

/**
 * 采集服务的控制面。UI 通过它启停采集、读取 root / 探针状态。
 * 实现类：dev.popsicle.privprobe.service.CollectorControllerImpl（单例，由 AppGraph 提供）。
 */
interface CollectorControl {
    val running: StateFlow<Boolean>
    val rootAvailable: StateFlow<Boolean>
    val rootDetail: StateFlow<String>
    val lastError: StateFlow<String?>
    val probeStatus: StateFlow<Map<ProbeId, ProbeStatus>>

    fun start(context: Context)
    fun stop(context: Context)

    /** 重新检测 root 并在需要时重启采集。 */
    suspend fun refresh(context: Context)

    /** 触发一次自检：DNS 查询 + 相机 + 麦克风 + 位置 + 传感器 + 剪贴板，用于验证采集链路。 */
    suspend fun runSelfTest(context: Context)
}
