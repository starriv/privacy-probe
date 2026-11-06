package dev.popsicle.privprobe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.popsicle.privprobe.ui.theme.LocalProbePalette

/**
 * 极简 sparkline：1.5px 线 + 10% 淡填充，无坐标轴。用于 KPI 事件 tile。
 */
@Composable
fun Sparkline(
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = LocalProbePalette.current.accent,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1)
        val n = values.size
        val stepX = if (n > 1) size.width / (n - 1) else size.width
        val points = values.mapIndexed { index, value ->
            val x = if (n > 1) index * stepX else size.width / 2f
            val y = size.height - 1f - (value.toFloat() / max) * (size.height - 2f)
            Offset(x, y)
        }
        val line = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(fill, color.copy(alpha = 0.10f))
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 占据行底部的 3px 份额条：track 用 hairline，fill 用 accent 75%。
 */
@Composable
fun ShareBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    color: Color = LocalProbePalette.current.accent,
) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(p.line),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color.copy(alpha = 0.75f)),
        )
    }
}
