package dev.popsicle.privprobe.core.data.impl

import android.content.Context
import android.content.SharedPreferences
import dev.popsicle.privprobe.core.data.AppSettings
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences 持久化的设置存储；写入后立即更新 StateFlow。 */
class PrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<AppSettings> = _state.asStateFlow()

    override fun snapshot(): AppSettings = _state.value

    /**
     * P2-7：read-modify-write 必须串行化——UI 线程与采集线程可能同时 update()，
     * 否则基于同一旧值计算会丢一次修改。synchronized 块内完成 transform→persist→state。
     */
    override fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(updateLock) {
            val current = _state.value
            val next = transform(current)
            if (next == current) return
            persist(next)
            _state.value = next
        }
    }

    private val updateLock = Any()

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val probes = ProbeId.entries.associateWith { id ->
            prefs.getBoolean("probe_${id.name}", defaults.probes[id] ?: true)
        }
        return AppSettings(
            collectorEnabled = prefs.getBoolean(KEY_ENABLED, defaults.collectorEnabled),
            foregroundNotification = prefs.getBoolean(
                KEY_NOTIFICATION, defaults.foregroundNotification,
            ),
            showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, defaults.showSystemApps),
            retentionDays = prefs.getInt(KEY_RETENTION, defaults.retentionDays).coerceIn(0, 365),
            pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL, defaults.pollIntervalMs)
                .coerceIn(500L, 30_000L),
            appOpsIntervalMs = prefs.getLong(KEY_APPOPS_INTERVAL, defaults.appOpsIntervalMs)
                .coerceIn(5_000L, 120_000L),
            probes = probes,
            maskDomains = prefs.getBoolean(KEY_MASK_DOMAINS, defaults.maskDomains),
            lowLevelEnabled = prefs.getBoolean(KEY_LOW_LEVEL, defaults.lowLevelEnabled),
        )
    }

    private fun persist(s: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, s.collectorEnabled)
            .putBoolean(KEY_NOTIFICATION, s.foregroundNotification)
            .putBoolean(KEY_SHOW_SYSTEM, s.showSystemApps)
            .putInt(KEY_RETENTION, s.retentionDays)
            .putLong(KEY_POLL_INTERVAL, s.pollIntervalMs)
            .putLong(KEY_APPOPS_INTERVAL, s.appOpsIntervalMs)
            .putBoolean(KEY_MASK_DOMAINS, s.maskDomains)
            .putBoolean(KEY_LOW_LEVEL, s.lowLevelEnabled)
            .apply {
                ProbeId.entries.forEach { id ->
                    putBoolean("probe_${id.name}", s.probes[id] ?: true)
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "privprobe_settings"
        private const val KEY_ENABLED = "collector_enabled"
        private const val KEY_NOTIFICATION = "foreground_notification"
        private const val KEY_SHOW_SYSTEM = "show_system_apps"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_APPOPS_INTERVAL = "appops_interval_ms"
        private const val KEY_MASK_DOMAINS = "mask_domains"
        private const val KEY_LOW_LEVEL = "low_level_enabled"
    }
}
