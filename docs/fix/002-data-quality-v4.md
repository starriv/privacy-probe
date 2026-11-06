# Fix: 采集数据口径修复（相机自噪 / 共享 uid 聚合 / 自身事件）

> **日期**: 2026-11-06
> **背景**: 基于 2026-11-06 的 14.7 小时真机采集（19,350 条事件）做隐私 / 反诈分析时发现的三类数据问题，以及域名情报缺口。

## 问题描述

1. **相机数据是探针自噪**：`/dev/video0`、`/dev/video1` 共 1,556 条 `device_open`（约 120 条/小时），全部归因到 `cameraserver`，但实验证明是相机服务探针自己的 `dumpsys media.camera` 轮询触发的，不是用户相机使用。
2. **共享 uid 聚合错误**：`agg_cat_uid` 以 uid 为主键，uid 1000 上的「电量和性能 633 条 + 动态系统更新 92 条 + 弹幕通知 23 条」被合并成一行（712），标签显示为最后写入的「弹幕通知」。
3. **自身 / 旧包名事件污染**：`dev.popsicle.privprobe` 自身 115 条（定位、前台、更新检查等）和 `dev.popsicle.privradar` 旧包名 3 条随普通事件入库，出现在调用者与时间线里。
4. **域名情报覆盖不足**：本次采集的 370 个目标中，6,572 条网络事件的目标不在 `domain_intel.json`（仅 160 个域名）。

## 根因分析

- **相机**：kprobe 的 `v4l2_open` 打开者是 `cameraserver` / 厂商相机 provider（uid 1047），Kotlin 侧只对 cameraserver 做了 60 秒/路径限流，没有区分「App 直接打开设备」和「系统媒体服务打开设备」。而 `dumpsys media.camera` 轮询会触发 cameraserver 打开 `/dev/video*`，所以固定呈现每分钟一对的假象。
  A/B 实验（`内核打开事件` 保持开启，只切换 `相机服务`）：

  | 时间窗口 | 相机服务探针 | 相机 device_open |
  |---|---|---:|
  | 17:47:45–17:51:45（4 分钟） | 关闭 | **0** |
  | 17:52:25–17:55:10（2.8 分钟） | 开启 | 6（约 60 秒一对） |

  关闭窗口内 kprobe 仍正常记录其他事件（5 条 process_exec），排除 kprobe 失效。
- **聚合**：`agg_cat_uid` 主键 `(day, category, is_system, uid)`，`package/label` 只是覆盖列；`agg_uid_target` 同样按 uid 聚合。共享 uid 的系统应用因此被合并，且标签取决于最后一条事件。
- **自身事件**：写入链路没有按包名排除本 App；自检事件也走 `repository.insertEvents`。
- **域名情报**：`knowledge/domains-*.txt` 只有 160 个域名，未随真实采集补充。

## 修复方案

### 1. 相机 / 麦克风设备打开只记应用进程

`NativeStreamProbe` 增加 `NativeOpenFilter`：`/dev/video*`、`/dev/snd` 打开事件只记录 uid ≥ 10000 的应用进程；cameraserver/audioserver（uid<10000）与未归因的打开直接丢弃。真实相机使用改由 dumpsys 活跃客户端 + CameraService 事件日志 + AppOps 相机审计提供（这些事件可归因到具体 App）。

### 2. 调用者 / 目标按 (uid, package) 拆分

- `agg_cat_uid` 主键改为 `(day, category, is_system, uid, package)`，`package` 非空（`''` 表示该 uid 下无法归因包名）；`agg_uid_target` 同样加入 `package`。
- Dashboard / 大类页 / 目标详情的调用者列表、应用数、调用者数都按 `(uid, package)` 统计。
- 调用者详情页改为 `observeCaller(category, uid, packageName)`：导航栈 `Screen.Caller` 携带包名，页面只显示该包名的事件、目标与计数；未知包名的调用者单独成行。
- 事件查询对包名使用 `package = ?`（未知包名用 `package IS NULL`），保证详情与列表中该行计数一致。

### 3. 自身 / 旧包名事件不写入历史

`insertEvents` 在解析包名后跳过 `context.packageName` 与 `dev.popsicle.privradar`；历史数据由 v4 迁移清理。自检结果仍由 `SelfTestRunner` 的返回值与 UI 展示，不依赖事件历史。

### 4. DB v3 → v4 迁移

`SQLiteOpenHelper` 版本升到 4，迁移在升级事务中完成：

1. 摘掉聚合触发器；
2. 删除历史相机/麦克风 kprobe `device_open` 自噪（`is_system=1`）；
3. 删除自身 / 旧包名事件；
4. 重建聚合表（新主键）并从 `events` 全量回填；
5. 装回新触发器、补齐索引。

事件数据除上述三类明确清理外保持不变；聚合表是派生数据，整表重建。

### 5. 域名情报扩充

从本次 370 个观测目标中取 359 个合法域名，补充到 `knowledge/domains-*.txt` 后重新生成数据集：

| 分类 | 之前 | 之后 |
|---|---:|---:|
| tracker | 45 | **75**（+30：字节/腾讯/京东/美团 dreport/Firebase/神策/广告 SDK 等） |
| functional | 8 | **100**（+92：连通性检测、NTP、GMS/Play、小米账号云服务、支付登录、推送、CDN 等） |
| unknown | 107 | **327** |
| 合计 | 160 | **502** |

`device_profile.json` 的 `audit.trackerDomains` 同步 45 → 75；生成脚本 `scripts/gen-datasets.py` 可重复执行且输出确定。

## 修改文件

| 文件 | 变更 |
|---|---|
| `core/collector/net/NativeStreamProbe.kt` | 新增 `NativeOpenFilter`；相机/麦克风设备打开只记录应用进程 |
| `core/data/PrivacyRepository.kt` | `observeCaller` 增加 `packageName` 参数 |
| `core/data/impl/SqlitePrivacyRepository.kt` | v4 迁移、按 (uid, package) 聚合与查询、自身事件过滤 |
| `ui/nav/Screen.kt`、`ui/ProbeApp.kt` | `Screen.Caller` 携带包名并贯穿导航 |
| `ui/screens/DashboardScreen.kt`、`CategoryScreen.kt`、`CallerScreen.kt`、`TargetScreen.kt` | 调用者点击传递包名；CallerScreen 按包名查询 |
| `ui/preview/PreviewSources.kt` | 跟随接口变更 |
| `app/src/test/.../SqlitePrivacyRepositoryTest.kt` | 新增共享 uid 拆分、自身事件过滤、v3→v4 迁移测试 |
| `app/src/test/.../NativeOpenFilterTest.kt` | 新增设备打开归属策略测试 |
| `knowledge/domains-*.txt`、`assets/datasets/domain_intel.json`、`device_profile.json` | 域名情报扩充 |
| `app/build.gradle.kts`、`CHANGELOG.md` | 版本 1.2.1 → 1.2.3（与列表状态修复一起发布） |

## 验证

### 自动化

- `:app:testDebugUnitTest`：**38 项全部通过**（新增 5 项：共享 uid 拆分、自身/旧包名过滤、v3→v4 迁移、设备打开归属策略 2 项）。
- `:app:lintDebug`：0 errors。
- `:app:assembleDebug` / `:app:assembleRelease`：通过（versionName 1.2.3 / versionCode 10203）。

### 真机（Xiaomi 17 Pro Max / Android 17）

升级迁移（保留用户数据）：

| 指标 | 迁移前（v3） | 迁移后（v4） |
|---|---:|---:|
| 事件总数 | 20,370 | 18,672（1,616 相机自噪 + 121 自身/旧包名被清除，期间新采集 39 条正常事件） |
| 相机 device_open 自噪 | 1,616 | **0** |
| 自身 / 旧包名事件 | 121 | **0** |
| `user_version` | 3 | **4** |
| agg_cat 与 events 分类计数 | — | 全部一致 |

聚合与调用者页面：

- 网络类 uid 1000 的调用者拆分为 电量和性能 633 / 动态系统更新 92 / 弹幕通知 23，与 events 原始计数逐一相等；
- 调用者页面「电量和性能」（com.miui.powerkeeper · uid 1000）只显示该包名的事件与目标，标题、计数正确；
- 相机类在迁移后只保留真实使用记录；重新打开系统相机后，dumpsys 捕获 `camera_active` / `camera_closed`（camera 5）并归因到 `com.android.camera`（小米相机），证明过滤后相机检测仍有效；
- 域名情报生效：`connect.rom.miui.com`、`www.baidu.com` 在目标详情显示「功能域名」标签。

### 已知遗留

- `dumpsys media.camera` 轮询仍会让 cameraserver 打开 `/dev/video*`（只是不再入库）；后续可评估更低副作用的相机探测方式。
- 网络事件仍有 32.6% 无法归因（netd/DNS 抓包侧），不在本次修复范围。
- 畸形域名（`connect.rom` 截断、非 UTF-8）只是从情报库排除，解析层仍未修正。

*创建于: 2026-11-06*
