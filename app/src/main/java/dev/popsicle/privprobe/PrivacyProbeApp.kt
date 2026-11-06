package dev.popsicle.privprobe

import android.app.Application
import dev.popsicle.privprobe.core.AppGraph
import dev.popsicle.privprobe.core.intel.RepoIntel

class PrivacyProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 只读知识库（assets/datasets）在 IO 线程加载，避免冷启动主线程解析 100KB+ JSON。
        kotlin.concurrent.thread(name = "RepoIntel-init", isDaemon = true) {
            runCatching { RepoIntel.init(applicationContext) }
        }
        AppGraph.init(this)
    }
}
