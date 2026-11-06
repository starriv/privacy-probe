package dev.popsicle.privprobe.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.core.model.CallerSummary
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.model.PrivacyEvent
import dev.popsicle.privprobe.core.model.TargetSummary
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText
import dev.popsicle.privprobe.ui.util.Format
import dev.popsicle.privprobe.ui.util.Intel

/** 最近事件 / 时间线中的一行（hairline 列表，无彩色徽标堆叠）。 */
@Composable
fun EventRow(
    event: PrivacyEvent,
    maskDomains: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(click)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(event.category, size = 28.dp, glyphSize = 15.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Format.eventTitle(event, maskDomains),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (event.category == PrivacyCategory.NETWORK && event.target != null) {
                    Spacer(Modifier.width(6.dp))
                    TrackerChip(event.target)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(Intel.callerLabel(event.packageName, event.appLabel))
                    append(" · ")
                    append(event.source.label)
                    append(" · ")
                    append(event.confidence)
                    append("%")
                },
                style = ProbeText.Meta,
                color = p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text = Format.clock(event.ts), style = ProbeText.Meta, color = p.muted)
    }
}

/**
 * Ranked 调用者行：28dp 图标 + 名称 14 + 包名/uid 12 muted + 次数 tabular + 份额 11 muted，
 * 行底 3px 份额条。
 */
@Composable
fun CallerRow(
    caller: CallerSummary,
    category: PrivacyCategory,
    maskDomains: Boolean,
    totalCount: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val fraction = if (totalCount <= 0) 0f else caller.count.toFloat() / totalCount
    Column(modifier = modifier.fillMaxWidth().then(click)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(caller.label, caller.packageName, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Intel.callerLabel(caller.packageName, caller.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (caller.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Chip("系统", p.muted)
                    }
                }
                Text(
                    text = buildString {
                        append(caller.packageName ?: "未知包名")
                        append(" · uid ")
                        append(caller.uid)
                    },
                    style = ProbeText.Label,
                    color = p.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text = Format.count(caller.count), style = ProbeText.Number, color = p.ink)
            Text(
                text = Format.percent(caller.count, totalCount),
                style = ProbeText.Meta.copy(textAlign = TextAlign.End),
                color = p.muted,
                modifier = Modifier.width(46.dp),
                textAlign = TextAlign.End,
            )
        }
        ShareBar(fraction = fraction, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** Ranked 目标行（域名 / 传感器 / 摄像头 …）。 */
@Composable
fun TargetRow(
    target: TargetSummary,
    category: PrivacyCategory,
    maskDomains: Boolean,
    totalCount: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val fraction = if (totalCount <= 0) 0f else target.count.toFloat() / totalCount
    Column(modifier = modifier.fillMaxWidth().then(click)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TargetIcon(kind = target.kind, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Intel.displayTarget(target.target, category, maskDomains),
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (category == PrivacyCategory.NETWORK) {
                        Spacer(Modifier.width(6.dp))
                        TrackerChip(target.target)
                    }
                }
                Text(
                    text = buildString {
                        append(Intel.targetKindLabel(target.kind))
                        append(" · ")
                        append(target.callerCount)
                        append(" 个调用者")
                    },
                    style = ProbeText.Label,
                    color = p.muted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text = Format.count(target.count), style = ProbeText.Number, color = p.ink)
            Text(
                text = Format.percent(target.count, totalCount),
                style = ProbeText.Meta.copy(textAlign = TextAlign.End),
                color = p.muted,
                modifier = Modifier.width(46.dp),
                textAlign = TextAlign.End,
            )
        }
        ShareBar(fraction = fraction, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** 目标详情里的调用者行（简化版 ranked row）。 */
@Composable
fun CallerMiniRow(
    caller: CallerSummary,
    totalCount: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val fraction = if (totalCount <= 0) 0f else caller.count.toFloat() / totalCount
    Column(modifier = modifier.fillMaxWidth().then(click)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(caller.label, caller.packageName, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Intel.callerLabel(caller.packageName, caller.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = caller.packageName ?: "uid ${caller.uid}",
                    style = ProbeText.Label,
                    color = p.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = Format.count(caller.count), style = ProbeText.Number, color = p.ink)
            Text(
                text = Format.percent(caller.count, totalCount),
                style = ProbeText.Meta.copy(textAlign = TextAlign.End),
                color = p.muted,
                modifier = Modifier.width(46.dp),
                textAlign = TextAlign.End,
            )
        }
        ShareBar(fraction = fraction, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** 时间线行：时间 11 muted · target 14 · 来源/置信度 11 muted。 */
@Composable
fun TimelineRow(
    event: PrivacyEvent,
    maskDomains: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = Format.clock(event.ts),
            style = ProbeText.Meta,
            color = p.muted,
            modifier = Modifier.width(42.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Format.eventTitle(event, maskDomains),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (event.category == PrivacyCategory.NETWORK && event.target != null) {
                    Spacer(Modifier.width(6.dp))
                    TrackerChip(event.target)
                }
            }
            val detail = event.detail?.takeIf { it.isNotBlank() }
            Text(
                text = buildString {
                    append(event.source.label)
                    append(" · ")
                    append(event.confidence)
                    append("%")
                    if (detail != null) {
                        append(" · ")
                        append(detail)
                    }
                },
                style = ProbeText.Meta,
                color = p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
