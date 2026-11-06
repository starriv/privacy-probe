# Changelog

## 1.2.3 — 2026-11-06

### 修复
- 详情页返回列表时状态丢失：大类详情的分段 tab、时间线分页和列表滚动位置改为按页面实例保存恢复；
  调用者 / 目标详情互跳返回后同样停在原处；切换底部 tab 时总览 / 配置的滚动位置也会保留。
- 采集数据口径：相机/麦克风内核打开只记录应用进程（uid≥10000）直接打开设备的事件；相机服务探针轮询触发的
  cameraserver / 厂商相机 provider 自噪不再入库，升级时清理历史自噪（实测关闭探针 4 分钟 0 条）。
- 调用者 / 目标聚合改为按 (uid, package) 拆分：共享 uid（如 uid 1000）不再把「电量和性能 / 动态系统更新 /
  弹幕通知」合并成一行显示成「弹幕通知」；调用者详情按包名过滤，未知包名的调用者单独成行。
- 隐私探针自身与旧包名（dev.popsicle.privradar）的事件不再写入历史，升级时清理，不再出现在调用者与时间线。
- 域名情报扩充 14.7 小时采集到的域名（tracker / functional / unclassified 分类）。

### 优化
- 大类时间线改为「加载更早」的游标增量加载：去掉「上一页 / 下一页 / 第 x 页 · 保留期内记录」挤在一行的分页条；
  按 (ts, id) 游标翻页，新事件持续写入时不会重复或跳过；加载完显示「已加载全部 N 条」。

### 构建
- DB v3→v4 迁移：重建按天聚合表（保留事件数据），一次性清理探针自噪与自身/旧包名事件。

## 1.2.1 — 2026-11-06

### 修复
- 检查更新弹窗只展示 Release 说明的可读文本，去掉 Markdown 的标题、引用和反引号。

## 1.2.0 — 2026-11-06

### 新增
- 配置页「关于」新增「检查更新」：手动查询 GitHub 最新 Release，有新版本时弹窗展示说明并跳转浏览器下载；只有点击时联网。
- 版本号比较与 Release 说明清理的单元测试。

### 修复
- 采集器可靠性：混合应用聚合、写入失败传播、生命周期串行化、DNS 归因保持。
- 存储维护改为每批写入后检查容量上限；分类时间线分页；子系统健康度独立上报。
- Native：刷新 privd 接口、DNS 解析、tracefs 处理与协议文档。

### 构建
- CI / Release 从源码构建原生采集器；正式发布强制校验签名证书。

## 1.1.0 — 2026-11-06

### 拆分与改名
- 独立目录 `/Users/starriv/workspace/privacy-probe`，不再依赖 CVE 仓库。
- 包名 `dev.popsicle.privradar` → `dev.popsicle.privprobe`；中文名「隐私雷达」→「隐私探针」。
- KernelSU 旧包授权已禁用（`allow_su=0`），避免旧 uid 复用继承 root。
- release 侧载（debug keystore 签名，非 debuggable）；安装脚本增加旧包/allowlist 检查。

### 安全 / 生命周期
- 自检不再通过 root 强授权限；UI 检查用户同意并二次确认。
- privd：PDEATHSIG + owner-pid 监控 + EPIPE 退出；force-stop 1s 内退出并清理 tracefs。
- tracefs 唯一组/实例名、启动 stale 清理、atexit 兜底；DNS 应答校验、ELF 边界校验。
- 默认采集关闭（首启引导）；导出改 SAF + 敏感数据确认。

### 正确性 / 性能
- DB v1→v3 迁移；按天预聚合；逻辑去重键 + 来源 UPSERT；索引调整；保留期硬上限。
- UidResolver root fallback（`pm list packages -U` / packages.list），修复网络事件 package=NULL。
- Logcat 解析重写（预过滤/无逐行 Matcher）；dumpsys 降频/收窄；熄屏门控。
- Dashboard 查询从 36 条降到 9 条预聚合；Category 时间线单查询。
- 静态 formatter、CSV 公式注入防护、稳定 Lazy key、去常驻动画。

### UI
- 底部 TabBar 改为非对称悬浮 dock：左侧胶囊 tabs + 右侧独立圆形采集开关；左右 16dp、底 12dp + navigationBars、62dp 高、全圆角，浅色柔和阴影，选中 tab 为 soft 底。

### 图标
- 重新制作为线性简洁图标：雷达双环 + 探针扫线 + 单一 accent 圆心，无盾牌、无渐变/填充块。

### 实测
- 空闲 PSS ~129MB、后台 CPU ~4.5% 单核；Database ~1.6MB / 3.2k events（7 天上限 150MB）。
- `testDebugUnitTest` 10/10；lint 0 errors；release APK 2.2MB。
