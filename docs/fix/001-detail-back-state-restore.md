# Fix: 详情返回列表时丢失分段 tab 与滚动位置

> **日期**: 2026-11-06

## 问题描述

在网络与 DNS 大类详情里切到「目标」tab、滚动列表后进入某个目标详情，再返回大类：

- 分段 tab 从「目标」跳回「调用者」；
- 列表滚动位置回到顶部。

调用者 → 目标 → 返回调用者详情、目标 → 调用者 → 返回目标详情也有同样的状态丢失。
另外在总览 / 配置之间切换底部 tab 时，两个根页面的滚动位置也会被重置。

## 根因分析

页面栈是自绘的：`ProbeApp` 用 `when (navigator.current)` 直接组合当前页面。进入详情时旧页面整个离开组合，
Compose 中 `remember` 的状态随之销毁：

- 列表没有传入 `LazyListState`，`LazyColumn` 内部滚动状态在离场后丢弃；
- 大类详情的分段 tab 与时间线分页用 `remember(category) { ... }`，只在组合存活期间有效；
- `ProbeNavigator.switchTab` 清空页面栈后重新加入根页面，根页面每次都是全新组合。

这是导航层的状态归属问题，不是单个列表的问题，因此修复集中在页面栈与页面状态容器。

## 修复方案

- 页面栈改为 `NavEntry(id, screen)`：`id` 进程内唯一且不复用，作为该页 UI 状态的保存键；
  根 tab 使用固定 id（总览 `0` / 配置 `1`），切换底部 tab 时保留各自的根页面槽位。
- `ProbeApp` 用 `rememberSaveableStateHolder()`，并以 `SaveableStateProvider(navigator.currentEntry.id)`
  包住当前页面：页面离场时自动保存状态，返回同一页面实例时恢复。已弹出页面的状态在页面栈变化后清理，
  下次重新打开同类页面从顶部开始，不会串用上一次的状态。
- 各列表页把滚动状态接入保存机制：`rememberLazyListState()` 交给对应 `LazyColumn(state = ...)`；
  大类详情的分段 tab、时间线分页改用 `rememberSaveable { mutableIntStateOf(0) }`。
- 总览 / 配置两个根页面同样使用 `rememberLazyListState()`，切 tab 回来时停在原处。

关键差异：

```kotlin
// ❌ Before：离开组合即丢失
var tab by remember(category) { mutableIntStateOf(0) }
LazyColumn(modifier = ..., contentPadding = ...) { ... }

// ✅ After：由外层 SaveableStateProvider(页面 id) 提供容器，离场保存、返回恢复
var tab by rememberSaveable { mutableIntStateOf(0) }
val listState = rememberLazyListState()
LazyColumn(state = listState, modifier = ..., contentPadding = ...) { ... }
```

## 修改文件

| 文件 | 变更 |
|---|---|
| `app/src/main/java/dev/popsicle/privprobe/ui/nav/Screen.kt` | 页面栈改为 `NavEntry(id, screen)`；push id 唯一不复用，根 tab 固定 id；保留 `entries` / `currentEntry` |
| `app/src/main/java/dev/popsicle/privprobe/ui/ProbeApp.kt` | `rememberSaveableStateHolder` + `SaveableStateProvider(当前页面 id)`；页面栈变化后清理已弹出页面的状态 |
| `app/src/main/java/dev/popsicle/privprobe/ui/screens/CategoryScreen.kt` | 分段 tab / 时间线分页改 `rememberSaveable`，接入 `rememberLazyListState` |
| `app/src/main/java/dev/popsicle/privprobe/ui/screens/CallerScreen.kt` | 接入 `rememberLazyListState` |
| `app/src/main/java/dev/popsicle/privprobe/ui/screens/TargetScreen.kt` | 接入 `rememberLazyListState` |
| `app/src/main/java/dev/popsicle/privprobe/ui/screens/DashboardScreen.kt` | 接入 `rememberLazyListState`（根页面切 tab 恢复） |
| `app/src/main/java/dev/popsicle/privprobe/ui/screens/SettingsScreen.kt` | 接入 `rememberLazyListState`（根页面切 tab 恢复） |
| `app/src/test/java/dev/popsicle/privprobe/ui/nav/ProbeNavigatorTest.kt` | 新增 7 项页面 id 契约测试（唯一、稳定、不复用、根 id 不参与清理） |
| `app/build.gradle.kts`、`CHANGELOG.md` | 版本 1.2.1 → 1.2.3（与采集口径修复一起发布）与变更记录 |

## 验证方式

- 单元测试：`./scripts/build-app.sh :app:testDebugUnitTest`，33 项全部通过（含新增 7 项 `ProbeNavigatorTest`）。
- 静态检查与构建：`./scripts/build-app.sh :app:lintDebug :app:assembleDebug` 通过（lint 0 errors）；
  `:app:assembleRelease` 通过。
- 真机回归（Xiaomi 17 Pro Max / HyperOS 4 / Android 17）：
  1. 网络与 DNS → 目标 tab → 滚动 → 目标详情 → 返回：返回前后截图逐像素对比平均差异 ≈ 0.01（仅状态栏网络速率读数变化）；
  2. 调用者 tab → 滚动 → 调用者详情 → 返回：同样恢复；调用者详情 → 目标详情 → 返回调用者：滚动位置一致；
  3. 总览滚动后进详情返回、总览 ↔ 配置来回切换：滚动位置保留（列表顶部有新事件插入时内容随索引前移，属实时数据正常行为）；
  4. 返回总览后重新打开同一大类：从顶部开始、分段 tab 回到「调用者」，确认已弹出页面的状态被清理。

*创建于: 2026-11-06*
