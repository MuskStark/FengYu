# 08 · 无障碍指南

> **定位：** 本文档是每个 ZhiFlow 组件和插件都必须通过的无障碍清单。它定义了对比度阈值、
> "不仅靠颜色"规则、键盘可操作性、焦点管理以及减弱动效策略。具体的对比度比值在
> [05 的对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)；本文档告诉你哪些颜色对
> 安全可用，以及如何避免动效成为障碍。

| | |
|---|---|
| **文档类型** | 无障碍要求 + 清单 |
| **读者** | 插件作者、AI 代码生成器——任何必须验证 UI 对所有人都可用的人 |
| **Token** | [`zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) |
| **相关文档** | [05 主题与色彩系统](05-theme-color-system.md)（对比度矩阵） · [07 动效](07-animation-guidelines.md)（减弱动效） · [04 交互](04-interaction-guidelines.md)（键盘流程） |

---

## 目录

1. [概览](#1-概览)
2. [设计原则 (POUR)](#2-设计原则-pour)
3. [规格表](#3-规格表)
   - [3.1 对比度要求](#对比度要求)
   - [3.2 安全颜色对（来自 05 对比度矩阵）](#安全颜色对)
   - [3.3 "不仅靠颜色"规则](#不仅靠颜色规则)
4. [JavaFX 模板](#4-javafx-模板)
5. [AI 清单](#5-ai-清单)
6. [反模式](#6-反模式)
7. [参考](#7-参考)

---

## 1. 概览

无障碍（"a11y"）确保 UI 对所有人可用——包括低视力、色盲、运动障碍或对动效敏感的人。在
ZhiFlow，这不是可选的点缀：每个组件、每个插件都必须达到本页的要求。好消息是其中大部分
已经由遵循设计系统自然得到——基于 token 的颜色系统、可见的焦点环、键盘优先的交互模型已经
干了大部分活。本文档命名剩余的不可妥协项。

参考的 WCAG 版本是 **2.1 AA**。下面引用的对比度比值都是从精确的 token 十六进制值计算得来，
存放在 [05 的对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)。

---

## 2. 设计原则 (POUR)

无障碍建立在四大支柱上（WCAG 的 **POUR** 原则）。下面的每条规则都映射到其中之一。

| 原则 | 含义 | 在 ZhiFlow |
|---|---|---|
| **可感知** (Perceivable) | 信息和 UI 组件必须以用户能感知的方式呈现。 | 足够对比度；状态不仅靠颜色；真实文本（非文字图片）。 |
| **可操作** (Operable) | UI 组件和导航必须可操作。 | 每个操作键盘可达；焦点可见；Esc 可关闭；无陷阱。 |
| **可理解** (Understandable) | UI 的内容和操作必须可理解。 | 文案清晰；组件一致；输入辅助/错误文本；破坏性操作要确认。 |
| **健壮** (Robust) | 内容必须足够健壮，能被当前和未来的工具（含辅助技术）解析。 | 自定义控件用 `AccessibleRole` + `accessibleText`；语义化节点。 |

---

## 3. 规格表

<span id="对比度要求"></span>

### 3.1 对比度要求

WCAG 2.1 阈值，由精确 token 十六进制值计算：

| 内容类型 | 阈值 | 备注 |
|---|---|---|
| **正文**（< 18 px / 14 px 粗体） | **≥ 4.5 : 1** | 正文、标签、说明文字的默认门槛。 |
| **大字**（≥ 18 px / 14 px 粗体） | **≥ 3 : 1** | 标题、大号标签。 |
| **UI 组件 / 图形**（图标、边框、焦点指示器） | **≥ 3 : 1** | 相对其相邻背景。 |
| **禁用 / 占位**内容 | *无下限*——刻意低对比 | **仅用于不可操作的内容**（见下方警告）。 |

> **禁用文本陷阱。** `-sk-text-disabled` 是刻意低于 AA 的（两种主题下都未达 4.5:1——见
> [矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)）。WCAG 豁免*禁用*控件的对比度
> 下限，所以这**只有**在用户确实无法操作该内容时才合法。绝不要把 `-sk-text-disabled`（或
> 低于 4.5:1 处的 `-sk-text-secondary`）用在用户必须读的关键信息上。

<span id="安全颜色对"></span>

### 3.2 安全颜色对（来自 05 对比度矩阵）

取自 [05 对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)中已核实的比值。这些组合
可放心使用；这里没列出的任何组合都视为未核实，依赖前请先算其比值。

| 前景 | 背景上 | 深色主题 | 浅色主题 | 结论 |
|---|---|---|---|---|
| `-sk-text` | `-sk-bg` | ✓ 10.81 | ✓ 16.67 | **正文——两主题都始终安全** |
| `-sk-text` | `-sk-bg-elevated` | ✓ 9.18 | ✓ 15.69 | 安全 |
| `-sk-text` | `-sk-bg-hover` | ✓ 7.83 | ✓ 14.11 | 安全 |
| `-sk-text` | `-sk-bg-selected` | ✓ 7.27 | ✓ 12.73 | 安全 |
| `-sk-text-secondary` | `-sk-bg` | ✓ 6.31 | ✓ 6.63 | 正文安全 |
| `-sk-text-secondary` | `-sk-bg-elevated` | ✓ 5.36 | ✓ 6.24 | 正文安全 |
| `-sk-text-secondary` | `-sk-bg-selected` | ~ 4.24（深） | ✓ 5.06（浅） | 深：仅大字；浅：安全 |
| `-sk-text-disabled` | 任意背景 | ~ 2–3 : 1 | ✗ < 3 : 1 | **仅不可操作内容** |
| 白 `#FFFFFF` 于 `-sk-accent` | (按钮) | 4.28（双） | 4.28（双） | 大字 AA；短按钮标签可用 |

**经验法则：**

- 正文默认用 **`-sk-text` 于 `-sk-bg`**——两主题下都是最安全的组合。
- 次要标签/说明用 **`-sk-text-secondary`**，但在深色主题的选中填充上请保持 13 px+（大字领域）。
- 把 **`-sk-accent` 作为文字色**留给图标、链接、短标签——作为正文它只在最浅背景上达大字 AA。
- **绝不**组合两个强调色（如 `-sk-accent` 文字于 `-sk-success` 背景）而不算比值——未核实就
  当它不达标。

<span id="不仅靠颜色规则"></span>

### 3.3 "不仅靠颜色"规则

状态、错误、状态必须由**多于颜色**的方式传达——色盲用户（或显示器差的用户）也必须能理解
状态。ZhiFlow 用通知系统在结构上强制了这一点：

| 状态 | 颜色（必要但不充分） | + 图标 | + 文本/标签 |
|---|---|---|---|
| 信息 | 蓝色强调（`.sk-notif-info` 色调） | ℹ | 消息文本 |
| 成功 | 绿色（`.sk-notif-success` 色调，`-sk-success`） | ✓ | 消息文本 |
| 警告 | 琥珀色（`.sk-notif-warning` 色调，`-sk-warning`） | ⚠ | 消息文本 |
| 错误 | 红色（`.sk-notif-error` 色调，`-sk-danger`） | ✗ / ⚠ | 消息文本 |

每个 `SkNotification.Type` 都携带**三个**通道：带色调的背景颜色、字形
（`INFO ℹ` / `SUCCESS ✓` / `WARNING ⚠` / `ERROR ✗`）和文字消息。这是任何自定义状态指示器
都应遵循的模式：绝不仅用红边表达"错误"——加图标和明确的词。

> 表单校验同理：一个出错的 `.sk-field` 应在其**下方**显示 `-sk-danger` 信息（见
> [04 · 表单](04-interaction-guidelines.md#表单与校验)），而不只是把边框变红。

---

## 4. JavaFX 模板

### 4.1 键盘可操作性 + 可见焦点

每个可交互节点都必须键盘可达且可操作，并带可见焦点环。ZhiFlow 的焦点指示器是
`-sk-accent` 边框（如
[`.sk-field:focused`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css)、
[`.search-bar:focused-within`](../../ZhiFlow/src/main/resources/css/shell.css)）。

```java
// 让自定义控件可聚焦 + 可键盘激活
myControl.setFocusTraversable(true);
myControl.setOnKeyPressed(e -> {
    if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
        activate();
        e.consume();
    }
});
```

> **绝不要给可交互控件设 `setFocusTraversable(false)`** 来"理顺"Tab 顺序——那正是键盘用户失去
> 入口的方式。如果一个节点不该获得焦点，它就不该是可交互的。

### 4.2 对话框与页面切换的焦点管理

刻意移动焦点；别让它滞留。

```java
// 打开对话框 → 把焦点移到第一个有意义的控件
dialog.setOnShown(e -> firstField.requestFocus());

// 关闭对话框 → 把焦点还回发起控件
dialog.setOnHidden(e -> launchButton.requestFocus());

// 页面切换 → 把焦点移入新内容
contentArea.showPage(view, title);
view.lookup(".sk-field").requestFocus();   // 或主操作
```

### 4.3 屏幕阅读器语义（`AccessibleRole` + `accessibleText`）

JavaFX 向平台屏幕阅读器（VoiceOver/NVDA）暴露无障碍树。自定义控件——任何不是原生
`Button`/`TextField` 的东西——都应声明其角色和文本标签。*（注：这是推荐标准；宿主代码库尚未
广泛采用——在新组件和插件中采用它。）*

```java
// 一个可点击的自定义卡片应自我标识
card.setAccessibleRole(AccessibleRole.BUTTON);
card.setAccessibleText(plugin.getName() + " — " + plugin.getDescription());

// 装饰性图标必须对 AT 隐藏
decorativeIcon.setAccessibleRole(AccessibleRole.NODE);
// （无 accessibleText ⇒ 视为装饰）
```

| 规则 | 细节 |
|---|---|
| 自定义可交互控件 | 设 `AccessibleRole`（`BUTTON`、`CHECK_BOX`、`TEXT`、`IMAGE_VIEW`，…）和描述性 `accessibleText`。 |
| 装饰性元素 | 不给 `accessibleText`，让屏幕阅读器跳过——别让它念出"星标图标"。 |
| 有意义的图标 | 给它们描述所代表内容的 `accessibleText`（"运行中"、"收藏"）。 |

### 4.4 减弱动效策略

JavaFX **没有** `prefers-reduced-motion` 的 CSS 媒体查询，所以动效由静态运行时标志门控。模式：

```java
// 单一全局开关（启动时从设置读取；默认 false）
public final class MotionPreferences {
    public static boolean REDUCE_MOTION = false;   // 从 "reduce_motion" 设置置位
}

// 任何动画在播放前/中检查该标志
private void playEntry(Node node) {
    if (MotionPreferences.REDUCE_MOTION) {
        node.setOpacity(1);           // 跳到终态
        return;
    }
    FadeTransition ft = new FadeTransition(Duration.millis(240), node);
    ft.setFromValue(0); ft.setToValue(1);
    ft.play();
}
```

`REDUCE_MOTION` 开启时：
- **跳过或缩短**入场/悬停/交叉淡入；跳到终态。
- **保留传达状态的循环**（运行脉动、加载旋转），但若其分散注意力则放慢或冻结在可读中点。
- **绝不移除功能**——减弱动效改变的是反馈*如何*出现，而非*是否*出现。

> 这是**提议标准**（代码库尚未实现该标志）。新动画应从一开始就写好门控，以便日后接上标志。

---

## 5. AI 清单

为 ZhiFlow（宿主或插件）构建 UI 时，你**必须**：

- [ ] **文字对比度 ≥ 4.5:1**——用[安全颜色对](#安全颜色对)；用任何其它组合前先算比值。
- [ ] **状态绝不仅靠颜色**——颜色配图标和文本（`SkNotification.Type` 模式）。
- [ ] **每个操作键盘可达**——绝不为"修"Tab 顺序给可交互控件设 `setFocusTraversable(false)`。
- [ ] **保持焦点环可见**——依赖 `-sk-accent` 的聚焦边框；不要覆盖成透明。
- [ ] **对话框/页面切换时管理焦点**——打开时聚焦首个控件，关闭时还原，切换时移入新内容。
- [ ] **自定义可交互控件设 `AccessibleRole` + `accessibleText`**；把纯装饰节点对 AT 隐藏。
- [ ] **提供减弱动效路径**——把动画门控在 `REDUCE_MOTION` 标志后，终态仍能出现。
- [ ] **Esc 关闭对话框/面板**——接好 Esc 处理器（见 [04 · 键盘](04-interaction-guidelines.md#键盘)）。
- [ ] **`-sk-text-disabled` 仅用于不可操作内容**——其对比度按设计低于 AA。

---

## 6. 反模式

| 反模式 | 为什么错 | 应改为 |
|---|---|---|
| **错误状态仅靠颜色** | 色盲用户会错过。 | 颜色 + 图标 + 文本（`.sk-notif-*` / `SkNotification` 模式）。 |
| **低对比度禁用文本作为唯一线索** | 低于 AA；用户读不到必须读的。 | 用户须读的内容用 `-sk-text-secondary`/`-sk-text`；`-sk-text-disabled` 只留给真正禁用的内容。 |
| **焦点陷阱**（对话框/覆盖层 Tab 在内部循环、且无 Esc 出口） | 键盘用户逃不出。 | Esc 关闭；Tab 顺序受控但可逃。 |
| **没有 Esc 处理器** | 键盘用户无法关闭。 | 接 `setOnKeyPressed` → `KeyCode.ESCAPE` 关闭。 |
| **把聚焦边框覆盖成透明** | 隐藏了焦点指示器。 | 保持 `-sk-accent` 焦点环可见。 |
| **自定义控件无 `AccessibleRole`/`accessibleText`** | 屏幕阅读器念不出有用内容。 | 声明角色 + 描述性文本标签。 |
| **无减弱动效路径的动画** | 前庭敏感让动效成为障碍。 | 门控在 `REDUCE_MOTION` 后；跳到终态。 |
| **大型不可动画的布局突变**（即时页面跳变） | 令人迷失；即使"即时"也能触发动效敏感。 | 用交叉淡入/短过渡；或通过 `accessibleText` 宣告变化。 |

---

## 7. 参考

- [`zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) — token 定义、`.sk-field:focused`
- [`shell.css`](../../ZhiFlow/src/main/resources/css/shell.css) — `.search-bar:focused-within`、焦点指示器
- [`SkNotification.java`](../../ZhiFlow-Api/src/main/java/fan/summer/api/component/SkNotification.java) — 颜色+图标+文本的状态模式
- **兄弟文档：**
  - [05 主题与色彩系统](05-theme-color-system.md) — 本规则所依据的已核实
    [对比度矩阵](05-theme-color-system.md#contrast-matrix-wcag-aa)
  - [07 动效指南](07-animation-guidelines.md) — 本文档减弱动效策略所门控的动效
  - [04 交互指南](04-interaction-guidelines.md) — 此处延伸的键盘流程
