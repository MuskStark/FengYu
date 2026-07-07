# 07 · 动效指南

> **定位：** 本文档是 SwissKitJ 动效的**唯一事实源**。应用中实际出厂的每一个动画都在
> 此编目——含精确时长、缓动、源码 `file:line`，以及可复制的 JavaFX 模板——外加一小组
> 尚未进入代码库的*提议性*标准动效。组件文档（[03](03-component-library.md)）和交互文档
> （[04](04-interaction-guidelines.md)）从这里引用时长/缓动，而非复述。

| | |
|---|---|
| **文档类型** | 动效参考 + JavaFX 模板 |
| **读者** | 插件作者、AI 代码生成器、任何给节点加动效的人 |
| **工具包** | `javafx.animation.*`（`FadeTransition`、`ScaleTransition`、`TranslateTransition`、`Timeline`、`ParallelTransition`、`PauseTransition`、`Interpolator`） |
| **相关文档** | [03 组件库](03-component-library.md) · [04 交互指南](04-interaction-guidelines.md) · [08 无障碍](08-accessibility-guide.md)（减弱动效） |

---

## 目录

1. [概览](#1-概览)
2. [设计原则](#2-设计原则)
3. [规格表 — 动效 token](#3-规格表--动效-token)
   - [3.1 时长刻度](#时长刻度)
   - [3.2 缓动目录](#缓动目录)
   - [3.3 真实动画清单](#真实动画清单)
4. [JavaFX 模板](#4-javafx-模板)
5. [提议的标准动效（尚未进入代码库）](#5-提议的标准动效尚未进入代码库)
6. [AI 清单 + 反模式](#6-ai-清单--反模式)
7. [参考](#7-参考)

---

## 1. 概览

SwissKitJ 的动效只服务于**一个**目的：**反馈**。每个动画都在回答*"刚刚发生了什么？"*
——一张卡片进入了网格、一个面板滑入、一个工具开始运行。这是 JetBrains IDEA **New UI**
纪律在动效上的延伸：克制、快速、永不阻塞输入。

工具包就是原生的 **JavaFX 21 动画**（`javafx.animation.*`）。没有动效库，没有应用外壳的
CSS keyframes（JavaFX CSS 不支持动画），没有 Lottie。每一份动效都是在 Java 里构造的
transition 或 timeline。这让动效可审计——在源码里都能 grep 到。

> **主题切换不动画。** 切换主题是一次即时的类替换
> （[`ThemeService.set`](../../SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java)）；
> 场景在一帧内重新解析 looked-up color。不要给主题切换加交叉淡入——那会*感觉*比即时替换
> 更慢。

---

## 2. 设计原则

### P1 — 有目的

每个动画都传达一次状态变化。如果你回答不了*"这个动效告诉用户什么？"*，砍掉它。运行中工具
的脉动说的是"我在干活"；卡片入场错峰说的是"这些是新的"；详情面板滑入说的是"这里有更多详情"。
空闲元素上的装饰性循环就是噪音。

### P2 — 快（反馈 ≤ 300 ms）

UI 反馈停留在 **100–300 ms** 区间。悬停、点击、聚焦、小型入场落在 100–150 ms；页面切换和
面板滑入落在 220–300 ms。超过 300 ms 的只留给刻意的面板运动（详情面板滑入是上限 300 ms）。
一个 500 ms 的按钮按下感觉就是坏了。

### P3 — 不阻塞

动画**绝不延迟输入**。卡片在点击时缩放，但点击处理器在 `onFinished`（或立即）触发回调。在
transition 播放期间，用户始终能再次点击、滚动或导航——动效叠加在交互之上，而非挡在前方。

### P4 — 可逆 / 可停

当动画中途状态又变化时，**先停掉旧 transition 再启新的。** `ToolCard` 的 hover-in/hover-out
对正是这么做的（`hoverOut.stop(); hoverIn.play()`）。不停旧的就启新的会导致抖动/重叠。

### P5 — 主题切换是即时的

上面已述，但值得单列一条原则：唯一*绝不*该动画的就是主题切换。它是一次类替换 + looked-up
color 的重新解析。淡入会让它感觉更慢，并让旧主题的颜色"漏"出来。

---

## 3. 规格表 — 动效 token

<span id="时长刻度"></span>

### 3.1 时长刻度

三档。从刻度里挑，不要发明时长。

| 档 | 范围 | 用途 | 真实例 |
|---|---|---|---|
| **快** | 100–150 ms | 按钮/点击/悬停/星标弹出等微反馈 | 点击缩放 **100 ms**、悬停缩放 **150 ms**、收藏星标弹出 **150 ms**、侧边栏活动项弹出 **160 ms**、ToggleSwitch 滑块 **150 ms** |
| **正常** | 220–280 ms | 切换、入场、交叉淡入 | 页面交叉淡入 **220/180 ms**、卡片入场 **240 ms**、网格滑入 **280 ms**、ToolCard 入场 **280 ms** |
| **慢** | 300 ms+ | 仅刻意的面板运动 | DetailPanel 滑入 **300 ms**、滑出 **250 ms** |
| **环境** | 800–2500 ms | 持续循环（状态而非交互） | AiChat webview 闪烁 **800 ms**、StatusBar 脉动 **2500 ms**、ToolCard 运行脉动 **2500 ms**、时钟滴答 **1 s** |

<span id="缓动目录"></span>

### 3.2 缓动目录

| 缓动 | JavaFX 常量 | 用于 |
|---|---|---|
| **EASE_OUT** | `Interpolator.EASE_OUT` | 入场、星标弹出、不透明度淡入（减速至静止） |
| **EASE_IN** | `Interpolator.EASE_IN` | 滑出 / 淡出的不透明度（加速离开） |
| **Material 标准** | `Interpolator.SPLINE(0.4, 0, 0.2, 1)` | 面板平移（标准的 Material/Web 曲线）——DetailPanel 使用 |
| **侧边栏弹出** | `Interpolator.SPLINE(0.34, 0.9, 0.64, 1)` | 带轻微过冲弹簧感的侧边栏活动项缩放弹出 |
| **ToolCard 入场** | 自定义 `Interpolator` | `curve(t) = 1 − (1−t)³ · cos(t · 2π)`——工具卡片入场专用的沉淀曲线（见 [§4](#card-entry)） |

> **有机 UI 动效绝不用 `LINEAR`**——读起来很机械。当你想用 `Interpolator.LINEAR` 时，几乎
> 总是该用 `EASE_OUT` 或某个 `SPLINE`。

<span id="真实动画清单"></span>

### 3.3 真实动画清单

SwissKitJ 出厂的全部动画。每一行都经源码 grep 核对。

| # | 动画 | 类型 | 时长 | 缓动 | 源码 |
|---|---|---|---|---|---|
| 1 | 窗口入场淡入 | `FadeTransition` | 250 ms | — | `ui/MainWindow.java:313` |
| 2 | StatusBar 状态点脉动 | `FadeTransition` | 2500 ms，无限 | — | `ui/MainWindow.java:151` |
| 3 | 时钟滴答 | `Timeline`（1 KeyFrame） | 1 s，无限 | — | `ui/MainWindow.java:323` |
| 4 | 工具卡片错峰入场 | `PauseTransition` + `FadeTransition`+`TranslateTransition`（`ParallelTransition`） | 240 ms + 每卡 35 ms 错峰 | — | `ui/content/ContentArea.java:345–351` |
| 5 | 页面切换交叉淡入 | `FadeTransition` × 2（`ParallelTransition`） | 220 ms 入 / 180 ms 出 | — | `ui/content/ContentArea.java:408–416` |
| 6 | 工具网格滑入 | `TranslateTransition`+`FadeTransition`（`ParallelTransition`） | 280 ms | — | `ui/content/ContentArea.java:424–428` |
| 7 | ToolCard 入场 | `Fade`+`Translate`+`Scale`（`ParallelTransition`） | 280 ms | 自定义曲线 | `ui/content/ToolCard.java:168–177` |
| 8 | ToolCard 运行脉动 | `FadeTransition` | 2500 ms，无限 | — | `ui/content/ToolCard.java:106` |
| 9 | ToolCard 收藏星标弹出 | `ScaleTransition` | 150 ms | `EASE_OUT` | `ui/content/ToolCard.java:128–131` |
| 10 | ToolCard 悬停缩放 | `ScaleTransition` × 2 | 150 ms | — | `ui/content/ToolCard.java:138–139` |
| 11 | ToolCard 点击缩放 | `ScaleTransition`（自动反转，2 周期） | 100 ms | — | `ui/content/ToolCard.java:156` |
| 12 | DetailPanel 滑入 | `Timeline` | 300 ms | `SPLINE(0.4,0,0.2,1)` / `EASE_OUT` | `ui/content/DetailPanel.java:346` |
| 13 | DetailPanel 滑出 | `Timeline` | 250 ms | `SPLINE(0.4,0,0.2,1)` / `EASE_IN` | `ui/content/DetailPanel.java:364` |
| 14 | 侧边栏活动项缩放弹出 | `ScaleTransition` | 160 ms | `SPLINE(0.34,0.9,0.64,1)` | `ui/sidebar/Sidebar.java:418–421` |
| 15 | AiChat webview 闪烁 | `FadeTransition` | 800 ms | — | `buildintool/ai/AiChatPlugin.java:747` |
| 16 | Email ToggleSwitch 滑块滑动 | `TranslateTransition` | 150 ms | — | `buildintool/email/ToggleSwitch.java:53` |

---

## 4. JavaFX 模板

可复制的代码块，与源码对应。每段标注其 `file:line`。

### <a id="entry-fade"></a>4.1 窗口/元素入场淡入（`FadeTransition`）

```java
// MainWindow 入场淡入 — ui/MainWindow.java:313
FadeTransition ft = new FadeTransition(Duration.millis(250), windowPane);
ft.setFromValue(0);
ft.setToValue(1);
ft.play();
```

### <a id="pulse"></a>4.2 持续脉动 — 环境状态（`FadeTransition`，无限）

```java
// StatusBar 状态点 — ui/MainWindow.java:151
FadeTransition pulse = new FadeTransition(Duration.millis(2500), dot);
pulse.setFromValue(1.0);
pulse.setToValue(0.3);
pulse.setCycleCount(Animation.INDEFINITE);
pulse.setAutoReverse(true);
pulse.play();
```

### <a id="clock"></a>4.3 Timeline 滴答（`Timeline`，1 s 循环）

```java
// StatusBar 时钟 — ui/MainWindow.java:323
clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
    // 在此更新时钟标签文本
}));
clockTimeline.setCycleCount(Animation.INDEFINITE);
clockTimeline.play();
// 记得在拆除时 stop()，避免泄漏
```

### <a id="card-stagger"></a>4.4 错峰入场（`PauseTransition` + `ParallelTransition`）

```java
// ContentArea 错峰卡片入场 — ui/content/ContentArea.java:345
int delay = i * 35;                    // 每卡 35 ms 错峰
card.setOpacity(0);
PauseTransition pause = new PauseTransition(Duration.millis(delay));
pause.setOnFinished(e -> {
    FadeTransition ft = new FadeTransition(Duration.millis(240), card);
    ft.setFromValue(0); ft.setToValue(1);
    TranslateTransition tt = new TranslateTransition(Duration.millis(240), card);
    tt.setFromY(10); tt.setToY(0);
    new ParallelTransition(ft, tt).play();
});
pause.play();
```

> **给错峰设上限。** ContentArea 把错峰限制在前 ~30 张卡片，避免在搜索刷新时创建成百上千
> 个 `PauseTransition`。超出上限的卡片直接即时出现（`setOpacity(1)`）。

### <a id="cross-fade"></a>4.5 页面交叉淡入（两个 `FadeTransition` 的 `ParallelTransition`）

```java
// ContentArea.showPage 交叉淡入 — ui/content/ContentArea.java:399–416
private void crossFadeTo(Node next) {
    Node current = ... ;                 // 即将离场的页面
    next.setOpacity(0);
    // ... 把 next 加入容器 ...
    FadeTransition fadeIn = new FadeTransition(Duration.millis(220), next);
    fadeIn.setFromValue(0); fadeIn.setToValue(1);
    if (current != null) {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), current);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        new ParallelTransition(fadeOut, fadeIn).play();
    } else {
        fadeIn.play();
    }
}
```

### <a id="grid-slide"></a>4.6 网格滑入（`TranslateTransition` + `FadeTransition`）

```java
// ContentArea 网格滑入 — ui/content/ContentArea.java:424
TranslateTransition tt = new TranslateTransition(Duration.millis(280), toolGrid);
tt.setFromY(16); tt.setToY(0);
FadeTransition ft = new FadeTransition(Duration.millis(280), toolGrid);
ft.setFromValue(0); ft.setToValue(1);
new ParallelTransition(tt, ft).play();
```

### <a id="card-entry"></a>4.7 ToolCard 入场带自定义插值器（`ParallelTransition`）

```java
// ToolCard 入场 — ui/content/ToolCard.java:168–177
setOpacity(0);
setScaleX(0.94); setScaleY(0.94);

FadeTransition ft = new FadeTransition(Duration.millis(280), this);
ft.setFromValue(0); ft.setToValue(1);
TranslateTransition tt = new TranslateTransition(Duration.millis(280), this);
tt.setFromY(12); tt.setToY(0);
ScaleTransition st = new ScaleTransition(Duration.millis(280), this);
st.setToX(1); st.setToY(1);

ParallelTransition entry = new ParallelTransition(ft, tt, st);
entry.setInterpolator(new Interpolator() {
    @Override protected double curve(double t) {
        return 1 - Math.pow(1 - t, 3) * Math.cos(t * Math.PI * 2);
    }
});
entry.play();
```

### <a id="hover-scale"></a>4.8 悬停缩放（入/出对，**先停后播**）

```java
// ToolCard 悬停 — ui/content/ToolCard.java:138–149
ScaleTransition hoverIn  = new ScaleTransition(Duration.millis(150), this);
ScaleTransition hoverOut = new ScaleTransition(Duration.millis(150), this);
hoverIn.setToX(1.03);  hoverIn.setToY(1.03);
hoverOut.setToX(1.0);  hoverOut.setToY(1.0);

setOnMouseEntered(e -> { hoverOut.stop(); hoverIn.play();  /* + 增强辉光 */ });
setOnMouseExited (e -> { hoverIn.stop();  hoverOut.play(); /* + 放松辉光 */ });
```

> 这是规范的**先停后播**模式（原则 P4）。永远成对提供入/出，在启新的之前先停掉对侧的——否则
> 快速移动鼠标会堆叠重叠的 transition，节点就会抖。

### <a id="click-scale"></a>4.9 点击缩放（自动反转，回调 `onFinished`）

```java
// ToolCard 点击 — ui/content/ToolCard.java:156
setOnMouseClicked(e -> {
    ScaleTransition click = new ScaleTransition(Duration.millis(100), this);
    click.setToX(0.97); click.setToY(0.97);
    click.setAutoReverse(true); click.setCycleCount(2);
    click.setOnFinished(ev -> onSelect.accept(plugin));   // 在挤压之后再触发
    click.play();
});
```

### <a id="panel-slide"></a>4.10 面板滑入/滑出（`Timeline` + `KeyValue` + `SPLINE`）

```java
// DetailPanel 滑入 — ui/content/DetailPanel.java:346
private void slideIn() {
    panelOpen = true;
    setVisible(true);
    Timeline tl = new Timeline(
        new KeyFrame(Duration.ZERO,
            new KeyValue(translateXProperty(), PANEL_WIDTH),
            new KeyValue(opacityProperty(), 0)),
        new KeyFrame(Duration.millis(300),
            new KeyValue(translateXProperty(), 0,  Interpolator.SPLINE(0.4, 0, 0.2, 1)),
            new KeyValue(opacityProperty(),   1,  Interpolator.EASE_OUT))
    );
    tl.play();
}

// DetailPanel 滑出 — ui/content/DetailPanel.java:364
private void slideOut() {
    panelOpen = false;
    Timeline tl = new Timeline(
        new KeyFrame(Duration.ZERO,
            new KeyValue(translateXProperty(), 0),
            new KeyValue(opacityProperty(), 1)),
        new KeyFrame(Duration.millis(250),
            new KeyValue(translateXProperty(), PANEL_WIDTH, Interpolator.SPLINE(0.4, 0, 0.2, 1)),
            new KeyValue(opacityProperty(),   0,             Interpolator.EASE_IN))
    );
    tl.play();
}
```

> 当你需要**多属性**（这里是 `translateX` + `opacity`）一起动画、且**每属性各自缓动**时，用
> `Timeline`（而非 `TranslateTransition`）。

### <a id="star-pop"></a>4.11 收藏星标弹出（`ScaleTransition`，`EASE_OUT`）

```java
// ToolCard 收藏星标 — ui/content/ToolCard.java:128–131
ScaleTransition pop = new ScaleTransition(Duration.millis(150), starBtn);
pop.setFromX(0.6); pop.setFromY(0.6);
pop.setToX(1.0);   pop.setToY(1.0);
pop.setInterpolator(Interpolator.EASE_OUT);
pop.play();
```

### <a id="sidebar-pop"></a>4.12 侧边栏活动项弹出（`ScaleTransition`，弹簧感 SPLINE）

```java
// Sidebar NavItem 活动弹出 — ui/sidebar/Sidebar.java:418–421
ScaleTransition st = new ScaleTransition(Duration.millis(160), this);
st.setFromX(0.85); st.setFromY(0.85);
st.setToX(1.0);    st.setToY(1.0);
st.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));   // 弹簧感
st.play();
```

### <a id="blink"></a>4.13 AiChat webview 闪烁（`FadeTransition`）

```java
// AiChat webview 闪烁 — buildintool/ai/AiChatPlugin.java:747
FadeTransition blink = new FadeTransition(Duration.millis(800), webView);
blink.setFromValue(1.0); blink.setToValue(0.5);
blink.setCycleCount(Animation.INDEFINITE);
blink.setAutoReverse(true);
blink.play();
```

### <a id="thumb-slide"></a>4.14 ToggleSwitch 滑块滑动（`TranslateTransition`）

```java
// Email ToggleSwitch — buildintool/email/ToggleSwitch.java:53
TranslateTransition slide = new TranslateTransition(Duration.millis(150), thumb);
// setFromX / setToX 依据开/关轨道宽度选择
slide.play();
```

---

## 5. 提议的标准动效（尚未进入代码库）

以下是**已批准的建议**新标准动效，*尚未实现*。它们遵循上述原则，并复用 token 的时长/缓动。在
新增对应功能时使用它们；引用本节让评审者知道这是提议标准。

| 提议 | 理由 | 建议时长/缓动 |
|---|---|---|
| **列表重排滑动** | 当条目重排（如收藏夹）时，让兄弟条目滑开而非瞬移。 | `TranslateTransition` 200 ms，`EASE_OUT` |
| **对话框入场（缩放 + 淡入）** | 模态从 0.96 → 1.0 缩放并淡入，显得是被放置的而非闪现。 | `ParallelTransition` 180 ms，`EASE_OUT` |
| **Toast 滑入 + 淡出** | `.sk-notif-*` 目前是直接出现；220 ms 从边缘滑入 + 延迟 180 ms 淡出会更原生。 | `TranslateTransition`+`FadeTransition` 220 ms 入，`PauseTransition` 3 s，`FadeTransition` 180 ms 出 |
| **复选框勾标过渡** | 在 `.sk-checkbox` 选中时动画化勾标/方框填充，提供触感反馈。 | `ScaleTransition`/`FadeTransition` 120 ms，`EASE_OUT` |
| **加载旋转** | 用于异步工具工作的标准不确定旋转（与运行脉动相对）。 | `RotateTransition` 800 ms，线性，无限 |
| **焦点环淡入** | 键盘焦点到来时柔化焦点环的出现。 | `FadeTransition` 100 ms，`EASE_OUT` |

### 模板 — 对话框入场（缩放 + 淡入）

```java
// 提议标准 — 尚未进入代码库
dialog.setScaleX(0.96); dialog.setScaleY(0.96); dialog.setOpacity(0);
ScaleTransition scale = new ScaleTransition(Duration.millis(180), dialog);
scale.setToX(1); scale.setToY(1); scale.setInterpolator(Interpolator.EASE_OUT);
FadeTransition fade = new FadeTransition(Duration.millis(180), dialog);
fade.setToValue(1);
new ParallelTransition(scale, fade).play();
```

### 模板 — toast 滑入 + 自动淡出

```java
// 提议标准 — 尚未进入代码库
toast.setTranslateX(320); toast.setOpacity(0);
TranslateTransition slideIn = new TranslateTransition(Duration.millis(220), toast);
slideIn.setToX(0); slideIn.setInterpolator(Interpolator.EASE_OUT);
FadeTransition fadeIn = new FadeTransition(Duration.millis(220), toast);
fadeIn.setToValue(1);
ParallelTransition enter = new ParallelTransition(slideIn, fadeIn);
enter.setOnFinished(e -> {
    PauseTransition dwell = new PauseTransition(Duration.seconds(3));
    dwell.setOnFinished(ev -> {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(180), toast);
        fadeOut.setFromValue(1); fadeOut.setToValue(0);
        fadeOut.play();
    });
    dwell.play();
});
enter.play();
```

---

## 6. AI 清单 + 反模式

为 SwissKitJ UI 添加动效时，你**必须**：

- [ ] **反馈 ≤ 300 ms**；把 300 ms+ 留给刻意的面板运动。
- [ ] **使用 [§3](#3-规格表--动效-token) 的 token 时长/缓动**——不要发明
      `Duration.millis(173)`。
- [ ] **启新 transition 前先停旧的**（悬停入/出模式，[§4.8](#hover-scale)）。
- [ ] **交互回调在 `onFinished` 或立即触发**——绝不让动效阻塞输入。
- [ ] **让环境循环明确是环境性的**（脉动/闪烁/时钟——状态而非交互）。
- [ ] **绝不给主题切换加动效**——它是即时的类替换。
- [ ] **动画化 `translate`/`opacity`/`scale`，不要动画化会触发回流的布局属性**
      （避免动画化 `width`/`height`/layout bounds）。
- [ ] **提供减弱动效路径**——见 [08 无障碍](08-accessibility-guide.md)。

### 反模式

| 反模式 | 为什么错 | 应改为 |
|---|---|---|
| **给主题切换加动效**（主题间交叉淡入） | 即时类替换更快更干净；淡入会让旧主题颜色漏出、且*感觉*更慢。 | 让 `ThemeService.set` 在一帧内替换类。 |
| **UI 反馈 > 500 ms** | 读起来像卡顿/坏了。 | 交互反馈上限 300 ms。 |
| **不停旧的就启新的 transition** | 重叠的 transition 会导致抖动。 | `old.stop(); new.play();` |
| **动画化布局属性**（`width`、`height`、layout-bounds） | 每帧触发布局 pass → 卡顿。 | 动画化变换（`translateX/Y`、`scaleX/Y`）与 `opacity`。 |
| **有机动效用 `Interpolator.LINEAR`** | 读起来机械/机器人感。 | `EASE_OUT` 或某个 `SPLINE`。 |
| **装饰性空闲循环**（卡片微光、面板呼吸） | 纯噪音；违反"动效 = 反馈"。 | 砍掉；循环只留给真实状态（运行/脉动）。 |

---

## 7. 参考

**源码文件（已核对的动画位置）：**
- [`ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) — 入场淡入 :313，状态脉动 :151，时钟 :323
- [`ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) — 错峰入场 :345，交叉淡入 :399，网格滑入 :424
- [`ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java) — 运行脉动 :106，星标弹出 :128，悬停 :138，点击 :156，入场 :168
- [`ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java) — 滑入 :346，滑出 :364
- [`ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) — 活动弹出 :418
- [`buildintool/ai/AiChatPlugin.java`](../../SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java) — webview 闪烁 :747
- [`buildintool/email/ToggleSwitch.java`](../../SwissKit/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java) — 滑块滑动 :53

**兄弟文档：**
- [03 组件库](03-component-library.md) — 使用这些动画的组件
- [04 交互指南](04-interaction-guidelines.md) — 这些动画所服务的流程
- [08 无障碍指南](08-accessibility-guide.md) — 减弱动效策略
