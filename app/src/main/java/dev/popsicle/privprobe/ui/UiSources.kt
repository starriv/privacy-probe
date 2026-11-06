package dev.popsicle.privprobe.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.popsicle.privprobe.core.AppGraph
import dev.popsicle.privprobe.core.collector.CollectorControl
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.ui.preview.PreviewCollectorControl
import dev.popsicle.privprobe.ui.preview.PreviewRepository
import dev.popsicle.privprobe.ui.preview.PreviewSettingsStore
import kotlinx.coroutines.delay

/**
 * UI 依赖的三件套：数据仓库 / 设置 / 采集控制。
 *
 * 并行开发期 [AppGraph.ready] 可能还是 false，此时退化为内置预览数据源，
 * 但会持续轮询 ready 状态，采集后端一就绪即自动切换到真实数据，无需重启 App。
 */
data class UiSources(
    val repository: PrivacyRepository,
    val settings: SettingsStore,
    val collector: CollectorControl,
    /** true 表示当前用的是内置预览数据（UI 会显示「预览数据」角标）。 */
    val preview: Boolean,
)

@Composable
fun rememberUiSources(): UiSources {
    var ready by remember { mutableStateOf(AppGraph.ready) }

    LaunchedEffect(Unit) {
        while (!ready) {
            delay(500)
            ready = AppGraph.ready
        }
    }

    return remember(ready) {
        if (ready) {
            runCatching {
                UiSources(
                    repository = AppGraph.repository,
                    settings = AppGraph.settings,
                    collector = AppGraph.collector,
                    preview = false,
                )
            }.getOrElse { previewSources() }
        } else {
            previewSources()
        }
    }
}

private fun previewSources(): UiSources = UiSources(
    repository = PreviewRepository(),
    settings = PreviewSettingsStore(),
    collector = PreviewCollectorControl(),
    preview = true,
)
