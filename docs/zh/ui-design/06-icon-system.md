# 06 · 图标系统

> **定位：** 本文档是 ZhiFlow 中图标的**唯一事实来源**——图标库、`MdiIconUtil` API、
> 尺寸刻度、`IconStyle` 强调色调色板，以及那个让每位新手作者都会踩坑的**反直觉事实**：
> `.ic-*` CSS 类是**空的**，因此图标颜色 + 辉光是从 **Java** 注入的，绝不能来自 CSS。
> 组件库（文档 03）在此链接图标用法；请收藏锚点 [`#icon-reference`](#icon-reference)。

| | |
|---|---|
| **文档类型** | 图标规范 + 渲染 API |
| **目标读者** | 插件作者、AI 代码生成器、任何需要把字形画上屏的开发者 |
| **事实来源** | [`ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java) · [`IconStyle.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java) |
| **关联文档** | [02 JavaFX 实现](02-javafx-implementation.md) · [03 组件库](03-component-library.md) · [05 主题与配色系统](05-theme-color-system.md) |

> **翻译说明：** 本文件是英文版 [`docs/ui-design/06-icon-system.md`](../ui-design/06-icon-system.md)
> 的中文镜像，结构完全一致。所有代码、类名、枚举值、RGB 值、文件路径均逐字保留，不作翻译。

---

## 目录

1. [概述](#1-概述)
2. [设计原则](#2-设计原则)
3. [规格表](#3-规格表)
   - [3.1 `MdiIconUtil` API](#mdiiconutil-api)
   - [3.2 尺寸刻度](#尺寸刻度)
   - [3.3 `IconStyle` 强调色调色板](#iconstyle-强调色调色板)
   - [3.4 内置工具图标映射](#内置工具图标映射)
4. [JavaFX 实现模板](#4-javafx-实现模板)
5. [AI 开发清单](#5-ai-开发清单)
6. [反模式](#6-反模式)
7. [参考资料](#7-参考资料)

---

## 1. 概述

ZhiFlow 的**所有**图标都来自同一个打包字体：Pictogrammers 的 **Material Design Icons**（MDI）。
没有 PNG、没有 SVG、没有 emoji——每个字形都是取自 `MaterialDesignIcons` 字体族的一个
Unicode 码位。

```
┌──────────────────────────────────────────────────────────────────────┐
│  一个图标是如何上屏的                                                 │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│   plugin.getMdiIcon()        "file-excel"   (名称，不带 "mdi-" 前缀) │
│            │                                                          │
│            ▼                                                          │
│   MdiIconUtil.createIcon(name, 45)                                    │
│            │  ┌─ 在 mdi-codemap.properties 中查码位 ──────────┐       │
│            │  │  file-excel → \uDB80\uDE1B                     │       │
│            │  │  (未知名称? → 回退到 "star")                   │       │
│            │  └────────────────────────────────────────────────┘       │
│            ▼                                                          │
│   new Text(codepoint)  +  字体 "MaterialDesignIcons" @ 指定尺寸       │
│            │                                                          │
│            ▼                                                          │
│   宿主填充：   icon.setFill(plugin.getIconStyle().getColor())         │
│   宿主加辉光： DropShadow(color, radius 12, spread 0.15)              │
│            │                                                          │
│            ▼                                                          │
│   StackPane.tool-icon-wrap (48×48)  →  工具卡片                       │
└──────────────────────────────────────────────────────────────────────┘
```

### 三份资源

| 内容 | 位置 | 说明 |
|---|---|---|
| **码位表** | [`ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties`](../../../ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties) | **7 448** 条名称 → 码位映射。键是裸 MDI 名称（`file-excel`，**不是** `mdi-file-excel`）。在首次使用 `MdiIconUtil` 时惰性加载。 |
| **字体二进制** | `/fonts/materialdesignicons-webfont.ttf`（classpath，打包在 API 模块） | 字体族注册名为 `MaterialDesignIcons`。与码位表一同打包。 |
| **渲染 API** | [`MdiIconUtil.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java) | 插件代码应使用的**唯一**入口。 |

### 你要用到的唯一 API

```java
import fan.summer.zhiflow.api.MdiIconUtil;
import javafx.scene.text.Text;

// 1. 名称不带 "mdi-" 前缀；尺寸单位为逻辑像素
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);

// 2. 未知名称会静默回退到 "star" 字形——务必核对拼写！
Text fallback = MdiIconUtil.createIcon("does-not-exist", 24.0); // 渲染出 ★
```

完整的图标目录（可视化浏览名称）见
[MDI 库](https://pictogrammers.com/library/mdi/)。一份关于创建图标的 20 行速成指南在
[02 JavaFX 实现 §4.2](02-javafx-implementation.md#42-图标--mdiiconutil)；本文是深入版。

---

## 2. 设计原则

### P1 — 一个字体库，一个渲染器

产品里的每个图标都来自 MDI 字体，通过 `MdiIconUtil.createIcon(...)` 绘制。**绝不**引入第二
个图标来源——不要 SF Symbols、不要 FontAwesome、不要手搓 SVG 路径、不要带图标资源的
`ImageView`。混用字体会破坏视觉一致性（描边粗细、光学尺寸、网格）并让字体体积翻倍。如果
缺某个字形，用 `putIcon()`（P6）注册——不要引入另一个字体。

### P2 — 图标传达含义，绝不作纯装饰

图标必须承载标签（或上下文）尚未完整传达的信息。如果去掉图标对理解毫无影响，那它就是装饰，
应当删除。工具的 `getMdiIcon()` 字形是该工具的主要视觉身份——选一个能读出该工具*动词或名词*
的图标（邮件工具用 `email`、Excel 拆分器用 `file-excel`），而不是一个通用的闪光。

> **可访问性：** 图标本身不是标签。工具卡片总是把字形与 `.tool-name` 标签配对；不要发布一个
> 没有可访问名称（`accessibleText` / tooltip）的纯图标控件。

### P3 — 名称是裸的；绝不加 `mdi-` 前缀

`getMdiIcon()` 和 `createIcon(name, …)` 都接收**不带 `mdi-` 前缀的 MDI 名称**：
`"file-excel"`、`"code-json"`、`"folder-open"`。目录网站把图标列为 `mdi-file-excel`；
返回前**去掉前缀**。带前缀的名称在码位表中不存在，会静默回退到 `star`。

```
正确：    "file-excel"      ✅   渲染 Excel 字形
错误：    "mdi-file-excel"  ❌   码位表中没有 → 静默渲染 "star"
```

### P4 — 颜色遵循令牌系统；`IconStyle` 是唯一例外

通用 UI 图标（导航项、工具栏按钮、状态字形）的填充来自
[05 主题与配色系统](05-theme-color-system.md)定义的令牌系统：它们以
`-sk-text-secondary` / `-sk-text-disabled` 渲染，从而在用户切换深色↔浅色时自动重染。为此请在
`Text` 节点上使用**工具样式类** `.sk-fill-2` / `.sk-fill-3`——**不要**写内联
`setStyle("-fx-fill: -sk-text-secondary;")`（见 [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)：
内联 `setStyle` 无法解析 looked-up 颜色变量）。

**工具图标是例外。** 工具的强调色是**品牌身份**，而非主题文本，因此从 Java 通过
`IconStyle.getColor()` 设置（见 [§4](#4-javafx-实现模板)）。它刻意**不**跟随主题。

### P5 — 选一个 `IconStyle`，但由宿主渲染

当你编写工具时，从 `getIconStyle()` 返回一个 `IconStyle`（七种强调色之一）。**对工具图标，
你不要自己去给节点上色**——宿主（`ToolCard`、`DetailPanel`）会读取
`getIconStyle().getColor()`、填充 `Text`、并施加 `DropShadow` 辉光。你只需*选择*样式；见下方
[关键的 `.ic-*` 说明](#关键的-ic--陷阱)。

### P6 — 扩展，不要分叉

对于打包集合中没有的、插件专用的字形，在运行时注册：

```java
MdiIconUtil.putIcon("my-plugin-mark", "\uDB81\uDC93"); // 来自你字体子集的码位
```

此后 `createIcon("my-plugin-mark", 24)` 就能像内置图标一样工作。这保住了单一渲染器契约（P1）。

---

## 3. 规格表

<a id="icon-reference"></a>

### MdiIconUtil API

所有方法都是 `fan.summer.zhiflow.api.MdiIconUtil` 上的 `static`。该类惰性加载码位表和字体，并在进程
生命周期内缓存。

| 签名 | 返回 | 行为 |
|---|---|---|
| `createIcon(String name, double size)` | `javafx.scene.text.Text` | `size` 像素的字形 `Text`。未知 `name` → 回退到 `"star"` 字形。默认填充为**白色**（`-fx-fill: white;`）；按 [§4](#4-javafx-实现模板) 覆盖。 |
| `createIcon(String name, double size, String extraStyle)` | `javafx.scene.text.Text` | 同上，并在默认白色填充后追加额外的内联 CSS（如 `"-fx-fill: #FF5722;"`）。`extraStyle` 可为 `null`（此时等同单参形式）。 |
| `getCodepoint(String name)` | `String` | `name` 的原始 Unicode 码位字符串（一个代理对）；未知 → `"star"` 的码位。便于你自建 `Label`/`Text` 时嵌入。 |
| `getFont(double size)` | `javafx.scene.text.Font` | 指定 `size` 的 `MaterialDesignIcons` 字体；若字体资源无法加载则返回 `null`。 |
| `putIcon(String name, String codepoint)` | `void` | 在运行时注册/覆盖一条 name → codepoint 映射（插件自定义字形）。 |

> **校验锚点**——上述四个读取签名恰好是：
> ```
> public static Text   createIcon(String iconName, double size)
> public static Text   createIcon(String iconName, double size, String extraStyle)
> public static String getCodepoint(String iconName)
> public static Font   getFont(double size)
> ```
> 外加 `public static void putIcon(String name, String codepoint)`。

**回退行为**——`createIcon` 和 `getCodepoint` 都使用
`CODEMAP.getOrDefault(name, CODEMAP.get("star"))`。因此拼写错误会*静默*渲染出*某个东西*
（一颗星）。发布前务必确认名称存在于
[`mdi-codemap.properties`](../../../ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties)。

### 尺寸刻度

| 尺寸 | 令牌用途 | 使用位置 | 来源 |
|---|---|---|---|
| **16 px** | 内联 / 状态字形 | 侧栏导航图标（`Sidebar` 用 `createIcon(mdi, 16, …)`） | 代码 |
| **18 px** | nav-item 图标 | `.nav-item-icon { -fx-min-width: 18px; }` | [`shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css) |
| **20 px** | 小型 UI 控件 | 紧凑工具栏 / chip 字形 | 约定 |
| **24 px** | 标准 / 卡片图标 | 你在插件视图里渲染独立图标时的默认值 | 约定 |
| **32 px** | 大号内联 | 空状态主视觉字形 | 约定 |
| **45 px** | 工具卡片字形 | `MdiIconUtil.createIcon(plugin.getMdiIcon(), 45)`，置于 48 px 容器内 | [`ToolCard.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java) |
| **48 px** | tool-icon-wrap *容器* | `.tool-icon-wrap { -fx-pref-width: 48px; -fx-pref-height: 48px; }` | [`shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css) |
| **50 px** | 详情面板主视觉 | `MdiIconUtil.createIcon(p.getMdiIcon(), 50)` | [`DetailPanel.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java) |

```
16 ─── 内联 / 状态          24 ─── 标准卡片图标
18 ─── nav-item             32 ─── 大号内联
20 ─── 小型控件             45 ─── 工具卡片字形（在 48px 容器内）
                            50 ─── 详情面板主视觉
```

> **容器 vs 字形：** `tool-icon-wrap` *容器*是 48 px；其内绘制的*字形*以 45 px 渲染（留出一点
> 光学内边距）。引用“工具图标尺寸”时，请明确说 45 px（字形）或 48 px（磁贴）——说清是哪个。

### IconStyle 强调色调色板

`fan.summer.zhiflow.api.IconStyle`——七种强调样式。每个携带一个**CSS 类名**和一个
**`javafx.scene.paint.Color`**。CSS 类施加于图标*容器*；颜色从 Java 施加于 `Text` *字形*。
（见[那个陷阱](#关键的-ic--陷阱)。）

| `IconStyle` | CSS 类 | RGB | 等价 `Color` | 默认填充于... |
|---|---|---|---|---|
| `BLUE` | `ic-blue` | **99, 130, 255** | `Color.rgb(99, 130, 255)` | （`getIconStyle()` 的默认值） |
| `PURPLE` | `ic-purple` | **160, 110, 255** | `Color.rgb(160, 110, 255)` | AiChat |
| `TEAL` | `ic-teal` | **40, 210, 140** | `Color.rgb(40, 210, 140)` | Base64 / Excel / EmailArchive / Browser |
| `AMBER` | `ic-amber` | **255, 185, 50** | `Color.rgb(255, 185, 50)` | HashCalculator |
| `RED` | `ic-red` | **255, 100, 100** | `Color.rgb(255, 100, 100)` | PdfTool |
| `PINK` | `ic-pink` | **245, 100, 160** | `Color.rgb(245, 100, 160)` | ColorConverter |
| `GRAY` | `ic-gray` | **200, 200, 210** | `Color.rgb(200, 200, 210)` | （无任何内置工具使用） |

**`IconStyle` 的方法：**

| 方法 | 返回 | 行为 |
|---|---|---|
| `getCssClass()` | `String` | 容器类名，如 `"ic-teal"`。 |
| `getColor()` | `javafx.scene.paint.Color` | 用于填充字形**并**作为 `DropShadow` 辉光颜色的强调色。 |
| `static fromCssClass(String)` | `IconStyle` | 按 CSS 类做大小写不敏感查找；`null` / 未知时返回 `BLUE`。 |

> 上表 7 组 RGB 是产品中**仅有的**强调色值。不要发明新的图标颜色。若需要非强调色，必须取自
> [05 主题与配色系统](05-theme-color-system.md)的令牌系统。

### 内置工具图标映射

11 个内置工具（在
[`BuiltinToolRegistrar`](../../../ZhiFlow/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java)
中注册）按如下方式映射到 `IconStyle` 强调色。`getMdiIcon()` 的值均为裸名称。

| 工具（插件类） | `getMdiIcon()` | `getIconStyle()` | RGB |
|---|---|---|---|
| `JsonFormatterPlugin` | `code-json` | `BLUE` | 99, 130, 255 |
| `MarkdownEditorPlugin` | `language-markdown` | `BLUE` | 99, 130, 255 |
| `EmailPlugin` | `email` | `BLUE` | 99, 130, 255 |
| `AiChatPlugin` | `robot-outline` | `PURPLE` | 160, 110, 255 |
| `Base64Plugin` | `base64` | `TEAL` | 40, 210, 140 |
| `ExcelSplitterPlugin` | `file-excel` | `TEAL` | 40, 210, 140 |
| `EmailArchivePlugin` | `email-check` | `TEAL` | 40, 210, 140 |
| `BrowserAutomatePlugin` | `web` | `TEAL` | 40, 210, 140 |
| `HashCalculatorPlugin` | `key-variant` | `AMBER` | 255, 185, 50 |
| `ColorConverterPlugin` | `palette` | `PINK` | 245, 100, 160 |
| `PdfToolPlugin` | `file-pdf-box` | `RED` | 255, 100, 100 |

**样式分布：** `GRAY` 默认**不**被任何内置工具使用；新工具可将其用于中性/工具类。新增工具时，
优先选择在目标屏幕上**用得较少**的 `IconStyle`，以保持网格视觉平衡——不要再往 `BLUE` 上堆
四个工具。

---

## 4. JavaFX 实现模板

<a id="关键的-ic--陷阱"></a>

### 关键的 `.ic-*` 陷阱——先读这一段

[`shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css) 中的 `.ic-blue`、
`.ic-purple`、… `.ic-gray` 规则是**空的**：

```css
/* 图标配色 — 颜色注入到 Text 节点上, glow 由 Java 代码通过 DropShadow 设置 */
.ic-blue   { }
.ic-purple { }
.ic-teal   { }
.ic-amber  { }
.ic-red    { }
.ic-pink   { }
.ic-gray   { }
```

它们**不带任何颜色、背景、边框**。这个类施加于图标*容器*，纯粹是为了让应用其余部分（以及
截图）能*命名*某个磁贴使用的是哪种样式——它**不是**颜色钩子。真正的颜色和辉光来自 **Java**：

```
错误的心智模型：
   给容器加 ".ic-blue"  ──▶  期望蓝色图标        ❌  （空规则 → 什么都没有）

正确的事实：
   icon.setFill(style.getColor())  ──▶  蓝色字形            ✅
   glow.setColor(style.getColor().deriveColor(0,1,1,0.75))  ✅  （辉光）
   wrapper.getStyleClass().add(style.getCssClass())         ◻  （仅作标记）
```

**一个 AI 若设置 `.ic-blue` 期望得到蓝色，会得到一个白色图标。** 工具图标的 `Text` 始终要从
Java 通过 `IconStyle.getColor()` 填充。

### 4.1 创建通用的、跟随主题的图标（导航 / 工具栏 / 状态）

对于应当随主题重染的图标（非工具 UI 的常见情形），使用
[`zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) 中的
`.sk-fill-2` / `.sk-fill-3` **工具样式类**：

```css
/* zhiflow-common.css */
.sk-fill-2 { -fx-fill: -sk-text-secondary; }   /* Text/Shape 填充（次要） */
.sk-fill-3 { -fx-fill: -sk-text-disabled; }    /* Text/Shape 填充（弱化） */
```

```java
import fan.summer.zhiflow.api.MdiIconUtil;
import javafx.scene.text.Text;

// 次要色调、自动跟随深色/浅色主题的图标
Text navIcon = MdiIconUtil.createIcon("folder-open", 18.0);
navIcon.getStyleClass().add("sk-fill-2");   // 由 CSS 解析 -sk-text-secondary
```

> **为什么用样式类而不是内联 `setStyle`？** `setStyle("-fx-fill: -sk-text-secondary;")` **不**
> 起作用——内联样式字符串无法解析 looked-up 颜色变量（见
> [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)）。`.sk-fill-*` 样式类由
> CSS 引擎针对节点的 scene 求值，因此它能解析令牌*并*跟随主题切换。用它。

### 4.2 直接设置字面 / 强调色（少见）

如果你必须用一个**既非**令牌**又非** `IconStyle` 的一次性颜色（不推荐），从 Java 在返回的
`Text` 节点上设置填充——这对字面颜色*确实*有效：

```java
Text warn = MdiIconUtil.createIcon("alert", 16.0);
warn.setFill(javafx.scene.paint.Color.web("#FFB320"));   // 从 Java 传字面 hex 没问题
// 或用三参形式，它会在默认白色填充后追加：
Text warn2 = MdiIconUtil.createIcon("alert", 16.0, "-fx-fill: #FFB320;");
```

当含义是“琥珀强调”时，优先用 `IconStyle.AMBER.getColor()` 而非字面值。

### 4.3 渲染工具卡片图标（规范模式）

这正是 [`ToolCard.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java) 所做的。
插件提供*名称*（`getMdiIcon()`）和*样式*（`getIconStyle()`）；宿主完成填充 + 辉光：

```java
import fan.summer.zhiflow.api.IconStyle;
import fan.summer.zhiflow.api.MdiIconUtil;
import fan.summer.zhiflow.api.SwissKitJPlugin;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

SwissKitJPlugin plugin = /* ... */;

// 1. 读取插件选择的强调色
Color iconColor = plugin.getIconStyle().getColor();          // 如 TEAL → rgb(40,210,140)

// 2. 以 45px 渲染字形，从 Java 填充（不是靠 .ic-* CSS！）
Text iconText = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);   // 裸名称，不带 "mdi-"
iconText.setStyle(String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
        (int)(iconColor.getRed()   * 255),
        (int)(iconColor.getGreen() * 255),
        (int)(iconColor.getBlue()  * 255)));
//   等价写法： iconText.setFill(iconColor);

// 3. 加辉光——同样的强调色，略带透明
DropShadow glow = new DropShadow();
glow.setColor(iconColor.deriveColor(0, 1, 1, 0.75));   // alpha 0.75
glow.setRadius(12);
glow.setSpread(0.15);
iconText.setEffect(glow);

// 4. 容器同时拿到两个类：尺寸类（真实生效）+ .ic-* 类（仅作标记）
StackPane iconWrap = new StackPane(iconText);
iconWrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass());
iconWrap.setPrefSize(48, 48);
iconWrap.setMinSize(48, 48);
```

辉光参数（`radius 12`、`spread 0.15`、`deriveColor(0,1,1,0.75)`）是既定的内部取值——逐字
复用，确保所有工具图标辉光一致。

### 4.4 编写插件的图标契约

在你的插件里，只需返回这两个值——**不要**自己去构建或给节点上色：

```java
@Override public String getMdiIcon()    { return "file-excel"; }   // 裸名称，不带 "mdi-"
@Override public IconStyle getIconStyle() { return IconStyle.TEAL; } // 宿主完成其余
```

若覆盖 `getIconStyle()`，返回七个 `IconStyle` 值之一——绝不要手搓颜色。其 `default` 为
`IconStyle.BLUE`。

### 4.5 查找一个 MDI 名称（确认它存在）

发布新的 `getMdiIcon()` 字符串前，确认该键存在于码位表：

```java
// 未知名称返回 "star" 的码位——所以要核对是否与 "star" 混淆；
// 更好的做法是 grep 该 properties 文件：
String cp = MdiIconUtil.getCodepoint("file-excel");  // "\uDB80\uDE1B"
```

```bash
# 确认名称在打包码位表中（7448 条）
grep -nE '^file-excel=' ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties
# → file-excel=\uDB80\uDE1B
```

在 [MDI 库](https://pictogrammers.com/library/mdi/)可视化浏览名称，然后返回名称前去掉
`mdi-` 前缀。

---

## 5. AI 开发清单

生成与图标相关的代码时，逐条核对**每一项**：

- [ ] **名称格式**——`getMdiIcon()` / `createIcon(name, …)` 返回/使用的是裸 MDI 名称，**不带**
      `mdi-` 前缀（`"file-excel"`，不是 `"mdi-file-excel"`）。
- [ ] **名称存在**——该名称存在于
      [`mdi-codemap.properties`](../../../ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties)；
      未知名称会静默回退到 `star`。
- [ ] **尺寸在刻度上**——尺寸是 `16 / 18 / 20 / 24 / 32 / 45 / 50` 之一（见
      [尺寸刻度](#尺寸刻度)）；工具卡片字形 = 45，容器 = 48。
- [ ] **工具图标颜色来自 Java，不是 CSS**——通过 `plugin.getIconStyle().getColor()`
      （或 `icon.setFill(IconStyle.X.getColor())`）填充。`.ic-*` 类**仅作标记**（空规则）。
- [ ] **通用 UI 图标跟随主题**——非工具图标用 `.sk-fill-2` / `.sk-fill-3` 样式类，**不要**用
      内联 `setStyle("-fx-fill: -sk-text-secondary;")`（它无法解析 looked-up 颜色）。
- [ ] **`IconStyle` 按含义 + 平衡来选**——七个值之一；在目标屏幕上优先选较少使用的强调色；
      `GRAY` 可用于中性工具。
- [ ] **不用 emoji 当图标**——emoji 是另一个字体，度量不同；用 MDI 字形。
- [ ] **不引入第二个图标库**——不要 FontAwesome / SF Symbols / SVG 图标路径；缺字形时用
      `putIcon()` 扩展。
- [ ] **图标有标签**——纯图标控件有可访问名称 / tooltip；工具卡片总是把字形与 `.tool-name`
      配对。
- [ ] **辉光使用内部取值**——若渲染工具磁贴，`DropShadow` radius `12`、spread `0.15`、颜色
      `style.getColor().deriveColor(0,1,1,0.75)`。

---

## 6. 反模式

### AP1 — 返回/使用带 `mdi-` 前缀的 MDI 名称

```java
@Override public String getMdiIcon() { return "mdi-file-excel"; }   // ❌
```

`"mdi-file-excel"` **不是** `mdi-codemap.properties` 中的键（键是裸的）。它会静默回退到
`star`，于是工具卡片显示一个通用星星，且无编译错误。**务必去掉前缀：** `"file-excel"`。
（见 [02 AP6](02-javafx-implementation.md#6-反模式)。）

### AP2 — 依赖 `.ic-*` CSS 给图标上色

```java
// ❌ 期望容器类能把字形染成蓝色
iconWrap.getStyleClass().addAll("tool-icon-wrap", "ic-blue");
// （从未在 Text 上调用 setFill / setStyle）
```

`.ic-blue` 规则是**空的**（`{ }`）。字形保持默认的**白色**。宿主从 Java 通过
`getIconStyle().getColor()` 填充 `Text`——见[那个陷阱](#关键的-ic--陷阱)。若你自行渲染工具图标，
必须执行 `setFill`/`setStyle` 这一步。

### AP3 — 用内联 `setStyle` 引用令牌给通用图标上色

```java
Text t = MdiIconUtil.createIcon("folder-open", 18.0);
t.setStyle("-fx-fill: -sk-text-secondary;");   // ❌ 无法解析
```

内联 `setStyle` 字符串无法解析 looked-up 颜色变量，因此 `-sk-text-secondary` 被丢弃，填充不变
（见 [05 §P5](05-theme-color-system.md#p5--颜色只放-css-绝不-setstyle)）。改用 `.sk-fill-2`
样式类：`t.getStyleClass().add("sk-fill-2");`。
（`setStyle` 里的字面 hex *确实*有效——AP3 专门针对令牌引用。）

### AP4 — 纯装饰 / 无标签的纯图标控件

没有文字标签、也没有 `accessibleText`/tooltip 的图标，对辅助技术不可见，对视力用户也含糊。
工具卡片始终包含 `.tool-name`；若你构建纯图标按钮，请设置 tooltip 和可访问文本。

### AP5 — 混入第二个图标字体 / emoji

引入 FontAwesome、SF Symbols、SVG 图标集，或用 emoji（`📧`）当图标，会破坏描边和网格一致性，
并增加字体体积。只用 MDI；缺字形时用 `MdiIconUtil.putIcon()` 注册。

### AP6 — 发明新的图标颜色

为工具图标硬编码一个全新的 RGB（如 `Color.rgb(10, 200, 255)`）会割裂调色板。工具强调色只来自
`IconStyle`；任何其它颜色必须是 [05 主题与配色系统](05-theme-color-system.md)的令牌。

### AP7 — 乱猜尺寸

在工具卡片里用 `createIcon(name, 13)` 或 `createIcon(name, 50)` 会破坏视觉网格。尺寸见
[尺寸刻度](#尺寸刻度)；工具卡片字形是 **45 px**（在 **48 px** 容器内），详情主视觉是 **50 px**，
导航图标是 **16–18 px**。

---

## 7. 参考资料

### 事实来源文件

| 内容 | 路径 |
|---|---|
| MDI 渲染器（`createIcon`、`getCodepoint`、`getFont`、`putIcon`） | [`ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/MdiIconUtil.java) |
| 图标强调样式（`BLUE`…`GRAY`、CSS 类、颜色、`fromCssClass`） | [`ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/IconStyle.java) |
| 插件图标契约（`getMdiIcon`、`getIconStyle`） | [`ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../../ZhiFlow-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| 码位表（7 448 条，键为裸名称） | [`ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties`](../../../ZhiFlow-Api/src/main/resources/fonts/mdi-codemap.properties) |
| 字体二进制 | `/fonts/materialdesignicons-webfont.ttf`（classpath，API 模块） |
| 空的 `.ic-*` 规则 + `.tool-icon-wrap`（48px）+ `.nav-item-icon`（18px） | [`ZhiFlow/src/main/resources/css/shell.css`](../../../ZhiFlow/src/main/resources/css/shell.css) |
| `.sk-fill-2` / `.sk-fill-3` 工具类 | [`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) |
| 规范的工具图标渲染器（45px 字形 + 辉光） | [`ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/ToolCard.java) |
| 详情面板主视觉渲染器（50px 字形 + 辉光） | [`ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java`](../../../ZhiFlow/src/main/java/fan/summer/ui/content/DetailPanel.java) |
| 11 个内置工具图标映射 | [`ZhiFlow/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java`](../../../ZhiFlow/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java) |

### 设计基线

| 内容 | 路径 |
|---|---|
| 图标目录（可视化浏览名称） | [Material Design Icons — Pictogrammers](https://pictogrammers.com/library/mdi/) |

### 同级 UI 设计文档

| 文档 | 链接 |
|---|---|
| 02 — JavaFX 实现（§4.2 图标速成、AP6 `mdi-` 前缀） | [02-javafx-implementation.md](02-javafx-implementation.md) |
| 03 — 组件库（图标用法链接至此） | [03-component-library.md](03-component-library.md) |
| 05 — 主题与配色系统（`-sk-*` 令牌、`.sk-fill-*`、P5 内联样式规则） | [05-theme-color-system.md](05-theme-color-system.md) |
