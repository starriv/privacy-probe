package dev.popsicle.privprobe.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.popsicle.privprobe.BuildConfig
import dev.popsicle.privprobe.core.data.PrivacyRepository
import dev.popsicle.privprobe.core.data.ProbeId
import dev.popsicle.privprobe.core.intel.RepoIntel
import dev.popsicle.privprobe.core.update.UpdateChecker
import dev.popsicle.privprobe.core.update.UpdateInfo
import dev.popsicle.privprobe.core.update.UpdateResult
import dev.popsicle.privprobe.service.CollectorControllerImpl
import dev.popsicle.privprobe.ui.UiSources
import dev.popsicle.privprobe.ui.components.ActionRow
import dev.popsicle.privprobe.ui.components.Hairline
import dev.popsicle.privprobe.ui.components.InfoRow
import dev.popsicle.privprobe.ui.components.LiveDot
import dev.popsicle.privprobe.ui.components.OutlineButton
import dev.popsicle.privprobe.ui.components.PreviewBadge
import dev.popsicle.privprobe.ui.components.PrimaryButton
import dev.popsicle.privprobe.ui.components.ProbeCard
import dev.popsicle.privprobe.ui.components.ProbeScaffold
import dev.popsicle.privprobe.ui.components.SectionTitle
import dev.popsicle.privprobe.ui.components.SettingDivider
import dev.popsicle.privprobe.ui.components.SliderRow
import dev.popsicle.privprobe.ui.components.StateDot
import dev.popsicle.privprobe.ui.components.StepperRow
import dev.popsicle.privprobe.ui.components.SwitchRow
import dev.popsicle.privprobe.ui.theme.LocalProbePalette
import dev.popsicle.privprobe.ui.theme.ProbeText
import dev.popsicle.privprobe.ui.util.formatMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SELF_TEST_PERMISSIONS = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

private enum class ExportKind(val label: String, val ext: String) {
    CSV("CSV", "csv"),
    JSONL("JSONL", "jsonl"),
}

/**
 * 配置页：中性状态卡 + 采集开关 + 探针列表 + 频率/保留 + 数据 + 诊断 + 关于。
 * 只有「运行自检」是 accent 实心按钮，其余 outline / 文本。
 */
@Composable
fun SettingsScreen(sources: UiSources) {
    val settings by sources.settings.state.collectAsStateWithLifecycle()
    val running by sources.collector.running.collectAsStateWithLifecycle()
    val rootAvailable by sources.collector.rootAvailable.collectAsStateWithLifecycle()
    val rootDetail by sources.collector.rootDetail.collectAsStateWithLifecycle()
    val lastError by sources.collector.lastError.collectAsStateWithLifecycle()
    val probeStatus by sources.collector.probeStatus.collectAsStateWithLifecycle()
    // 自检结果（CollectorControllerImpl 额外暴露的 StateFlow，契约外但只读）
    val collectorImpl = remember(sources.collector) { sources.collector as? CollectorControllerImpl }
    val fallbackSelfTestFlow = remember { MutableStateFlow<String?>(null) }
    val selfTestFlow = remember(collectorImpl) {
        collectorImpl?.selfTestResult ?: fallbackSelfTestFlow
    }
    val selfTestStatus by selfTestFlow.collectAsStateWithLifecycle()
    val p = LocalProbePalette.current

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var selfTesting by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<ExportKind?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSelfTestConfirm by remember { mutableStateOf(false) }
    var exportConfirm by remember { mutableStateOf<ExportKind?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf<UpdateInfo?>(null) }
    var diagTick by remember { mutableIntStateOf(0) }
    var totalEvents by remember { mutableStateOf<Long?>(null) }
    var domainCount by remember { mutableStateOf<Int?>(null) }
    var pollLocal by remember(settings.pollIntervalMs) {
        mutableFloatStateOf(settings.pollIntervalMs.toFloat())
    }
    var appOpsLocal by remember(settings.appOpsIntervalMs) {
        mutableFloatStateOf(settings.appOpsIntervalMs.toFloat())
    }

    LaunchedEffect(sources, diagTick) {
        totalEvents = runCatching { sources.repository.totalEventCount() }.getOrNull()
        domainCount = runCatching { sources.repository.domainCount() }.getOrNull()
    }

    fun runRefresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            runCatching { sources.collector.refresh(context) }
                .onSuccess { snackbar.showSnackbar("已重新检测 root 与采集状态") }
                .onFailure { snackbar.showSnackbar("检测失败：${it.message ?: "未知错误"}") }
            refreshing = false
            diagTick++
        }
    }

    fun startSelfTest() {
        if (selfTesting) return
        scope.launch {
            selfTesting = true
            val result = runCatching { sources.collector.runSelfTest(context) }
            selfTesting = false
            val detail = collectorImpl?.selfTestResult?.value
            if (result.isFailure) {
                snackbar.showSnackbar("自检失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
            } else {
                snackbar.showSnackbar(
                    detail?.take(160)?.takeIf { it.isNotBlank() } ?: "自检已执行，结果见「诊断 · 自检结果」",
                )
            }
            diagTick++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // 只有全部授予才进入自检；任何拒绝 / 部分授予都中止（不做 root 强授）
        if (SELF_TEST_PERMISSIONS.all { selfTestPermissionGranted(context, it) }) {
            showSelfTestConfirm = true
        } else {
            scope.launch {
                snackbar.showSnackbar("已取消：未授予相机 / 麦克风 / 定位权限，自检未执行")
            }
        }
    }

    fun onSelfTestClick() {
        if (selfTesting) return
        val missing = SELF_TEST_PERMISSIONS.filter { !selfTestPermissionGranted(context, it) }
        if (missing.isEmpty()) {
            showSelfTestConfirm = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun startExport(kind: ExportKind) {
        if (exporting != null) return
        // 先确认内容含敏感数据，再让用户用 SAF 选择保存位置；不写共享 Downloads、不自动分享
        exportConfirm = kind
    }

    fun finishExport(kind: ExportKind, uri: Uri?) {
        if (uri == null) {
            scope.launch { snackbar.showSnackbar("已取消导出，未写入任何文件") }
            return
        }
        if (exporting != null) return
        scope.launch {
            exporting = kind
            val result = runCatching { exportToUri(context, sources.repository, kind, uri) }
            exporting = null
            result.onSuccess { count ->
                snackbar.showSnackbar("已导出 $count 条事件（含未脱敏域名），仅保存在你选择的位置")
            }.onFailure {
                // 清理用户选择位置上可能残留的半成品文件
                runCatching { context.contentResolver.delete(uri, null, null) }
                snackbar.showSnackbar("导出失败：${it.message ?: "未知错误"}（已清理未完成文件）")
            }
            diagTick++
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> finishExport(ExportKind.CSV, uri) }

    val jsonlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri -> finishExport(ExportKind.JSONL, uri) }

    fun launchExportChooser(kind: ExportKind) {
        val suggested = suggestedExportName(kind)
        when (kind) {
            ExportKind.CSV -> csvLauncher.launch(suggested)
            ExportKind.JSONL -> jsonlLauncher.launch(suggested)
        }
    }

    fun clearAllData() {
        scope.launch {
            runCatching { sources.repository.clearAll() }
                .onSuccess { snackbar.showSnackbar("已清空全部本地事件") }
                .onFailure { snackbar.showSnackbar("清空失败：${it.message ?: "未知错误"}") }
            diagTick++
        }
    }

    /** 只打开 GitHub Releases 链接（UpdateChecker 已限定域名）。 */
    fun openDownload(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            scope.launch { snackbar.showSnackbar("无法打开浏览器：${it.message ?: "未知错误"}") }
        }
    }

    /** 用户主动点击时查询一次 GitHub 最新 Release，不做后台轮询。 */
    fun checkForUpdate() {
        if (checkingUpdate) return
        scope.launch {
            checkingUpdate = true
            val result = runCatching { UpdateChecker.check() }
            checkingUpdate = false
            result.onFailure {
                snackbar.showSnackbar("检查更新失败：${it.message ?: "网络不可用"}")
            }.onSuccess { update ->
                when (update) {
                    is UpdateResult.Available -> updateAvailable = update.info
                    is UpdateResult.UpToDate ->
                        snackbar.showSnackbar("已是最新版本（${update.latestVersion}）")
                    is UpdateResult.Failed ->
                        snackbar.showSnackbar("检查更新失败：${update.reason}")
                }
            }
        }
    }

    val healthyCount = probeStatus.count { it.value.healthy }

    ProbeScaffold(
        title = "配置",
        trailing = { if (sources.preview) PreviewBadge() },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            ) {
                // ---------------------------------------------------------- 状态
                item {
                    ProbeCard(padding = 0.dp) {
                        StatusLine(
                            label = "root",
                            value = if (rootAvailable) {
                                "已授权 · $rootDetail".take(64)
                            } else {
                                "未授权 · 低层探针不可用"
                            },
                            dot = if (rootAvailable) p.live else p.down,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        StatusLine(
                            label = "采集",
                            value = if (running) "运行中" else "已暂停",
                            dot = if (running) p.live else p.muted,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        StatusLine(
                            label = "探针",
                            value = "$healthyCount / ${probeStatus.size} 健康",
                            dot = if (healthyCount == probeStatus.size) p.live else p.warn,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        StatusLine(
                            label = "最近错误",
                            value = lastError ?: "无",
                            dot = if (lastError != null) p.down else null,
                            valueColor = if (lastError != null) p.down else p.ink2,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlineButton(
                                text = if (refreshing) "检测中…" else "重新检测",
                                onClick = { runRefresh() },
                                modifier = Modifier.weight(1f),
                                enabled = !refreshing,
                            )
                            PrimaryButton(
                                text = if (selfTesting) "自检中…" else "运行自检",
                                onClick = { onSelfTestClick() },
                                modifier = Modifier.weight(1f),
                                enabled = !selfTesting,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ---------------------------------------------------------- 采集
                item {
                    SectionTitle(text = "采集")
                    ProbeCard(padding = 0.dp) {
                        SwitchRow(
                            title = "采集总开关",
                            subtitle = when {
                                running -> "采集服务运行中"
                                !settings.collectorEnabled -> "默认关闭；开启后启动前台服务与 root 探针"
                                else -> "采集服务已暂停"
                            },
                            checked = settings.collectorEnabled,
                            onCheckedChange = { enabled ->
                                sources.settings.update { it.copy(collectorEnabled = enabled) }
                                if (enabled) {
                                    sources.collector.start(context)
                                    val notificationGranted =
                                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) == PackageManager.PERMISSION_GRANTED
                                    if (!notificationGranted) {
                                        scope.launch {
                                            snackbar.showSnackbar("未授予通知权限，采集仍可运行，但前台服务通知不可见")
                                        }
                                    }
                                } else {
                                    sources.collector.stop(context)
                                }
                            },
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "常驻前台通知",
                            subtitle = "采集时显示不可清除的状态通知",
                            checked = settings.foregroundNotification,
                            onCheckedChange = { value ->
                                sources.settings.update { it.copy(foregroundNotification = value) }
                            },
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "低层原生采集",
                            subtitle = "关闭后仅系统服务 / 日志类探针",
                            checked = settings.lowLevelEnabled,
                            onCheckedChange = { value ->
                                sources.settings.update { it.copy(lowLevelEnabled = value) }
                            },
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "显示系统应用",
                            subtitle = "包含 MIUI / Google 等系统包",
                            checked = settings.showSystemApps,
                            onCheckedChange = { value ->
                                sources.settings.update { it.copy(showSystemApps = value) }
                            },
                        )
                        SettingDivider()
                        SwitchRow(
                            title = "域名脱敏",
                            subtitle = "展示时隐藏子域，如 a***.weixin.qq.com",
                            checked = settings.maskDomains,
                            onCheckedChange = { value ->
                                sources.settings.update { it.copy(maskDomains = value) }
                            },
                        )
                    }
                }

                // ---------------------------------------------------------- 探针
                item {
                    SectionTitle(
                        text = "采集探针",
                        trailing = {
                            Text(
                                text = "健康 $healthyCount / ${probeStatus.size}",
                                style = ProbeText.Meta,
                                color = p.muted,
                            )
                        },
                    )
                    Text(
                        text = "共 ${ProbeId.entries.size} 个类别开关；健康数是采集进程上报的探针状态，" +
                            "底层 pcap / uprobe / kprobe 由单个 privd 进程承载，不是每个开关一个进程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                    ProbeCard(padding = 0.dp) {
                        ProbeId.entries.forEachIndexed { index, probe ->
                            if (index > 0) SettingDivider()
                            val status = probeStatus[probe]
                            SwitchRow(
                                title = probe.title,
                                subtitle = status?.detail?.takeIf { it.isNotBlank() } ?: probe.description,
                                checked = settings.probes[probe] ?: true,
                                onCheckedChange = { value ->
                                    sources.settings.update { current ->
                                        current.copy(probes = current.probes + (probe to value))
                                    }
                                },
                                leading = { StateDot(healthy = status?.healthy == true) },
                            )
                        }
                    }
                }

                // ---------------------------------------------------------- 频率与保留
                item {
                    SectionTitle(text = "频率与保留")
                    ProbeCard(padding = 0.dp) {
                        SliderRow(
                            title = "轮询间隔",
                            subtitle = "相机 / 音频 / 定位 / 传感器基础间隔",
                            valueLabel = formatMillis(pollLocal.toLong()),
                            value = pollLocal,
                            onValueChange = { pollLocal = it },
                            onValueChangeFinished = {
                                sources.settings.update { it.copy(pollIntervalMs = pollLocal.toLong()) }
                            },
                            valueRange = 500f..5_000f,
                            steps = 17,
                        )
                        SettingDivider()
                        SliderRow(
                            title = "AppOps 间隔",
                            subtitle = "dumpsys appops 较重，建议 15 秒以上",
                            valueLabel = formatMillis(appOpsLocal.toLong()),
                            value = appOpsLocal,
                            onValueChange = { appOpsLocal = it },
                            onValueChangeFinished = {
                                sources.settings.update { it.copy(appOpsIntervalMs = appOpsLocal.toLong()) }
                            },
                            valueRange = 3_000f..60_000f,
                            steps = 56,
                        )
                        SettingDivider()
                        StepperRow(
                            title = "数据保留",
                            subtitle = "超过保留期的事件自动清理",
                            valueLabel = "${settings.retentionDays} 天",
                            onDecrease = {
                                sources.settings.update {
                                    it.copy(retentionDays = (it.retentionDays - 1).coerceAtLeast(1))
                                }
                            },
                            onIncrease = {
                                sources.settings.update {
                                    it.copy(retentionDays = (it.retentionDays + 1).coerceAtMost(30))
                                }
                            },
                            canDecrease = settings.retentionDays > 1,
                            canIncrease = settings.retentionDays < 30,
                        )
                    }
                }

                // ---------------------------------------------------------- 数据
                item {
                    SectionTitle(text = "数据")
                    ProbeCard(padding = 0.dp) {
                        InfoRow(label = "事件总数（保留期内）", value = totalEvents?.toString() ?: "…")
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "域名数（保留期内）", value = domainCount?.toString() ?: "…")
                        Hairline()
                        ActionRow(
                            title = "导出 CSV",
                            subtitle = "自选保存位置，不自动分享",
                            onClick = { startExport(ExportKind.CSV) },
                            enabled = exporting == null,
                            trailing = {
                                if (exporting == ExportKind.CSV) {
                                    CircularProgressIndicator(
                                        color = p.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                        Hairline()
                        ActionRow(
                            title = "导出 JSONL",
                            subtitle = "每行一条事件，自选保存位置，不自动分享",
                            onClick = { startExport(ExportKind.JSONL) },
                            enabled = exporting == null,
                            trailing = {
                                if (exporting == ExportKind.JSONL) {
                                    CircularProgressIndicator(
                                        color = p.accent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                        Hairline()
                        ActionRow(
                            title = "清空所有数据",
                            subtitle = "删除本机数据库内全部事件，不可恢复",
                            onClick = { showClearConfirm = true },
                            destructive = true,
                        )
                        Hairline()
                        Text(
                            text = "统计口径：事件总数 = 当前保留期内的全部事件；域名数 = 保留期内出现过的网络目标域名。" +
                                "导出内容包含原始事件（域名不做脱敏）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // ---------------------------------------------------------- 诊断
                item {
                    SectionTitle(text = "诊断")
                    ProbeCard(padding = 0.dp) {
                        InfoRow(
                            label = "root",
                            value = if (rootAvailable) "可用" else "不可用",
                            valueColor = if (rootAvailable) p.ink else p.down,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "采集状态", value = if (running) "运行中" else "已暂停")
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "探针健康", value = "$healthyCount / ${probeStatus.size}")
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(
                            label = "最近错误",
                            value = lastError ?: "无",
                            valueColor = if (lastError != null) p.down else p.ink2,
                            valueStart = lastError != null,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(
                            label = "自检结果",
                            value = selfTestStatus?.takeIf { it.isNotBlank() } ?: "未运行",
                            valueColor = if (selfTestStatus?.startsWith("自检通过") == true) p.ink else p.ink2,
                            valueStart = true,
                        )
                    }
                }

                // ---------------------------------------------------------- 关于
                item {
                    val profile = remember { RepoIntel.deviceProfile() }
                    SectionTitle(text = "关于")
                    ProbeCard(padding = 0.dp) {
                        InfoRow(
                            label = "应用",
                            value = "隐私探针（dev.popsicle.privprobe）",
                            valueStart = true,
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(
                            label = "设备",
                            value = "${profile.marketingName}（${profile.model}）",
                        )
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "系统", value = "${profile.os} · ${profile.android}", valueStart = true)
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "内核", value = profile.kernel, valueStart = true)
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "root", value = profile.root, valueStart = true)
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "扫描包数", value = profile.scannedPackages.toString())
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "追踪域名", value = profile.trackerDomains.toString())
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "知识库包", value = profile.repoKnowledgePackages.toString())
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "数据来源", value = profile.dataSource, valueStart = true)
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        InfoRow(label = "版本", value = BuildConfig.VERSION_NAME)
                        Hairline(modifier = Modifier.padding(start = 16.dp))
                        ActionRow(
                            title = "检查更新",
                            subtitle = if (checkingUpdate) "正在查询 GitHub…" else "有新版本时可跳转浏览器下载",
                            enabled = !checkingUpdate,
                            trailing = {
                                if (checkingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = p.accent,
                                    )
                                } else {
                                    Text(text = "检查", style = ProbeText.Label, color = p.accent)
                                }
                            },
                            onClick = { checkForUpdate() },
                        )
                        Hairline()
                        Text(
                            text = "仅限本机授权设备使用。事件只写入本机数据库，不上传。" +
                                "只有在自检和主动点击「检查更新」时才会访问网络。",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )

            if (showSelfTestConfirm) {
                AlertDialog(
                    onDismissRequest = { showSelfTestConfirm = false },
                    title = { Text(text = "运行链路自检？", color = p.ink) },
                    text = {
                        Text(
                            text = "自检会打开相机约 1 秒、录音约 1.5 秒，并请求一次定位，用于验证探针链路是否正常。" +
                                "所有数据仅保存在本机，不会上传；自检不会使用 root 强制授权。",
                            color = p.muted,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showSelfTestConfirm = false
                                startSelfTest()
                            },
                        ) {
                            Text(text = "开始自检", color = p.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSelfTestConfirm = false }) {
                            Text(text = "取消", color = p.ink2)
                        }
                    },
                    containerColor = p.card,
                )
            }

            exportConfirm?.let { kind ->
                AlertDialog(
                    onDismissRequest = { exportConfirm = null },
                    title = { Text(text = "导出 ${kind.label}？", color = p.ink) },
                    text = {
                        Text(
                            text = "导出内容包含本机采集的敏感事件（应用行为、域名 / 目标、时间等），" +
                                "其中域名不做脱敏。请保存到可信位置，不要分享给不可信的应用。" +
                                "导出会先写入应用缓存临时文件，完成或失败后立即删除。",
                            color = p.muted,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val target = kind
                                exportConfirm = null
                                launchExportChooser(target)
                            },
                        ) {
                            Text(text = "选择保存位置", color = p.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { exportConfirm = null }) {
                            Text(text = "取消", color = p.ink2)
                        }
                    },
                    containerColor = p.card,
                )
            }

            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = {
                        Text(text = "清空所有数据？", color = p.ink)
                    },
                    text = {
                        Text(
                            text = "将删除本机数据库内的全部隐私事件，操作不可恢复。",
                            color = p.muted,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearConfirm = false
                                clearAllData()
                            },
                        ) {
                            Text(text = "清空", color = p.down)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text(text = "取消", color = p.ink2)
                        }
                    },
                    containerColor = p.card,
                )
            }

            updateAvailable?.let { info ->
                AlertDialog(
                    onDismissRequest = { updateAvailable = null },
                    title = { Text(text = "发现新版本 ${info.latestVersion}", color = p.ink) },
                    text = {
                        Column {
                            Text(text = "当前版本 ${BuildConfig.VERSION_NAME}。", color = p.muted)
                            info.notes?.let { notes ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = notes,
                                    color = p.muted,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "将用浏览器打开 GitHub Releases 下载新版本；安装前请确认来源。",
                                color = p.muted,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val url = info.downloadUrl ?: info.releaseUrl
                                updateAvailable = null
                                openDownload(url)
                            },
                        ) {
                            Text(text = "去下载", color = p.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { updateAvailable = null }) {
                            Text(text = "稍后", color = p.ink2)
                        }
                    },
                    containerColor = p.card,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    dot: androidx.compose.ui.graphics.Color?,
    valueColor: androidx.compose.ui.graphics.Color = LocalProbePalette.current.ink,
) {
    val p = LocalProbePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ProbeText.Label,
            color = p.muted,
            modifier = Modifier.width(72.dp),
        )
        if (dot != null) {
            LiveDot(color = dot, animate = false)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = value,
            style = ProbeText.NumberSmall,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ 自检 / 导出实现

/** 判断自检所需权限是否可用；系统只授予「大致位置」时按定位可用处理。 */
private fun selfTestPermissionGranted(context: Context, permission: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    return permission == Manifest.permission.ACCESS_FINE_LOCATION &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}

private fun suggestedExportName(kind: ExportKind): String =
    "隐私探针-${dev.popsicle.privprobe.ui.util.Format.fileStamp()}.${kind.ext}"

/**
 * 导出到用户通过 SAF 选定的位置：
 * 1) 先写应用缓存临时文件（数据层只接受 File）；
 * 2) 拷贝到用户选择的 content Uri；
 * 3) 无论成功失败都删除临时文件；失败时由调用方删除半成品文档。
 */
private suspend fun exportToUri(
    context: Context,
    repository: PrivacyRepository,
    kind: ExportKind,
    uri: Uri,
): Int = withContext(Dispatchers.IO) {
    val temp = File(context.cacheDir, "export-${System.currentTimeMillis()}.${kind.ext}")
    try {
        val count = if (kind == ExportKind.CSV) {
            repository.exportCsv(temp)
        } else {
            repository.exportJsonl(temp)
        }
        if (!temp.exists()) error("导出文件未生成")
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法写入所选位置")
        output.use { out ->
            temp.inputStream().use { input -> input.copyTo(out) }
        }
        count
    } finally {
        runCatching { temp.delete() }
    }
}
