package dev.popsicle.privprobe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.core.model.CategorySummary
import dev.popsicle.privprobe.core.model.DashboardState
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.ui.UiSources
import dev.popsicle.privprobe.ui.components.CategoryIcon
import dev.popsicle.privprobe.ui.components.EmptyState
import dev.popsicle.privprobe.ui.components.EventRow
import dev.popsicle.privprobe.ui.components.Hairline
import dev.popsicle.privprobe.ui.components.KpiCard
import dev.popsicle.privprobe.ui.components.KpiTileData
import dev.popsicle.privprobe.ui.components.LiveDot
import dev.popsicle.privprobe.ui.components.LoadingState
import dev.popsicle.privprobe.ui.components.OutlineButton
import dev.popsicle.privprobe.ui.components.PreviewBadge
import dev.popsicle.privprobe.ui.components.PrimaryButton
import dev.popsicle.privprobe.ui.components.ProbeCard
import dev.popsicle.privprobe.ui.components.ProbeScaffold
import dev.popsicle.privprobe.ui.components.RootWarningCard
import dev.popsicle.privprobe.ui.components.SectionTitle
import dev.popsicle.privprobe.ui.components.ShareBar
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText
import dev.popsicle.privprobe.ui.util.Format
import dev.popsicle.privprobe.ui.util.Intel
import kotlinx.coroutines.launch

/**
 * 总览：一行中性状态 + KPI 四联卡 + 2 列大类卡片 + 最近事件 hairline 列表。
 * 一个强调色，大类图标中性单色；问题用语义色，其余灰。
 */
@Composable
fun DashboardScreen(
    sources: UiSources,
    onOpenCategory: (PrivacyCategory) -> Unit,
    onOpenCaller: (PrivacyCategory, Int, String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by remember(sources) { sources.repository.observeDashboard() }
        .collectAsStateWithLifecycle(initialValue = null)
    val settings by sources.settings.state.collectAsStateWithLifecycle()
    val running by sources.collector.running.collectAsStateWithLifecycle()
    val rootAvailable by sources.collector.rootAvailable.collectAsStateWithLifecycle()
    val probeStatus by sources.collector.probeStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = LocalProbePalette.current
    // 滚动位置随页面状态保存：进入大类 / 调用者详情再返回，或切底部 tab 回来时都不回到顶部。
    val listState = rememberLazyListState()

    ProbeScaffold(
        title = "隐私探针",
        // 36dp 图标按钮外框右移到 8dp，使 20dp 齿轮图标的光学右边缘与卡片 16dp 右基准对齐。
        headerEnd = 8.dp,
        trailing = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "配置",
                    tint = p.ink2,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    ) {
        val current = state
        if (current == null) {
            LoadingState()
        } else {
            val enabledProbeCount = ProbeId.entries.count { settings.probes[it] == true }
            DashboardContent(
                state = current,
                listState = listState,
                preview = sources.preview,
                maskDomains = settings.maskDomains,
                running = running,
                rootAvailable = rootAvailable,
                collectorEnabled = settings.collectorEnabled,
                enabledProbeCount = enabledProbeCount,
                probeCount = probeStatus.count { it.value.healthy }
                    .takeIf { it > 0 } ?: current.activeProbeCount,
                onOpenCategory = onOpenCategory,
                onOpenCaller = onOpenCaller,
                onOpenSettings = onOpenSettings,
                onRefreshRoot = { scope.launch { runCatching { sources.collector.refresh(context) } } },
            )
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    listState: LazyListState,
    preview: Boolean,
    maskDomains: Boolean,
    running: Boolean,
    rootAvailable: Boolean,
    collectorEnabled: Boolean,
    enabledProbeCount: Int,
    probeCount: Int,
    onOpenCategory: (PrivacyCategory) -> Unit,
    onOpenCaller: (PrivacyCategory, Int, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshRoot: () -> Unit,
) {
    val p = LocalProbePalette.current
    val recent = remember(state) { state.recent.take(12) }
    val updatedAt = remember(state) {
        state.recent.firstOrNull()?.ts
            ?: state.categories.mapNotNull { it.lastTs }.maxOrNull()
    }
    val hourly = remember(state.categories) { mergeHourly(state.categories) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (preview) {
                    PreviewBadge()
                    Spacer(Modifier.width(10.dp))
                }
                if (running) {
                    LiveDot(color = p.live)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = buildString {
                        if (!collectorEnabled) {
                            // 默认关闭时不要显示「无 root / 采集暂停」这类误导状态
                            append("采集未开启")
                            append(" · 探针开关 ")
                            append(enabledProbeCount)
                            append(" 个")
                        } else {
                            append(if (running) "采集中" else "已暂停")
                            append(" · ")
                            append(if (rootAvailable) "root ✓" else "root 未授权")
                            append(" · 探针 ")
                            append(probeCount)
                            append("/")
                            append(enabledProbeCount)
                            append(" 健康")
                        }
                    },
                    style = ProbeText.Label,
                    color = p.ink2,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "更新于 ${updatedAt?.let { Format.clock(it) } ?: "—"}",
                    style = ProbeText.Meta,
                    color = p.muted,
                    maxLines = 1,
                )
            }
        }

        if (!collectorEnabled) {
            item {
                Spacer(Modifier.height(12.dp))
                ProbeCard {
                    Text(
                        text = "采集未开启",
                        style = MaterialTheme.typography.titleMedium,
                        color = p.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "默认不采集。开启后会启动前台服务与 root 探针，所有数据仅保存在本机、不会上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        text = "前往配置开启",
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else if (!rootAvailable) {
            item {
                Spacer(Modifier.height(10.dp))
                RootWarningCard(hint = state.rootHint, onRefresh = onRefreshRoot)
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            KpiCard(
                tiles = listOf(
                    KpiTileData("今日事件", Format.count(state.totalToday), sparkline = hourly),
                    KpiTileData("应用", Format.count(state.appCount.toLong()), baseline = "个应用"),
                    KpiTileData("域名", Format.count(state.domainCount.toLong()), baseline = "个域名"),
                    KpiTileData("活跃探针", Format.count(probeCount.toLong()), baseline = "个探针"),
                ),
            )
        }

        item {
            SectionTitle(
                text = "隐私大类",
                trailing = {
                    Text(text = "今日", style = ProbeText.Meta, color = p.muted)
                },
            )
        }

        items(
            items = state.categories.chunked(2),
            key = { row -> row.joinToString("|") { it.category.id } },
        ) { row ->
            val maxToday = state.categories.maxOfOrNull { it.countToday }?.coerceAtLeast(1) ?: 1
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { summary ->
                    CategoryCard(
                        summary = summary,
                        maxCount = maxToday,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenCategory(summary.category) },
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        item {
            SectionTitle(
                text = "最近事件",
                trailing = {
                    Text(
                        text = "共 ${Format.count(state.recent.size.toLong())} 条",
                        style = ProbeText.Meta,
                        color = p.muted,
                    )
                },
            )
        }

        if (recent.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无事件",
                    hint = "采集运行后，这里会显示最近捕获的敏感调用。",
                )
            }
        } else {
            itemsIndexed(
                items = recent,
                key = { index, event -> "${event.ts}-${event.uid}-${event.category.id}-${event.source.name}-${event.target.orEmpty()}-${event.subType}-$index" },
            ) { index, event ->
                Column {
                    EventRow(
                        event = event,
                        maskDomains = maskDomains,
                        onClick = { onOpenCaller(event.category, event.uid, event.packageName) },
                    )
                    if (index != recent.lastIndex) Hairline()
                }
            }
        }

        if (state.totalToday == 0L) {
            item {
                EmptyState(
                    title = "今天还没有敏感事件",
                    hint = "确认采集已开启，或到配置页运行一次链路自检。",
                    action = {
                        OutlineButton(
                            text = "前往配置",
                            onClick = onOpenSettings,
                            modifier = Modifier.width(132.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    summary: CategorySummary,
    maxCount: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalProbePalette.current
    val shape = RoundedCornerShape(16.dp)
    val fraction = if (maxCount <= 0) 0f else summary.countToday.toFloat() / maxCount
    Column(
        modifier = modifier
            .then(
                if (!p.dark) {
                    Modifier.shadow(
                        elevation = 1.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor = Color.Black.copy(alpha = 0.05f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(p.card)
            .border(1.dp, p.line, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(summary.category, size = 28.dp, glyphSize = 15.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = summary.category.shortTitle,
                style = MaterialTheme.typography.titleSmall,
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Format.count(summary.countToday),
                style = ProbeText.KpiSmall,
                color = p.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        val top = summary.topCaller
        Text(
            text = if (top != null) {
                "top · ${Intel.callerLabel(top.packageName, top.label)}"
            } else {
                "今日暂无调用"
            },
            style = ProbeText.Label,
            color = p.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        if (summary.countToday > 0) {
            ShareBar(fraction = fraction)
        } else {
            // 真实 0：保留结构高度，但不画占比条
            Spacer(Modifier.height(3.dp))
        }
    }
}

/** 把 11 个大类的 24 小时桶合并成 KPI tile 的 sparkline。 */
private fun mergeHourly(categories: List<CategorySummary>): List<Int> {
    val out = IntArray(24)
    for (category in categories) {
        category.todayHourly.forEachIndexed { index, value ->
            if (index in 0..23) out[index] += value
        }
    }
    return out.toList()
}
