package dev.popsicle.privprobe.ui.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import dev.popsicle.privprobe.core.model.PrivacyCategory

/**
 * 极简页面栈。不用 navigation 库：sealed class + 自己的栈 + BackHandler 足够。
 */
sealed interface Screen {
    /** 总览（底部 tab 0）。 */
    data object Dashboard : Screen

    /** 配置（底部 tab 1）。 */
    data object Settings : Screen

    data class Category(val category: PrivacyCategory) : Screen

    data class Caller(val category: PrivacyCategory, val uid: Int) : Screen

    data class Target(val category: PrivacyCategory, val target: String) : Screen
}

@Stable
class ProbeNavigator {
    private val stack = mutableStateListOf<Screen>(Screen.Dashboard)

    val current: Screen get() = stack.last()

    /** 是否在二级页（决定是否显示底部 tab 栏）。 */
    val canGoBack: Boolean get() = stack.size > 1

    /** 当前根 tab 下标，用于底部栏选中态。 */
    val rootTabIndex: Int
        get() = when (stack.firstOrNull()) {
            Screen.Settings -> 1
            else -> 0
        }

    fun push(screen: Screen) {
        if (stack.lastOrNull() == screen) return
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 切换底部 tab：清空栈并重置根页面。 */
    fun switchTab(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}

@Composable
fun rememberProbeNavigator(): ProbeNavigator = remember { ProbeNavigator() }
