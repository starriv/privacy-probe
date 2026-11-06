package dev.popsicle.privprobe.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌（dashboard-design skill）：一套中性色 + 单一强调色。
 * 只有 accent 承担强调/选中/数据标记，live/warn/down 仅用于语义（实时、告警、错误）。
 */
@Immutable
data class ProbePalette(
    val dark: Boolean,
    /** 页面底色 */
    val page: Color,
    /** 卡片 / 面板 */
    val card: Color,
    /** 轨道、hover、segmented track、次级填充 */
    val soft: Color,
    /** 主文字 */
    val ink: Color,
    /** 次级文字 */
    val ink2: Color,
    /** 标签、坐标轴、占比等弱化文字 */
    val muted: Color,
    /** 1px hairline */
    val line: Color,
    /** 输入框 / 选中描边等略强 hairline */
    val lineStrong: Color,
    /** 唯一强调色 */
    val accent: Color,
    val onAccent: Color,
    /** 实时圆点 */
    val live: Color,
    val warn: Color,
    val down: Color,
)

val LightPalette = ProbePalette(
    dark = false,
    page = Color(0xFFF8F8F6),
    card = Color(0xFFFFFFFF),
    soft = Color(0xFFF1F1EF),
    ink = Color(0xFF17171A),
    ink2 = Color(0xFF48484D),
    muted = Color(0xFF66666D),
    line = Color(0x13000000),
    lineStrong = Color(0x24000000),
    accent = Color(0xFF3E63DD),
    onAccent = Color(0xFFFFFFFF),
    live = Color(0xFF12A150),
    warn = Color(0xFFC98A00),
    down = Color(0xFFD23F45),
)

val DarkPalette = ProbePalette(
    dark = true,
    page = Color(0xFF0B0B0D),
    card = Color(0xFF141417),
    soft = Color(0xFF1C1C20),
    ink = Color(0xFFF3F3F5),
    ink2 = Color(0xFFC4C4CA),
    muted = Color(0xFF8C8C95),
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x29FFFFFF),
    accent = Color(0xFF7B96FF),
    onAccent = Color(0xFF0B0B0D),
    live = Color(0xFF34C48B),
    warn = Color(0xFFF6B100),
    down = Color(0xFFFF6B70),
)

val LocalProbePalette = staticCompositionLocalOf { LightPalette }
