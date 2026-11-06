package dev.popsicle.privprobe.core.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.popsicle.privprobe.core.intel.RepoIntel
import dev.popsicle.privprobe.core.root.RootShell
import java.util.concurrent.ConcurrentHashMap

/**
 * uid ↔ 包名 / 显示名 / 是否系统应用。
 *
 * 数据来源优先级：
 * 1. `pm list packages -U`（root 下覆盖全部用户/已安装包，含 uid）；
 * 2. `/data/system/packages.list`（root 下读，pm 不可用时的兜底）；
 * 3. PackageManager（label / 系统标志；Android 17 对 app 身份做了包可见性收紧，
 *    实测本 App 走 PM 只拿到极少包，因此覆盖不足时会走 root 兜底）；
 * 4. 仓库知识库 [RepoIntel]（中文名、system uid 映射、已清理标记）；
 * 5. 内置系统服务 uid 中文名表（android/phone/bluetooth/audioserver/cameraserver/netd/gms ...）。
 *
 * 首次 [init] 后异步加载；加载失败（PM 覆盖不足且 root 兜底失败）时进入 degraded 状态并按
 * 指数退避持续重试——root 稍后才授权时也能自愈。期间查询回退到 PackageManager / 内置表。
 */
object UidResolver {

    private const val TAG = "UidResolver"

    /** 加载状态（供配置页/诊断展示，root 不可用时可显示降级提示）。 */
    enum class LoadState { IDLE, LOADING, READY, DEGRADED }

    private data class LoadStats(
        val pmCount: Int,
        val pmListCount: Int,
        val rootCount: Int,
        val packagesListCount: Int,
        val uidCount: Int,
        val source: String,
        val error: String? = null,
    )

    private val packageToUid = ConcurrentHashMap<String, Int>()
    private val uidToPackages = ConcurrentHashMap<Int, MutableList<String>>()
    private val packageLabels = ConcurrentHashMap<String, String>()
    private val systemUidLabels = ConcurrentHashMap<Int, String>()
    private val systemPkgFlags = ConcurrentHashMap<String, Boolean>()
    private val validatedPackages = ConcurrentHashMap<String, Boolean>()

    /** 串行化加载：保证加载与查询不会同时改包表。 */
    private val loadLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loadState: LoadState = LoadState.IDLE

    @Volatile
    private var degradedReason: String? = null

    @Volatile
    private var retryThread: Thread? = null

    /** 系统服务 uid → 中文标签（设备实测 + AOSP 约定）。 */
    private val BUILTIN_SYSTEM_UIDS: Map<Int, String> = mapOf(
        0 to "root",
        1000 to "Android 系统",
        1001 to "电话服务",
        1002 to "蓝牙",
        1013 to "媒体服务",
        1019 to "无线电",
        1027 to "NFC",
        1041 to "音频服务",
        1047 to "相机服务",
        1052 to "netd / DNS",
        1053 to "媒体编解码",
        1068 to "网络服务",
        1073 to "网络策略",
        2000 to "Shell",
        10135 to "Google 服务 (GMS)",
        10223 to "系统界面 (SystemUI)",
        10220 to "系统更新",
        10358 to "腾讯安全组件",
        10200 to "MIUI Core",
        10133 to "小爱同学 (AICR)",
        10142 to "小米位置服务",
        10150 to "小米相机",
        10221 to "小米电源统计",
    )

    /** 少于该包数就认为 PackageManager 覆盖不足（设备实测约 430+ 包）。 */
    private const val PM_COVERAGE_THRESHOLD = 100

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        systemUidLabels.putAll(BUILTIN_SYSTEM_UIDS)
        // RepoIntel 若带 system uid → 包映射，一并加入
        runCatching {
            RepoIntel.knownPackages().forEach { (pkg, intel) ->
                intel.uid?.let { uid ->
                    if (intel.system) {
                        uidToPackages.getOrPut(uid) { mutableListOf() }.let { list ->
                            if (!list.contains(pkg)) list.add(pkg)
                        }
                        packageToUid.putIfAbsent(pkg, uid)
                        intel.label?.let { systemUidLabels.putIfAbsent(uid, it) }
                    }
                }
            }
        }
        startLoader()
    }

    private fun startLoader() {
        if (retryThread?.isAlive == true) return
        val t = Thread({ loadWithRetry() }, "UidResolver-init").apply { isDaemon = true }
        retryThread = t
        t.start()
    }

    private fun loadWithRetry() {
        var backoff = 10_000L
        var attempt = 0
        while (true) {
            attempt++
            loadState = LoadState.LOADING
            val stats = runCatching { synchronized(loadLock) { loadOnce() } }.getOrElse {
                Log.w(TAG, "uid load attempt $attempt failed", it)
                null
            }
            if (stats != null && stats.uidCount > 1) {
                loadState = LoadState.READY
                degradedReason = null
                Log.i(
                    TAG,
                    "uid map ready: ${stats.uidCount} uids, source=${stats.source}, " +
                        "pm=${stats.pmCount}, pmList=${stats.pmListCount}, root=${stats.rootCount}, " +
                        "packages.list=${stats.packagesListCount}, attempt=$attempt",
                )
                return
            }
            loadState = LoadState.DEGRADED
            degradedReason = buildString {
                append("uid 归因降级：PackageManager 仅 ${stats?.pmCount ?: -1} 个包")
                append("、pm list 仅 ${stats?.pmListCount ?: -1} 条")
                if (stats?.rootCount == 0) append("，root pm 兜底失败")
                stats?.error?.let { append("（$it）") }
                append("；将在后台重试")
            }
            Log.w(TAG, degradedReason!!)
            runCatching { Thread.sleep(backoff) }
            backoff = (backoff * 2).coerceAtMost(300_000L)
        }
    }

    private fun loadOnce(): LoadStats {
        val ctx = appContext ?: return LoadStats(0, 0, 0, 0, packageToUid.size, "none", "未初始化")
        val pm = ctx.packageManager
        // 1) PackageManager 全量（label/标记/uid）——Android 17 下可能被包可见性收紧
        var pmCount = 0
        runCatching {
            val packages = pm.getInstalledPackages(0)
            packages.forEach { pi ->
                val ai = pi.applicationInfo ?: return@forEach
                pmCount++
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
                if (!label.isNullOrBlank()) packageLabels[pi.packageName] = label
                systemPkgFlags[pi.packageName] =
                    (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val uid = runCatching { pm.getPackageUid(pi.packageName, 0) }.getOrDefault(-1)
                if (uid > 0) putPackage(pi.packageName, uid)
            }
        }.onFailure { Log.w(TAG, "PackageManager load failed", it) }

        // 2) pm list packages -U（app 身份下通常被过滤成 1 条，root 才完整；先试进程内 shell）
        val pmListCount = runCatching { parsePmList(runShellOutput("pm list packages -U")) }.getOrDefault(0)

        // 3) PM 覆盖不足时走 root 兜底：pm（root 身份）+ /data/system/packages.list
        var rootCount = 0
        var packagesListCount = 0
        var rootError: String? = null
        if (pmCount < PM_COVERAGE_THRESHOLD && packageToUid.size < PM_COVERAGE_THRESHOLD) {
            val rootPm = runCatching { RootShell.execLines("pm list packages -U", 15_000) }.getOrNull()
            if (rootPm != null && rootPm.size > 1) {
                rootCount = parsePmList(rootPm)
                // 系统包清单（root 身份），用于修正 is_system
                val rootSys = runCatching { RootShell.execLines("pm list packages -s -U", 15_000) }.getOrNull()
                rootSys?.forEach { line ->
                    val pkg = parsePmLine(line)?.first ?: return@forEach
                    systemPkgFlags[pkg] = true
                }
            } else {
                rootError = RootShell.detail.take(120)
                // packages.list 兜底：`<package> <uid> <debuggable> <dataDir> ...`
                val list = runCatching {
                    RootShell.execLines("cat /data/system/packages.list 2>/dev/null", 12_000)
                }.getOrNull()
                if (list != null && list.size > 1) {
                    list.forEach { line ->
                        val cols = line.trim().split(Regex("\\s+"))
                        if (cols.size < 2) return@forEach
                        val pkg = cols[0]
                        val uid = cols[1].toIntOrNull() ?: return@forEach
                        if (pkg.contains('.') && uid > 0) {
                            putPackage(pkg, uid)
                            packagesListCount++
                        }
                    }
                }
            }
        }

        // 只统计 PM / root 真实解析出的 uid：RepoIntel 里带 uid 的条目不能单独把状态标成 READY，
        // 否则「PM 只有 1 个包 + root 不可用」会被 RepoIntel 伪装成加载成功，包名覆盖依然缺失。
        val osUidCount = packageToUid.values.toSet().size

        // 4) RepoIntel 已知包补齐 label/uid/system
        runCatching {
            RepoIntel.knownPackages().forEach { (pkg, intel) ->
                intel.label?.let { packageLabels.putIfAbsent(pkg, it) }
                intel.uid?.let { uid ->
                    packageToUid.putIfAbsent(pkg, uid)
                    uidToPackages.getOrPut(uid) { mutableListOf() }.let { list ->
                        if (!list.contains(pkg)) list.add(pkg)
                    }
                }
                if (intel.system) systemPkgFlags.putIfAbsent(pkg, true)
            }
        }

        val source = when {
            rootCount > 0 -> "root-pm"
            packagesListCount > 0 -> "root-packages.list"
            pmListCount > 1 -> "pm-list"
            pmCount > 1 -> "package-manager"
            else -> "none"
        }
        return LoadStats(
            pmCount = pmCount,
            pmListCount = pmListCount,
            rootCount = rootCount,
            packagesListCount = packagesListCount,
            uidCount = osUidCount,
            source = source,
            error = rootError,
        )
    }

    /** 解析 `package:com.foo uid:10123`；返回成功条数。 */
    private fun parsePmList(lines: List<String>): Int {
        var count = 0
        lines.forEach { line ->
            val pair = parsePmLine(line) ?: return@forEach
            putPackage(pair.first, pair.second)
            count++
        }
        return count
    }

    private fun parsePmLine(line: String): Pair<String, Int>? {
        val l = line.trim()
        if (!l.startsWith("package:")) return null
        val pkg = l.removePrefix("package:").substringBefore(" ").trim()
        if (pkg.isEmpty() || !pkg.contains('.')) return null
        val uid = Regex("uid:(\\d+)").find(l)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        if (uid <= 0) return null
        return pkg to uid
    }

    private fun runShellOutput(command: String): List<String> = runCatching {
        val p = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.lineSequence().toList()
    }.getOrDefault(emptyList())

    private fun putPackage(pkg: String, uid: Int) {
        packageToUid[pkg] = uid
        uidToPackages.getOrPut(uid) { mutableListOf() }.let { list ->
            if (!list.contains(pkg)) list.add(pkg)
        }
    }

    fun packageForUid(uid: Int): String? {
        if (uid < 0) return null
        uidToPackages[uid]?.firstOrNull()?.let { return it }
        return when (uid) {
            0 -> "root"
            1000 -> "android"
            1001 -> "com.android.phone"
            1002 -> "com.android.bluetooth"
            1013 -> "media"
            1019 -> "com.android.radio"
            1041 -> "audioserver"
            1047 -> "cameraserver"
            1052 -> "netd"
            10135 -> "com.google.android.gms"
            10223 -> "com.android.systemui"
            10200 -> "com.miui.core"
            else -> null
        }
    }

    fun uidForPackage(pkg: String?): Int {
        if (pkg.isNullOrBlank()) return -1
        packageToUid[pkg]?.let { return it }
        RepoIntel.packageIntel(pkg)?.uid?.let { return it }
        val ctx = appContext ?: return -1
        return runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
    }

    /** 显示名：RepoIntel 中文名 > PackageManager label > 系统 uid 名 > 包名 > uid。 */
    fun labelFor(pkg: String?, uid: Int?): String {
        if (!pkg.isNullOrBlank()) {
            RepoIntel.packageIntel(pkg)?.label?.let { if (it.isNotBlank()) return it }
            packageLabels[pkg]?.let { if (it.isNotBlank()) return it }
        }
        uid?.let { u ->
            systemUidLabels[u]?.let { return it }
            packageForUid(u)?.let { p ->
                RepoIntel.packageIntel(p)?.label?.let { if (it.isNotBlank()) return it }
            }
        }
        return pkg ?: if (uid != null && uid > 0) "uid $uid" else "未知调用者"
    }

    fun isKnownPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (packageToUid.containsKey(pkg)) return true
        if (RepoIntel.packageIntel(pkg) != null) return true
        return validatedPackages[pkg] == true
    }

    /**
     * 校验并返回「看起来真实存在」的包名。
     *
     * 探针日志里经常把 Java 类名（如 `com.miui.powerkeeper...ScenarioCollectorUtils`）
     * 或内部类当成包名；仅做语法检查不够，必须经 PackageManager / RepoIntel / 已解析包表确认。
     * 返回 null 表示候选不可信，调用方应改用 [packageForUid] 兜底/留空。
     */
    fun validatePackageName(candidate: String?): String? {
        val pkg = sanitizePackageName(candidate) ?: return null
        if (isKnownPackage(pkg)) return pkg
        // 负缓存：传感器日志里同一个类名会反复出现，避免每轮都走一次 PM 查询
        validatedPackages[pkg]?.let { return if (it) pkg else null }
        val ctx = appContext ?: return null
        val exists = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        }.getOrDefault(false)
        if (validatedPackages.size > 4096) validatedPackages.clear()
        validatedPackages[pkg] = exists
        return if (exists) pkg else null
    }

    /**
     * 仅做包名语法归一：去掉末尾标点、拒绝大写 / `$` / `/` / 空格，至少 2 段。
     * 只用于清洗候选，不判断包是否真实存在（那一步用 [validatePackageName]）。
     */
    fun sanitizePackageName(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val p = candidate.trim().trimEnd('.', ',', ')', ';', ':', '"', '\'')
        if (p.isEmpty() || p.length > 200) return null
        if (p.contains('$') || p.contains('/') || p.contains(' ') || p.any { it.isUpperCase() }) return null
        val segments = p.split('.')
        if (segments.size < 2) return null
        if (segments.any { seg -> seg.isEmpty() || !seg.all { it.isLetterOrDigit() || it == '_' } }) return null
        if (!segments.first().first().isLetter()) return null
        return p
    }

    fun isSystemPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        systemPkgFlags[pkg]?.let { return it }
        RepoIntel.packageIntel(pkg)?.let { return it.system }
        val ctx = appContext ?: return false
        val flag = runCatching {
            @Suppress("DEPRECATION")
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)
        systemPkgFlags[pkg] = flag
        return flag
    }

    /**
     * uid 是否系统。
     *
     * 修复：-1（未知归因）不能当系统；0（root）与 <10000 的系统服务 uid 才算。
     * >=10000 时结合已解析包的系统标志；包表为空时用系统 uid 名称表 / RepoIntel 兜底。
     */
    fun isSystemUid(uid: Int): Boolean {
        if (uid < 0) return false
        if (uid < 10_000) return true
        val pkgs = uidToPackages[uid]
        if (!pkgs.isNullOrEmpty()) {
            // 共享 uid 只要有任意一个包是系统包即视为系统（如 gms 全家桶）
            if (pkgs.any { isSystemPackage(it) }) return true
            return false
        }
        // 没有解析到包：系统服务 uid 表 / RepoIntel 系统映射兜底
        return systemUidLabels.containsKey(uid) || RepoIntel.knownPackages().values.any {
            it.system && it.uid == uid
        }
    }

}
