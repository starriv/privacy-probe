package dev.popsicle.privprobe.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.core.intel.RepoIntel
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.TargetDetailState
import dev.popsicle.privprobe.ui.UiSources
import dev.popsicle.privprobe.ui.components.CallerMiniRow
import dev.popsicle.privprobe.ui.components.Chip
import dev.popsicle.privprobe.ui.components.EmptyState
import dev.popsicle.privprobe.ui.components.Hairline
import dev.popsicle.privprobe.ui.components.KpiCard
import dev.popsicle.privprobe.ui.components.KpiTileData
import dev.popsicle.privprobe.ui.components.LoadingState
import dev.popsicle.privprobe.ui.components.ProbeCard
import dev.popsicle.privprobe.ui.components.ProbeScaffold
import dev.popsicle.privprobe.ui.components.SectionTitle
import dev.popsicle.privprobe.ui.components.TimelineRow
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText
import dev.popsicle.privprobe.ui.util.Format
import dev.popsicle.privprobe.ui.util.Intel

/**
 * 目标详情：header + 3 联 KPI + 知识库关联 + 调用者排名 + 事件时间线。
 */
@Composable
fun TargetScreen(
    sources: UiSources,
    category: PrivacyCategory,
    target: String,
    onBack: () -> Unit,
    onOpenCaller: (Int, String?) -> Unit,
) {
    val detail by remember(sources, category, target) {
        sources.repository.observeTarget(category, target)
    }.collectAsStateWithLifecycle(initialValue = null)
    val settings by sources.settings.state.collectAsStateWithLifecycle()
    val state = detail
    val p = LocalProbePalette.current
    val display = Intel.displayTarget(target, category, settings.maskDomains)
    // 滚动位置随页面状态保存：进入调用者详情再返回时停在原处。
    val listState = rememberLazyListState()

    ProbeScaffold(
        title = "目标",
        onBack = onBack,
        trailing = { Chip(text = Intel.targetKindLabel(state?.target?.kind ?: category.id), color = p.muted) },
    ) {
        if (state == null) {
            LoadingState()
        } else {
            TargetContent(
                state = state,
                listState = listState,
                category = category,
                maskDomains = settings.maskDomains,
                onOpenCaller = onOpenCaller,
            )
        }
    }
}

@Composable
private fun TargetContent(
    state: TargetDetailState,
    listState: LazyListState,
    category: PrivacyCategory,
    maskDomains: Boolean,
    onOpenCaller: (Int, String?) -> Unit,
) {
    val p = LocalProbePalette.current
    val target = state.target
    val display = Intel.displayTarget(target.target, category, maskDomains)
    val domainIntel = remember(target.target) {
        if (category == PrivacyCategory.NETWORK) RepoIntel.domainIntel(target.target) else null
    }
    val events = state.events
    val callerTotal = state.callers.sumOf { it.count }.coerceAtLeast(1)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
    ) {
        item {
            Column {
                Text(text = display, style = ProbeText.Label, color = p.muted)
                Spacer(Modifier.height(12.dp))
                KpiCard(
                    tiles = listOf(
                        KpiTileData("累计", Format.count(target.count)),
                        KpiTileData("调用者", Format.count(state.callers.size.toLong())),
                        KpiTileData("最近", if (target.lastTs > 0) Format.clock(target.lastTs) else "—"),
                    ),
                    valueStyle = ProbeText.KpiSmall,
                )
            }
        }

        val packages = domainIntel?.packages?.take(6).orEmpty()
        if (packages.isNotEmpty()) {
            item { SectionTitle(text = "知识库关联应用") }
            item {
                ProbeCard(padding = 0.dp) {
                    packages.forEachIndexed { index, pkg ->
                        if (index > 0) Hairline(modifier = Modifier.padding(start = 16.dp))
                        Text(
                            text = pkg,
                            style = ProbeText.NumberSmall,
                            color = p.ink2,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        item {
            SectionTitle(
                text = "调用者",
                trailing = {
                    Text(
                        text = "${state.callers.size} 个应用",
                        style = ProbeText.Meta,
                        color = p.muted,
                    )
                },
            )
        }
        if (state.callers.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无调用者",
                    hint = "该目标还没有被归因到具体应用。",
                )
            }
        } else {
            itemsIndexed(
                items = state.callers,
                key = { index, caller -> "${caller.uid}-${caller.packageName.orEmpty()}-$index" },
            ) { index, caller ->
                Column {
                    if (index > 0) Hairline()
                    CallerMiniRow(
                        caller = caller,
                        totalCount = callerTotal,
                        onClick = { onOpenCaller(caller.uid, caller.packageName) },
                    )
                }
            }
        }

        item {
            SectionTitle(
                text = "事件",
                trailing = {
                    Text(
                        text = "共 ${Format.count(events.size.toLong())} 条",
                        style = ProbeText.Meta,
                        color = p.muted,
                    )
                },
            )
        }
        if (events.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无事件",
                    hint = "该目标还没有事件记录。",
                )
            }
        } else {
            itemsIndexed(
                items = events,
                key = { index, event -> "${event.ts}-${event.uid}-${event.category.id}-${event.source.name}-${event.target.orEmpty()}-${event.subType}-$index" },
            ) { index, event ->
                Column {
                    TimelineRow(event = event, maskDomains = maskDomains)
                    if (index != events.lastIndex) Hairline()
                }
            }
        }
    }
}
