package dev.popsicle.privprobe.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import dev.popsicle.privprobe.core.model.CallerDetailState
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.ui.UiSources
import dev.popsicle.privprobe.ui.components.Chip
import dev.popsicle.privprobe.ui.components.EmptyState
import dev.popsicle.privprobe.ui.components.Hairline
import dev.popsicle.privprobe.ui.components.InfoRow
import dev.popsicle.privprobe.ui.components.KpiCard
import dev.popsicle.privprobe.ui.components.KpiTileData
import dev.popsicle.privprobe.ui.components.LoadingState
import dev.popsicle.privprobe.ui.components.ProbeCard
import dev.popsicle.privprobe.ui.components.ProbeScaffold
import dev.popsicle.privprobe.ui.components.SectionTitle
import dev.popsicle.privprobe.ui.components.TargetRow
import dev.popsicle.privprobe.ui.components.TimelineRow
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText
import dev.popsicle.privprobe.ui.util.Format
import dev.popsicle.privprobe.ui.util.Intel

/**
 * 调用者详情：header + 4 联 KPI + 仓库知识库定义列表 + 目标分布 + 事件时间线。
 */
@Composable
fun CallerScreen(
    sources: UiSources,
    category: PrivacyCategory,
    uid: Int,
    packageName: String?,
    onBack: () -> Unit,
    onOpenTarget: (String) -> Unit,
) {
    val detail by remember(sources, category, uid, packageName) {
        sources.repository.observeCaller(category, uid, packageName)
    }.collectAsStateWithLifecycle(initialValue = null)
    val settings by sources.settings.state.collectAsStateWithLifecycle()
    val state = detail
    val p = LocalProbePalette.current
    val title = state?.let { Intel.callerLabel(it.caller.packageName, it.caller.label) } ?: "调用者"
    // 滚动位置随页面状态保存：进入目标详情再返回时停在原处。
    val listState = rememberLazyListState()

    ProbeScaffold(
        title = title,
        onBack = onBack,
        trailing = { Chip(text = category.shortTitle, color = p.muted) },
    ) {
        if (state == null) {
            LoadingState()
        } else {
            CallerContent(
                state = state,
                listState = listState,
                category = category,
                maskDomains = settings.maskDomains,
                onOpenTarget = onOpenTarget,
            )
        }
    }
}

@Composable
private fun CallerContent(
    state: CallerDetailState,
    listState: LazyListState,
    category: PrivacyCategory,
    maskDomains: Boolean,
    onOpenTarget: (String) -> Unit,
) {
    val p = LocalProbePalette.current
    val caller = state.caller
    val intel = remember(caller.packageName) { Intel.packageIntel(caller.packageName) }
    val events = state.events
    val targetTotal = state.targets.sumOf { it.count }.coerceAtLeast(1)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
    ) {
        item {
            Column {
                Text(
                    text = caller.packageName ?: "未知包名",
                    style = ProbeText.Label,
                    color = p.muted,
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Chip(text = "uid ${caller.uid}", color = p.muted)
                    if (caller.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Chip(text = "系统应用", color = p.muted)
                    }
                    if (Intel.isDebloatCleaned(caller.packageName)) {
                        Spacer(Modifier.width(6.dp))
                        Chip(text = "已清理", color = p.muted)
                    }
                }
                Spacer(Modifier.height(12.dp))
                KpiCard(
                    tiles = listOf(
                        KpiTileData("今日", Format.count(state.todayCount)),
                        KpiTileData("累计", Format.count(state.totalCount)),
                        KpiTileData("目标", Format.count(state.targets.size.toLong())),
                        KpiTileData("首见", state.firstTs?.let { Format.clock(it) } ?: "—"),
                    ),
                    valueStyle = ProbeText.KpiSmall,
                )
            }
        }

        if (intel != null) {
            item { SectionTitle(text = "仓库知识库") }
            item {
                ProbeCard(padding = 0.dp) {
                    if (intel.category != null) {
                        InfoRow(label = "分类", value = intel.category)
                    }
                    if (intel.vendor != null) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "厂商", value = intel.vendor)
                    }
                    if (intel.score != null) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "审计评分", value = intel.score.toString())
                    }
                    if (intel.risk != null) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "风险", value = intel.risk, valueColor = p.warn)
                    }
                    if (intel.action != null) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "处置建议", value = intel.action)
                    }
                    if (intel.dataTypes.isNotEmpty()) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "数据类型", value = intel.dataTypes.joinToString("、"))
                    }
                    val endpoints = intel.endpoints.take(4)
                    if (endpoints.isNotEmpty()) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = "端点 ${endpoints.size}",
                                style = ProbeText.Label,
                                color = p.muted,
                            )
                            Spacer(Modifier.height(4.dp))
                            endpoints.forEach { endpoint ->
                                Text(
                                    text = endpoint,
                                    style = ProbeText.NumberSmall,
                                    color = p.ink2,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                    val reason = intel.reason
                    if (!reason.isNullOrBlank()) {
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        Text(
                            text = reason,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }

        item {
            SectionTitle(
                text = "目标分布",
                trailing = {
                    Text(
                        text = "${state.targets.size} 个",
                        style = ProbeText.Meta,
                        color = p.muted,
                    )
                },
            )
        }
        if (state.targets.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无目标",
                    hint = "该调用者在此大类下还没有可归类的目标。",
                )
            }
        } else {
            itemsIndexed(
                items = state.targets,
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

        item {
            SectionTitle(
                text = "事件时间线",
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
                    hint = "该调用者在此大类下还没有事件记录。",
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
