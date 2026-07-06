# 03 · 组件库

> **定位：** ZhiFlow 中每一个可复用视觉组件的完整、权威规格。每个组件都给出了足够的细节，
> 使得 AI（或从未见过本代码库的人类）都能从零生成像素级、行为一致的实现。当某个取值是主题
> 令牌时，本文档只给出令牌名并外链，**不**重复罗列十六进制值。各令牌解析后的具体取值见
> [05 主题与配色系统](05-theme-color-system.md)。

| | |
|---|---|
| **文档类型** | 逐组件参考规格（Foundation + Shell） |
| **目标读者** | UI 设计师、插件作者、AI 代码生成器 |
| **CSS 来源（基础层）** | [`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) — 宿主与所有插件共享 |
| **CSS 来源（壳层）** | [`ZhiFlow/src/main/resources/css/shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css) — 仅宿主壳层 |
| **关联文档** | [02 JavaFX 实现](02-javafx-implementation.md) · [05 主题与配色系统](05-theme-color-system.md) · [06 图标系统](06-icon-system.md) |

---

## 如何阅读本文档

### 两大组件家族

ZhiFlow 有**两层组件**，各自有独立的样式表。判断一个类属于哪一层，是关于它最重要的事实：

| 家族 | 样式表 | 谁可以使用？ | 类前缀 | 数量 |
|---|---|---|---|---|
| **Foundation（基础层）** | `zhiflow-common.css`（API 模块） | 宿主**和**所有第三方插件 | `.sk-*` | 12 个组件 |
| **Shell（壳层）** | `shell.css`（仅宿主） | 仅宿主应用 | 无前缀（`nav-item`、`tool-card`、…） | 5 个组件 |

基础层组件会通过 `Themes.applyTo(scene)` 加载到任意 Scene 上，因此在你的插件嵌入式视图**以及**
独立 Stage 中都能使用。壳层组件是应用壳的装饰件——它们只存在于主窗口，插件不应依赖它们。

> **不要混淆两个前缀。** 插件里引用 `.nav-item` 能编译通过，但会以无样式渲染（该类不在当前
> Scene 上）。只要存在对应的 `.sk-*` 基础类，就优先使用基础类。

### 七个子部分（每个组件通用）

下文每个组件都遵循相同的结构，便于代码生成器统一解析：

1. **概览与解剖** —— 它是什么、何时使用、带标注的 ASCII 示意图。
2. **CSS 类** —— 精确类名 + 源文件行号（逐字取自源码）。
3. **使用的令牌** —— 该组件依赖的每一个 `-sk-*` 令牌，附一行用途说明。
4. **状态与修饰** —— hover/focus/selected/disabled 规则，以及 `.active`/伪类矩阵。
5. **布局与尺寸** —— 容器选型、间距、padding、pref/min/max 尺寸。
6. **JavaFX 模板** —— 最小可复制的实例化代码（类加在节点上，无十六进制）。
7. **参考** —— CSS 文件 +（仅壳层）Java 文件 + 令牌链接 + 图标链接。

### 规格表中的约定

- **令牌列** —— 形如 `-sk-accent` 表示“looked-up color `-sk-accent`”。其真实十六进制随主题变化；
  在 [令牌参考表](05-theme-color-system.md#token-reference-table) 中查一次即可。
- **“内联安全（Inline-safe）”** —— 指只设置*颜色*属性（`-fx-text-fill`、`-fx-fill`、
  `-fx-background-color`）的工具类。它们被设计为可与一个*同时*通过 `setStyle(...)` 携带内联几何
  信息（字号、padding）的节点组合使用。参见 [02 JavaFX 实现](02-javafx-implementation.md#css-naming) 的 P5。
- **示意图** 仅为 ASCII 画 + 表格 + 围栏代码块 —— 不使用 Mermaid。

### 快速跳转索引

**基础层组件**（`zhiflow-common.css`）
- [F1 · 文本工具类](#f1--文本工具类) —— `.sk-t1` `.sk-t2` `.sk-t3`
- [F2 · 表面工具类](#f2--表面工具类) —— `.sk-surface` `.sk-surface-soft` `.sk-outlined` `.sk-outlined-strong`
- [F3 · 状态文本工具类](#f3--状态文本工具类) —— `.sk-accent-text` `.sk-success-text` `.sk-warning-text` `.sk-danger-text`
- [F4 · 图形填充工具类](#f4--图形填充工具类) —— `.sk-fill-2` `.sk-fill-3`
- [F5 · 遮罩（Scrim）](#f5--遮罩scrim) —— `.sk-scrim`
- [F6 · 输入框（Field）](#f6--输入框field) —— `.sk-field` `.sk-field-label`
- [F7 · 按钮](#f7--按钮) —— `.sk-btn-primary` `.sk-btn-secondary`
- [F8 · 下拉框](#f8--下拉框) —— `.sk-combo`
- [F9 · 复选框](#f9--复选框) —— `.sk-checkbox`
- [F10 · 表格](#f10--表格) —— `.sk-table`
- [F11 · 标签页](#f11--标签页) —— `.sk-tab-pane`
- [F12 · 对话框](#f12--对话框) —— `.sk-dialog`
- [F13 · 通知](#f13--通知) —— `.sk-notif-*`
- [F14 · 步骤向导指示器](#f14--步骤向导指示器) —— `.sk-step-*`

**壳层组件**（`shell.css`）
- [S1 · 导航项](#s1--导航项) —— `.nav-item`
- [S2 · 搜索栏](#s2--搜索栏) —— `.search-bar`
- [S3 · 工具卡片](#s3--工具卡片) —— `.tool-card`
- [S4 · 详情面板](#s4--详情面板) —— `.detail-panel`
- [S5 · 状态栏](#s5--状态栏) —— `.statusbar`

---

# 第一部分 —— 基础层组件

> 来源：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`。凡调用了
> `Themes.applyTo(scene)` 的地方均可用。

---

## F1 · 文本工具类

### 1. 概览与解剖

三个文本填充工具类，适用于任意 `Label`/`Text`/`Labeled` 节点。它们的存在是因为内联
`setStyle("-fx-text-fill: ...")` 无法引用 `-sk-*` 令牌——把颜色单独移到 class 中，可以让内联样式的
其余部分（字号、padding）继续留作内联，并在切换主题时仍然正确重新计算。**这是任意文本颜色的标准
模式**（见 P5）。

```
文本重要性的层级

   .sk-t1  ████████  主要文本   —— 标题、值、正文
   .sk-t2  ██████    次要文本   —— 标签、说明、元数据
   .sk-t3  ████      禁用/提示  —— 占位符、提示、空状态
```

请**用它们替代** `setStyle` 中硬编码的 rgba。当你发现自己在写 `-fx-text-fill: rgba(...)` 时，
停下来改用对应的 `.sk-t*` 类。

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-t1` | `zhiflow-common.css:126` | 主要文本填充 |
| `.sk-t2` | `zhiflow-common.css:127` | 次要文本/标签填充 |
| `.sk-t3` | `zhiflow-common.css:128` | 禁用/提示/弱化文本填充 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-text` | `.sk-t1` 填充 —— 占主导的前景色 |
| `-sk-text-secondary` | `.sk-t2` 填充 —— 弱化的前景色 |
| `-sk-text-disabled` | `.sk-t3` 填充 —— 最弱但仍可读的前景色 |

每个令牌按主题解析，取值见 [令牌参考表](05-theme-color-system.md#token-reference-table)。

### 4. 状态与修饰

无 —— 这些是静态颜色类，没有伪类或修饰变体。

### 5. 布局与尺寸

这些类**只**设置 `-fx-text-fill`。字号、字重、字体族*不*包含——请通过内联（例如
`setStyle("-fx-font-size: 13px;")`）或在父节点上设置。一个节点可以同时携带其中一个类**以及**
内联几何信息；内联只覆盖它写出的属性，类负责颜色。

### 6. JavaFX 模板

```java
// 主要值
Label value = new Label("JSON");
value.getStyleClass().add("sk-t1");
value.setStyle("-fx-font-size: 16px; -fx-font-weight: 500;");

// 次要说明 —— 注意：只有 font-family 是内联的，颜色来自 class
Label caption = new Label("v1.2.0");
caption.getStyleClass().add("sk-t2");
caption.setStyle("-fx-font-family: 'SF Mono','Consolas',monospace;");

// 禁用提示 / 空状态消息
Label empty = new Label("No results");
empty.getStyleClass().add("sk-t3");
empty.setStyle("-fx-font-size: 13px;");
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通用主题工具类小节）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)
- 命名约定：[02 JavaFX 实现 —— `#css-naming`](02-javafx-implementation.md#css-naming)

---

## F2 · 表面工具类

### 1. 概览与解剖

背景填充与边框颜色工具类，用于面板、磁贴、分组区域。与文本工具类一样，它们**只负责颜色**——需配合
内联的 `-fx-border-width`/`-fx-background-radius` 来定义形状。

```
两档高度 + 两档边框强度

  .sk-surface         抬高背景 ────  卡片 / 对话框主体
  .sk-surface-soft    悬浮背景 ────  内嵌面板、表头
  .sk-outlined        默认边框       分组控件框
  .sk-outlined-strong 强调边框       重点框 / 悬浮抬升
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-surface` | `zhiflow-common.css:131` | 抬高背景填充 |
| `.sk-surface-soft` | `zhiflow-common.css:132` | 柔和（悬浮档）背景填充 |
| `.sk-outlined` | `zhiflow-common.css:133` | 默认边框颜色（配合内联 width/radius） |
| `.sk-outlined-strong` | `zhiflow-common.css:134` | 强调边框颜色 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | `.sk-surface` —— 比基础背景高一档 |
| `-sk-bg-hover` | `.sk-surface-soft` —— 悬浮/可交互档 |
| `-sk-border` | `.sk-outlined` —— 默认细边框 |
| `-sk-border-strong` | `.sk-outlined-strong` —— 强调边框 |

### 4. 状态与修饰

无。要表达 hover/选中态的视觉效果，可在 Java 处理器中切换类，或组合一个已经编码了这些状态的组件
（例如 `.sk-table .table-row-cell:hover`）。

### 5. 布局与尺寸

`.sk-outlined` / `.sk-outlined-strong` **只**设置 `-fx-border-color`。你**必须**另外通过内联或
另一条规则提供 `-fx-border-width`（通常还有 `-fx-border-radius` + 配套的
`-fx-background-radius`），否则不会绘制边框。`.sk-surface*` 只设置 `-fx-background-color`。

### 6. JavaFX 模板

```java
// 一个带描边框的分组面板
VBox panel = new VBox();
panel.getStyleClass().addAll("sk-surface", "sk-outlined");
panel.setStyle("-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 12;");
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通用主题工具类小节）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)
- 命名约定：[02 JavaFX 实现 —— `#css-naming`](02-javafx-implementation.md#css-naming)

---

## F3 · 状态文本工具类

### 1. 概览与解剖

四个语义化文本颜色类，用于状态/引导文案（链接、成功/错误消息）。每个都与一个状态令牌 1:1 对应。
**绝不**用于装饰——绿色标签表示“成功”，而非“我喜欢绿色”（见 P4，[05 主题与配色系统](05-theme-color-system.md)）。

```
.sk-accent-text   链接 / 强调   →  -sk-accent
.sk-success-text  正向状态      →  -sk-success
.sk-warning-text  警示状态      →  -sk-warning
.sk-danger-text   错误状态      →  -sk-danger
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-accent-text` | `zhiflow-common.css:135` | 强调文本填充 —— 链接、内联强调 |
| `.sk-success-text` | `zhiflow-common.css:136` | 成功状态文本 |
| `.sk-warning-text` | `zhiflow-common.css:137` | 警告状态文本 |
| `.sk-danger-text` | `zhiflow-common.css:138` | 错误/危险状态文本 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-accent` | `.sk-accent-text` |
| `-sk-success` | `.sk-success-text` |
| `-sk-warning` | `.sk-warning-text` |
| `-sk-danger` | `.sk-danger-text` |

### 4. 状态与修饰

无。类即状态——要表示“这里曾是错误、现已成功”，把 `.sk-danger-text` 换成 `.sk-success-text`。

### 5. 布局与尺寸

仅颜色。按需配合内联字体属性。

### 6. JavaFX 模板

```java
// 链接样式的标签
Label link = new Label("Open docs");
link.getStyleClass().add("sk-accent-text");
link.setStyle("-fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");

// 校验错误消息
Label err = new Label("Invalid JSON");
err.getStyleClass().add("sk-danger-text");
err.setStyle("-fx-font-size: 12px;");
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通用主题工具类小节）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F4 · 图形填充工具类

### 1. 概览与解剖

两个 `-fx-fill` 工具类，用于 `Text`/`Shape` 节点（类 SVG 的字形、图标几何）。区别于设置
`-fx-text-fill` 的 `.sk-t*` 家族——JavaFX 对文本填充与图形填充使用不同属性，因此对于以 `Shape`
绘制的图标需要这些类。

```
.sk-fill-2  次要填充  →  -sk-text-secondary
.sk-fill-3  禁用填充   →  -sk-text-disabled
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-fill-2` | `zhiflow-common.css:129` | 次要 `-fx-fill`（Text/Shape） |
| `.sk-fill-3` | `zhiflow-common.css:130` | 禁用/弱化 `-fx-fill`（Text/Shape） |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-text-secondary` | `.sk-fill-2` |
| `-sk-text-disabled` | `.sk-fill-3` |

### 4. 状态与修饰

无。

### 5. 布局与尺寸

仅颜色。注意：宿主侧栏/导航图标目前仍在 `Sidebar.NavItem` 中通过内联
`setStyle("-fx-fill: #…")` 设置（已知的历史遗留 TODO）；对于**新**插件图标，优先使用
`.sk-fill-2`/`.sk-fill-3` 或 [06 图标系统](06-icon-system.md) 文档中的 `IconStyle` 强调色。

### 6. JavaFX 模板

```java
Text glyph = MdiIconUtil.createIcon("magnify", 16);
glyph.getStyleClass().add("sk-fill-3");   // 弱化的搜索字形
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通用主题工具类小节）
- 图标：[06 图标系统](06-icon-system.md)
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F5 · 遮罩（Scrim）

### 1. 概览与解剖

全幅半透明遮罩，置于模态 `Stage` 之后，用于压暗其余 UI、把注意力集中到对话框上。应用于透明
`Stage` 的根区域。压暗级别随主题变化（浅色主题下更轻，以免弄脏白色背景）。

```
┌──────────────────────────────────────┐
│           .sk-scrim（压暗层）         │
│    ┌────────────────────────────┐     │
│    │   .sk-dialog（模态主体）    │     │
│    │                            │     │
│    └────────────────────────────┘     │
└──────────────────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-scrim` | `zhiflow-common.css:139` | 模态背景填充 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-scrim` | 背景色 —— 深色 `rgba(0,0,0,0.50)` / 浅色 `rgba(15,23,42,0.32)` |

> **使用令牌，绝不用字面量。** 旧代码曾内联 `rgba(0,0,0,0.35)`；该字面量已移除。`.sk-scrim` 类是
> 唯一支持的压暗方式。

### 4. 状态与修饰

无。

### 5. 布局与尺寸

仅颜色（`-fx-background-color`）。遮罩区域应填满其 `Stage`；宿主通过把类加到全尺寸根节点并使用
`StageStyle.TRANSPARENT` 来实现。

### 6. JavaFX 模板

```java
StackPane root = new StackPane();
root.getStyleClass().add("sk-scrim");      // 在对话框后压暗
// 对话框内容作为子节点加入并居中
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通用主题工具类小节）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F6 · 输入框（Field）

### 1. 概览与解剖

唯一的文本输入组件。带边框、随主题变化的输入框，在获得焦点时抬升。类名是 `.sk-field`（不是
`.sk-text-field`）。配套的标签类提供字段上方的小号加粗标题。

```
  .sk-field-label（标题，11px 加粗次要）
  ┌──────────────────────────────────┐
  │ 在此输入…                          │  ← .sk-field
  └──────────────────────────────────┘
   未聚焦：边框 -sk-border，背景 -sk-bg
   聚焦：  边框 -sk-accent，背景 -sk-bg-elevated
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-field` | `zhiflow-common.css:152` | 文本输入（TextField/TextArea） |
| `.sk-field-label` | `zhiflow-common.css:166` | 字段上方的标题标签 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg` | 未聚焦背景 |
| `-sk-bg-elevated` | 聚焦背景（把字段抬起来） |
| `-sk-border` | 未聚焦边框 |
| `-sk-accent` | 聚焦边框（聚焦环颜色） |
| `-sk-text` | 输入文字颜色 |
| `-sk-text-secondary` | 标题颜色 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 默认 | `.sk-field` | `-sk-border` 边框，`-sk-bg` 背景，6px 圆角，`8 12` padding |
| 聚焦 | `.sk-field:focused`（行 162） | 边框 → `-sk-accent`，背景 → `-sk-bg-elevated` |

**不存在** `.sk-text-field`——该名称在源码中不存在。

### 5. 布局与尺寸

- 容器：`javafx.scene.control.TextField`（或 `TextArea`）。
- 几何（来自 CSS）：`1px` 边框，`6px` 圆角，padding `8 12 8 12`，字号 `13px`。
- 标签为 `11px`、加粗、次要色。

### 6. JavaFX 模板

```java
Label caption = new Label("OUTPUT PATH");
caption.getStyleClass().add("sk-field-label");

TextField input = new TextField();
input.getStyleClass().add("sk-field");
input.setPromptText("/path/to/file");
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（输入字段小节，行 151–166）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F7 · 按钮

### 1. 概览与解剖

两个按钮变体——**且只有两个**。没有基类 `.sk-btn`；两个变体都是独立类。Primary 是强调色填充的
动作按钮；Secondary 是带边框的幽灵按钮。

```
  .sk-btn-primary        .sk-btn-secondary
  ┌───────────────┐      ┌───────────────┐
  │   Save         │      │   Cancel       │
  └───────────────┘      └───────────────┘
   强调色填充，白色文字    悬浮档背景，带边框
   “执行”动作            安全/次要动作
```

**唯一原则：** 一个屏幕最多只能有一个 primary 按钮（默认/确认动作）。其余皆为 secondary。

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-btn-primary` | `zhiflow-common.css:282` | 强调色填充的动作按钮 |
| `.sk-btn-secondary` | `zhiflow-common.css:296` | 带边框的幽灵按钮 |

没有 `.sk-btn` 基类——不要臆造。

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-accent` | Primary 背景；hover/press 使用 `derive(-sk-accent, -8%/-16%)` |
| `-sk-bg-hover` | Secondary 默认背景 |
| `-sk-bg-selected` | Secondary 悬浮背景 |
| `-sk-border` / `-sk-border-strong` | Secondary 默认 / 悬浮边框 |
| `-sk-text` | Secondary 文字颜色（Primary 使用字面量 `white`） |

### 4. 状态与修饰

| 变体 | 默认 | 悬浮 | 按下 |
|---|---|---|---|
| `.sk-btn-primary` | 背景 `-sk-accent`，文字白 | 背景 `derive(-sk-accent,-8%)`（行 292） | 背景 `derive(-sk-accent,-16%)`（行 293） |
| `.sk-btn-secondary` | 背景 `-sk-bg-hover`，边框 `-sk-border` | 背景 `-sk-bg-selected`，边框 `-sk-border-strong`（行 307） | （无独立按下规则） |

两者都设置 `-fx-cursor: hand`。对于禁用按钮，使用 `button.setDisable(true)`（JavaFX 会将其变暗）。

### 5. 布局与尺寸

- 几何（来自 CSS）：`6px` 圆角，padding `8 18 8 18`，字号 `13px`，字重 `500`（primary）。容器：
  `javafx.scene.control.Button`。

> **遗留辅助类提示。** API 中存在 `UiUtils.glassBtn(text, primary)` 作为便捷方法，但它用**内联
> 十六进制**（`#3574F0`）而非 `.sk-btn-*` 类来构建按钮。对于主题正确、可重新换肤的按钮，请直接
> 使用 `.sk-btn-primary`/`.sk-btn-secondary`。

### 6. JavaFX 模板

```java
Button ok = new Button("Save");
ok.getStyleClass().add("sk-btn-primary");
ok.setDefaultButton(true);

Button cancel = new Button("Cancel");
cancel.getStyleClass().add("sk-btn-secondary");
cancel.setCancelButton(true);
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（主/次按钮小节，行 281–307）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F8 · 下拉框

### 1. 概览与解剖

随主题变化的 `ComboBox`/`ChoiceBox`。闭合控件镜像输入框外观（边框 + 背景），下拉弹层被重新样式化
为抬高的圆角菜单，带悬浮行与选中行。

```
  .sk-combo（闭合）                弹层（.combo-box-popup .list-view）
  ┌──────────────────┐ ▾           ┌──────────────────────┐
  │ Selected item     │             │ ▸ hovered row        │
  └──────────────────┘             │   selected row       │
                                    └──────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-combo` | `zhiflow-common.css:196` | 闭合下拉框 |
| `.sk-combo .list-cell` | `zhiflow-common.css:205` | 选中单元格文字 |
| `.sk-combo .arrow-button` | `zhiflow-common.css:206` | 下拉箭头按钮（透明） |
| `.sk-combo .arrow` | `zhiflow-common.css:207` | 下拉箭头字形 |
| `.combo-box-popup .list-view` | `zhiflow-common.css:209` | 弹层列表（抬高、圆角、带阴影） |
| `.combo-box-popup .list-view .list-cell` | `zhiflow-common.css:218` | 弹层行 |
| `.combo-box-popup .list-view .list-cell:filled:hover` | `zhiflow-common.css:224` | 悬浮行 |
| `.combo-box-popup .list-view .list-cell:filled:selected` | `zhiflow-common.css:229` | 选中行 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg` / `-sk-bg-elevated` | 闭合控件背景 / 弹层背景 |
| `-sk-border` | 控件 + 弹层边框 |
| `-sk-bg-hover` / `-sk-bg-selected` | 弹层行悬浮 / 选中背景 |
| `-sk-text` / `-sk-text-secondary` | 行文字 / 箭头字形 |
| `-sk-accent` | 选中行文字颜色 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 闭合 | `.sk-combo` | `-sk-bg` 背景，`-sk-border` 边框，6px 圆角 |
| 弹层悬浮 | `.list-cell:filled:hover` | 背景 → `-sk-bg-hover` |
| 弹层选中 | `.list-cell:filled:selected` | 背景 → `-sk-bg-selected`，文字 → `-sk-accent` |

### 5. 布局与尺寸

闭合控件：`1px` 边框，`6px` 圆角，字号 `13px`。弹层：`8px` 圆角，`1px` 边框，
`dropshadow(gaussian, rgba(0,0,0,0.40), 16, 0, 0, 6)`。容器：`javafx.scene.control.ComboBox`。

### 6. JavaFX 模板

```java
ComboBox<String> box = new ComboBox<>();
box.getStyleClass().add("sk-combo");
box.getItems().addAll("JSON", "YAML", "XML");
box.getSelectionModel().select(0);
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（下拉框小节，行 195–233）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F9 · 复选框

### 1. 概览与解剖

随主题变化的 `CheckBox`。未选中方框使用输入框外观（边框 + 基础背景）；选中时方框填充强调色，
勾选标记变白。

```
  ☐  .sk-checkbox（未选中）       ☑  .sk-checkbox:selected
      方框：-sk-bg / -sk-border        方框：-sk-accent，标记：white
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-checkbox` | `zhiflow-common.css:270` | 复选框标签 + 文字 |
| `.sk-checkbox .box` | `zhiflow-common.css:271` | 未选中方框 |
| `.sk-checkbox:selected .box` | `zhiflow-common.css:278` | 选中方框填充 |
| `.sk-checkbox:selected .mark` | `zhiflow-common.css:279` | 勾选标记 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-text` | 标签文字颜色 |
| `-sk-bg` | 未选中方框背景 |
| `-sk-border` | 未选中方框边框 |
| `-sk-accent` | 选中方框填充 + 边框 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 未选中 | `.sk-checkbox .box` | `-sk-bg` 背景，`-sk-border` 边框，4px 圆角 |
| 选中 | `.sk-checkbox:selected .box` | 背景 + 边框 → `-sk-accent` |
| 选中标记 | `.sk-checkbox:selected .mark` | 标记填充 → white |

### 5. 布局与尺寸

方框：`1px` 边框，`4px` 圆角。标签文字：`13px`。容器：`javafx.scene.control.CheckBox`。

### 6. JavaFX 模板

```java
CheckBox cb = new CheckBox("Pretty-print output");
cb.getStyleClass().add("sk-checkbox");
cb.setSelected(true);
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（复选框小节，行 269–279）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F10 · 表格

### 1. 概览与解剖

随主题变化的 `TableView`。抬高的圆角表面、悬浮档表头行、透明单元格边框，以及行的悬浮 + 选中状态。
选中行的文字会变为强调色。

```
┌──────────────────────────────────────────────┐  .sk-table（抬高，8px 圆角）
│ NAME            TYPE             SIZE         │  column-header（悬浮档背景）
├──────────────────────────────────────────────┤
│ json-formatter  builtin          12 KB        │  table-row-cell:hover → -sk-bg-hover
│ image-resizer   plugin           ───          │  table-row-cell:selected → -sk-bg-selected
│                                              │       （选中单元格文字 → -sk-accent）
└──────────────────────────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-table` | `zhiflow-common.css:251` | 表格表面 |
| `.sk-table .column-header-background` | `zhiflow-common.css:259` | 表头条背景 |
| `.sk-table .column-header` | `zhiflow-common.css:260` | 表头单元格 |
| `.sk-table .column-header .label` | `zhiflow-common.css:261` | 表头文字 |
| `.sk-table .table-cell` | `zhiflow-common.css:262` | 正文单元格 |
| `.sk-table .table-row-cell` | `zhiflow-common.css:263` | 正文行 |
| `.sk-table .table-row-cell:selected` | `zhiflow-common.css:264` | 选中行 |
| `.sk-table .table-row-cell:selected .table-cell` | `zhiflow-common.css:265` | 选中单元格文字 |
| `.sk-table .table-row-cell:hover` | `zhiflow-common.css:266` | 悬浮行 |
| `.sk-table .placeholder .label` | `zhiflow-common.css:267` | 空状态文字 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 表格表面背景 |
| `-sk-border` | 表面 + 表头单元格边框 |
| `-sk-bg-hover` | 表头条背景 + 行悬浮背景 |
| `-sk-bg-selected` | 选中行背景 |
| `-sk-text` | 正文单元格文字 |
| `-sk-text-secondary` | 表头文字 |
| `-sk-text-disabled` | 空状态占位文字 |
| `-sk-accent` | 选中行单元格文字 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 表头 | `.column-header-background` / `.column-header` | 悬浮档背景；每个表头下方有底边框 |
| 行悬浮 | `.table-row-cell:hover` | 背景 → `-sk-bg-hover` |
| 行选中 | `.table-row-cell:selected` | 背景 → `-sk-bg-selected`；单元格文字 → `-sk-accent` |
| 空 | `.placeholder .label` | 禁用色文字 |

单元格边框被强制透明（`-fx-table-cell-border-color: transparent`）以获得干净外观。

### 5. 布局与尺寸

表面：`1px` 边框，`8px` 圆角。表头文字：`12px`，字重 `500`。正文单元格：`13px`，padding
`6 10 6 10`。容器：`javafx.scene.control.TableView`。

### 6. JavaFX 模板

```java
TableView<Item> table = new TableView<>();
table.getStyleClass().add("sk-table");
// 照常添加 TableColumn<Item,String>；表头/单元格样式会自动应用
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（表格小节，行 250–267）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F11 · 标签页

### 1. 概览与解剖

随主题变化的 `TabPane`，采用扁平的 IDEA 风格标签页：透明标签区、下划线样式的选中指示器（选中
标签下方一条 2px 强调色细条），以及整个表头下方的一条透明边框。**选择器是嵌套的**——不存在独立的
`.sk-tab` 类。

```
  .sk-tab-pane .tab（透明，带 padding）
  ┌────────┬────────┬────────┐
  │  Tab 1 │  Tab 2 │  Tab 3 │   ← .sk-tab-pane .tab-header-area（1px 底边框）
  │════════│        │        │   ← .sk-tab-pane .tab:selected（2px 强调色下划线）
  └────────┴────────┴────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-tab-pane` | `zhiflow-common.css:169` | TabPane 根（透明，最小标签宽 100px） |
| `.sk-tab-pane .tab-header-area` | `zhiflow-common.css:170` | 表头 padding |
| `.sk-tab-pane .tab-header-area .tab-header-background` | `zhiflow-common.css:171` | 表头背景 + 底边框 |
| `.sk-tab-pane .tab` | `zhiflow-common.css:176` | 单个标签页 |
| `.sk-tab-pane .tab .tab-label` | `zhiflow-common.css:185` | 标签文字 |
| `.sk-tab-pane .tab:hover` | `zhiflow-common.css:186` | 悬浮标签页 |
| `.sk-tab-pane .tab:selected` | `zhiflow-common.css:187` | 选中标签页（下划线） |
| `.sk-tab-pane .tab:selected .tab-label` | `zhiflow-common.css:192` | 选中标签文字 |
| `.sk-tab-pane .tab:selected .focus-indicator` | `zhiflow-common.css:193` | 抑制聚焦环 |

> **不存在独立的 `.sk-tab`。** 规则始终是 `.sk-tab-pane .tab`（后代选择器）。源码中没有裸
> `.sk-tab`，写了也不会匹配任何东西。

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-border` | 表头底边框 |
| `-sk-text-secondary` | 未选中标签文字 |
| `-sk-text` | 选中标签文字 |
| `-sk-bg-hover` | 标签悬浮背景 |
| `-sk-bg-selected` | 选中标签背景 |
| `-sk-accent` | 选中标签下划线（底边框） |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 未选中 | `.tab` + `.tab .tab-label` | 透明背景；次要文字；padding `8 20` |
| 悬浮 | `.tab:hover` | 背景 → `-sk-bg-hover` |
| 选中 | `.tab:selected` | 背景 → `-sk-bg-selected`；2px 底边框 `-sk-accent` |
| 选中文字 | `.tab:selected .tab-label` | 文字 → `-sk-text` |
| 聚焦环 | `.tab:selected .focus-indicator` | 边框强制透明（无内环） |

标签圆角为 `6px 6px 0 0`（仅顶部）。

### 5. 布局与尺寸

- `tab-min-width: 100px`，`tab-max-height: 36px`。
- 标签 padding：`8 20 8 20`。标签文字：`13px`，字重 `500`。
- 表头区 padding：`4 8 0 8`。容器：`javafx.scene.control.TabPane`。

### 6. JavaFX 模板

```java
TabPane tabs = new TabPane();
tabs.getStyleClass().add("sk-tab-pane");
tabs.getTabs().addAll(
    new Tab("Input",  inputPane),
    new Tab("Output", outputPane)
);
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（TabPane 小节，行 168–193）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F12 · 对话框

### 1. 概览与解剖

用于独立 `Stage`（非原生 `Alert`）的随主题变化对话框表面。抬高的圆角卡片，1px 边框，柔和的
drop shadow 使用 `-sk-shadow` 令牌。配合 `.sk-scrim`（[F5](#f5--遮罩scrim)）作为背景。

```
   .sk-scrim（压暗）
   ┌────────────────────────────────────┐  .sk-dialog（抬高，10px 圆角）
   │  Title                              │     dropshadow(gaussian, -sk-shadow, 30, 0, 0, 12)
   │  Body message goes here.            │
   │            [ Cancel ]  [ OK ]       │
   └────────────────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-dialog` | `zhiflow-common.css:142` | 对话框表面 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 对话框背景 |
| `-sk-border` | 1px 框线 |
| `-sk-shadow` | drop shadow 颜色 —— 深色 `rgba(0,0,0,0.45)` / 浅色 `rgba(15,23,42,0.18)` |

> **阴影使用令牌。** dropshadow 为 `dropshadow(gaussian, -sk-shadow, 30, 0, 0, 12)`——
> 不是硬编码的 `rgba(0,0,0,…)` 字面量。请保持如此。

### 4. 状态与修饰

无 —— `.sk-dialog` 是静态表面。内部按钮使用 `.sk-btn-primary`/`.sk-btn-secondary` 或
`.sk-notif-ok`/`.sk-notif-cancel`。

### 5. 布局与尺寸

`1px` 边框，`10px` 圆角。阴影：半径 30，扩散 0，偏移 `(0, 12)`。容器：作为
`Stage(StageStyle.TRANSPARENT)` 根节点的 `VBox`/`StackPane`。

### 6. JavaFX 模板

```java
VBox dialog = new VBox(12);
dialog.getStyleClass().add("sk-dialog");
dialog.setPadding(new Insets(20, 24, 16, 24));
// 添加标题标签、正文，以及由 .sk-btn-secondary / .sk-btn-primary 组成的按钮栏
```

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（对话框小节，行 141–149）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)
- 为独立 Stage 应用主题：[02 JavaFX 实现 —— `#plugin-skeleton`](02-javafx-implementation.md#plugin-skeleton)

---

## F13 · 通知

### 1. 概览与解剖

毛玻璃通知系统，通过
[`SkNotification`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/component/SkNotification.java)
（`toast` / `notify` / `confirm`）暴露给插件。它是一个自包含的抬高卡片，带有严重程度着色的圆形
图标、自动换行的消息，以及可选的按钮栏。**不存在 `.sk-badge`，也不存在 `.sk-notification`**——该
家族是 `.sk-notif-*`。

```
┌────────────────────────────────────────────────┐  .sk-notif-root（420px，10px 圆角）
│   ╭───╮                                         │     dropshadow(gaussian, -sk-shadow, 28, 0, 0, 10)
│   │ ⚠ │  Warning title                          │
│   ╰───╯  Body message wraps inside 360px.        │
│                                                │
│                          [ Cancel ]  [   OK   ] │  .sk-notif-btn-bar
└────────────────────────────────────────────────┘
   图标：.sk-notif-icon（32px 圆形）+ 严重程度类
   严重程度用柔和令牌填充图标圆并染色字形
```

严重程度类应用在**图标**节点上（而非根节点）。它同时设置字形颜色与图标圆背景：

```
.sk-notif-info     字形 -sk-accent   背景 -sk-accent-soft
.sk-notif-success  字形 -sk-success  背景 -sk-success-soft
.sk-notif-warning  字形 -sk-warning  背景 -sk-warning-soft
.sk-notif-error    字形 -sk-danger   背景 -sk-danger-soft
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-notif-root` | `zhiflow-common.css:310` | 卡片表面（抬高、带边框、带阴影，宽 420px） |
| `.sk-notif-icon` | `zhiflow-common.css:320` | 32px 圆形图标容器 |
| `.sk-notif-info` | `zhiflow-common.css:326` | 信息严重程度（图标字形 + 柔和背景） |
| `.sk-notif-success` | `zhiflow-common.css:327` | 成功严重程度 |
| `.sk-notif-warning` | `zhiflow-common.css:328` | 警告严重程度 |
| `.sk-notif-error` | `zhiflow-common.css:329` | 错误严重程度 |
| `.sk-notif-message` | `zhiflow-common.css:330` | 正文文字（在 360px 处换行） |
| `.sk-notif-btn-bar` | `zhiflow-common.css:331` | 按钮容器 |
| `.sk-notif-ok` | `zhiflow-common.css:332` | Primary OK 按钮 |
| `.sk-notif-cancel` | `zhiflow-common.css:344` | Secondary Cancel 按钮 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 卡片背景 |
| `-sk-border` | 卡片框线 |
| `-sk-shadow` | 卡片 drop shadow 颜色 |
| `-sk-text` | 消息文字填充 |
| `-sk-accent` / `-sk-accent-soft` | 信息字形 + 柔和背景；OK 按钮背景 |
| `-sk-success` / `-sk-success-soft` | 成功字形 + 柔和背景 |
| `-sk-warning` / `-sk-warning-soft` | 警告字形 + 柔和背景 |
| `-sk-danger` / `-sk-danger-soft` | 错误字形 + 柔和背景 |
| `-sk-bg-hover` / `-sk-bg-selected` | Cancel 按钮背景 / 悬浮 |
| `-sk-text-secondary` | Cancel 按钮文字 |

> **柔和颜色使用新的柔和令牌。** 严重程度背景是 `-sk-success-soft`、`-sk-warning-soft`、
> `-sk-danger-soft` 和 `-sk-accent-soft`——**不是**已移除的旧 `rgba(76,217,123,0.15)` 字面量。
> 请以当前源码为准进行核对。

### 4. 状态与修饰

| 元素 | 默认 | 悬浮 |
|---|---|---|
| `.sk-notif-ok` | 背景 `-sk-accent`，白色文字（行 332） | 背景 `derive(-sk-accent,-8%)`（行 343） |
| `.sk-notif-cancel` | 背景 `-sk-bg-hover`，边框 `-sk-border`，次要文字（行 344） | 背景 `-sk-bg-selected`，文字 → `-sk-text`（行 355） |

四个严重程度类作为**第二个**类与 `.sk-notif-icon` 一起加到 `.sk-notif-icon` 节点上——正如
`SkNotification` 所做（`getStyleClass().addAll("sk-notif-icon", type.styleClass)`）。

### 5. 布局与尺寸

- 卡片：pref 宽 `420px`，max `480px`，`10px` 圆角，`1px` 边框，padding（在 Java 中设置）
  `20 24 16 24`。阴影：半径 28，扩散 0，偏移 `(0, 10)`。
- 图标：`32×32` 圆，字形 `22px`。
- 消息：`13.5px`，行距 `2px`，换行宽度 `360px`（在 Java 中设置）。
- OK/Cancel：`12.5px`，`6px` 圆角，padding `6 20`。容器：透明 `Stage`，调用
  `Themes.applyTo(scene)` 以使令牌解析。

### 6. JavaFX 模板

插件应调用辅助类，而非手动构造标记：

```java
// 自动消失的 toast（约 2.5s）
SkNotification.toast(view, SkNotification.Type.SUCCESS, "Saved");

// 带 OK 的模态
SkNotification.notify(view, SkNotification.Type.WARNING, "Check your input");

// 模态确认（阻塞；超时/关闭返回 false）
if (SkNotification.confirm(view, "Delete?", "This cannot be undone.")) {
    // ...执行破坏性动作
}
```

若必须手动构造（罕见），请镜像 `SkNotification.showOverlay`：一个 `.sk-notif-root`
`VBox`，内含一个 `HBox`（`.sk-notif-icon` + 严重程度 `Label` 与 `.sk-notif-message` `Text`），
再加一个 `.sk-notif-btn-bar`（`.sk-notif-cancel` + `.sk-notif-ok` `Button`）。

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（通知小节，行 309–355）
- Java：`ZhiFlow-Api/src/main/java/fan/summer/api/component/SkNotification.java`
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## F14 · 步骤向导指示器

### 1. 概览与解剖

`StepWizard` 的视觉指示器——一行水平的编号圆点，由连接线相连。已过步骤显示绿色勾，当前步骤是
带脉冲的强调色圆点，未来步骤为 idle。两个已完成步骤之间的连接线变绿。

```
   ✓─────────②─────────③        （current = 第 2 步，0 起索引 1）
   done      current    idle
   -sk-success  -sk-accent  -sk-bg-selected / -sk-border
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.sk-step-done` | `zhiflow-common.css:358` | 已完成圆点（填充 + 描边 `-sk-success`） |
| `.sk-step-current` | `zhiflow-common.css:359` | 当前圆点（填充 + 描边 `-sk-accent`） |
| `.sk-step-idle` | `zhiflow-common.css:360` | 未来圆点（填充 `-sk-bg-selected`，描边 `-sk-border`） |
| `.sk-step-line-done` | `zhiflow-common.css:361` | 已完成连接线（`-sk-success`） |
| `.sk-step-line-idle` | `zhiflow-common.css:362` | 未来连接线（`-sk-border`） |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-success` | done 圆点填充/描边 + done 连接线 |
| `-sk-accent` | current 圆点填充/描边 |
| `-sk-bg-selected` | idle 圆点填充（两个主题下都可见） |
| `-sk-border` | idle 圆点描边 + idle 连接线 |

### 4. 状态与修饰

状态类由 `StepWizard.refreshIndicator()` 在 **Java 中切换**（一个圆点在任一时刻只携带
`sk-step-done` / `sk-step-current` / `sk-step-idle` 之一）。每个圆点内的数字/勾标签使用
`.sk-t1`（done/current）或 `.sk-t3`（idle）。

### 5. 布局与尺寸

圆点为 `24×24` `StackPane` 内的 `Circle(12)`，描边宽 `1.5`。连接线为 `2px` 高的 `Region`，最小宽
`40`，可增长填满。向导主体对其底部按钮使用 `.sk-btn-primary`/secondary 样式（目前在
`StepWizard` 中通过内联十六进制实现——见按钮组件）。

### 6. JavaFX 模板

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  selectNode,  () -> file != null);
wizard.addStep("Configure",    configNode,  () -> configValid);
wizard.addStep("Confirm",      confirmNode, () -> true);
wizard.build();
```

（指示器样式是自动的——你无需自己应用 `.sk-step-*` 类。）

### 7. 参考

- CSS：`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`（StepWizard 指示器小节，行 357–362）
- Java：`ZhiFlow-Api/src/main/java/fan/summer/api/component/StepWizard.java`
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

# 第二部分 —— 壳层组件

> 来源：`ZhiFlow/src/main/resources/css/shell.css`。**仅宿主应用。** 这些类是应用壳的装饰件——
> 它们不会被加载到插件 Scene 上，插件不得依赖它们。此处记录它们，是为了使壳层本身能被忠实地重新
> 生成。

壳层遵循一条标志性的 IDEA New UI 规则：

> **选中态 = 中性灰填充 + 细强调色条，绝非蓝色铺满。** 选中的导航项是 `-sk-bg-selected` 加一条
> **左侧 3px** 的 `-sk-accent` 边框——而非强调色填充的整行。
> （见 [05 主题与配色系统](05-theme-color-system.md) 的 P3。）

---

## S1 · 导航项

### 1. 概览与解剖

左侧栏中的一行：图标 + 标签 + 可选计数徽标。默认扁平；激活项获得“中性填充 + 左侧色条”处理。由
`Sidebar` / 其内部 `NavItem` 承载。

```
   .nav-item（扁平，6px 圆角）
   ┌─────────────────────────────────────┐
   │ ▢  All Tools                 [ 3 ]  │  图标  标签（Hgrow）  徽标
   └─────────────────────────────────────┘
       │
       │  hover → 背景 -sk-bg-hover，标签 → -sk-text
       │  active → 背景 -sk-bg-selected + 左侧 3px -sk-accent 边框
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.nav-item` | `shell.css:54` | 项目行 |
| `.nav-item-icon` | `shell.css:65` | 图标字形 |
| `.nav-item-text` | `shell.css:66` | 标签文字 |
| `.nav-item:hover` | `shell.css:67` | 悬浮行 |
| `.nav-item.active` | `shell.css:69` | 激活（选中）行 |
| `.nav-item.active .nav-item-text` | `shell.css:74` | 激活标签 |
| `.nav-badge` | `shell.css:76` | 计数徽标 |
| `.nav-badge-new` | `shell.css:84` | “New”徽标变体 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-hover` | 行悬浮背景 |
| `-sk-bg-selected` | 激活行背景（中性填充） |
| `-sk-accent` | 激活行**左侧** 3px 边框 |
| `-sk-text-secondary` | 图标 + 标签默认色（以及徽标文字） |
| `-sk-text` | 悬浮/激活标签 |
| `-sk-success` | `.nav-badge-new` 文字 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 默认 | `.nav-item` | 透明背景，透明边框，`6px` 圆角，pref-height `32px` |
| 悬浮 | `.nav-item:hover` | 背景 → `-sk-bg-hover`；标签 → `-sk-text` |
| 激活 | `.nav-item.active` | 背景 → `-sk-bg-selected`；左边框 `3px` `-sk-accent`（标志性规则） |
| 激活标签 | `.nav-item.active .nav-item-text` | 文字 → `-sk-text`，字重 `500` |
| 折叠 | `.sidebar.collapsed .nav-item` | 图标居中，padding `8 0 8 0`；文字/徽标 opacity 0 |

激活边框写作 `transparent transparent transparent -sk-accent`，宽度 `0 0 0 3px`——即**仅左侧**。
图标的悬浮/激活填充目前在 `Sidebar.NavItem` 中内联设置（`#9AA0A6` idle，`#3574F0` 激活）——已知
的历史遗留 TODO；CSS `.nav-item-icon` 规则声明 `-sk-text-secondary` 为预期填充。

### 5. 布局与尺寸

行：`6px` 圆角，padding `7 10 7 12`，spacing `10px`，pref-height `32px`，对齐 `CENTER_LEFT`。
图标：`16px` MDI 字形，最小宽 `18px`。徽标：`20px` 圆角药丸，padding `1 7`，字体 `10px` 加粗。
容器：`HBox`（`Sidebar.NavItem extends HBox`）。

### 6. JavaFX 模板

壳层通过 `Sidebar`/`NavItem` 构建这些；忠实重建：

```java
HBox item = new HBox();
item.getStyleClass().add("nav-item");
item.setAlignment(Pos.CENTER_LEFT);
item.setSpacing(10);
item.setPrefHeight(32);

Text icon = MdiIconUtil.createIcon("view-grid", 16);
icon.getStyleClass().add("nav-item-icon");

Label text = new Label("All Tools");
text.getStyleClass().add("nav-item-text");
HBox.setHgrow(text, Priority.ALWAYS);

Label badge = new Label("3");
badge.getStyleClass().add("nav-badge");

item.getChildren().addAll(icon, text, badge);
// 选中时：item.getStyleClass().add("active");
```

### 7. 参考

- CSS：`ZhiFlow/src/main/resources/css/shell.css`（侧栏小节，行 53–84）
- Java：`ZhiFlow/src/main/java/fan/summer/ui/sidebar/Sidebar.java`（`NavItem` 内部类）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)
- 图标：[06 图标系统](06-icon-system.md)

---

## S2 · 搜索栏

### 1. 概览与解剖

位于内容区顶部的 IDEA“Search Everywhere”风格胶囊。一个完全圆角的容器，内含搜索图标、透明
`TextField` 和快捷键提示芯片。在 focus-within 时把边框提亮为强调色。

```
   .search-bar（胶囊，999px 圆角，34px 高）
   ╭──────────────────────────────────────────╮
   │ 🔍   Search tools…               ⌘K       │
   ╰──────────────────────────────────────────╯
    图标  .search-field（透明）       .search-kbd
       focused-within → 边框 -sk-accent，背景 -sk-bg
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.search-bar` | `shell.css:93` | 胶囊容器 |
| `.search-bar:focused-within` | `shell.css:103` | 聚焦态（任一子节点聚焦） |
| `.search-field` | `shell.css:107` | 内部 TextField（透明） |
| `.search-field:focused` | `shell.css:114` | 聚焦时保持内部字段透明 |
| `.search-icon` | `shell.css:214` | 前置放大镜字形 |
| `.search-kbd` | `shell.css:215` | 快捷键提示芯片 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 默认胶囊背景 |
| `-sk-bg` | focused-within 胶囊背景 |
| `-sk-border` | 默认胶囊边框 |
| `-sk-accent` | focused-within 边框 |
| `-sk-text` | 输入文字 |
| `-sk-text-disabled` | 提示文字 + 图标 + kbd 提示 |

### 4. 状态与修饰

| 状态 | 选择器 | 效果 |
|---|---|---|
| 默认 | `.search-bar` | `-sk-bg-elevated` 背景，`-sk-border` 边框，`999px` 圆角 |
| focused-within | `.search-bar:focused-within` | 背景 → `-sk-bg`，边框 → `-sk-accent` |
| 内部字段聚焦 | `.search-field:focused` | 保持透明（无内框） |

内部 `.search-field` 刻意无边框/透明，使得胶囊本身成为唯一可见的边框。

### 5. 布局与尺寸

胶囊：`999px` 圆角，pref-height `34px`，padding `0 14`，spacing `10px`。字段：`13px`，提示为禁用色。
kbd 芯片：`10px`，等宽，`4px` 圆角，padding `1 5`。容器：由 `ContentArea.buildSearchBar()` 构建的
`HBox`。

### 6. JavaFX 模板

```java
Label icon = new Label("🔍");
icon.getStyleClass().add("search-icon");

TextField field = new TextField();
field.getStyleClass().add("search-field");
field.setPromptText("Search tools…");
HBox.setHgrow(field, Priority.ALWAYS);

Label kbd = new Label("⌘K");
kbd.getStyleClass().add("search-kbd");

HBox bar = new HBox(10, icon, field, kbd);
bar.getStyleClass().add("search-bar");
bar.setAlignment(Pos.CENTER_LEFT);
bar.setPrefHeight(34);
```

### 7. 参考

- CSS：`ZhiFlow/src/main/resources/css/shell.css`（search-bar 小节，行 92–114；kbd 行 213–225）
- Java：`ZhiFlow/src/main/java/fan/summer/ui/content/ContentArea.java`（`buildSearchBar()`）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## S3 · 工具卡片

### 1. 概览与解剖

工具网格中的一个磁贴。抬高的圆角卡片，带彩色图标（在 48px 的容器内，携带一个 `.ic-*` 强调类）、
工具名、简短描述和内置/插件标签。悬浮时边框抬升为 strong，背景提为悬浮档；Java 在悬浮时增加缩放
+ 图标辉光，并播放错峰进入动画。

```
   .tool-card（抬高，8px 圆角，152×128）
   ┌───────────────────────────┐
   │                       ★    │  收藏星标（右上，Java）
   │   ╭────╮                   │
   │   │ ic │  Tool Name        │  .tool-icon-wrap（+ .ic-*）  .tool-name
   │   ╰────╯  Short desc…      │                              .tool-desc
   │                            │
   │   BUILTIN                  │  .tool-tag（+ .tool-tag-plugin）
   └───────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.tool-card` | `shell.css:117` | 卡片表面 |
| `.tool-card:hover` | `shell.css:129` | 悬浮抬升 |
| `.tool-icon-wrap` | `shell.css:131` | 48px 图标容器（透明；颜色来自 `.ic-*`/Java） |
| `.ic-blue` … `.ic-gray` | `shell.css:138–144` | 图标强调类 —— **空 CSS 规则**（颜色/辉光在 Java 中设置） |
| `.tool-name` | `shell.css:146` | 工具名文字 |
| `.tool-desc` | `shell.css:147` | 描述文字 |
| `.tool-tag` | `shell.css:148` | 内置/插件标签 |
| `.tool-tag-plugin` | `shell.css:158` | 插件标签变体（绿色） |

> **`.ic-*` 规则刻意为空。** 它们不带任何 CSS 属性——图标填充与 `DropShadow` 辉光在 Java 中由
> `IconStyle.getColor()` 注入（见 [06 图标系统](06-icon-system.md)）。该类仅作为语义钩子/未来
> CSS 钩子存在。

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 卡片背景 |
| `-sk-bg-hover` | 悬浮背景 |
| `-sk-border` / `-sk-border-strong` | 默认 / 悬浮边框 |
| `-sk-text` | 工具名 |
| `-sk-text-secondary` | 描述 + 标签文字 |
| `-sk-success` | `.tool-tag-plugin` 文字（+ 其内联绿色染色） |

### 4. 状态与修饰

| 状态 | 选择器 / 处理器 | 效果 |
|---|---|---|
| 默认 | `.tool-card` | `-sk-bg-elevated`，`-sk-border`，`8px` 圆角 |
| 悬浮（CSS） | `.tool-card:hover` | 背景 → `-sk-bg-hover`，边框 → `-sk-border-strong` |
| 悬浮（Java） | `ToolCard` 鼠标处理器 | 缩放 → 1.03，图标辉光半径 12→20 |
| 按下（Java） | 点击处理器 | 缩放下沉至 0.97 然后回调 |
| 进入（Java） | 构造器 | 280ms 内 fade+translate+scale（每卡错峰 35ms） |
| 后台运行 | Java | 右上绿色脉冲圆点 |

### 5. 布局与尺寸

卡片：`8px` 圆角，pref `152×128`，padding `14`，spacing `3px`。图标容器：`48×48`。名称：`13px`，
字重 `500`。描述：`11px`，wrap-text。标签：`10px`，`4px` 圆角，padding `1 6`。容器：`StackPane`
（`ToolCard extends StackPane`）包裹一个内部 `VBox`。

### 6. JavaFX 模板

壳层通过 `new ToolCard(plugin, onSelect, registry, favoriteService)` 构建卡片。忠实的手动构建：

```java
VBox card = new VBox();
card.getStyleClass().add("tool-card");
card.setSpacing(3);

Text icon = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);
icon.setStyle("-fx-fill: rgba(...);");          // 来自 IconStyle.getColor()
StackPane wrap = new StackPane(icon);
wrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass());
wrap.setPrefSize(48, 48);

Label name = new Label(plugin.getName());
name.getStyleClass().add("tool-name");
Label desc = new Label(plugin.getDescription());
desc.getStyleClass().add("tool-desc");
desc.setWrapText(true);
Label tag = new Label("BUILTIN");
tag.getStyleClass().add("tool-tag");

card.getChildren().addAll(wrap, name, desc, tag);
```

### 7. 参考

- CSS：`ZhiFlow/src/main/resources/css/shell.css`（tool-card 小节，行 116–158）
- Java：`ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java`
- 图标：[06 图标系统](06-icon-system.md)（以及 `IconStyle` 枚举）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## S4 · 详情面板

### 1. 概览与解剖

内容区右侧的滑入面板，在选中工具卡片时显示。抬高的表面，左侧边框 + 内嵌阴影，工具图标、
名称 + 版本/类型元信息、描述、Launch/Uninstall/Favorite 按钮，以及一个属性列表（版本/类型/分类）。
它从右侧滑入（translateX 260→0）。

```
   .detail-panel（抬高，左侧边框，260px 宽）
   ┌──────────────────────────────┐
   │                          [✕]  │  关闭（.sk-t3）
   │   ╭────╮                      │
   │   │ ic │  Tool Name           │  .tool-icon-wrap  .tool-name
   │   ╰────╯  v1.2.0 · builtin    │                   .status-text（元信息）
   │   Description wraps here.     │  .tool-desc
   │                              │
   │   [      LAUNCH         ]    │  .detail-launch-btn（强调色填充）
   │   [    UNINSTALL        ]    │  内联红色染色按钮
   │   [   ADD FAVORITE      ]    │  内联琥珀色染色按钮
   │                              │
   │   Version        1.2.0       │  .sk-t3 键 / .sk-t2 值（propRow）
   │   Type           builtin     │
   │   Category       text        │
   └──────────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.detail-panel` | `shell.css:161` | 面板表面 |
| `.detail-launch-btn` | `shell.css:171` | Launch 按钮（强调色填充） |
| `.detail-launch-btn:hover` | `shell.css:181` | Launch 悬浮 |
| `.detail-launch-btn:pressed` | `shell.css:182` | Launch 按下 |

该面板还复用基础类：`.tool-icon-wrap`、`.tool-name`、`.tool-desc`、`.status-text`（元信息），以及
`.sk-t1`/`.sk-t2`/`.sk-t3`（关闭按钮 + 属性键/值行）。

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 面板背景 |
| `-sk-border` | 左侧边框 |
| `-sk-accent` | Launch 按钮背景（+ hover/press `derive`） |
| `-sk-text` / `-sk-text-secondary` / `-sk-text-disabled` | 名称 / 描述 / 关闭+属性键 |

> shell.css 中该面板的 drop shadow 仍使用 `rgba(0,0,0,0.30)` 字面量（偏移 `(-4,0)`）——这是
> 宿主壳层的细节，不是令牌化的基础阴影。

### 4. 状态与修饰

| 元素 | 默认 | 悬浮 | 按下 |
|---|---|---|---|
| `.detail-launch-btn` | 背景 `-sk-accent`，白色文字 | 背景 `derive(-sk-accent,-8%)` | 背景 `derive(-sk-accent,-16%)` |

Uninstall/Favorite 按钮在 `DetailPanel` 中以**内联**方式（红/琥珀染色）样式化，而非通过可复用类——
它们是一次性的引导控件。滑入/滑出由 Java 中的 `Timeline` 驱动（300ms 进 / 250ms 出，spline 缓动）。

### 5. 布局与尺寸

面板：pref/min/max 宽 `260px`，padding `20 16 20 16`，spacing `10px`，左侧边框 `1px`，无圆角。
Launch 按钮：全宽，`6px` 圆角，pref-height `34px`。容器：`VBox`（`DetailPanel extends VBox`）。
面板叠加在 `StackPane` 中对齐 `TOP_RIGHT`，使其滑入时网格不会重排。

### 6. JavaFX 模板

壳层使用 `new DetailPanel()` 然后 `detailPanel.show(plugin)` / `detailPanel.hide()`。手动核心构建：

```java
VBox panel = new VBox();
panel.getStyleClass().add("detail-panel");
panel.setPrefWidth(260);

Button launch = new Button("Launch");
launch.getStyleClass().add("detail-launch-btn");
launch.setMaxWidth(Double.MAX_VALUE);
```

### 7. 参考

- CSS：`ZhiFlow/src/main/resources/css/shell.css`（detail-panel 小节，行 160–182）
- Java：`ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java`
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)
- 图标：[06 图标系统](06-icon-system.md)

---

## S5 · 状态栏

### 1. 概览与解剖

钉在主窗口底部的细条。左对齐的状态文字（工具/插件计数）由暗色圆点分隔，右侧是实时时钟，左侧有
一个小号脉冲绿色活动点。文字使用等宽字体以营造“控制台”感。

```
   .statusbar（抬高，顶边框，28px 高）
   ┌──────────────────────────────────────────────────────────┐
   │ ● 12 tools  ·  3 plugins                    14:02:09      │
   │ ↑ 活动点        ↑.status-text   ↑.status-sep    ↑时钟       │
   └──────────────────────────────────────────────────────────┘
```

### 2. CSS 类

| 类 | 源码行 | 用途 |
|---|---|---|
| `.statusbar` | `shell.css:185` | 条容器 |
| `.status-text` | `shell.css:194` | 状态/时钟文字（等宽） |
| `.status-sep` | `shell.css:200` | “·”分隔点 |

### 3. 使用的令牌

| 令牌 | 用途 |
|---|---|
| `-sk-bg-elevated` | 条背景 |
| `-sk-border` | 顶边框 |
| `-sk-text-secondary` | 状态/时钟文字 |
| `-sk-text-disabled` | 分隔点 |

脉冲活动点是 Java 的 `Circle`（`Color.web("#4cd97b")` + `Glow`），带一个 `FadeTransition`——其
颜色目前为内联，未令牌化。

### 4. 状态与修饰

无 —— 状态栏是静态的只读条。

### 5. 布局与尺寸

条：pref/min-height `28px`，顶边框 `1px`，padding `0 16`，spacing `12px`。文字：`12px`，等宽
（`"SF Mono", "Consolas", "Microsoft YaHei", monospace`）。容器：由 `MainWindow.buildStatusBar()`
构建的 `HBox`。一个 `Hgrow.ALWAYS` 的 `Region` 把时钟推到右侧。

### 6. JavaFX 模板

```java
HBox bar = new HBox(12);
bar.getStyleClass().add("statusbar");
bar.setAlignment(Pos.CENTER_LEFT);
bar.setPadding(new Insets(0, 16, 0, 16));

Label tools = new Label("12 tools");
tools.getStyleClass().add("status-text");

Label sep = new Label("·");
sep.getStyleClass().add("status-sep");

Label plugins = new Label("3 plugins");
plugins.getStyleClass().add("status-text");

Region spacer = new Region();
HBox.setHgrow(spacer, Priority.ALWAYS);

Label clock = new Label("14:02:09");
clock.getStyleClass().add("status-text");

bar.getChildren().addAll(tools, sep, plugins, spacer, clock);
```

### 7. 参考

- CSS：`ZhiFlow/src/main/resources/css/shell.css`（statusbar 小节，行 184–200）
- Java：`ZhiFlow/src/main/java/fan/summer/ui/MainWindow.java`（`buildStatusBar()`）
- 令牌：[05 主题与配色系统 —— 令牌参考表](05-theme-color-system.md#token-reference-table)

---

## 附录 · 类到组件的反向索引

反向索引：给定一个类，找到它的组件。

### 基础层类（`zhiflow-common.css`）

| 类 | 组件 |
|---|---|
| `.sk-t1` `.sk-t2` `.sk-t3` | [F1 文本工具类](#f1--文本工具类) |
| `.sk-fill-2` `.sk-fill-3` | [F4 图形填充工具类](#f4--图形填充工具类) |
| `.sk-surface` `.sk-surface-soft` | [F2 表面工具类](#f2--表面工具类) |
| `.sk-outlined` `.sk-outlined-strong` | [F2 表面工具类](#f2--表面工具类) |
| `.sk-accent-text` `.sk-success-text` `.sk-warning-text` `.sk-danger-text` | [F3 状态文本工具类](#f3--状态文本工具类) |
| `.sk-scrim` | [F5 遮罩](#f5--遮罩scrim) |
| `.sk-field` `.sk-field-label` | [F6 输入框](#f6--输入框field) |
| `.sk-btn-primary` `.sk-btn-secondary` | [F7 按钮](#f7--按钮) |
| `.sk-combo` | [F8 下拉框](#f8--下拉框) |
| `.sk-checkbox` | [F9 复选框](#f9--复选框) |
| `.sk-table` | [F10 表格](#f10--表格) |
| `.sk-tab-pane` | [F11 标签页](#f11--标签页) |
| `.sk-dialog` | [F12 对话框](#f12--对话框) |
| `.sk-notif-root` `.sk-notif-icon` `.sk-notif-info` `.sk-notif-success` `.sk-notif-warning` `.sk-notif-error` `.sk-notif-message` `.sk-notif-ok` `.sk-notif-cancel` | [F13 通知](#f13--通知) |
| `.sk-step-done` `.sk-step-current` `.sk-step-idle` `.sk-step-line-done` `.sk-step-line-idle` | [F14 步骤向导指示器](#f14--步骤向导指示器) |

### 壳层类（`shell.css`）

| 类 | 组件 |
|---|---|
| `.nav-item` `.nav-item-icon` `.nav-item-text` `.nav-badge` `.nav-badge-new` | [S1 导航项](#s1--导航项) |
| `.search-bar` `.search-field` `.search-icon` `.search-kbd` | [S2 搜索栏](#s2--搜索栏) |
| `.tool-card` `.tool-icon-wrap` `.ic-*` `.tool-name` `.tool-desc` `.tool-tag` `.tool-tag-plugin` | [S3 工具卡片](#s3--工具卡片) |
| `.detail-panel` `.detail-launch-btn` | [S4 详情面板](#s4--详情面板) |
| `.statusbar` `.status-text` `.status-sep` | [S5 状态栏](#s5--状态栏) |

### 不存在的名称（请勿使用）

| 不存在的名称 | 正确替代 |
|---|---|
| `.sk-btn` | `.sk-btn-primary` 或 `.sk-btn-secondary` |
| `.sk-text-field` | `.sk-field` |
| `.sk-badge` | `.nav-badge`（壳层）或 `.tool-tag`（壳层） |
| `.sk-notification` | `.sk-notif-*` 家族 |
| `.sk-tab`（独立） | `.sk-tab-pane .tab`（嵌套） |

---

## 参考

### 源文件（权威）

- 基础层 CSS：[`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css)
- 壳层 CSS：[`ZhiFlow/src/main/resources/css/shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css)
- 壳层 Java：
  - [`ZhiFlow/src/main/java/fan/summer/ui/sidebar/Sidebar.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/sidebar/Sidebar.java)（`NavItem`）
  - [`ZhiFlow/src/main/java/fan/summer/ui/content/ContentArea.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/ContentArea.java)（search/grid）
  - [`ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java)
  - [`ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java)
  - [`ZhiFlow/src/main/java/fan/summer/ui/MainWindow.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/MainWindow.java)（status bar）
- 基础层 Java：
  - [`ZhiFlow-Api/src/main/java/fan/summer/api/component/SkNotification.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/component/SkNotification.java)
  - [`ZhiFlow-Api/src/main/java/fan/summer/api/component/StepWizard.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/component/StepWizard.java)
  - [`ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java)

### 关联 UI 设计文档

- [01 设计系统](01-design-system.md)
- [02 JavaFX 实现](02-javafx-implementation.md) —— [`#css-naming`](02-javafx-implementation.md#css-naming)、[`#plugin-skeleton`](02-javafx-implementation.md#plugin-skeleton)
- [05 主题与配色系统](05-theme-color-system.md) —— [`#token-reference-table`](05-theme-color-system.md#token-reference-table)
- [06 图标系统](06-icon-system.md)
