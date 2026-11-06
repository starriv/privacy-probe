# PrivacyProbe 视觉设计规范（v2 · 克制版）

> 设计语言参考并改造自 [dashboard-design skill](https://github.com/abdiraimov-otabek/dashboard-design-skill)
> （MIT，作者 Otabek Abdiraimov），本地副本见
> [`design-reference/dashboard-design-skill/`](design-reference/dashboard-design-skill/)。
> 该 skill 面向 Web/SwiftUI 仪表盘，本文件把它翻译成 Android + Jetpack Compose 的落地规则。

## 为什么重构

v1 的 UI「五颜六色、AI 味重」：
11 个大类各有一套彩虹色（粉/橙/紫/黄/青）、深蓝玻璃质感、渐变和脉冲、全局等宽字体、
42sp Hero 大数字、彩色胶囊标签、发光边框。信息没有错，但**颜色在装饰而不是在表达**，
注意力被分散，读者一眼看不到「今天发生了什么」。

v2 只做三件事：**灰度承载结构，单色强调数据，暖色只标需要关注的异常。**

## 设计令牌

所有颜色只在 `ui/theme/Palette.kt` 定义，UI 里不允许出现裸色值。

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `page` | `#F8F8F6` | `#0B0B0D` | 页面背景 |
| `card` | `#FFFFFF` | `#141417` | 卡片 / 面板 |
| `soft` | `#F1F1EF` | `#1C1C20` | hover、分段轨道、图标底 |
| `ink` | `#17171A` | `#F3F3F5` | 主文字 |
| `ink2` | `#48484D` | `#C4C4CA` | 次级文字、未选值 |
| `muted` | `#66666D` | `#8C8C95` | 标签、坐标、时间 |
| `line` | `rgba(0,0,0,.075)` | `rgba(255,255,255,.08)` | 发丝线、占比轨道 |
| `lineStrong` | `rgba(0,0,0,.14)` | `rgba(255,255,255,.16)` | 输入框、描边按钮 |
| `accent` | `#3E63DD` | `#7B96FF` | 数据、选中、链接、主按钮 |
| `onAccent` | `#FFFFFF` | `#0B0B0D` | accent 上的文字 |
| `live` | `#0F7F57` | `#34C48B` | 实时/健康状态点 |
| `warn` | `#C98A00` | `#F6B100` | 需要关注（高风险、异常） |
| `down` | `#D23F45` | `#FF6B70` | 破坏性动作、拒绝/错误 |

规则：
- **一个强调色**。所有柱、线、选中、主按钮用 `accent`；没有第二个数据色。
- 绿色只做「实时/健康」状态点，不做大色块；琥珀只做风险/关注；红只做破坏性/被拒。
- 未选中项、次要比率、历史数据一律用 `muted` / `ink2` / `line`。
- tint 一律用 token + alpha（`accent.copy(alpha=0.06f)`），不引入新颜色。

## 字体与数字

- 系统默认字体（中文用系统字体），不再全局使用 `Monospace`，不使用 1.4sp 字距的「全大写」标签。
- 数字使用 **tabular figures**（`fontFeatureSettings = "tnum"`），保证列对齐。
- 尺度：正文 14sp；标签/说明 12–13sp muted；面板标题 15–16sp semibold；KPI 值 28–32sp semibold；
  列表次数 14sp semibold；时间/来源 11sp muted。删除 42sp Hero 大字。
- 页面中不出现解释性句子；说明放详情/空态，或压缩成 2–4 字名词短语。

## 对齐基准（全局）

- 所有页面内容统一 **16dp 横向内边距**；页面标题、分区标题（隐私大类/最近事件/仓库知识库/采集/关于…）、
  状态行、KPI 卡片、列表卡片必须共用同一条 16dp 左基准线。
- 二级页（有返回）的返回按钮外框左移到 8dp，让 20dp 返回图标的光学左边缘落在 16dp 基准线上；
  标题在返回按钮之后，不强行与内容左对齐（这是二级页的标准结构）。
- 页面骨架 `ProbeScaffold` 的标题栏与 `SectionTitle` 都复用该基准，禁止在内容边距之外再加水平 padding。

## 间距系统

统一按 4dp 栅格，所有页面共用同一套尺度：

| 位置 | 值 | 说明 |
|---|---|---|
| 页面左右安全边距 | **16dp** | Dashboard/大类/调用者/目标/配置/关于 全部一致 |
| 内容顶部（标题栏 56dp 之后） | **2dp** | 一级/二级页一致 |
| 内容底部 | **24dp** | 列表底部呼吸；详情页另加 navigationBarsPadding |
| 卡片内边距 | **16dp** | ProbeCard / 设置卡 / 定义列表 |
| KPI tile 内边距 | **12dp**（横/竖） | 一卡四联的格内边距，分隔线 1dp |
| 分区标题 | **top 20dp / bottom 8dp** | 隐私大类/最近事件/仓库知识库/采集/关于… |
| 大类网格 | **横 12dp / 竖 12dp** | 两列卡片间距 |
| Ranked/事件行 | 行高 44–48dp，内边距 16dp | 行底 3dp ShareBar + 8dp |
| 设置行 | 横 16dp / 竖 12dp | Switch / Action / Status |
| 定义列表行（InfoRow） | 横 16dp / 竖 10dp | 标签固定 88dp |
| 悬浮底部 dock | 左右 16dp；距底部 12dp + navigationBars；高 62dp；全圆角 | 左侧胶囊 tabs + 右侧独立 62dp 圆形主按钮；浅色柔和阴影 + 1px hairline |
| 返回/设置图标 | 外框 8dp，图标光学边缘 16dp | 36dp 点击区，20dp 图标居中 |

**例外（有意为之）**：Chip 5×1dp、SegmentedTabs 轨道 3dp、EmptyState 24/32dp（空态居中呼吸）。
这些例外不再影响任何一级结构线的对齐。

**审查方法**：截图用 PIL 量像素（本机 density 3.0，1dp = 3px）：
页面卡片左边缘应为 48px、右边缘 1152px；标题文本框 48px；分区标题墨迹 48–52px；
底部悬浮 dock 左边缘 48px（16dp），胶囊/圆形按钮高 62dp；所有页面 ±1px 内视为通过。

## 布局与组件

- 间距基数 4dp；卡片内边距 16–20dp；卡片圆角 16dp；内层选中圆角 10–12dp。
- 卡片：1px hairline 边框 + `card` 背景；浅色带极轻阴影，深色无阴影。禁止渐变、发光、彩色描边。
- **KPI 卡片组**：一张卡内 4 个 tile，用竖 hairline 分隔；tile = 标签 12 muted + 值 28–32 semibold
  + 一行 one-word baseline（如「24 小时」「个应用」）；事件 tile 带 24h 极简 sparkline（1.5px 线，无坐标轴）。
- **大类卡片**：2 列网格；中性 32dp 图标方块；名称 14；今日次数 24–26 tabular；行底 3px 占比条
  （track=`line`，fill=`accent` 75%）；top caller 12 muted。风险不在卡片上用大彩标，详情页才用 10sp
  琥珀 outline chip。
- **Ranked list**（调用者/域名/传感器等）：行高 44–48dp；图标 28dp；名称 14；包名/uid 12 muted；
  次数右对齐 tabular；占比 11 muted 固定宽；行底 3px 占比条；行间 hairline。
- **Segmented tabs**：`soft` 轨道 + `card` 选中 pill + hairline 描边；选中文字 `ink`，未选中 `muted`。
- **时间线**：时间 11 muted · target 14 · 来源/置信度 11 muted（用「·」分隔），不用彩色 badge 堆叠。
- **配置**：状态四行（root / 采集 / 探针健康 / 最近错误）+ live dot；开关 accent；
  每个分区标题 12 muted；行高 44–48；`运行自检` 唯一实心 accent 主按钮，`重新检测` outline；
  清空数据仅红色文字。
- **悬浮底部 dock**：左右 16dp、底部 12dp（另加 navigationBars），62dp 高、全圆角；左侧胶囊放两个 tab（总览/配置），card 背景 + 1px hairline、浅色柔和阴影、深色仅 hairline；选中 tab 为 soft 底 + ink 文字/图标；右侧独立 62dp accent 圆形按钮快速启停采集，形成参考图的非对称构图。
- **空态/加载/无 root**：保留页面结构，一行说明 + 一个动作；不使用插画、渐变、emoji。

## 主题

- 跟随系统浅色/深色（`isSystemInDarkTheme()`），两套 palette 都是完整可用的。
- 状态栏/导航栏图标颜色随主题切换（`enableEdgeToEdge` 的 `SystemBarStyle`）。

## 截图 QA（每次改 UI 必做）

1. 浅色 + 深色各截 Dashboard / 大类详情 / 调用者详情 / 配置 / 关于。
2. 一眼测试：先看到「今日事件 + 大类分布」，而不是 logo/装饰/彩色标签。
3. 颜色审计：全屏除 accent / live / warn / down 外没有其它色相；没有渐变/发光。
4. 数字审计：同一数字在各处一致；有「次/个/天」单位；空数据用 `—` 不用 0 或假百分比。
5. 文字审计：屏幕上的解释性句子 = 0；标签都是 1–4 个字。
6. 浅色对比度：muted 在 page/card/soft 上都 ≥4.5:1；深色同样检查。
7. 交互回归：导航、tabs、自检、通知、真实数据刷新、无 FATAL/ANR。

## 与旧版的差异

| v1（已删除） | v2 |
|---|---|
| 11 个大类各一套彩虹色 | 全部中性灰 + 单一 accent |
| 渐变 Hero 卡、发光描边、呼吸脉冲 | 白/深灰卡片 + 发丝线 + 6dp live 点 |
| 全局等宽字体 + 1.4sp 字距 | 系统字体 + tabular 数字 |
| 42sp Hero 数字 | 28–32sp KPI，页面不喊叫 |
| 彩色胶囊/大风险标签 | 小号 amber outline chip（仅详情） |
| 每张卡不同色块 | 同一套卡片样式，靠数字和占比条区分 |
