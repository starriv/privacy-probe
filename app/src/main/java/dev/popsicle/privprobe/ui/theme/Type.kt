package dev.popsicle.privprobe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 系统默认字体（不指定字族），层级：
 * 20 页面标题 / 16 面板标题 / 14 正文 / 12–13 标签 / 11 元信息。
 * 数字统一等宽数位（fontFeatureSettings = "tnum"）。
 */
val ProbeTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        letterSpacing = (-0.5).sp,
    ),
)

/** 数字与元信息专用样式。 */
object ProbeText {
    /** 详情页 4 联 KPI（稍小以适配窄格）。 */
    val KpiSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        letterSpacing = (-0.4).sp,
    )

    /** 列表中的数字（次数、份额）。 */
    val Number = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )

    val NumberSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontFeatureSettings = "tnum",
    )

    /** 11sp 元信息（时间、占比、来源）——等宽数位，避免时间/百分比跳动。 */
    val Meta = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")

    /** 12sp 标签（可能含数字，使用等宽数位）。 */
    val Label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
}
