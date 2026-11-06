package dev.popsicle.privprobe.core.collector.net

import android.content.Context
import dev.popsicle.privprobe.core.collector.Probe
import dev.popsicle.privprobe.core.collector.ProbeSink
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyEvent
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把 [NativeStreamProbe]（回调式、自带线程/重启）适配成 [Probe]（协程式、由 ProbeManager 调度）。
 *
 * - PCAP / DNS_TRACE / KERNEL_OPEN 三个开关共用同一个 privd 进程，所以 [id] 用 PCAP，
 *   [statusIds] 覆盖三个开关，分别显示各子系统真实就绪状态。
 * - 事件通过 sink.emit 进入 ProbeManager 的批量落库通道。
 * - 协程取消时先 SIGTERM 清理 tracefs，再取消 scope，保证无残留 su/privd 进程。
 */
class NativeProbeWrapper(
    private val context: Context,
) : Probe {

    override val id: ProbeId = ProbeId.PCAP

    override val statusIds: List<ProbeId> = listOf(
        ProbeId.PCAP,
        ProbeId.DNS_TRACE,
        ProbeId.KERNEL_OPEN,
    )

    override suspend fun run(sink: ProbeSink) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // P1-12：每事件 launch + ProbeManager trySend 静默丢事件 → 改为固定容量 Channel，
        // 下游单协程顺序批量消费；队列满时计数 dropped 并通过 status 暴露，不再静默丢。
        val queue = Channel<PrivacyEvent>(capacity = 2048)
        val dropped = AtomicLong(0)
        val probe = NativeStreamProbe(
            context = context,
            settingsProvider = { sink.settings },
            sink = { event ->
                if (!queue.trySend(event).isSuccess) dropped.incrementAndGet()
            },
        )
        val writer = scope.launch {
            val batch = ArrayList<PrivacyEvent>(64)
            var lastReportAt = 0L
            for (event in queue) {
                batch.add(event)
                while (batch.size < 64) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                runCatching { sink.emitAll(batch) }
                batch.clear()
                val n = dropped.get()
                val now = System.currentTimeMillis()
                if (n > 0 && now - lastReportAt > 2_000) {
                    lastReportAt = now
                    sink.status("原生采集下游拥塞：累计丢弃 $n 条事件", healthy = false)
                }
            }
        }
        try {
            sink.status("原生采集器启动中…", healthy = false)
            probe.start(scope)
            coroutineScope {
                launch {
                    probe.featureStatuses.collect { statuses ->
                        statuses.forEach { (id, state) ->
                            sink.statusForProbe(id, state.detail, state.healthy)
                        }
                    }
                }
                awaitCancellation()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    probe.stop()
                    queue.close()
                    if (withTimeoutOrNull(2_000) { writer.join(); true } != true) {
                        sink.log("native queue drain timed out")
                    }
                } finally {
                    scope.cancel()
                    writer.cancelAndJoin()
                    sink.status("已停止", healthy = false)
                }
            }
        }
    }
}
