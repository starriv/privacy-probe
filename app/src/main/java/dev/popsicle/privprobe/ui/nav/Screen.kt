package dev.popsicle.privprobe.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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

    data class Caller(
        val category: PrivacyCategory,
        val uid: Int,
        /** 调用者包名；null 表示该 uid 下无法归因包名的事件。 */
        val packageName: String?,
    ) : Screen

    data class Target(val category: PrivacyCategory, val target: String) : Screen
}

/**
 * 栈中的一个页面实例。
 *
 * [id] 在进程内唯一且不复用，作为该页 UI 状态（LazyColumn 滚动位置、分段 tab）的保存键：
 * 进入详情页再返回时，同一个页面实例拿回自己的状态；页面弹出后状态随之清理，不会串到下次打开的同类页面。
 * 根 tab 页面使用固定 id，切换底部 tab 时总览 / 配置各自的滚动位置同样保留。
 */
data class NavEntry(val id: Long, val screen: Screen)

@Stable
class ProbeNavigator {
    private val stack = mutableStateListOf(NavEntry(DASHBOARD_ID, Screen.Dashboard))
    private var nextId = FIRST_PUSHED_ID

    /** 当前页面栈，栈底是根 tab 页面。 */
    val entries: List<NavEntry> get() = stack

    val currentEntry: NavEntry get() = stack.last()

    val current: Screen get() = stack.last().screen

    /** 是否在二级页（决定是否显示底部 tab 栏）。 */
    val canGoBack: Boolean get() = stack.size > 1

    /** 当前根 tab 下标，用于底部栏选中态。 */
    val rootTabIndex: Int
        get() = when (stack.first().screen) {
            Screen.Settings -> 1
            else -> 0
        }

    fun push(screen: Screen) {
        if (stack.last().screen == screen) return
        stack.add(NavEntry(nextId++, screen))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** 切换底部 tab：清空二级页；根页面保留自己的固定槽位，切回来时状态还在。 */
    fun switchTab(screen: Screen) {
        stack.clear()
        stack.add(NavEntry(rootId(screen), screen))
    }

    companion object {
        const val DASHBOARD_ID = 0L
        const val SETTINGS_ID = 1L
        private const val FIRST_PUSHED_ID = 2L

        fun rootId(screen: Screen): Long = when (screen) {
            Screen.Settings -> SETTINGS_ID
            else -> DASHBOARD_ID
        }

        /** 根 tab 页面状态跨 tab 切换保留，不属于「已弹出页面」的清理范围。 */
        fun isRootId(id: Long): Boolean = id == DASHBOARD_ID || id == SETTINGS_ID
    }
}

@Composable
fun rememberProbeNavigator(): ProbeNavigator = remember { ProbeNavigator() }
