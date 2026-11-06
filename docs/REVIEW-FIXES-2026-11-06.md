# 工程化与可靠性复审修复（2026-11-06）

本轮针对后续复审的十项问题修复；历史性能数据仍见 `REVIEW-2026-11-06.md`，不作为本轮性能实测。

| 问题 | 修复 |
|---|---|
| 系统/普通应用聚合覆盖 | 类别、小时图、调用者和目标按展示维度 SUM/MAX；目标数量 DISTINCT |
| 落库失败静默成功 | 事务及提交异常向上传递；仅成功后刷新计数，由 flusher 重试并记录失败 |
| 服务停止竞态 | 进程级串行命令队列；探针状态变更受 Mutex 保护；清理完成后按 startId 停服务；主线程不等待 IO |
| Native 取消时跳过清理 | NonCancellable 收尾，关闭生产者后排空队列，finally 取消独立 scope |
| 网络切换漏采/假健康 | 抓包线程每 2 秒协调接口集合，保留已有 socket；独立 status 帧上报 PCAP/uprobe/kprobe 实际状态 |
| DNS 跨应用误去重 | Kotlin 以 UID+域名关联，未知 UID 不做跨来源抑制；Native 查询去重包含源/目标端点和 UID |
| 发布不重建 Native/签名回退 | CI 和 Release 固定 NDK 28 重编；tag 缺密钥失败；Gradle 严格签名选项和 APK 证书匹配检查 |
| 持续写入不刷新 | 写入版本改为 2 秒 sample，保留 30 秒兜底 ticker |
| 时间线受 Top 调用者限制 | 按类别读取全部保留期，120 条一页，以 ts/id 排序；后台停止轮询 |
| 容量清理不及时 | 每批检查有效页和行数，超行数留 10% 余量；循环删除到字节限制内；正确执行 incremental_vacuum |

通知数字改为单条今日聚合查询，不再构建整个 Dashboard。

## 容量语义

- 默认保留 7 天；保留期清理每 30 分钟检查一次。
- 每批成功写入后检查 30 万行及 150 MiB 有效数据库页；超过行数限制时清理到 27 万行，避免每批重建聚合。
- 删除与受影响日期聚合重建在同一事务完成。相同毫秒的事件用 id 决定删除顺序。
- 物理文件还可能包含空闲页、WAL 与读事务保留的页。回收页并尝试 checkpoint；活跃读事务可能暂时阻止 WAL 截断，不因此反复删除正常历史数据。
- `incremental_vacuum` 通过 rawQuery 完整读取结果执行；Android 上 execSQL 会拒绝此返回行的 PRAGMA。

## 验证

```sh
./scripts/build-native.sh
./scripts/build-app.sh :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
# 没有发布密钥时应失败，不能生成正式签名产物：
env -u PRIVPROBE_KEYSTORE_PATH ./scripts/build-app.sh help -PrequireReleaseSigning=true
```

新增 11 项测试，合计 21 项：真实 SQLite 混合聚合、失败事务回滚/重试、v2 迁移、相同时间戳分页、容量删除与回收、持续写入刷新、DNS 跨 UID 关联、独立健康状态、停止排空/重试、快速重启命令顺序。SQLite 测试使用 Robolectric Native SQLite 模式，API 34；不启动真实 root 进程。

真机隔离验证：临时 `privd --isolate` 实例在新增/删除无地址 dummy 接口时，绑定状态从 **7/7 → 8/8 → 7/7** 自动更新，无需重启。PCAP、uprobe、kprobe 独立就绪状态均收到。SIGTERM 后实例、临时接口已清理。未改变路由，未替换手机上已安装的 App。

本轮不声明耗电、PSS 或 UI 帧耗时改善的实测数值。完整 App 的设备升级、反复启停及长时间运行仍需后续验收。

## 后续性能验收口径

使用固定设备、同一签名的 release 构建，记录版本和开关配置；分别测试空闲前台、熄屏采集、高网络吞吐及接近容量限制的库。比较每批写入、维护和 Dashboard 查询耗时（p50/p95）、应用/privd CPU、PSS、数据库与 WAL 大小、丢弃数。UI 使用 `dumpsys gfxinfo dev.popsicle.privprobe framestats`，不要把宿主 SQLite 时间直接当作真机帧性能。

## 最终结果

- `testDebugUnitTest`：21 项全部通过，无跳过。
- `lintDebug`：0 errors / 14 warnings（SDK/依赖更新提示及 KTX、资源目录建议）。
- `assembleRelease`：通过；本地测试签名校验通过，APK 内 `privd` 与本轮重编资产逐字节一致。
- 正式签名保护：移除 keystore 环境变量后开启 `requireReleaseSigning=true`，构建按预期失败并报告 `Release signing keystore is required`。
- APK：`app/build/outputs/apk/release/app-release.apk`，2,083,830 bytes。
- SHA-256：`d445ae7e15e2d3ac183d0b881a1ce84fde5625404ba79b79f763b052be1b2c0e`。
- CI YAML 语法与 `git diff --check` 通过；未运行远程 GitHub 发布。
