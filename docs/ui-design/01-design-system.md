# 01 · UI Design System

> **Role:** This is the **constitution** of the FengYu user interface — the design
> philosophy and cross-cutting principles that every screen, every component, and every
> plugin must obey. It does **not** restate exact color values (those live in
> [05 Theme & Color System](05-theme-color-system.md)) or component-level CSS (those live in
> [03 Component Library](03-component-library.md)). Instead it defines *why* the UI looks the
> way it does, the layout primitives, the typography/spacing/radius scales, and how FengYu
> extends the JetBrains IDEA 2025 **New UI** language it was modeled on.

| | |
|---|---|
| **Doc type** | Philosophy + global layout + scales (top-level entry) |
| **Audience** | Anyone touching the UI — designers, plugin authors, AI code generators |
| **Window source** | [`FengYu/src/main/java/fan/summer/app/FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java) · [`ui/MainWindow.java`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java) |
| **Reference spec** | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| **Related** | [02 JavaFX Implementation](02-javafx-implementation.md) · [03 Component Library](03-component-library.md) · [05 Theme & Color System](05-theme-color-system.md) · [06 Icon System](06-icon-system.md) · [07 Animation](07-animation-guidelines.md) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Spec Tables / Layout](#3-spec-tables--layout)
   - [3.1 Global Layout](#global-layout)
   - [3.2 Typography](#typography)
   - [3.3 Spacing Grid](#spacing-grid)
   - [3.4 Radius Scale](#radius-scale)
   - [3.5 Elevation & Shadow](#elevation--shadow)
   - [3.6 Information Hierarchy](#information-hierarchy)
4. [Differences from IDEA New UI](#4-differences-from-idea-new-ui)
5. [AI Development Checklist](#5-ai-development-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

FengYu is a **plugin-based desktop toolkit** built on JavaFX 21. Its user interface is a
deliberate, faithful implementation of the **JetBrains IntelliJ IDEA 2025 "New UI"** visual
language: neutral-gray surfaces, a single restrained accent color, flat shapes, and motion
that serves feedback rather than spectacle.

This document is the top of the UI documentation tree. Read it first for the *philosophy*,
then drop into the deeper references for the *mechanics*:

- To **start coding** a plugin UI → [02 JavaFX Implementation](02-javafx-implementation.md)
- For **exact color values** → [05 Theme & Color System](05-theme-color-system.md)
- For **a specific widget** → [03 Component Library](03-component-library.md)
- For **icons** → [06 Icon System](06-icon-system.md)
- For **motion** → [07 Animation Guidelines](07-animation-guidelines.md)

### The single-source-of-truth convention

The UI doc set avoids duplication by design. Each kind of fact lives in exactly one place and
is *linked* from everywhere else:

| Fact | Authoritative doc |
|---|---|
| Token hex values & contrast ratios | [05](05-theme-color-system.md#token-reference-table) |
| CSS class names & naming convention | [02](02-javafx-implementation.md#css-naming) / [03](03-component-library.md) |
| Icon names, sizes, `IconStyle` colors | [06](06-icon-system.md#icon-reference) |
| Animation durations & easings | [07](07-animation-guidelines.md) |
| Component CSS & states | [03](03-component-library.md) |

If you find yourself restating any of the above in a new screen or plugin, stop and link
instead.

---

## 2. Design Principles

Four non-negotiable principles. Every layout decision in FengYu derives from one of these.

### P1 — Functional-first

FengYu is a **toolbox**, not a showcase. The UI exists to get the user to a tool and let
it do its job. Decoration that does not aid comprehension or feedback has no place.

- **Do** lead with content (the tool grid), keep chrome minimal, and make the most common
  action (launch a tool) one click.
- **Don't** add visual effects, illustrations, or animations that don't explain state.
  A tool card that pulses while running is feedback; a card that shimmers on idle is noise.

### P2 — Restrained IDEA New UI aesthetics

The defining trait of the New UI is **restraint**: neutral grays everywhere, flat surfaces,
and a single accent (`#3574F0`, the `-sk-accent` token) used surgically. See
[P1 of 05](05-theme-color-system.md#2-design-principles) for the color rationale.

- **Do** let gray surfaces and typography carry the design. Reserve the accent for primary
  actions, focus rings, and the **selection indicator** (the 3 px left strip on an active
  sidebar item — see [P3 below](#p3--darklight-theme-parity) and
  [03 · NavItem](03-component-library.md)).
- **Don't** flood a surface with accent color, paint large areas blue, or stack colored
  panels. The New UI is a gray UI with blue punctuation.

### P3 — Dark/light theme parity

Every screen must look intentional and pass accessibility in **both** themes. There is no
"primary" theme — dark and light are first-class. This is enforced structurally: colors are
never hardcoded, only referenced as `-sk-*` tokens, so
[`ThemeService.set(Theme)`](../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java)
can swap themes with zero flicker.

- **Do** color every node with a `-sk-*` token or `.sk-t*`/`.sk-surface*` utility class, and
  verify contrast in both themes via the
  [contrast matrix](05-theme-color-system.md#contrast-matrix-wcag-aa).
- **Don't** write `setStyle("-fx-background-color: #2B2B2B")`. That value is frozen and will
  be wrong the moment the user toggles themes. This is the single most common UI bug.

### P4 — Plugins blend as native

A third-party plugin dropped into `.fengyu/plugin/` must be visually indistinguishable from
a built-in tool. The same `.sk-*` foundation components, the same tokens, the same fonts and
icons are available to every plugin via [`Themes.applyTo(scene)`](02-javafx-implementation.md).
There is no "plugin look."

- **Do** build plugin UIs from `.sk-*` foundation classes and `-sk-*` tokens — exactly as the
  11 built-in tools do.
- **Don't** ship a plugin with its own bespoke color palette, custom font, or a different
  button shape. If the foundation components don't cover a need, prefer extending them over
  inventing a parallel visual language.

---

## 3. Spec Tables / Layout

<span id="global-layout"></span>

### 3.1 Global Layout

The main window is a classic IDE shell: a native OS title bar, a left **Sidebar**, a central
**ContentArea**, and a bottom **StatusBar**. Drawn here in the same ASCII style as
[`docs/architecture.md`](../architecture.md):

```
┌──────────────────────────────────────────────────────────────────┐
│  Native OS title bar  (StageStyle.DECORATED — not custom chrome)  │
├────────────┬─────────────────────────────────────────────────────┤
│            │  ContentArea                                        │
│  Sidebar   │  ┌───────────────────────────────────────────────┐  │
│  (nav,     │  │ Search bar (⌘K)                                │  │
│  catego-   │  ├───────────────────────────────────────────────┤  │
│  ries,     │  │                                               │  │
│  favorites,│  │   Tool grid (FlowPane of ToolCards)           │  │
│  theme     │  │   — OR — a cached plugin view (showPage)      │  │
│  toggle)   │  │   — OR — the detail/launch panel              │  │
│            │  │                                               │  │
│  ~56 px    │  └───────────────────────────────────────────────┘  │
│  expanded  │                                                     │
│  /collapsi-│              DetailPanel (right, slide-in 300 ms)   │
│  ble       │                                                     │
├────────────┴─────────────────────────────────────────────────────┤
│  StatusBar (mono clock · status dot · status text · 28 px)        │
└──────────────────────────────────────────────────────────────────┘
```

**Window facts** (verified in
[`FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java)):

| Property | Value | Source |
|---|---|---|
| Initial scene size | **960 × 620** | `FengYuApp.java:113` |
| Minimum window size | **800 × 520** | `FengYuApp.java:137–138` |
| Window chrome | **Native** (`StageStyle.DECORATED`) | `FengYuApp.java:133` |
| Title | `FengYu` | `FengYuApp.java:134` |
| Layout root | `BorderPane` (top = none, left = Sidebar, center = ContentArea, bottom = StatusBar) | `MainWindow.java` |

**Region roles:**

| Region | Component | Purpose | Source |
|---|---|---|---|
| Left | [`Sidebar`](../../FengYu/src/main/java/fan/summer/ui/sidebar/Sidebar.java) | Navigation: categories (DEV/TEXT/IMAGE/NET/OTHER), AI, Plugins, Favorites, Settings; theme toggle in footer. Collapsible (persisted as `sidebar.collapsed`). | `ui/sidebar/Sidebar.java` |
| Center | [`ContentArea`](../../FengYu/src/main/java/fan/summer/ui/content/ContentArea.java) | Search bar + tool grid (FlowPane of `ToolCard`s) + cached plugin pages. `showPage(node, title)` swaps content with a 220/180 ms cross-fade. | `ui/content/ContentArea.java` |
| Right (overlay) | [`DetailPanel`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) | Slide-in (300 ms) launch panel for a hovered/selected tool — icon, name, description, launch button. | `ui/content/DetailPanel.java` |
| Bottom | StatusBar (in [`MainWindow`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java)) | Mono clock, pulsing status dot (2500 ms), status text. 28 px tall. | `ui/MainWindow.java` |

<span id="typography"></span>

### 3.2 Typography

**Font stack** (applied globally, never overridden per-component):

```
"SF Pro Text", "Inter", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif
```

The stack resolves to the platform's best native UI font (SF on macOS, Segoe on Windows,
PingFang/YaHei for CJK) and degrades gracefully. **Never** set a different `-fx-font-family`
— the stack is what keeps the UI native-feeling on every OS.

**Size scale.** FengYu uses a tight, IDE-appropriate scale around a **13 px base**. Every
size in the app maps to one of these:

| Size | Token-ish name | Used for | Example source |
|---|---|---|---|
| 11 px | micro | Section titles, eyebrow labels | `.section-title` |
| 12 px | caption | Status bar text, secondary metadata | `.status-text` (mono) |
| 13 px | **body** (base) | Tool names, button labels, body text, nav labels | `.tool-name`, `.sk-btn-*` |
| 13.5 px | body-large | AI chat message text | `.ai-msg-text` |
| 15 px | heading | Section headers, dialog titles | `.section-header` |

> **Rule:** if a size isn't in this table, it isn't in the UI. Pick the closest step rather
> than inventing a 14 px or 16 px. Headings stay small because this is an IDE, not a marketing
> site — large type wastes vertical space that tools need.

**Weight & color.** Default weight is regular; emphasis is conveyed by **color** (moving text
from `-sk-text-secondary` → `-sk-text`) far more often than by bold. Bold is reserved for the
rare heading and the pressed/active affordance. See [§3.6 Information Hierarchy](#information-hierarchy).

<span id="spacing-grid"></span>

### 3.3 Spacing Grid

A **4 px base unit** governs every margin, padding, and gap. All spacing is a multiple of 4
(half-steps of 2 are tolerated only for 1 px hairlines and the 2/3 px accent strips).

| Value | Use |
|---|---|
| 4 px | Tight internal padding, icon-to-label gap inside a button/chip |
| 8 px | Default inner padding (cards, fields), small gaps between siblings |
| 12 px | Medium gaps, padding inside list rows / nav items |
| 16 px | Section padding, gaps between card columns |
| 20 px | Generous section padding |
| 24 px | Outer page padding, gaps between major regions |

> **Rule of thumb:** when in doubt, use 8 px. The grid is what makes the dense, scannable IDE
> layout feel orderly. Off-grid spacing (13 px, 7 px) reads as broken even when the user can't
> articulate why.

<span id="radius-scale"></span>

### 3.4 Radius Scale

FengYu uses a small, consistent set of corner radii (CSS `-fx-background-radius`):

| Radius | Where | Example classes |
|---|---|---|
| **999 px** (capsule / pill) | Search bar, AI input bar — fully rounded "floating" controls | `.search-bar` |
| **8 px** | Cards, tables, popups — the "medium surface" radius | `.tool-card`, `.sk-table`, popups |
| **6 px** | Buttons, input fields, nav items — the "control" radius | `.sk-btn-primary`, `.sk-field`, `.nav-item` |
| **10 px** | Dialogs, notifications — the "elevated surface" radius | `.sk-dialog`, `.sk-notif-*` |

> **Shape language:** controls are 6 px, surfaces they sit on are 8 px, the modal surfaces
> above those are 10 px. The pill is reserved for *floating* controls (search/AI input) to
> distinguish them from in-grid controls. Mixing radii arbitrarily breaks the hierarchy.

<span id="elevation--shadow"></span>

### 3.5 Elevation & Shadow

FengYu is **flat by default**. Drop shadows are a scarce resource reserved for surfaces
that genuinely float *above* the content plane — never used to decorate flat panels.

| Surface | Elevated? | Shadow |
|---|---|---|
| Tool cards, nav items, fields, tables | **No** — flat | none |
| Detail panel | Yes — slides over content | `-sk-shadow` (tokenized, see [05](05-theme-color-system.md)) |
| Dialogs (`.sk-dialog`) | Yes — modal | `-sk-shadow` |
| Notifications (`.sk-notif-root`) | Yes — toast | `-sk-shadow` |

> The black drop shadows that used to be hardcoded were tokenized to `-sk-shadow` in v3.2.0
> so they resolve correctly in both themes (a flat black shadow reads wrong on a light
> surface). See [05](05-theme-color-system.md) for the token. **Anti-pattern:** sprinkling
> `-fx-effect: dropshadow(...)` on cards or buttons to "make them pop." They don't pop; they
> just look heavy. The New UI is flat.

<span id="information-hierarchy"></span>

### 3.6 Information Hierarchy

Text prominence is a three-step ladder expressed entirely through the text tokens (see
[05](05-theme-color-system.md#token-reference-table)):

| Tier | Token | Utility class | Use |
|---|---|---|---|
| Primary | `-sk-text` | `.sk-t1` | Headings, body copy, the thing the user is reading |
| Secondary | `-sk-text-secondary` | `.sk-t2` / `.sk-fill-2` | Captions, labels, supporting metadata |
| Disabled / hint | `-sk-text-disabled` | `.sk-t3` / `.sk-fill-3` | Placeholders, disabled controls, non-actionable info |

> **Promotion, not bolding.** To draw attention, move text up a tier (secondary → primary)
> or add the `-sk-accent` color for the rare action/selection label. Reserve bold for
> pressed/active states. **Never** use `-sk-text-disabled` for content the user needs to read
> — its contrast is intentionally below AA (see the
> [contrast matrix](05-theme-color-system.md#contrast-matrix-wcag-aa)); it exists only for
> content the user *cannot act on*.

---

## 4. Differences from IDEA New UI

FengYu adopts the IDEA New UI's *spirit* (neutral grays, restrained accent, flat surfaces,
surgical selection indicator) but is **not** a clone of an IDE — it's a *toolbox*. The
differences below are deliberate and define the product's identity.

### Intentionally the same

| Aspect | Shared with IDEA New UI |
|---|---|
| Accent color | `#3574F0` (`-sk-accent`) — identical brand blue |
| Selection treatment | Neutral `-sk-bg-selected` fill + **3 px left accent strip**, not a blue flood |
| Surface palette | Neutral grays (`-sk-bg`, `-sk-bg-elevated`, `-sk-bg-hover`, `-sk-bg-selected`) |
| Flat aesthetic | No gradients, no glassmorphism, shadows only on modals/toasts |
| Typography | Small, IDE-scale sans-serif, color-led hierarchy |

### FengYu-specific additions

| Addition | Why it's here | Where |
|---|---|---|
| **Tool-card grid** | A FlowPane of `ToolCard`s (152 × 130 px) is the home screen — tools are discovered visually as cards, not as a menu tree. IDEA has no equivalent; its "cards" are settings tiles. | `ContentArea`, see [03 · Tool Card](03-component-library.md) |
| **Sidebar category sections** | Group nav by `ToolCategory` (DEV / TEXT / IMAGE / NET / OTHER) plus AI, Plugins, Favorites, Settings. Reflects FengYu's plugin taxonomy. | `Sidebar.java` |
| **Detail Panel** | A right-side slide-in (300 ms) that previews a hovered/selected tool and offers a launch button — a hybrid of IDEA's "search everywhere" preview and a detail drawer. | `DetailPanel.java` |
| **Tool-card "running" pulse** | Cards pulse (2500 ms) while their plugin has running tasks, so background work is visible without a separate jobs view. | `ToolCard.java:106`, see [07](07-animation-guidelines.md) |
| **Notification system** | `.sk-notif-*` toasts (info/success/warning/error) for tool feedback — IDEA uses its own notification API; FengYu exposes a themed equivalent to plugins. | see [03 · Notification](03-component-library.md) |

### Intentionally dropped from IDEA

| Dropped | Reason |
|---|---|
| Multi-split editor tabs | FengYu shows one tool at a time (`showPage` cross-fade). Tools are not documents. |
| Toolbar / tool window buttons | The sidebar + grid is enough; an IDE-style toolbar would add chrome without value. |
| Heavy modal project structure | FengYu is flat: launch a tool, use it, leave. No project tree. |

---

## 5. AI Development Checklist

When generating UI for FengYu (host or plugin), you **MUST**:

- [ ] **Follow the four principles** — functional-first, restrained accent, theme parity,
      plugins-blend-native. If a proposed effect doesn't serve one of these, cut it.
- [ ] **Use the typography scale** (11/12/13/13.5/15 px) and the global font stack — never
      set a different `-fx-font-family` or an off-scale size.
- [ ] **Use the spacing grid** (multiples of 4; 8 px default) and the radius scale
      (6/8/10/999 by surface type).
- [ ] **Color only with `-sk-*` tokens or `.sk-t*` / `.sk-surface*` utility classes** — never
      inline hex. See [05](05-theme-color-system.md).
- [ ] **Keep surfaces flat.** Shadows are for modals/toasts/detail panel only; everyone else
      is flat.
- [ ] **Express hierarchy by tier, not bold** — `-sk-text` → `-sk-text-secondary` →
      `-sk-text-disabled`. Reserve `-sk-accent` for actions and the selection indicator.
- [ ] **For components**, drop into [03 Component Library](03-component-library.md); for
      motion, [07 Animation](07-animation-guidelines.md); for icons, [06](06-icon-system.md).

---

## 6. Anti-patterns

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| **Glassmorphism / frosted blur** | Deprecated in v3.2.0; the `.glass-*` classes were renamed to `.sk-*` (see the [New UI spec §7](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md)). Blur reads as "2018 web," not New UI. | Flat `-sk-bg-elevated` surfaces. |
| **Gratuitous drop shadows** on cards/buttons | The New UI is flat; shadows add visual weight without information. | Flat surfaces; reserve `-sk-shadow` for modals/toasts/detail panel. |
| **Blue-flood selection** (painting a selected row/item solid accent) | Too loud; the accent is punctuation, not fill. | Neutral `-sk-bg-selected` fill + 3 px left `-sk-accent` strip (the signature New UI rule). |
| **Off-grid spacing** (7 px, 13 px, 22 px) | Breaks the rhythm; the UI feels "off" even when users can't say why. | Multiples of 4 (8 px default). |
| **Inventing font sizes** (14 px, 16 px) | Erodes the tight IDE scale; headings balloon. | Pick the nearest step from the 11/12/13/13.5/15 scale. |
| **Hardcoded hex in `setStyle()`** | Breaks on theme switch — the value is frozen and won't re-resolve. | A `-sk-*` token or `.sk-t*` / `.sk-surface*` utility class. |
| **A parallel "plugin look"** (custom palette/font in a third-party tool) | Violates P4 — plugins must blend as native. | Build from `.sk-*` foundation components, exactly like the 11 built-ins. |

---

## 7. References

**Source files:**
- [`FengYu/src/main/java/fan/summer/app/FengYuApp.java`](../../FengYu/src/main/java/fan/summer/app/FengYuApp.java) — window size, `StageStyle.DECORATED`, title
- [`FengYu/src/main/java/fan/summer/ui/MainWindow.java`](../../FengYu/src/main/java/fan/summer/ui/MainWindow.java) — layout root, StatusBar
- [`ui/sidebar/Sidebar.java`](../../FengYu/src/main/java/fan/summer/ui/sidebar/Sidebar.java) · [`ui/content/ContentArea.java`](../../FengYu/src/main/java/fan/summer/ui/content/ContentArea.java) · [`ui/content/DetailPanel.java`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) · [`ui/content/ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java)

**Specs & sibling docs:**
- [New UI redesign spec](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) — the authoritative design source
- [`docs/architecture.md`](../architecture.md) — module/layout diagrams (matched ASCII style here)
- [02 JavaFX Implementation](02-javafx-implementation.md) — code playbook + CSS naming
- [03 Component Library](03-component-library.md) — per-component spec
- [05 Theme & Color System](05-theme-color-system.md) — exact token values + contrast matrix
- [06 Icon System](06-icon-system.md) · [07 Animation Guidelines](07-animation-guidelines.md) · [08 Accessibility Guide](08-accessibility-guide.md)
