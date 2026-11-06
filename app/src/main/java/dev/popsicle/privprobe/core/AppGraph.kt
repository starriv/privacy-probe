package dev.popsicle.privprobe.core

import android.content.Context
import dev.popsicle.privprobe.core.app.UidResolver
import dev.popsicle.privprobe.core.collector.CollectorControl
import dev.popsicle.privprobe.core.collector.ProbeManager
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.SettingsStore
import dev.popsicle.privprobe.core.data.impl.PrefsSettingsStore
import dev.popsicle.privprobe.core.data.impl.SqlitePrivacyRepository
import dev.popsicle.privprobe.core.intel.RepoIntel
import dev.popsicle.privprobe.core.model.PrivacyCategory
import dev.popsicle.privprobe.core.root.RootShell
import dev.popsicle.privprobe.service.CollectorControllerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 进程级依赖图。由 [dev.popsicle.privprobe.PrivacyProbeApp] 在 onCreate 中初始化。
 *
 * 真实接线：SqlitePrivacyRepository（裸 SQLite）/ PrefsSettingsStore / ProbeManager /
 * CollectorControllerImpl（前台服务控制面）。原生探针通过 ProbeManager.externalProbeFactory 注入。
 */
object AppGraph {

    @Volatile
    var ready: Boolean = false
        private set

    lateinit var repository: PrivacyRepository
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var collector: CollectorControl
        private set

    /** 探针调度器（Lead 接线原生探针时使用）。 */
    lateinit var probeManager: ProbeManager
        private set

    /** 由 Application 调用。幂等。 */
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            // RepoIntel 由 Application 在 IO 线程加载；这里只启动包解析（本身异步）。
            runCatching { UidResolver.init(app) }

            val store = PrefsSettingsStore(app)
            val repo = SqlitePrivacyRepository(
                context = app,
                settings = store,
                systemUidPredicate = { uid -> UidResolver.isSystemUid(uid) },
            )
            repo.displayNameResolver = { uid, pkg -> UidResolver.labelFor(pkg, uid) }
            repo.packageResolver = { uid -> UidResolver.packageForUid(uid) }
            repo.targetKindResolver = { category, target -> resolveTargetKind(category, target) }

            val manager = ProbeManager(RootShell, repo, app)
            val controller = CollectorControllerImpl.create(app, repo, manager, store)
            repo.statusProvider = { controller.statusSnapshot() }

            settings = store
            repository = repo
            probeManager = manager
            collector = controller

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                // 清理过期数据 + 定期 retention（进程级只启动一次）
                runCatching { (repo as SqlitePrivacyRepository).purgeExpired() }
                while (isActive) {
                    delay(6 * 60 * 60 * 1000L)
                    runCatching { (repo as SqlitePrivacyRepository).purgeExpired() }
                }
            }
            // 原生低层采集器（AF_PACKET + tracefs uprobe/kprobe）包装成 Probe。
            // 由 PCAP / DNS_TRACE / KERNEL_OPEN 开关共同控制；ProbeManager 仅在 lowLevelEnabled 时调用 factory。
            manager.externalProbeFactory = {
                listOf(dev.popsicle.privprobe.core.collector.net.NativeProbeWrapper(app))
            }
            ready = true
            // 用户已开启采集时，随 App 启动自动拉起前台采集服务（失败则等用户在配置页手动开启）
            if (store.snapshot().collectorEnabled) {
                runCatching {
                    dev.popsicle.privprobe.service.CollectorService.start(app)
                }
            }
        }
    }

    private fun resolveTargetKind(category: PrivacyCategory, target: String): String = when (category) {
        PrivacyCategory.NETWORK -> RepoIntel.domainKind(target) ?: "domain"
        PrivacyCategory.CAMERA -> "camera"
        PrivacyCategory.MICROPHONE -> "audio"
        PrivacyCategory.LOCATION -> "location"
        PrivacyCategory.SENSOR -> "sensor"
        PrivacyCategory.SCREEN -> "screen"
        PrivacyCategory.CLIPBOARD -> "clipboard"
        PrivacyCategory.TELEPHONY -> "telephony"
        PrivacyCategory.CONTACTS -> "contacts"
        PrivacyCategory.DEVICE_ID -> "device_id"
        PrivacyCategory.APP_ACTIVITY -> "app"
    }
}
