# Fix: 大类时间线分页条改为「加载更早」

> **日期**: 2026-11-06

## 问题描述

大类详情「时间线」tab 里，「上一页 / 下一页 / 第 x 页 · 保留期内记录」四个元素挤在同一行：没有间距和层级，
文字与按钮各自使用默认样式，和页面 16dp 栅格、muted 标签的设计语言不一致；而且 7 天保留期内网络类可能有
上千条记录（约 85 页），靠「下一页」逐页翻非常难用。另外分页用 OFFSET，时间线持续写入新事件时会出现
翻页重复/跳过。

## 根因分析

- UI：`Row { TextButton(...) TextButton(...) Text(...) }` 直接把分页控件和说明文案堆在一行，没有使用设计
  令牌/间距；`第 x 页` 与 `保留期内记录` 属于无信息量的占位文案。
- 数据：`timelineForCategory(category, limit, offset)` 按 OFFSET 分页，offsets 基于「当前时刻的事件集合」；
  每 2 秒刷新的新事件会把 offsets 整体推移，导致重复或漏事件。
- 刷新：首屏每 2 秒轮询重查当前页；用户翻到历史页后，新事件仍会把当前页内容顶掉。

## 修复方案

### UI：时间线改为游标增量加载

去掉分页条（上一页 / 下一页 / 第 x 页 / 保留期内记录），列表底部改为：

- 还有更早记录时：全宽 `OutlineButton("加载更早")`（点击后显示「加载中…」并禁用）；
- 已到保留期最早记录时：居中 muted 文案「已加载全部 N 条」；
- 顶部 SectionTitle 保持「时间线 · N 条」，N 为当前已加载数量。

首屏 120 条保持每 2 秒刷新；一旦用户点击「加载更早」，冻结首屏刷新，避免历史列表被新事件顶动。
切换 tab 或重新进入页面时回到首屏并恢复实时刷新。

### 数据：按 (ts, id) 游标取页

- 新增 `TimelineCursor(ts, id)` / `TimelinePage(events, nextCursor)` 模型。
- `timelineForCategory(category, cursor, limit)`：`WHERE category=? AND (ts<? OR (ts=? AND id<?)) ORDER BY ts DESC, id DESC LIMIT limit+1`，
  多取一条判断是否还有更早记录；返回本页与下一页游标。同毫秒事件按 id 稳定排序，不会在页边界漏掉。
- `PreviewRepository` 跟随新签名（预览数据只有一页，直接返回 nextCursor=null）。

## 修改文件

| 文件 | 变更 |
|---|---|
| `core/model/Models.kt` | 新增 `TimelineCursor` / `TimelinePage` |
| `core/data/PrivacyRepository.kt` | `timelineForCategory` 改为游标分页 |
| `core/data/impl/SqlitePrivacyRepository.kt` | 新增 `SELECT_TIMELINE`（带 id）与 (ts,id) 游标查询 |
| `ui/preview/PreviewSources.kt` | 跟随新接口 |
| `ui/screens/CategoryScreen.kt` | 删除分页条；新增首屏刷新 / 加载更早两个 effect 与底部 footer |
| `test/.../SqlitePrivacyRepositoryTest.kt` | 更新时间线测试并新增同毫秒游标翻页测试 |
| `CHANGELOG.md` | 1.2.3 记录 |

## 验证

- `:app:testDebugUnitTest`：**39 项全部通过**（含新增的「同毫秒事件游标翻页不重不漏」测试）。
- `:app:lintDebug`：0 errors。
- 真机（Xiaomi 17 Pro Max / Android 17）：
  - 电话与短信大类 → 时间线：首屏「时间线 · 120 条」，底部为全宽「加载更早」，无分页条；
  - 点击「加载更早」后加载到 187 条（页面累计 187），底部变为「已加载全部 187 条」；
  - 滚动位置保持，列表无重复行（游标按 ts+id 翻页）；
  - release 1.2.3 安装后复查：首屏「时间线 · 120 条」、底部「加载更早」、无分页条，R8 构建表现一致。
