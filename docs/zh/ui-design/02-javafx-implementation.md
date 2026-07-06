# 02 · JavaFX 实现指南

> **定位:** 本文是把 ZhiFlow UI 设计**转化为可运行 JavaFX 代码**的开发者 / AI 操作手册。
> 它定义了必须实现的插件契约、所有节点都遵守的 CSS 类名命名规范,以及可复制粘贴的插件骨架。
> 后续文档(尤其是 [03 组件库](../ui-design/03-component-library.md))会回链本文的
> [`#css-naming`](#css-naming) 命名规范与 [`#plugin-skeleton`](#plugin-skeleton) 模板。

| | |
|---|---|
| **文档类型** | 插件契约 + 实现模式 |
| **目标读者** | 插件作者、AI 代码生成器、任何构建 ZhiFlow 工具的人 |
| **事实来源** | [`ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| **配套插件指南** | [`docs/plugins/ui.md`](../plugins/ui.md)(双语,布局陷阱 + StepWizard) |
| **相关** | [01 设计系统](01-design-system.md) · [05 主题与色彩系统](05-theme-color-system.md) · [06 图标系统](06-icon-system.md) |

> **翻译说明:** 本文件是英文版 [`docs/ui-design/02-javafx-implementation.md`](../ui-design/02-javafx-implementation.md)
> 的中文镜像。叙述文字已翻译;**所有代码、类名、方法签名、文件路径、CSS 类名、占位符均逐字保留**,
> 与英文版完全一致。代码块不做翻译。

---

## 目录

1. [概述](#1-概述)
2. [设计原则](#2-设计原则)
3. [规格表](#3-规格表)
   - [3.1 `SwissKitJPlugin` 方法契约](#zhiflowjplugin-方法契约)
   - [3.2 CSS 类命名规范](#css-naming)
   - [3.3 布局容器选型指南](#布局容器选型指南)
4. [JavaFX 实现模板](#4-javafx-实现模板)
   - [4.1 插件骨架](#plugin-skeleton)
   - [4.2 图标 · `MdiIconUtil`](#42-图标--mdiiconutil)
   - [4.3 为独立 Stage 应用主题 · `Themes.applyTo`](#43-为独立-stage-应用主题--themesapplyto)
   - [4.4 国际化(i18n)模式](#44-国际化i18n模式)
   - [4.5 三大布局陷阱](#45-三大布局陷阱)
5. [AI 开发检查清单](#5-ai-开发检查清单)
6. [反模式](#6-反模式)
7. [参考资料](#7-参考资料)

---

## 1. 概述

ZhiFlow 是一个 **JavaFX 21** 桌面工具箱。两个架构决策决定了本文的一切:

1. **UI 完全用 Java 代码构建——没有 FXML。** 每个界面都在 `createView()` 中由
   `javafx.scene.*` 节点拼装而成。没有 `.fxml` 文件、没有 `FXMLLoader`、没有控制器装配。
   这让插件保持自包含、易于重构、依赖极轻(一个外部插件 JAR 的 classpath 只需 `ZhiFlow-Api`)。

2. **主题完全通过 CSS looked-up color 实现。** 没有任何节点内联设置颜色。双主题(深色/浅色)调色板
   是一组 14 个 `-sk-*` token,一次性声明在
   [`zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) 中,
   通过切换 scene root 上的一个 class 来切换主题。Token 取值、对比度矩阵以及完整的主题生命周期见
   [05 主题与色彩系统](05-theme-color-system.md)——**本文不重复任何颜色取值。**

> **本文的用途。** 一个已经读过设计文档(01、03、06)的人或 AI 会问:
> *"我知道 JSON 格式化工具长什么样了——我怎么把它变成一个可编译、主题正确、能被宿主加载的插件?"*
> 本文从头到尾回答这个问题:你要实现的接口、必须/可选覆盖的方法、加在节点上的 CSS 类,以及
> 那些会让布局崩掉的坑。

### 四个核心部件

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       宿主应用 (ZhiFlow)                                │
│                                                                          │
│   ┌─────────────┐   发现        ┌──────────────────────────────────────┐ │
│   │  ServiceLoader│ ───────────► │  plugins/*.jar                       │ │
│   │  META-INF/   │              │  └─ fan.summer.zhiflow.api.SwissKitJPlugin    │ │
│   │  services/   │              │     (一个类直接实现接口)              │ │
│   └─────────────┘              └──────────────────────────────────────┘ │
│           │                              │                               │
│           │ getId/getName/getCategory     │ createView()                  │
│           │ getMdiIcon/getIconStyle       │  ──► Node (被缓存)            │
│           ▼                              ▼                               │
│   ┌──────────────┐              ┌────────────────────────┐              │
│   │ 侧边栏 +      │              │ 内容 StackPane         │              │
│   │ 工具卡片      │              │  └─ 你的视图 (Node)    │              │
│   └──────────────┘              │     自动继承主 scene   │              │
│                                 │     的 CSS + 主题      │              │
│                                 └────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────────┘
```

- **契约** —— `SwissKitJPlugin`(16 个方法;[§3.1](#zhiflowjplugin-方法契约))。
- **宿主** —— 调用元数据方法构建侧边栏/搜索;调用一次 `createView()` 并缓存返回的 `Node`,
  把它嵌入内容 `StackPane`。
- **样式表** —— `zhiflow-common.css`,由宿主加载到主 scene。你嵌入的视图自动继承;独立窗口必须
  通过 [`Themes.applyTo`](#43-为独立-stage-应用主题--themesapplyto) 显式加入。
- **类分类法** —— `.sk-*` 用于共享/组件类(来自 common.css),无前缀的 shell 类
  (`nav-item`、`tool-card` …),状态修饰符([§3.2](#css-naming))。

---

## 2. 设计原则

五条规则。前三条是 [05 主题与色彩系统](05-theme-color-system.md) 中同名原则的实现视角重述;
后两条专用于 JavaFX 代码生成。

### P1 —— 代码即 UI(无 FXML、无标记)

你的插件类和像素之间没有任何声明式中间层。`createView()` 用纯 Java 构建并返回一个 `Node` 图。
结论:每一个视觉元素都可 grep、可用 IDE 重构、可在普通 diff 中审查。**不要**引入 FXML、
mustache 模板或生成的 builder。

### P2 —— 颜色只通过 token 或工具类设置,绝不内联

> 这是最重要的一条规则。重述自 [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)。

JavaFX **内联** `setStyle("-fx-...")` 字符串**不会**对 looked-up color 变量求值。
`node.setStyle("-fx-text-fill: -sk-text;")` *不会*解析为主题颜色,*也不会*在切换主题时更新。因此:

| 你想设置什么 | 怎么做 |
|---|---|
| **颜色**(文字填充、背景、边框、填充) | 通过 `node.getStyleClass().add("…")` 应用 `.sk-*` 工具类/复合类。绝不内联。 |
| **尺寸 / 内边距 / 圆角** | 内联 `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")` **没问题**——这些不是 looked-up color。 |

一个节点可以**同时**带颜色类和内联几何样式。

### P3 —— 尺寸与内边距可以内联

padding、间距、圆角、min/max 尺寸、insets——这些都是几何而非颜色,内联设置是安全的
(`setPadding`、`setHgap`、`setStyle("-fx-background-radius: 10;")`)。只有**颜色**被限制为必须用 CSS 类。
这与 P2 互为表里,存在意义是让你不必为了加 `8px` 内边距就新建一个类。

### P4 —— 每个插件一个被缓存的视图

`createView()` **只调用一次**,在首次激活时。宿主缓存返回的 `Node` 并在之后每次激活时复用。含义:

- **不要**在每次 `onActivate()` 时从头重建视图。在 `createView()` 中构建一次,持有需要修改的控件
  字段引用,在生命周期钩子里只刷新*内容*而非*结构*。
- 在 `createView()` 内部(首次调用时)惰性构建视图图是完全可取的常见做法。
- 如果需要多步骤工作流,使用 `fan.summer.zhiflow.api.component.StepWizard`(见
  [`docs/plugins/ui.md`](../plugins/ui.md)),而不是整棵树地替换。

### P5 —— CSS 类名遵循 `sk-` 前缀规范

来自 `zhiflow-common.css` 的共享组件/工具类带 **`.sk-`** 前缀(如 `.sk-field`、`.sk-btn-primary`)。
外壳 chrome 类(侧边栏、工具卡片、状态栏)**不带前缀**(`nav-item`、`tool-card`)。完整的分类法、
v3.2.0 的 `.glass-*`→`.sk-*` 迁移以及状态修饰符规范见 [§3.2](#css-naming)。

---

## 3. 规格表

### 3.1 `SwissKitJPlugin` 方法契约
<span id="zhiflowjplugin-方法契约"></span>

该接口声明 **16 个方法**:**7 个必需**(无默认),**9 个有合理默认值**。下表的每个签名都从
[`SwissKitJPlugin.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java)
**逐字**复制——按原样复制;不要改写返回类型或增删参数。

#### 必需方法(必须实现——无默认值)

| # | 签名 | 返回类型 | 用途 |
|---|---|---|---|
| 1 | `String getId()` | `String` | 全局唯一工具 ID。推荐反向域名(如 `"com.example.json-formatter"`)。内置工具用 `"builtin.<slug>"`。 |
| 2 | `String getName()` | `String` | 工具卡片和侧边栏上的显示名。推荐 i18n key:`I18n.get("builtin.<slug>.name")`。 |
| 3 | `String getDescription()` | `String` | 卡片和详情面板上的一行描述。推荐 i18n key。 |
| 4 | `ToolCategory getCategory()` | `ToolCategory` | 侧边栏分组/过滤。取值之一:`DEV`、`TEXT`、`IMAGE`、`NET`、`OTHER`。 |
| 5 | `String getVersion()` | `String` | 语义化版本字符串,如 `"1.0.0"`。 |
| 6 | `String getMdiIcon()` | `String` | Material Design Icons 名称,**不带** `mdi` 前缀,如 `"code-json"`,**不是** `"mdi-code-json"`。 |
| 7 | `Node createView()` | `javafx.scene.Node` | 构建主 UI。调用**一次**;返回的 `Node` 被缓存并复用。永不返回 `null`。 |

#### 默认方法(可选覆盖)

| # | 签名 | 默认值 | 何时覆盖… |
|---|---|---|---|
| 8 | `default IconStyle getIconStyle()` | `IconStyle.BLUE` | 你想要不同的图标底色(如 `PURPLE`、`TEAL`、`AMBER`、`RED`、`PINK`、`GRAY`)。 |
| 9 | `default ToolType getType()` | `ToolType.PLUGIN` | 你是**内置**工具 → 返回 `ToolType.BUILTIN`。外部插件保留默认。 |
| 10 | `default void onActivate()` | `{}`(空) | 工具进入前台——恢复定时器、恢复 UI 状态。 |
| 11 | `default void onDeactivate()` | `{}`(空) | 工具进入后台(无运行任务)——暂停定时器、持久化状态。 |
| 12 | `default void onUnload()` | `{}`(空) | 插件被卸载/关停——释放线程、关闭文件/网络句柄、取消任务。只触发一次。 |
| 13 | `default boolean hasRunningTasks()` | `false` | 你有应继续运行的后台任务。返回 `true` 时宿主改调 `onBackground()` 而非 `onDeactivate()`。 |
| 14 | `default void onBackground()` | `{}`(空) | **带**运行任务进入后台——调整 UI 轮询等(替代 `onDeactivate`)。 |
| 15 | `default void onForeground()` | `{}`(空) | 从后台状态返回前台——刷新布局/尺寸。当前次被后台化时,在 `onActivate()` 之后触发。 |
| 16 | `default List<AiTool> aiTools()` | `List.of()` | 你的插件对外暴露 AI 可调用工具。非 AI 插件默认空。 |

> **生命周期顺序。** `createView()` → 被缓存。正常导航触发 `onActivate()` ↔ `onDeactivate()`。
> 若 `hasRunningTasks()` 为 `true`,宿主改用 `onBackground()` / `onForeground()`。`onUnload()` 在
> 卸载/关停时**恰好触发一次**。这些**默认全部是空操作**——只实现你需要的。

#### 配套枚举(取值逐字)

```
ToolCategory  = DEV | TEXT | IMAGE | NET | OTHER          (每个含 id + i18nKey)
ToolType      = BUILTIN | PLUGIN                          (BUILTIN 随宿主发布)
IconStyle     = BLUE | PURPLE | TEAL | AMBER | RED | PINK | GRAY
                每个映射到一个 CSS 类(ic-blue … ic-gray)+ 强调色 Color
```

- `ToolCategory` —— [`ToolCategory.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/ToolCategory.java)
- `ToolType` —— [`ToolType.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/ToolType.java)
- `IconStyle` —— [`IconStyle.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java)
  (图标底色样式定义在 `shell.css`;见 [06 图标系统](06-icon-system.md))。

---

### CSS 类命名规范
<span id="css-naming"></span>

ZhiFlow 场景图中的每个节点都带有零个或多个样式类,这些类来自三个命名空间。知道一个类属于哪个
命名空间,就知道它的所有者是谁、插件能否安全使用。

#### 三个命名空间

| 命名空间 | 前缀 | 所有者 | 示例 | 插件可用? |
|---|---|---|---|---|
| **共享 / 组件类** | `.sk-` | `zhiflow-common.css`(随 API JAR 发布) | `.sk-field`、`.sk-surface`、`.sk-btn-primary`、`.sk-table`、`.sk-dialog`、`.sk-t1` | ✅ 可以——这是面向插件的词汇表 |
| **外壳 chrome 类** | *(无前缀)* | `shell.css`(仅宿主应用) | `nav-item`、`tool-card`、`search-bar`、`statusbar`、`ic-blue` | ⚠️ 宿主拥有;插件不应依赖(它们用于侧边栏/卡片/状态栏,不是工具内容) |
| **状态修饰符** | `is-*` / 状态 | 组件类经 `:hover`/`:focused` 或显式切换 | `.sk-notif-success`、`.sk-notif-warning`、`.sk-notif-danger` | ✅ 可以,用于语义状态 |

> **BEM-lite。** `.sk-` 命名空间遵循扁平、连词符分隔的 "BEM-lite" 方案:
> `sk-` + `block` + 可选的 `__element` 或 `-modifier`。实际中 ZhiFlow 保持扁平
> (`.sk-btn-primary`、`.sk-field-label`),而非完整的 `block__elem--mod` 记法。经验法则:
> **一个概念、一个类、`sk-` 前缀、连词符分词。**

#### 颜色工具类(应用这些,不要内联颜色)

这些类把一个 `-sk-*` token 绑定到某个 CSS 属性,使其解析并在切换主题时重新解析
(完整 token→类对照表见 [05 §3.2](05-theme-color-system.md#token--css-工具类)):

| 类 | 设置 | Token |
|---|---|---|
| `.sk-t1` | `Labeled` 节点的 `-fx-text-fill` | `-sk-text` |
| `.sk-t2` | `Labeled` 节点的 `-fx-text-fill` | `-sk-text-secondary` |
| `.sk-t3` | `Labeled` 节点的 `-fx-text-fill` | `-sk-text-disabled` |
| `.sk-surface` | `-fx-background-color` | `-sk-bg-elevated` |
| `.sk-outlined` | `-fx-border-color` | `-sk-border` |

#### 复合组件类(优先于手写 CSS)

对更复杂的控件,`zhiflow-common.css` 提供了打包多个 token + 几何尺寸的现成类。**用这些,而不是
自己拼装**——完整规格见 [03 组件库](03-component-library.md):

| 类 | 给你什么 |
|---|---|
| `.sk-field` | 主题化文本输入:`-sk-bg` 背景、`-sk-border` 边框、`-sk-text` 填充、6px 圆角、内边距;`:focused` → 强调色边框 |
| `.sk-table` | 主题化表格:抬升表面、边框;选中行 → `-sk-bg-selected` + 强调色标签 |
| `.sk-tab-pane` | 标签页:hover `-sk-bg-hover`、选中 `-sk-bg-selected` + 2px 底部强调条 |
| `.sk-dialog` | 对话面板:抬升表面、边框、10px 圆角、投影 |
| `.sk-btn-primary` | 主操作按钮:`-sk-accent` 背景、白色文字(标准的强调色用法) |
| `.sk-btn-secondary` | 次按钮:`-sk-bg-hover` 背景、`-sk-border` 边框、`-sk-text` 填充 |
| `.sk-notif-*` | 通知变体(`success`/`warning`/`danger`),用 `-sk-accent-soft` + 状态 token |

#### 状态修饰符规范

状态色(`-sk-success`、`-sk-warning`、`-sk-danger`)**严格语义化**——它们表示成功 / 警示 / 破坏。
它们通过组件变体应用,绝不做原始装饰填充:

```java
// ✅ 通过通知组件类表达状态
notif.getStyleClass().addAll("sk-notif", "sk-notif-success");   // 绿
notif.getStyleClass().addAll("sk-notif", "sk-notif-warning");   // 琥珀
notif.getStyleClass().addAll("sk-notif", "sk-notif-danger");    // 红
```

绝不要把状态色挪作装饰(例如别用琥珀色做 "精选" 徽章)。需要非语义色调时,见
[05 §P4](05-theme-color-system.md) 和 [06 图标系统](06-icon-system.md) 中的分类色。

#### `.glass-*` → `.sk-*` 迁移(v3.2.0,破坏性)

> **⚠️ 对外部插件的破坏性变更。** v3.2.0 中,整个共享组件类家族从 `.glass-*` 重命名为 `.sk-*`。
> **任何基于 v3.2.0 之前 API 构建、引用了 `.glass-*` 类的插件 JAR,升级后将失去样式。**

完整、权威的迁移表位于 New UI 重设计规格
[**§7 "共性类重命名 `.glass-*` → `.sk-*`"**](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md)。
几行代表性映射(已对照该规格核实):

| 旧(pre-v3.2.0) | 新(v3.2.0+) |
|---|---|
| `.glass-dialog` | `.sk-dialog` |
| `.glass-btn-primary` / `.glass-btn-secondary` | `.sk-btn-primary` / `.sk-btn-secondary` |
| `.glass-field` / `.glass-field-label` | `.sk-field` / `.sk-field-label` |
| `.glass-table` | `.sk-table` |
| `.glass-notif-*` | `.sk-notif-*` |

模式是机械的:**去掉 `glass`,加上 `sk`** —— `.glass-<block>` → `.sk-<block>`。完整表格
(含 `.glass-tab-pane`、`.glass-combo`、`.glass-checkbox` 等)见 **New UI 规格 §7**——不要凭记忆
重建映射;拿不准时直接查阅该章节。

---

### 布局容器选型指南
<span id="布局容器选型指南"></span>

ZhiFlow 用标准 JavaFX pane 拼装布局。选择与你所需空间关系匹配的容器;选错容器是缩放崩坏的
头号原因(见 [§4.5 三大布局陷阱](#45-三大布局陷阱))。

| 容器 | 用于 | 关键 API | 坑 |
|---|---|---|---|
| **GridPane** | 表单、标签/控件行、对齐的列 | `setHgap`/`setVgap`、带 `Priority` 的 `ColumnConstraints`、`GridPane.setHalignment` | 通过 `ColumnConstraints` + `Priority` 控制列宽,不要用 `setPrefWidth` |
| **VBox / HBox** | 线性垂直 / 水平堆叠 | `setSpacing`、`setPadding`、`VBox.setVgrow` / `HBox.setHgrow(node, Priority.ALWAYS)` | 填充剩余空间:`setHgrow`/`setVgrow(Priority.ALWAYS)` **+** `node.setMaxWidth/Height(MAX_VALUE)`。绝不 `setPrefWidth(MAX_VALUE)` |
| **BorderPane** | 顶/中/底(或左/右)区域——主窗口 chrome | `setTop` / `setCenter` / `setBottom` | 中部增长;宿主已拥有应用级 BorderPane——插件很少需要 |
| **FlowPane** | 换行的卡片网格、chip 行、图标托盘 | `setHgap`/`setVgap`、`setPrefWrapLength` | 子节点保持其 pref 尺寸;适合统一大小的瓦片 |
| **StackPane** | 叠加、z 轴堆叠、**页面切换**(多个子节点,一个可见) | `setVisible` + `setManaged` 切换 | 切换 "页面" 时,同时切换 `visible` 与 `managed`(见 [§4.5](#45-三大布局陷阱)) |
| **ScrollPane** | 可滚动内容区 | `.content-scroll` 类用于细滚动条;`setFitToWidth` | 在 StackPane 内,必须 `setMaxWidth`/`setMaxHeight(MAX_VALUE)` 才能填满 |

#### 决策流程

```
子节点是严格的行/列网格(标签 + 输入)?
│  是 → GridPane  (增长列用 ColumnConstraints + Priority)
│  否
└─ 是单一的线性序列?
   │  是 → VBox(垂直)或 HBox(水平)
   │       └─ 填充剩余空间:setHgrow/Vgrow(ALWAYS) + setMaxWidth/Height(MAX_VALUE)
   │  否
   └─ 它们叠加 / 作为 "页面" 切换?
      │  是 → StackPane  (逐页切换 visible + managed)
      │  否
      └─ 内容溢出需要滚动?
         │  是 → ScrollPane  (加 .content-scroll;在 StackPane 内设 setMaxWidth/Height(MAX_VALUE))
         │  否
         └─ 瓦片要换行?
            │  是 → FlowPane
            │  否
            └─ 三个区域(顶/中/底)?→ BorderPane
```

---

## 4. JavaFX 实现模板

本节是可复制粘贴的核心。它展示一个**完整可编译**的 `SwissKitJPlugin` 骨架,使用与
[`docs/plugins/ui.md`](../plugins/ui.md) 相同的 `{{base-package}}` / `{{Name}}` / `{{slug}}` 占位符,
随后是图标助手、主题助手、i18n 模式以及三大布局陷阱——所有反复出现的构建块。

### 插件骨架
<span id="plugin-skeleton"></span>

> **占位符规范**(与 `docs/plugins/ui.md` 完全一致):
> - `{{base-package}}` —— 你的插件基础包,如 `com.example.mytool`
> - `{{Name}}` —— PascalCase 工具名,如 `CsvSorter`
> - `{{slug}}` —— kebab-case slug,如 `csv-sorter`
>
> 一个类**直接**实现 `SwissKitJPlugin`——没有单独的 `*PluginUi` 包装。全部 11 个内置工具都遵循
> 这个单类模式(见 [§6 AP1](#ap1--单独的-pluginui-包装类))。

```java
package {{base-package}}.ui;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.MdiIconUtil;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import fan.summer.zhiflow.api.ToolType;
import fan.summer.zhiflow.api.i18n.I18n;
import fan.summer.zhiflow.api.theme.Themes;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * {{Name}} — a ZhiFlow plugin.
 *
 * <p>Implements {@link SwissKitJPlugin} directly: one class holds both the metadata
 * (id/name/category/icon) and the view ({@link #createView()}). The host calls
 * {@code createView()} once and caches the returned {@link Node}.</p>
 */
public class {{Name}}Plugin implements SwissKitJPlugin {

    // ── Cached view (built once in createView) ──────────────────────────
    private GridPane rootPanel;
    private final TextArea inputArea = new TextArea();
    private final Label statusLabel = new Label();

    // i18n key prefix; every user-visible string reads through I18n
    private static final String P = "builtin.{{slug}}.";

    // ── ① Required metadata (7 methods, no default) ─────────────────────

    @Override public String getId()          { return "builtin.{{slug}}"; }
    @Override public String getName()        { return I18n.get(P + "name"); }
    @Override public String getDescription() { return I18n.get(P + "desc"); }
    @Override public ToolCategory getCategory() { return ToolCategory.TEXT; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "code-json"; }  // NO "mdi-" prefix

    @Override
    public Node createView() {
        // Build once. The host caches this Node and reuses it on every activation.
        rootPanel = new GridPane();
        rootPanel.setHgap(10);
        rootPanel.setVgap(8);
        rootPanel.setPadding(new Insets(20));

        // Column 0 = label (fixed), Column 1 = control (grows)
        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHgrow(Priority.NEVER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        rootPanel.getColumnConstraints().addAll(col0, col1);

        Label inputLabel = new Label();
        inputLabel.getStyleClass().add("sk-t2");                 // color via class, NOT inline
        I18n.bind(inputLabel.textProperty(), P + "inputLabel");  // auto-updates on language switch

        inputArea.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        inputArea.setWrapText(true);
        VBox.setVgrow(inputArea, Priority.ALWAYS);               // fill vertical space

        Button runBtn = new Button();
        runBtn.getStyleClass().add("sk-btn-primary");            // accent button — see 05/03
        I18n.bind(runBtn.textProperty(), P + "run");
        runBtn.setOnAction(e -> doWork());

        // statusLabel: DYNAMIC text → use I18n.get at update time, not I18n.bind
        statusLabel.getStyleClass().add("sk-t3");

        rootPanel.add(inputLabel, 0, 0);
        rootPanel.add(inputArea, 1, 0);
        rootPanel.add(runBtn,    1, 1);
        rootPanel.add(statusLabel, 0, 2, 2, 1);                  // span 2 columns
        GridPane.setHalignment(runBtn, HPos.RIGHT);

        return rootPanel;
    }

    // ── ② Defaults you commonly override ───────────────────────────────

    @Override public IconStyle getIconStyle() { return IconStyle.PURPLE; }
    @Override public ToolType getType()       { return ToolType.BUILTIN; }  // builtins MUST override

    // ── ③ Lifecycle hooks (no-ops unless you need them) ────────────────

    @Override public void onActivate()   { /* resume timers / refresh */ }
    @Override public void onDeactivate() { /* pause timers */ }
    @Override public void onUnload()     { /* release threads, close handles */ }

    // ── ④ Background-task aware lifecycle (optional) ───────────────────

    @Override public boolean hasRunningTasks() { return false; }
    @Override public void onBackground() { /* keep tasks alive while hidden */ }
    @Override public void onForeground() { /* re-attach UI after background */ }

    // ── ⑤ AI tools (optional — default is List.of()) ───────────────────

    @Override public List<AiTool> aiTools() { return List.of(); }

    // ── example worker ─────────────────────────────────────────────────
    private void doWork() {
        // Dynamic message: resolve via I18n.get at the moment you set it.
        statusLabel.setText(I18n.get(P + "done"));
    }
}
```

#### 骨架说明

- **必需与默认泾渭分明。** 7 个必需方法在接口中无 `default`——不实现就无法编译。9 个默认方法为
  完整性而列;删掉你不需要的。
- **`getId()` 取值。** 内置工具用 `"builtin.<slug>"`(如 `"builtin.json-formatter"`)。
  **⚠️ 注意不一致:** 当前 11 个内置中有 3 个(`EmailArchivePlugin`、`ExcelSplitterPlugin`、
  `BrowserAutomatePlugin`)改用遗留形式 `"fan.summer.zhiflow.buildin.<slug>"`。当前代码库中没有单一规范形式;
  **新内置应优先用 `"builtin.<slug>"`**,外部插件应使用反向域名 ID(`"com.example.<slug>"`)。
  grep 时不要假设只有一种形式。
- **`getMdiIcon()` 无 `mdi-` 前缀。** 返回 `"mdi-code-json"` 会解析失败并回退到 `star` 字形
  (见 [§4.2](#42-图标--mdiiconutil))。
- **内置的 `getType()`。** 接口默认是 `ToolType.PLUGIN`;内置工具**必须**覆盖为
  `ToolType.BUILTIN`。外部插件保留默认。
- **注册实现。** 创建 `src/main/resources/META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin`,内含
  完全限定类名(一行),然后把 fat-JAR 打进宿主的 `plugins/` 目录。支持热重载。

```
META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin
└─ {{base-package}}.ui.{{Name}}Plugin
```

---

### 4.2 图标 · `MdiIconUtil`

图标是通过内置 webfont 渲染的 Material Design Icons。唯一入口:

```java
import fan.summer.zhiflow.api.MdiIconUtil;
import javafx.scene.text.Text;

// 名称不带 "mdi-" 前缀;尺寸单位为逻辑像素
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);
```

| 关注点 | 规则 |
|---|---|
| **名称格式** | MDI 名称**不带** `mdi-` 前缀:`"file-excel"`、`"code-json"`、`"folder-open"`。接口的 `getMdiIcon()` 遵循同样规则。 |
| **未知名称** | 回退到 `star` 字形——所以拼写错误会*静默*渲染出*某些东西*。对照 [MDI 目录](https://pictogrammers.com/library/mdi/) 仔细核对拼写。 |
| **默认填充** | `createIcon(name, size)` 返回白色填充的 `Text`(`-fx-fill: white;`)。用三参重载或 `setStyle("-fx-fill: ...;")` 覆盖为分类色。 |
| **自定义字形** | `MdiIconUtil.putIcon(name, codepoint)` 为插件专用图标注册运行时映射。 |

完整的图标/底色系统(分类色、`IconStyle`→`ic-*` 映射、尺寸)见 [06 图标系统](06-icon-system.md)。

源码:[`MdiIconUtil.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java)。

---

### 4.3 为独立 Stage 应用主题 · `Themes.applyTo`

从 `createView()` 返回的节点被**嵌入宿主主 scene**,该 scene 已加载 `zhiflow-common.css` 并盖好
主题类。**嵌入视图无需做任何事。**

但插件若打开**自己的** `Stage`/`Scene`(模态 `Alert`、独立工具窗口),会得到一个**没有样式表、
没有主题类**的新 scene——每个 `-sk-*` token 都会解析失败。用一次调用修复:

```java
import fan.summer.zhiflow.api.theme.Themes;
import javafx.scene.Scene;
import javafx.stage.Stage;

Stage dialog = new Stage();
Scene scene = new Scene(content, 480, 320);

Themes.applyTo(scene);   // ← 插件要做的唯一调用。加载 CSS + 盖主题类 + 跟踪以便后续切换。

dialog.setScene(scene);
dialog.show();
```

`Themes.applyTo(scene)` 委托给 `ThemeService.registerScene(scene)`——所以窗口会被*跟踪*:之后的
`ThemeService.set(...)` 也会重新主题化它。**插件必须调用 `Themes.applyTo`,而非 `ThemeService` 内部
方法**——`Themes` 是受支持的稳定表面。

#### `Alert`/`Dialog` 模式

`Dialog` 惰性创建自己的 `Scene`,所以通过 `sceneProperty` 监听器挂载(与
[`docs/plugins/ui.md`](../plugins/ui.md) 一致):

```java
private void showAlert(Alert.AlertType type, String message) {
    Alert alert = new Alert(type);
    alert.setHeaderText(null);
    alert.setContentText(message);
    // Dialog 稍后创建 Scene —— 出现时再应用主题
    alert.getDialogPane().sceneProperty().addListener((obs, old, scene) -> {
        if (scene != null) Themes.applyTo(scene);
    });
    alert.showAndWait();
}
```

完整的主题生命周期(token 解析、`ThemeService.set`/`onChange`、WebView 同步、持久化)见
[05 主题与色彩系统](05-theme-color-system.md)。

| 该做 | 不该做 |
|---|---|
| 为你创建的任何 `Scene` 调用 `Themes.applyTo(scene)` | 在插件代码中直接调 `ThemeService.registerScene` |
| 信任 `createView()` 节点的自动继承 | 手动把 `zhiflow-common.css` 加到 `getStylesheets()` |
| 信任 `Themes.applyTo` 幂等(已应用则空操作) | 自己重新添加样式表 URL |

源码:[`Themes.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java) ·
[`ThemeService.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java)。

---

### 4.4 国际化(i18n)模式

每条用户可见字符串都流经 [`fan.summer.zhiflow.api.i18n.I18n`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/i18n/I18n.java)。
有三种模式——按文本*何时*产生来选择。(镜像 [`docs/plugins/ui.md`](../plugins/ui.md)。)

| 模式 | 何时用 | 示例 |
|---|---|---|
| `I18n.bind(property, key)` | **静态**标签/按钮——文本设一次,切换语言时自动更新 | `I18n.bind(label.textProperty(), "builtin.json.input")` |
| `I18n.get(key)` | **动态**文本——运行时产生的状态消息、格式化输出 | `statusLabel.setText(I18n.get("builtin.json.idle"))` |
| `I18n.addListener(runnable)` | 切换 locale 时需要自定义刷新逻辑 | `I18n.addListener(this::refreshStatus)` |

```java
// 静态标签 —— 绑定一次,自动跟随 locale
Label titleLabel = new Label();
I18n.bind(titleLabel.textProperty(), "builtin.{{slug}}.title");

// 动态状态 —— 在设置的那一刻解析
statusLabel.setText(I18n.get("builtin.{{slug}}.done"));

// locale 切换时批量刷新
I18n.addListener(() -> rebuildDynamicParts());
```

> **`bind` vs `get`:** `bind` 把一个 `Property` 连到 key 上,使 UI 在切换语言时*无需代码*即可更新
> ——用于除翻译外文本永不变的东西。`get` 是一次式查找,用于文本本身是计算出来的(依赖状态的状态、
> 格式化数字)。如果你发现自己对同一个静态标签反复 `get`,那你多半想要的是 `bind`。

---

### 4.5 三大布局陷阱

这三个陷阱出现在 [`docs/plugins/ui.md`](../plugins/ui.md) 中,几乎包揽了所有 "为什么我的布局坏了"
的报告。此处重述,因为它们是任何代码生成器的必修知识。

#### 陷阱 1 —— ScrollPane 不填满父容器(StackPane)

StackPane 内的 ScrollPane 不会扩展,除非你显式抬高它的最大尺寸:

```java
ScrollPane sp = new ScrollPane(content);
sp.setMaxWidth(Double.MAX_VALUE);    // ← 必需,否则不填满
sp.setMaxHeight(Double.MAX_VALUE);
```

加 `.content-scroll` 类以获得 ZhiFlow 的细滚动条样式。

#### 陷阱 2 —— 填满 HBox/VBox 的剩余空间

要让子节点填满剩余水平(或垂直)空间,需要**两个**调用:

```java
node.setMaxWidth(Double.MAX_VALUE);              // 允许它超越 pref 增长
HBox.setHgrow(node, Priority.ALWAYS);            // 把多余空间分给它
// (垂直方向用 VBox.setVgrow(node, Priority.ALWAYS) + setMaxHeight)
```

> **❌ 禁止:** `node.setPrefWidth(Double.MAX_VALUE)` —— 这会让**整个**布局塌缩,而不只是这个节点。
> 始终用 `setMaxWidth` + `setHgrow`。见 [AP3](#ap3--hboxvbox-子节点-setprefwidthmax_value)。

#### 陷阱 3 —— StackPane 页面切换

用 `StackPane` 在 "页面" 间切换时,对每个子节点同时切换 `visible` **和** `managed`。只切 `visible`
会让隐藏页面仍占据布局空间:

```java
Node[] pages = { pageA, pageB, pageC };
int idx = 1;   // 显示 pageB
for (int j = 0; j < pages.length; j++) {
    pages[j].setVisible(j == idx);    // 隐藏页面不绘制
    pages[j].setManaged(j == idx);    // ……也不占布局空间
}
```

---

## 5. AI 开发检查清单

生成 ZhiFlow 插件时你**必须**满足以下全部项。每项都是硬门槛。

- [ ] **直接实现 `SwissKitJPlugin`——不是包装。** 一个类同时持有元数据 + 视图。不要创建单独的
      `*PluginUi` 类(全部 11 个内置都直接实现接口)。
- [ ] **实现全部 7 个必需方法。** `getId`、`getName`、`getDescription`、`getCategory`、
      `getVersion`、`getMdiIcon`、`createView` 无默认值——缺了就编译不过。
- [ ] **缓存 `createView()` 的结果。** 视图图构建一次(在 `createView()` 内或首次调用时惰性构建);
      持有需修改控件的字段引用;在生命周期钩子里只刷新*内容*,绝不在每次激活时重建结构。
- [ ] **返回不带 `mdi-` 前缀的 MDI 名称。** `getMdiIcon()` → `"code-json"`,绝不要 `"mdi-code-json"`。
      `MdiIconUtil.createIcon` 同理。
- [ ] **内置覆盖 `getType()`。** 返回 `ToolType.BUILTIN`。接口默认是 `ToolType.PLUGIN`——外部插件保留。
- [ ] **绝不自己加载 `zhiflow-common.css`。** 嵌入视图从宿主 scene 继承;独立窗口通过
      `Themes.applyTo(scene)` 获得。不要调 `getStylesheets().add(...)`。
- [ ] **绝不在插件代码中调 `ThemeService` 内部方法。** 用 `Themes.applyTo(scene)`(受支持的表面)。
      `ThemeService.registerScene`/`set` 是宿主级。
- [ ] **绝不在 `setStyle()` 中内联十六进制颜色或 `-sk-*` token。** 内联样式不解析 looked-up color。
      颜色 → `.sk-*` 类;尺寸/内边距/圆角 → 内联。(见 [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)。)
- [ ] **优先用复合类而非手写 CSS。** 用 `.sk-field`、`.sk-btn-primary`、`.sk-table`、`.sk-dialog`
      等,而不是重新实现主题化控件。(见 [03 组件库](03-component-library.md)。)
- [ ] **用 `.sk-*` 类名,绝不用 `.glass-*`。** `.glass-*` 家族在 v3.2.0 已移除;见
      [迁移说明](#glass---sk-迁移v320破坏性) 与 New UI 规格 §7。
- [ ] **所有用户可见字符串走 I18n。** 静态标签用 `I18n.bind`,动态文本用 `I18n.get`,自定义刷新用
      `I18n.addListener`。
- [ ] **正确填满空间。** `setHgrow`/`setVgrow(Priority.ALWAYS)` + `setMaxWidth/Height(MAX_VALUE)`;
      绝不 `setPrefWidth(MAX_VALUE)`。StackPane 页面切换时同时切 `visible` 与 `managed`。StackPane
      内的 ScrollPane 设 `setMaxWidth/Height(MAX_VALUE)`。
- [ ] **注册 service 文件。** `META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin` 写 FQCN;把 fat-JAR
      打进 `plugins/`。

---

## 6. 反模式

每条展示错误、为什么崩、以及纠正。

### AP1 —— 单独的 `*PluginUi` 包装类

```java
// ❌ 错误 —— 把契约拆到两个类
public class {{Name}}Plugin implements SwissKitJPlugin {
    private final {{Name}}PluginUi ui = new {{Name}}PluginUi();   // 额外间接层
    public Node createView() { return ui.getView(); }
    // … 元数据方法 …
}
public class {{Name}}PluginUi { Node getView() { … } }
```

全部 11 个内置工具都**直接**在一个类里实现 `SwissKitJPlugin`——元数据 + 视图在一起。包装层增加了
间接、文件数翻倍,破坏了 "grep 一个类看全部" 的预期。(`docs/plugins/ui.md` 中那个独立的
`*PluginUi` 示例演示的是一种视图构建器模式;在 ZhiFlow 自身代码库里,视图直接在 `createView()`
中构建。)

```java
// ✅ 正确 —— 一个类实现接口并构建视图
public class {{Name}}Plugin implements SwissKitJPlugin {
    private GridPane rootPanel;
    public Node createView() { /* 构建一次 rootPanel */ return rootPanel; }
    // … 元数据方法 …
}
```

### AP2 —— `setStyle()` 中内联十六进制 / token 颜色

```java
// ❌ 错误 —— 硬编码深色值;切换主题永不更新
label.setStyle("-fx-text-fill: #D0D0D0;");

// ❌ 同样错误 —— looked-up color 在内联 setStyle 中不解析
label.setStyle("-fx-text-fill: -sk-text;");
```

```java
// ✅ 正确 —— 应用工具类;token 解析并重新解析
label.getStyleClass().add("sk-t1");
```

这是最常见的主题 bug;完整解释见 [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)。
颜色 → 类;几何 → 内联。

### AP3 —— HBox/VBox 子节点 `setPrefWidth(MAX_VALUE)`

```java
// ❌ 错误 —— 让整个布局塌缩,不只是这个节点
node.setPrefWidth(Double.MAX_VALUE);
```

```java
// ✅ 正确 —— 增长优先级 + 抬高最大尺寸
node.setMaxWidth(Double.MAX_VALUE);
HBox.setHgrow(node, Priority.ALWAYS);
```

见 [陷阱 2](#陷阱-2--填满-hboxvbox-的剩余空间)。

### AP4 —— 手动加载公共 CSS

```java
// ⚠️ 建议用 applyTo() —— commonStylesheetUrl() 只加载样式表;
//    它不会给根节点盖 .theme-dark / .theme-light 类,因此 token 无法解析
scene.getStylesheets().add(Themes.commonStylesheetUrl());
// 或
Themes.loadCommonStylesheet(scene);   // 实质上是包私有的;不是插件 API
```

```java
// ✅ 正确 —— 一个受支持的调用
Themes.applyTo(scene);   // 加载 CSS + 盖主题类 + 跟踪以便切换
```

嵌入的 `createView()` 节点**什么都不需要**——它们继承宿主 scene 的样式表。

### AP5 —— 在插件代码中调 `ThemeService` 内部方法

```java
// ❌ 错误 —— ThemeService 是宿主级引擎;其 API 可能变
ThemeService.registerScene(myScene);
ThemeService.set(ThemeService.Theme.LIGHT);
```

```java
// ✅ 正确 —— Themes 是稳定、面向插件的表面
Themes.applyTo(myScene);
// (切换主题是宿主的职责;插件通过 Themes.applyTo 跟踪的 scene 被动响应)
```

`Themes.applyTo` 委托给 `ThemeService.registerScene`,所以你免费获得跟踪能力而不必耦合内部。
见 [05 §4.7](05-theme-color-system.md)。

### AP6 —— 返回带 `mdi-` 前缀的 MDI 名称

```java
// ❌ 错误 —— 带 "mdi-" 前缀;不解析;静默回退到 star 字形
@Override public String getMdiIcon() { return "mdi-code-json"; }
Text icon = MdiIconUtil.createIcon("mdi-file-excel", 24.0);
```

```java
// ✅ 正确 —— 裸 MDI 名称
@Override public String getMdiIcon() { return "code-json"; }
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);
```

### AP7 —— 使用 `.glass-*` 类名(v3.2.0 已移除)

```java
// ❌ 错误 —— .glass-* 在 v3.2.0 已移除;节点失去样式
field.getStyleClass().add("glass-field");
btn.getStyleClass().add("glass-btn-primary");
```

```java
// ✅ 正确 —— .sk-* 命名空间
field.getStyleClass().add("sk-field");
btn.getStyleClass().add("sk-btn-primary");
```

见 [`.glass-*` → `.sk-*` 迁移](#glass---sk-迁移v320破坏性) 与 New UI 规格 §7。

### AP8 —— 每次激活都重建视图

```java
// ❌ 错误 —— createView() 应只跑一次;重建会泄漏节点并重置状态
@Override public void onActivate() {
    this.rootPanel = buildFromScratch();   // 丢掉被缓存的图
}
```

```java
// ✅ 正确 —— 在 createView() 中构建一次;onActivate() 只刷新内容
@Override public Node createView() { /* 构建一次 */ return rootPanel; }
@Override public void onActivate() { statusLabel.setText(refreshStatus()); }
```

宿主缓存了来自 `createView()` 的 `Node`;把它当作结构的唯一来源。

---

## 7. 参考资料

### 源码文件(权威)

| 内容 | 路径 |
|---|---|
| 插件契约(16 个方法) | [`ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| 图标样式(`BLUE`…`GRAY`、CSS 类、颜色) | [`ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java) |
| 工具分类(`DEV`…`OTHER`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/ToolCategory.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/ToolCategory.java) |
| 工具类型(`BUILTIN`/`PLUGIN`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/ToolType.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/ToolType.java) |
| MDI 图标渲染器(`createIcon`、`putIcon`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java) |
| 面向插件的主题助手(`applyTo`、`COMMON_CSS`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java) |
| 主题引擎(`registerScene`、`set`、`onChange`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java) |
| 共享组件 + token CSS | [`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) |
| 参考内置(单类模式) | [`ZhiFlow/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java`](../../../ZhiFlow/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java) |

### 设计基线

| 内容 | 路径 |
|---|---|
| 权威 IDEA New UI 重设计规格(含 §7 `.glass-*`→`.sk-*` 表) | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| UI 设计文档总规格 | [`docs/superpowers/specs/2026-07-01-ui-design-docs-design.md`](../superpowers/specs/2026-07-01-ui-design-docs-design.md) |

### 配套与同级文档

| 文档 | 链接 |
|---|---|
| 配套插件指南(布局陷阱、StepWizard、i18n) | [`docs/plugins/ui.md`](../plugins/ui.md) |
| 01 —— UI 设计系统(哲学、排版、间距) | [01-design-system.md](01-design-system.md) |
| 03 —— 组件库(完整 `.sk-*` 组件规格) | [03-component-library.md](03-component-library.md) |
| 05 —— 主题与色彩系统(token、对比度、主题生命周期) | [05-theme-color-system.md](05-theme-color-system.md) |
| 06 —— 图标系统(`IconStyle`、分类色、尺寸) | [06-icon-system.md](06-icon-system.md) |

---

*本文方法签名逐字复制自 `SwissKitJPlugin.java` 并经 `grep` 核实。若接口变更,本文须重新生成以保持
一致——接口是事实来源,而非本页。*
