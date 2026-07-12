# 02 · JavaFX Implementation Guide

> **Role:** This is the **developer / AI playbook** for turning FengYu UI design into running JavaFX code.
> It defines the plugin contract you must implement, the CSS class-naming convention every node obeys,
> and a copy-paste plugin skeleton. Later docs — especially [03 Component Library](03-component-library.md) —
> link back here for the [`#css-naming`](#css-naming) convention and the [`#plugin-skeleton`](#plugin-skeleton) template.

| | |
|---|---|
| **Doc type** | Plugin contract + implementation patterns |
| **Audience** | Plugin authors, AI code generators, anyone who builds a FengYu tool |
| **Source of truth** | [`FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| **Companion plugin guide** | [`docs/plugins/ui.md`](../plugins/ui.md) (bilingual, layout pitfalls + StepWizard) |
| **Related** | [01 Design System](01-design-system.md) · [05 Theme & Color System](05-theme-color-system.md) · [06 Icon System](06-icon-system.md) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Spec Tables](#3-spec-tables)
   - [3.1 `SwissKitJPlugin` Method Contract](#fengyujplugin-method-contract)
   - [3.2 CSS Class Naming Convention](#css-naming)
   - [3.3 Layout-Container Selection Guide](#layout-container-selection-guide)
4. [JavaFX Implementation Template](#4-javafx-implementation-template)
   - [4.1 Plugin Skeleton](#plugin-skeleton)
   - [4.2 Icons · `MdiIconUtil`](#42-icons--mdiiconutil)
   - [4.3 Theming a standalone Stage · `Themes.applyTo`](#43-theming-a-standalone-stage--themesapplyto)
   - [4.4 I18n patterns](#44-i18n-patterns)
   - [4.5 The three layout pitfalls](#45-the-three-layout-pitfalls)
5. [AI Development Checklist](#5-ai-development-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

FengYu is a **JavaFX 21** desktop toolbox. Two architectural decisions shape everything in this document:

1. **The UI is built entirely in Java code — there is NO FXML.** Every screen is assembled from
   `javafx.scene.*` nodes in `createView()`. There is no `.fxml` file, no `FXMLLoader`, no controller
   wiring. This keeps plugins self-contained, refactor-friendly, and dependency-light (an external
   plugin JAR needs only `FengYu-Api` on its classpath).

2. **Theming happens entirely through CSS looked-up colors.** No node sets a color inline. The dual-theme
   (dark/light) palette is a set of 14 `-sk-*` tokens declared once in
   [`fengyu-common.css`](../../FengYu-Api/src/main/resources/css/fengyu-common.css), switched by a
   single class on the scene root. The token values, contrast matrix, and the full theme lifecycle live
   in [05 Theme & Color System](05-theme-color-system.md) — **this doc does not duplicate color values.**

> **What this document is for.** A human or an AI that has already read the design docs (01, 03, 06) asks:
> *"I know what the JSON Formatter tool should look like — how do I turn that into a compilable,
> theme-correct, host-compatible plugin?"* This page answers that, end to end: the interface you
> implement, the methods you must/may override, the CSS classes you put on nodes, and the gotchas that
> break layout.

### The four moving parts

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       HOST APPLICATION (FengYu)                        │
│                                                                          │
│   ┌─────────────┐   discovers   ┌──────────────────────────────────────┐ │
│   │  ServiceLoader│ ───────────► │  plugins/*.jar                       │ │
│   │  META-INF/   │              │  └─ fan.summer.fengyu.api.SwissKitJPlugin    │ │
│   │  services/   │              │     (one class implements interface)  │ │
│   └─────────────┘              └──────────────────────────────────────┘ │
│           │                              │                               │
│           │ getId/getName/getCategory     │ createView()                  │
│           │ getMdiIcon/getIconStyle       │  ──► Node (cached)            │
│           ▼                              ▼                               │
│   ┌──────────────┐              ┌────────────────────────┐              │
│   │ Sidebar +    │              │ Content StackPane      │              │
│   │ tool cards   │              │  └─ your view (Node)   │              │
│   └──────────────┘              │     inherits the main  │              │
│                                 │     scene's CSS + theme│              │
│                                 └────────────────────────┘              │
└──────────────────────────────────────────────────────────────────────────┘
```

- **The contract** — `SwissKitJPlugin` (16 methods; [§3.1](#fengyujplugin-method-contract)).
- **The host** — calls metadata methods to build the sidebar/search, calls `createView()` once and caches
  the `Node`, embeds it in the content `StackPane`.
- **The stylesheet** — `fengyu-common.css`, loaded by the host onto the main scene. Your embedded view
  inherits it automatically; a standalone window must opt in via [`Themes.applyTo`](#43-theming-a-standalone-stage--themesapplyto).
- **The class taxonomy** — `.sk-*` for shared/component classes (from common.css), unprefixed shell
  classes (`nav-item`, `tool-card`, …), status modifiers ([§3.2](#css-naming)).

---

## 2. Design Principles

Five rules. The first three are restated from [05 Theme & Color System](05-theme-color-system.md) in their
implementation-specific form; the last two are specific to JavaFX code-gen.

### P1 — Code is the UI (no FXML, no markup)

There is no declarative layer between your plugin class and the pixels. `createView()` builds and returns a
`Node` graph in plain Java. Consequence: every visual is greppable, refactorable with the IDE, and reviewable
in a normal diff. **Do not** introduce FXML, mustache templates, or generated builders.

### P2 — Colors only via tokens or utility classes, never inline

> This is the single most important rule. Restated from [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle).

JavaFX **inline** `setStyle("-fx-...")` strings are **not** evaluated against looked-up color variables.
`node.setStyle("-fx-text-fill: -sk-text;")` will *not* resolve to the theme color and will *not* update on
theme switch. Therefore:

| What you want to set | How |
|---|---|
| **Color** (text fill, background, border, fill) | Apply a `.sk-*` utility/composite class via `node.getStyleClass().add("…")`. Never inline. |
| **Size / padding / radius** | Inline `setStyle("-fx-padding: 8 12; -fx-background-radius: 6;")` is **fine** — these are not looked-up colors. |

A node may carry **both** a color class and inline geometry styles simultaneously.

### P3 — Sizes and padding may be inline

Padding, gaps, radii, min/max dimensions, insets — all of these are geometry, not color, and are safe to set
inline (`setPadding`, `setHgap`, `setStyle("-fx-background-radius: 10;")`). Only **colors** are restricted to
CSS classes. This is the inverse of P2 and exists so you don't have to mint a new class just to add `8px`
padding.

### P4 — One cached view per plugin

`createView()` is called **once**, on first activation. The host caches the returned `Node` and reuses it for
every subsequent activation. Implications:

- **Do not** rebuild the view from scratch on every `onActivate()`. Build once in `createView()`, hold field
  references to the controls you need to mutate, and refresh *content* (not structure) in lifecycle hooks.
- It is fine (and common) to lazily build the view graph inside `createView()` itself on first call.
- If you need a multi-step workflow, use `fan.summer.fengyu.api.component.StepWizard` (see
  [`docs/plugins/ui.md`](../plugins/ui.md)) instead of swapping whole trees.

### P5 — CSS class naming follows the `sk-` prefix convention

Shared component/utility classes from `fengyu-common.css` are prefixed **`.sk-`** (e.g. `.sk-field`,
`.sk-btn-primary`). Shell-chrome classes (sidebar, tool cards, status bar) are **unprefixed** (`nav-item`,
`tool-card`). The full taxonomy, the v3.2.0 `.glass-*`→`.sk-*` migration, and the status-modifier convention
are in [§3.2](#css-naming).

---

## 3. Spec Tables

### 3.1 `SwissKitJPlugin` Method Contract
<span id="fengyujplugin-method-contract"></span>

The interface declares **16 methods**: **7 required** (no default), **9 with sensible defaults**. Every
signature below is reproduced **verbatim** from
[`SwissKitJPlugin.java`](../../FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) — copy them
as-is; do not paraphrase return types or add parameters.

#### Required methods (must implement — no default)

| # | Signature | Return type | Purpose |
|---|---|---|---|
| 1 | `String getId()` | `String` | Globally unique tool ID. Reverse-domain recommended (e.g. `"com.example.json-formatter"`). Built-ins use `"builtin.<slug>"`. |
| 2 | `String getName()` | `String` | Display name on the tool card + sidebar. i18n key recommended: `I18n.get("builtin.<slug>.name")`. |
| 3 | `String getDescription()` | `String` | One-line description on the card + detail panel. i18n key recommended. |
| 4 | `ToolCategory getCategory()` | `ToolCategory` | Sidebar grouping/filtering. One of `DEV`, `TEXT`, `IMAGE`, `NET`, `OTHER`. |
| 5 | `String getVersion()` | `String` | Semver string, e.g. `"1.0.0"`. |
| 6 | `String getMdiIcon()` | `String` | Material Design Icons name **without** the `mdi` prefix, e.g. `"code-json"`, **not** `"mdi-code-json"`. |
| 7 | `Node createView()` | `javafx.scene.Node` | Builds the main UI. Called **once**; the returned `Node` is cached and reused. Never `null`. |

#### Default methods (may override)

| # | Signature | Default | Override when… |
|---|---|---|---|
| 8 | `default IconStyle getIconStyle()` | `IconStyle.BLUE` | You want a different icon-tile tint (e.g. `PURPLE`, `TEAL`, `AMBER`, `RED`, `PINK`, `GRAY`). |
| 9 | `default ToolType getType()` | `ToolType.PLUGIN` | You are a **built-in** tool → return `ToolType.BUILTIN`. External plugins leave the default. |
| 10 | `default void onActivate()` | `{}` (no-op) | The tool enters the foreground — resume timers, restore UI state. |
| 11 | `default void onDeactivate()` | `{}` (no-op) | The tool moves to the background (no running tasks) — pause timers, persist state. |
| 12 | `default void onUnload()` | `{}` (no-op) | Plugin is unloaded/shutdown — release threads, close file/network handles, cancel tasks. Fires once. |
| 13 | `default boolean hasRunningTasks()` | `false` | You have background work that should keep running when the user navigates away. Returning `true` makes the host call `onBackground()` instead of `onDeactivate()`. |
| 14 | `default void onBackground()` | `{}` (no-op) | Moving to background **with** running tasks — adjust UI polling, etc. (replaces `onDeactivate`). |
| 15 | `default void onForeground()` | `{}` (no-op) | Returning from a backgrounded state — refresh layout/dimensions. Fires after `onActivate()` when previously backgrounded. |
| 16 | `default List<AiTool> aiTools()` | `List.of()` | Your plugin exposes AI-callable tools. Default empty for non-AI plugins. |

> **Lifecycle ordering.** `createView()` → cached. Normal navigation fires `onActivate()` ↔ `onDeactivate()`.
> If `hasRunningTasks()` is `true`, the host uses `onBackground()` / `onForeground()` instead. `onUnload()`
> fires exactly once on uninstall/shutdown. These are all **no-ops by default** — only implement what you need.

#### The supporting enums (verbatim values)

```
ToolCategory  = DEV | TEXT | IMAGE | NET | OTHER          (each has an id + i18nKey)
ToolType      = BUILTIN | PLUGIN                          (BUILTIN ships with the host)
IconStyle     = BLUE | PURPLE | TEAL | AMBER | RED | PINK | GRAY
                each maps to a CSS class (ic-blue … ic-gray) + accent Color
```

- `ToolCategory` — [`ToolCategory.java`](../../FengYu-Api/src/main/java/fan/summer/api/ToolCategory.java)
- `ToolType` — [`ToolType.java`](../../FengYu-Api/src/main/java/fan/summer/api/ToolType.java)
- `IconStyle` — [`IconStyle.java`](../../FengYu-Api/src/main/java/fan/summer/api/IconStyle.java) (icon-tile
  styling lives in `shell.css`; see [06 Icon System](06-icon-system.md)).

---

### CSS Class Naming Convention
<span id="css-naming"></span>

Every node in the FengYu scene graph carries zero or more style classes drawn from three namespaces.
Knowing which namespace a class belongs to tells you who owns it and whether it's safe for plugins to use.

#### The three namespaces

| Namespace | Prefix | Owner | Examples | Safe for plugins? |
|---|---|---|---|---|
| **Shared / component classes** | `.sk-` | `fengyu-common.css` (ships in the API JAR) | `.sk-field`, `.sk-surface`, `.sk-btn-primary`, `.sk-table`, `.sk-dialog`, `.sk-t1` | ✅ Yes — this is the plugin-facing vocabulary |
| **Shell-chrome classes** | *(unprefixed)* | `shell.css` (host application only) | `nav-item`, `tool-card`, `search-bar`, `statusbar`, `ic-blue` | ⚠️ Host-owned; plugins should not rely on these (they are for the sidebar/cards/status bar, not tool content) |
| **Status modifiers** | `is-*` / state | Component classes via `:hover`/`:focused` or explicit toggle | `.sk-notif-success`, `.sk-notif-warning`, `.sk-notif-danger` | ✅ Yes, for semantic status |

> **BEM-lite.** The `.sk-` namespace follows a flat, hyphen-separated "BEM-lite" scheme:
> `sk-` + `block` + optional `__element` or `-modifier`. In practice FengYu keeps it flat
> (`.sk-btn-primary`, `.sk-field-label`) rather than the full `block__elem--mod` notation. The rule of thumb:
> **one concept, one class, `sk-` prefix, hyphenated words.**

#### Color utility classes (apply these, don't inline color)

These bind a `-sk-*` token to a CSS property so it resolves and re-resolves on theme switch (see
[05 §3.2](05-theme-color-system.md#token--css-utility-class) for the full token→class table):

| Class | Sets | Token |
|---|---|---|
| `.sk-t1` | `-fx-text-fill` on `Labeled` | `-sk-text` |
| `.sk-t2` | `-fx-text-fill` on `Labeled` | `-sk-text-secondary` |
| `.sk-t3` | `-fx-text-fill` on `Labeled` | `-sk-text-disabled` |
| `.sk-surface` | `-fx-background-color` | `-sk-bg-elevated` |
| `.sk-outlined` | `-fx-border-color` | `-sk-border` |

#### Composite component classes (prefer these over hand-rolled CSS)

For richer controls, `fengyu-common.css` ships ready-made classes bundling several tokens + geometry.
**Use these instead of assembling your own** — full specs are in [03 Component Library](03-component-library.md):

| Class | What it gives you |
|---|---|
| `.sk-field` | Themed text input: `-sk-bg` bg, `-sk-border` border, `-sk-text` fill, 6px radius, padding; `:focused` → accent border |
| `.sk-table` | Themed table: elevated surface, borders; selected row → `-sk-bg-selected` + accent label |
| `.sk-tab-pane` | Tabs: hover `-sk-bg-hover`, selected `-sk-bg-selected` + 2px bottom accent |
| `.sk-dialog` | Dialog pane: elevated surface, border, 10px radius, drop shadow |
| `.sk-btn-primary` | Primary action button: `-sk-accent` bg, white text (the canonical accent usage) |
| `.sk-btn-secondary` | Secondary button: `-sk-bg-hover` bg, `-sk-border`, `-sk-text` fill |
| `.sk-notif-*` | Notification variants (`success`/`warning`/`danger`) using `-sk-accent-soft` + status tokens |

#### Status-modifier convention

Status colors (`-sk-success`, `-sk-warning`, `-sk-danger`) are **strictly semantic** — they mean
succeeded / caution / destructive. They are applied via component variants, never as raw decorative fills:

```java
// ✅ Status through the notification component class
notif.getStyleClass().addAll("sk-notif", "sk-notif-success");   // green
notif.getStyleClass().addAll("sk-notif", "sk-notif-warning");   // amber
notif.getStyleClass().addAll("sk-notif", "sk-notif-danger");    // red
```

Never repurpose a status color decoratively (e.g. don't use amber for a "featured" badge). See
[05 §P4](05-theme-color-system.md) and [06 Icon System](06-icon-system.md) for category colors when you need
a non-semantic tint.

#### The `.glass-*` → `.sk-*` migration (v3.2.0, breaking)

> **⚠️ Breaking change for external plugins.** In v3.2.0 the entire shared-component class family was
> renamed from `.glass-*` to `.sk-*`. **Any plugin JAR built against a pre-v3.2.0 API that references
> `.glass-*` classes will render unstyled after upgrading.**

The complete, authoritative migration table lives in the New UI redesign spec,
[**§7 "共性类重命名 `.glass-*` → `.sk-*`"**](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md).
A few representative rows (verified against that spec):

| Old (pre-v3.2.0) | New (v3.2.0+) |
|---|---|
| `.glass-dialog` | `.sk-dialog` |
| `.glass-btn-primary` / `.glass-btn-secondary` | `.sk-btn-primary` / `.sk-btn-secondary` |
| `.glass-field` / `.glass-field-label` | `.sk-field` / `.sk-field-label` |
| `.glass-table` | `.sk-table` |
| `.glass-notif-*` | `.sk-notif-*` |

The pattern is mechanical: **strip `glass`, add `sk`** — `.glass-<block>` → `.sk-<block>`. For the full
table (including `.glass-tab-pane`, `.glass-combo`, `.glass-checkbox`, etc.), see **New UI spec §7** — do not
reconstruct the mapping from memory; if unsure, consult that section directly.

---

### Layout-Container Selection Guide
<span id="layout-container-selection-guide"></span>

FengYu builds layouts from the standard JavaFX panes. Pick the container that matches the spatial
relationship you need; the wrong container is the #1 cause of broken resizing (see
[§4.5 The three layout pitfalls](#45-the-three-layout-pitfalls)).

| Container | Use for | Key API | Gotcha |
|---|---|---|---|
| **GridPane** | Forms, label/control rows, aligned columns | `setHgap`/`setVgap`, `ColumnConstraints` with `Priority`, `GridPane.setHalignment` | Drive column width via `ColumnConstraints` + `Priority`, not `setPrefWidth` |
| **VBox / HBox** | Linear vertical / horizontal stacks | `setSpacing`, `setPadding`, `VBox.setVgrow` / `HBox.setHgrow(node, Priority.ALWAYS)` | To fill remaining space: `setHgrow`/`setVgrow(Priority.ALWAYS)` **+** `node.setMaxWidth/Height(MAX_VALUE)`. Never `setPrefWidth(MAX_VALUE)` |
| **BorderPane** | Top/center/bottom (or left/right) regions — main window chrome | `setTop` / `setCenter` / `setBottom` | Center grows; the host already owns the app-level BorderPane — plugins rarely need one |
| **FlowPane** | Wrapping card grids, chip rows, icon trays | `setHgap`/`setVgap`, `setPrefWrapLength` | Children keep their pref size; good for uniform tiles |
| **StackPane** | Overlays, z-stacking, **page switching** (multiple children, one visible) | `setVisible` + `setManaged` toggling | When switching "pages", toggle **both** `visible` and `managed` (see [§4.5](#45-the-three-layout-pitfalls)) |
| **ScrollPane** | Scrollable content area | `.content-scroll` style class for thin scrollbar; `setFitToWidth` | Inside a StackPane, must set `setMaxWidth`/`setMaxHeight(MAX_VALUE)` to fill |

#### Decision flow

```
Do my children have a strict row/column grid (labels + inputs)?
│  YES → GridPane  (ColumnConstraints + Priority for the growing column)
│  NO
└─ Are they a single linear sequence?
   │  YES → VBox (vertical) or HBox (horizontal)
   │       └─ fill remaining space: setHgrow/Vgrow(ALWAYS) + setMaxWidth/Height(MAX_VALUE)
   │  NO
   └─ Do they overlay / swap as "pages"?
      │  YES → StackPane  (toggle visible + managed per page)
      │  NO
      └─ Does the content overflow and need scrolling?
         │  YES → ScrollPane  (add .content-scroll; setMaxWidth/Height(MAX_VALUE) in a StackPane)
         │  NO
         └─ Do tiles wrap to the next line?
            │  YES → FlowPane
            │  NO
            └─ Three regions (top/center/bottom)? → BorderPane
```

---

## 4. JavaFX Implementation Template

This section is the copy-paste core. It shows a **complete, compilable** `SwissKitJPlugin` skeleton using
the same `{{base-package}}` / `{{Name}}` / `{{slug}}` placeholders as
[`docs/plugins/ui.md`](../plugins/ui.md), then the icon helper, the theme helper, the i18n patterns, and the
three layout pitfalls — all the recurring building blocks.

### Plugin Skeleton
<span id="plugin-skeleton"></span>

> **Placeholder convention** (identical to `docs/plugins/ui.md`):
> - `{{base-package}}` — your plugin's base package, e.g. `com.example.mytool`
> - `{{Name}}` — PascalCase tool name, e.g. `CsvSorter`
> - `{{slug}}` — kebab-case slug, e.g. `csv-sorter`
>
> One class implements `SwissKitJPlugin` **directly** — no separate `*PluginUi` wrapper. All 11 built-in
> tools follow this single-class pattern (see [§6 AP1](#ap1--a-separate-pluginui-wrapper-class)).

```java
package {{base-package}}.ui;

import fan.summer.fengyu.api.ai.AiTool;
import fan.summer.fengyu.api.IconStyle;
import fan.summer.fengyu.api.MdiIconUtil;
import fan.summer.fengyu.api.SwissKitJPlugin;
import fan.summer.fengyu.api.ToolCategory;
import fan.summer.fengyu.api.ToolType;
import fan.summer.fengyu.api.i18n.I18n;
import fan.summer.fengyu.api.theme.Themes;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * {{Name}} — a FengYu plugin.
 *
 * <p>Implements {@link SwissKitJPlugin} directly: one class holds both the metadata
 * (id/name/category/icon) and the view ({@link #createView()}). The host calls
 * {@code createView()} once and caches the returned {@link Node}.</p>
 */
public class {{Name}}Plugin implements SwissKitJPlugin {

    // ── Cached view (built once in createView) ──────────────────────────
    private GridPane rootPanel;
    private final TextArea inputArea = new TextArea();
    private final Label statusLabel = new Label();

    // i18n key prefix; every user-visible string reads through I18n
    private static final String P = "builtin.{{slug}}.";

    // ── ① Required metadata (7 methods, no default) ─────────────────────

    @Override public String getId()          { return "builtin.{{slug}}"; }
    @Override public String getName()        { return I18n.get(P + "name"); }
    @Override public String getDescription() { return I18n.get(P + "desc"); }
    @Override public ToolCategory getCategory() { return ToolCategory.TEXT; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "code-json"; }  // NO "mdi-" prefix

    @Override
    public Node createView() {
        // Build once. The host caches this Node and reuses it on every activation.
        rootPanel = new GridPane();
        rootPanel.setHgap(10);
        rootPanel.setVgap(8);
        rootPanel.setPadding(new Insets(20));

        // Column 0 = label (fixed), Column 1 = control (grows)
        ColumnConstraints col0 = new ColumnConstraints();
        col0.setHgrow(Priority.NEVER);
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS);
        rootPanel.getColumnConstraints().addAll(col0, col1);

        Label inputLabel = new Label();
        inputLabel.getStyleClass().add("sk-t2");                 // color via class, NOT inline
        I18n.bind(inputLabel.textProperty(), P + "inputLabel");  // auto-updates on language switch

        inputArea.getStyleClass().addAll("sk-surface", "sk-outlined", "sk-t1");
        inputArea.setWrapText(true);
        VBox.setVgrow(inputArea, Priority.ALWAYS);               // fill vertical space

        Button runBtn = new Button();
        runBtn.getStyleClass().add("sk-btn-primary");            // accent button — see 05/03
        I18n.bind(runBtn.textProperty(), P + "run");
        runBtn.setOnAction(e -> doWork());

        // statusLabel: DYNAMIC text → use I18n.get at update time, not I18n.bind
        statusLabel.getStyleClass().add("sk-t3");

        rootPanel.add(inputLabel, 0, 0);
        rootPanel.add(inputArea, 1, 0);
        rootPanel.add(runBtn,    1, 1);
        rootPanel.add(statusLabel, 0, 2, 2, 1);                  // span 2 columns
        GridPane.setHalignment(runBtn, HPos.RIGHT);

        return rootPanel;
    }

    // ── ② Defaults you commonly override ───────────────────────────────

    @Override public IconStyle getIconStyle() { return IconStyle.PURPLE; }
    @Override public ToolType getType()       { return ToolType.BUILTIN; }  // builtins MUST override

    // ── ③ Lifecycle hooks (no-ops unless you need them) ────────────────

    @Override public void onActivate()   { /* resume timers / refresh */ }
    @Override public void onDeactivate() { /* pause timers */ }
    @Override public void onUnload()     { /* release threads, close handles */ }

    // ── ④ Background-task aware lifecycle (optional) ───────────────────

    @Override public boolean hasRunningTasks() { return false; }
    @Override public void onBackground() { /* keep tasks alive while hidden */ }
    @Override public void onForeground() { /* re-attach UI after background */ }

    // ── ⑤ AI tools (optional — default is List.of()) ───────────────────

    @Override public List<AiTool> aiTools() { return List.of(); }

    // ── example worker ─────────────────────────────────────────────────
    private void doWork() {
        // Dynamic message: resolve via I18n.get at the moment you set it.
        statusLabel.setText(I18n.get(P + "done"));
    }
}
```

#### Notes on the skeleton

- **Required vs. default is explicit.** The 7 required methods have no `default` in the interface — you must
  implement them or the class won't compile. The 9 defaults are shown for completeness; delete the ones you
  don't need.
- **`getId()` value.** Built-ins use `"builtin.<slug>"` (e.g. `"builtin.json-formatter"`).
  **⚠️ Note the inconsistency:** 3 of the 11 current built-ins (`EmailArchivePlugin`,
  `ExcelSplitterPlugin`, `BrowserAutomatePlugin`) instead use the legacy form
  `"fan.summer.fengyu.buildin.<slug>"`. There is no single canonical form in the codebase today; **new built-ins
  should prefer `"builtin.<slug>"`**, and external plugins should use a reverse-domain ID
  (`"com.example.<slug>"`). Do not assume there is only one form when grepping.
- **`getMdiIcon()` has no `mdi-` prefix.** Returning `"mdi-code-json"` will fail to resolve and fall back to
  the `star` glyph (see [§4.2](#42-icons--mdiiconutil)).
- **`getType()` for built-ins.** The interface default is `ToolType.PLUGIN`; a built-in tool **must**
  override to `ToolType.BUILTIN`. External plugins leave the default.
- **Register the implementation.** Create
  `src/main/resources/META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin` containing the fully-qualified class
  name (one line), then package a fat-JAR into the host's `plugins/` directory. Hot-reload is supported.

```
META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin
└─ {{base-package}}.ui.{{Name}}Plugin
```

---

### 4.2 Icons · `MdiIconUtil`

Icons are Material Design Icons rendered through a bundled webfont. The one entry point:

```java
import fan.summer.fengyu.api.MdiIconUtil;
import javafx.scene.text.Text;

// name WITHOUT the "mdi-" prefix; size in logical pixels
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);
```

| Concern | Rule |
|---|---|
| **Name format** | MDI name **without** `mdi-` prefix: `"file-excel"`, `"code-json"`, `"folder-open"`. The interface's `getMdiIcon()` follows the same rule. |
| **Unknown name** | Falls back to the `star` glyph — so a typo renders *something*, silently. Double-check the spelling against the [MDI catalog](https://pictogrammers.com/library/mdi/). |
| **Default fill** | `createIcon(name, size)` returns a `Text` filled white (`-fx-fill: white;`). Override with the 3-arg form or `setStyle("-fx-fill: ...;")` for category colors. |
| **Custom glyph** | `MdiIconUtil.putIcon(name, codepoint)` registers a runtime mapping for plugin-specific icons. |

Full icon/tile system (category colors, `IconStyle`→`ic-*` mapping, sizing) is in
[06 Icon System](06-icon-system.md).

Source: [`MdiIconUtil.java`](../../FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java).

---

### 4.3 Theming a standalone Stage · `Themes.applyTo`

Nodes returned from `createView()` are **embedded in the host's main scene**, which already has
`fengyu-common.css` loaded and the theme class stamped. **Embedded views need do nothing.**

But a plugin that opens its **own** `Stage`/`Scene` (a modal `Alert`, a standalone tool window) gets a fresh
scene with **no stylesheet and no theme class** — every `-sk-*` token would fail to resolve. Fix it with one
call:

```java
import fan.summer.fengyu.api.theme.Themes;
import javafx.scene.Scene;
import javafx.stage.Stage;

Stage dialog = new Stage();
Scene scene = new Scene(content, 480, 320);

Themes.applyTo(scene);   // ← THE one call plugins make. Loads CSS + stamps theme class + tracks for swaps.

dialog.setScene(scene);
dialog.show();
```

`Themes.applyTo(scene)` delegates to `ThemeService.registerScene(scene)` — so the window is *tracked*: a
later `ThemeService.set(...)` re-themes it too. **Plugins must call `Themes.applyTo`, not `ThemeService`
internals** — `Themes` is the supported, stable surface.

#### The `Alert`/`Dialog` pattern

A `Dialog` creates its own `Scene` lazily, so attach via the `sceneProperty` listener (matches
[`docs/plugins/ui.md`](../plugins/ui.md)):

```java
private void showAlert(Alert.AlertType type, String message) {
    Alert alert = new Alert(type);
    alert.setHeaderText(null);
    alert.setContentText(message);
    // Dialog creates its Scene later — apply the theme when it appears
    alert.getDialogPane().sceneProperty().addListener((obs, old, scene) -> {
        if (scene != null) Themes.applyTo(scene);
    });
    alert.showAndWait();
}
```

The full theme lifecycle (token resolution, `ThemeService.set`/`onChange`, WebView sync, persistence) is in
[05 Theme & Color System](05-theme-color-system.md).

| Do | Don't |
|---|---|
| `Themes.applyTo(scene)` for any `Scene` you create | Call `ThemeService.registerScene` directly from plugin code |
| Rely on automatic inheritance for `createView()` nodes | Manually add `fengyu-common.css` to `getStylesheets()` |
| Trust `Themes.applyTo` to be idempotent (no-op if already applied) | Re-add the stylesheet URL yourself |

Source: [`Themes.java`](../../FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java) ·
[`ThemeService.java`](../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java).

---

### 4.4 I18n patterns

Every user-visible string flows through [`fan.summer.fengyu.api.i18n.I18n`](../../FengYu-Api/src/main/java/fan/summer/api/i18n/I18n.java).
There are three patterns — pick by *when* the text is produced. (Mirrors
[`docs/plugins/ui.md`](../plugins/ui.md).)

| Pattern | When to use | Example |
|---|---|---|
| `I18n.bind(property, key)` | **Static** labels/buttons — text set once, must auto-update on language switch | `I18n.bind(label.textProperty(), "builtin.json.input")` |
| `I18n.get(key)` | **Dynamic** text — status messages, formatted output produced at runtime | `statusLabel.setText(I18n.get("builtin.json.idle"))` |
| `I18n.addListener(runnable)` | Custom refresh logic when the locale changes | `I18n.addListener(this::refreshStatus)` |

```java
// Static label — binds once, follows locale automatically
Label titleLabel = new Label();
I18n.bind(titleLabel.textProperty(), "builtin.{{slug}}.title");

// Dynamic status — resolve at the moment you set it
statusLabel.setText(I18n.get("builtin.{{slug}}.done"));

// Bulk refresh on locale change
I18n.addListener(() -> rebuildDynamicParts());
```

> **`bind` vs `get`:** `bind` wires a `Property` to a key so the UI updates *without* any code on language
> switch — use it for anything that never changes text except for translation. `get` is a one-shot lookup for
> text that is itself computed (a status that depends on state, a formatted number). If you find yourself
> re-calling `get` on the same static label, you probably wanted `bind`.

---

### 4.5 The three layout pitfalls

These three traps appear in [`docs/plugins/ui.md`](../plugins/ui.md) and account for nearly every
"why does my layout look broken" report. They are reproduced here because they are mandatory knowledge for
any code generator.

#### Pitfall 1 — ScrollPane not filling its parent (StackPane)

A `ScrollPane` inside a `StackPane` won't expand unless you explicitly raise its max size:

```java
ScrollPane sp = new ScrollPane(content);
sp.setMaxWidth(Double.MAX_VALUE);    // ← required, or it won't fill
sp.setMaxHeight(Double.MAX_VALUE);
```

Add the `.content-scroll` style class for the thin FengYu scrollbar styling.

#### Pitfall 2 — Filling an HBox/VBox's remaining space

To make a child fill the remaining horizontal (or vertical) space, you need **two** calls:

```java
node.setMaxWidth(Double.MAX_VALUE);              // allow it to grow beyond pref
HBox.setHgrow(node, Priority.ALWAYS);            // give it the extra space
// (for vertical, use VBox.setVgrow(node, Priority.ALWAYS) + setMaxHeight)
```

> **❌ Forbidden:** `node.setPrefWidth(Double.MAX_VALUE)` — this collapses the **entire** layout, not just
> the node. Always use `setMaxWidth` + `setHgrow`. This is [AP3](#ap3--setprefwidthmax_value-on-hboxvbox-children).

#### Pitfall 3 — StackPane page switching

When using a `StackPane` to swap between "pages", toggle **both** `visible` **and** `managed` for each
child. Toggling only `visible` leaves the hidden page occupying layout space:

```java
Node[] pages = { pageA, pageB, pageC };
int idx = 1;   // show pageB
for (int j = 0; j < pages.length; j++) {
    pages[j].setVisible(j == idx);    // hidden pages are not drawn
    pages[j].setManaged(j == idx);    // …and don't take layout space
}
```

---

## 5. AI Development Checklist

When generating a FengYu plugin you **MUST** satisfy all of the following. Each is a hard gate.

- [ ] **Implement `SwissKitJPlugin` directly — not a wrapper.** One class holds metadata + view. Do not create
      a separate `*PluginUi` class (all 11 built-ins implement the interface directly).
- [ ] **Implement all 7 required methods.** `getId`, `getName`, `getDescription`, `getCategory`,
      `getVersion`, `getMdiIcon`, `createView` have no defaults — the class won't compile without them.
- [ ] **Cache the `createView()` result.** Build the view graph once (inside `createView()` or lazily on
      first call); hold field references to controls; refresh *content* in lifecycle hooks, never rebuild
      structure on each activation.
- [ ] **Return the MDI name without the `mdi-` prefix.** `getMdiIcon()` → `"code-json"`, never
      `"mdi-code-json"`. Same for `MdiIconUtil.createIcon`.
- [ ] **Override `getType()` for built-ins.** Return `ToolType.BUILTIN`. The interface default is
      `ToolType.PLUGIN` — external plugins leave it.
- [ ] **Never load `fengyu-common.css` yourself.** Embedded views inherit it from the host scene;
      standalone windows get it via `Themes.applyTo(scene)`. Do not call `getStylesheets().add(...)`.
- [ ] **Never call `ThemeService` internals from plugin code.** Use `Themes.applyTo(scene)` (the supported
      surface). `ThemeService.registerScene`/`set` are host-level.
- [ ] **Never set inline hex colors or `-sk-*` tokens in `setStyle()`.** Inline styles don't resolve
      looked-up colors. Color → `.sk-*` class; size/padding/radius → inline. (See [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle).)
- [ ] **Prefer composite classes over hand-rolled CSS.** Use `.sk-field`, `.sk-btn-primary`, `.sk-table`,
      `.sk-dialog`, etc. rather than re-implementing themed controls. (See [03 Component Library](03-component-library.md).)
- [ ] **Use `.sk-*` class names, never `.glass-*`.** The `.glass-*` family was removed in v3.2.0; see the
      [migration note](#the-glass---sk--migration-v320-breaking) and New UI spec §7.
- [ ] **All user-visible strings go through I18n.** `I18n.bind` for static labels, `I18n.get` for dynamic
      text, `I18n.addListener` for custom refresh.
- [ ] **Fill space correctly.** `setHgrow`/`setVgrow(Priority.ALWAYS)` + `setMaxWidth/Height(MAX_VALUE)`;
      never `setPrefWidth(MAX_VALUE)`. Toggle both `visible` and `managed` on StackPane page switches. Set
      `setMaxWidth/Height(MAX_VALUE)` on a ScrollPane inside a StackPane.
- [ ] **Register the service file.** `META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin` with the FQCN;
      package a fat-JAR into `plugins/`.

---

## 6. Anti-patterns

Each shows the mistake, why it breaks, and the correction.

### AP1 — A separate `*PluginUi` wrapper class

```java
// ❌ WRONG — splits the contract across two classes
public class {{Name}}Plugin implements SwissKitJPlugin {
    private final {{Name}}PluginUi ui = new {{Name}}PluginUi();   // extra indirection
    public Node createView() { return ui.getView(); }
    // … metadata methods …
}
public class {{Name}}PluginUi { Node getView() { … } }
```

All 11 built-in tools implement `SwissKitJPlugin` **directly** in one class — metadata + view together. A
wrapper adds indirection, doubles the number of files, and breaks the "grep the class, see everything"
expectation. (The standalone `*PluginUi` example in `docs/plugins/ui.md` illustrates a view-builder pattern;
in FengYu's own codebase the view is built directly in `createView()`.)

```java
// ✅ CORRECT — one class implements the interface and builds the view
public class {{Name}}Plugin implements SwissKitJPlugin {
    private GridPane rootPanel;
    public Node createView() { /* build rootPanel once */ return rootPanel; }
    // … metadata methods …
}
```

### AP2 — Inline hex / token color in `setStyle()`

```java
// ❌ WRONG — hardcoded dark value; never updates on theme switch
label.setStyle("-fx-text-fill: #D0D0D0;");

// ❌ ALSO WRONG — looked-up colors do NOT resolve in inline setStyle
label.setStyle("-fx-text-fill: -sk-text;");
```

```java
// ✅ CORRECT — apply the utility class; the token resolves & re-resolves
label.getStyleClass().add("sk-t1");
```

This is the single most common theme bug; see [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle)
for the full explanation. Color → class; geometry → inline.

### AP3 — `setPrefWidth(MAX_VALUE)` on HBox/VBox children

```java
// ❌ WRONG — collapses the ENTIRE layout, not just this node
node.setPrefWidth(Double.MAX_VALUE);
```

```java
// ✅ CORRECT — grow priority + raised max size
node.setMaxWidth(Double.MAX_VALUE);
HBox.setHgrow(node, Priority.ALWAYS);
```

See [Pitfall 2](#pitfall-2--filling-an-hboxvboxs-remaining-space).

### AP4 — Loading common CSS manually

```java
// ⚠️ Prefer applyTo() — commonStylesheetUrl() only loads the stylesheet;
//    it does NOT stamp the .theme-dark / .theme-light class on the root, so tokens won't resolve
scene.getStylesheets().add(Themes.commonStylesheetUrl());
// or
Themes.loadCommonStylesheet(scene);   // package-private in spirit; not the plugin API
```

```java
// ✅ CORRECT — one supported call
Themes.applyTo(scene);   // loads CSS + stamps theme + tracks for swaps
```

Embedded `createView()` nodes need **nothing** — they inherit the host scene's stylesheet.

### AP5 — Calling `ThemeService` internals from plugin code

```java
// ❌ WRONG — ThemeService is the host-level engine; its API may change
ThemeService.registerScene(myScene);
ThemeService.set(ThemeService.Theme.LIGHT);
```

```java
// ✅ CORRECT — Themes is the stable, plugin-facing surface
Themes.applyTo(myScene);
// (theme switching is the host's job; plugins react via Themes.applyTo-tracked scenes)
```

`Themes.applyTo` delegates to `ThemeService.registerScene`, so you get tracking for free without coupling to
internals. See [05 §4.7](05-theme-color-system.md).

### AP6 — Returning an MDI name with the `mdi-` prefix

```java
// ❌ WRONG — "mdi-" prefix; won't resolve; silently falls back to the star glyph
@Override public String getMdiIcon() { return "mdi-code-json"; }
Text icon = MdiIconUtil.createIcon("mdi-file-excel", 24.0);
```

```java
// ✅ CORRECT — bare MDI name
@Override public String getMdiIcon() { return "code-json"; }
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);
```

### AP7 — Using `.glass-*` class names (removed in v3.2.0)

```java
// ❌ WRONG — .glass-* was removed in v3.2.0; node renders unstyled
field.getStyleClass().add("glass-field");
btn.getStyleClass().add("glass-btn-primary");
```

```java
// ✅ CORRECT — .sk-* namespace
field.getStyleClass().add("sk-field");
btn.getStyleClass().add("sk-btn-primary");
```

See the [`.glass-*` → `.sk-*` migration](#the-glass---sk--migration-v320-breaking) and New UI spec §7.

### AP8 — Rebuilding the view on every activation

```java
// ❌ WRONG — createView() should run once; rebuilding leaks nodes and resets state
@Override public void onActivate() {
    this.rootPanel = buildFromScratch();   // throws away the cached graph
}
```

```java
// ✅ CORRECT — build once in createView(); refresh only content in onActivate()
@Override public Node createView() { /* build once */ return rootPanel; }
@Override public void onActivate() { statusLabel.setText(refreshStatus()); }
```

The host caches the `Node` from `createView()`; treat it as the single source of structure.

---

## 7. References

### Source files (canonical)

| What | Path |
|---|---|
| Plugin contract (16 methods) | [`FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| Icon styles (`BLUE`…`GRAY`, CSS class, color) | [`FengYu-Api/src/main/java/fan/summer/api/IconStyle.java`](../../FengYu-Api/src/main/java/fan/summer/api/IconStyle.java) |
| Tool categories (`DEV`…`OTHER`) | [`FengYu-Api/src/main/java/fan/summer/api/ToolCategory.java`](../../FengYu-Api/src/main/java/fan/summer/api/ToolCategory.java) |
| Tool types (`BUILTIN`/`PLUGIN`) | [`FengYu-Api/src/main/java/fan/summer/api/ToolType.java`](../../FengYu-Api/src/main/java/fan/summer/api/ToolType.java) |
| MDI icon renderer (`createIcon`, `putIcon`) | [`FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java) |
| Plugin-facing theme helper (`applyTo`, `COMMON_CSS`) | [`FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java`](../../FengYu-Api/src/main/java/fan/summer/api/theme/Themes.java) |
| Theme engine (`registerScene`, `set`, `onChange`) | [`FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java`](../../FengYu-Api/src/main/java/fan/summer/api/theme/ThemeService.java) |
| Shared component + token CSS | [`FengYu-Api/src/main/resources/css/fengyu-common.css`](../../FengYu-Api/src/main/resources/css/fengyu-common.css) |
| Reference built-in (single-class pattern) | [`FengYu/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java`](../../FengYu/src/main/java/fan/summer/buildintool/dev/JsonFormatterPlugin.java) |

### Design baseline

| What | Path |
|---|---|
| Authoritative IDEA New UI redesign spec (incl. §7 `.glass-*`→`.sk-*` table) | [`docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`](../superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md) |
| UI design docs master spec | [`docs/superpowers/specs/2026-07-01-ui-design-docs-design.md`](../superpowers/specs/2026-07-01-ui-design-docs-design.md) |

### Companion & sibling docs

| Doc | Link |
|---|---|
| Companion plugin guide (layout pitfalls, StepWizard, i18n) | [`docs/plugins/ui.md`](../plugins/ui.md) |
| 01 — UI Design System (philosophy, typography, spacing) | [01-design-system.md](01-design-system.md) |
| 03 — Component Library (full `.sk-*` component specs) | [03-component-library.md](03-component-library.md) |
| 05 — Theme & Color System (tokens, contrast, theme lifecycle) | [05-theme-color-system.md](05-theme-color-system.md) |
| 06 — Icon System (`IconStyle`, category colors, sizing) | [06-icon-system.md](06-icon-system.md) |

---

*Method signatures in this document are reproduced verbatim from `SwissKitJPlugin.java` and verified with
`grep`. If the interface changes, this doc must be regenerated to match — the interface is the source of
truth, not this page.*
