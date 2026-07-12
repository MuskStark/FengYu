# 07 · Animation Guidelines

> **Role:** This is the **single source of truth** for motion in FengYu. Every animation
> actually shipped in the app is catalogued here with its exact duration, easing, source
> `file:line`, and a copyable JavaFX template — plus a small set of *proposed* standard
> animations that aren't in the codebase yet. Component docs ([03](03-component-library.md))
> and interaction docs ([04](04-interaction-guidelines.md)) cite durations/easings from here
> rather than restating them.

| | |
|---|---|
| **Doc type** | Motion reference + JavaFX templates |
| **Audience** | Plugin authors, AI code generators, anyone adding motion to a node |
| **Toolkit** | `javafx.animation.*` (`FadeTransition`, `ScaleTransition`, `TranslateTransition`, `Timeline`, `ParallelTransition`, `PauseTransition`, `Interpolator`) |
| **Related** | [03 Component Library](03-component-library.md) · [04 Interaction Guidelines](04-interaction-guidelines.md) · [08 Accessibility](08-accessibility-guide.md) (reduced motion) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Spec Tables — Animation Tokens](#3-spec-tables--animation-tokens)
   - [3.1 Duration Scale](#duration-scale)
   - [3.2 Easing Catalog](#easing-catalog)
   - [3.3 The Real Animation Inventory](#real-animation-inventory)
4. [JavaFX Templates](#4-javafx-templates)
5. [Proposed Standard Animations (not yet in codebase)](#5-proposed-standard-animations-not-yet-in-codebase)
6. [AI Checklist + Anti-patterns](#6-ai-checklist--anti-patterns)
7. [References](#7-references)

---

## 1. Overview

Motion in FengYu serves **one** purpose: **feedback**. Every animation answers the question
*"what just happened?"* — a card entered the grid, a panel slid in, a tool started running.
This is the JetBrains IDEA **New UI** discipline applied to motion: motion is restrained,
fast, and never blocks input.

The toolkit is plain **JavaFX 21 animation** (`javafx.animation.*`). There is no animation
library, no CSS keyframes for the app shell (JavaFX CSS has no animation support), no Lottie.
Every motion is a transition or timeline constructed in Java. That keeps motion auditable —
it's all grep-able in the source.

> **The theme switch is NOT animated.** Switching theme is an instant class swap
> ([`ThemeService.set`](../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java));
> the scene re-resolves looked-up colors in one frame. Do not add a cross-fade to theme
> switching — it would *feel* slower than the instant swap.

---

## 2. Design Principles

### P1 — Purposeful

Every animation conveys a state change. If you can't answer *"what does this motion tell the
user?"*, cut it. The running-tool pulse says "I'm working"; the card-entry stagger says
"these are new"; the detail-panel slide says "here's more detail." Decorative loops on idle
elements are noise.

### P2 — Fast (≤ 300 ms for feedback)

UI feedback stays in the **100–300 ms** band. Hover, click, focus, and small entries land at
100–150 ms; page transitions and panel slides at 220–300 ms. Anything over 300 ms is reserved
for deliberate panel motion (detail panel slide-in is the upper bound at 300 ms). A 500 ms
button press feels broken.

### P3 — Non-blocking

Animations **never delay input**. A card scales on click, but the click handler fires the
callback `onFinished` (or immediately). The user can always click again, scroll, or navigate
while a transition plays — motion is layered on top of the interaction, not in front of it.

### P4 — Reversible / stoppable

When state changes mid-animation, **stop the old transition before starting the new one.** The
hover-in/hover-out pair in `ToolCard` does exactly this (`hoverOut.stop(); hoverIn.play()`).
Starting a new transition without stopping the prior one causes jitter/overlap.

### P5 — Theme switch is instant

Already stated above but worth its own principle: the one thing you must *never* animate is
the theme change. It's a class swap and a looked-up-color re-resolution. A fade would make it
feel slower and leak the old theme's colors through.

---

## 3. Spec Tables — Animation Tokens

<span id="duration-scale"></span>

### 3.1 Duration Scale

Three bands. Pick from the scale, don't invent durations.

| Band | Range | Use | Real examples |
|---|---|---|---|
| **fast** | 100–150 ms | Button/click/hover/star-pop micro-feedback | click scale **100 ms**, hover scale **150 ms**, favorite star pop **150 ms**, sidebar active pop **160 ms**, ToggleSwitch thumb **150 ms** |
| **normal** | 220–280 ms | Transitions, entries, cross-fades | page cross-fade **220/180 ms**, card entry **240 ms**, grid slide-in **280 ms**, ToolCard entry **280 ms** |
| **slow** | 300 ms+ | Deliberate panel motion only | DetailPanel slide-in **300 ms**, slide-out **250 ms** |
| **ambient** | 800–2500 ms | Continuous loops (status, not interaction) | AiChat webview blink **800 ms**, StatusBar pulse **2500 ms**, ToolCard running pulse **2500 ms**, clock tick **1 s** |

<span id="easing-catalog"></span>

### 3.2 Easing Catalog

| Easing | JavaFX constant | Use for |
|---|---|---|
| **EASE_OUT** | `Interpolator.EASE_OUT` | Entries, star pops, opacity fade-ins (decelerate into rest) |
| **EASE_IN** | `Interpolator.EASE_IN` | Slide-out / fade-out opacity (accelerate away) |
| **Material standard** | `Interpolator.SPLINE(0.4, 0, 0.2, 1)` | Panel translate (the canonical Material/Web curve) — used by DetailPanel |
| **Sidebar pop** | `Interpolator.SPLINE(0.34, 0.9, 0.64, 1)` | A springy overshoot-feel for the sidebar active-item scale pop |
| **ToolCard entry** | custom `Interpolator` | `curve(t) = 1 − (1−t)³ · cos(t · 2π)` — a one-of-a-kind settle curve for the tool-card entry (see [§4](#card-entry)) |

> **Never use `LINEAR`** for organic UI motion — it reads as mechanical. If you reach for
> `Interpolator.LINEAR`, you almost certainly want `EASE_OUT` or a `SPLINE`.

<span id="real-animation-inventory"></span>

### 3.3 The Real Animation Inventory

Every animation shipped in FengYu. Each row is grep-verified against source.

| # | Animation | Type | Duration | Easing | Source |
|---|---|---|---|---|---|
| 1 | Window entry fade-in | `FadeTransition` | 250 ms | — | `ui/MainWindow.java:313` |
| 2 | StatusBar status-dot pulse | `FadeTransition` | 2500 ms, infinite | — | `ui/MainWindow.java:151` |
| 3 | Clock tick | `Timeline` (1 KeyFrame) | 1 s, infinite | — | `ui/MainWindow.java:323` |
| 4 | Tool-card staggered entry | `PauseTransition` + `FadeTransition`+`TranslateTransition` (`ParallelTransition`) | 240 ms + 35 ms stagger per card | — | `ui/content/ContentArea.java:345–351` |
| 5 | Page switch cross-fade | `FadeTransition` × 2 (`ParallelTransition`) | 220 ms in / 180 ms out | — | `ui/content/ContentArea.java:408–416` |
| 6 | Tool-grid slide-in | `TranslateTransition`+`FadeTransition` (`ParallelTransition`) | 280 ms | — | `ui/content/ContentArea.java:424–428` |
| 7 | ToolCard entry | `Fade`+`Translate`+`Scale` (`ParallelTransition`) | 280 ms | custom curve | `ui/content/ToolCard.java:168–177` |
| 8 | ToolCard running pulse | `FadeTransition` | 2500 ms, infinite | — | `ui/content/ToolCard.java:106` |
| 9 | ToolCard favorite-star pop | `ScaleTransition` | 150 ms | `EASE_OUT` | `ui/content/ToolCard.java:128–131` |
| 10 | ToolCard hover scale | `ScaleTransition` × 2 | 150 ms | — | `ui/content/ToolCard.java:138–139` |
| 11 | ToolCard click scale | `ScaleTransition` (auto-reverse, 2 cycles) | 100 ms | — | `ui/content/ToolCard.java:156` |
| 12 | DetailPanel slide-in | `Timeline` | 300 ms | `SPLINE(0.4,0,0.2,1)` / `EASE_OUT` | `ui/content/DetailPanel.java:346` |
| 13 | DetailPanel slide-out | `Timeline` | 250 ms | `SPLINE(0.4,0,0.2,1)` / `EASE_IN` | `ui/content/DetailPanel.java:364` |
| 14 | Sidebar active-item scale pop | `ScaleTransition` | 160 ms | `SPLINE(0.34,0.9,0.64,1)` | `ui/sidebar/Sidebar.java:418–421` |
| 15 | AiChat webview blink | `FadeTransition` | 800 ms | — | `buildintool/ai/AiChatPlugin.java:747` |
| 16 | Email ToggleSwitch thumb slide | `TranslateTransition` | 150 ms | — | `buildintool/email/ToggleSwitch.java:53` |

---

## 4. JavaFX Templates

Copyable blocks matching source. Each cites its `file:line`.

### <a id="entry-fade"></a>4.1 Window/element entry fade (`FadeTransition`)

```java
// MainWindow entry fade — ui/MainWindow.java:313
FadeTransition ft = new FadeTransition(Duration.millis(250), windowPane);
ft.setFromValue(0);
ft.setToValue(1);
ft.play();
```

### <a id="pulse"></a>4.2 Continuous pulse — ambient status (`FadeTransition`, infinite)

```java
// StatusBar status dot — ui/MainWindow.java:151
FadeTransition pulse = new FadeTransition(Duration.millis(2500), dot);
pulse.setFromValue(1.0);
pulse.setToValue(0.3);
pulse.setCycleCount(Animation.INDEFINITE);
pulse.setAutoReverse(true);
pulse.play();
```

### <a id="clock"></a>4.3 Timeline tick (`Timeline`, 1 s recurring)

```java
// StatusBar clock — ui/MainWindow.java:323
clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
    // update the clock label text here
}));
clockTimeline.setCycleCount(Animation.INDEFINITE);
clockTimeline.play();
// remember to stop() on teardown to avoid leaks
```

### <a id="card-stagger"></a>4.4 Staggered entry (`PauseTransition` + `ParallelTransition`)

```java
// ContentArea staggered card entry — ui/content/ContentArea.java:345
int delay = i * 35;                    // 35 ms stagger per card
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

> **Cap the stagger.** ContentArea limits staggering to the first ~30 cards so that hundreds
> of `PauseTransition`s aren't created during a search refresh. Beyond the cap, cards appear
> instantly (`setOpacity(1)`).

### <a id="cross-fade"></a>4.5 Page cross-fade (`ParallelTransition` of two `FadeTransition`s)

```java
// ContentArea.showPage cross-fade — ui/content/ContentArea.java:399–416
private void crossFadeTo(Node next) {
    Node current = ... ;                 // the outgoing page
    next.setOpacity(0);
    // ... add next to container ...
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

### <a id="grid-slide"></a>4.6 Grid slide-in (`TranslateTransition` + `FadeTransition`)

```java
// ContentArea grid slide-in — ui/content/ContentArea.java:424
TranslateTransition tt = new TranslateTransition(Duration.millis(280), toolGrid);
tt.setFromY(16); tt.setToY(0);
FadeTransition ft = new FadeTransition(Duration.millis(280), toolGrid);
ft.setFromValue(0); ft.setToValue(1);
new ParallelTransition(tt, ft).play();
```

### <a id="card-entry"></a>4.7 ToolCard entry with custom interpolator (`ParallelTransition`)

```java
// ToolCard entry — ui/content/ToolCard.java:168–177
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

### <a id="hover-scale"></a>4.8 Hover scale (in/out pair, **stop-before-play**)

```java
// ToolCard hover — ui/content/ToolCard.java:138–149
ScaleTransition hoverIn  = new ScaleTransition(Duration.millis(150), this);
ScaleTransition hoverOut = new ScaleTransition(Duration.millis(150), this);
hoverIn.setToX(1.03);  hoverIn.setToY(1.03);
hoverOut.setToX(1.0);  hoverOut.setToY(1.0);

setOnMouseEntered(e -> { hoverOut.stop(); hoverIn.play();  /* + intensify glow */ });
setOnMouseExited (e -> { hoverIn.stop();  hoverOut.play(); /* + relax glow    */ });
```

> This is the canonical **stop-before-play** pattern (Principle P4). Always pair an in/out and
> stop the opposite one before starting the new — otherwise rapid mouse moves pile up
> overlapping transitions and the node jitters.

### <a id="click-scale"></a>4.9 Click scale (auto-reverse, callback `onFinished`)

```java
// ToolCard click — ui/content/ToolCard.java:156
setOnMouseClicked(e -> {
    ScaleTransition click = new ScaleTransition(Duration.millis(100), this);
    click.setToX(0.97); click.setToY(0.97);
    click.setAutoReverse(true); click.setCycleCount(2);
    click.setOnFinished(ev -> onSelect.accept(plugin));   // fire AFTER the squish
    click.play();
});
```

### <a id="panel-slide"></a>4.10 Panel slide-in/out (`Timeline` + `KeyValue` + `SPLINE`)

```java
// DetailPanel slide-in — ui/content/DetailPanel.java:346
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

// DetailPanel slide-out — ui/content/DetailPanel.java:364
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

> Use `Timeline` (not `TranslateTransition`) when you need **multiple properties** (here
> `translateX` + `opacity`) animated together with **per-property easings**.

### <a id="star-pop"></a>4.11 Favorite-star pop (`ScaleTransition`, `EASE_OUT`)

```java
// ToolCard favorite star — ui/content/ToolCard.java:128–131
ScaleTransition pop = new ScaleTransition(Duration.millis(150), starBtn);
pop.setFromX(0.6); pop.setFromY(0.6);
pop.setToX(1.0);   pop.setToY(1.0);
pop.setInterpolator(Interpolator.EASE_OUT);
pop.play();
```

### <a id="sidebar-pop"></a>4.12 Sidebar active-item pop (`ScaleTransition`, springy SPLINE)

```java
// Sidebar NavItem active pop — ui/sidebar/Sidebar.java:418–421
ScaleTransition st = new ScaleTransition(Duration.millis(160), this);
st.setFromX(0.85); st.setFromY(0.85);
st.setToX(1.0);    st.setToY(1.0);
st.setInterpolator(Interpolator.SPLINE(0.34, 0.9, 0.64, 1.0));   // springy
st.play();
```

### <a id="blink"></a>4.13 AiChat webview blink (`FadeTransition`)

```java
// AiChat webview blink — buildintool/ai/AiChatPlugin.java:747
FadeTransition blink = new FadeTransition(Duration.millis(800), webView);
blink.setFromValue(1.0); blink.setToValue(0.5);
blink.setCycleCount(Animation.INDEFINITE);
blink.setAutoReverse(true);
blink.play();
```

### <a id="thumb-slide"></a>4.14 ToggleSwitch thumb slide (`TranslateTransition`)

```java
// Email ToggleSwitch — buildintool/email/ToggleSwitch.java:53
TranslateTransition slide = new TranslateTransition(Duration.millis(150), thumb);
// setFromX / setToX chosen based on the on/off track width
slide.play();
```

---

## 5. Proposed Standard Animations (not yet in codebase)

The following are **approved suggestions** for new standard motion, *not yet implemented*.
They follow the principles above and reuse the token durations/easings. Use them when adding
the corresponding affordance; cite this section so reviewers know it's a proposed standard.

| Proposed | Rationale | Suggested duration/easing |
|---|---|---|
| **List reorder slide** | When items reorder (e.g. favorites), animate siblings sliding aside instead of snapping. | `TranslateTransition` 200 ms, `EASE_OUT` |
| **Dialog enter (scale + fade)** | Modal scale-up from 0.96 → 1.0 with fade-in, so it feels placed rather than flashed. | `ParallelTransition` 180 ms, `EASE_OUT` |
| **Toast slide-in + fade-out** | `.sk-notif-*` currently appears; a 220 ms slide-from-edge + delayed 180 ms fade-out would feel more native. | `TranslateTransition`+`FadeTransition` 220 ms in, `PauseTransition` 3 s, `FadeTransition` 180 ms out |
| **Checkbox check-mark transition** | Animate the mark/box fill on `.sk-checkbox` selection for tactile feedback. | `ScaleTransition`/`FadeTransition` 120 ms, `EASE_OUT` |
| **Loading spinner** | A standard indeterminate spin for async tool work (vs. the running pulse). | `RotateTransition` 800 ms, linear, infinite |
| **Focus-ring fade-in** | Soften the appearance of the focus ring when keyboard focus arrives. | `FadeTransition` 100 ms, `EASE_OUT` |

### Template — dialog enter (scale + fade)

```java
// PROPOSED standard — not yet in codebase
dialog.setScaleX(0.96); dialog.setScaleY(0.96); dialog.setOpacity(0);
ScaleTransition scale = new ScaleTransition(Duration.millis(180), dialog);
scale.setToX(1); scale.setToY(1); scale.setInterpolator(Interpolator.EASE_OUT);
FadeTransition fade = new FadeTransition(Duration.millis(180), dialog);
fade.setToValue(1);
new ParallelTransition(scale, fade).play();
```

### Template — toast slide-in + auto fade-out

```java
// PROPOSED standard — not yet in codebase
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

## 6. AI Checklist + Anti-patterns

When adding motion to FengYu UI, you **MUST**:

- [ ] **Stay ≤ 300 ms** for interaction feedback; reserve 300 ms+ for deliberate panel motion.
- [ ] **Use the token durations/easings** from [§3](#3-spec-tables--animation-tokens) — don't
      invent `Duration.millis(173)`.
- [ ] **Stop the old transition before starting the new** (the hover in/out pattern, [§4.8](#hover-scale)).
- [ ] **Fire interaction callbacks `onFinished` or immediately** — never block input on motion.
- [ ] **Keep ambient loops clearly ambient** (pulse/blink/clock — status, not interaction).
- [ ] **Never animate the theme switch** — it's an instant class swap.
- [ ] **Animate `translate`/`opacity`/`scale`, not layout properties** that trigger reflow
      (avoid animating `width`/`height`/layout bounds).
- [ ] **Provide a reduced-motion path** — see [08 Accessibility](08-accessibility-guide.md).

### Anti-patterns

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| **Animating the theme switch** (cross-fade between themes) | The instant class swap is faster and cleaner; a fade leaks the old theme's colors and *feels* slower. | Let `ThemeService.set` swap the class in one frame. |
| **UI feedback > 500 ms** | Reads as lag/broken. | Cap interaction feedback at 300 ms. |
| **Starting a new transition without stopping the old** | Overlapping transitions cause jitter. | `old.stop(); new.play();` |
| **Animating layout properties** (`width`, `height`, layout-bounds) | Triggers layout pass every frame → janky. | Animate transforms (`translateX/Y`, `scaleX/Y`) and `opacity`. |
| **`Interpolator.LINEAR`** for organic motion | Reads as mechanical/robotic. | `EASE_OUT` or a `SPLINE`. |
| **Decorative idle loops** (shimmering cards, breathing panels) | Pure noise; violates "motion = feedback." | Cut it; reserve loops for actual status (running/pulse). |

---

## 7. References

**Source files (verified animation sites):**
- [`ui/MainWindow.java`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java) — entry fade :313, status pulse :151, clock :323
- [`ui/content/ContentArea.java`](../../FengYu/src/main/java/fan/summer/ui/content/ContentArea.java) — staggered entry :345, cross-fade :399, grid slide :424
- [`ui/content/ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java) — running pulse :106, star pop :128, hover :138, click :156, entry :168
- [`ui/content/DetailPanel.java`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) — slide-in :346, slide-out :364
- [`ui/sidebar/Sidebar.java`](../../FengYu/src/main/java/fan/summer/ui/sidebar/Sidebar.java) — active pop :418
- [`buildintool/ai/AiChatPlugin.java`](../../FengYu/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java) — webview blink :747
- [`buildintool/email/ToggleSwitch.java`](../../FengYu/src/main/java/fan/summer/buildintool/email/ToggleSwitch.java) — thumb slide :53

**Sibling docs:**
- [03 Component Library](03-component-library.md) — components that use these animations
- [04 Interaction Guidelines](04-interaction-guidelines.md) — the flows these animations serve
- [08 Accessibility Guide](08-accessibility-guide.md) — reduced-motion strategy
