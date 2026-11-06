# 隐私探针 UI 截图（真机 · v1.1.0）

设备：Xiaomi 17 Pro Max / HyperOS 4（OS4.0.5.0.XPBCNXM）/ Android 17（SDK 37），KernelSU 越狱 root。
App：`dev.popsicle.privprobe` **1.1.0**，release 侧载（非 debuggable）。
截图：`adb shell screencap -p` 原图 1200×2608，未裁剪未修图；浅色/深色分别用 `cmd uimode night no/yes` 切换后冷启动 Activity 得到。
数据：真实采集链路（SQLite + 8 个系统探针 + privd），截图时约 **14,100 条今日事件 / 62 个应用 / 239 个域名**；底部为「胶囊 tabs + 独立圆形按钮」悬浮 dock。

## 九宫格展示

README 中的九宫格由下方 9 张**原始截图**通过 HTML 表格直接引用（3×3），未做任何裁切、缩放或合成；
每个 `<img>` 链接指向原始 PNG，点击可查看 1200×2608 原图。

## 截图索引

| 浅色 | 深色 |
|---|---|
| `ui-dashboard-light.png` | `ui-dashboard-dark.png` |
| `ui-category-network-light.png` | `ui-category-network-dark.png` |
| `ui-caller-light.png` | `ui-caller-dark.png` |
| `ui-target-light.png` | `ui-target-dark.png` |
| `ui-settings-light.png` | `ui-settings-dark.png` |
| `ui-settings-about-light.png` | `ui-settings-about-dark.png` |

## 主题说明

应用主题由 `isSystemInDarkTheme()` 驱动，**跟随系统**。验收时发现设备 HyperOS 的 UiMode auto
状态卡在 night（`mComputedNightMode=true`，但系统时间已是白天）；通过 `cmd uimode night no`
→ `auto`重新计算后恢复浅色，App 随即变浅色，确认不是 App 写死主题。
截图完成后系统已恢复 `cmd uimode night auto`。

底部导航为**非对称悬浮 dock**：左侧 62dp 高胶囊 tabs（左右 16dp、底 12dp + navigationBars、全圆角、浅色柔和阴影），右侧独立 62dp 圆形 accent 按钮快速启停采集；选中 tab 为 soft 底 + ink 文字。

## 设计基线（v1.1.0）

- 浅色：page `#F8F8F6`、card `#FFFFFF`、soft `#F1F1EF`、ink `#17171A`、muted `#66666D`、hairline `rgba(0,0,0,.075)`、accent `#3E63DD`
- 深色：page `#0B0B0D`、card `#141417`、soft `#1C1C20`、ink `#F3F3F5`、muted `#8C8C95`、hairline `rgba(255,255,255,.08)`、accent `#7B96FF`
- 单一 accent；绿仅实时、琥珀仅需关注；无渐变/彩虹/常驻动画（LiveDot 为静态圆点）。
- 间距：页面/卡片左右 16dp，标题 16dp；二级页返回图标光学边缘 16dp；KPI 12dp；网格 gap 12dp；悬浮 dock 左右 16dp / 底 12dp（详见 `docs/DESIGN.md`）。

## 像素基准（density 3.0 → 1dp=3px）

卡片/列表左右 48px / 1152px；标题文字框 48px；CJK 字形 bearing +1–2px；悬浮 dock 左 48px；胶囊/FAB 高 186px；二级页返回箭头
ink 约 +3.3dp（Material icon path 内边距）；允许 ±1px 偏差。
