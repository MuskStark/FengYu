# 05 · Theme & Color System

> **Role:** This is the **single source of truth** for every `-sk-*` color token in ZhiFlow.
> If you need an exact hex value, a token name, or a contrast-safe color pair, it lives here.
> Every other UI design doc (01, 02, 03, 04, 06, 07, 08) links back to this page instead of
> restating values. Bookmark the anchor [`#token-reference-table`](#token-reference-table).

| | |
|---|---|
| **Doc type** | Token reference + theme lifecycle |
| **Audience** | Plugin authors, AI code generators, anyone who colors a node |
| **Source of truth** | [`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) |
| **Related** | [01 Design System](01-design-system.md) · [02 JavaFX Implementation](02-javafx-implementation.md) · [08 Accessibility](08-accessibility-guide.md) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Spec Tables](#3-spec-tables)
   - [3.1 Token Reference Table](#token-reference-table)
   - [3.2 Token → CSS Utility Class](#token--css-utility-class)
   - [3.3 Contrast Matrix (WCAG AA)](#contrast-matrix-wcag-aa)
4. [JavaFX Implementation Template](#4-javafx-implementation-template)
5. [AI Development Checklist](#5-ai-development-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

ZhiFlow ships a **dual-theme** (dark / light) color system derived from the JetBrains
IntelliJ IDEA 2025 **New UI**. The entire palette is expressed as **19 semantic color
tokens** prefixed `-sk-`, and one **accent** color (`#3574F0`) shared by both themes.

This document is the **only** place where concrete token values (`-sk-bg = #1E1E1E`,
`-sk-text = #D0D0D0`, …) are tabulated. Components, layouts, and plugins **must not**
hardcode hex values — they reference tokens instead. That single discipline is what makes
theme switching work with zero flicker.

### How a token resolves (the looked-up color mechanism)

A token is a **JavaFX looked-up color** declared in
[`zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css)
under one of two classes placed on the **scene root**:

```
┌──────────────────────── Scene root (Parent) ────────────────────────┐
│  styleClass = [ "theme-dark" ]   ←  OR  [ "theme-light" ]           │
│                                                                     │
│   .theme-dark {  -sk-bg: #1E1E1E;  -sk-text: #D0D0D0;  ... }        │
│   .theme-light { -sk-bg: #FFFFFF;  -sk-text: #1E1E1E;  ... }        │
│                                                                     │
│  ┌─────────────── child nodes ───────────────┐                      │
│  │  -fx-text-fill: -sk-text;   → #D0D0D0     │  resolved against    │
│  │  -fx-background-color: -sk-bg; → #1E1E1E  │  nearest ancestor    │
│  │  ...                                       │  carrying the class  │
│  └────────────────────────────────────────────┘                      │
└───────────────────────────────────────────────────────────────────────┘
```

Switching the theme = swapping that one class on the root. JavaFX re-resolves every
looked-up color downward through the scene graph. **No stylesheet is reloaded, no node is
rebuilt, there is no flicker.** This is performed by
[`ThemeService.set(Theme)`](../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java).

> **Key consequence:** a token's value is *contextual*. `-sk-text` is `#D0D0D0` under
> `.theme-dark` and `#1E1E1E` under `.theme-light`. Code that hardcodes `#D0D0D0` will be
> wrong the moment the user toggles themes. Always reference the token.

### Where to use which layer

| Layer | Tokens | Typical nodes |
|---|---|---|
| **Canvas / window background** | `-sk-bg` | Main scene root, primary scroll panes |
| **Surfaces / cards / elevated panels** | `-sk-bg-elevated`, `-sk-bg-hover` | Cards, dialogs, popups, table bodies, field backgrounds |
| **Selection / focus fill** | `-sk-bg-selected` | Selected list rows, tabs, table rows |
| **Borders / dividers** | `-sk-border`, `-sk-border-strong` | Card outlines, input borders, separators |
| **Text** | `-sk-text`, `-sk-text-secondary`, `-sk-text-disabled` | Headings, body, captions, placeholders |
| **Accent (brand / action)** | `-sk-accent`, `-sk-accent-soft` | Primary buttons, links, focus ring, selection strip |
| **Status (semantic)** | `-sk-success`, `-sk-warning`, `-sk-danger` | Success/warning/error states only |

---

## 2. Design Principles

Five non-negotiable rules. Violating any of them produces UI that is visually loud, breaks
on theme switch, or fails accessibility.

### P1 — Neutral-gray dominant

The IDEA New UI is built on a **neutral-gray** foundation. Backgrounds, surfaces, borders,
and body text are all grays. The eye reads content, not chrome. Roughly 90%+ of any screen
should be drawn from the gray tokens (`-sk-bg`, `-sk-bg-elevated`, `-sk-bg-hover`,
`-sk-bg-selected`, `-sk-border`, `-sk-border-strong`, `-sk-text*`).

```
   Dominant gray canvas               Rare, deliberate accent
  ┌─────────────────────────┐        ┌─────┐
  │ ░░░░░░░░░░░░░░░░░░░░░░  │        │ ▓▓▓ │  -sk-accent, used on ONE
  │ ░░░░░░░░░░░░░░░░░░░░░░  │        └─────┘  primary action / selection
  │ ░░░░░░░░░░░░░░░░░░░░░░  │                  indicator per region
  └─────────────────────────┘
```

### P2 — Accent is scarce, and only for action + selection

`-sk-accent` (`#3574F0`) is the single brand color. It appears in exactly **two** roles:

1. **Key actions** — a primary button, a focused input's border, a link.
2. **Selection indicator** — the selected-state marker.

It is **never** used as a large background fill. "Accent flood" (a whole panel tinted blue)
reads as alarming and fights the neutral aesthetic. See [§6 Anti-patterns](#6-anti-patterns).

### P3 — Selection = neutral fill + 3 px left accent strip, NOT blue flood

The IDEA New UI selection pattern is unmistakable and must be reproduced faithfully:

```
   Selected item (CORRECT)              Selected item (WRONG)
  ┌──┬────────────────────────┐        ┌────────────────────────┐
  │▓▓│  Label                  │        │  Label                  │
  │▓▓│                          │        │        (blue flood)     │
  └──┴────────────────────────┘        └────────────────────────┘
   ▲                                     whole row = -sk-accent bg
   3px left strip = -sk-accent
   row bg       = -sk-bg-selected (neutral)
   label color  = -sk-accent (or -sk-text)
```

- Row/area background → `-sk-bg-selected` (a **neutral** gray).
- The **only** blue is the thin 3 px strip on the leading edge and the accent-tinted label.

### P4 — Status colors are strictly semantic

`-sk-success`, `-sk-warning`, `-sk-danger` carry **meaning** (operation succeeded, caution,
destructive). They are never decorative and never repurposed (e.g. don't use `-sk-warning`
for a "featured" badge). If a color carries no state, use a gray token or an `IconStyle`
category color (see [06 Icon System](06-icon-system.md)).

### P5 — Colors live in CSS, never in `setStyle()`

> **Critical rule — read this twice.**
>
> **JavaFX inline `setStyle("-fx-...")` strings are NOT evaluated against looked-up color
> variables defined in a stylesheet.** You cannot write
> `node.setStyle("-fx-text-fill: -sk-text;")` and expect it to resolve to the theme color.
> Inline styles simply don't participate in the looked-up-color resolution that the theme
> classes drive.
>
> This is **precisely why** the `.sk-*` utility classes exist. They live inside
> `zhiflow-common.css`, where the looked-up colors **are** in scope, so they *do* resolve
> and *do* re-resolve on theme switch.

**The rule:**

| What you want to set | How |
|---|---|
| **Color** (text fill, background, border, fill) | Use the matching **`.sk-*` utility class** — add it via `node.getStyleClass().add("...")`. **Never** via `setStyle()`. |
| **Size / padding / radius** | Inline `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")` is **fine** — these are not looked-up colors. |

A node may carry **both** a color class and inline size/padding styles simultaneously; the
class handles color (and re-resolves on theme switch), inline handles geometry.

---

## 3. Spec Tables

### 3.1 Token Reference Table
<span id="token-reference-table"></span>

The canonical table. **Every token appears under both `.theme-dark` and `.theme-light` in
`zhiflow-common.css`** with the exact values below. These are reproduced verbatim from the
source CSS — if you ever find a discrepancy, the **CSS file wins** and this doc must be
fixed.

#### Neutral / background tokens

| Token | Dark (`#hex`) | Light (`#hex`) | Purpose | Use on |
|---|---|---|---|---|
| `-sk-bg` | `#1E1E1E` | `#FFFFFF` | Window / canvas base background | Scene root, primary content panes, field inputs |
| `-sk-bg-elevated` | `#2B2B2B` | `#F7F8FA` | Raised surfaces: cards, dialogs, popups, menus, table bodies | Cards, `.sk-dialog`, `.sk-surface`, context menus |
| `-sk-bg-hover` | `#363636` | `#EBECEF` | Hover fill for interactive rows/areas | List items, menu items, tabs on hover, secondary-button bg |
| `-sk-bg-selected` | `#393B40` | `#DFE1E5` | Selected-state fill (neutral) | Selected list/table rows, selected tabs |

#### Border tokens

| Token | Dark (`#hex`) | Light (`#hex`) | Purpose | Use on |
|---|---|---|---|---|
| `-sk-border` | `#3C3F41` | `#DADCE0` | Standard 1 px outline / divider | Card outlines, input borders, separators, table grid |
| `-sk-border-strong` | `#555555` | `#C9CDD3` | Emphasized border (hover/focus affordance) | Secondary-button hover border, emphasized dividers |

#### Text tokens

| Token | Dark (`#hex`) | Light (`#hex`) | Purpose | Use on |
|---|---|---|---|---|
| `-sk-text` | `#D0D0D0` | `#1E1E1E` | Primary text — headings, body, values | Labels, content text, `.sk-t1` |
| `-sk-text-secondary` | `#9AA0A6` | `#5A5D60` | Secondary text — captions, metadata, section titles | Sub-labels, `.sk-t2`, table column headers |
| `-sk-text-disabled` | `#6B6F73` | `#A0A4A8` | Disabled / placeholder / hint text | Disabled controls, placeholders, scroll thumbs, `.sk-t3` |

#### Accent tokens

| Token | Dark value | Light value | Purpose | Use on |
|---|---|---|---|---|
| `-sk-accent` | `#3574F0` | `#3574F0` | Brand / action / selection indicator (shared by both themes) | Primary buttons, links, focus border, selection strip, checkbox fill |
| `-sk-accent-soft` | `rgba(53,116,240,0.18)` | `rgba(53,116,240,0.14)` | Low-opacity accent tint background | Notification info icon background, subtle accent fills |

#### Status tokens (strictly semantic)

| Token | Dark (`#hex`) | Light (`#hex`) | Meaning | Use on |
|---|---|---|---|---|
| `-sk-success` | `#5BB065` | `#3C914A` | Operation succeeded / positive state | Success notification icon, success progress bar |
| `-sk-warning` | `#F0A732` | `#C2751C` | Caution / needs attention | Warning notification icon |
| `-sk-danger` | `#F75464` | `#E53935` | Error / destructive | Error notification icon, danger progress bar |

#### Soft tint tokens (low-opacity status fills)

These mirror the `-sk-accent-soft` pattern for the three status colors — a low-opacity fill
useful for tinted backgrounds (notification bodies, status badges).

| Token | Dark value | Light value | Purpose | Use on |
|---|---|---|---|---|
| `-sk-success-soft` | `rgba(91,176,101,0.18)` | `rgba(60,145,74,0.14)` | Soft success fill | Success notification background, success badge |
| `-sk-warning-soft` | `rgba(240,167,50,0.18)` | `rgba(194,117,28,0.14)` | Soft warning fill | Warning notification background, caution badge |
| `-sk-danger-soft` | `rgba(247,84,100,0.18)` | `rgba(229,57,53,0.14)` | Soft danger fill | Error notification background, destructive badge |

#### Elevation & overlay tokens

| Token | Dark value | Light value | Purpose | Use on |
|---|---|---|---|---|
| `-sk-shadow` | `rgba(0,0,0,0.45)` | `rgba(15,23,42,0.18)` | Drop-shadow color for elevation (dialogs, notifications) — softer/lighter in light theme | `.sk-dialog` shadow, popup / notification shadows |
| `-sk-scrim` | `rgba(0,0,0,0.50)` | `rgba(15,23,42,0.32)` | Modal dim / backdrop overlay (transparent-Stage dialogs) | `.sk-scrim` modal backdrop |

**Token count: 19** (`-sk-bg`, `-sk-bg-elevated`, `-sk-bg-hover`, `-sk-bg-selected`,
`-sk-border`, `-sk-border-strong`, `-sk-text`, `-sk-text-secondary`, `-sk-text-disabled`,
`-sk-accent`, `-sk-accent-soft`, `-sk-success`, `-sk-warning`, `-sk-danger`,
`-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft`, `-sk-shadow`, `-sk-scrim`). Do
not invent 20th token names — extend the system via [§3.2 utility classes](#token--css-utility-class)
or propose a new token in the CSS first.

#### Raw CSS excerpt

For copy-paste / verification, here are the two theme blocks verbatim from
[`zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css):

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

### Token → CSS Utility Class
<span id="token--css-utility-class"></span>

Tokens are CSS variables; you usually apply them **indirectly** through a utility class
(also defined in `zhiflow-common.css`). This is mandatory whenever you would otherwise be
tempted to reach for inline `setStyle()` — see [P5](#p5--colors-live-in-css-never-in-setstyle).

| Token | Utility class(es) | CSS property | Notes |
|---|---|---|---|
| `-sk-text` | `.sk-t1` | `-fx-text-fill` | Primary text on `Label`/`Labeled` nodes |
| `-sk-text-secondary` | `.sk-t2` | `-fx-text-fill` | Secondary text on `Labeled` nodes |
| `-sk-text-secondary` | `.sk-fill-2` | `-fx-fill` | Secondary fill on `Text`/`Shape` nodes |
| `-sk-text-disabled` | `.sk-t3` | `-fx-text-fill` | Disabled/hint text on `Labeled` nodes |
| `-sk-text-disabled` | `.sk-fill-3` | `-fx-fill` | Disabled fill on `Text`/`Shape` nodes |
| `-sk-bg-elevated` | `.sk-surface` | `-fx-background-color` | Elevated card/panel surface |
| `-sk-bg-hover` | `.sk-surface-soft` | `-fx-background-color` | Soft (hover-tone) surface |
| `-sk-border` | `.sk-outlined` | `-fx-border-color` | Pair with inline `-fx-border-width`/`-fx-border-radius` |
| `-sk-border-strong` | `.sk-outlined-strong` | `-fx-border-color` | Emphasized outline |
| `-sk-accent` | `.sk-accent-text` | `-fx-text-fill` | Link / accent text — inline-safe |
| `-sk-success` | `.sk-success-text` | `-fx-text-fill` | Success status text |
| `-sk-warning` | `.sk-warning-text` | `-fx-text-fill` | Warning status text |
| `-sk-danger` | `.sk-danger-text` | `-fx-text-fill` | Error status text |
| `-sk-scrim` | `.sk-scrim` | `-fx-background-color` | Modal backdrop overlay |

> **Why classes instead of inline color?** Because an inline `setStyle("-fx-text-fill: -sk-text;")`
> does **not** resolve the looked-up color. The class lives in the stylesheet where the
> variable is in scope, so it resolves and re-resolves on theme switch. Inline style is only
> safe for **size/padding/radius**.

#### Beyond utilities: composite component classes

For richer components, `zhiflow-common.css` ships ready-made classes that bundle several
tokens + geometry. Use these instead of hand-rolling (see [03 Component Library](03-component-library.md)
for full specs):

| Class | Bundles |
|---|---|
| `.sk-field` | `-sk-bg` bg, `-sk-border` border, `-sk-text` fill, 6 px radius, padding |
| `.sk-field:focused` | switches border to `-sk-accent`, bg to `-sk-bg-elevated` |
| `.sk-table` | `-sk-bg-elevated` surface, `-sk-border`, selected row → `-sk-bg-selected` + accent label |
| `.sk-tab-pane` | tabs, hover `-sk-bg-hover`, selected `-sk-bg-selected` + 2 px bottom accent |
| `.sk-dialog` | `-sk-bg-elevated`, `-sk-border`, 10 px radius, drop shadow |
| `.sk-btn-primary` | `-sk-accent` bg, white text (the canonical accent usage) |
| `.sk-btn-secondary` | `-sk-bg-hover` bg, `-sk-border`, `-sk-text` fill |
| `.sk-combo` / `.sk-checkbox` | themed native controls |
| `.sk-notif-*` | notification variants using `-sk-accent-soft` + status tokens |

---

### Contrast Matrix (WCAG AA)
<span id="contrast-matrix-wcag-aa"></span>

WCAG 2.1 thresholds: **normal text ≥ 4.5:1**, **large text (≥18 px / 14 px bold) ≥ 3:1**.
Below, ratios were computed from the exact token hex values above. `✓` = passes normal-text
AA (≥4.5:1); `~` = passes large-text AA only (≥3:1 but <4.5:1); `✗` = fails AA entirely
(<3:1). Full accessibility requirements (focus visibility, "not by color alone", reduced
motion) are in [08 Accessibility Guide](08-accessibility-guide.md).

#### Dark theme — text tokens on background tokens

| Foreground \ Background | `-sk-bg`<br>`#1E1E1E` | `-sk-bg-elevated`<br>`#2B2B2B` | `-sk-bg-hover`<br>`#363636` | `-sk-bg-selected`<br>`#393B40` |
|---|:---:|:---:|:---:|:---:|
| `-sk-text` `#D0D0D0` | ✓ 10.81 | ✓ 9.18 | ✓ 7.83 | ✓ 7.27 |
| `-sk-text-secondary` `#9AA0A6` | ✓ 6.31 | ✓ 5.36 | ✓ 4.58 | ~ 4.24 |
| `-sk-text-disabled` `#6B6F73` | ~ 3.29 | ✗ 2.80 | ✗ 2.39 | ✗ 2.21 |
| `-sk-accent` `#3574F0` | ~ 3.90 | ~ 3.31 | ✗ 2.82 | ✗ 2.62 |

#### Light theme — text tokens on background tokens

| Foreground \ Background | `-sk-bg`<br>`#FFFFFF` | `-sk-bg-elevated`<br>`#F7F8FA` | `-sk-bg-hover`<br>`#EBECEF` | `-sk-bg-selected`<br>`#DFE1E5` |
|---|:---:|:---:|:---:|:---:|
| `-sk-text` `#1E1E1E` | ✓ 16.67 | ✓ 15.69 | ✓ 14.11 | ✓ 12.73 |
| `-sk-text-secondary` `#5A5D60` | ✓ 6.63 | ✓ 6.24 | ✓ 5.61 | ✓ 5.06 |
| `-sk-text-disabled` `#A0A4A8` | ✗ 2.51 | ✗ 2.36 | ✗ 2.12 | ✗ 1.92 |
| `-sk-accent` `#3574F0` | ~ 4.28 | ~ 4.03 | ~ 3.62 | ~ 3.27 |

#### Accent as a background (primary button)

White text (`#FFFFFF`) on `-sk-accent` (`#3574F0`) → **4.28:1**, which passes large-text AA
(≥3:1). This is why `.sk-btn-primary` uses white text on the accent — acceptable for button
labels (which are effectively bold/medium-weight and short). For long-form text, prefer
`-sk-text` on a neutral background.

#### Reading the matrix

- **`-sk-text`** is body-text-safe on **every** background in both themes. Default to it.
- **`-sk-text-secondary`** is safe for normal text on most backgrounds; in dark theme it
  drops to large-text-only on `-sk-bg-selected` (4.24:1) — fine for 13 px+ labels but avoid
  it for tiny text on the selected fill.
- **`-sk-text-disabled`** is intentionally low-contrast — it is for **disabled** content the
  user cannot act on, where reduced prominence is the *point*. Never use it for actionable
  or essential information.
- **`-sk-accent`** as a *text* color passes large-text AA on the lightest backgrounds only;
  prefer it for icons, links, short labels, and selection indicators rather than body copy.

---

## 4. JavaFX Implementation Template

This section shows the **complete theme lifecycle**: how a scene gets the tokens, how the
user toggles themes, how custom renderers (WebView/canvas) stay in sync, and how standalone
plugin windows opt in.

### 4.1 The theme engine at a glance

```
  Application startup
        │
        ▼
  ThemeService.registerScene(mainScene)   ──►  loads zhiflow-common.css
        │                                      stamps .theme-dark / .theme-light
        │                                      on the scene root
        ▼
  (user clicks "Light" in settings)
        │
        ▼
  ThemeService.set(Theme.LIGHT)           ──►  swaps root class  (no reload, no flicker)
        │                                      fires every onChange(Consumer<Theme>)
        ▼
  listeners re-render custom surfaces     ──►  WebView (MarkdownRenderer), Canvas, etc.
        │
        ▼
  host persists  DB key "theme" = "light"
```

Three collaborators, all in `fan.summer.zhiflow.api.theme`:

| Class | Role |
|---|---|
| [`ThemeService`](../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java) | The low-level engine. Holds current theme, owns registered scenes + listeners, stamps the class. **FX-thread-only.** |
| [`Themes`](../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java) | Plugin-facing convenience helper. `Themes.applyTo(scene)` is the one entry point plugins should call; `Themes.COMMON_CSS` is the stylesheet resource path. |
| [`MarkdownRenderer`](../../ZhiFlow/src/main/java/fan/summer/ai/util/MarkdownRenderer.java) | Reference implementation of WebView theme sync (HTML can't reuse JavaFX tokens). |

### 4.2 The API surface (signatures verbatim)

These are the **exact** public method signatures from `ThemeService.java`. Copy them as-is;
do not paraphrase parameter types.

```java
public enum ThemeService.Theme { DARK, LIGHT }

public static Theme current();                                       // never null; default DARK
public static void set(Theme theme);                                 // null ignored; FX thread
public static void registerScene(Scene scene);                       // idempotent; loads CSS + stamps class
public static void onChange(Consumer<Theme> listener);               // fires on every set()
public static void removeListener(Consumer<Theme> listener);         // no-op if absent
```

Thread contract: `set`, `registerScene`, and the resulting listener callbacks **must be
invoked on the JavaFX Application Thread.** Listener exceptions are swallowed so a faulty
listener can never break a theme switch.

### 4.3 Registering a scene (host application)

The host registers the primary scene at startup. `registerScene` is idempotent — it loads
`zhiflow-common.css` once, adds the scene to the tracked list, and stamps the current theme
class on the root.

```java
import fan.summer.zhiflow.api.theme.ThemeService;
import javafx.scene.Scene;

// ... build your root container ...
Scene scene = new Scene(root, 1200, 800);

// Load zhiflow-common.css + stamp current theme class on root.
ThemeService.registerScene(scene);
```

What `registerScene` does internally:

```java
public static void registerScene(Scene scene) {
    if (scene == null) return;
    Themes.loadCommonStylesheet(scene);                 // adds /css/zhiflow-common.css once
    if (!SCENES.contains(scene)) SCENES.add(scene);     // track for future set() swaps
    if (scene.getRoot() != null) {
        applyClass(scene.getRoot(),                     // stamp .theme-dark or .theme-light
            current == Theme.DARK ? "theme-dark" : "theme-light");
    }
}
```

### 4.4 Switching the theme

Switching is a one-liner. `set()` re-stamps the class on **every** registered scene's root
and fires all `onChange` listeners. No stylesheet reload, no node rebuild → no flicker.

```java
import fan.summer.zhiflow.api.theme.ThemeService;

ThemeService.set(ThemeService.Theme.LIGHT);   // must run on the FX thread
```

Internally (abridged):

```java
public static void set(Theme theme) {
    if (theme == null) return;
    current = theme;
    String cls = (theme == Theme.DARK) ? "theme-dark" : "theme-light";
    for (Scene s : SCENES) {
        if (s.getRoot() != null) applyClass(s.getRoot(), cls);   // swap the class → re-resolve
    }
    for (Consumer<Theme> l : LISTENERS) {
        try { l.accept(theme); } catch (Exception ignored) { }   // notify, fault-tolerant
    }
}

private static void applyClass(Parent root, String themeClass) {
    root.getStyleClass().removeAll("theme-dark", "theme-light");  // remove the other
    root.getStyleClass().add(themeClass);                          // add the new one
}
```

> The class is stamped on the **scene root**, and the token declarations live in CSS scoped
> to `.theme-dark`/`.theme-light`. Because every descendant resolves looked-up colors
> against the nearest ancestor carrying the class, the swap cascades to the entire scene.

### 4.5 Listening for theme changes (custom renderers)

For surfaces that **can't** piggyback on looked-up colors — a `WebView` (HTML/CSS), a
`Canvas`, an off-screen image — register an `onChange` listener and re-render.

```java
import fan.summer.zhiflow.api.theme.ThemeService;
import javafx.scene.web.WebView;

WebView web = new WebView();

// Re-render this surface whenever the theme changes.
ThemeService.onChange(theme -> {
    // Runs on the FX thread. Rebuild the surface's content for the new theme.
    web.getEngine().loadContent(renderMyHtml(theme));
});
```

Always `removeListener` when the surface is discarded, to avoid leaks and stale callbacks:

```java
ThemeService.removeListener(myListener);
```

> **Important:** register the listener as a stable reference (a field or `final` lambda
> captured into a field) so you can pass the *same* instance to `removeListener`.

### 4.6 The WebView sync pattern (from `MarkdownRenderer`)

A `WebView` renders HTML, which has its own CSS — JavaFX looked-up colors are **not**
available there. `MarkdownRenderer` solves this by embedding **two** CSS text blocks
(`DARK_CSS` / `LIGHT_CSS`) and picking one at render time, then re-rendering on
`ThemeService.onChange`.

```java
// MarkdownRenderer.java — the pattern, abridged
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
    String css = (theme == ThemeService.Theme.LIGHT) ? LIGHT_CSS : DARK_CSS;  // pick variant
    Node document = PARSER.parse(markdown);
    return wrapHtml(RENDERER.render(document), css);                          // embed + wrap
}
```

Note the palette mapping — the WebView CSS intentionally mirrors the JavaFX tokens even
though the values are duplicated, because the two CSS worlds cannot share variables:

| JavaFX token (dark) | WebView `DARK_CSS` equivalent |
|---|---|
| `-sk-bg`-ish canvas | `background: #1e1e2e` |
| `-sk-text`-ish | `color: rgba(255,255,255,0.98)` |
| `-sk-accent` | `a { color: #3574F0; }` (shared accent) |
| `-sk-bg-elevated` | `pre { background: rgba(255,255,255,0.06); }` |
| `-sk-border` | `pre/th/td { border: 1px solid rgba(255,255,255,0.10); }` |

| JavaFX token (light) | WebView `LIGHT_CSS` equivalent |
|---|---|
| `-sk-bg` | `background: #ffffff` |
| `-sk-text` | `color: #1E1E1E` |
| `-sk-accent` | `a { color: #3574F0; }` |
| `-sk-bg-elevated` | `pre/th { background: #F7F8FA; }` |
| `-sk-border` | `border: 1px solid #DADCE0` |

> **Accent is shared:** `#3574F0` is the one value identical in both worlds and both
> themes. If you build your own WebView content, reuse this same accent and follow the
> dark=`#1e1e2e` / light=`#ffffff` background convention to stay visually consistent with
> the AI chat surface.

### 4.7 Standalone plugin windows — `Themes.applyTo(scene)`

Plugins embedded in the main scene via `createView()` inherit the tokens automatically (the
host already registered that scene). But a plugin that opens its **own** `Stage` (a modal
dialog, a standalone tool window) gets a fresh `Scene` with no stylesheet and no theme class.
Call **`Themes.applyTo(scene)`** to fix both:

```java
import fan.summer.zhiflow.api.theme.Themes;
import javafx.scene.Scene;
import javafx.stage.Stage;

Stage dialog = new Stage();
Scene scene = new Scene(content, 480, 320);

Themes.applyTo(scene);   // ← the ONE call plugins make. Loads CSS + stamps theme class.
                         //   Internally delegates to ThemeService.registerScene(scene).

dialog.setScene(scene);
dialog.show();
```

`Themes.applyTo` simply delegates to `ThemeService.registerScene`, so the window is tracked:
a later `ThemeService.set(...)` will re-theme it too. **Plugins should call `Themes.applyTo`,
not `ThemeService` internals** — that is the supported, stable surface.

The stylesheet resource path (used internally; plugins rarely need it directly):

```java
Themes.COMMON_CSS = "/css/zhiflow-common.css";   // resource inside the API JAR
Themes.commonStylesheetUrl();                      // → external-form URL for getStylesheets()
```

### 4.8 Persisting the user's choice

`ThemeService` itself has **no database dependency** — it only holds runtime state. The host
application is responsible for persisting the choice under the DB key **`"theme"`** with
value `"dark"` or `"light"`, and calling `ThemeService.set(...)` at startup.

```
  DB key "theme"  ∈  { "dark", "light" }
```

Typical startup flow:

```java
// 1. Read persisted preference
String stored = settingsDb.get("theme", "dark");          // default dark
ThemeService.Theme initial =
    "light".equalsIgnoreCase(stored) ? Theme.LIGHT : Theme.DARK;

// 2. Register scene first (so the class lands on the right root), then set the theme
ThemeService.registerScene(primaryScene);
ThemeService.set(initial);                                 // stamps + notifies

// 3. On user toggle, persist + apply
settingsDb.put("theme", "light");
ThemeService.set(ThemeService.Theme.LIGHT);
```

---

## 5. AI Development Checklist

When generating themed UI for ZhiFlow, you **MUST** satisfy all of the following. Treat
each item as a hard gate.

- [ ] **Use tokens or utility classes for every color.** Reference `-sk-*` tokens in CSS, or
      apply `.sk-t1`/`.sk-t2`/`.sk-t3`/`.sk-surface`/`.sk-surface-soft`/`.sk-outlined`/
      `.sk-outlined-strong` (and the composite `.sk-field`, `.sk-table`, …) via
      `getStyleClass()`. **Never** put a hex value or a `-sk-*` token inside a `setStyle()`
      string — inline styles are not evaluated against looked-up colors.
- [ ] **Inline `setStyle` is only for size/padding/radius.** Geometry is fine inline:
      `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")`. Color is not.
- [ ] **Standalone `Stage`/`Scene`? Call `Themes.applyTo(scene)`.** Do not call
      `ThemeService.registerScene` directly from plugin code; do not manually add
      `zhiflow-common.css` to `getStylesheets()`.
- [ ] **Custom rendering surface (WebView/Canvas)? Register `ThemeService.onChange`.** Rebuild
      the surface's content for the new theme inside the callback (see the `MarkdownRenderer`
      pattern). Remove the listener when the surface is torn down.
- [ ] **Persist the choice via DB key `"theme"` = `"dark"`/`"light"`.** Read it at startup and
      call `ThemeService.set(...)`. Default to dark if absent.
- [ ] **Selection uses the neutral-fill + accent-strip pattern.** Selected background =
      `-sk-bg-selected`; the only accent is the 3 px left strip / accent label. Never flood
      the whole selected area with `-sk-accent`.
- [ ] **Accent is scarce.** `-sk-accent` appears on primary actions, links, focus borders,
      and the selection indicator — not as a large fill.
- [ ] **Status colors are semantic only.** `-sk-success`/`-sk-warning`/`-sk-danger` mean
      success/caution/error; never decorative.
- [ ] **Verify contrast.** Body text must hit ≥4.5:1 against its background (use the
      [contrast matrix](#contrast-matrix-wcag-aa)); disabled text may be lower-contrast by
      design but only for non-actionable content.
- [ ] **Do not invent new token names.** Use only the 19 tokens in the
      [Token Reference Table](#token-reference-table). If you genuinely need a new semantic
      color, add it to `zhiflow-common.css` first, then document it here.

---

## 6. Anti-patterns

Each anti-pattern shows the mistake, why it breaks, and the correction.

### AP1 — Hex color in `setStyle()` (breaks on theme switch)

```java
// ❌ WRONG — hardcoded dark value; stays dark forever, even in light theme
label.setStyle("-fx-text-fill: #D0D0D0;");
box.setStyle("-fx-background-color: #2B2B2B;");
```

This paints the literal dark-theme values regardless of the active theme, and it will **not**
update when the user toggles to light. Worse, even *token* references fail inline:

```java
// ❌ ALSO WRONG — looked-up colors are NOT resolved in inline setStyle
label.setStyle("-fx-text-fill: -sk-text;");
```

```java
// ✅ CORRECT — apply the utility class; the token resolves & re-resolves
label.getStyleClass().add("sk-t1");
// or for a custom panel surface:
box.getStyleClass().add("sk-surface");   // -sk-bg-elevated
```

**Rule:** color → class; size/padding/radius → inline.

### AP2 — Accent as a large background fill

```java
// ❌ WRONG — a whole panel flooded with accent blue; loud, fights the neutral aesthetic
pane.setStyle("-fx-background-color: #3574F0;");   // also fails AP1 (inline hex)
region.getStyleClass().add("..."); // some class that paints a big -sk-accent area
```

```java
// ✅ CORRECT — neutral surface + accent reserved for the single primary action
pane.getStyleClass().add("sk-surface");             // neutral -sk-bg-elevated
Button action = new Button("Run");
action.getStyleClass().add("sk-btn-primary");       // accent on the button only
```

Accent is an *exclamation mark*, not wallpaper.

### AP3 — Blue-flood selection

```java
// ❌ WRONG — selected row painted entirely with accent
row.setStyle("-fx-background-color: #3574F0;");
```

```java
// ✅ CORRECT — neutral selected fill + 3px leading accent strip (per .sk-table/.sk-tab-pane)
row.getStyleClass().addAll("sk-table");             // selected row → -sk-bg-selected,
// and label text → -sk-accent; strip rendered via the component's CSS
```

See [P3](#p3--selection--neutral-fill--3-px-left-accent-strip-not-blue-flood).

### AP4 — Inventing new token names

```css
/* ❌ WRONG — not in the system; nobody else uses it; won't switch consistently */
.my-card { -fx-background-color: -sk-card-bg; }     /* no such token */
```

```css
/* ✅ CORRECT — use an existing token, or add the token to zhiflow-common.css first */
.my-card { -fx-background-color: -sk-bg-elevated; }
```

The 19 tokens are the contract. Extending it is fine, but the CSS must define the new token
under **both** `.theme-dark` and `.theme-light`, and this doc must be updated.

### AP5 — Forgetting `Themes.applyTo` on a standalone window

```java
// ❌ WRONG — standalone Stage is unstyled; no tokens resolve
Stage popup = new Stage();
popup.setScene(new Scene(myContent));
popup.show();
```

```java
// ✅ CORRECT — apply the theme to the standalone scene
Stage popup = new Stage();
Scene scene = new Scene(myContent);
Themes.applyTo(scene);                 // loads CSS + stamps theme class + tracks for swaps
popup.setScene(scene);
popup.show();
```

### AP6 — Status colors used decoratively

```java
// ❌ WRONG — warning amber used to make a "featured" badge look cheerful
badge.setStyle("-fx-text-fill: #F0A732;");
```

```java
// ✅ CORRECT — use a neutral/category color for decoration; reserve amber for caution
badge.getStyleClass().add("sk-t2");
// or an IconStyle category color per doc 06 for category badges
```

### AP7 — Leaking an `onChange` listener

```java
// ❌ WRONG — anonymous listener can never be removed; surface leaks after close
ThemeService.onChange(t -> web.getEngine().loadContent(render(t)));
```

```java
// ✅ CORRECT — keep a reference; remove on dispose
private final Consumer<ThemeService.Theme> themeListener =
    t -> web.getEngine().loadContent(render(t));

ThemeService.onChange(themeListener);
// on close:
ThemeService.removeListener(themeListener);
```

### AP8 — `set()` / `registerScene()` off the FX thread

```java
// ❌ WRONG — mutates scene graph from a background thread
new Thread(() -> ThemeService.set(Theme.LIGHT)).start();
```

```java
// ✅ CORRECT — always on the JavaFX Application Thread
Platform.runLater(() -> ThemeService.set(Theme.LIGHT));
```

---

## 7. References

### Source files (canonical)

| What | Path |
|---|---|
| Token + utility-class definitions | [`ZhiFlow-Api/src/main/resources/css/zhiflow-common.css`](../../ZhiFlow-Api/src/main/resources/css/zhiflow-common.css) |
| Theme engine (DARK/LIGHT, `current/set/registerScene/onChange/removeListener`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java`](../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/ThemeService.java) |
| Plugin-facing helper (`applyTo`, `COMMON_CSS`) | [`ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java`](../../ZhiFlow-Api/src/main/java/fan/summer/api/theme/Themes.java) |
| WebView theme-sync reference (`DARK_CSS`/`LIGHT_CSS`) | [`ZhiFlow/src/main/java/fan/summer/ai/util/MarkdownRenderer.java`](../../ZhiFlow/src/main/java/fan/summer/ai/util/MarkdownRenderer.java) |

### Design baseline

| What | Path |
|---|---|
| Authoritative IDEA New UI redesign spec | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| UI design docs master spec | [`docs/superpowers/specs/2026-07-01-ui-design-docs-design.md`](../superpowers/specs/2026-07-01-ui-design-docs-design.md) |

### Sibling UI design docs

| Doc | Link |
|---|---|
| 01 — UI Design System (philosophy, typography, spacing) | [01-design-system.md](01-design-system.md) |
| 02 — JavaFX Implementation Guide (contracts, class naming) | [02-javafx-implementation.md](02-javafx-implementation.md) |
| 08 — Accessibility Guide (full WCAG requirements) | [08-accessibility-guide.md](08-accessibility-guide.md) |

---

*Token values in this document are reproduced verbatim from `zhiflow-common.css` and
verified with `grep`. If the CSS ever changes, this doc must be regenerated to match — the
CSS is the source of truth, not this page.*
