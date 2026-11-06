package dev.popsicle.privprobe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.util.Intel

/**
 * 大类图标：Canvas 手绘单色线条，统一 ink2，不再按大类上色。
 * 形状是唯一的区分手段（颜色只留给 accent / 语义色）。
 */
@Composable
fun CategoryGlyph(
    category: PrivacyCategory,
    modifier: Modifier = Modifier,
    tint: Color = LocalProbePalette.current.ink2,
) {
    Canvas(modifier) {
        drawCategoryGlyph(category, tint, size.minDimension / 24f)
    }
}

/** 28dp 中性图标方块，用于列表 / 卡片左侧。 */
@Composable
fun CategoryIcon(
    category: PrivacyCategory,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    glyphSize: Dp = 16.dp,
) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(p.soft),
        contentAlignment = Alignment.Center,
    ) {
        CategoryGlyph(category, Modifier.size(glyphSize), tint = p.ink2)
    }
}

/**
 * 应用标识：中性 soft 方块 + 名称首字（ink2）。
 * 不用彩色哈希头像，保持仪表盘安静。
 */
@Composable
fun AppIcon(
    label: String?,
    packageName: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(p.soft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = firstGlyph(label, packageName),
            style = TextStyle(
                color = p.ink2,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.38f).sp,
            ),
        )
    }
}

/** 目标类型图标：中性 soft 方块 + 类型首字。 */
@Composable
fun TargetIcon(
    kind: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val p = LocalProbePalette.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(p.soft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Intel.targetKindLabel(kind).take(1),
            style = TextStyle(
                color = p.ink2,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.36f).sp,
            ),
        )
    }
}

private fun firstGlyph(label: String?, packageName: String?): String {
    val raw = label?.trim().orEmpty().ifEmpty { packageName?.substringAfterLast('.')?.trim().orEmpty() }
    if (raw.isEmpty()) return "?"
    val first = raw.first()
    return if (first.code in 0x21..0x7E) first.uppercase() else first.toString()
}

private fun DrawScope.drawCategoryGlyph(category: PrivacyCategory, tint: Color, s: Float) {
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    val strokeWidth = 1.85f * s
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    when (category) {
        PrivacyCategory.NETWORK -> {
            drawCircle(tint, radius = 8.1f * s, center = p(12f, 12f), style = stroke)
            drawLine(tint, p(3.9f, 12f), p(20.1f, 12f), strokeWidth, StrokeCap.Round)
            drawOval(tint, topLeft = p(7.3f, 3.9f), size = Size(9.4f * s, 16.2f * s), style = stroke)
        }

        PrivacyCategory.CAMERA -> {
            drawRoundRect(
                tint,
                topLeft = p(3.2f, 7.4f),
                size = Size(17.6f * s, 11.6f * s),
                cornerRadius = CornerRadius(2.4f * s),
                style = stroke,
            )
            val bump = Path().apply {
                moveTo(9f * s, 7.4f * s)
                lineTo(10f * s, 4.5f * s)
                lineTo(14f * s, 4.5f * s)
                lineTo(15f * s, 7.4f * s)
            }
            drawPath(bump, tint, style = stroke)
            drawCircle(tint, radius = 3.2f * s, center = p(12f, 13.2f), style = stroke)
        }

        PrivacyCategory.MICROPHONE -> {
            drawRoundRect(
                tint,
                topLeft = p(9f, 2.8f),
                size = Size(6f * s, 11f * s),
                cornerRadius = CornerRadius(3f * s),
                style = stroke,
            )
            drawArc(
                tint,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(6.2f, 7f),
                size = Size(11.6f * s, 11.6f * s),
                style = stroke,
            )
            drawLine(tint, p(12f, 18.6f), p(12f, 21.2f), strokeWidth, StrokeCap.Round)
            drawLine(tint, p(8.8f, 21.2f), p(15.2f, 21.2f), strokeWidth, StrokeCap.Round)
        }

        PrivacyCategory.LOCATION -> {
            drawCircle(tint, radius = 5.9f * s, center = p(12f, 9.6f), style = stroke)
            drawCircle(tint, radius = 2f * s, center = p(12f, 9.6f))
            drawLine(tint, p(8f, 14.4f), p(12f, 21f), strokeWidth, StrokeCap.Round)
            drawLine(tint, p(16f, 14.4f), p(12f, 21f), strokeWidth, StrokeCap.Round)
        }

        PrivacyCategory.SENSOR -> {
            drawCircle(tint, radius = 1.5f * s, center = p(5.2f, 18.8f))
            for (r in listOf(3.6f, 6.6f, 9.6f)) {
                drawArc(
                    tint,
                    startAngle = -72f,
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = p(5.2f - r, 18.8f - r),
                    size = Size(2 * r * s, 2 * r * s),
                    style = stroke,
                )
            }
        }

        PrivacyCategory.CLIPBOARD -> {
            drawRoundRect(
                tint,
                topLeft = p(4.6f, 4f),
                size = Size(14.8f * s, 17.2f * s),
                cornerRadius = CornerRadius(2.4f * s),
                style = stroke,
            )
            drawRoundRect(
                tint,
                topLeft = p(8.8f, 2.2f),
                size = Size(6.4f * s, 4f * s),
                cornerRadius = CornerRadius(1.6f * s),
                style = stroke,
            )
            listOf(10.4f, 14f, 17.6f).forEach { y ->
                drawLine(tint, p(8f, y), p(16f, y), strokeWidth * 0.8f, StrokeCap.Round)
            }
        }

        PrivacyCategory.SCREEN -> {
            drawRoundRect(
                tint,
                topLeft = p(2.4f, 4.4f),
                size = Size(19.2f * s, 13f * s),
                cornerRadius = CornerRadius(2f * s),
                style = stroke,
            )
            drawLine(tint, p(12f, 17.4f), p(12f, 20.4f), strokeWidth, StrokeCap.Round)
            drawLine(tint, p(8.2f, 20.6f), p(15.8f, 20.6f), strokeWidth, StrokeCap.Round)
        }

        PrivacyCategory.TELEPHONY -> {
            drawRoundRect(
                tint,
                topLeft = p(8f, 2.2f),
                size = Size(8f * s, 19.6f * s),
                cornerRadius = CornerRadius(2.4f * s),
                style = stroke,
            )
            drawLine(tint, p(10.4f, 5f), p(13.6f, 5f), strokeWidth * 0.8f, StrokeCap.Round)
            drawCircle(tint, radius = 0.8f * s, center = p(12f, 18.8f))
        }

        PrivacyCategory.CONTACTS -> {
            drawCircle(tint, radius = 3.1f * s, center = p(12f, 8.2f), style = stroke)
            drawArc(
                tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(5.3f, 13.4f),
                size = Size(13.4f * s, 13.4f * s),
                style = stroke,
            )
        }

        PrivacyCategory.DEVICE_ID -> {
            drawRoundRect(
                tint,
                topLeft = p(6.2f, 6.2f),
                size = Size(11.6f * s, 11.6f * s),
                cornerRadius = CornerRadius(2f * s),
                style = stroke,
            )
            drawCircle(tint, radius = 1.6f * s, center = p(12f, 12f))
            listOf(9f, 12f, 15f).forEach { v ->
                drawLine(tint, p(v, 3.2f), p(v, 6.2f), strokeWidth * 0.8f, StrokeCap.Round)
                drawLine(tint, p(v, 17.8f), p(v, 20.8f), strokeWidth * 0.8f, StrokeCap.Round)
                drawLine(tint, p(3.2f, v), p(6.2f, v), strokeWidth * 0.8f, StrokeCap.Round)
                drawLine(tint, p(17.8f, v), p(20.8f, v), strokeWidth * 0.8f, StrokeCap.Round)
            }
        }

        PrivacyCategory.APP_ACTIVITY -> {
            val pulse = Path().apply {
                moveTo(2.4f * s, 13.4f * s)
                lineTo(6.6f * s, 13.4f * s)
                lineTo(8.6f * s, 6.8f * s)
                lineTo(12f * s, 18.4f * s)
                lineTo(14.2f * s, 13.4f * s)
                lineTo(21.6f * s, 13.4f * s)
            }
            drawPath(pulse, tint, style = stroke)
        }
    }
}
