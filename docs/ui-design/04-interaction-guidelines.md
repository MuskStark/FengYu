# 04 · Interaction Guidelines

> **Role:** This is the spec for **how users navigate, discover, and act** in SwissKitJ — the
> interaction flows, not the components. It tells you what happens when a user clicks a nav
> item, hovers a card, launches a tool, uninstalls a plugin, or hits a destructive action.
> Components live in [03](03-component-library.md); the animations these flows trigger live in
> [07](07-animation-guidelines.md); keyboard flows are extended for accessibility in
> [08](08-accessibility-guide.md).

| | |
|---|---|
| **Doc type** | Interaction flows + event-wiring patterns |
| **Audience** | Plugin authors, AI code generators, anyone wiring up user actions |
| **Source files** | [`ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) · [`ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) · [`ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) · [`ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java) · [`ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java) · [`ui/store/PluginStoreUi.java`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java) |
| **Notification API** | [`GlassNotification`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java) (`.sk-notif-*`) |
| **Related** | [03 Component Library](03-component-library.md) · [06 Icon System](06-icon-system.md) · [07 Animation](07-animation-guidelines.md) · [08 Accessibility](08-accessibility-guide.md) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Flow Tables](#3-flow-tables)
   - [3.1 Navigation](#navigation)
   - [3.2 Tool Discovery & Launch](#tool-discovery--launch)
   - [3.3 Plugin Lifecycle](#plugin-lifecycle)
   - [3.4 Plugin Store](#plugin-store)
   - [3.5 Forms & Validation](#forms--validation)
   - [3.6 Keyboard](#keyboard)
   - [3.7 Four-State Feedback](#four-state-feedback)
   - [3.8 Destructive Operations](#destructive-operations)
4. [JavaFX Templates](#4-javafx-templates)
5. [AI Checklist](#5-ai-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

SwissKitJ is a **toolbox**: the user opens it, finds a tool, uses it, and leaves. Every
interaction in the shell exists to make that loop fast and forgiving. This document catalogs
the real flows implemented in the host (verified against source) and the event-wiring
patterns a plugin author reuses to fit in natively.

Three things make the SwissKitJ interaction model what it is:

1. **Discoverability by default** — the home screen *is* a searchable grid of tool cards.
   The user never needs to know a tool's name to find it.
2. **Progressive disclosure** — a hovered/selected card reveals a detail panel; launching
   swaps the grid for the tool's view. Detail is opt-in, never forced.
3. **Forgiving actions** — destructive operations (uninstall, leave-with-running-tasks)
   confirm first; non-destructive navigation is instant and reversible.

---

## 2. Design Principles

### P1 — Discoverability

Users find tools by browsing cards and by typing in the search bar. Every tool is reachable
from the home grid in one click plus an optional detail peek. Don't bury tools in nested
menus — the grid + search is the navigation.

### P2 — Progressive disclosure

Show summary first (card → name/desc/tag), reveal detail on interest (detail panel → full
description + launch), commit on action (launch → tool view). Never front-load a tool's full
UI until the user has asked for it.

### P3 — Forgiving

Destructive operations require a second, explicit confirmation (see
[`GlassNotification.confirm`](#destructive-operations)). Navigation away from a running tool
either keeps it in the background (if `hasRunningTasks()`) or is freely reversible. The user
should never lose work to a stray click.

### P4 — Consistent feedback (four states)

Every asynchronous or data-driven surface presents exactly one of four states — **loading,
empty, error, success** — using the shared components (`.sk-notif-*`, `.progress-bar`,
`.sk-table` placeholder). A blank panel is a bug.

---

## 3. Flow Tables

<span id="navigation"></span>

### 3.1 Navigation

The left **Sidebar** is the primary navigation. It groups destinations by `ToolCategory`
(DEV / TEXT / IMAGE / NET / OTHER), plus the AI chat, the plugin store, favorites, and
settings. A theme toggle lives in the footer.

| Action | What happens | Persistence / source |
|---|---|---|
| Click a category nav-item | Filters the tool grid to that category; item becomes active (`-sk-bg-selected` + 3 px left `-sk-accent` strip + 160 ms scale pop). | `Sidebar.setOnCategorySelect` → `ContentArea` |
| Click AI / Plugins / Settings | Navigates to that page via `contentArea.showPage(view, title)` (220/180 ms cross-fade). | `MainWindow` |
| Collapse / expand sidebar | Sidebar width animates; state persisted under setting key **`sidebar.collapsed`** = `"true"`/`"false"`. | `Sidebar.java:292`, restored at `:323` |
| Toggle theme (footer) | `ThemeService.set(Theme)` swaps `.theme-dark`↔`.theme-light` on the scene root **instantly** (no animation); persisted under setting key `"theme"`. | [05 Theme & Color System](05-theme-color-system.md) |

> **The sidebar collapse is the only persisted layout state.** Don't persist ad-hoc window
> sizes or panel positions; the app's layout is deterministic.

<span id="tool-discovery--launch"></span>

### 3.2 Tool Discovery & Launch

The core loop. Every step has deliberate motion (cited from [07](07-animation-guidelines.md)):

```
┌────────────────┐    type     ┌──────────────────┐  hover   ┌─────────────────────┐
│  Home grid     │ ──────────▶ │  Filtered grid   │ ───────▶ │  DetailPanel        │
│  (all cards)   │  filters    │  (search match)  │  150 ms  │  slides in (300 ms) │
│                │  live       │                  │  scale   │  icon+desc+launch   │
└────────────────┘             └──────────────────┘          └─────────┬───────────┘
       ▲                                                              │ click launch
       │                                       220/180 ms             ▼
       │  back (Esc / back btn) ◀──────── cross-fade ◀────────┌──────────────┐
       │                                                        │  Plugin view │
       │  if hasRunningTasks() → stay cached (background)        │  (cached)    │
       │  else → evict cache                                     └──────────────┘
└─────────────────────────────────────────────────────────────────────────┘
```

| Step | Trigger | Effect | Animation (see [07](07-animation-guidelines.md)) |
|---|---|---|---|
| Search | Type in the search bar | Grid re-filters live; matched cards stagger in | staggered entry 240 ms + 35 ms per card (capped at ~30) |
| Card hover | Mouse over a `ToolCard` | Card scales up slightly; detail panel slides in on the right | hover scale 150 ms; DetailPanel slide-in **300 ms** |
| Launch | Click the card (or the detail-launch button) | Card squishes, then the plugin view cross-fades in | click scale 100 ms (auto-reverse); page cross-fade **220 ms in / 180 ms out** |
| Back | Back button / Esc | Return to the grid; cross-fade back | cross-fade 220/180 ms |

**View caching.** `MainWindow` caches each plugin's `createView()` result in a `cachedViews`
map. Launching a tool a second time reuses the cached `Node` — `createView()` is **not**
called again. The cache is evicted on back **only if** the plugin reports
`!hasRunningTasks()`; a tool with background work stays cached so the user can return to it.

> ⌘K is displayed in the search bar as a keyboard hint (`.search-kbd` Label `⌘K`); the search
> field filters the grid live as the user types into it.

<span id="plugin-lifecycle"></span>

### 3.3 Plugin Lifecycle

Plugins move through `activate → (foreground/background) → deactivate`, plus `uninstall`. The
host wires this in [`MainWindow`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java):

| Transition | Trigger | Effect | Source |
|---|---|---|---|
| **Activate** | Launch a tool | `registry.activate(plugin)`; view shown via `showPage`. | `MainWindow` launch callback |
| **Foreground / Background** | App focus / minimize or user navigates away | `onForeground` / `onBackground` hooks fire on the plugin (if it overrides them). | `SwissKitJPlugin` defaults |
| **Deactivate (back)** | Back button / Esc | If `hasRunningTasks()` → **keep cached** (background); else evict the cached view and `registry.deactivate()`. | `contentArea.setOnBack` |
| **Running indicator** | Plugin reports `hasRunningTasks()` | Its `ToolCard` shows the running dot with a **2500 ms pulse** so background work is visible in the grid. | `ToolCard.java:106` |
| **Uninstall** | Detail panel → uninstall button | **Confirms first** (see [§3.8](#destructive-operations)); on confirm: evict cache, deactivate if active, navigate to grid, `loader.uninstallPlugin(plugin)`. | `DetailPanel.showUninstallConfirm` → `contentArea.setOnUninstall` |

> The `hasRunningTasks()` contract is what makes "forgiving" work: a user can click back from
> a tool that's mid-job without killing it — it just drops to the background and keeps
> pulsing on its card. Override `hasRunningTasks()` truthfully in any plugin that does async
> work.

<span id="plugin-store"></span>

### 3.4 Plugin Store

Installing a plugin is a foreground async task with visible progress, handled by
[`PluginStoreUi`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java)
(online pane) and `LocalInstallPane` (local JAR install):

| Step | Effect |
|---|---|
| Browse | Online store lists available plugins (fetched remotely). |
| Install | Progress shown via `.progress-bar`; success/failure reported via `GlassNotification` toast. |
| Switch to local-installed tab | The store UI toggles between online and local-installed views. |
| After install | Plugin appears in its category in the sidebar/grid on next refresh. |

<span id="forms--validation"></span>

### 3.5 Forms & Validation

SwissKitJ forms (built from `.sk-field` / `.sk-combo` / `.sk-checkbox`) follow conservative
validation timing:

| Aspect | Rule |
|---|---|
| **When to validate** | On **blur** or **submit**, not on every keystroke. Live per-keystroke validation feels naggy in a desktop tool. |
| **Error message placement** | Immediately **below** the offending field, in `-sk-danger` text (use a label under the field, not a popup). |
| **Submit feedback** | Disable the submit button during async work; on result show a `GlassNotification` toast (success/info/warning/error). |
| **Required fields** | Mark with a `-sk-danger` asterisk or a clear label; never rely on color alone (see [08](08-accessibility-guide.md)). |

<span id="keyboard"></span>

### 3.6 Keyboard

| Key | Action | Source |
|---|---|---|
| **Esc** | Close the focused dialog/panel (e.g. `AboutDialog` closes on Esc); in a plugin view, back to the grid. | `AboutDialog.java:53` |
| **Tab / Shift+Tab** | Move focus through controls in DOM/logical order; the focus ring must be visible (see [08](08-accessibility-guide.md)). | default |
| **Enter / Space** | Activate the focused button/card. | default |
| **⌘K (displayed)** | Shown as the search-bar shortcut hint; the search field is the live filter. | `.search-kbd` label |

> Every action reachable by mouse must be reachable by keyboard — this is both an interaction
> principle and an accessibility requirement (extended in [08](08-accessibility-guide.md)).

<span id="four-state-feedback"></span>

### 3.7 Four-State Feedback

Any surface that loads data or runs async work shows exactly one of these states, using the
shared components:

| State | When | Component |
|---|---|---|
| **Loading** | While data is being fetched / a job is running | `.progress-bar` (6 px, `-sk-accent` fill) and/or the ToolCard running pulse |
| **Empty** | A query returned nothing / nothing to show | Empty-state copy in `.sk-table` placeholder text (`-sk-text-disabled`) or a centered label |
| **Error** | A load/job failed | `GlassNotification.notify(WARNING/ERROR, ...)` + inline `-sk-danger` message near the source |
| **Success** | A job completed | `GlassNotification.toast(SUCCESS, ...)` |

> **Anti-pattern:** a blank panel while loading. Even a disabled `.progress-bar` or a
> `-sk-text-disabled` "Loading…" label is better than nothing — it tells the user the app
> didn't freeze.

<span id="destructive-operations"></span>

### 3.8 Destructive Operations

Destructive actions (uninstall, overwrite, clear) require a **second, explicit confirmation**
via the shared modal:

```java
// The canonical confirmation — DetailPanel.showUninstallConfirm (DetailPanel.java:303)
boolean confirmed = GlassNotification.confirm(this, title, message);
if (confirmed) {
    doUninstall();
}
```

| Rule | Detail |
|---|---|
| **Always confirm** | `GlassNotification.confirm(context, title, message)` blocks for a yes/no; never delete/uninstall without it. |
| **Clear, irreversible copy** | The message must state *what* happens and that it's irreversible (e.g. the uninstall message names the plugin). Use `-sk-danger` for the destructive verb where appropriate. |
| **Default to cancel** | The safe choice is the default; the user must affirmatively choose to proceed. |
| **Reversible ≠ destructive** | Hiding a panel, toggling a setting, navigating away — these need no confirm. Reserve the dialog for data loss. |

---

## 4. JavaFX Templates

### 4.1 Sidebar → category select wiring

```java
// MainWindow wires the sidebar's category callback
sidebar.setOnCategorySelect(categoryId -> {
    contentArea.filterByCategory(categoryId);   // re-filters the tool grid
});
```

### 4.2 Card click → detail panel → launch → cached page

```java
// ToolCard fires onSelect AFTER its click squish (ToolCard.java:156–159)
setOnMouseClicked(e -> {
    ScaleTransition click = new ScaleTransition(Duration.millis(100), this);
    click.setAutoReverse(true); click.setCycleCount(2);
    click.setOnFinished(ev -> onSelect.accept(plugin));   // hand off to ContentArea
    click.play();
});

// ContentArea.onCardSelect → show DetailPanel (slide-in 300 ms),
// whose launch button calls contentArea.showPage(cachedView, name).
```

### 4.3 Launch with view caching + hasRunningTasks-aware back

```java
// MainWindow launch callback (paraphrased from source)
Node view = cachedViews.get(plugin);
if (view == null) {
    view = plugin.createView();
    PluginContext.wrapEvents(plugin, view);
    cachedViews.put(plugin, view);            // cache: createView() runs ONCE
}
registry.activate(plugin);
contentArea.showPage(view, plugin.getName());

// Back callback — keep running tools cached
contentArea.setOnBack(() -> {
    SwissKitJPlugin current = registry.getActivePlugin();
    if (current != null && !current.hasRunningTasks()) {
        cachedViews.remove(current);          // evict only when idle
    }
    registry.deactivate();
});
```

### 4.4 Theme-change re-render listener

```java
// For custom rendering (WebView/canvas) that can't auto-follow looked-up colors
ThemeService.onChange(theme -> Platform.runLater(() -> {
    myWebView.getEngine().reload();           // re-render with theme-aware CSS
}));
```

### 4.5 Confirmation dialog + notification toast

```java
// Destructive confirm
if (GlassNotification.confirm(view, I18n.get("detail.uninstall.confirmTitle"), msg)) {
    doUninstall();
}

// Success / info / warning feedback
GlassNotification.toast(view, GlassNotification.Type.SUCCESS, I18n.get("msg.saved"));
GlassNotification.notify(view, GlassNotification.Type.WARNING, I18n.get("setting.urlEmpty"));
```

> **Notification API** ([`GlassNotification`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java)):
> `toast(owner, type, message)`, `notify(owner, type, [title,] message)`,
> `confirm(owner, title, message) → boolean`. `Type` ∈ `INFO/SUCCESS/WARNING/ERROR` maps to
> `.sk-notif-info/-success/-warning/-error`.

---

## 5. AI Checklist

When wiring interactions in SwissKitJ (host or plugin), you **MUST**:

- [ ] **Cache the view** — `createView()` once; reuse the `Node`. Never rebuild on every
      activation.
- [ ] **Cross-fade on page switch** — use `showPage` (220/180 ms), don't hard-swap nodes.
- [ ] **Confirm destructive ops** — `GlassNotification.confirm(...)` before uninstall/delete.
- [ ] **Show one of the four states** — loading/empty/error/success, never blank.
- [ ] **Wire Esc** — dialogs/panels close on Esc; plugin view backs out to the grid.
- [ ] **Persist sidebar collapse** via setting key `sidebar.collapsed`, theme via `"theme"`.
- [ ] **Report `hasRunningTasks()` truthfully** so background work survives Back.
- [ ] **Use `Themes.applyTo`/`ThemeService.onChange`** for custom-rendered surfaces — never
      assume the theme; re-render on change.
- [ ] **Reach every action by keyboard** — not just the mouse (see [08](08-accessibility-guide.md)).

---

## 6. Anti-patterns

| Anti-pattern | Why it's wrong | Do instead |
|---|---|---|
| **Rebuilding the view on every activation** | Wastes work, loses user state, breaks caching. | Cache `createView()`; reuse the `Node`. |
| **No empty/error state** | A blank panel looks like a freeze. | Show loading/empty/error copy via the four-state components. |
| **Destructive action without confirm** | One stray click loses data. | `GlassNotification.confirm(...)` first; default to cancel. |
| **Blocking the FX thread** for async ops | Freezes the whole UI. | Run async work off the FX thread; update UI via `Platform.runLater`. |
| **Hard-swapping pages** | Jarring; loses place. | `showPage` cross-fade (220/180 ms). |
| **Lying `hasRunningTasks()`** | Back evicts a running tool's view → lost work. | Return `true` while work is in flight. |
| **Validating on every keystroke** | Naggy; fights the user mid-entry. | Validate on blur/submit. |

---

## 7. References

**Source files:**
- [`ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) — launch/back/uninstall wiring, view cache
- [`ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) — category select, `sidebar.collapsed` persistence
- [`ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) — search, `showPage`/`crossFadeTo`, grid filter
- [`ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java) — `onSelect` callback, running pulse
- [`ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java) — slide-in, `showUninstallConfirm`
- [`ui/store/PluginStoreUi.java`](../../SwissKit/src/main/java/fan/summer/ui/store/PluginStoreUi.java) · [`ui/setting/SwissKitJSettingUi.java`](../../SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java)
- [`GlassNotification.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/GlassNotification.java) — toast/notify/confirm API

**Sibling docs:**
- [03 Component Library](03-component-library.md) — the components these flows use
- [06 Icon System](06-icon-system.md) · [07 Animation Guidelines](07-animation-guidelines.md) — motion cited throughout
- [08 Accessibility Guide](08-accessibility-guide.md) — keyboard flows + reduced motion
