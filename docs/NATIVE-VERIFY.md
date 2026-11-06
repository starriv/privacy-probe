# privd 真机验证记录（NATIVE-VERIFY）

> 设备：小米 17 Pro Max（`aab3ac51`），HyperOS `OS4.0.5.0.XPBCNXM`，Android 17 / SDK 37，
> KernelSU root（`su -c` → uid=0，`u:r:ksu:s0`），SELinux Enforcing。
> 验证时间：2026-11-06 01:2x（本地时区 Asia/Shanghai）。
> 二进制：`app/src/main/assets/bin/arm64-v8a/privd`
> - 证据采集版本 sha256 `754b33…de7212`（39280 B）
> - **最终交付版本** sha256 `11246560402530170da26b384ec86804d78396e93c746a888517e5a34674e252`（39608 B，
>   在证据版本上增加 `snd_pcm_open stream=%x2` 与 exec 噪声过滤；strip 后的 aarch64 PIE，仅依赖 bionic libc）
> - APK 内 payload 与 assets 文件 sha256 一致（`assembleDebug` 后 `unzip -p … assets/bin/arm64-v8a/privd | sha256sum` 校验通过）

## 1. 构建（可复现）

```bash
cd privacy-probe
./scripts/build-native.sh
# NDK 28.0.12674087
# aarch64-linux-android31-clang -O2 -fPIE -pie --target=aarch64-linux-android31
# 源码：native/{privd,netcap,tracefs,util}.c + privd.h
# 产物：app/src/main/assets/bin/arm64-v8a/privd（仅依赖 bionic libc，动态链接 /system/bin/linker64）
```

推送与自检（注意 `adb shell "su -c '...'"` 整体引号，否则只有第一条命令是 root）：

```bash
adb push app/src/main/assets/bin/arm64-v8a/privd /data/local/tmp/privd
adb shell "su -c 'chmod 755 /data/local/tmp/privd; /data/local/tmp/privd --dry-run'"
```

## 2. `--dry-run` 实测（2026-11-06 01:31）

```bash
adb shell "su -c 'timeout 8 /data/local/tmp/privd --dry-run'"
```

输出（原始）：

```json
{"v":1,"t":"hello","ts":1793899893887,"pid":5006,"version":"1.0.0","features":["pcap","uprobe","kprobe-open","exec","uid-lookup"],"ifaces":["p2p0","wlan0","rmnet_data1","rmnet_data2","rmnet_data0","rmnet_ipa0","dummy0"],"uprobe_libc":"/apex/com.android.runtime/lib64/bionic/libc.so","dry_run":true,"caps":{"tracefs":true,"instance":true,"uprobe":true,"kprobe":true,"exec":true,"pcap":true,"uid_lookup":true}}
privd: dry-run complete
```

`caps` 全部为 `true`：
- `tracefs` 可写、`instance` 可创建；
- uprobe 注册（libc `getaddrinfo`，运行时从 ELF `.dynsym` 解析 offset）；
- kprobe（`v4l2_open` / `snd_pcm_open`）注册；
- `sched_process_exec` tracepoint 可 enable；
- AF_PACKET raw socket 可创建并 bind 到 7 个非 lo 接口；
- `/proc/net/*` 可读。

> 修复记录：初版 `--dry-run` 不带 feature flag 时 `features` 数组引用了未初始化的栈 buffer
> （输出过 `"features":[ld-android.so]` 这样的垃圾）。已改为零初始化 buffer，且
> dry-run 默认检查 App 实际会启用的全部特性（pcap/uprobe/kprobe-open/exec/uid-lookup）。

## 3. 端到端：`--pcap --uprobe --kprobe-open --exec --uid-lookup`

```bash
# 设备侧脚本（/data/local/tmp/final_run.sh）以 root 执行
/data/local/tmp/privd --pcap --uprobe --kprobe-open --exec --uid-lookup \
  > /data/local/tmp/privd.out 2> /data/local/tmp/privd.err &
sleep 1
R=$RANDOM
curl -s -m 4 -o /dev/null "http://$R.sslip.io"     # 强制 DNS query + A 应答
curl -s -m 4 -o /dev/null "https://example.com"    # TLS SNI
curl -s -m 4 -o /dev/null "http://example.org"     # HTTP Host
am start -a android.media.action.STILL_IMAGE_CAMERA  # 触发相机/音频设备打开
sleep 3
kill -TERM <privd_pid>
```

stderr（原始）：

```
privd: pcap: capturing on 7 interface(s)
privd: uprobe: 2 event(s) on /apex/com.android.runtime/lib64/bionic/libc.so (instance=1)
privd: kprobe: 2 event(s)
privd: tracefs: base=/sys/kernel/tracing/instances/privprobe instance=1
privd: shutting down
```

stdout 共 53 行，**全部是合法 NDJSON**（`json.loads` 逐行校验：valid=53 bad=0），
事件类型计数：`hello 1, dns_q 4, dns_a 24, gai 9, sni 1, http 1, conn 10, open 1, exec 2`，
无 `warn` / `error`。

### 3.1 DNS 查询 + 应答（AF_PACKET, wlan0）

```json
{"v":1,"t":"dns_q","ts":1793899899191,"iface":"wlan0","proto":"udp","src":"192.168.31.52:14730","dst":"223.5.5.5:53","name":"21822.sslip.io","qtype":"A"}
{"v":1,"t":"dns_q","ts":1793899901177,"iface":"wlan0","proto":"udp","src":"192.168.31.52:8257","dst":"223.5.5.5:53","name":"www.googleapis.com","qtype":"A","uid":10135}
{"v":1,"t":"dns_a","ts":1793899901191,"name":"www.googleapis.com","addr":"172.217.118.4","ttl":78}
{"v":1,"t":"dns_a","ts":1793899901191,"name":"www.googleapis.com","addr":"172.217.116.4","ttl":78}
```

- 能抓到真实后台流量：`www.googleapis.com` 的 `uid=10135`（gms/Cronet）。
- DNS 应答用于 Kotlin 侧 IP→域名缓存；A/AAAA 都支持，TTL 来自报文。
- 部分 `dns_q` 无 `uid`：Android 17 的 netd DNS socket 是 root（uid 0）创建的短命 socket，
  抓包线程按需刷新 `/proc/net/udp` 仍可能在其关闭后（实测约一半命中），协议允许省略
  `uid`，Kotlin 用 `gai` 事件归因。

### 3.2 TLS SNI / HTTP Host

```json
{"v":1,"t":"sni","ts":1793899899748,"iface":"wlan0","dst":"172.66.147.243:443","name":"example.com","uid":0}
{"v":1,"t":"http","ts":1793899900553,"iface":"wlan0","dst":"104.20.26.136:80","host":"example.org","uid":0}
```

IPv4 / IPv6 均支持，`dst` 为 `ip:port`（IPv6 为 `[addr]:port`）；TLS 按 TCP 段首包解析，
分段/非 ClientHello 静默丢弃。

### 3.3 uprobe：libc `getaddrinfo`（域名 + pid + uid + comm）

```json
{"v":1,"t":"gai","ts":1793899899192,"name":"21822.sslip.io","pid":5046,"uid":0,"comm":"curl"}
{"v":1,"t":"gai","ts":1793899901049,"name":"ime-igw.doubao.com","pid":13463,"uid":10377,"comm":"Thread-4607"}
{"v":1,"t":"gai","ts":1793899901175,"name":"www.googleapis.com","pid":19323,"uid":10135,"comm":"CronetNet"}
{"v":1,"t":"gai","ts":1793899901745,"name":"play.googleapis.com","pid":5730,"uid":10128,"comm":"bbxb"}
```

- 事件来自 `/sys/kernel/tracing/uprobe_events` 全局注册（`p:PRIVGAI/getaddrinfo <libc>:0xabe00 node=+0(%x0):string`），
  在 `instances/privprobe/events/PRIVGAI/.../enable` 单独 enable，读 `instances/privprobe/trace_pipe`。
- offset 是运行时解析 `/apex/com.android.runtime/lib64/bionic/libc.so` 的 ELF64 `.dynsym` 得到的
  `st_value`（本机 `getaddrinfo=0xabe00`，`android_getaddrinfofornet=0xabe64`；解析失败才用硬编码 fallback 并 warn）。
- 也能抓到后台应用真实 DNS 调用（如 `ime-igw.doubao.com` 来自输入法 uid 10377）。

### 3.4 kprobe：敏感设备打开（open）

```json
{"v":1,"t":"open","ts":1793899901861,"path":"/dev/snd/pcmC0D0p","pid":5781,"uid":1041,"comm":"primary_in_100"}
```

- 注册命令：`p:PRIVOPEN/v4l2_open v4l2_open`、
  `p:PRIVOPEN/snd_pcm_open snd_pcm_open stream=%x2`
  （x2 = `snd_pcm_open(name, stream, mode)` 的 stream：0=playback，1=capture；
  内核不支持寄存器取值时自动退回无参注册）。
- 上述样本是 audioserver 音频输出线程（uid 1041）打开 **playback** PCM 设备，属于底层证据；
  按产品语义 **playback 不算麦克风**，Kotlin 侧会忽略 `/dev/snd/pcmC*D*p`，
  只有 capture（`/dev/snd/pcmC*D*c`）映射为 MICROPHONE（confidence 60）；`/dev/video*` → CAMERA。
- 相机 trigger 在该窗口引发的是播放通道打开；`v4l2_open` 在更早一轮测试中出现过（`open` 计数 1）。

### 3.5 exec tracepoint

```json
{"v":1,"t":"exec","ts":1793899901851,"pid":5786,"uid":1000,"comm":"test-nusensors","path":"/odm/bin/test-nusensors"}
{"v":1,"t":"exec","ts":1793899901857,"pid":5787,"uid":1000,"comm":"test-nusensors","path":"/odm/bin/test-nusensors"}
```

- 来自 `sched_process_exec` tracepoint，只过滤 root(0)/shell(2000) 的进程创建，避免 su/unzip 噪声。

### 3.6 出站连接（conn）

```json
{"v":1,"t":"conn","ts":...,"iface":"wlan0","dst":"172.66.147.243:443","proto":"tcp","uid":0}
```

TCP SYN（无 ACK）→ 事件；uid 通过 `/proc/net/tcp{,6}` local port→uid 缓存（1s 刷新 + 未命中按需刷新）。
Kotlin 侧只在命中 DNS IP 缓存时发 HEURISTIC 事件，否则丢弃（避免纯 IP 噪声入库）。

## 4. 退出清理实测

SIGTERM 后：

```bash
adb shell "su -c 'ls -d /sys/kernel/tracing/instances/privprobe 2>/dev/null || echo instance_clean'"
# instance_clean
adb shell "su -c 'ls /sys/kernel/tracing/events/PRIVGAI 2>/dev/null || echo privgai_clean'"
# privgai_clean
adb shell "su -c 'ls /sys/kernel/tracing/events/PRIVOPEN 2>/dev/null || echo privopen_clean'"
# privopen_clean
adb shell "su -c 'pidof privd; echo done'"
# done（无残留进程）
```

清理内容：disable 所有自己 enable 的事件 → 从全局 `uprobe_events`/`kprobe_events` 删除
`PRIVGAI/*`、`PRIVOPEN/*` 定义 → rmdir `instances/privprobe` → 关闭 trace_pipe → join 线程。
单事件失败只输出 `warn`，不会退出；进程收到 SIGTERM/SIGINT/SIGHUP 都会走清理。

## 5. 关键设备事实（本轮补充）

- **tracefs 实例里没有 `uprobe_events` / `kprobe_events`**（只有全局 `/sys/kernel/tracing/` 下才有；
  实测 `echo ... >> instances/privprobe/uprobe_events` → `Permission denied`）。
  因此 privd 采用：**全局注册动态事件 → 实例内 enable + 读 `instances/privprobe/trace_pipe`**，
  既避开小米 bcc 对全局 trace_pipe 的消费，又保证隔离。实例创建失败时退回全局并 `warn`。
- `instances/` 下有小米自己的 `btspi`、`hsuart`、`km` 实例，不要动；本程序只用自己创建的 `privprobe`。
- 注册 kprobe 时 `p:PRIVOPEN/v4l2_open v4l2_open`（无参数）可直接成功；不要依赖
  `available_filter_functions`（本机不存在），注册失败即视为不可用。
- `/proc/net/udp` 在 DNS 查询瞬间能看到 netd 的 socket（uid 列 **0**，不是 1052），
  但 socket 生命周期极短，抓包归因存在竞态；DNS 归因主要靠 uprobe `gai`。

## 6. Kotlin 侧 `NativeStreamProbe`

`app/src/main/java/dev/popsicle/privprobe/core/collector/net/NativeStreamProbe.kt`：

- 构造 `(context, settingsProvider: () -> AppSettings, sink: (PrivacyEvent) -> Unit)`；
- `start(scope)` 在传入的 CoroutineScope 上运行监督循环：解压 `assets/bin/<abi>/privd` 到
  `filesDir/privd`（`setExecutable` 755）→ `ProcessBuilder("su","-c","exec <bin> --pcap ...")`；
- 逐行 `org.json` 解析 NDJSON：
  `dns_q`/`sni`/`http` → NETWORK(PACKET, 90)，`gai` → NETWORK(UPROBE, 95)，
  `open` `/dev/video*`→CAMERA、capture `pcmC*D*c`→MICROPHONE (KERNEL_TRACE, 60)，
  `exec` → APP_ACTIVITY(KERNEL_TRACE, 60)；
- **conn 去噪**：只有 DNS IP→域名缓存能解析出域名时才发（target=域名, HEURISTIC, 65），
  裸 IP（如 `1.2.3.4:443`）一律丢弃；且同 `(uid, domain)` **60s 最多一条**（GMS/vending 每 1~2s
  新建连接，12 条里 11 条是 `android.googleapis.com` 的问题由此消除）；
- **dns_q/gai 去重**：`dns_q` 先挂起 1s，窗口内出现同名 `gai` 就丢弃 `dns_q`（保留归因更好的 uprobe 事件）；
  `gai` 立即发出；反之若 `gai` 先到，1s 内同名 `dns_q` 直接丢弃；pending 任务在 `stop()` 时全部 cancel；
  另外 `getaddrinfo` 与 `android_getaddrinfofornet` 两条 uprobe 可能各命中一次，同 `(uid, name)`
  **2s 内只发先到的一条** gai（被去重的 gai 仍会抑制同名 dns_q）；
- **open 限流**：同 `(uid,path)` 10s 最多一条；cameraserver/audioserver（uid 1047/1041）60s 一条；
  playback（`pcmC*D*p`）与未知 snd 路径忽略；
- 维护 DNS A/AAAA → 域名 的 TTL 缓存；`lastStatus: StateFlow<String?>` 暴露 hello/warn/error/退出原因；
- **二进制解压防 ETXTBSY**：每次解压到唯一文件名 `filesDir/privd-<ts>-<counter>`，写失败换名重试 3 次（间隔 200ms），
  成功后 unlink 旧 `privd` / `privd-*`（unlink 对正在执行的二进制安全）；启动前 `pkill -f '[p]rivd'`
  并用 shell 内循环轮询 `pgrep -f '[p]rivd'` 等待旧进程退出（最多 ~1.5s），`stop()` 后同样等待；
  这样服务重启/设置变更时不会出现 `open failed: ETXTBSY (Text file busy)`；
- 进程退出指数退避重启（1s→30s，稳定 30s 后复位）；`stop()` 用 hello 里的 pid 发 SIGTERM/SIGKILL，
  再 destroy `su` 进程，保证不留 root 子进程。

编译校验（2026-11-06 01:35，全部通过）：

```bash
cd privacy-probe
./scripts/build-app.sh :app:compileDebugKotlin   # exit=0，0 error
./scripts/build-app.sh assembleDebug             # exit=0
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/bin
#   39608  Defl:N  20095  …  assets/bin/arm64-v8a/privd
unzip -p app/build/outputs/apk/debug/app-debug.apk assets/bin/arm64-v8a/privd | shasum -a 256
#   1124656…4e252（与 assets 源文件一致）
```

另外本文件也用独立 kotlinc（Models.kt + SettingsStore.kt + 本文件）单独编译通过。
启动前会 `pkill -f '[p]rivd'` 清理旧实例；`stop()` 优先按 hello.pid 发 SIGTERM 让 privd
自行清理 tracefs，再 SIGKILL/destroy 兜底。

## 7. 已知限制

1. TLS SNI 只解析每个 TCP 段的段首；ClientHello 被拆到后续段、或连接中途加入（无 SYN）时静默丢弃。
2. QUIC / UDP 443 不解析；DoH/DoT 只能看到 TLS SNI，看不到内层 DNS 域名。
3. IPv4 非首片分片跳过；IPv6 扩展头只处理 Hop-by-Hop/Routing/DestOpts，Fragment 跳过。
4. 动态 uprobe/kprobe 定义在全局（内核限制），非实例隔离；异常退出（SIGKILL）可能残留
   `PRIVGAI/*`、`PRIVOPEN/*` 定义，下次启动会先 `-:` 删除再重加（已处理）。
5. `conn` 事件的 pid 未知（/proc/net 不提供），只有 uid；DNS uid 归因存在短命 socket 竞态。
6. 二进制为 arm64-v8a only；其它 ABI 需要补构建（脚本里改 target 即可）。
7. 若内核拒绝 `stream=%x2` 寄存器参数，snd 事件退化为无方向路径 `/dev/snd/pcmC0D0`，
   Kotlin 会忽略该路径（宁可少报，不把播放误报成录音）。

## 8. 真机验证结果：kprobe `snd_pcm_open stream=%x2`（2026-11-06 01:5x）

**结论：通过。** 现场条件：App 前台、采集运行中、privd 在跑（pid 30883，路径
`privd-1793901098571-1` —— 唯一文件名，说明 ETXTBSY 修复已在真机生效）。

### 8.1 注册语法（直接读取 App 的 live privd 注册结果）

```bash
adb shell "su -c 'grep -E \"PRIVOPEN|PRIVGAI\" /sys/kernel/tracing/{kprobe,uprobe}_events'"
```

```
p:PRIVOPEN/v4l2_open v4l2_open
p:PRIVOPEN/snd_pcm_open snd_pcm_open stream=%x2
p:PRIVGAI/getaddrinfo /apex/com.android.runtime/lib64/bionic/libc.so:0x00000000000abe00 node=+0(%x0):string
p:PRIVGAI/android_getaddrinfofornet /apex/com.android.runtime/lib64/bionic/libc.so:0x00000000000abe64 node=+0(%x0):string
```

`/sys/kernel/tracing/events/PRIVOPEN/snd_pcm_open/format`：

```
field:u64 stream;	offset:16;	size:8;
print fmt: "(%lx) stream=0x%Lx", REC->__probe_ip, REC->stream
```

→ 内核接受 `stream=%x2` 寄存器参数；`stream` 进入 trace 字段，格式为 `0x%Lx`
（`stream=0x0/0x1`，`strtoul(base 0)` 可解析）。

### 8.2 实测 trace（独立实例 chkstream，不打断 App 的 privprobe 实例）

App 的 privd 在跑且占用 `instances/privprobe`，因此新建**自己的** `instances/chkstream`，
只 enable App 已注册的 `events/PRIVOPEN/snd_pcm_open`，读 `instances/chkstream/trace_pipe`。
触发录音（相机/音频输入 UI，2 秒后返回），得到原始 trace：

```
  primary_in_142-7467    [004] d.... 54165.606134: snd_pcm_open: (snd_pcm_open+0x0/0x21c) stream=0x1
  primary_in_144-9297    [004] d.... 54177.181994: snd_pcm_open: (snd_pcm_open+0x0/0x21c) stream=0x1
```

- `stream=0x1` = capture → privd 输出 `/dev/snd/pcmC0D0c`，Kotlin 映射 MICROPHONE（confidence 60）；
- playback(`0x0`) 与 capture 共用同一解析代码（`stream == 1 ? 'c' : 'p'`），且本轮之前的历史证据
  已抓到过实际 playback 打开（修复前 privd 默认输出 `/dev/snd/pcmC0D0p`、uid 1041
  `primary_in_100`）；本轮窗口内通知/音量键均未产生 PCM open，故未重复观测到 `0x0`。

### 8.3 现场约束遵守情况

- **未 pkill**：App 的 privd（pid 30883）全程存活，测试后仍在跑；
- **未停采集**：`instances/privprobe` 一直存在，App 前台保持；
- 只创建/删除自己的 `instances/chkstream`，只 enable/disable `events/PRIVOPEN/snd_pcm_open`
  （未删除/改动 App 的 `PRIVGAI/*`、`PRIVOPEN/*` 全局定义）；
- 结束：`chkstream` 已 rmdir、全局无 `CHKSTREAM` 残留；前台恢复为
  `dev.popsicle.privprobe/.MainActivity`。

### 8.4 结论

- 注册成功 + trace `stream=0x1` 可解析 → **capture(c)/playback(p) 分类成立**，
  Kotlin 只对 capture 发 MICROPHONE，不会把播放误报成录音；
- 若个别内核拒绝 `stream=%x2`，privd 自动退回无参注册 → 路径 `/dev/snd/pcmC0D0`
  被 Kotlin 忽略（§7.7），不影响其它探针。

