# 04 · 交互指南

> **定位：** 本文档规定用户在 SwissKitJ 中**如何导航、发现和执行操作**——是交互流程，而非
> 组件本身。它告诉你：当用户点击一个导航项、悬停卡片、启动工具、卸载插件、或触发破坏性
> 操作时会发生什么。组件见 [03](03-component-library.md)；这些流程触发的动画见
> [07](07-animation-guidelines.md)；键盘流程在 [08](08-accessibility-guide.md) 中为无障碍做了延伸。

| | |
|---|---|
| **文档类型** | 交互流程 + 事件接线模式 |
| **读者** | 插件作者、AI 代码生成器、任何在接线用户操作的人 |
| **源码文件** | [`ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) · [`ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) · [`ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) · [`ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java) · [`ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java) · [`ui/store/PluginStoreUi.java`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java) |
| **通知 API** | [`GlassNotification`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java)（`.sk-notif-*`） |
| **相关文档** | [03 组件库](03-component-library.md) · [06 图标系统](06-icon-system.md) · [07 动效](07-animation-guidelines.md) · [08 无障碍](08-accessibility-guide.md) |

---

## 目录

1. [概览](#1-概览)
2. [设计原则](#2-设计原则)
3. [流程表](#3-流程表)
   - [3.1 导航](#导航)
   - [3.2 工具发现与启动](#工具发现与启动)
   - [3.3 插件生命周期](#插件生命周期)
   - [3.4 插件商店](#插件商店)
   - [3.5 表单与校验](#表单与校验)
   - [3.6 键盘](#键盘)
   - [3.7 四态反馈](#四态反馈)
   - [3.8 破坏性操作](#破坏性操作)
4. [JavaFX 模板](#4-javafx-模板)
5. [AI 清单](#5-ai-清单)
6. [反模式](#6-反模式)
7. [参考](#7-参考)

---

## 1. 概览

SwissKitJ 是一个**工具箱**：用户打开它，找一个工具，用它，然后离开。外壳里的每一个交互
都是为了把这个循环做得又快又宽容。本文档编目了宿主中实际实现的流程（经源码核对），以及
插件作者为了让自己的工具原生融合而复用的事件接线模式。

三件事定义了 SwissKitJ 的交互模型：

1. **默认可发现**——主屏*就是*一个可搜索的工具卡片网格。用户无需先知道工具名就能找到它。
2. **渐进式披露**——悬停/选中的卡片揭示详情面板；启动则把网格替换为工具视图。详情是按需
   的，绝不强塞。
3. **操作可宽容**——破坏性操作（卸载、带着运行中任务离开）先确认；非破坏性导航即时且可逆。
   用户绝不该因一次误点击而丢失工作。

---

## 2. 设计原则

### P1 — 可发现

用户通过浏览卡片和在搜索栏输入来找工具。每个工具都能从主网格一次点击（外加可选的详情窥视）
到达。不要把工具埋进嵌套菜单——网格 + 搜索就是导航。

### P2 — 渐进式披露

先给摘要（卡片 → 名称/描述/标签），在感兴趣时揭示详情（详情面板 → 完整描述 + 启动），在行动
时提交（启动 → 工具视图）。在用户主动要求前，绝不预加载工具的完整 UI。

### P3 — 可宽容

破坏性操作需要二次、明确的确认（见 [`GlassNotification.confirm`](#破坏性操作)）。从运行中
工具离开，要么（若 `hasRunningTasks()`）把它留作后台，要么完全可逆。用户绝不该因误点击丢
失工作。

### P4 — 一致反馈（四态）

每个异步或数据驱动的表面都呈现四态之一——**加载、空、错误、成功**——使用共享组件
（`.sk-notif-*`、`.progress-bar`、`.sk-table` 占位）。空白面板就是 bug。

---

## 3. 流程表

<span id="导航"></span>

### 3.1 导航

左侧 **Sidebar** 是主导航。它按 `ToolCategory`（DEV / TEXT / IMAGE / NET / OTHER）分组，外加
AI 聊天、插件商店、收藏和设置。主题开关在底部。

| 操作 | 发生什么 | 持久化 / 源码 |
|---|---|---|
| 点击分类导航项 | 把工具网格过滤到该分类；该项变为活动（`-sk-bg-selected` + 3 px 左侧 `-sk-accent` 竖条 + 160 ms 缩放弹出）。 | `Sidebar.setOnCategorySelect` → `ContentArea` |
| 点击 AI / Plugins / Settings | 通过 `contentArea.showPage(view, title)` 导航到该页（220/180 ms 交叉淡入）。 | `MainWindow` |
| 收起 / 展开侧边栏 | 侧边栏宽度动画；状态持久化在设置键 **`sidebar.collapsed`** = `"true"`/`"false"`。 | `Sidebar.java:292`，在 `:323` 恢复 |
| 切换主题（底部） | `ThemeService.set(Theme)` 在场景根上**即时**替换 `.theme-dark`↔`.theme-light`（无动画）；持久化在设置键 `"theme"`。 | [05 主题与色彩系统](05-theme-color-system.md) |

> **侧边栏折叠是唯一持久化的布局状态。** 不要持久化随意的窗口尺寸或面板位置；应用的布局是
> 确定性的。

<span id="工具发现与启动"></span>

### 3.2 工具发现与启动

核心循环。每一步都有刻意的动效（引用自 [07](07-animation-guidelines.md)）：

```
┌────────────────┐    输入      ┌──────────────────┐  悬停   ┌─────────────────────┐
│  主网格        │ ──────────▶  │  过滤后网格      │ ──────▶ │  DetailPanel        │
│  (全部卡片)    │  实时过滤    │  (搜索匹配)      │  150 ms │  滑入 (300 ms)       │
│                │              │                  │  缩放   │  图标+描述+启动      │
└────────────────┘              └──────────────────┘         └─────────┬───────────┘
       ▲                                                              │ 点击启动
       │                                       220/180 ms             ▼
       │  返回 (Esc / 返回按钮) ◀──────── 交叉淡入 ◀─────────┌──────────────┐
       │                                                      │  插件视图     │
       │  若 hasRunningTasks() → 保持缓存 (后台)                │  (已缓存)     │
       │  否则 → 驱逐缓存                                       └──────────────┘
└─────────────────────────────────────────────────────────────────────────┘
```

| 步骤 | 触发 | 效果 | 动画（见 [07](07-animation-guidelines.md)） |
|---|---|---|---|
| 搜索 | 在搜索栏输入 | 网格实时再过滤；匹配的卡片错峰进入 | 错峰入场 240 ms + 每卡 35 ms（上限约 30） |
| 卡片悬停 | 鼠标进入 `ToolCard` | 卡片轻微放大；详情面板在右侧滑入 | 悬停缩放 150 ms；DetailPanel 滑入 **300 ms** |
| 启动 | 点击卡片（或详情启动按钮） | 卡片挤压，然后插件视图交叉淡入 | 点击缩放 100 ms（自动反转）；页面交叉淡入 **220 ms 入 / 180 ms 出** |
| 返回 | 返回按钮 / Esc | 回到网格；交叉淡入返回 | 交叉淡入 220/180 ms |

**视图缓存。** `MainWindow` 把每个插件的 `createView()` 结果缓存在 `cachedViews` map 里。
第二次启动某工具会复用缓存的 `Node`——**不会**再次调用 `createView()`。缓存仅在**插件报告**
`!hasRunningTasks()` 时于返回时驱逐；有后台工作的工具保持缓存，以便用户回到它。

> ⌘K 在搜索栏以键盘提示（`.search-kbd` 标签 `⌘K`）形式展示；搜索字段在用户输入时实时过滤
> 网格。

<span id="插件生命周期"></span>

### 3.3 插件生命周期

插件在 `activate → (foreground/background) → deactivate` 间流转，外加 `uninstall`。宿主在
[`MainWindow`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) 中接线：

| 转换 | 触发 | 效果 | 源码 |
|---|---|---|---|
| **激活** | 启动某工具 | `registry.activate(plugin)`；视图通过 `showPage` 展示。 | `MainWindow` 启动回调 |
| **前台 / 后台** | 应用获焦 / 最小化或用户离开 | 在插件上触发 `onForeground` / `onBackground` 钩子（若它覆盖了）。 | `SwissKitJPlugin` 默认 |
| **去活（返回）** | 返回按钮 / Esc | 若 `hasRunningTasks()` → **保持缓存**（后台）；否则驱逐缓存视图并 `registry.deactivate()`。 | `contentArea.setOnBack` |
| **运行指示** | 插件报告 `hasRunningTasks()` | 其 `ToolCard` 显示带 **2500 ms 脉动**的运行点，让后台工作在网格里可见。 | `ToolCard.java:106` |
| **卸载** | 详情面板 → 卸载按钮 | **先确认**（见 [§3.8](#破坏性操作)）；确认后：驱逐缓存，若活动则去活，导航到网格，`loader.uninstallPlugin(plugin)`。 | `DetailPanel.showUninstallConfirm` → `contentArea.setOnUninstall` |

> `hasRunningTasks()` 契约正是"可宽容"得以成立的关键：用户可以从一个正在干活的工具点返回，
> 而不会杀掉它——它只是退到后台、在卡片上继续脉动。任何做异步工作的插件都应如实覆盖
> `hasRunningTasks()`。

<span id="插件商店"></span>

### 3.4 插件商店

安装插件是一个带可见进度的前台异步任务，由
[`PluginStoreUi`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java)（在线
页）和 `LocalInstallPane`（本地 JAR 安装）处理：

| 步骤 | 效果 |
|---|---|
| 浏览 | 在线商店列出可用插件（远程拉取）。 |
| 安装 | 通过 `.progress-bar` 显示进度；成功/失败通过 `GlassNotification` toast 反馈。 |
| 切到本地已安装页 | 商店 UI 在在线与本地已安装视图间切换。 |
| 安装后 | 插件在下次刷新时出现在侧边栏/网格的对应分类里。 |

<span id="表单与校验"></span>

### 3.5 表单与校验

SwissKitJ 表单（由 `.sk-field` / `.sk-combo` / `.sk-checkbox` 构建）遵循保守的校验时机：

| 方面 | 规则 |
|---|---|
| **何时校验** | 在 **失焦** 或 **提交** 时，而非每次按键。桌面工具里逐键校验很烦人。 |
| **错误信息位置** | 紧贴在出错字段**下方**，用 `-sk-danger` 文本（字段下的标签，不要弹窗）。 |
| **提交反馈** | 异步工作期间禁用提交按钮；结果出来用 `GlassNotification` toast（成功/信息/警告/错误）。 |
| **必填字段** | 用 `-sk-danger` 星号或清晰标签标记；绝不仅靠颜色（见 [08](08-accessibility-guide.md)）。 |

<span id="键盘"></span>

### 3.6 键盘

| 键 | 动作 | 源码 |
|---|---|---|
| **Esc** | 关闭聚焦的对话框/面板（如 `AboutDialog` 在 Esc 时关闭）；在插件视图里返回网格。 | `AboutDialog.java:53` |
| **Tab / Shift+Tab** | 按 DOM/逻辑顺序在控件间移动焦点；焦点环必须可见（见 [08](08-accessibility-guide.md)）。 | 默认 |
| **Enter / Space** | 激活聚焦的按钮/卡片。 | 默认 |
| **⌘K（展示）** | 作为搜索栏快捷键提示展示；搜索字段是实时过滤器。 | `.search-kbd` 标签 |

> 凡是鼠标能到达的操作，键盘都必须能到达——这既是交互原则，也是无障碍要求（在
> [08](08-accessibility-guide.md) 中延伸）。

<span id="四态反馈"></span>

### 3.7 四态反馈

任何加载数据或跑异步工作的表面都只显示这四态之一，使用共享组件：

| 状态 | 何时 | 组件 |
|---|---|---|
| **加载** | 数据正在拉取 / 工作正在跑 | `.progress-bar`（6 px，`-sk-accent` 填充）和/或 ToolCard 运行脉动 |
| **空** | 查询无结果 / 无内容可显示 | `.sk-table` 占位文本（`-sk-text-disabled`）或居中标签的空态文案 |
| **错误** | 加载/工作失败 | `GlassNotification.notify(WARNING/ERROR, ...)` + 出错处附近的内联 `-sk-danger` 信息 |
| **成功** | 工作完成 | `GlassNotification.toast(SUCCESS, ...)` |

> **反模式：** 加载时显示空白面板。哪怕一个禁用的 `.progress-bar` 或一行
> `-sk-text-disabled` 的"加载中…"都好过什么都没有——它告诉用户应用没卡死。

<span id="破坏性操作"></span>

### 3.8 破坏性操作

破坏性操作（卸载、覆盖、清空）需要通过共享模态进行**二次、明确确认**：

```java
// 规范确认 — DetailPanel.showUninstallConfirm (DetailPanel.java:303)
boolean confirmed = GlassNotification.confirm(this, title, message);
if (confirmed) {
    doUninstall();
}
```

| 规则 | 细节 |
|---|---|
| **必须确认** | `GlassNotification.confirm(context, title, message)` 阻塞等是/否；绝不未经确认就删除/卸载。 |
| **清晰、不可逆的文案** | 信息必须说明*会发生什么*以及不可逆（如卸载信息会点名插件）。破坏性动词在合适处用 `-sk-danger`。 |
| **默认取消** | 安全选项是默认；用户必须明确选择才继续。 |
| **可逆 ≠ 破坏性** | 隐藏面板、切设置、导航离开——这些都无需确认。把对话框留给数据丢失。 |

---

## 4. JavaFX 模板

### 4.1 侧边栏 → 分类选择接线

```java
// MainWindow 接线侧边栏的分类回调
sidebar.setOnCategorySelect(categoryId -> {
    contentArea.filterByCategory(categoryId);   // 重新过滤工具网格
});
```

### 4.2 卡片点击 → 详情面板 → 启动 → 缓存页

```java
// ToolCard 在点击挤压之后再触发 onSelect (ToolCard.java:156–159)
setOnMouseClicked(e -> {
    ScaleTransition click = new ScaleTransition(Duration.millis(100), this);
    click.setAutoReverse(true); click.setCycleCount(2);
    click.setOnFinished(ev -> onSelect.accept(plugin));   // 交给 ContentArea
    click.play();
});

// ContentArea.onCardSelect → 显示 DetailPanel (滑入 300 ms)，
// 其启动按钮调用 contentArea.showPage(cachedView, name)。
```

### 4.3 带视图缓存 + hasRunningTasks 感知返回的启动

```java
// MainWindow 启动回调（源码意译）
Node view = cachedViews.get(plugin);
if (view == null) {
    view = plugin.createView();
    PluginContext.wrapEvents(plugin, view);
    cachedViews.put(plugin, view);            // 缓存：createView() 只跑一次
}
registry.activate(plugin);
contentArea.showPage(view, plugin.getName());

// 返回回调 —— 保持运行中工具缓存
contentArea.setOnBack(() -> {
    SwissKitJPlugin current = registry.getActivePlugin();
    if (current != null && !current.hasRunningTasks()) {
        cachedViews.remove(current);          // 仅在空闲时驱逐
    }
    registry.deactivate();
});
```

### 4.4 主题变更再渲染监听

```java
// 给无法自动跟随 looked-up color 的自定义渲染 (WebView/canvas)
ThemeService.onChange(theme -> Platform.runLater(() -> {
    myWebView.getEngine().reload();           // 用主题感知 CSS 重新渲染
}));
```

### 4.5 确认对话框 + 通知 toast

```java
// 破坏性确认
if (GlassNotification.confirm(view, I18n.get("detail.uninstall.confirmTitle"), msg)) {
    doUninstall();
}

// 成功 / 信息 / 警告反馈
GlassNotification.toast(view, GlassNotification.Type.SUCCESS, I18n.get("msg.saved"));
GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("setting.urlEmpty"));
```

> **通知 API**（[`GlassNotification`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java)）：
> `toast(owner, type, message)`、`notify(owner, type, [title,] message)`、
> `confirm(owner, title, message) → boolean`。`Type` ∈ `INFO/SUCCESS/WARNING/ERROR` 映射到
> `.sk-notif-info/-success/-warning/-error`。

---

## 5. AI 清单

在 SwissKitJ（宿主或插件）中接线交互时，你**必须**：

- [ ] **缓存视图**——`createView()` 只跑一次；复用该 `Node`。绝不在每次激活时重建。
- [ ] **页面切换用交叉淡入**——用 `showPage`（220/180 ms），不要硬替换节点。
- [ ] **破坏性操作要确认**——卸载/删除前先 `GlassNotification.confirm(...)`。
- [ ] **显示四态之一**——加载/空/错误/成功，绝不空白。
- [ ] **接好 Esc**——对话框/面板在 Esc 时关闭；插件视图退回网格。
- [ ] **持久化侧边栏折叠**用设置键 `sidebar.collapsed`，主题用 `"theme"`。
- [ ] **如实报告 `hasRunningTasks()`**，让后台工作能在返回后存活。
- [ ] **用 `Themes.applyTo`/`ThemeService.onChange`** 处理自定义渲染表面——绝不假设主题；变更
      时重新渲染。
- [ ] **每个操作都能键盘到达**——不止鼠标（见 [08](08-accessibility-guide.md)）。

---

## 6. 反模式

| 反模式 | 为什么错 | 应改为 |
|---|---|---|
| **每次激活都重建视图** | 浪费工作、丢失用户状态、破坏缓存。 | 缓存 `createView()`；复用 `Node`。 |
| **没有空/错误态** | 空白面板看起来像卡死。 | 通过四态组件显示加载/空/错误文案。 |
| **破坏性操作不确认** | 一次误点击丢数据。 | 先 `GlassNotification.confirm(...)`；默认取消。 |
| **异步操作阻塞 FX 线程** | 冻结整个 UI。 | 异步工作移出 FX 线程；通过 `Platform.runLater` 更新 UI。 |
| **硬替换页面** | 突兀；丢失位置感。 | `showPage` 交叉淡入（220/180 ms）。 |
| **`hasRunningTasks()` 撒谎** | 返回会驱逐运行中工具的视图 → 丢工作。 | 工作进行中就返回 `true`。 |
| **每次按键都校验** | 烦人；和用户正在输入打架。 | 在失焦/提交时校验。 |

---

## 7. 参考

**源码文件：**
- [`ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) — 启动/返回/卸载接线，视图缓存
- [`ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) — 分类选择，`sidebar.collapsed` 持久化
- [`ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) — 搜索、`showPage`/`crossFadeTo`、网格过滤
- [`ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java) — `onSelect` 回调、运行脉动
- [`ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java) — 滑入、`showUninstallConfirm`
- [`ui/store/PluginStoreUi.java`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java) · [`ui/setting/SwissKitJSettingUi.java`](../../SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java)
- [`GlassNotification.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java) — toast/notify/confirm API

**兄弟文档：**
- [03 组件库](03-component-library.md) — 这些流程所用组件
- [06 图标系统](06-icon-system.md) · [07 动效指南](07-animation-guidelines.md) — 全文引用的动效
- [08 无障碍指南](08-accessibility-guide.md) — 键盘流程 + 减弱动效
