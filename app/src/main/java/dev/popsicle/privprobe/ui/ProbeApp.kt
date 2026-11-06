package dev.popsicle.privprobe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.popsicle.privprobe.ui.components.ProbeBottomBar
import dev.popsicle.privprobe.ui.nav.Screen
import dev.popsicle.privprobe.ui.nav.rememberProbeNavigator
import dev.popsicle.privprobe.ui.screens.CallerScreen
import dev.popsicle.privprobe.ui.screens.CategoryScreen
import dev.popsicle.privprobe.ui.screens.DashboardScreen
import dev.popsicle.privprobe.ui.screens.SettingsScreen
import dev.popsicle.privprobe.ui.screens.TargetScreen
import dev.popsicle.privprobe.ui.theme.LocalProbePalette

/**
 * 根 Composable：依赖、页面栈、底部 tab、返回处理。
 * 导航：sealed [Screen] + [rememberProbeNavigator] + BackHandler，不引入 navigation 库。
 */
@Composable
fun ProbeApp() {
    val sources = rememberUiSources()
    val navigator = rememberProbeNavigator()
    val context = LocalContext.current
    val p = LocalProbePalette.current
    val collectorRunning by sources.collector.running.collectAsStateWithLifecycle()

    // Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 运行时权限：首次进入请求一次。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝不阻塞采集 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BackHandler(enabled = navigator.canGoBack) {
        navigator.pop()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.page),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (val screen = navigator.current) {
                    Screen.Dashboard -> DashboardScreen(
                        sources = sources,
                        onOpenCategory = { category -> navigator.push(Screen.Category(category)) },
                        onOpenCaller = { category, uid -> navigator.push(Screen.Caller(category, uid)) },
                        onOpenSettings = { navigator.switchTab(Screen.Settings) },
                    )

                    Screen.Settings -> SettingsScreen(sources = sources)

                    is Screen.Category -> CategoryScreen(
                        sources = sources,
                        category = screen.category,
                        onBack = { navigator.pop() },
                        onOpenCaller = { uid -> navigator.push(Screen.Caller(screen.category, uid)) },
                        onOpenTarget = { target -> navigator.push(Screen.Target(screen.category, target)) },
                    )

                    is Screen.Caller -> CallerScreen(
                        sources = sources,
                        category = screen.category,
                        uid = screen.uid,
                        onBack = { navigator.pop() },
                        onOpenTarget = { target -> navigator.push(Screen.Target(screen.category, target)) },
                    )

                    is Screen.Target -> TargetScreen(
                        sources = sources,
                        category = screen.category,
                        target = screen.target,
                        onBack = { navigator.pop() },
                        onOpenCaller = { uid -> navigator.push(Screen.Caller(screen.category, uid)) },
                    )
                }
            }
            if (!navigator.canGoBack) {
                ProbeBottomBar(
                    selectedIndex = navigator.rootTabIndex,
                    onSelect = { index ->
                        navigator.switchTab(if (index == 1) Screen.Settings else Screen.Dashboard)
                    },
                    collectorRunning = collectorRunning,
                    onToggleCollector = {
                        if (collectorRunning) {
                            sources.collector.stop(context)
                        } else {
                            sources.collector.start(context)
                        }
                    },
                )
            }
        }
    }
}
