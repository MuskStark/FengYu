# 01 · UI 设计系统

> **定位：** 本文档是 FengYu 用户界面的**根本大法**——每一屏、每个组件、每个插件
> 都必须遵循的设计哲学与贯穿性原则。它**不复述**具体的颜色取值（那些在
> [05 主题与色彩系统](05-theme-color-system.md)）或组件级 CSS（那些在
> [03 组件库](03-component-library.md)）。它定义的是 UI *为什么* 是这个样子：布局原语、
> 字号/间距/圆角刻度，以及 FengYu 如何在其模仿的 JetBrains IDEA 2025 **New UI** 语言
> 之上做扩展。

| | |
|---|---|
| **文档类型** | 哲学 + 全局布局 + 刻度（顶层入口） |
| **读者** | 任何接触 UI 的人——设计师、插件作者、AI 代码生成器 |
| **窗口源码** | [`FengYu/src/main/java/fan/summer/app/FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java) · [`ui/MainWindow.java`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java) |
| **参考规范** | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| **相关文档** | [02 JavaFX 实现](02-javafx-implementation.md) · [03 组件库](03-component-library.md) · [05 主题与色彩系统](05-theme-color-system.md) · [06 图标系统](06-icon-system.md) · [07 动效](07-animation-guidelines.md) |

---

## 目录

1. [概览](#1-概览)
2. [设计原则](#2-设计原则)
3. [规格表 / 布局](#3-规格表--布局)
   - [3.1 全局布局](#全局布局)
   - [3.2 字体排印](#字体排印)
   - [3.3 间距栅格](#间距栅格)
   - [3.4 圆角刻度](#圆角刻度)
   - [3.5 投影与高度](#投影与高度)
   - [3.6 信息层级](#信息层级)
4. [与 IDEA New UI 的差异](#4-与-idea-new-ui-的差异)
5. [AI 开发清单](#5-ai-开发清单)
6. [反模式](#6-反模式)
7. [参考](#7-参考)

---

## 1. 概览

FengYu 是一个基于 JavaFX 21 构建的**插件化桌面工具箱**。它的界面是对 **JetBrains
IntelliJ IDEA 2025 "New UI"** 视觉语言的刻意且忠实的实现：中性灰的表面、克制的单一强调
色、扁平的形状，以及服务于反馈而非炫技的动效。

本文档是 UI 文档树的顶端。先在这里读*哲学*，再进入更深的参考去看*机制*：

- 想**开始写**插件 UI → [02 JavaFX 实现](02-javafx-implementation.md)
- 想要**精确颜色取值** → [05 主题与色彩系统](05-theme-color-system.md)
- 想找**某个具体控件** → [03 组件库](03-component-library.md)
- 关于**图标** → [06 图标系统](06-icon-system.md)
- 关于**动效** → [07 动效指南](07-animation-guidelines.md)

### 单一事实源约定

UI 文档集在设计上就避免重复。每一类事实只住在一个地方，其它地方都*链接*过去：

| 事实 | 权威文档 |
|---|---|
| Token 十六进制值与对比度比 | [05](05-theme-color-system.md#token-reference-table) |
| CSS 类名与命名约定 | [02](02-javafx-implementation.md#css-naming) / [03](03-component-library.md) |
| 图标名、尺寸、`IconStyle` 颜色 | [06](06-icon-system.md#icon-reference) |
| 动画时长与缓动 | [07](07-animation-guidelines.md) |
| 组件 CSS 与状态 | [03](03-component-library.md) |

如果你发现自己在某个新屏或插件里复述上述任何内容，停下，改成链接。

---

## 2. 设计原则

四条不可妥协的原则。FengYu 的每一个布局决策都源自其中之一。

### P1 — 功能优先

FengYu 是一个**工具箱**，不是展示橱窗。UI 存在的目的是让用户到达某个工具并让它干活。
无助于理解或反馈的装饰没有位置。

- **应该**以内容（工具网格）为先，保持外框极简，让最常见的动作（启动工具）一次点击即可。
- **不应该**添加不说明状态的视觉效果、插画或动画。工具卡片在运行时脉动是反馈；卡片在空闲
  时闪烁是噪音。

### P2 — 克制的 IDEA New UI 美学

New UI 的决定性特征是**克制**：到处是中性灰、扁平表面、以及一个被"手术刀式"使用的强调
色（`#3574F0`，即 `-sk-accent` token）。颜色理据见
[05 的 P1](05-theme-color-system.md#2-design-principles)。

- **应该**让灰色表面和字体排印承载设计。把强调色留给主要操作、焦点环和**选中指示器**
  （活动侧边栏项左侧的 3 px 竖条——见 [下方 P3](#p3--深浅主题对等) 与
  [03 · NavItem](03-component-library.md)）。
- **不应该**用强调色铺满表面、大面积刷蓝、或叠放彩色面板。New UI 是一个带蓝色标点的灰色 UI。

### P3 — 深浅主题对等

每一屏都必须在**两种**主题下都看起来是刻意的、并通过无障碍检验。没有"主"主题——深色和
浅色都是一等公民。这在结构上被强制执行：颜色绝不被硬编码，只作为 `-sk-*` token 引用，
因此
[`ThemeService.set(Theme)`](../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java)
能零闪烁切换主题。

- **应该**用 `-sk-*` token 或 `.sk-t*`/`.sk-surface*` 工具类给每个节点上色，并在两种主题下
  通过[对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)验证对比度。
- **不应该**写 `setStyle("-fx-background-color: #2B2B2B")`。这个值被冻死了，用户一切主题
  它就是错的。这是最常见的单一 UI bug。

### P4 — 插件原生融合

丢进 `.fengyu/plugin/` 的第三方插件，必须在视觉上与内置工具无法区分。同样的 `.sk-*`
基础组件、同样的 token、同样的字体和图标，都通过
[`Themes.applyTo(scene)`](02-javafx-implementation.md) 提供给每个插件。不存在"插件长相"。

- **应该**用 `.sk-*` 基础类和 `-sk-*` token 构建插件 UI——就和那 11 个内置工具一模一样。
- **不应该**给插件配一套自带的调色板、自定义字体或不同形状的按钮。如果基础组件覆盖不了
  某个需求，优先扩展它们，而不是发明一套并行的视觉语言。

---

## 3. 规格表 / 布局

<span id="全局布局"></span>

### 3.1 全局布局

主窗口是一个经典的 IDE 外壳：原生 OS 标题栏、左侧 **Sidebar**、中间 **ContentArea**、
底部 **StatusBar**。这里采用与 [`docs/architecture.md`](../architecture.md) 一致的 ASCII
画法：

```
┌──────────────────────────────────────────────────────────────────┐
│  原生 OS 标题栏  (StageStyle.DECORATED —— 非自定义 chrome)        │
├────────────┬─────────────────────────────────────────────────────┤
│            │  ContentArea                                        │
│  Sidebar   │  ┌───────────────────────────────────────────────┐  │
│  (导航、    │  │ 搜索栏 (⌘K)                                    │  │
│  分类、     │  ├───────────────────────────────────────────────┤  │
│  收藏、     │  │                                               │  │
│  主题开关)  │  │   工具网格 (ToolCard 的 FlowPane)             │  │
│            │  │   —— 或 —— 一个缓存的插件视图 (showPage)       │  │
│  展开约     │  │   —— 或 —— 详情/启动面板                       │  │
│  56 px     │  │                                               │  │
│  /可收起    │  └───────────────────────────────────────────────┘  │
│            │                                                     │
│            │              DetailPanel (右侧，300 ms 滑入)         │
├────────────┴─────────────────────────────────────────────────────┤
│  StatusBar (等宽时钟 · 状态点 · 状态文本 · 28 px)                  │
└──────────────────────────────────────────────────────────────────┘
```

**窗口事实**（已核对
[`FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java)）：

| 属性 | 值 | 出处 |
|---|---|---|
| 初始场景尺寸 | **960 × 620** | `FengYuApp.java:113` |
| 最小窗口尺寸 | **800 × 520** | `FengYuApp.java:137–138` |
| 窗口 chrome | **原生**（`StageStyle.DECORATED`） | `FengYuApp.java:133` |
| 标题 | `FengYu` | `FengYuApp.java:134` |
| 布局根 | `BorderPane`（top = 无，left = Sidebar，center = ContentArea，bottom = StatusBar） | `MainWindow.java` |

**区域职责：**

| 区域 | 组件 | 用途 | 源码 |
|---|---|---|---|
| 左 | [`Sidebar`](../../FengYu/src/main/java/fan/summer/ui/sidebar/Sidebar.java) | 导航：分类 (DEV/TEXT/IMAGE/NET/OTHER)、AI、Plugins、Favorites、Settings；主题开关在底部。可收起（以 `sidebar.collapsed` 持久化）。 | `ui/sidebar/Sidebar.java` |
| 中 | [`ContentArea`](../../FengYu/src/main/java/fan/summer/ui/content/ContentArea.java) | 搜索栏 + 工具网格（`ToolCard` 的 FlowPane）+ 缓存的插件页。`showPage(node, title)` 用 220/180 ms 交叉淡入切换内容。 | `ui/content/ContentArea.java` |
| 右（覆盖层） | [`DetailPanel`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) | 为悬停/选中的工具滑入（300 ms）的启动面板——图标、名称、描述、启动按钮。 | `ui/content/DetailPanel.java` |
| 底 | StatusBar（位于 [`MainWindow`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java)） | 等宽时钟、脉动状态点（2500 ms）、状态文本。高 28 px。 | `ui/MainWindow.java` |

<span id="字体排印"></span>

### 3.2 字体排印

**字体栈**（全局应用，绝不由组件覆盖）：

```
"SF Pro Text", "Inter", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif
```

该栈会解析为平台最佳的原生 UI 字体（macOS 上 SF、Windows 上 Segoe、CJK 用 PingFang/YaHei），
并优雅降级。**永远不要**设置不同的 `-fx-font-family`——正是这个字体栈让 UI 在每个 OS 上都
有原生质感。

**字号刻度。** FengYu 采用一套围绕 **13 px 基准**的、适合 IDE 的紧凑刻度。应用里的每
个字号都映射到其中之一：

| 字号 | 代号 | 用于 | 出处示例 |
|---|---|---|---|
| 11 px | 微 | 区块标题、眉标 | `.section-title` |
| 12 px | 说明 | 状态栏文本、次要元数据 | `.status-text`（等宽） |
| 13 px | **正文**（基准） | 工具名、按钮标签、正文、导航标签 | `.tool-name`、`.sk-btn-*` |
| 13.5 px | 正文大 | AI 聊天消息文本 | `.ai-msg-text` |
| 15 px | 标题 | 区块头、对话框标题 | `.section-header` |

> **规则：** 不在此表里的字号，就不该出现在 UI 里。挑最近的一档，而不要发明 14 px 或
> 16 px。标题保持小，因为这是 IDE 不是营销页——大字会浪费工具需要的垂直空间。

**字重与颜色。** 默认字重是 regular；强调更常通过**颜色**（把文字从
`-sk-text-secondary` → `-sk-text`）来表达，而非加粗。Bold 留给罕见标题和按下/活动的状态
暗示。见 [§3.6 信息层级](#信息层级)。

<span id="间距栅格"></span>

### 3.3 间距栅格

一个 **4 px 基本单位**统领每一个外边距、内边距和间隙。所有间距都是 4 的倍数（2 的半步仅
用于 1 px 细线和 2/3 px 强调条）。

| 取值 | 用途 |
|---|---|
| 4 px | 紧凑内边距，按钮/chip 内图标到标签的间隙 |
| 8 px | 默认内边距（卡片、输入框），兄弟元素间的小间隙 |
| 12 px | 中等间隙，列表行 / 导航项内边距 |
| 16 px | 区块内边距，卡片列间隙 |
| 20 px | 宽松区块内边距 |
| 24 px | 外层页面内边距，主要区域间隙 |

> **经验法则：** 拿不准就用 8 px。正是这套栅格让密集可扫描的 IDE 布局显得井然。偏离栅格
> 的间距（13 px、7 px）即便用户说不上来，也会读起来像是"坏了"。

<span id="圆角刻度"></span>

### 3.4 圆角刻度

FengYu 使用一套小而一致的圆角集合（CSS `-fx-background-radius`）：

| 圆角 | 用于 | 示例类 |
|---|---|---|
| **999 px**（胶囊 / 药丸） | 搜索栏、AI 输入栏——全圆角的"漂浮"控件 | `.search-bar` |
| **8 px** | 卡片、表格、弹层——"中等表面"圆角 | `.tool-card`、`.sk-table`、弹层 |
| **6 px** | 按钮、输入框、导航项——"控件"圆角 | `.sk-btn-primary`、`.sk-field`、`.nav-item` |
| **10 px** | 对话框、通知——"高架表面"圆角 | `.sk-dialog`、`.sk-notif-*` |

> **形状语言：** 控件 6 px，它们所在的表面 8 px，再往上的模态表面 10 px。药丸形留给
> *漂浮*控件（搜索/AI 输入），以区别于网格内的控件。随意混用圆角会破坏层级。

<span id="投影与高度"></span>

### 3.5 投影与高度

FengYu **默认扁平**。投影是一种稀缺资源，专门留给真正*浮*在内容平面之上的表面——绝不
用来装饰扁平面板。

| 表面 | 是否高架？ | 投影 |
|---|---|---|
| 工具卡片、导航项、输入框、表格 | **否**——扁平 | 无 |
| 详情面板 | 是——在内容上方滑动 | `-sk-shadow`（已 tokenize，见 [05](05-theme-color-system.md)） |
| 对话框（`.sk-dialog`） | 是——模态 | `-sk-shadow` |
| 通知（`.sk-notif-root`） | 是——toast | `-sk-shadow` |

> v3.2.0 把曾经硬编码的黑色投影 tokenize 成了 `-sk-shadow`，使其能在两种主题下正确解析
> （扁平黑影在浅色表面上读起来是错的）。token 见 [05](05-theme-color-system.md)。
> **反模式：** 给卡片或按钮撒 `-fx-effect: dropshadow(...)` 想"让它更跳"。它们不会更跳；
> 只会显得更重。New UI 是扁平的。

<span id="信息层级"></span>

### 3.6 信息层级

文字的显眼程度是一个三档阶梯，完全通过文字 token 表达（见
[05](05-theme-color-system.md#token-reference-table)）：

| 档 | Token | 工具类 | 用途 |
|---|---|---|---|
| 主要 | `-sk-text` | `.sk-t1` | 标题、正文、用户正在读的内容 |
| 次要 | `-sk-text-secondary` | `.sk-t2` / `.sk-fill-2` | 说明、标签、支撑性元数据 |
| 禁用 / 提示 | `-sk-text-disabled` | `.sk-t3` / `.sk-fill-3` | 占位符、禁用控件、不可操作的信息 |

> **用晋升，而非加粗。** 要引起注意，把文字提升一档（次要 → 主要），或为罕见的操作/选中
> 标签加上 `-sk-accent` 颜色。Bold 留给按下/活动状态。**永远不要**把 `-sk-text-disabled`
> 用在用户需要读的内容上——它的对比度是刻意低于 AA 的（见
> [对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)）；它只服务于用户
> *不可操作*的内容。

---

## 4. 与 IDEA New UI 的差异

FengYu 采纳了 IDEA New UI 的*精神*（中性灰、克制强调色、扁平表面、手术刀式的选中指示
器），但**不是**一个 IDE 的克隆——它是一个*工具箱*。下面的差异是刻意的，定义了产品的身份。

### 刻意保持一致

| 方面 | 与 IDEA New UI 共享 |
|---|---|
| 强调色 | `#3574F0`（`-sk-accent`）——完全相同的品牌蓝 |
| 选中处理 | 中性 `-sk-bg-selected` 填充 + **3 px 左侧强调条**，不是刷蓝 |
| 表面调色板 | 中性灰（`-sk-bg`、`-sk-bg-elevated`、`-sk-bg-hover`、`-sk-bg-selected`） |
| 扁平美学 | 无渐变、无毛玻璃、投影仅用于模态/toast |
| 字体排印 | 小号、IDE 尺寸的无衬线字体，以颜色主导层级 |

### FengYu 特有的增项

| 增项 | 存在原因 | 位置 |
|---|---|---|
| **工具卡片网格** | `ToolCard` 的 FlowPane（152 × 130 px）就是主屏——工具以卡片被视觉化发现，而非菜单树。IDEA 没有对应物；它的"卡片"是设置磁贴。 | `ContentArea`，见 [03 · Tool Card](03-component-library.md) |
| **侧边栏分类区** | 按 `ToolCategory`（DEV / TEXT / IMAGE / NET / OTHER）+ AI、Plugins、Favorites、Settings 分组导航。反映 FengYu 的插件分类法。 | `Sidebar.java` |
| **详情面板** | 一个右侧滑入（300 ms）的面板，预览悬停/选中的工具并提供启动按钮——是 IDEA "Search Everywhere"预览与详情抽屉的混合体。 | `DetailPanel.java` |
| **工具卡片"运行中"脉动** | 当插有任务在跑时卡片脉动（2500 ms），让后台工作可见，无需单独的作业视图。 | `ToolCard.java:106`，见 [07](07-animation-guidelines.md) |
| **通知系统** | `.sk-notif-*` toast（info/success/warning/error）用于工具反馈——IDEA 用它自己的通知 API；FengYu 向插件暴露了一个带主题的对等物。 | 见 [03 · Notification](03-component-library.md) |

### 刻意从 IDEA 中去掉

| 去掉项 | 原因 |
|---|---|
| 多分裂编辑器标签 | FengYu 一次只显示一个工具（`showPage` 交叉淡入）。工具不是文档。 |
| 工具栏 / 工具窗口按钮 | 侧边栏 + 网格就够了；IDE 式工具栏只增外框不增值。 |
| 重型模态工程结构 | FengYu 是扁平的：启动工具，用它，离开。没有工程树。 |

---

## 5. AI 开发清单

为 FengYu（宿主或插件）生成 UI 时，你**必须**：

- [ ] **遵循四条原则**——功能优先、克制强调色、主题对等、插件原生融合。如果某个拟议效果
      不服务于其中任何一条，砍掉它。
- [ ] **使用字号刻度**（11/12/13/13.5/15 px）和全局字体栈——永远不要设不同的
      `-fx-font-family` 或偏离刻度的字号。
- [ ] **使用间距栅格**（4 的倍数；默认 8 px）和圆角刻度（按表面类型 6/8/10/999）。
- [ ] **只用 `-sk-*` token 或 `.sk-t*` / `.sk-surface*` 工具类上色**——绝不内联十六进制。
      见 [05](05-theme-color-system.md)。
- [ ] **保持表面扁平。** 投影只给模态/toast/详情面板；其它一切扁平。
- [ ] **用层级而非加粗表达层级**——`-sk-text` → `-sk-text-secondary` → `-sk-text-disabled`。
      `-sk-accent` 留给操作和选中指示器。
- [ ] **关于组件**，进 [03 组件库](03-component-library.md)；动效进
      [07 动效](07-animation-guidelines.md)；图标进 [06](06-icon-system.md)。

---

## 6. 反模式

| 反模式 | 为什么错 | 应改为 |
|---|---|---|
| **毛玻璃 / 磨砂模糊** | v3.2.0 已废弃；`.glass-*` 类被重命名为 `.sk-*`（见 [New UI 规范 §7](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md)）。模糊读起来像"2018 年的网页"，不像 New UI。 | 扁平的 `-sk-bg-elevated` 表面。 |
| **给卡片/按钮加多余投影** | New UI 是扁平的；投影只增视觉重量不增信息。 | 扁平表面；`-sk-shadow` 留给模态/toast/详情面板。 |
| **刷蓝选中**（把选中行/项整块刷成强调色） | 太吵；强调色是标点不是填涂。 | 中性 `-sk-bg-selected` 填充 + 3 px 左侧 `-sk-accent` 竖条（标志性 New UI 规则）。 |
| **偏离栅格的间距**（7 px、13 px、22 px） | 破坏节奏；UI 即便用户说不清也会感觉"不对"。 | 4 的倍数（默认 8 px）。 |
| **发明字号**（14 px、16 px） | 侵蚀紧凑的 IDE 刻度；标题会膨胀。 | 从 11/12/13/13.5/15 里挑最近一档。 |
| **`setStyle()` 里硬编码十六进制** | 切主题即坏——值被冻死，不会重新解析。 | `-sk-*` token 或 `.sk-t*` / `.sk-surface*` 工具类。 |
| **平行的"插件长相"**（第三方工具用自定义调色板/字体） | 违反 P4——插件必须原生融合。 | 用 `.sk-*` 基础组件构建，和那 11 个内置工具一样。 |

---

## 7. 参考

**源码文件：**
- [`FengYu/src/main/java/fan/summer/app/FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java) —— 窗口尺寸、`StageStyle.DECORATED`、标题
- [`FengYu/src/main/java/fan/summer/ui/MainWindow.java`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java) —— 布局根、StatusBar
- [`ui/sidebar/Sidebar.java`](../../FengYu/src/main/java/fan/summer/ui/sidebar/Sidebar.java) · [`ui/content/ContentArea.java`](../../FengYu/src/main/java/fan/summer/ui/content/ContentArea.java) · [`ui/content/DetailPanel.java`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) · [`ui/content/ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java)

**规范与兄弟文档：**
- [New UI 重设计规范](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) —— 权威设计源
- [`docs/architecture.md`](../architecture.md) —— 模块/布局图（此处匹配了其 ASCII 风格）
- [02 JavaFX 实现](02-javafx-implementation.md) —— 代码手册 + CSS 命名
- [03 组件库](03-component-library.md) —— 组件级规格
- [05 主题与色彩系统](05-theme-color-system.md) —— 精确 token 值 + 对比度矩阵
- [06 图标系统](06-icon-system.md) · [07 动效指南](07-animation-guidelines.md) · [08 无障碍指南](08-accessibility-guide.md)
