package dev.popsicle.privprobe

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.popsicle.privprobe.ui.ProbeApp
import dev.popsicle.privprobe.ui.theme.PrivacyProbeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏 + 跟随系统明暗：透明状态栏/导航栏，图标颜色由 SystemBarStyle.auto 按系统主题决定。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            PrivacyProbeTheme {
                ProbeApp()
            }
        }
    }
}
