# 08 · Accessibility Guide

> **Role:** This is the **accessibility checklist every SwissKitJ component and plugin must
> pass.** It defines the contrast thresholds, the "not by color alone" rule, keyboard
> operability, focus management, and a reduced-motion strategy. Concrete contrast ratios live
> in the [contrast matrix in 05](05-theme-color-system.md#contrast-matrix-wcag-aa); this doc
> tells you which pairs are safe to use and how to keep motion from being a barrier.

| | |
|---|---|
| **Doc type** | Accessibility requirements + checklist |
| **Audience** | Plugin authors, AI code generators — anyone who must verify a UI is usable by all |
| **Tokens** | [`swisskit-common.css`](../../SwissKitJ-Api/src/main/resources/css/swisskit-common.css) |
| **Related** | [05 Theme & Color System](05-theme-color-system.md) (contrast matrix) · [07 Animation](07-animation-guidelines.md) (reduced motion) · [04 Interaction](04-interaction-guidelines.md) (keyboard flows) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles (POUR)](#2-design-principles-pour)
3. [Spec Tables](#3-spec-tables)
   - [3.1 Contrast Requirements](#contrast-requirements)
   - [3.2 Safe Color Pairs (from the 05 contrast matrix)](#safe-color-pairs)
   - [3.3 "Not By Color Alone" Rule](#not-by-color-alone-rule)
4. [JavaFX Templates](#4-javafx-templates)
5. [AI Checklist](#5-ai-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

Accessibility ("a11y") ensures the UI is usable by everyone — including people with low
vision, color blindness, motor impairments, or sensitivity to motion. In SwissKitJ this is
not optional polish: every component and every plugin must meet the requirements on this
page. The good news is that most of it falls out of following the design system already —
the token-based color system, the visible focus ring, and the keyboard-first interaction
model do most of the work. This doc names the remaining non-negotiables.

The reference WCAG version is **2.1 AA**. The contrast ratios cited below were computed from
the exact token hex values and live in the
[contrast matrix in 05](05-theme-color-system.md#contrast-matrix-wcag-aa).

---

## 2. Design Principles (POUR)

Accessibility rests on four pillars (the WCAG **POUR** principles). Every rule below maps to
one of them.

| Principle | Meaning | In SwissKitJ |
|---|---|---|
| **P**erceivable | Information and UI components must be presentable to users in ways they can perceive. | Sufficient contrast; never color-alone status; real text (not images of text). |
| **O**perable | UI components and navigation must be operable. | Every action keyboard-reachable; visible focus; Esc closes; no traps. |
| **U**nderstandable | Content and operation of the UI must be understandable. | Clear copy; consistent components; input Assistance/error text; confirm destructive ops. |
| **R**obust | Content must be robust enough to be interpreted by current and future tools, including assistive technologies. | `AccessibleRole` + `accessibleText` on custom controls; semantic nodes. |

---

## 3. Spec Tables

<span id="contrast-requirements"></span>

### 3.1 Contrast Requirements

WCAG 2.1 thresholds, computed from the exact token hex values:

| Content type | Threshold | Notes |
|---|---|---|
| **Normal text** (< 18 px / 14 px bold) | **≥ 4.5 : 1** | The default bar for body copy, labels, captions. |
| **Large text** (≥ 18 px / 14 px bold) | **≥ 3 : 1** | Headings, large labels. |
| **UI components / graphics** (icons, borders, focus indicators) | **≥ 3 : 1** | Against their adjacent background. |
| **Disabled / placeholder** content | *No minimum* — intentionally low contrast | **But only for non-actionable content** (see warning below). |

> **The disabled-text trap.** `-sk-text-disabled` is deliberately below AA (it fails 4.5:1 in
> both themes — see the [matrix](05-theme-color-system.md#contrast-matrix-wcag-aa)). WCAG
> exempts *disabled* controls from contrast minimums, so this is legal **only** when the user
> genuinely cannot act on the content. Never use `-sk-text-disabled` (or `-sk-text-secondary`
> where it falls below 4.5:1) for essential information the user must read.

<span id="safe-color-pairs"></span>

### 3.2 Safe Color Pairs (from the 05 contrast matrix)

Drawn from the verified ratios in
[05's contrast matrix](05-theme-color-system.md#contrast-matrix-wcag-aa). Use these
combinations freely; treat anything not listed here as unverified and compute its ratio
before relying on it.

| Foreground | On background | Dark theme | Light theme | Verdict |
|---|---|---|---|---|
| `-sk-text` | `-sk-bg` | ✓ 10.81 | ✓ 16.67 | **Body text — always safe (both themes)** |
| `-sk-text` | `-sk-bg-elevated` | ✓ 9.18 | ✓ 15.69 | Safe |
| `-sk-text` | `-sk-bg-hover` | ✓ 7.83 | ✓ 14.11 | Safe |
| `-sk-text` | `-sk-bg-selected` | ✓ 7.27 | ✓ 12.73 | Safe |
| `-sk-text-secondary` | `-sk-bg` | ✓ 6.31 | ✓ 6.63 | Safe for normal text |
| `-sk-text-secondary` | `-sk-bg-elevated` | ✓ 5.36 | ✓ 6.24 | Safe for normal text |
| `-sk-text-secondary` | `-sk-bg-selected` | ~ 4.24 (dark) | ✓ 5.06 (light) | Dark: large-text only; light: safe |
| `-sk-text-disabled` | any bg | ~ 2–3 : 1 | ✗ < 3 : 1 | **Non-actionable content only** |
| White `#FFFFFF` on `-sk-accent` | (button) | 4.28 (both) | 4.28 (both) | Large-text AA; fine for short button labels |

**Rules of thumb:**

- Default to **`-sk-text` on `-sk-bg`** for body copy — it's the safest pair in both themes.
- Use **`-sk-text-secondary`** for secondary labels/captions, but on the selected fill in
  dark theme keep it to 13 px+ (large-text territory).
- Reserve **`-sk-accent` as a text color** for icons, links, and short labels — as body copy
  it only meets large-text AA on the lightest backgrounds.
- **Never** combine two accents (e.g. `-sk-accent` text on `-sk-success` bg) without
  computing the ratio — assume it fails until verified.

<span id="not-by-color-alone-rule"></span>

### 3.3 "Not By Color Alone" Rule

Status, errors, and state must be conveyed by **more than color** — a colorblind user (or a
user on a bad monitor) must still understand the state. SwissKitJ enforces this structurally
with the notification system:

| State | Color (necessary but not sufficient) | + Icon | + Text/label |
|---|---|---|---|
| Info | blue accent (`.sk-notif-info` tint) | ℹ | message text |
| Success | green (`.sk-notif-success` tint, `-sk-success`) | ✓ | message text |
| Warning | amber (`.sk-notif-warning` tint, `-sk-warning`) | ⚠ | message text |
| Error | red (`.sk-notif-error` tint, `-sk-danger`) | ✗ / ⚠ | message text |

Each `SkNotification.Type` carries **all three** channels: a tinted background color, a
glyph (`INFO ℹ` / `SUCCESS ✓` / `WARNING ⚠` / `ERROR ✗`), and a textual message. This is
the pattern to follow for any custom status indicator: never signal "error" with a red
border alone — add an icon and an explicit word.

> The same applies to form validation: an errored `.sk-field` should show a `-sk-danger`
> message **below** it (see [04 · Forms](04-interaction-guidelines.md#表单与校验)), not just
> turn the border red.

---

## 4. JavaFX Templates

### 4.1 Keyboard operability + visible focus

Every interactive node must be reachable and operable by keyboard, with a visible focus ring.
SwissKitJ's focus indicator is a `-sk-accent` border (e.g.
[`.sk-field:focused`](../../SwissKitJ-Api/src/main/resources/css/swisskit-common.css),
[`.search-bar:focused-within`](../../SwissKit/src/main/resources/css/shell.css)).

```java
// Make a custom control focusable + keyboard-activatable
myControl.setFocusTraversable(true);
myControl.setOnKeyPressed(e -> {
    if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
        activate();
        e.consume();
    }
});
```

> **Never set `setFocusTraversable(false)`** on an interactive control to "clean up" tab
> order — that's how keyboard users lose access. If a node shouldn't receive focus, it
> shouldn't be interactive.

### 4.2 Focus management on dialogs and page switches

Move focus deliberately; don't leave it stranded.

```java
// Opening a dialog → move focus to the first meaningful control
dialog.setOnShown(e -> firstField.requestFocus());

// Closing a dialog → restore focus to the launching control
dialog.setOnHidden(e -> launchButton.requestFocus());

// Page switch → move focus into the new content
contentArea.showPage(view, title);
view.lookup(".sk-field").requestFocus();   // or the primary action
```

### 4.3 Screen-reader semantics (`AccessibleRole` + `accessibleText`)

JavaFX exposes an accessibility tree to platform screen readers (VoiceOver/NVDA). Custom
controls — anything that isn't a stock `Button`/`TextField` — should declare their role and a
text label. *(Note: this is the recommended standard; the host codebase has not yet adopted
it broadly — adopt it in new components and plugins.)*

```java
// A custom clickable card should identify itself
card.setAccessibleRole(AccessibleRole.BUTTON);
card.setAccessibleText(plugin.getName() + " — " + plugin.getDescription());

// A decorative icon must be hidden from the AT
decorativeIcon.setAccessibleRole(AccessibleRole.NODE);
// (no accessibleText ⇒ it's treated as decoration)
```

| Rule | Detail |
|---|---|
| Custom interactive controls | Set `AccessibleRole` (`BUTTON`, `CHECK_BOX`, `TEXT`, `IMAGE_VIEW`, …) and a descriptive `accessibleText`. |
| Decorative elements | Leave them without `accessibleText` so the screen reader skips them — don't make it read out "star icon". |
| Meaningful icons | Give them `accessibleText` naming what they represent ("running", "favorite"). |

### 4.4 Reduced-motion strategy

JavaFX has **no CSS media query** for `prefers-reduced-motion`, so motion is gated by a static
runtime flag. The pattern:

```java
// A single global switch (read from settings on startup; default false)
public final class MotionPreferences {
    public static boolean REDUCE_MOTION = false;   // set from the "reduce_motion" setting
}

// Any animation checks the flag before/while playing
private void playEntry(Node node) {
    if (MotionPreferences.REDUCE_MOTION) {
        node.setOpacity(1);           // jump to the end state
        return;
    }
    FadeTransition ft = new FadeTransition(Duration.millis(240), node);
    ft.setFromValue(0); ft.setToValue(1);
    ft.play();
}
```

When `REDUCE_MOTION` is on:
- **Skip or shorten** entries/hovers/cross-fades; jump to the final state.
- **Keep status loops** that convey state (the running pulse, a loading spinner) but slow
  them down or freeze them at a readable midpoint if they're distracting.
- **Never remove functionality** — reduced motion changes *how* feedback appears, not
  *whether* it appears.

> This is a **proposed standard** (the codebase doesn't yet implement the flag). New
> animations should be written gated from the start so the flag can be wired in later.

---

## 5. AI Checklist

When building UI for SwissKitJ (host or plugin), you **MUST**:

- [ ] **Contrast ≥ 4.5:1 for text** — use the [safe pairs](#safe-color-pairs); compute any
      other combination before using it.
- [ ] **Never convey status by color alone** — pair color with an icon and text (the
      `SkNotification.Type` pattern).
- [ ] **Make every action keyboard-reachable** — never `setFocusTraversable(false)` on an
      interactive control to "fix" tab order.
- [ ] **Keep the focus ring visible** — rely on the `-sk-accent` focused border; don't
      override it to transparent.
- [ ] **Manage focus on dialogs/page switches** — focus the first control on open, restore on
      close, move into new content on switch.
- [ ] **Set `AccessibleRole` + `accessibleText`** on custom interactive controls; hide purely
      decorative nodes from the AT.
- [ ] **Provide a reduced-motion path** — gate animations behind a `REDUCE_MOTION` flag so the
      final state still appears.
- [ ] **Esc closes dialogs/panels** — wire the Esc handler (see [04 · Keyboard](04-interaction-guidelines.md#键盘)).
- [ ] **Keep `-sk-text-disabled` for non-actionable content only** — its contrast is below AA
      by design.

---

## 6. Anti-patterns

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| **Relying on color for error state** | Colorblind users miss it. | Color + icon + text (the `.sk-notif-*` / `SkNotification` pattern). |
| **Low-contrast disabled text as the only affordance** | Below AA; user can't read what they must. | `-sk-text-secondary`/`-sk-text` for anything the user needs to read; reserve `-sk-text-disabled` for truly disabled content. |
| **Trapping focus** (a dialog/overlay Tab-cycles inside with no Esc out) | Keyboard users can't escape. | Esc closes; tab order is contained but escapable. |
| **No Esc handler** | Keyboard users can't dismiss. | Wire `setOnKeyPressed` → close on `KeyCode.ESCAPE`. |
| **Overriding the focused border to transparent** | Hides the focus indicator. | Keep the `-sk-accent` focus ring visible. |
| **Custom controls without `AccessibleRole`/`accessibleText`** | Screen readers announce nothing useful. | Declare role + a descriptive text label. |
| **Animations with no reduced-motion path** | Vestibular sensitivity makes motion a barrier. | Gate behind `REDUCE_MOTION`; jump to final state. |
| **Large, un-animatable layout shifts** (instant page jumps) | Disorienting; can trigger motion sensitivity even when "instant". | Use the cross-fade/short transitions; or announce the change via `accessibleText`. |

---

## 7. References

- [`swisskit-common.css`](../../SwissKitJ-Api/src/main/resources/css/swisskit-common.css) — token definitions, `.sk-field:focused`
- [`shell.css`](../../SwissKit/src/main/resources/css/shell.css) — `.search-bar:focused-within`, focus indicators
- [`SkNotification.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/SkNotification.java) — the color+icon+text status pattern
- **Sibling docs:**
  - [05 Theme & Color System](05-theme-color-system.md) — the verified
    [contrast matrix](05-theme-color-system.md#contrast-matrix-wcag-aa) these rules derive from
  - [07 Animation Guidelines](07-animation-guidelines.md) — the motion this doc's reduced-motion strategy gates
  - [04 Interaction Guidelines](04-interaction-guidelines.md) — keyboard flows extended here
