# 设备 / 构建环境事实（本次开发环境）

> 这些是 2026-11-06 在本机（macOS + 已 root 的小米 17 Pro Max）实测得到的结论。
> 开发任何探针前先读这一页，避免重复踩坑。

## 1. 设备

- 序列号 `aab3ac51`，`popsicle`，Xiaomi 17 Pro Max，HyperOS `OS4.0.5.0.XPBCNXM`，Android 17 / SDK 37。
- 已通过 KernelSU 越狱模式 root：`adb shell "su -c 'id'"` → `uid=0(root)`，context `u:r:ksu:s0`。
- **SELinux Enforcing**，但 ksu domain 权限很宽：全程**不需要** `setenforce 0`（也做不到，会 Permission denied）。
- root 进程 `CapEff=0x1ffffffffff`（全 caps）。实测可用：
  - `AF_PACKET` raw socket（绑定 wlan0 收包 OK）
  - `NETLINK_INET_DIAG`（`ss` 可用；`ss -tunp` 能看到进程）
  - `iptables` / `nft`（mangle 表有小米自己的规则，不要乱改）
  - tracefs：`/sys/kernel/tracing/{kprobe_events,uprobe_events,instances,...}` 可写、可 enable、`trace_pipe` 可读
  - 读 `/proc/<任意 pid>/{fd,maps,status}`（跨 uid 可读）
  - `/proc/net/{tcp,udp,tcp6,udp6,unix}` 可读，含 uid 列
- **重要**：`adb shell su -c 'a; b'` 只有 `a` 会以 root 执行。必须整体加引号：
  `adb shell "su -c 'a; b'"`。

## 2. 验证过的低层能力

### 2.1 AF_PACKET 抓包

用 NDK clang 编译的测试程序在 root 下 `socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL))` + bind wlan0 + recv 成功。
接口：`wlan0`，另外 `rmnet*`（5G）、`dummy0` 等；用 `ip -o link` 枚举。

### 2.2 uprobe（DNS 调用链可用）

- 内核 `CONFIG_UPROBES=y`，但 **符号名方式注册失败**（libc 被 strip）。必须用**文件偏移**：
  ```
  echo 'p:gai/getaddrinfo /apex/com.android.runtime/lib64/bionic/libc.so:0xabe00 node=+0(%x0):string' >> /sys/kernel/tracing/uprobe_events
  ```
  enable 路径：`/sys/kernel/tracing/events/gai/getaddrinfo/enable`（组名=事件名里 `/` 前那段）。
- 实测 trace 输出：
  ```
  curl-17277 [000] ...: getaddrinfo: (0x7da557ae00) node="example.net"
  ```
- `libc.so` 在 host 上用 NDK `llvm-nm -D --defined-only` 看到：`getaddrinfo@@LIBC` = `0xabe00`，
  `android_getaddrinfofornet@@LIBC_Q` = `0xabe64`。**App 运行时应自己解析 ELF `.dynsym` 取偏移**，硬编码只做 fallback。
- tracefs 实例：优先 `/sys/kernel/tracing/instances/privprobe/`（先 `mkdir` 再设置，退出删除），避免与小米 bcc 探针互相干扰。

### 2.3 kprobe

- 函数名添加可用：`v4l2_open`、`snd_pcm_open`、`binder_transaction`、`unix_stream_sendmsg`、`do_sys_openat2` 均可注册。
- 字符串过滤可用（注意加引号）：
  `echo 'filename ~ "*video*"' > events/kprobes/<ev>/filter` → 接受。
- `do_sys_openat2` 取路径：`dfd=%x0 filename=+0(%x1):string`，实测能打印 `/system/lib64/libnetd_client.so` 等路径。
- `available_filter_functions` 文件不存在，不能据此判断符号；直接注册失败即视为不存在。

### 2.4 其它

- `dumpsys appops` 全量 2.1 MB，包含每个 uid/op 的 `Access: [flags] 时间 duration=...` 与 `Running start at:`，可轮询 diff。
- `dumpsys media.camera` 活跃客户端格式（实测）：
  ```
  Active Camera Clients:
  [
  (Camera ID: 5, Cost: 99, PID: 4572, Score: 0, State: 2User Id: 0, Client Package Name: com.android.camera, ...)
  ]
  ```
- `dumpsys sensorservice` 有 `Recent Sensor Events` 段，逐行含 `+ 0x... pid= uid= samplingPeriod= (sensor, package)`。
- `dumpsys location` / `-a` 有监听器 `uid/package Request[...]` 和每客户端 `locations = N` 计数。
- `dumpsys media.audio_flinger` 的输入线程段只在录音时出现；`dumpsys audio` 有 `## RecordActivityMonitor`。
- `dumpsys media_projection` 无会话时输出 `null`；有会话时解析 `Active projections`。
- `dumpsys clipboard` 在这个 ROM 上无输出；剪贴板主要靠 logcat / AppOps。
- `logcat` root 可读；`setprop log.tag.resolv VERBOSE` 能看到 DNS 解析过程，但**看不到被查询域名**，因此 DNS 归因用 uprobe + 抓包。

## 3. 构建 / 安装

- 本机没有系统 JDK；用 `~/.gradle/jdks/...` 里的 Temurin 17（构建脚本已自动找）。
- DSH 沙箱只允许写工作区，**不要写 `~/.gradle`**。构建脚本已把 Gradle home/Android user home 指到仓库：
  ```
  cd privacy-probe && ./scripts/build-app.sh assembleDebug
  ```
  首次构建会下载依赖，比较慢；之后增量很快。
- **`adb install` 会被 MIUI 拒绝**（`INSTALL_FAILED_USER_RESTRICTED`）。用 root 安装：
  ```
  adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/privprobe.apk
  adb shell "su -c 'pm install -r /data/local/tmp/privprobe.apk'"
  adb shell am start -n dev.popsicle.privprobe/.MainActivity
  ```
- 截图：`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png /tmp/s.png`（`exec-out` 得到的 PNG 可能损坏）。
- 构建 APK 路径：`privacy-probe/app/build/outputs/apk/debug/app-debug.apk`。
- 测试 UI 时屏幕可能已锁；`input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` 可用。

## 4. 权限 / 触发测试

- 打开相机可用：`am start -a android.media.action.STILL_IMAGE_CAMERA`，随后 `dumpsys media.camera` 能看到 active client。
- 录音：设备有 `com.android.soundrecorder`；更可控的是 App 自己的「自检」按钮（申请 RECORD_AUDIO 录 2 秒）。
- 自检 DNS：App 里 `InetAddress.getAllByName("example.com")`，验证 uprobe/pcap 是否抓到。
