# privd 原生采集器协议（NDJSON over stdout）

`privd` 由 App 通过 `su` 启动，运行在 root 上下文（ustar: `u:r:ksu:s0`，全 capabilities，SELinux Enforcing）。
App 侧 `NativeStreamProbe` 逐行读取 stdout，把每行 JSON 映射成 `PrivacyEvent`。

## 启动方式

```
su -c '<nativeDir>/privd --pcap --uprobe --kprobe-open --exec --uid-lookup --owner-pid <app_pid>'
```

- `--owner-pid <pid>`：启动它的 App 进程 pid。App 进程消失（force-stop/被杀）时 privd 在 ~1s 内自行退出。
  同时进程启动时设置 `prctl(PR_SET_PDEATHSIG, SIGTERM)`，父进程（su）死亡也会退出。
- `--instance <name>` / `--isolate`：仅用于诊断。默认实例名 = `privprobe_<pid>_<starttime>`，
  事件组名 = `PRIVGAI_<pid>_<starttime>` / `PRIVOPEN_<pid>_<starttime>`
  （starttime 取 /proc/self/stat 第 22 字段，防 pid 复用）；多个 privd 互不删除对方事件。
  `--isolate` 再加 `TEST` 前缀。
- **启动自清理**：先清理残留实例（解除实例内 enable），再删除 owner（pid+starttime）已死的
  自己的事件组；同时兼容旧版本固定组名 `PRIVGAI`/`PRIVOPEN` 与实例 `privprobe`/`privradar`，
  并会给「同目录下残留的旧 privd 进程」发 SIGTERM。
- **退出兜底**：注册 `atexit` 清理（正常/异常退出都 disable 事件 → `-:<group>/<event>` →
  rmdir 实例 → flush stdout/stderr）；force-stop 时由 owner-pid watchdog 触发正常退出路径。
- **stdout 断开即退出**：`emit_json` 检查 fputs/fflush/ferror，EPIPE（App 进程死亡 / 管道关闭）时
  置 g_stop，主循环清理 tracefs 后退出，不留孤儿 root 进程。

- 进程收到 SIGTERM / SIGINT 时必须移除自己创建的 uprobe/kprobe/安全模式，并退出。
- 所有日志走 stderr；stdout 只允许输出 NDJSON，每行一个 JSON，必须 flush。
- 未知行/坏 JSON 由 Kotlin 侧忽略，不允许崩溃。
- 首次启动先输出 `hello`，之后按事件流输出。

## 通用字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 固定 1 |
| `t` | string | 事件类型 |
| `ts` | long | 事件时间，Unix 毫秒 |
| `uid` | int? | 进程 uid（600 以下为系统/服务 uid） |
| `pid` | int? | 进程 pid |
| `comm` | string? | 进程名 |

## 事件类型

### 1. 生命周期

```json
{"v":1,"t":"hello","ts":1762390000000,"pid":1234,"features":["pcap","uprobe","kprobe-open","exec"],"ifaces":["wlan0","rmnet0"],"uprobe_libc":"/apex/com.android.runtime/lib64/bionic/libc.so"}
{"v":1,"t":"warn","msg":"uprobe getaddrinfo unavailable: ..."}
{"v":1,"t":"error","msg":"..."}
```

`hello.features` 表示请求启用的功能，不能作为健康证明。初始化完成及运行状态变化时发送：

```json
{"v":1,"t":"status","feature":"pcap","ok":true,"msg":"网络接口 7/7 已绑定","ts":1762390000001}
{"v":1,"t":"status","feature":"uprobe","ok":false,"msg":"DNS 调用链注册失败","ts":1762390000002}
```

`feature` 为 `pcap`、`uprobe` 或 `kprobe-open`，Kotlin 分别更新探针健康度。
PCAP 每 2 秒协调网络接口集合；没有接口或部分绑定失败时报告不健康，后续自动重试。
状态帧不受数据事件每秒限额丢弃。未知状态类型由旧客户端忽略。

### 2. 数据包捕获（PCAP）

DNS 查询（UDP/TCP 53）：

```json
{"v":1,"t":"dns_q","ts":1762390000123,"iface":"wlan0","proto":"udp",
 "src":"192.168.31.52:41234","dst":"223.5.5.5:53",
 "name":"api.example.com","qtype":"A","uid":10123,"pid":4567}
```

- `name` 必须是规范化后的域名（小写、去尾点）。
- 无法归因时省略 `uid`/`pid`（Kotlin 会显示为“netd / 系统 DNS”）。
- DoT 只解析到 SNI，不解析内层 DNS；DoH 只解析到 TLS SNI。

DNS 应答（用于 IP→域名关联；Kotlin 只缓存，不一定入库）。
**校验**：只有与最近 10s 内发出的查询（id + qname + qtype 匹配）对应的应答才会输出，
且只接受 qname/CNAME 链上的 A/AAAA 记录，防止伪造应答污染 App 的 IP→域名缓存：

```json
{"v":1,"t":"dns_a","ts":...,"name":"api.example.com","addr":"1.2.3.4","ttl":120}
```

TLS ClientHello SNI（443/8443/853 等）：

```json
{"v":1,"t":"sni","ts":...,"iface":"wlan0","dst":"1.2.3.4:443",
 "name":"api.example.com","uid":10123,"pid":4567}
```

HTTP Host：

```json
{"v":1,"t":"http","ts":...,"dst":"1.2.3.4:80","host":"example.com","uid":10123,"pid":4567}
```

新建出站连接（可选，用于“网络连接”统计；`uid` 通过 /proc/net 查询）：

```json
{"v":1,"t":"conn","ts":...,"dst":"1.2.3.4:443","proto":"tcp","uid":10123,"pid":4567}
```

### 3. 调用链跟踪（tracefs）

libc DNS 调用（uprobe `getaddrinfo` / `android_getaddrinfofornet`，带 hostname 字符串）。
uprobe 偏移**完全从运行时 ELF `.dynsym` 解析**（只接受 STT_FUNC/GNU_IFUNC 函数符号 + 边界校验）；
解析失败时禁用该 uprobe 并输出 warn，**不使用任何硬编码偏移 fallback**（避免挂到指令中间）：

```json
{"v":1,"t":"gai","ts":...,"name":"api.example.com","pid":4567,"uid":10123,"comm":"com.example.app"}
```

敏感设备打开（kprobe，device 层；uid/pid 可能是 cameraserver/audioserver）：

```json
{"v":1,"t":"open","ts":...,"path":"/dev/video0","pid":1234,"uid":1047,"comm":"cameraserver"}
```

进程创建（tracepoint `sched_process_exec`，可选）。
噪声过滤：只输出 uid>=10000 的应用进程，且过滤 `dumpsys/pm/sh/getprop/...` 等工具 comm：


```json
{"v":1,"t":"exec","ts":...,"pid":4567,"uid":10123,"comm":"com.example.app"}
```

## 归因规则

1. `gai` 的 `uid` 由 `/proc/<pid>/status` 查询；查询失败时省略。
2. PCAP 的 TCP/UDP 事件通过 `/proc/net/{tcp,udp,udp6,tcp6}` 的 `uid` 列按本地端口归因。
3. DNS 走 netd（uid 1052）时无法从 socket 归因；Kotlin 用 `gai` 事件（同名 + 时间窗）或后续 IP 连接做关联推断（`EventSource.HEURISTIC`）。
4. `open` 事件如果 uid 是 cameraserver/audioserver 等系统服务，Kotlin 会保留系统服务名并降低到“底层证据”，不当作最终调用者。

## 健壮性要求

- 单个事件解析失败绝不能退出；禁止在 stdout 打印调试信息。
- PCAP 丢包/权限失败时输出 `warn` 并继续（可以只跑 trace 模式）。
- 退出时清理：删除 `instances/privprobe` 或自己添加的 uprobe/kprobe 事件，关闭 trace 开关。
- 支持 `--dry-run`：只做自检并输出 hello/warn，然后退出，用于 App 配置页诊断。
