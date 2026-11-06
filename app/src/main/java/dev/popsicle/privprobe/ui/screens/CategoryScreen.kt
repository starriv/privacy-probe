package dev.popsicle.privprobe.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.ui.UiSources
import dev.popsicle.privprobe.ui.components.CallerRow
import dev.popsicle.privprobe.ui.components.EmptyState
import dev.popsicle.privprobe.ui.components.Hairline
import dev.popsicle.privprobe.ui.components.KpiCard
import dev.popsicle.privprobe.ui.components.KpiTileData
import dev.popsicle.privprobe.ui.components.LoadingState
import dev.popsicle.privprobe.ui.components.ProbeScaffold
import dev.popsicle.privprobe.ui.components.RiskChip
import dev.popsicle.privprobe.ui.components.SectionTitle
import dev.popsicle.privprobe.ui.components.SegmentedTabs
import dev.popsicle.privprobe.ui.components.TargetRow
import dev.popsicle.privprobe.ui.components.TimelineRow
import dev.popsicle.privprobe.ui.util.Format

/**
 * 大类详情：header + 4 联 KPI 卡 + 三段 tabs + ranked 列表 / 时间线。
 */
@Composable
fun CategoryScreen(
    sources: UiSources,
    category: PrivacyCategory,
    onBack: () -> Unit,
    onOpenCaller: (Int) -> Unit,
    onOpenTarget: (String) -> Unit,
) {
    val detail by remember(sources, category) { sources.repository.observeCategory(category) }
        .collectAsStateWithLifecycle(initialValue = null)
    val settings by sources.settings.state.collectAsStateWithLifecycle()
    val state = detail
    var tab by remember(category) { mutableIntStateOf(0) }
    val maskDomains = settings.maskDomains
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var page by remember(category, settings.showSystemApps) { mutableIntStateOf(0) }

    ProbeScaffold(
        title = category.title,
        onBack = onBack,
        trailing = { RiskChip(category.risk) },
    ) {
        if (state == null) {
            LoadingState()
        } else {
            val current = state
            val timeline by produceState(
                initialValue = emptyList<PrivacyEvent>(), category, tab, page, settings.showSystemApps, lifecycle,
            ) {
                value = emptyList()
                if (tab != 2) return@produceState
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        try {
                            value = sources.repository.timelineForCategory(category, 120, page * 120)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            // 暂时读库失败保留上一次结果。
                        }
                        kotlinx.coroutines.delay(2_000)
                    }
                }
            }
            val callerTotal = current.callers.sumOf { it.count }.coerceAtLeast(1)
            val targetTotal = current.targets.sumOf { it.count }.coerceAtLeast(1)

            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            ) {
                item {
                    KpiCard(
                        tiles = listOf(
                            KpiTileData("今日", Format.count(current.countToday)),
                            KpiTileData("累计", Format.count(current.countTotal)),
                            KpiTileData("调用者", Format.count(current.callerCount.toLong())),
                            KpiTileData("目标", Format.count(current.targetCount.toLong())),
                        ),
                        valueStyle = dev.popsicle.privprobe.ui.theme.ProbeText.KpiSmall,
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    SegmentedTabs(
                        labels = listOf(
                            "调用者 ${current.callerCount}",
                            "目标 ${current.targetCount}",
                            "时间线",
                        ),
                        selectedIndex = tab,
                        onSelect = { tab = it },
                    )
                }
                // tab 与下方 ranked list / 时间线之间留出与卡片一致的 8dp 呼吸。
                item { Spacer(Modifier.height(8.dp)) }
                when (tab) {
                    0 -> {
                        if (current.callers.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "今天还没有调用者",
                                    hint = "该大类今日暂无记录，历史事件会随采集累积。",
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = current.callers,
                                key = { index, caller -> "${caller.uid}-${caller.packageName.orEmpty()}-$index" },
                            ) { index, caller ->
                                Column {
                                    if (index > 0) Hairline()
                                    CallerRow(
                                        caller = caller,
                                        category = category,
                                        maskDomains = maskDomains,
                                        totalCount = callerTotal,
                                        onClick = { onOpenCaller(caller.uid) },
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        if (current.targets.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "暂无目标",
                                    hint = "没有解析到域名 / 传感器 / 摄像头等目标。",
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = current.targets,
                                key = { index, target -> "${target.target}-$index" },
                            ) { index, target ->
                                Column {
                                    if (index > 0) Hairline()
                                    TargetRow(
                                        target = target,
                                        category = category,
                                        maskDomains = maskDomains,
                                        totalCount = targetTotal,
                                        onClick = { onOpenTarget(target.target) },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        item {
                            Row {
                                TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                                TextButton(onClick = { page++ }, enabled = timeline.size == 120) { Text("下一页") }
                                Text("第 ${page + 1} 页 · 保留期内记录")
                            }
                        }
                        if (timeline.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "时间线为空",
                                    hint = "时间线由调用者事件聚合，采集到新事件后自动刷新。",
                                )
                            }
                        } else {
                            item {
                                SectionTitle(text = "时间线 · ${timeline.size} 条")
                            }
                            itemsIndexed(
                                items = timeline,
                                key = { index, event -> "${event.ts}-${event.uid}-${event.category.id}-${event.source.name}-${event.target.orEmpty()}-${event.subType}-$index" },
                            ) { index, event ->
                                Column {
                                    TimelineRow(event = event, maskDomains = maskDomains)
                                    if (index != timeline.lastIndex) Hairline()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
