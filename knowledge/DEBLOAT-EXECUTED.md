# 已执行清理清单（2026-11-06，2026-11-08 补录）

> 设备：Xiaomi 17 Pro Max · HyperOS 4.0.5.0.XPBCNXM · Android 17 · 单用户 0 · 执行时临时 KernelSU root
> 本清单记录本轮**实际执行**的清理动作，与 `docs/DEBLOAT.md` 的完整分级清单（155 条建议）不同：这里只列真正动过的。
> 一键复现/回滚：`scripts/privacy-clean.sh`

## 总览

| 类型 | 数量 | 说明 |
|---|---|---|
| 已卸载包 | 25 | `pm uninstall -k --user 0`（保留数据，可恢复；OTA 客户端见第 7 节） |
| 已锁定包 | 2 | `uninstall-lock` = 卸载 + `disable-user` + 清残留数据（`com.mobiletools.systemhelper` 见第 6 节；`com.miui.greenguard` 见第 8 节） |
| 已冻结包 | 3 | `pm disable-user --user 0`（随时可 `pm enable`） |
| 已禁用组件 | 25 | 组件级 `pm disable`（反诈 13 + 锁屏画报 2 + 安装器安全校验 7 + 企业模式 3） |
| 附加设置 | 3 | `lock_screen_magazine_status=0`（锁屏画报关闭）、`miui_updater_enable=0`、`ota_disable_automatic_update=1`（系统更新关闭） |
| 入口开关 | 2 | Settings 的 `ex_func` SharedPreferences：企业模式、安全守护（见第 8 节） |

## 1. 已卸载 / 已锁定（26 个）

### 1.1 隐私/广告/统计/推荐（Tier 0，20 个）

| 包名 | 名称 | 批次 | 为什么卸 | 卸载后影响 |
|---|---|---|---|---|
| `com.miui.analytics` | 小米统计 | Tier 0 | 纯统计/分析上报组件，无桌面入口、无用户功能 | 第三方应用统计归因失效；日常无感 |
| `com.miui.systemAdSolution` | 系统广告服务(MSA) | Tier 0 | 系统开屏广告/信息流广告 SDK（MSA） | 系统应用内广告位消失（这正是目的） |
| `com.miui.bugreport` | 用户反馈 | Tier 0 | 内置日志收集/bugreport provider，收集系统日志与截屏上传 | 无法在系统内提交反馈；本地崩溃日志仍保留 |
| `com.miui.misightservice` | 系统质量监测 | Tier 0 | MiSight 质量/体验数据上报 | 无感 |
| `com.miui.uireporter` | UI信息工具 | Tier 0 | UI 状态/交互埋点上报通道，无用户功能 | 无感 |
| `com.miui.contentcatcher` | 传送门(内容捕获) | Tier 0 | 读取屏幕文本/内容的"传送门"捕获组件 | 传送门（识屏）功能失效 |
| `com.miui.contentextension` | 传送门 | Tier 0 | 传送门扩展组件 | 传送门（识屏）功能失效 |
| `com.miui.personalassistant` | 智能助理(负一屏) | Tier 0 | 内容推荐/资讯聚合，含 advert.track 广告追踪 provider | 负一屏、桌面卡片消失 |
| `com.miui.thirdappassistant` | 智能应用助手 | Tier 0 | 三方应用推荐/安装引导 | 无感 |
| `com.miui.phrase` | 剪贴板与常用语 | Tier 0 | 剪贴板/常用语记录属高敏隐私面（会留存复制内容），kb-miui 复核无系统级依赖 | 常用语/剪贴板历史功能失效 |
| `com.miui.weather2` | 天气 | Tier 0 | 天气应用含广告/资讯位 | 无天气 app（可用第三方） |
| `com.miui.yellowpage` | 生活黄页 | Tier 0 | 号码黄页/生活服务，联网拉取 | 拨号里的黄页入口消失 |
| `com.miui.touchassistant` | 悬浮球 | Tier 0 | 悬浮球辅助 | 无（若你在用则保留） |
| `com.miui.accessibility` | 小米闻声 | Tier 0 | 声音转文字/无障碍辅助 | 无障碍听写功能不可用 |
| `com.android.browser` | 浏览器 | Tier 0 | 信息流/广告内置，含小米日志上传 SDK | 无（你已装 Via 浏览器） |
| `com.google.android.gms.location.history` | Google位置记录 | Tier 0 | Google 位置历史 | Google 位置历史功能不可用 |
| `com.xiaomi.ab` | 小米AB实验 | Tier 0 | 灰度/AB 实验分组上报（携带设备标识） | 失去灰度实验资格，无功能影响 |
| `com.xiaomi.aireco` | 推荐引擎 | Tier 0 | 内容/应用推荐引擎 | 负一屏等推荐内容消失 |
| `com.xiaomi.barrage` | 弹幕通知 | Tier 0 | 通知弹幕特效 | 无 |
| `com.xiaomi.macro` | 自动连招 | Tier 0 | 游戏自动连招 | 无 |

### 1.2 反诈 / 快应用 / 运营商遥测（4 个）

| 包名 | 名称 | 批次 | 为什么卸 | 卸载后影响 |
|---|---|---|---|---|
| `com.miui.guardprovider` | 安全防护 provider | 反诈专项 | 系统签名特权组件，持有 CAPTURE_VIDEO/AUDIO/通话 权限；删冻影响 AI 通话预警与安全联动 | 安全中心部分扫描/AI预警能力缺失 |
| `com.miui.hybrid` | 快应用引擎 | 快应用专项 | 快应用运行时，含广告/推荐与小米日志上传 SDK | 快应用、部分服务卡片不可用 |
| `com.xiaomi.minigame` | 小游戏 | 快应用专项 | 小游戏中心，含日志 SDK | 无 |
| `com.mobiletools.systemhelper` | 联通SystemHelper | 反诈专项 / **锁定** | 联通设备管理(DM)：应用列表/使用时长/流量+IMEI/IMSI/ICCID 明文上报（近期争议核心） | 双卡相关功能可能受影响；单卡基本无感。2026-11-08 升级为 `uninstall-lock`（卸载 + 禁用 + 清数据） |
| `com.android.updater` | 系统更新（OTA） | 系统更新 | HyperOS OTA 客户端；`pm disable-user` 被拒，只能对 user 0 卸载（详见第 7 节） | 收不到系统/安全更新（正是目的） |

### 1.3 平台厂商组件（1 个，2026-11-08 补录）

| 包名 | 名称 | 批次 | 为什么卸 | 卸载后影响 |
|---|---|---|---|---|
| `com.qualcomm.qti.services.systemhelper` | 高通 System Helper Service | 平台厂商 | `/system_ext/priv-app` 特权服务（共享 `android.uid.system`，清单 `enabled=false`，从不运行）；与联通 SystemHelper 名字相近，易被误认 | 无（默认不运行；`pm install-existing` 可恢复） |

## 2. 已冻结（3 个）

| 包名 | 名称 | 为什么冻结 | 影响 |
|---|---|---|---|
| `com.miui.securityadd` | 安全附加组件 | 含 child.home.analytics、gamebooster 等 provider，安全中心推荐/统计附加件 | 游戏加速、儿童空间部分统计功能 |
| `com.xiaomi.metoknlp` | 网络位置服务 | 上传 Wi-Fi/基站信息做网络定位（小米侧） | 无 GPS 时定位变慢/精度下降 |
| `com.bsp.catchlog` | BSP日志抓取 | 诊断日志抓取（售后用），APK 位于 /data，可冻结 | 售后诊断日志不再抓取 |

## 3. 已禁用组件（25 个：24 个稳定 + 1 个会被系统自愈）

| 包名 | 组件 | 功能 |
|---|---|---|
| `com.miui.securitycenter` | `com.miui.electricrisk.AiGuardSceneService` | 反诈：AI 通话预警场景服务 |
| `com.miui.securitycenter` | `com.miui.electricrisk.ElectricRiskMainActivity` | 电诈防护主界面 |
| `com.miui.securitycenter` | `com.miui.electricrisk.ElectricProtectPhoneActivity` | 电话防护 |
| `com.miui.securitycenter` | `com.miui.electricrisk.AiGuardPosterActivity` | AI 预警弹窗 |
| `com.miui.securitycenter` | `com.miui.electricrisk.ElectricProtectMmsActivity` | 短信防护 |
| `com.miui.securitycenter` | `com.miui.electricrisk.ElectricProtectAppActivity` | 应用防护 |
| `com.miui.securitycenter` | `com.miui.electricrisk.ElectricProtectScreenActivity` | 屏幕共享防护 |
| `com.miui.securitycenter` | `com.miui.antifraud.PayActivityMonitorService` | 支付场景监控 |
| `com.miui.securitycenter` | `com.miui.antifraud.AntiFraudReceiver` | 反诈广播接收器 |
| `com.miui.securitycenter` | `com.xiaomi.security.xsof.XSOFProvider` | XSOF 行为数据库 Provider |
| `com.miui.securitycenter` | `com.xiaomi.security.xsof.MiSafetyDetectService` | XSOF 安全检测服务 |
| `com.miui.securitycenter` | `com.xiaomi.security.xsof.frauddetection.service.MiFraudDetectionService` | XSOF 反诈检测服务 |
| `com.miui.securitycenter` | `com.xiaomi.security.xsof.frauddetection.provider.FraudDetectionProvider` | 反诈数据 Provider |
| `com.miui.miwallpaper` | `com.miui.miwallpaper.wallpaperservice.MiuiKeyguardPictorialWallpaper` | 锁屏画报壁纸服务 |
| `com.miui.miwallpaper` | `com.miui.miwallpaper.fashiongallery.WallpaperProvider` | 锁屏画报内容 Provider |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.SecurityModeVerifyActivity` | 安装器：安全模式验证页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.SecureModeActivity` | 安装器：安全模式拦截/引导页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.SecureModeSettingsActivity` | 安装器：安全模式设置页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.SecurityModeFeedBackActivity` | 安装器：安全模式反馈页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.RiskAppAuthActivity` | 安装器：风险应用授权/验证页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.secure.SecurityAuthorizeActivity` | 安装器：安全授权页 |
| `com.miui.packageinstaller` | `com.miui.packageInstaller.ui.securemode.SecureModeAdvantageActivity` | 安装器：安全模式优势介绍页 |
| `com.miui.securitycore` | `com.miui.enterprise.receiver.EntActivationReceiver` | 企业模式：激活广播接收器（2026-11-08 补录） |
| `com.miui.securitycore` | `com.miui.enterprise.settings.EntActivationSettingsActivity` | 企业模式：激活设置页（2026-11-08 补录） |
| `com.miui.securitycore` | `com.miui.enterprise.settings.AppRuuningBlockHandleActivity` | 企业模式：应用运行拦截页（2026-11-08 补录） |

> 实测：`com.miui.securitycenter/com.miui.electricrisk.AiGuardPosterActivity` 被禁用后，手机管家（uid 1000）会在 **~4 秒内自动重新启用**（logcat：`set app component=...AiGuardPosterActivity enabled state: 1 from com.miui.securitycenter`），属系统自愈。其余 14 个组件保持禁用。脚本会在执行后 6 秒校验并提示这一项。

> **2026-11-06 补录（安装器安全校验，7 个）**：从文件管理器/浏览器点 APK 安装时，安装器会把流程导入 `InstallStart → com.android.packageinstaller.SettingsActivity → SecurityModeVerifyActivity → SecureModeActivity → 浏览器/小米商店`，并在 `app_security_risk_app.db/install_history` 记为 `canceled`，导致未知来源应用装不上（实测对象：FlClash 0.9.3）。禁用上述 7 个组件后复测：流程直接走 `InstallStart → InstallPrepareAlertActivity → NewInstallerPrepareActivity`，7 个组件在 logcat 中出现 0 次，无 `ActivityNotFoundException` / 崩溃；`adb install` 与 root `pm install` 本来就不受影响。`com.miui.packageinstaller` 是 `/data/app` 数据应用，OTA / 商店更新后组件状态可能重置，重跑脚本即可。

## 4. 复现 / 回滚脚本

```bash
./scripts/privacy-clean.sh list                 # 查看清单（离线可用）
./scripts/privacy-clean.sh plan                 # 执行计划（默认命令，dry-run）
./scripts/privacy-clean.sh status               # 每项在当前设备的状态
./scripts/privacy-clean.sh apply                # 实际执行（需 root）
./scripts/privacy-clean.sh verify               # 仅校验（退出码 2 = 未通过）
./scripts/privacy-clean.sh restore <回滚文件>    # 回滚

清单数据集中在 `scripts/lib/privacy-clean.manifest.sh`（分组 / 包 / 组件 / 设置 + 说明），
引擎在 `scripts/lib/privacy-clean.common.sh` 与 `scripts/privacy-clean.sh`；
支持 `--only <分组>` / `--skip <分组>` 过滤。本地冒烟测试（mock adb，不接触真机）：
`./tests/privacy-clean/smoke.sh`。
```

前置条件：HyperOS 4 / Android 17 普通 shell 无法卸载/禁用系统应用，需先取得本仓库的临时 root（`./scripts/run.sh`）。脚本默认 dry-run、自动备份、幂等，可重复运行。

## 5. 回滚记录

| 文件 | 内容 |
|---|---|
| `scan/debloat-backups/20261106-002845.txt` | Tier 0：19 卸载 + 4 冻结 |
| `scan/debloat-backups/20261106-003358-antifraud-quickapp.txt` | 反诈/快应用：4 卸载 + 13 组件 |
| `scan/debloat-backups/20261106-0050-lockscreen-magazine.txt` | 锁屏画报：2 组件 |
| `scan/privacy-clean-backups/20261106-232600-installer-secmode.txt` | 安装器安全校验：7 组件禁用（手动执行补录） |
| 无（手工执行） | 2026-11-08 SystemHelper 复核：1 个 uninstall-lock + 1 个 uninstall（回滚命令见第 6 节） |
| 无（手工执行） | 2026-11-08 禁用系统更新：1 个 uninstall + 2 个 setting（回滚命令见第 7 节；也可 `apply --only ota` 复跑） |
| 无（手工执行） | 2026-11-08 扩展功能：1 个 uninstall-lock + 3 个 comp + 2 个 pref（回滚命令见第 8 节；也可 `apply --only extend` 复跑） |

回滚：`./scripts/privacy-clean.sh --restore <回滚文件>`（需 root）。

## 6. 2026-11-08 补录：SystemHelper 复核 + 锁定

> 起因：清理后仍能在手机里“看到 SystemHelper”。真机复核发现是**两个名字相近、互不相同的包**；
> 完整证据见 [`evidence/2026-11-08-systemhelper-why-visible.md`](../evidence/2026-11-08-systemhelper-why-visible.md)。

| 包 | 显示名 | 复核结论 | 本轮动作 |
|---|---|---|---|
| `com.mobiletools.systemhelper` | **SystemHelper**（联通 DM） | 对 user 0 **早已是「已卸载」**（`installed=false`、`pm path` 为空、无进程）；APK 仍在 `/system/priv-app`（只读系统分区，删不掉），所以 `pm list packages -u` 仍能列出 | 升级为 **`uninstall-lock`**：清残留数据（412K + 11K）→ 追加 `pm disable-user --user 0`（`enabled=3`，即使 OTA / `pm install-existing` 恢复也保持禁用） |
| `com.qualcomm.qti.services.systemhelper` | **System Helper Service**（高通） | 名字相近、**与联通遥测无关**；`/system_ext/priv-app` 特权服务，共享 `android.uid.system`，清单 `enabled=false`，从不运行 | `pm uninstall -k --user 0`（Success；`pm install-existing` 可恢复） |

实测核查：

| 检查 | 结果 |
|---|---|
| `pm list packages --user 0 \| grep -i systemhelper` | 空（两个包都不再对 user 0 安装） |
| `dumpsys package com.mobiletools.systemhelper` → User 0 | `installed=false … enabled=3`（disabled-user） |
| `dumpsys package com.qualcomm.qti.services.systemhelper` → User 0 | `installed=false` |
| `ps -A \| grep -i systemhelper` | 无进程 |
| `设置 → 应用管理`（普通列表 / 更多 → 显示系统应用） | 两个包都不出现（该列表只显示“已安装且启用”的包） |
| `设置` 顶部搜索 | 搜不到两个包 |

回滚：

```sh
# 高通 System Helper Service
adb shell su -c 'pm install-existing --user 0 com.qualcomm.qti.services.systemhelper'

# 联通 SystemHelper（恢复安装 + 解除禁用）
adb shell su -c 'pm install-existing --user 0 com.mobiletools.systemhelper'
adb shell su -c 'pm enable --user 0 com.mobiletools.systemhelper'
```

> `uninstall-lock` 清掉的数据（应用列表/使用时长缓存等）**不可恢复**；两个 APK 仍在只读系统分区，
> 无法真正删除 —— 想让 `pm list packages -u` 也看不到，只能改系统分区或用 KernelSU 模块 + overlayfs 遮盖。

## 7. 2026-11-08 补录：禁用系统更新（OTA，保持当前系统）

> 目的：把系统固定在 `OS4.0.5.0.XPBCNXM` / `CP2A.260605.016`，避免 OTA 修复 CVE-2026-98260 导致本方案失效。
> 复核结论：**`pm disable-user` 被 HyperOS 4 拒绝**（`SecurityException: Cannot disable miui core packages`），
> 但 **`pm uninstall --user 0` 可用**（Success）；`/product/app/Updater` 无任何 init/service 依赖
> （只有 shareduid allowlist 引用），卸载不影响开机。

| 类别 | 操作 | 结果 |
|---|---|---|
| 包 | `pm uninstall -k --user 0 com.android.updater`（系统更新 9.7.3，`/product/app/Updater`，独立 UID `android.uid.updater`） | ✅ Success；对 user 0 不再安装，`DailyCheckJobService`/receiver/推送全部失效 |
| 设置 | `settings put secure miui_updater_enable 0` | ✅ 1 → 0（updater 读取的更新开关） |
| 设置 | `settings put global ota_disable_automatic_update 1` | ✅ 出厂即 1，显式再写一次（AOSP 自动 OTA 开关） |

核查：

| 检查 | 结果 |
|---|---|
| `pm list packages --user 0 \| grep com.android.updater` | 空 |
| updater 进程 / `DailyCheckJobService` | 无进程；job `Ready: false`（组件不可用） |
| `miui.intent.action.CHECK_UPDATE` | 无法解析（MIUI 系统更新入口失效，无崩溃） |
| 已暂存 OTA | `/data/ota_package`、`/data/ota` 为空，`update_engine` 未运行 |
| 系统构建 | `ro.build.display.id` 仍为 `CP2A.260605.016` |

> 注意：`android.settings.SYSTEM_UPDATE_SETTINGS` 现在打开的是 **Google Play 系统更新**（GMS 的
> `SystemUpdateActivity`），它只更新 Play 模块 / 部分 APEX，不改 ROM 构建号与内核。
> 若某次恢复（`pm install-existing` / 应用商店更新系统应用）把 updater 装回来，`apply --only ota` 重跑即可。

回滚：

```sh
adb shell su -c 'pm install-existing --user 0 com.android.updater'
adb shell su -c 'settings put secure miui_updater_enable 1'
adb shell su -c 'settings put global ota_disable_automatic_update 0'
# 或：./scripts/privacy-clean.sh restore <备份文件>
```

## 8. 2026-11-08 补录：扩展功能 / 无用功能（未成年模式、企业模式、安全守护）

> 用户要求清掉「没用的功能」。三者形态不同：未成年模式是独立预装应用；企业模式是
> `com.miui.securitycore` 内的模块；安全守护是安装器的「安全安装模式 / Mi Protect」+ 设置入口。

| 类别 | 对象 | 操作 | 结果 |
|---|---|---|---|
| 应用 | `com.miui.greenguard`（`/product/app/MIUIgreenguard`，v3.14.09.21.00） | `uninstall-lock`：`pm uninstall -k --user 0` + 清数据（390K + 29K） | ✅ 桌面「未成年人模式」入口消失；`installed=false`，无进程，无 LAUNCHER Activity |
| 组件 | `com.miui.securitycore/com.miui.enterprise.receiver.EntActivationReceiver` | `pm disable --user 0` | ✅ disabled（企业模式激活广播） |
| 组件 | `com.miui.securitycore/com.miui.enterprise.settings.EntActivationSettingsActivity` | 同上 | ✅ disabled（企业模式设置页） |
| 组件 | `com.miui.securitycore/com.miui.enterprise.settings.AppRuuningBlockHandleActivity` | 同上 | ✅ disabled（应用运行拦截页） |
| 入口 | `com.android.settings` → `ex_func.xml` → `ex_func_enterprise_mode=false` | 改写 SharedPreferences（force-stop Settings 后写入，保持 `system:system 660`） | ✅ 企业模式入口隐藏 |
| 入口 | 同上 → `ex_func_safe_install_mode=false` | 同上 | ✅ 安全守护（Mi Protect）入口隐藏；其执行组件已在第 3 节 installer 组禁用 |

核查：

| 检查 | 结果 |
|---|---|
| `pm path com.miui.greenguard` | 空；`pm list packages --user 0` 无；无进程 |
| `dumpsys package com.miui.securitycore` → disabledComponents | 3 个 enterprise 组件全 disabled |
| `ex_func.xml` | 两个键为 `false`，其余键（`ex_func_need_update`、`ex_func_mi_coin`）保持原值 |
| 设置应用 | force-stop 后重新启动正常，两个入口开关读取为关闭 |

> 「应用扩展功能」页的开关不是 settings provider 键，而是 `/data/user_de/0/com.android.settings/shared_prefs/ex_func.xml`，
> 所以脚本为此新增了 `PC_PREFS` 布尔项支持（`pref` 动作：读取 → force-stop 持有者 → 改写 → 保持属主/权限）。
> 回滚：`restore <备份文件>` 会把两个键写回 `true`，并 `pm enable` 企业模式组件、`pm install-existing` 恢复 greenguard。

## 9. 风险说明

1. **必须 root**：HyperOS 4 / Android 17 不允许普通 shell 卸载/禁用系统应用（`only root can delete system app` / `Cannot disable system packages`），本仓库的 KernelSU 临时 root 可用。
2. **不是所有包都能卸**：`com.miui.securitycenter`（手机管家）本体保留（定义 58 项 MIUI 权限，卸了权限弹窗/应用信息会崩），反诈能力用**组件级禁用**处理；`com.miui.miwallpaper`（壁纸）同样保留，只禁用画报组件。
3. **系统更新可能恢复**：OTA / 手机管家升级后，被卸载的包与禁用组件可能回来，重跑脚本即可。
4. **双卡用户**：`com.mobiletools.systemhelper` 与双卡相关（UAD 标注），本机卡 2 为空；双卡用户如遇异常用回滚恢复。
5. **rom 差异**：包名因机型/区域/系统版本不同，脚本执行前会核对包是否存在，不会误卸。
6. **安装器安全校验组件**：禁用 `RiskAppAuthActivity` / `SecurityAuthorizeActivity` 后，安装被系统标记为“风险”的应用不再出现二次授权/验证页（直接放行或由系统报错）；该组禁用只影响 GUI 点击 APK 的安装流程，`adb install` 与 root `pm install` 不受影响。安装器是 `/data/app` 数据应用，OTA / 商店更新后可能重置，重跑脚本即可。

