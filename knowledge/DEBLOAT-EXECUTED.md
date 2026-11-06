# 已执行清理清单（2026-11-06）

> 设备：Xiaomi 17 Pro Max · HyperOS 4.0.5.0.XPBCNXM · Android 17 · 单用户 0 · 执行时临时 KernelSU root
> 本清单记录本轮**实际执行**的清理动作，与 `docs/DEBLOAT.md` 的完整分级清单（155 条建议）不同：这里只列真正动过的。
> 一键复现/回滚：`scripts/privacy-clean.sh`

## 总览

| 类型 | 数量 | 说明 |
|---|---|---|
| 已卸载包 | 24 | `pm uninstall -k --user 0`（保留数据，可恢复） |
| 已冻结包 | 3 | `pm disable-user --user 0`（随时可 `pm enable`） |
| 已禁用组件 | 15 | 组件级 `pm disable`（反诈 13 + 锁屏画报 2） |
| 附加设置 | 1 | `lock_screen_magazine_status=0`（锁屏画报关闭） |

## 1. 已卸载（24 个）

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
| `com.mobiletools.systemhelper` | 联通SystemHelper | 反诈专项 | 联通设备管理(DM)：应用列表/使用时长/流量+IMEI/IMSI/ICCID 明文上报（近期争议核心） | 双卡相关功能可能受影响；单卡基本无感 |

## 2. 已冻结（3 个）

| 包名 | 名称 | 为什么冻结 | 影响 |
|---|---|---|---|
| `com.miui.securityadd` | 安全附加组件 | 含 child.home.analytics、gamebooster 等 provider，安全中心推荐/统计附加件 | 游戏加速、儿童空间部分统计功能 |
| `com.xiaomi.metoknlp` | 网络位置服务 | 上传 Wi-Fi/基站信息做网络定位（小米侧） | 无 GPS 时定位变慢/精度下降 |
| `com.bsp.catchlog` | BSP日志抓取 | 诊断日志抓取（售后用），APK 位于 /data，可冻结 | 售后诊断日志不再抓取 |

## 3. 已禁用组件（15 个：14 个稳定 + 1 个会被系统自愈）

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

> 实测：`com.miui.securitycenter/com.miui.electricrisk.AiGuardPosterActivity` 被禁用后，手机管家（uid 1000）会在 **~4 秒内自动重新启用**（logcat：`set app component=...AiGuardPosterActivity enabled state: 1 from com.miui.securitycenter`），属系统自愈。其余 14 个组件保持禁用。脚本会在执行后 6 秒校验并提示这一项。

## 4. 复现 / 回滚脚本

```bash
./scripts/privacy-clean.sh --list                    # 查看本清单
./scripts/privacy-clean.sh                           # dry-run（不改手机）
./scripts/privacy-clean.sh --apply                   # 实际执行（需 root）
./scripts/privacy-clean.sh --restore <回滚文件>      # 回滚
```

前置条件：HyperOS 4 / Android 17 普通 shell 无法卸载/禁用系统应用，需先取得本仓库的临时 root（`./scripts/run.sh`）。脚本默认 dry-run、自动备份、幂等，可重复运行。

## 5. 回滚记录

| 文件 | 内容 |
|---|---|
| `scan/debloat-backups/20261106-002845.txt` | Tier 0：19 卸载 + 4 冻结 |
| `scan/debloat-backups/20261106-003358-antifraud-quickapp.txt` | 反诈/快应用：4 卸载 + 13 组件 |
| `scan/debloat-backups/20261106-0050-lockscreen-magazine.txt` | 锁屏画报：2 组件 |

回滚：`./scripts/privacy-clean.sh --restore <回滚文件>`（需 root）。

## 6. 风险说明

1. **必须 root**：HyperOS 4 / Android 17 不允许普通 shell 卸载/禁用系统应用（`only root can delete system app` / `Cannot disable system packages`），本仓库的 KernelSU 临时 root 可用。
2. **不是所有包都能卸**：`com.miui.securitycenter`（手机管家）本体保留（定义 58 项 MIUI 权限，卸了权限弹窗/应用信息会崩），反诈能力用**组件级禁用**处理；`com.miui.miwallpaper`（壁纸）同样保留，只禁用画报组件。
3. **系统更新可能恢复**：OTA / 手机管家升级后，被卸载的包与禁用组件可能回来，重跑脚本即可。
4. **双卡用户**：`com.mobiletools.systemhelper` 与双卡相关（UAD 标注），本机卡 2 为空；双卡用户如遇异常用回滚恢复。
5. **rom 差异**：包名因机型/区域/系统版本不同，脚本执行前会核对包是否存在，不会误卸。

