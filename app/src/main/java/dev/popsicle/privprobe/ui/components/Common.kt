package dev.popsicle.privprobe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.popsicle.privprobe.core.model.RiskLevel
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText

/* ------------------------------------------------------------------ 布局骨架 */

/**
 * 页面骨架：状态栏留白 + 顶部标题栏（20sp）+ 内容。
 * 二级页带返回，一级页没有返回按钮。
 */
@Composable
fun ProbeScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    /** 右侧操作区终点内边距；图标按钮传 8dp，让 20dp 图标的光学右边缘落在 16dp 内容线上。 */
    headerEnd: Dp = 16.dp,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val p = LocalProbePalette.current
    // 页面内容统一 16dp 横向内边距。标题栏必须复用同一基准：
    // - 一级页（无返回）：标题从 16dp 起，与下方卡片严格对齐；
    // - 二级页：返回按钮外框左移到 8dp，让 20dp 图标的光学左边缘仍落在 16dp 基准线上。
    val headerStart = if (onBack != null) 8.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.page)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = headerStart, end = headerEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = p.ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
    }
}

/**
 * 悬浮底部 dock：左侧胶囊放两个 tab（总览 / 配置），右侧单独的圆形主按钮
 * 快速启停采集——对应参考图「胶囊 tabs + 独立 FAB」的流行布局。
 *
 * 胶囊与 FAB 均左右 16dp、距底 12dp（另加 navigationBarsPadding），高 62dp、
 * 全圆角；浅色柔和阴影 + 1px hairline，深色仅 hairline。选中 tab 用 soft 底 + ink 文字，
 * FAB 用单一 accent，未做常驻动画。
 */
@Composable
fun ProbeBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    collectorRunning: Boolean,
    onToggleCollector: () -> Unit,
) {
    val p = LocalProbePalette.current
    val shape = CircleShape
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(62.dp)
                    .then(
                        if (!p.dark) {
                            Modifier.shadow(
                                elevation = 8.dp,
                                shape = shape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = Color.Black.copy(alpha = 0.10f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .background(p.card)
                    .border(1.dp, p.line, shape)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomTab(0, "总览", Icons.Filled.Home, selectedIndex == 0) { onSelect(0) }
                BottomTab(1, "配置", Icons.Filled.Settings, selectedIndex == 1) { onSelect(1) }
            }

            // 独立圆形 FAB：开启/停止采集。与胶囊同样的悬浮高度，形成参考图的非对称构图。
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .then(
                        if (!p.dark) {
                            Modifier.shadow(
                                elevation = 8.dp,
                                shape = shape,
                                clip = false,
                                ambientColor = p.accent.copy(alpha = 0.22f),
                                spotColor = p.accent.copy(alpha = 0.28f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(shape)
                    .background(p.accent)
                    .clickable(
                        onClickLabel = if (collectorRunning) "停止采集" else "开启采集",
                        onClick = onToggleCollector,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (collectorRunning) {
                    // material-icons-core 没有 Stop 图标：用 16dp 圆角方块表达停止，避免引入额外图标依赖。
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(p.onAccent),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "开启采集",
                        tint = p.onAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomTab(
    index: Int,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalProbePalette.current
    val color = if (selected) p.ink else p.muted
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(26.dp))
            .background(if (selected) p.soft else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = ProbeText.Label.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = color,
        )
    }
}

/* ------------------------------------------------------------------ 卡片与基础元素 */

/** 卡片：16dp 圆角、1px hairline、浅色极轻阴影，深色无阴影。 */
@Composable
fun ProbeCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalProbePalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            .padding(padding),
        content = content,
    )
}

/** 1px 横向 hairline。 */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalProbePalette.current.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 分区标题：12sp muted。 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    Row(
        // 分区标题与页面标题、卡片共用同一条 16dp 基准线：
        // 这里不再额外加水平内边距，避免 2dp 的视觉错位。
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = ProbeText.Label.copy(fontWeight = FontWeight.Medium),
            color = p.muted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 实时圆点：6dp 静态圆点；不做常驻动画，避免 RenderThread 持续 60fps 重绘。 */
@Composable
fun LiveDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
    @Suppress("UNUSED_PARAMETER") animate: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/* ------------------------------------------------------------------ KPI */

data class KpiTileData(
    val label: String,
    val value: String,
    /** 24h 极简 sparkline，仅事件 tile 使用。 */
    val sparkline: List<Int>? = null,
    /** 12sp muted 一行基线，例如「个应用」；与 sparkline 二选一，用于平衡 tile 高度。 */
    val baseline: String? = null,
)

/** 一张卡内的 KPI 四联，竖 hairline 分隔。 */
@Composable
fun KpiCard(
    tiles: List<KpiTileData>,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = ProbeText.KpiSmall,
) {
    val p = LocalProbePalette.current
    ProbeCard(modifier = modifier, padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            tiles.forEachIndexed { index, tile ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                            .background(p.line),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Text(
                        text = tile.label,
                        style = ProbeText.Label,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tile.value,
                        style = valueStyle,
                        color = p.ink,
                        maxLines = 1,
                    )
                    val spark = tile.sparkline
                    when {
                        spark != null -> {
                            Spacer(Modifier.height(6.dp))
                            Sparkline(
                                values = spark,
                                modifier = Modifier.fillMaxWidth().height(18.dp),
                            )
                        }
                        tile.baseline != null -> {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = tile.baseline,
                                style = ProbeText.Label,
                                color = p.muted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 徽标 / 标签 */

/** 10sp outline 小标签。 */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ProbeText.Meta.copy(fontSize = 10.sp, lineHeight = 13.sp),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 风险 chip：详情页用 amber outline；低风险用 muted。 */
@Composable
fun RiskChip(risk: RiskLevel, modifier: Modifier = Modifier) {
    val p = LocalProbePalette.current
    Chip(
        text = "${risk.label}风险",
        color = if (risk == RiskLevel.LOW) p.muted else p.warn,
        modifier = modifier,
    )
}

/** 域名分类 chip：追踪器 amber，功能域名 muted，未知不显示。 */
@Composable
fun TrackerChip(domain: String?, modifier: Modifier = Modifier) {
    val intel = remember(domain) {
        dev.popsicle.privprobe.core.intel.RepoIntel.domainIntel(domain)
    } ?: return
    val p = LocalProbePalette.current
    when {
        intel.isTracker -> Chip("追踪器", p.warn, modifier)
        intel.isFunctional -> Chip("功能域名", p.muted, modifier)
    }
}

/** 预览数据提示。 */
@Composable
fun PreviewBadge(modifier: Modifier = Modifier) {
    val p = LocalProbePalette.current
    Chip("预览数据（非真实采集）", p.muted, modifier)
}

/* ------------------------------------------------------------------ 控件 */

@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.soft)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .then(
                        if (selected) {
                            Modifier.background(p.card).border(1.dp, p.line, RoundedCornerShape(9.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = ProbeText.Label.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                    color = if (selected) p.ink else p.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** accent 实心主按钮（每屏最多一个）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) p.accent else p.soft)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!enabled) {
                CircularProgressIndicator(
                    color = p.muted,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) p.onAccent else p.muted,
                maxLines = 1,
            )
        }
    }
}

/** hairline outline 次按钮。 */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val p = LocalProbePalette.current
    val content = if (destructive) p.down else p.ink2
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.card)
            .border(1.dp, if (destructive) p.down.copy(alpha = 0.45f) else p.lineStrong, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) content else p.muted,
            maxLines = 1,
        )
    }
}

/* ------------------------------------------------------------------ 状态 */

/** 空态：一行说明 + 一个动作，无插画。 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = p.ink2)
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** 加载态：骨架块。 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val p = LocalProbePalette.current
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (index == 0) 88.dp else 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.soft),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** root 未授权提示：一行说明 + 一个 outline 动作。 */
@Composable
fun RootWarningCard(
    hint: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalProbePalette.current
    ProbeCard(modifier = modifier) {
        Text(
            text = "未检测到 root",
            style = MaterialTheme.typography.titleSmall,
            color = p.warn,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = hint ?: "低层采集需要 root；授予后重新检测，或仅使用系统服务轮询类探针。",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton(text = "重新检测", onClick = onRefresh, modifier = Modifier.fillMaxWidth())
    }
}