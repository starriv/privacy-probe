# Fix: 清理标记区分「整包卸载 / 冻结 / 仅禁组件」

> **日期**: 2026-11-09

## 问题描述

用探针查看 `com.miui.securitycenter`（手机管家）时，调用者详情页把该包标记为「已清理」，
但真机上它仍然安装着、进程仍在运行，看起来像“清理没生效 / 应该卸载却没卸”。

实际背景：清理清单对该包只做了**组件级禁用**（13 个反诈组件），包本体是有意保留的
（定义 58 项 MIUI 权限，被 `com.android.permissioncontroller` 等依赖）。问题出在探针的展示口径。

## 根因分析

1. **数据集把组件类名当成了包名**：`scripts/gen-datasets.py` 用整篇正则
   `re.findall(r"\b(?:com|android|miui)\.[a-zA-Z0-9_.]+", text)` 扫
   `knowledge/DEBLOAT-EXECUTED.md`，于是：
   - 组件表里的 `com.miui.securitycenter/com.miui.electricrisk.*` 让 **securitycenter 整个包**进入
     debloat 集合；
   - 组件全限定名（`com.miui.electricrisk.AiGuardSceneService` 等 15 条）和
     `android.uid.*` 共享 UID 名也被当成包名；
   - 旧数据集 44 条里只有 29 条是真正的包。
2. **UI 只有布尔标记**：`RepoIntel.isDebloatCleaned()` 返回 true/false，chip 固定显示「已清理」，
   无法表达“整包卸载 / 已冻结 / 只禁了组件”的粒度差异。

## 修复方案

### 数据：按清单结构解析，输出处置粒度

`parse_debloat()` 改为按 markdown 分组 + 行内动作词解析，输出 `{包名: 粒度}`：

| 粒度 | 含义 | 来源 |
|---|---|---|
| `uninstall` | `pm uninstall -k --user 0` | `## 已卸载` 分组 |
| `lock` | 卸载 + `disable-user` + 清数据 | `uninstall-lock` / 分组内「锁定」 |
| `freeze` | `pm disable-user --user 0` | `## 已冻结` 分组 |
| `component` | 只禁用包内组件，包本体仍在 | `## 已禁用组件` 分组 |

解析规则：

- 包名只取表格行**反引号代码段**的内容；`包/组件` 形式只取斜杠前的包；
- 包名每段以小写字母开头（允许 `systemAdSolution` 这类驼峰），因此组件类名
  （`AiGuardSceneService` / `XSOFProvider`）不会命中；
- 排除 `android.uid.*` / `android.process.*`（共享 UID 名，不是包）；
- 同一包出现在多处时保留影响更大的动作（`lock > uninstall > freeze > component`）；
- 解析结果为空时直接报错，防止清单格式变化后静默产出空数据。

### UI：chip 按粒度显示

- `RepoIntel.debloatKind(pkg): String?` 取代布尔接口（`isDebloatCleaned` 保留为兼容封装）；
- `Intel.debloatLabel(pkg)` 映射为中文标签：**已卸载 / 已锁定 / 已冻结 / 仅禁组件**；
- 调用者详情页 chip 使用该标签。

### 数据同步

`knowledge/DEBLOAT-EXECUTED.md` 同步到 CVE 仓库最新清单（2026-11-08 补录：
SystemHelper 锁定、OTA 禁用、扩展功能/企业模式），新数据集 44 → 34 条，
`com.miui.securitycenter` / `com.miui.miwallpaper` / `com.miui.packageinstaller` /
`com.miui.securitycore` 均标记为 `component`。

## 修改文件

| 文件 | 变更 |
|---|---|
| `scripts/gen-datasets.py` | 新增 `parse_debloat()` / `packages_in_row()` / `classify_debloat_row()` / `is_package_name()`；debloat 输出 `{包名: 粒度}` |
| `knowledge/DEBLOAT-EXECUTED.md` | 同步最新已执行清单 |
| `app/src/main/assets/datasets/package_intel.json` | 重新生成（仅 debloat 变化，247 个包数据不变） |
| `core/intel/RepoIntel.kt` | debloat 改为 Map；新增 `debloatKind()`；兼容旧布尔数据集 |
| `ui/util/Intel.kt` | 新增 `debloatLabel()` |
| `ui/screens/CallerScreen.kt` | chip 显示粒度标签 |
| `app/build.gradle.kts`、`CHANGELOG.md` | 版本 1.2.4（versionCode 10204）与变更记录 |

## 验证

- `python3 scripts/gen-datasets.py`：`debloat: uninstall=25, lock=2, freeze=3, component=4`，
  无组件类名 / `android.uid.*` 混入；`packages` 与旧数据集逐项一致（247 个）。
- `:app:testDebugUnitTest` + `:app:assembleDebug`：通过。
- 构建产物 `app-debug.apk`：`versionName=1.2.4`、`versionCode=10204`；打包内
  `assets/datasets/package_intel.json` debloat 34 条，`com.miui.securitycenter=component`；
  dex 内含「仅禁组件」标签。
- 真机复查（Xiaomi 17 Pro Max / HyperOS 4 / Android 17）：
  - 旧版是 CI 发布签名，与本地 debug keystore 不同，`pm install -r` 被拒；先备份
    `/data/data/dev.popsicle.privprobe`（`/tmp/privprobe-data.tgz`）再卸载旧版，安装
    1.2.4 debug（versionCode 10204）。
  - 恢复备份数据后修正 SELinux 标签（tar 以 root 解包把子目录标成
    `system_data_file`，需 `chcon -R u:object_r:app_data_file:s0:c183,c256,c512,c768`），
    启动后显示「采集中 · root ✓」，事件数继续增长。
  - 设备标识大类 → 手机管家（`com.miui.securitycenter`，uid 1000）调用者页：chip 由
    旧的「已清理」变为「**仅禁组件**」；今日 / 累计 4 条
    `use_icc_auth_with_device_identifier`，首见 11:24，目标分布与时间线正常。
