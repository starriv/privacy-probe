package dev.popsicle.privprobe.core.update

import android.util.Log
import dev.popsicle.privprobe.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** GitHub Releases 上查到的最新版本信息。 */
data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    /** 直接下载 APK 的地址；没有 APK 资产时为 null，回退到 releaseUrl。 */
    val downloadUrl: String?,
    /** Release 说明（截断后用于弹窗展示）。 */
    val notes: String?,
)

sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 检查 GitHub 上的最新 Release。
 *
 * 只有用户在配置页主动点击「检查更新」时才会发起一次 HTTPS 请求；
 * 不做后台轮询、不上传任何设备信息（User-Agent 仅包含应用名和版本号）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val API_URL = "https://api.github.com/repos/starriv/privacy-probe/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/starriv/privacy-probe/releases/latest"
    private const val TIMEOUT_MS = 8_000

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateResult =
        withContext(Dispatchers.IO) {
            runCatching { fetchLatest() }.fold(
                onSuccess = { info ->
                    if (isNewerVersion(info.latestVersion, currentVersion)) {
                        UpdateResult.Available(info)
                    } else {
                        UpdateResult.UpToDate(currentVersion, info.latestVersion)
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "check update failed", t)
                    UpdateResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: "网络请求失败")
                },
            )
        }

    private fun fetchLatest(): UpdateInfo {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrivacyProbe/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) error("GitHub 上还没有发布版本")
            if (code != HttpURLConnection.HTTP_OK) error("GitHub 返回 HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val latest = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (latest.isBlank()) error("响应缺少版本号")
            val releaseUrl = safeGithubUrl(json.optString("html_url")) ?: RELEASES_PAGE
            val downloadUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    ?.let { safeGithubUrl(it.optString("browser_download_url")) }
            }
            val notes = cleanupReleaseNotes(json.optString("body"))
            return UpdateInfo(latest, releaseUrl, downloadUrl, notes)
        } finally {
            conn.disconnect()
        }
    }

    /** 只接受 GitHub 自己的下载地址，避免把服务端返回的任意 URL 交给浏览器。 */
    private fun safeGithubUrl(raw: String?): String? = raw?.takeIf {
        it.startsWith("https://github.com/") ||
            it.startsWith("https://objects.githubusercontent.com/") ||
            it.startsWith("https://github-releases.githubusercontent.com/")
    }
}

/** 比较 "1.2.0" / "v1.2.0" 这类版本号，latest 是否比 current 新。 */
internal fun isNewerVersion(latest: String, current: String): Boolean {
    val l = versionParts(latest)
    val c = versionParts(current)
    for (i in 0 until maxOf(l.size, c.size)) {
        val a = l.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** Release 说明是 Markdown，弹窗里只保留可读文本（去掉 #、引用符号、反引号和分隔线）。 */
internal fun cleanupReleaseNotes(raw: String?): String? =
    raw?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && it != "---" }
        ?.map { line -> line.trimStart('>', '#', ' ', '-').replace("`", "").trim() }
        ?.filter { it.isNotEmpty() }
        ?.joinToString("\n")
        ?.take(400)
        ?.takeIf { it.isNotBlank() }

private fun versionParts(version: String): List<Int> =
    version.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
