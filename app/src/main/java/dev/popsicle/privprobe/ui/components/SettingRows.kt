package dev.popsicle.privprobe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText

/** 设置项之间的 hairline。 */
@Composable
fun SettingDivider() {
    Hairline(modifier = Modifier.padding(start = 16.dp))
}

/** 开关行：标题 14、说明 12 muted、accent 开关。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) p.ink else p.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.accent,
                checkedBorderColor = p.accent,
                uncheckedThumbColor = p.muted,
                uncheckedTrackColor = p.soft,
                uncheckedBorderColor = p.lineStrong,
            ),
        )
    }
}

/** 滑杆行。 */
@Composable
fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    steps: Int = 0,
) {
    val p = LocalProbePalette.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
            Text(text = valueLabel, style = ProbeText.Number, color = p.ink)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.padding(top = 2.dp),
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = p.accent,
                activeTrackColor = p.accent.copy(alpha = 0.85f),
                inactiveTrackColor = p.line,
                activeTickColor = p.onAccent,
                inactiveTickColor = p.line,
            ),
        )
    }
}

/** 步进行（离散值）。 */
@Composable
fun StepperRow(
    title: String,
    valueLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
        }
        StepperButton(label = "−", enabled = canDecrease, onClick = onDecrease)
        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            Text(text = valueLabel, style = ProbeText.Number, color = p.ink, textAlign = TextAlign.Center)
        }
        StepperButton(label = "+", enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalProbePalette.current
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, p.lineStrong, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = ProbeText.Number,
            color = if (enabled) p.ink2 else p.muted,
        )
    }
}

/** 可点击行（导出 / 清空 / 自检 / 重新检测）。 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> p.muted
                    destructive -> p.down
                    else -> p.ink
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** hairline 定义列表行：左标签 12 muted（固定宽），右值 13 ink。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalProbePalette.current.ink,
    trailing: (@Composable () -> Unit)? = null,
    /** 长值（系统 / 内核 / root / 数据来源）用左对齐换行，短值仍右对齐。 */
    valueStart: Boolean = false,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = ProbeText.Label,
            color = p.muted,
            modifier = Modifier.width(88.dp),
        )
        if (trailing != null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        } else {
            Text(
                text = value,
                style = ProbeText.NumberSmall,
                color = valueColor,
                textAlign = if (valueStart) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 状态行小圆点（探针健康 / root / 采集）。 */
@Composable
fun StateDot(healthy: Boolean, modifier: Modifier = Modifier) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (healthy) p.live else p.down),
    )
}
