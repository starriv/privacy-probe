package dev.popsicle.privprobe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.popsicle.privprobe.MainActivity
import dev.popsicle.privprobe.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台采集服务（specialUse）。持有通知；探针协程由 [CollectorControllerImpl] / ProbeManager 管理。
 * 所有数据只在本机 SQLite，不联网。
 *
 * P1-14 / P2-5：
 * - startForeground 失败不再被吞：上报 lastError、清理 controller、stopSelf，避免 ANR/杀进程。
 * - 通知 30s 刷新一次，数字用「今日事件数」（缓存，查库失败沿用上次），文案区分
 *   「探针开关数」与「运行进程数」；文本不变不 notify，减少系统通知开销。
 * - 通知标题/频道/描述统一「隐私探针」。
 */
class CollectorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var stopping = false
    private var latestStartId = 0
    private var cleanupDone = false
    private var lastNotifiedText: String? = null
    private var cachedTodayCount = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val controller = CollectorControllerImpl.get()
        if (intent?.action == ACTION_STOP) {
            requestStop(startId)
            return START_NOT_STICKY
        }
        if (controller == null) {
            Log.w(TAG, "AppGraph not ready; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startAsForeground(buildNotification())) {
            // P1-14：前台服务启动失败必须显式失败，不能继续跑。
            // 清理交给 controller scope 异步完成（进程通常还活着）；这里停止服务避免 ANR。
            stopping = true
            cleanupDone = true
            controller.reportError("前台服务启动失败，采集已停止（请检查通知权限或系统限制）")
            controller.onServiceStopped()
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        stopping = false
        cleanupDone = false
        controller.onServiceStarted()
        startNotificationLoop()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 前台服务在任务移除后继续采集；用户显式停止才会退出。
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 主线程不等待 root/数据库 IO；清理由进程级命令队列持有。
        if (!cleanupDone) CollectorControllerImpl.get()?.onServiceStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun requestStop(startId: Int) {
        stopping = true
        notificationJob?.cancel()
        notificationJob = null
        val cleanup = CollectorControllerImpl.get()?.onServiceStopped()
        cleanupDone = true // 队列持有清理任务，onDestroy 不重复提交。
        scope.launch {
            cleanup?.join()
            if (latestStartId == startId) {
                ServiceCompat.stopForeground(this@CollectorService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private fun startAsForeground(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        }.getOrElse {
            Log.w(TAG, "startForeground failed", it)
            false
        }
    }

    private fun startNotificationLoop() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (isActive) {
                if (stopping) break
                refreshTodayCount()
                updateNotificationIfChanged()
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    /** 通知里的数字用「今日事件数」；查库失败/超时沿用上次缓存（P2-5）。 */
    private suspend fun refreshTodayCount() {
        if (!AppGraph.ready) return
        val state = runCatching {
            withTimeoutOrNull(NOTIFICATION_QUERY_TIMEOUT_MS) {
                AppGraph.repository.todayEventCount()
            }
        }.getOrNull() ?: return
        cachedTodayCount = state
    }

    private fun updateNotificationIfChanged() {
        val controller = CollectorControllerImpl.get() ?: return
        val settings = controller.settingsSnapshot()
        if (!settings.foregroundNotification) return
        val snapshot = controller.statusSnapshot()
        val switches = if (AppGraph.ready) AppGraph.probeManager.enabledSwitchCount(settings) else 0
        val processes = if (AppGraph.ready) AppGraph.probeManager.runningProbeCount() else 0
        val root = if (snapshot.rootAvailable) "root ✓" else "root ✗"
        val text = "今日 $cachedTodayCount 条事件 · 探针开关 $switches · 运行进程 $processes · $root"
        if (text == lastNotifiedText) return
        lastNotifiedText = text
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }.onFailure { Log.w(TAG, "update notification failed", it) }
    }

    private fun buildNotification(text: String? = null): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentText = text ?: "正在读取本机系统接口（dumpsys/appops/logcat），数据不出设备"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("隐私探针 · 采集运行中")
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 频道名称/描述统一「隐私探针」；createNotificationChannel 对已存在的频道是幂等更新。 */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "隐私探针",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "隐私探针采集状态与今日事件计数"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CollectorService"
        private const val CHANNEL_ID = "privprobe_collector"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_QUERY_TIMEOUT_MS = 3_000L
        const val ACTION_START = "dev.popsicle.privprobe.action.START_COLLECTOR"
        const val ACTION_STOP = "dev.popsicle.privprobe.action.STOP_COLLECTOR"

        /** 便于调试/外部触发：`am startservice -a ...START_COLLECTOR` */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, CollectorService::class.java).setAction(ACTION_START),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CollectorService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
