package dev.popsicle.privprobe.core.intel

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 项目自带知识库（knowledge/ 下的隐私审计数据，随 APK 独立构建）。
 *
 * 数据由 `privacy-probe/scripts/gen-datasets.py` 从以下文件生成：
 * - research/kb-core.tsv、research/kb-miui.tsv：376 个系统包的中文名 / 分类 / 处置建议
 * - noir-audit/report.json：247 个「联网 + 敏感权限」应用的端点、数据类型、评分
 * - noir-audit/domains-{trackers,functional,other}.txt：域名分类
 * - docs/DEBLOAT-EXECUTED.md：本仓库已执行清理的系统包
 *
 * 只读、纯本地，不发任何网络请求。
 */
data class PackageIntel(
    val packageName: String,
    val label: String?,
    val category: String?,
    val vendor: String?,
    val system: Boolean,
    val uid: Int?,
    val dataTypes: List<String>,
    val endpoints: List<String>,
    val risk: String?,
    val action: String?,
    val reason: String?,
    val score: Int?,
)

data class DomainIntel(
    val kind: String,
    val packages: List<String>,
) {
    val isTracker: Boolean get() = kind == "tracker"
    val isFunctional: Boolean get() = kind == "functional"
}

data class DeviceProfile(
    val marketingName: String,
    val model: String,
    val os: String,
    val android: String,
    val kernel: String,
    val root: String,
    val scannedPackages: Int,
    val trackerDomains: Int,
    val repoKnowledgePackages: Int,
    val dataSource: String,
)

object RepoIntel {
    private const val TAG = "RepoIntel"

    @Volatile
    private var loaded = false

    private val packages = HashMap<String, PackageIntel>()
    private val domainKinds = HashMap<String, String>()
    private val domainPackages = HashMap<String, List<String>>()
    private val debloat = HashSet<String>()
    private var profile: DeviceProfile? = null

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = context.applicationContext
            runCatching { loadPackages(app) }
                .onFailure { Log.w(TAG, "load package_intel failed", it) }
            runCatching { loadDomains(app) }
                .onFailure { Log.w(TAG, "load domain_intel failed", it) }
            runCatching { loadProfile(app) }
                .onFailure { Log.w(TAG, "load device_profile failed", it) }
            loaded = true
        }
    }

    fun packageIntel(packageName: String?): PackageIntel? {
        if (!loaded || packageName.isNullOrBlank()) return null
        return packages[packageName]
    }

    fun knownPackages(): Map<String, PackageIntel> =
        if (loaded) packages else emptyMap()

    fun isDebloatCleaned(packageName: String?): Boolean =
        loaded && packageName != null && debloat.contains(packageName)

    /** 精确匹配失败时按父域名回退（用于识别子域上报）。 */
    fun domainIntel(domain: String?): DomainIntel? {
        if (!loaded || domain.isNullOrBlank()) return null
        var d = domain.trim().lowercase().trimEnd('.')
        // 去掉端口/路径/通配前缀
        d = d.substringBefore('/')
        d = d.substringBefore(':')
        while (true) {
            val kind = domainKinds[d]
            if (kind != null) {
                return DomainIntel(kind, domainPackages[d].orEmpty())
            }
            val idx = d.indexOf('.')
            if (idx < 0) return null
            d = d.substring(idx + 1)
        }
    }

    fun domainKind(domain: String?): String? = domainIntel(domain)?.kind

    fun deviceProfile(): DeviceProfile = if (loaded) (profile ?: fallbackProfile()) else fallbackProfile()

    private fun fallbackProfile(): DeviceProfile = DeviceProfile(
        marketingName = "Xiaomi 17 Pro Max",
        model = "2509FPN0BC",
        os = "HyperOS 4",
        android = "Android 17 / SDK 37",
        kernel = "6.12.69-android16",
        root = "KernelSU 越狱模式",
        scannedPackages = 0,
        trackerDomains = 0,
        repoKnowledgePackages = 0,
        dataSource = "built-in",
    )

    private fun loadPackages(context: Context) {
        val text = context.assets.open("datasets/package_intel.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val arr = root.optJSONObject("packages") ?: return
        arr.keys().forEach { pkg ->
            val o = arr.optJSONObject(pkg) ?: return@forEach
            packages[pkg] = PackageIntel(
                packageName = pkg,
                label = o.optString("label").ifBlank { null },
                category = o.optString("category").ifBlank { null },
                vendor = o.optString("vendor").ifBlank { null },
                system = o.optBoolean("system", false),
                uid = o.optString("uid").toIntOrNull(),
                dataTypes = o.optJSONArray("dataTypes").toStringList(),
                endpoints = o.optJSONArray("endpoints").toStringList(),
                risk = o.optString("risk").ifBlank { null },
                action = o.optString("action").ifBlank { null },
                reason = o.optString("reason").ifBlank { null },
                score = if (o.has("score")) o.optInt("score") else null,
            )
        }
        val db = root.optJSONObject("debloat")
        if (db != null) {
            db.keys().forEach { debloat.add(it) }
        }
    }

    private fun loadDomains(context: Context) {
        val text = context.assets.open("datasets/domain_intel.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val kinds = root.optJSONObject("kinds") ?: return
        kinds.keys().forEach { d ->
            domainKinds[d] = kinds.optString(d)
        }
        val pkgs = root.optJSONObject("packages")
        pkgs?.keys()?.forEach { d ->
            domainPackages[d] = pkgs.optJSONArray(d).toStringList()
        }
    }

    private fun loadProfile(context: Context) {
        val text = context.assets.open("datasets/device_profile.json").bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val audit = o.optJSONObject("audit")
        profile = DeviceProfile(
            marketingName = o.optString("marketingName", "Xiaomi 17 Pro Max"),
            model = o.optString("model", "2509FPN0BC"),
            os = o.optString("os", "HyperOS 4"),
            android = o.optString("android", "Android 17 / SDK 37"),
            kernel = o.optString("kernel", ""),
            root = o.optString("root", "KernelSU"),
            scannedPackages = audit?.optInt("scannedPackages", 0) ?: 0,
            trackerDomains = audit?.optInt("trackerDomains", 0) ?: 0,
            repoKnowledgePackages = audit?.optInt("repoKnowledgePackages", 0) ?: 0,
            dataSource = o.optString("dataSource", "repo"),
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i).ifBlank { null } }
    }
}
