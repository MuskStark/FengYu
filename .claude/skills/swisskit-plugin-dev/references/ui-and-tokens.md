# UI, Theme & Component Patterns

Plugins build their UI in JavaFX code (no FXML) and theme it via CSS looked-up colors. A
plugin embedded via `createView()` inherits the host's `swisskit-common.css` automatically —
you don't load it yourself. For a **standalone Stage** (rare — e.g. a popup window) call
`Themes.applyTo(scene)`.

This file is the working reference for coloring, laying out, and using the shared components.
It **inlines the must-know facts** so it's usable offline; for full design rationale see the
online spec.

## Authority: the main project design spec

**This file is a plugin-facing cheat-sheet. The authoritative source is the main project's
`docs/ui-design/` doc set — on any conflict, the spec wins.** A plugin is a guest in the
SwissKitJ shell and must follow these exactly. (Docs site: `https://muskstark.github.io/SwissKitJ/#/ui-design/...`)

| Topic | Spec doc (online) | Rule in one line |
|---|---|---|
| Color tokens & contrast | [05 Theme & Color System](https://muskstark.github.io/SwissKitJ/#/ui-design/05-theme-color-system) | `-sk-*` tokens / `.sk-*` classes only; no hex; check the contrast matrix |
| Components & CSS | [03 Component Library](https://muskstark.github.io/SwissKitJ/#/ui-design/03-component-library) | Use the `.sk-*` classes as specified; names below are the *only* valid ones |
| JavaFX coding + naming | [02 JavaFX Implementation](https://muskstark.github.io/SwissKitJ/#/ui-design/02-javafx-implementation) | Code-is-the-UI; single class; `sk-` prefix BEM-lite |
| Layout / typography / spacing / radius | [01 Design System](https://muskstark.github.io/SwissKitJ/#/ui-design/01-design-system) | Global font stack; 11/12/13/13.5/15 px sizes; 4 px grid; 6/8/10/999 px radii |
| Motion | [07 Animation Guidelines](https://muskstark.github.io/SwissKitJ/#/ui-design/07-animation-guidelines) | Token durations/easings; feedback ≤ 300 ms; never animate theme switch |
| Interaction | [04 Interaction Guidelines](https://muskstark.github.io/SwissKitJ/#/ui-design/04-interaction-guidelines) | Cache view; cross-fade page switch; confirm destructive; four-state feedback |
| Accessibility | [08 Accessibility Guide](https://muskstark.github.io/SwissKitJ/#/ui-design/08-accessibility-guide) | ≥ 4.5:1 contrast; not-by-color-alone; keyboard-reachable; Esc closes |

**Design philosophy (from [01](https://muskstark.github.io/SwissKitJ/#/ui-design/01-design-system)):**
functional-first; restrained IDEA New UI aesthetics (neutral-gray dominant, accent `#3574F0`
used sparingly); dark/light theme parity; plugins blend in as native. Selection = neutral
`-sk-bg-selected` + 3 px left accent strip — never a blue flood.

## The two CSS namespaces (don't confuse them)

| Namespace | Source | Plugin-safe? | Examples |
|---|---|---|---|
| **`.sk-*`** foundation | `swisskit-common.css` (ships in the API JAR) | ✅ Yes — use freely | `.sk-field`, `.sk-btn-primary`, `.sk-table`, `.sk-dialog`, `.sk-t1`, `.sk-surface` |
| **unprefixed** shell chrome | `shell.css` (host-only) | ❌ No — not on a plugin's scene | `.nav-item`, `.tool-card`, `.search-bar`, `.statusbar`, `.ic-blue` |

A plugin that reaches for `.nav-item` will compile but render unstyled (the class isn't loaded
on the plugin's scene). **Always prefer a `.sk-*` foundation class.**

> v3.2.0 renamed `.glass-*` → `.sk-*` (e.g. `.glass-dialog` → `.sk-dialog`, `.glass-field` →
> `.sk-field`, `.glass-btn-primary` → `.sk-btn-primary`). A pre-3.2.0 plugin JAR renders
> unstyled on a 3.2.0 host.

## Color tokens (`-sk-*`)

Never inline hex. Colors are **looked-up colors** resolved per-theme against `.theme-dark` /
`.theme-light` on the scene root. A `-sk-*` token string in `setStyle` *does* resolve; a hex
literal is frozen and breaks on theme switch.

**Full token table (inlined for offline use; authoritative copy in
[05 Theme & Color System](https://muskstark.github.io/SwissKitJ/#/ui-design/05-theme-color-system)):**

| Token | Dark | Light | Use |
|---|---|---|---|
| `-sk-bg` | `#1E1E1E` | `#FFFFFF` | window/canvas background |
| `-sk-bg-elevated` | `#2B2B2B` | `#F7F8FA` | cards, dialogs, fields |
| `-sk-bg-hover` | `#363636` | `#EBECEF` | hover fill |
| `-sk-bg-selected` | `#393B40` | `#DFE1E5` | selected row/item |
| `-sk-border` | `#3C3F41` | `#DADCE0` | borders, dividers |
| `-sk-border-strong` | `#555555` | `#C9CDD3` | stronger borders (hover) |
| `-sk-text` | `#D0D0D0` | `#1E1E1E` | body text |
| `-sk-text-secondary` | `#9AA0A6` | `#5A5D60` | captions, labels |
| `-sk-text-disabled` | `#6B6F73` | `#A0A4A8` | disabled/hint (**below AA — non-actionable content only**) |
| `-sk-accent` | `#3574F0` | `#3574F0` | primary actions, focus ring, selection strip |
| `-sk-accent-soft` | `rgba(53,116,240,.18)` | `rgba(53,116,240,.14)` | soft accent fill (selection tint) |
| `-sk-success` | `#5BB065` | `#3C914A` | success status (semantic only) |
| `-sk-warning` | `#F0A732` | `#C2751C` | warning status (semantic only) |
| `-sk-danger` | `#F75464` | `#E53935` | error/destructive status (semantic only) |

**Safe text/bg pairs (WCAG AA ≥ 4.5:1, both themes):** `-sk-text` on any `-sk-bg*`; 
`-sk-text-secondary` on `-sk-bg`/`-sk-bg-elevated`. **Never** use `-sk-text-disabled` for
content the user must read. Full matrix in
[05 §contrast](https://muskstark.github.io/SwissKitJ/#/ui-design/05-theme-color-system).

Status colors are **semantic** — never decorative. Status must be conveyed by **color + icon +
text** together, never color alone (see
[08 Accessibility](https://muskstark.github.io/SwissKitJ/#/ui-design/08-accessibility-guide)).

## `.sk-*` utility classes (the inline-style workaround)

Inline CSS can set sizes/padding/radius freely, but for **colors** prefer these classes:

| Class | Resolves to | Use |
|---|---|---|
| `.sk-t1` / `.sk-t2` / `.sk-t3` | `-sk-text` / `-sk-text-secondary` / `-sk-text-disabled` | text fill tiers |
| `.sk-fill-2` / `.sk-fill-3` | `-sk-text-secondary` / `-sk-text-disabled` | icon/graphic fill |
| `.sk-surface` | `-sk-bg-elevated` | card/elevated background |
| `.sk-surface-soft` | `-sk-bg-hover` | hover/soft background |
| `.sk-outlined` / `.sk-outlined-strong` | `-sk-border` / `-sk-border-strong` | borders |
| `.sk-field` (+ `.sk-field-label`) | input field | text input |
| `.sk-btn-primary` / `.sk-btn-secondary` | accent-filled / bordered button | actions (there is **no** `.sk-btn` base) |
| `.sk-combo` / `.sk-checkbox` / `.sk-table` / `.sk-tab-pane` / `.sk-dialog` | matching control | use as-is |

Example:
```java
Label title = new Label(I18n.get(P + "name"));
title.getStyleClass().add("sk-t1");           // → -sk-text

Button go = new Button("Go");
go.getStyleClass().add("sk-btn-primary");     // accent fill, white text

TextField input = new TextField();
input.getStyleClass().add("sk-field");
```

## Icons — `MdiIconUtil` + `IconStyle`

```java
import fan.summer.api.MdiIconUtil;
import fan.summer.api.IconStyle;

// A standard 24px icon (returns a javafx.scene.text.Text using the MDI webfont)
Text icon = MdiIconUtil.createIcon("file-excel", 24);

// Colored from Java via IconStyle (NOT from CSS — the .ic-* rules are empty)
Text icon = MdiIconUtil.createIcon("file-excel", 24);
icon.setFill(IconStyle.TEAL.getColor());   // or apply a DropShadow glow
```

- `MdiIconUtil.createIcon(String name, double size)` → `Text`
- `MdiIconUtil.createIcon(String name, double size, String extraStyle)` → `Text`
- `getMdiIcon()` returns the **bare** name (no `mdi-` prefix); verify a name exists in
  [`mdi-codemap.properties`](https://github.com/MuskStark/SwissKitJ/blob/main/SwissKitJ-Api/src/main/resources/fonts/mdi-codemap.properties) before using it.
- Icon size scale: 16 (inline/status), 18 (nav), 20 (small), 24 (standard/card), 32 (large).

## i18n — `I18n`

Always register the bundle in `createView()` first, then use:
```java
I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());

// Static label — auto-refreshes on locale change:
Label name = new Label();
I18n.bind(name.textProperty(), P + "name");

// Dynamic / one-shot text:
String prompt = I18n.get(P + "prompt");
String formatted = I18n.get(P + "items", count);   // MessageFormat
```

- Keys must be prefixed `plugin.<slug>.` (e.g. `plugin.csv-sorter.name`).
- `messages.properties` (default/English) and `messages_zh.properties` must share identical
  keys.
- `I18n.bind(property, key)` for static JavaFX properties (auto-refresh, weakly held).
- `I18n.get(key)` / `I18n.get(key, args...)` for dynamic text.

## Reusable components

### `GlassNotification` — toast/notify/confirm

The themed replacement for JavaFX `Alert`. All overloads take `Window` **or** `Node` (resolved
via its scene's window); `null` is safe.

```java
import fan.summer.api.component.GlassNotification;

// Non-modal toast, auto-dismisses (~2.5s)
GlassNotification.toast(view, GlassNotification.Type.SUCCESS, "Saved");

// Modal OK notification
GlassNotification.notify(view, GlassNotification.Type.WARNING, "Check your input");

// Modal OK/Cancel confirmation — blocks, returns true on OK
if (GlassNotification.confirm(view, "Delete?", "This cannot be undone.")) {
    delete();
}
```
`Type` ∈ `INFO` / `SUCCESS` / `WARNING` / `ERROR` — each maps to `.sk-notif-info` /
`.sk-notif-success` / `.sk-notif-warning` / `.sk-notif-error` (icon + color + message — never
status by color alone).

### `StepWizard` — multi-step flow

```java
import fan.summer.api.component.StepWizard;

StepWizard wizard = new StepWizard();
wizard.addStep("Input",  inputPane,  () -> !inputField.getText().isBlank());
wizard.addStep("Confirm", confirmPane, () -> true);
wizard.addStep("Done",    resultPane,  () -> true);
wizard.build();                  // idempotent; call after all addStep
wizard.setOnStepChanged((from, to, total) -> { ... });
// canProceed == false → Next button shakes and won't advance
```

### `UiUtils` — shared control factory

```java
import fan.summer.api.component.UiUtils;

Button primary = UiUtils.glassBtn("Run", true);   // accent blue
Button ghost   = UiUtils.glassBtn("Cancel", false);// bordered
Region spacer  = UiUtils.hSpacer();               // HBox.hgrow=ALWAYS
Label sub      = UiUtils.subLabel("Output");      // small muted field label
```

## Standalone Stage theming (rare)

If your plugin opens its **own** window (not the embedded `createView()`), apply the theme
manually:
```java
import fan.summer.api.theme.Themes;

Stage popup = new Stage();
Scene scene = new Scene(content);
Themes.applyTo(scene);     // loads swisskit-common.css + stamps theme-dark/theme-light
popup.setScene(scene);
popup.show();
```
For a node already in the host scene, you don't need this. To react to theme changes for
custom rendering (WebView/canvas), register `ThemeService.onChange(theme -> ...)`.

## The three layout pitfalls

These bite everyone. Memorize them.

1. **ScrollPane inside a StackPane/Pane won't fill.** You must release its max size:
   ```java
   ScrollPane sp = new ScrollPane(content);
   sp.setMaxWidth(Double.MAX_VALUE);
   sp.setMaxHeight(Double.MAX_VALUE);
   sp.getStyleClass().add("content-scroll");   // thin scrollbar variant
   ```

2. **Fill remaining HBox/VBox space — two calls, not one.**
   ```java
   // CORRECT:
   field.setMaxWidth(Double.MAX_VALUE);
   HBox.setHgrow(field, Priority.ALWAYS);

   // WRONG — collapses the whole layout:
   field.setPrefWidth(Double.MAX_VALUE);   // never do this
   ```

3. **StackPane page switching — toggle BOTH flags.** Toggling only `visible` leaves hidden
   pages occupying layout space:
   ```java
   for (int i = 0; i < pages.size(); i++) {
       pages.get(i).setVisible(i == current);
       pages.get(i).setManaged(i == current);
   }
   ```

## Layout container quick guide

| Container | Use for | Key API |
|---|---|---|
| `GridPane` | Forms (label+field rows) | `add(node, col, row)`, `getColumnConstraints()` |
| `VBox` / `HBox` | Linear stacks | `setSpacing`, `setHgrow`/`setVgrow` + `setMaxWidth(MAX_VALUE)` |
| `BorderPane` | top/center/bottom regions | `setCenter`, `setTop`, `setBottom` |
| `FlowPane` | Wrapping card/chip grids | `setHgap`, `setVgap`, `setPrefWrapLength` |
| `StackPane` | Overlay / page switching | toggle `visible` **and** `managed` |
| `ScrollPane` | Scrollable content | `setMaxWidth/Height(MAX_VALUE)` + `.content-scroll` |
