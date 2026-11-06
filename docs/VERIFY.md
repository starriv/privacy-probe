# PrivacyProbe 真机验证记录

- 设备：Xiaomi 17 Pro Max（`popsicle` / `2509FPN0BC`），HyperOS 4（`OS4.0.5.0.XPBCNXM`），Android 17 / SDK 37
- 内核：`6.12.69-android16-6-g43b5ab7e247d-abogki558343811-4k`
- root：KernelSU 越狱模式（late-load LKM），`su` context `u:r:ksu:s0`，SELinux Enforcing，全 capabilities
- App：`dev.popsicle.privprobe` 1.0.0 debug APK（root `pm install`，2026-11-06 01:54 最终构建）
- APK 内原生产物：`assets/bin/arm64-v8a/privd` sha256 `11246560402530170da26b384ec86804d78396e93c746a888517e5a34674e252`（39608 B）
- 验证时间：2026-11-06 01:54–02:01

## 1. 环境与安装

```sh
adb devices
# aab3ac51	device

adb shell "su -c 'id'"
# uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0

cd privacy-probe && ./scripts/install.sh
# BUILD SUCCESSFUL
# Success
# installed & launched
```

MIUI 不允普通 adb install（`INSTALL_FAILED_USER_RESTRICTED`），因此脚本使用
`adb push` + `su -c 'pm install -r'`；首次启动还需在 MIUI 弹窗允许
「获取已安装的应用信息」（QUERY_ALL_PACKAGES，点「始终允许」）。

`./scripts/verify.sh` 结果：

```text
[OK] su -c id → uid=0
[OK] 已安装 dev.popsicle.privprobe
[OK] APK 版本
[OK] CollectorService 已注册/运行
结果：OK=3 FAIL=0
```

## 2. root 授权

- 首次启动 KernelSU 弹窗授予 PrivacyProbe root 后，`/data/adb/ksu/.allowlist` 中出现
  `dev.popsicle.privprobe`，后续启动不再弹窗。
- App 状态：`root 已授权 uid=0(root), context=u:r:ksu:s0`、`采集中`、`11/11 探针健康`。

## 3. 自检（配置页「运行自检」，最终一次 2026-11-06 02:00:53）

```text
self-test: permissions=OK(已通过 root 授权相机/录音/定位/剪贴板)；
dns=OK(解析成功：172.66.147.243（2 个地址）)；
camera=OK(摄像头 0 打开成功)；
microphone=OK(读取 24000 个采样点)；
location=OK(provider=gps)；
sensor=OK(加速度计 icm4x6xx Accelerometer Non-wakeup 注册成功，收到事件=true)；
clipboard=OK(剪贴板读写成功)
```

> 注：反复用 `am start` 开关相机后，MIUI CameraProvider 偶发掉设备
> （`dumpsys media.camera` 设备数变 0，provider 进程 D 状态）；`kill -9` provider
> 让 init 重启后恢复 9 个摄像头设备，自检 camera 步骤即通过。属系统相机服务
> 的稳定性问题，不是 App 缺陷。

## 4. 最终事件统计（2026-11-06 02:01，SQLite）

- 总事件 **886**，今日 **811**，超过 24h 的历史事件 **0**（AppOps 首轮只导入最近 24h 基线）
- 覆盖 **11 个大类**、**45 个应用调用者**、**21 个域名**
- 来源分布：APPOPS 269 / DUMPSYS 329 / KERNEL_TRACE 180 / UPROBE 25 / PACKET 11 /
  LOGCAT 28 / HEURISTIC 25 / SELF_TEST 19

| 大类 | 事件数 | 说明 |
|---|---:|---|
| 传感器 sensor | 285 | SensorService Recent Sensor Events，含 pid/uid/package |
| 位置 location | 178 | 监听器注册 + 定位投递 + AppOps |
| 前台/进程 app_activity | 175 | ActivityTaskManager + exec tracepoint |
| 设备标识 device_id | 76 | AppOps（USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER 等） |
| 网络 network | 64 | DNS / TLS SNI / getaddrinfo / 域名连接 |
| 电话短信 telephony | 35 | AppOps（READ_SMS/CALL_LOG/PHONE_STATE 等） |
| 剪贴板 clipboard | 25 | AppOps + logcat |
| 麦克风 microphone | 23 | audio_flinger / AudioRecord / AppOps / kprobe capture |
| 相机 camera | 14 | CameraService / AppOps / kprobe v4l2_open |
| 通讯录日历 contacts | 11 | AppOps（READ_CONTACTS/CALENDAR 等） |

网络 top 域名（节选）：`android.googleapis.com`(13)、`example.com`(6，自检)、
`play.googleapis.com`(5)、`phonedeviceverification-pa.googleapis.com`(4)、
`play-fe.googleapis.com`(4)、`beacons.gvt2.com`(3)、`clients.l.google.com`(3)、
`connect.rom.miui.com`(3)、`ime.doubao.com`(3)、`tracker.xiaomixiaoai.com`(3)、
`www.googleapis.com`(3)、`dig.bdurl.net`(2)。

## 5. 低层采集证据

### 5.1 privd（AF_PACKET + tracefs）

```sh
adb shell "su -c 'ps -A | grep privd'"
# root 4707 4440 ... privd-1793901515045-1     ← 唯一文件名（ETXTBSY 修复生效）

cat /sys/kernel/tracing/uprobe_events
# p:PRIVGAI/getaddrinfo /apex/com.android.runtime/lib64/bionic/libc.so:0x...abe00 node=+0(%x0):string
# p:PRIVGAI/android_getaddrinfofornet /apex/.../libc.so:0x...abe64 node=+0(%x0):string
cat /sys/kernel/tracing/kprobe_events
# p:PRIVOPEN/v4l2_open v4l2_open
# p:PRIVOPEN/snd_pcm_open snd_pcm_open stream=%x2
```

`privd --dry-run` 能力检测：`tracefs/instance/uprobe/kprobe/exec/pcap/uid_lookup` 全 true；
端到端抓包 53 行合法 NDJSON（dns_q/dns_a/sni/http/gai/open/exec/conn），无 warn/error。
`stream=%x2` 实测 `stream=0x1`（capture）→ 只映射 MICROPHONE，播放不误报；详见
[`NATIVE-VERIFY.md`](NATIVE-VERIFY.md)。

### 5.2 事件→调用者归因样例

```text
NETWORK  getaddrinfo  example.com                uid 10052  dev.popsicle.privprobe  UPROBE 95
NETWORK  getaddrinfo  ime-igw.doubao.com         uid 10377  com.bytedance.android.doubaoime  UPROBE 95
NETWORK  getaddrinfo  tracking.miui.com          uid 10150  com.android.camera       UPROBE 95
NETWORK  tls_sni      tracker.xiaomixiaoai.com   uid -1     （pcap 未归因）             PACKET 90
CAMERA   camera_active camera 0                  uid 10052  dev.popsicle.privprobe  DUMPSYS 90
CAMERA   device_open   /dev/video0               uid 1047   cameraserver            KERNEL_TRACE 60
MIC      device_open   /dev/snd/pcmC0D0c         uid 1041   audioserver             KERNEL_TRACE 60
MIC      audio_record_log mic:record             uid 10052  dev.popsicle.privprobe  LOGCAT 68
```

## 6. 数据质量校验（修复后）

- logcat 假事件（`handleAppForeground/Background`、`handleForegroundServicesChanged`）：
  **0 条**；
- location 只保留真实访问行（`location account change` / 监听器注册）；
- App 启动首轮 AppOps 只导入最近 24h，之后只发新访问；>24h 历史 **0 条**；
- `conn_domain` 限流 60s/`(uid,domain)`、`gai` 2s/`(uid,name)` 去重、`dns_q` 1s pending 去重；
- kprobe `open` 限流 10s / 系统服务 60s，playback 不映射麦克风；
- NETWORK 事件 package 名由 uid 反查补齐（无法归因的 netd/系统 pcap 事件显示「未知调用者」，属预期）。

## 7. 停止采集 / 清理

关闭配置页「采集总开关」后实测：

```text
CollectorService 数量：0
adb shell "su -c 'ps -A | grep -E \"privd|su\"'"
# 无 privd / 无残留 su
```

再次打开自动拉起前台服务与 privd，11/11 探针恢复健康。

> 注意：若 App 进程被系统直接杀死（非正常 stop），内核里可能残留
> `PRIVGAI/*`、`PRIVOPEN/*` 动态事件定义；privd 下次启动会先 `-:` 删除再重建，
> 不影响功能。

## 8. 截图索引

| 文件 | 说明 |
|---|---|
| [`screenshots/ui-dashboard-light.png`](screenshots/ui-dashboard-light.png) / [`-dark`](screenshots/ui-dashboard-dark.png) | Dashboard：KPI 四联 + 大类卡片 + 最近事件 |
| [`screenshots/ui-category-network-light.png`](screenshots/ui-category-network-light.png) / [`-dark`](screenshots/ui-category-network-dark.png) | 网络与 DNS 大类详情（调用者/目标/时间线） |
| [`screenshots/ui-caller-light.png`](screenshots/ui-caller-light.png) / [`-dark`](screenshots/ui-caller-dark.png) | 调用者详情 + RepoIntel 知识库 + 目标分布 |
| [`screenshots/ui-target-light.png`](screenshots/ui-target-light.png) / [`-dark`](screenshots/ui-target-dark.png) | 目标详情（域名/传感器/摄像头）：调用者 + 事件 |
| [`screenshots/ui-settings-light.png`](screenshots/ui-settings-light.png) / [`-dark`](screenshots/ui-settings-dark.png) | 配置页：root/采集/探针状态 + 开关 |
| [`screenshots/ui-settings-about-light.png`](screenshots/ui-settings-about-light.png) / [`-dark`](screenshots/ui-settings-about-dark.png) | 诊断 + 关于（设备画像） |

> 截图均为 2026-11-06 设计重构（v2）后的真机截图，浅色/深色各一套；设计说明见
> [`DESIGN.md`](DESIGN.md)。

## 9. 已知限制

- DNS 归属依赖 libc `getaddrinfo` uprobe 或「DNS 应答 IP → 随后连接」关联推断；
  走独立解析器（Go/Rust 内置 resolver）或 pcap 无法归因的查询会显示「未知调用者」。
- DoH/DoT 只能看到 TLS SNI；QUIC/HTTP3 域名不可见。
- 相机/麦克风的底层打开者可能是 cameraserver/audioserver，最终调用者以系统服务
  接口（Active Camera Clients / audio track）为准，kernel 事件作为底层证据。
- AppOps 全量 dump 约 2 MB，单独 15s 低频轮询。
- 本仓库绑定 Xiaomi 17 Pro Max 精确内核；换机型需重新适配 uprobe 偏移与 dumpsys 字段。

---

## v1.1.0 review 后复验（2026-11-06）

构建 / 测试：

```sh
./scripts/build-app.sh testDebugUnitTest :app:lintDebug   # 10/10 pass, 0 lint errors
./scripts/build-app.sh assembleRelease                    # 2.2MB, 非 debuggable
./scripts/install.sh                                      # release + 旧包/allowlist 检查
```

关键结果：

- DB：`PRAGMA user_version=3`，旧 1,750 条事件迁移后保留；`agg_*` 表有数据；`auto_vacuum=2`。
- 归因：logcat `uid map ready: 373 uids, source=root-pm`；近期网络事件 package 基本非空。
- 探针：`probes started: [... 8]`；privd 实例 `privprobe_<pid>_<starttime>`，事件组带 pid。
- P0-3：`am force-stop` 后无 privd / 无 tracefs 组定义 / 无实例。
- 资源：PSS ~129MB；20s 空闲 CPU ~4.55% 单核；静止 UI 0 帧。
- 自检：权限被拒绝时中止、不打开相机/麦克风；导出走 SAF 并二次确认。
- 截图：设备解锁后已重拍 12 张浅/深截图（见 `docs/screenshots/README.md`）；期间修复了主题卡 night、`uid=-1` Lazy key 崩溃、root 详情 `__PR_TIMEOUT__` 泄漏。
