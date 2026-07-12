# 05 · 主题与配色系统

> **定位：** 本文档是 FengYu 中**所有** `-sk-*` 颜色令牌的**唯一事实来源**。
> 你需要的精确十六进制值、令牌名、或对比度安全的配色组合，都在这里。
> 其他所有 UI 设计文档（01、02、03、04、06、07、08）都链接回本页，而不重复列举数值。
> 请收藏锚点 [`#token-reference-table`](#token-reference-table)。

| | |
|---|---|
| **文档类型** | 令牌参考 + 主题生命周期 |
| **目标读者** | 插件作者、AI 代码生成器、任何需要给节点上色的开发者 |
| **事实来源** | [`FengYu-Api/src/main/resources/css/fengyu-common.css`](../../../FengYu-Api/src/main/resources/css/fengyu-common.css) |
| **关联文档** | [01 设计系统](01-design-system.md) · [02 JavaFX 实现](02-javafx-implementation.md) · [08 可访问性](08-accessibility-guide.md) |

---

## 目录

1. [概览](#1-概览)
2. [设计原则](#2-设计原则)
3. [规范细则](#3-规范细则)
   - [3.1 令牌参考表](#token-reference-table)
   - [3.2 令牌 → CSS 工具类](#token--css-utility-class)
   - [3.3 对比度矩阵（WCAG AA）](#contrast-matrix-wcag-aa)
4. [JavaFX 实现模板](#4-javafx-实现模板)
5. [AI 开发清单](#5-ai-开发清单)
6. [反模式](#6-反模式)
7. [参考](#7-参考)

---

## 1. 概览

FengYu 内置一套源自 JetBrains IntelliJ IDEA 2025 **New UI** 的**深浅双主题**
（dark / light）配色系统。整套调色板用 **19 个语义化颜色令牌**（前缀 `-sk-`）表达，
外加一个**两种主题共享的强调色**（`#3574F0`）。

本文档是令牌具体取值（`-sk-bg = #1E1E1E`、`-sk-text = #D0D0D0`……）**唯一**被列表化的
地方。组件、布局、插件**严禁**硬编码十六进制值——而应引用令牌。正是这一条铁律，
让主题切换做到零闪烁。

### 令牌如何解析（looked-up color 机制）

一个令牌是**JavaFX looked-up color**，声明在
[`fengyu-common.css`](../../../FengYu-Api/src/main/resources/css/fengyu-common.css)
中，归属于放置在**场景根节点**上的两个类之一：

```
┌──────────────────────── 场景根（Parent） ────────────────────────────┐
│  styleClass = [ "theme-dark" ]   ←  或者  [ "theme-light" ]          │
│                                                                     │
│   .theme-dark {  -sk-bg: #1E1E1E;  -sk-text: #D0D0D0;  ... }        │
│   .theme-light { -sk-bg: #FFFFFF;  -sk-text: #1E1E1E;  ... }        │
│                                                                     │
│  ┌─────────────────── 子节点 ──────────────────┐                     │
│  │  -fx-text-fill: -sk-text;   → #D0D0D0       │  对携带该类的      │
│  │  -fx-background-color: -sk-bg; → #1E1E1E    │  最近祖先解析      │
│  │  ...                                         │                     │
│  └──────────────────────────────────────────────┘                     │
└───────────────────────────────────────────────────────────────────────┘
```

切换主题 = 在根上换这一个类。JavaFX 会向下对整棵场景图重新解析每个 looked-up color。
**不重载样式表、不重建节点、没有闪烁。** 这一动作由
[`ThemeService.set(Theme)`](../../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java)
完成。

> **关键推论：** 令牌的取值是*上下文相关*的。`-sk-text` 在 `.theme-dark` 下是
> `#D0D0D0`，在 `.theme-light` 下是 `#1E1E1E`。硬编码 `#D0D0D0` 的代码，在用户切换主题
> 的瞬间就会出错。始终引用令牌。

### 各层级用在哪

| 层级 | 令牌 | 典型节点 |
|---|---|---|
| **画布 / 窗口背景** | `-sk-bg` | 主场景根、主滚动面板 |
| **表面 / 卡片 / 抬升面板** | `-sk-bg-elevated`、`-sk-bg-hover` | 卡片、对话框、弹层、表格体、输入框背景 |
| **选中 / 聚焦填充** | `-sk-bg-selected` | 选中的列表行、标签页、表格行 |
| **边框 / 分割线** | `-sk-border`、`-sk-border-strong` | 卡片描边、输入框边框、分割线 |
| **文本** | `-sk-text`、`-sk-text-secondary`、`-sk-text-disabled` | 标题、正文、说明、占位符 |
| **强调色（品牌 / 操作）** | `-sk-accent`、`-sk-accent-soft` | 主按钮、链接、聚焦环、选中条 |
| **状态色（语义化）** | `-sk-success`、`-sk-warning`、`-sk-danger` | 仅用于成功/警告/错误状态 |

---

## 2. 设计原则

五条不可妥协的规则。违反任何一条，都会产出视觉喧闹、切换主题即坏、或不达标的 UI。

### P1 — 中性灰主导

IDEA New UI 建立在**中性灰**基底之上。背景、表面、边框、正文全是灰色。让眼睛阅读内容，
而不是阅读外壳。任何屏幕中约 90% 以上的区域都应取自灰色令牌（`-sk-bg`、`-sk-bg-elevated`、
`-sk-bg-hover`、`-sk-bg-selected`、`-sk-border`、`-sk-border-strong`、`-sk-text*`）。

```
   主导的中性灰画布                 罕见、克制的强调色
  ┌─────────────────────────┐        ┌─────┐
  │ ░░░░░░░░░░░░░░░░░░░░░░  │        │ ▓▓▓ │  -sk-accent，每个区域仅用于
  │ ░░░░░░░░░░░░░░░░░░░░░░  │        └─────┘  一个主操作 / 选中指示
  │ ░░░░░░░░░░░░░░░░░░░░░░  │
  └─────────────────────────┘
```

### P2 — 强调色稀缺，且仅用于操作 + 选中

`-sk-accent`（`#3574F0`）是唯一的品牌色。它只出现在**两个**角色：

1. **关键操作**——主按钮、输入框聚焦边框、链接。
2. **选中指示**——选中态的标记。

它**绝不用作**大面积背景填充。“强调色泛滥”（整块面板涂成蓝色）会显得刺眼，并与中性美学
相冲突。参见[§6 反模式](#6-反模式)。

### P3 — 选中态 = 中性填充 + 3px 左侧强调条，而非蓝色填充

IDEA New UI 的选中模式非常鲜明，必须忠实复现：

```
   选中项（正确）                       选中项（错误）
  ┌──┬────────────────────────┐        ┌────────────────────────┐
  │▓▓│  标签                   │        │  标签                   │
  │▓▓│                          │        │        （蓝色填充）      │
  └──┴────────────────────────┘        └────────────────────────┘
   ▲                                     整行背景 = -sk-accent
   3px 左条 = -sk-accent
   行背景    = -sk-bg-selected（中性）
   标签色    = -sk-accent（或 -sk-text）
```

- 行/区域背景 → `-sk-bg-selected`（一种**中性**灰）。
- 唯一的蓝色是前导边的 3px 细条，以及强调色调的标签。

### P4 — 状态色严格语义化

`-sk-success`、`-sk-warning`、`-sk-danger` 承载**含义**（操作成功、注意、破坏性）。
它们绝不作装饰，也绝不挪作他用（例如不要用 `-sk-warning` 做“精选”徽章）。如果某颜色不
承载状态，就用灰色令牌或 `IconStyle` 分类色（见 [06 图标系统](06-icon-system.md)）。

### P5 — 颜色放在 CSS 里，绝不放进 `setStyle()`

> **关键规则——请读两遍。**
>
> **JavaFX 内联 `setStyle("-fx-...")` 字符串不会针对样式表中定义的 looked-up color 变量
> 进行求值。** 你不能写 `node.setStyle("-fx-text-fill: -sk-text;")` 然后期望它解析为主题色。
> 内联样式并不参与由主题类驱动的 looked-up color 解析。
>
> 这**正是** `.sk-*` 工具类存在的原因。它们位于 `fengyu-common.css` 内部，在那里
> looked-up color **处于作用域内**，因此它们*能够*解析，并在切换主题时*重新*解析。

**规则：**

| 你想设置什么 | 怎么做 |
|---|---|
| **颜色**（文本填充、背景、边框、fill） | 使用匹配的 **`.sk-*` 工具类**——通过 `node.getStyleClass().add("...")` 添加。**绝不**通过 `setStyle()`。 |
| **尺寸 / 内边距 / 圆角** | 内联 `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")` **可以**——这些不是 looked-up color。 |

一个节点可以**同时**携带颜色类和内联的尺寸/内边距样式；类负责颜色（并在切换主题时重新解析），
内联负责几何尺寸。

---

## 3. 规范细则

### 3.1 令牌参考表
<span id="token-reference-table"></span>

权威表格。**每个令牌都在 `fengyu-common.css` 的 `.theme-dark` 与 `.theme-light` 下各
出现一次**，取下表精确值。以下内容逐字摘自源 CSS——若发现任何出入，**以 CSS 文件为准**，
本文档必须随之修正。

#### 中性 / 背景令牌

| 令牌 | Dark（`#hex`） | Light（`#hex`） | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-bg` | `#1E1E1E` | `#FFFFFF` | 窗口 / 画布基础背景 | 场景根、主内容面板、输入框 |
| `-sk-bg-elevated` | `#2B2B2B` | `#F7F8FA` | 抬升表面：卡片、对话框、弹层、菜单、表格体 | 卡片、`.sk-dialog`、`.sk-surface`、上下文菜单 |
| `-sk-bg-hover` | `#363636` | `#EBECEF` | 可交互行/区域的悬停填充 | 列表项、菜单项、悬停标签页、次按钮背景 |
| `-sk-bg-selected` | `#393B40` | `#DFE1E5` | 选中态填充（中性） | 选中的列表/表格行、选中标签页 |

#### 边框令牌

| 令牌 | Dark（`#hex`） | Light（`#hex`） | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-border` | `#3C3F41` | `#DADCE0` | 标准 1px 描边 / 分割线 | 卡片描边、输入框边框、分割线、表格网格 |
| `-sk-border-strong` | `#555555` | `#C9CDD3` | 强调边框（悬停/聚焦反馈） | 次按钮悬停边框、强调分割线 |

#### 文本令牌

| 令牌 | Dark（`#hex`） | Light（`#hex`） | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-text` | `#D0D0D0` | `#1E1E1E` | 主文本——标题、正文、数值 | 标签、内容文本、`.sk-t1` |
| `-sk-text-secondary` | `#9AA0A6` | `#5A5D60` | 次要文本——说明、元数据、区块标题 | 子标签、`.sk-t2`、表格列头 |
| `-sk-text-disabled` | `#6B6F73` | `#A0A4A8` | 禁用 / 占位 / 提示文本 | 禁用控件、占位符、滚动条拇指、`.sk-t3` |

#### 强调色令牌

| 令牌 | Dark 值 | Light 值 | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-accent` | `#3574F0` | `#3574F0` | 品牌 / 操作 / 选中指示（两种主题共享） | 主按钮、链接、聚焦边框、选中条、复选框填充 |
| `-sk-accent-soft` | `rgba(53,116,240,0.18)` | `rgba(53,116,240,0.14)` | 低透明度强调色背景 | 通知信息图标背景、细微强调填充 |

#### 状态令牌（严格语义化）

| 令牌 | Dark（`#hex`） | Light（`#hex`） | 含义 | 用在 |
|---|---|---|---|---|
| `-sk-success` | `#5BB065` | `#3C914A` | 操作成功 / 正向状态 | 成功通知图标、成功进度条 |
| `-sk-warning` | `#F0A732` | `#C2751C` | 注意 / 需要关注 | 警告通知图标 |
| `-sk-danger` | `#F75464` | `#E53935` | 错误 / 破坏性 | 错误通知图标、危险进度条 |

#### 柔和色调令牌（低透明度状态填充）

这些令牌镜像了 `-sk-accent-soft` 模式，为三种状态色各提供一个低透明度填充——适用于着色
背景（通知体、状态徽章）。

| 令牌 | Dark 值 | Light 值 | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-success-soft` | `rgba(91,176,101,0.18)` | `rgba(60,145,74,0.14)` | 柔和成功填充 | 成功通知背景、成功徽章 |
| `-sk-warning-soft` | `rgba(240,167,50,0.18)` | `rgba(194,117,28,0.14)` | 柔和警告填充 | 警告通知背景、注意徽章 |
| `-sk-danger-soft` | `rgba(247,84,100,0.18)` | `rgba(229,57,53,0.14)` | 柔和危险填充 | 错误通知背景、破坏性徽章 |

#### 抬升与覆盖令牌

| 令牌 | Dark 值 | Light 值 | 用途 | 用在 |
|---|---|---|---|---|
| `-sk-shadow` | `rgba(0,0,0,0.45)` | `rgba(15,23,42,0.18)` | 抬升用投影色（对话框、通知）——light 主题下更柔和/更浅 | `.sk-dialog` 阴影、弹层 / 通知阴影 |
| `-sk-scrim` | `rgba(0,0,0,0.50)` | `rgba(15,23,42,0.32)` | 模态遮罩 / 背景覆盖（透明 Stage 对话框） | `.sk-scrim` 模态背景 |

**令牌总数：19**（`-sk-bg`、`-sk-bg-elevated`、`-sk-bg-hover`、`-sk-bg-selected`、
`-sk-border`、`-sk-border-strong`、`-sk-text`、`-sk-text-secondary`、`-sk-text-disabled`、
`-sk-accent`、`-sk-accent-soft`、`-sk-success`、`-sk-warning`、`-sk-danger`、
`-sk-success-soft`、`-sk-warning-soft`、`-sk-danger-soft`、`-sk-shadow`、`-sk-scrim`）。
不要发明第 20 个令牌名——通过[§3.2 工具类](#token--css-utility-class)扩展系统，或先在 CSS 中提案
新令牌。

#### 原始 CSS 摘录

供复制粘贴 / 校验，以下是
[`fengyu-common.css`](../../../FengYu-Api/src/main/resources/css/fengyu-common.css)
中两个主题块的逐字内容：

```css
.theme-dark {
    -sk-bg:            #1E1E1E;
    -sk-bg-elevated:   #2B2B2B;
    -sk-bg-hover:      #363636;
    -sk-bg-selected:   #393B40;
    -sk-border:        #3C3F41;
    -sk-border-strong: #555555;
    -sk-text:          #D0D0D0;
    -sk-text-secondary:#9AA0A6;
    -sk-text-disabled: #6B6F73;
    -sk-accent:        #3574F0;
    -sk-accent-soft:   rgba(53,116,240,0.18);
    -sk-success:       #5BB065;
    -sk-warning:       #F0A732;
    -sk-danger:        #F75464;
    -sk-success-soft:  rgba(91,176,101,0.18);
    -sk-warning-soft:  rgba(240,167,50,0.18);
    -sk-danger-soft:   rgba(247,84,100,0.18);
    -sk-shadow:        rgba(0,0,0,0.45);
    -sk-scrim:         rgba(0,0,0,0.50);
}
.theme-light {
    -sk-bg:            #FFFFFF;
    -sk-bg-elevated:   #F7F8FA;
    -sk-bg-hover:      #EBECEF;
    -sk-bg-selected:   #DFE1E5;
    -sk-border:        #DADCE0;
    -sk-border-strong: #C9CDD3;
    -sk-text:          #1E1E1E;
    -sk-text-secondary:#5A5D60;
    -sk-text-disabled: #A0A4A8;
    -sk-accent:        #3574F0;
    -sk-accent-soft:   rgba(53,116,240,0.14);
    -sk-success:       #3C914A;
    -sk-warning:       #C2751C;
    -sk-danger:        #E53935;
    -sk-success-soft:  rgba(60,145,74,0.14);
    -sk-warning-soft:  rgba(194,117,28,0.14);
    -sk-danger-soft:   rgba(229,57,53,0.14);
    -sk-shadow:        rgba(15,23,42,0.18);
    -sk-scrim:         rgba(15,23,42,0.32);
}
```

---

### 令牌 → CSS 工具类
<span id="token--css-utility-class"></span>

令牌是 CSS 变量；你通常**间接**地通过工具类（同样定义在 `fengyu-common.css`）来应用它们。
每当你想伸手用内联 `setStyle()` 时，就必须改用工具类——见 [P5](#p5--颜色放在-css-里绝不放进-setstyle)。

| 令牌 | 工具类 | CSS 属性 | 说明 |
|---|---|---|---|
| `-sk-text` | `.sk-t1` | `-fx-text-fill` | `Label`/`Labeled` 节点上的主文本 |
| `-sk-text-secondary` | `.sk-t2` | `-fx-text-fill` | `Labeled` 节点上的次要文本 |
| `-sk-text-secondary` | `.sk-fill-2` | `-fx-fill` | `Text`/`Shape` 节点上的次要填充 |
| `-sk-text-disabled` | `.sk-t3` | `-fx-text-fill` | `Labeled` 节点上的禁用/提示文本 |
| `-sk-text-disabled` | `.sk-fill-3` | `-fx-fill` | `Text`/`Shape` 节点上的禁用填充 |
| `-sk-bg-elevated` | `.sk-surface` | `-fx-background-color` | 抬升的卡片/面板表面 |
| `-sk-bg-hover` | `.sk-surface-soft` | `-fx-background-color` | 柔和（悬停色调）表面 |
| `-sk-border` | `.sk-outlined` | `-fx-border-color` | 配合内联 `-fx-border-width`/`-fx-border-radius` |
| `-sk-border-strong` | `.sk-outlined-strong` | `-fx-border-color` | 强调描边 |
| `-sk-accent` | `.sk-accent-text` | `-fx-text-fill` | 链接 / 强调文本——内联安全 |
| `-sk-success` | `.sk-success-text` | `-fx-text-fill` | 成功状态文本 |
| `-sk-warning` | `.sk-warning-text` | `-fx-text-fill` | 警告状态文本 |
| `-sk-danger` | `.sk-danger-text` | `-fx-text-fill` | 错误状态文本 |
| `-sk-scrim` | `.sk-scrim` | `-fx-background-color` | 模态背景覆盖 |

> **为什么用类而不是内联颜色？** 因为内联 `setStyle("-fx-text-fill: -sk-text;")`
> **不会**解析 looked-up color。类位于样式表内，变量处于作用域内，因此能解析并在切换主题时
> 重新解析。内联样式仅对**尺寸/内边距/圆角**安全。

#### 工具类之外：复合组件类

对于更丰富的组件，`fengyu-common.css` 提供了现成的类，捆绑多个令牌 + 几何尺寸。优先使用
这些，而不是自己拼装（完整规格见 [03 组件库](03-component-library.md)）：

| 类 | 捆绑 |
|---|---|
| `.sk-field` | `-sk-bg` 背景、`-sk-border` 边框、`-sk-text` 填充、6px 圆角、内边距 |
| `.sk-field:focused` | 边框切到 `-sk-accent`、背景切到 `-sk-bg-elevated` |
| `.sk-table` | `-sk-bg-elevated` 表面、`-sk-border`、选中行 → `-sk-bg-selected` + 强调标签 |
| `.sk-tab-pane` | 标签页、悬停 `-sk-bg-hover`、选中 `-sk-bg-selected` + 2px 底部强调 |
| `.sk-dialog` | `-sk-bg-elevated`、`-sk-border`、10px 圆角、阴影 |
| `.sk-btn-primary` | `-sk-accent` 背景、白色文本（强调色的规范用法） |
| `.sk-btn-secondary` | `-sk-bg-hover` 背景、`-sk-border`、`-sk-text` 填充 |
| `.sk-combo` / `.sk-checkbox` | 主题化原生控件 |
| `.sk-notif-*` | 通知变体，使用 `-sk-accent-soft` + 状态令牌 |

---

### 对比度矩阵（WCAG AA）
<span id="contrast-matrix-wcag-aa"></span>

WCAG 2.1 阈值：**普通文本 ≥ 4.5:1**，**大文本（≥18px / 14px 加粗）≥ 3:1**。
下表比值由上述令牌精确十六进制值计算得出。`✓` = 通过普通文本 AA（≥4.5:1）；`~` = 仅通过
大文本 AA（≥3:1 但 <4.5:1）；`✗` = 完全不达标（<3:1）。完整的可访问性要求（聚焦可见性、
“不仅靠颜色传达信息”、减弱动效）见 [08 可访问性规范](08-accessibility-guide.md)。

#### Dark 主题——文本令牌在背景令牌上

| 前景 \ 背景 | `-sk-bg`<br>`#1E1E1E` | `-sk-bg-elevated`<br>`#2B2B2B` | `-sk-bg-hover`<br>`#363636` | `-sk-bg-selected`<br>`#393B40` |
|---|:---:|:---:|:---:|:---:|
| `-sk-text` `#D0D0D0` | ✓ 10.81 | ✓ 9.18 | ✓ 7.83 | ✓ 7.27 |
| `-sk-text-secondary` `#9AA0A6` | ✓ 6.31 | ✓ 5.36 | ✓ 4.58 | ~ 4.24 |
| `-sk-text-disabled` `#6B6F73` | ~ 3.29 | ✗ 2.80 | ✗ 2.39 | ✗ 2.21 |
| `-sk-accent` `#3574F0` | ~ 3.90 | ~ 3.31 | ✗ 2.82 | ✗ 2.62 |

#### Light 主题——文本令牌在背景令牌上

| 前景 \ 背景 | `-sk-bg`<br>`#FFFFFF` | `-sk-bg-elevated`<br>`#F7F8FA` | `-sk-bg-hover`<br>`#EBECEF` | `-sk-bg-selected`<br>`#DFE1E5` |
|---|:---:|:---:|:---:|:---:|
| `-sk-text` `#1E1E1E` | ✓ 16.67 | ✓ 15.69 | ✓ 14.11 | ✓ 12.73 |
| `-sk-text-secondary` `#5A5D60` | ✓ 6.63 | ✓ 6.24 | ✓ 5.61 | ✓ 5.06 |
| `-sk-text-disabled` `#A0A4A8` | ✗ 2.51 | ✗ 2.36 | ✗ 2.12 | ✗ 1.92 |
| `-sk-accent` `#3574F0` | ~ 4.28 | ~ 4.03 | ~ 3.62 | ~ 3.27 |

#### 强调色作为背景（主按钮）

白色文本（`#FFFFFF`）在 `-sk-accent`（`#3574F0`）上 → **4.28:1**，通过大文本 AA（≥3:1）。
这就是 `.sk-btn-primary` 用白色文本配强调色的原因——对按钮标签（实质上加粗/中等字重且短）
是可接受的。对长篇正文，请优先用 `-sk-text` 配中性背景。

#### 如何阅读本矩阵

- **`-sk-text`** 在两种主题的**所有**背景上都达到正文安全标准。默认用它。
- **`-sk-text-secondary`** 在多数背景上对普通文本安全；在 dark 主题下，它在 `-sk-bg-selected`
  上降到仅大文本可用（4.24:1）——13px+ 标签没问题，但不要用于选中填充上的小字。
- **`-sk-text-disabled`** 故意低对比——它是给用户**无法操作**的**禁用**内容用的，降低显著度
  才是*目的*。绝不用于可操作或关键信息。
- **`-sk-accent`** 作为*文本*色，仅在较亮背景上通过大文本 AA；优先用于图标、链接、短标签和
  选中指示，而非正文。

---

## 4. JavaFX 实现模板

本节展示**完整的主题生命周期**：场景如何获得令牌、用户如何切换主题、自定义渲染器
（WebView/canvas）如何保持同步、以及独立插件窗口如何接入。

### 4.1 主题引擎一览

```
  应用启动
        │
        ▼
  ThemeService.registerScene(mainScene)   ──►  加载 fengyu-common.css
        │                                      在场景根上盖章 .theme-dark / .theme-light
        ▼
  （用户在设置里点击“Light”）
        │
        ▼
  ThemeService.set(Theme.LIGHT)           ──►  换根上的类（不重载、不闪烁）
        │                                      触发所有 onChange(Consumer<Theme>)
        ▼
  监听器重新渲染自定义表面                ──►  WebView（MarkdownRenderer）、Canvas 等
        │
        ▼
  宿主持久化  DB key "theme" = "light"
```

三个协作者，都在 `fan.summer.fengyu.api.theme`：

| 类 | 角色 |
|---|---|
| [`ThemeService`](../../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java) | 底层引擎。持有当前主题、管理已注册场景与监听器、盖章。**仅 FX 线程。** |
| [`Themes`](../../../FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java) | 面向插件的便捷助手。`Themes.applyTo(scene)` 是插件应调用的唯一入口；`Themes.COMMON_CSS` 是样式表资源路径。 |
| [`MarkdownRenderer`](../../../FengYu/src/main/java/fan/summer/ai/util/MarkdownRenderer.java) | WebView 主题同步的参考实现（HTML 无法复用 JavaFX 令牌）。 |

### 4.2 API 表面（签名逐字）

以下是 `ThemeService.java` 中**精确**的 public 方法签名。原样复制；不要改写参数类型。

```java
public enum ThemeService.Theme { DARK, LIGHT }

public static Theme current();                                       // 永不为 null；默认 DARK
public static void set(Theme theme);                                 // null 被忽略；FX 线程
public static void registerScene(Scene scene);                       // 幂等；加载 CSS + 盖章
public static void onChange(Consumer<Theme> listener);               // 每次 set() 触发
public static void removeListener(Consumer<Theme> listener);         // 不存在则无操作
```

线程约定：`set`、`registerScene` 及其触发的监听回调**必须在 JavaFX Application Thread
上调用。** 监听器抛出的异常会被吞掉，因此一个有缺陷的监听器绝不会破坏主题切换。

### 4.3 注册场景（宿主应用）

宿主在启动时注册主场景。`registerScene` 是幂等的——加载一次 `fengyu-common.css`，把场景
加入跟踪列表，并在根上盖当前主题类。

```java
import fan.summer.fengyu.api.theme.ThemeService;
import javafx.scene.Scene;

// ... 构建你的根容器 ...
Scene scene = new Scene(root, 1200, 800);

// 加载 fengyu-common.css + 在根上盖当前主题类
ThemeService.registerScene(scene);
```

`registerScene` 内部做的事：

```java
public static void registerScene(Scene scene) {
    if (scene == null) return;
    Themes.loadCommonStylesheet(scene);                 // 仅添加一次 /css/fengyu-common.css
    if (!SCENES.contains(scene)) SCENES.add(scene);     // 为将来 set() 换类而跟踪
    if (scene.getRoot() != null) {
        applyClass(scene.getRoot(),                     // 盖 .theme-dark 或 .theme-light
            current == Theme.DARK ? "theme-dark" : "theme-light");
    }
}
```

### 4.4 切换主题

切换只需一行。`set()` 在**每个**已注册场景的根上重新盖章，并触发所有 `onChange` 监听器。
不重载样式表、不重建节点 → 无闪烁。

```java
import fan.summer.fengyu.api.theme.ThemeService;

ThemeService.set(ThemeService.Theme.LIGHT);   // 必须在 FX 线程上运行
```

内部实现（节选）：

```java
public static void set(Theme theme) {
    if (theme == null) return;
    current = theme;
    String cls = (theme == Theme.DARK) ? "theme-dark" : "theme-light";
    for (Scene s : SCENES) {
        if (s.getRoot() != null) applyClass(s.getRoot(), cls);   // 换类 → 重新解析
    }
    for (Consumer<Theme> l : LISTENERS) {
        try { l.accept(theme); } catch (Exception ignored) { }   // 通知，容错
    }
}

private static void applyClass(Parent root, String themeClass) {
    root.getStyleClass().removeAll("theme-dark", "theme-light");  // 移除另一个
    root.getStyleClass().add(themeClass);                          // 添加新的
}
```

> 类盖在**场景根**上，而令牌声明位于以 `.theme-dark`/`.theme-light` 限定的 CSS 中。由于每个
> 后代都针对携带该类的最近祖先解析 looked-up color，因此换类会级联到整棵场景树。

### 4.5 监听主题变化（自定义渲染器）

对于**无法**搭便车用 looked-up color 的表面——`WebView`（HTML/CSS）、`Canvas`、离屏图像——
注册 `onChange` 监听器并重新渲染。

```java
import fan.summer.fengyu.api.theme.ThemeService;
import javafx.scene.web.WebView;

WebView web = new WebView();

// 主题变化时重新渲染此表面
ThemeService.onChange(theme -> {
    // 在 FX 线程运行。为新主题重建表面内容。
    web.getEngine().loadContent(renderMyHtml(theme));
});
```

当表面被销毁时，务必 `removeListener`，以避免泄漏和陈旧回调：

```java
ThemeService.removeListener(myListener);
```

> **重要：** 把监听器注册为一个稳定引用（字段或被字段捕获的 `final` lambda），这样你才能把
> *同一个*实例传给 `removeListener`。

### 4.6 WebView 同步模式（来自 `MarkdownRenderer`）

`WebView` 渲染 HTML，而 HTML 有自己的 CSS——JavaFX 的 looked-up color 在那里**不可用**。
`MarkdownRenderer` 通过内嵌**两**个 CSS 文本块（`DARK_CSS` / `LIGHT_CSS`）解决：渲染时选其一，
并在 `ThemeService.onChange` 时重新渲染。

```java
// MarkdownRenderer.java —— 模式节选
private static final String DARK_CSS = """
    body { ... background: #1e1e2e; color: rgba(255,255,255,0.98); }
    a { color: #3574F0; }
    ...
    """;

private static final String LIGHT_CSS = """
    body { ... background: #ffffff; color: #1E1E1E; }
    a { color: #3574F0; }
    ...
    """;

public static String render(String markdown, ThemeService.Theme theme) {
    String css = (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;  // 选变体
    Node document = PARSER.parse(markdown);
    return wrapHtml(RENDERER.render(document), css);                          // 内嵌 + 包装
}
```

注意调色板映射——WebView CSS 刻意镜像 JavaFX 令牌，即使数值被复制，因为两个 CSS 世界无法
共享变量：

| JavaFX 令牌（dark） | WebView `DARK_CSS` 等价物 |
|---|---|
| 类似 `-sk-bg` 画布 | `background: #1e1e2e` |
| 类似 `-sk-text` | `color: rgba(255,255,255,0.98)` |
| `-sk-accent` | `a { color: #3574F0; }`（共享强调色） |
| `-sk-bg-elevated` | `pre { background: rgba(255,255,255,0.06); }` |
| `-sk-border` | `pre/th/td { border: 1px solid rgba(255,255,255,0.10); }` |

| JavaFX 令牌（light） | WebView `LIGHT_CSS` 等价物 |
|---|---|
| `-sk-bg` | `background: #ffffff` |
| `-sk-text` | `color: #1E1E1E` |
| `-sk-accent` | `a { color: #3574F0; }` |
| `-sk-bg-elevated` | `pre/th { background: #F7F8FA; }` |
| `-sk-border` | `border: 1px solid #DADCE0` |

> **强调色共享：** `#3574F0` 是两个世界、两种主题中都相同的唯一数值。如果你构建自己的
> WebView 内容，请复用同一强调色，并遵循 dark=`#1e1e2e` / light=`#ffffff` 背景约定，以保持与
> AI 聊天表面的视觉一致。

### 4.7 独立插件窗口——`Themes.applyTo(scene)`

通过 `createView()` 嵌入主场景的插件会自动继承令牌（宿主已注册该场景）。但打开**自己**
`Stage`（模态对话框、独立工具窗口）的插件，会得到一个没有样式表、没有主题类的新 `Scene`。
调用 **`Themes.applyTo(scene)`** 一次即可同时解决两者：

```java
import fan.summer.fengyu.api.theme.Themes;
import javafx.scene.Scene;
import javafx.stage.Stage;

Stage dialog = new Stage();
Scene scene = new Scene(content, 480, 320);

Themes.applyTo(scene);   // ← 插件调用的唯一入口。加载 CSS + 盖主题类。
                         //   内部委托给 ThemeService.registerScene(scene)。

dialog.setScene(scene);
dialog.show();
```

`Themes.applyTo` 只是委托给 `ThemeService.registerScene`，因此该窗口会被跟踪：之后的
`ThemeService.set(...)` 也会重新为其上色。**插件应调用 `Themes.applyTo`，而非 `ThemeService`
内部方法**——那才是受支持的稳定表面。

样式表资源路径（内部使用；插件很少直接需要）：

```java
Themes.COMMON_CSS = "/css/fengyu-common.css";   // API JAR 内的资源
Themes.commonStylesheetUrl();                      // → 用于 getStylesheets() 的 external-form URL
```

### 4.8 持久化用户选择

`ThemeService` 本身**没有数据库依赖**——它只持有运行时状态。持久化用户选择的职责在宿主应用：
在 DB key **`"theme"`** 下存值 `"dark"` 或 `"light"`，并在启动时调用 `ThemeService.set(...)`。

```
  DB key "theme"  ∈  { "dark", "light" }
```

典型启动流程：

```java
// 1. 读取持久化偏好
String stored = settingsDb.get("theme", "dark");          // 默认 dark
ThemeService.Theme initial =
    "light".equalsIgnoreCase(stored) ? Theme.LIGHT : Theme.DARK;

// 2. 先注册场景（这样类落在正确的根上），再设主题
ThemeService.registerScene(primaryScene);
ThemeService.set(initial);                                 // 盖章 + 通知

// 3. 用户切换时，持久化 + 应用
settingsDb.put("theme", "light");
ThemeService.set(ThemeService.Theme.LIGHT);
```

---

## 5. AI 开发清单

为 FengYu 生成主题化 UI 时，你**必须**满足以下全部条件。把每一条都当作硬性关卡。

- [ ] **每种颜色都用令牌或工具类。** 在 CSS 中引用 `-sk-*` 令牌，或通过 `getStyleClass()`
      应用 `.sk-t1`/`.sk-t2`/`.sk-t3`/`.sk-surface`/`.sk-surface-soft`/`.sk-outlined`/
      `.sk-outlined-strong`（以及复合类 `.sk-field`、`.sk-table`……）。**绝不**把十六进制值或
      `-sk-*` 令牌放进 `setStyle()` 字符串——内联样式不会针对 looked-up color 求值。
- [ ] **内联 `setStyle` 仅用于尺寸/内边距/圆角。** 几何属性可以内联：
      `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")`。颜色不行。
- [ ] **独立的 `Stage`/`Scene`？调用 `Themes.applyTo(scene)`。** 插件代码不要直接调用
      `ThemeService.registerScene`；不要手动把 `fengyu-common.css` 加进 `getStylesheets()`。
- [ ] **自定义渲染表面（WebView/Canvas）？注册 `ThemeService.onChange`。** 在回调内为新主题
      重建表面内容（见 `MarkdownRenderer` 模式）。表面销毁时移除监听器。
- [ ] **通过 DB key `"theme"` = `"dark"`/`"light"` 持久化选择。** 启动时读取并调用
      `ThemeService.set(...)`。缺失时默认 dark。
- [ ] **选中态用中性填充 + 强调条模式。** 选中背景 = `-sk-bg-selected`；唯一的强调色是 3px
      左条 / 强调标签。绝不用 `-sk-accent` 灌满整个选中区域。
- [ ] **强调色稀缺。** `-sk-accent` 出现在主操作、链接、聚焦边框和选中指示上——不作大面积填充。
- [ ] **状态色仅语义化。** `-sk-success`/`-sk-warning`/`-sk-danger` 表示成功/注意/错误；绝不
      作装饰。
- [ ] **核对对比度。** 正文必须对其背景达到 ≥4.5:1（用[对比度矩阵](#contrast-matrix-wcag-aa)）；
      禁用文本可以按设计降低对比，但仅限不可操作内容。
- [ ] **不要发明新令牌名。** 只用[令牌参考表](#token-reference-table)中的 19 个令牌。如果你
      确实需要新的语义色，先把它加到 `fengyu-common.css`，再在此文档登记。

---

## 6. 反模式

每个反模式给出错误做法、为何失效，以及修正。

### AP1 — `setStyle()` 里写十六进制色（切换主题即坏）

```java
// ❌ 错误——硬编码 dark 值；即便切到 light 主题也永远是 dark
label.setStyle("-fx-text-fill: #D0D0D0;");
box.setStyle("-fx-background-color: #2B2B2B;");
```

无论激活哪个主题，这都会画出 dark 主题的字面色，且用户切到 light 时**不会**更新。更糟的是，
连*令牌*引用在内联里也失效：

```java
// ❌ 同样错误——looked-up color 在内联 setStyle 里不会解析
label.setStyle("-fx-text-fill: -sk-text;");
```

```java
// ✅ 正确——应用工具类；令牌会解析并在切换时重新解析
label.getStyleClass().add("sk-t1");
// 或对自定义面板表面：
box.getStyleClass().add("sk-surface");   // -sk-bg-elevated
```

**规则：** 颜色 → 类；尺寸/内边距/圆角 → 内联。

### AP2 — 强调色作大面积背景填充

```java
// ❌ 错误——整块面板灌满强调蓝；刺眼，与中性美学冲突
pane.setStyle("-fx-background-color: #3574F0;");   // 也违反 AP1（内联十六进制）
region.getStyleClass().add("..."); // 某个涂大块 -sk-accent 区域的类
```

```java
// ✅ 正确——中性表面 + 强调色仅留给唯一主操作
pane.getStyleClass().add("sk-surface");             // 中性 -sk-bg-elevated
Button action = new Button("Run");
action.getStyleClass().add("sk-btn-primary");       // 强调色仅在按钮上
```

强调色是*惊叹号*，不是壁纸。

### AP3 — 蓝色灌满的选中态

```java
// ❌ 错误——选中行整片用强调色涂
row.setStyle("-fx-background-color: #3574F0;");
```

```java
// ✅ 正确——中性选中填充 + 3px 前导强调条（按 .sk-table/.sk-tab-pane）
row.getStyleClass().addAll("sk-table");             // 选中行 → -sk-bg-selected，
// 且标签文本 → -sk-accent；条由组件 CSS 渲染
```

见 [P3](#p3--选中态--中性填充--3px-左侧强调条而非蓝色填充)。

### AP4 — 发明新令牌名

```css
/* ❌ 错误——不在系统里；没人用它；切换不一致 */
.my-card { -fx-background-color: -sk-card-bg; }     /* 没有这个令牌 */
```

```css
/* ✅ 正确——用既有令牌，或先把令牌加进 fengyu-common.css */
.my-card { -fx-background-color: -sk-bg-elevated; }
```

19 个令牌就是契约。扩展是可以的，但 CSS 必须在 `.theme-dark` **和** `.theme-light` 下都定义
新令牌，且本文档必须更新。

### AP5 — 独立窗口忘记 `Themes.applyTo`

```java
// ❌ 错误——独立 Stage 无样式；令牌不解析
Stage popup = new Stage();
popup.setScene(new Scene(myContent));
popup.show();
```

```java
// ✅ 正确——对独立场景应用主题
Stage popup = new Stage();
Scene scene = new Scene(myContent);
Themes.applyTo(scene);                 // 加载 CSS + 盖主题类 + 为换类而跟踪
popup.setScene(scene);
popup.show();
```

### AP6 — 状态色用作装饰

```java
// ❌ 错误——警告琥珀色用来让“精选”徽章显得欢快
badge.setStyle("-fx-text-fill: #F0A732;");
```

```java
// ✅ 正确——装饰用中性/分类色；琥珀色仅留给注意
badge.getStyleClass().add("sk-t2");
// 或分类徽章按文档 06 用 IconStyle 分类色
```

### AP7 — 泄漏 `onChange` 监听器

```java
// ❌ 错误——匿名监听器永远无法移除；关闭后表面泄漏
ThemeService.onChange(t -> web.getEngine().loadContent(render(t)));
```

```java
// ✅ 正确——保留引用；销毁时移除
private final Consumer<ThemeService.Theme> themeListener =
    t -> web.getEngine().loadContent(render(t));

ThemeService.onChange(themeListener);
// 关闭时：
ThemeService.removeListener(themeListener);
```

### AP8 — 在非 FX 线程上调用 `set()` / `registerScene()`

```java
// ❌ 错误——从后台线程改动场景图
new Thread(() -> ThemeService.set(Theme.LIGHT)).start();
```

```java
// ✅ 正确——始终在 JavaFX Application Thread 上
Platform.runLater(() -> ThemeService.set(Theme.LIGHT));
```

---

## 7. 参考

### 源码文件（权威）

| 内容 | 路径 |
|---|---|
| 令牌 + 工具类定义 | [`FengYu-Api/src/main/resources/css/fengyu-common.css`](../../../FengYu-Api/src/main/resources/css/fengyu-common.css) |
| 主题引擎（DARK/LIGHT、`current/set/registerScene/onChange/removeListener`） | [`FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java`](../../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java) |
| 面向插件的助手（`applyTo`、`COMMON_CSS`） | [`FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java`](../../../FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java) |
| WebView 主题同步参考（`DARK_CSS`/`LIGHT_CSS`） | [`FengYu/src/main/java/fan/summer/ai/util/MarkdownRenderer.java`](../../../FengYu/src/main/java/fan/summer/ai/util/MarkdownRenderer.java) |

### 设计基准

| 内容 | 路径 |
|---|---|
| 权威 IDEA New UI 重设计 spec | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| UI 设计文档集主 spec | [`docs/superpowers/specs/2026-07-01-ui-design-docs-design.md`](../../superpowers/specs/2026-07-01-ui-design-docs-design.md) |

### 关联 UI 设计文档

| 文档 | 链接 |
|---|---|
| 01 — UI 设计系统（哲学、排版、间距） | [01-design-system.md](01-design-system.md) |
| 02 — JavaFX 实现规范（契约、类命名） | [02-javafx-implementation.md](02-javafx-implementation.md) |
| 08 — 可访问性规范（完整 WCAG 要求） | [08-accessibility-guide.md](08-accessibility-guide.md) |

---

*本文档中的令牌取值逐字摘自 `fengyu-common.css` 并经 `grep` 校验。如果 CSS 发生变更，本文档
必须重新生成以保持一致——CSS 才是事实来源，而非本页。*
