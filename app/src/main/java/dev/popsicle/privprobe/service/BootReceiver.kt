package dev.popsicle.privprobe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.popsicle.privprobe.core.AppGraph
import dev.popsicle.privprobe.core.data.impl.PrefsSettingsStore

/** 开机自启：仅当用户在设置里开启采集时启动前台服务。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val enabled = runCatching {
            if (AppGraph.ready) {
                AppGraph.settings.snapshot().collectorEnabled
            } else {
                PrefsSettingsStore(context.applicationContext).snapshot().collectorEnabled
            }
        }.getOrDefault(false)
        if (!enabled) {
            Log.i(TAG, "boot: collector disabled by settings")
            return
        }
        runCatching {
            context.startForegroundService(
                Intent(context, CollectorService::class.java)
                    .setAction(CollectorService.ACTION_START),
            )
            Log.i(TAG, "boot: collector service requested")
        }.onFailure { Log.w(TAG, "boot: start collector failed", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
