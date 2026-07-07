# 03 · Component Library

> **Role:** The complete, authoritative spec for every reusable visual component in SwissKitJ.
> Each component is specified with enough detail that an AI (or a human who has never seen the
> codebase) can generate a pixel-and-behavior-faithful implementation from scratch. When a value
> is a theme token, this doc names the token and links out — it does **not** restate hex values.
> Read [05 Theme & Color System](05-theme-color-system.md) for what each token resolves to.

| | |
|---|---|
| **Doc type** | Per-component reference spec (Foundation + Shell) |
| **Audience** | UI designers, plugin authors, AI code generators |
| **CSS source (foundation)** | [`SwissKitJ-Api/src/main/resources/css/swisskit-common.css`](../../SwissKitJ-Api/src/main/resources/css/swisskit-common.css) — shared by host + every plugin |
| **CSS source (shell)** | [`SwissKit/src/main/resources/css/shell.css`](../../SwissKit/src/main/resources/css/shell.css) — host app shell only |
| **Related** | [02 JavaFX Implementation](02-javafx-implementation.md) · [05 Theme & Color System](05-theme-color-system.md) · [06 Icon System](06-icon-system.md) |

---

## How to read this document

### Two component families

SwissKitJ has **two layers of components**, each with its own stylesheet. Knowing which layer a
class lives in is the single most important fact about it:

| Family | Stylesheet | Who can use it? | Class prefix | Count |
|---|---|---|---|---|
| **Foundation** | `swisskit-common.css` (API module) | Host **and** every third-party plugin | `.sk-*` | 12 components |
| **Shell** | `shell.css` (host only) | Host application only | unprefixed (`nav-item`, `tool-card`, …) | 5 components |

Foundation components are loaded onto any scene by `Themes.applyTo(scene)`, so they work inside
your plugin's embedded view **and** in a standalone Stage. Shell components are app-shell chrome —
they exist only in the main window and you should not build a plugin that depends on them.

> **Don't confuse the two prefixes.** A plugin that reaches for `.nav-item` will compile but render
> unstyled (the class isn't on the scene). Always prefer a `.sk-*` foundation class when one exists.

### The seven sub-sections (every component)

Every component below follows this identical structure so a code generator can parse them uniformly:

1. **Overview & anatomy** — what it is, when to use it, a labeled ASCII diagram.
2. **CSS classes** — the exact class names + the source file line (verbatim from source).
3. **Tokens used** — every `-sk-*` token the component depends on, with a one-line role.
4. **States & modifiers** — hover/focus/selected/disabled rules and the `.active`/pseudo-class matrix.
5. **Layout & sizing** — container choice, spacing, padding, pref/min/max dimensions.
6. **JavaFX template** — minimal copyable instantiation (class on node, no hex).
7. **References** — CSS file + (shell only) Java file + token link + icon link.

### Conventions used in the spec tables

- **Token column** — a name like `-sk-accent` means "the looked-up color `-sk-accent`". Its actual
  hex differs per theme; look it up once in the [Token Reference Table](05-theme-color-system.md#token-reference-table).
- **"Inline-safe"** — a utility class that only sets *color* properties (`-fx-text-fill`, `-fx-fill`,
  `-fx-background-color`). These are designed to be combined with a node that *also* carries inline
  geometry (font-size, padding) via `setStyle(...)`. See P5 in [02 JavaFX Implementation](02-javafx-implementation.md#css-naming).
- **Diagrams** are ASCII art + tables + fenced code only — no Mermaid.

### Quick-jump index

**Foundation components** (`swisskit-common.css`)
- [F1 · Text utilities](#f1--text-utilities) — `.sk-t1` `.sk-t2` `.sk-t3`
- [F2 · Surface utilities](#f2--surface-utilities) — `.sk-surface` `.sk-surface-soft` `.sk-outlined` `.sk-outlined-strong`
- [F3 · Status-text utilities](#f3--status-text-utilities) — `.sk-accent-text` `.sk-success-text` `.sk-warning-text` `.sk-danger-text`
- [F4 · Shape-fill utilities](#f4--shape-fill-utilities) — `.sk-fill-2` `.sk-fill-3`
- [F5 · Scrim](#f5--scrim) — `.sk-scrim`
- [F6 · Field](#f6--field) — `.sk-field` `.sk-field-label`
- [F7 · Button](#f7--button) — `.sk-btn-primary` `.sk-btn-secondary`
- [F8 · Combo box](#f8--combo-box) — `.sk-combo`
- [F9 · Checkbox](#f9--checkbox) — `.sk-checkbox`
- [F10 · Table](#f10--table) — `.sk-table`
- [F11 · Tab pane](#f11--tab-pane) — `.sk-tab-pane`
- [F12 · Dialog](#f12--dialog) — `.sk-dialog`
- [F13 · Notification](#f13--notification) — `.sk-notif-*`
- [F14 · Step wizard indicator](#f14--step-wizard-indicator) — `.sk-step-*`

**Shell components** (`shell.css`)
- [S1 · Navigation item](#s1--navigation-item) — `.nav-item`
- [S2 · Search bar](#s2--search-bar) — `.search-bar`
- [S3 · Tool card](#s3--tool-card) — `.tool-card`
- [S4 · Detail panel](#s4--detail-panel) — `.detail-panel`
- [S5 · Status bar](#s5--status-bar) — `.statusbar`

---

# Part A — Foundation Components

> Source: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css`. Available everywhere
> `Themes.applyTo(scene)` has been called.

---

## F1 · Text utilities

### 1. Overview & anatomy

Three text-fill utilities for any `Label`/`Text`/`Labeled` node. They exist because inline
`setStyle("-fx-text-fill: ...")` cannot reference a `-sk-*` token — moving just the color into a
class lets the rest of the inline style (font-size, padding) stay inline and still re-theme
correctly. **This is the canonical pattern for any text color** (see P5).

```
Hierarchy of text importance

   .sk-t1  ████████  Primary text     — titles, values, body copy
   .sk-t2  ██████    Secondary text   — labels, captions, metadata
   .sk-t3  ████      Disabled/hint    — placeholders, hints, empty states
```

Use them **instead of** hard-coded rgba in `setStyle`. If you find yourself writing
`-fx-text-fill: rgba(...)`, stop and use the matching `.sk-t*` class.

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-t1` | `swisskit-common.css:126` | Primary text fill |
| `.sk-t2` | `swisskit-common.css:127` | Secondary text/label fill |
| `.sk-t3` | `swisskit-common.css:128` | Disabled/hint/weak text fill |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-text` | `.sk-t1` fill — the dominant foreground color |
| `-sk-text-secondary` | `.sk-t2` fill — de-emphasized foreground |
| `-sk-text-disabled` | `.sk-t3` fill — weakest readable foreground |

Each token resolves per theme in the [Token Reference Table](05-theme-color-system.md#token-reference-table).

### 4. States & modifiers

None — these are static color classes with no pseudo-class or modifier variants.

### 5. Layout & sizing

These classes set **only** `-fx-text-fill`. Font size, weight, and family are *not* included — set
those inline (e.g. `setStyle("-fx-font-size: 13px;")`) or on the parent. A node may carry one of
these classes **and** inline geometry at the same time; inline overrides only the properties it
names, the class owns the color.

### 6. JavaFX template

```java
// Primary value
Label value = new Label("JSON");
value.getStyleClass().add("sk-t1");
value.setStyle("-fx-font-size: 16px; -fx-font-weight: 500;");

// Secondary caption — note: only font-family is inline, color comes from the class
Label caption = new Label("v1.2.0");
caption.getStyleClass().add("sk-t2");
caption.setStyle("-fx-font-family: 'SF Mono','Consolas',monospace;");

// Disabled hint / empty-state message
Label empty = new Label("No results");
empty.getStyleClass().add("sk-t3");
empty.setStyle("-fx-font-size: 13px;");
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (utility-classes section)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)
- Naming convention: [02 JavaFX Implementation — `#css-naming`](02-javafx-implementation.md#css-naming)

---

## F2 · Surface utilities

### 1. Overview & anatomy

Background-fill and border-color utilities for panels, tiles, and grouped regions. Like the text
utilities they are **color-only** — pair them with inline `-fx-border-width`/`-fx-background-radius`
to define the shape.

```
Two elevation levels + two border weights

  .sk-surface         elevated bg ────  card / dialog body
  .sk-surface-soft    hover bg   ────  inset panel, table header
  .sk-outlined        default border   grouped control frame
  .sk-outlined-strong strong border    emphasis frame / hover lift
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-surface` | `swisskit-common.css:131` | Elevated background fill |
| `.sk-surface-soft` | `swisskit-common.css:132` | Soft (hover-tier) background fill |
| `.sk-outlined` | `swisskit-common.css:133` | Default border color (pair with inline width/radius) |
| `.sk-outlined-strong` | `swisskit-common.css:134` | Strong border color |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | `.sk-surface` — one tier above the base bg |
| `-sk-bg-hover` | `.sk-surface-soft` — the hover/interactive tier |
| `-sk-border` | `.sk-outlined` — default hairline border |
| `-sk-border-strong` | `.sk-outlined-strong` — emphasized border |

### 4. States & modifiers

None. To express hover/selection visually, swap the class in a Java handler or compose with a
component that already encodes those states (e.g. `.sk-table .table-row-cell:hover`).

### 5. Layout & sizing

`.sk-outlined` / `.sk-outlined-strong` set **only** `-fx-border-color`. You **must** also supply
`-fx-border-width` (and usually `-fx-border-radius` + matching `-fx-background-radius`) inline or
via another rule, otherwise no border is drawn. `.sk-surface*` set only `-fx-background-color`.

### 6. JavaFX template

```java
// A grouped panel with an outlined frame
VBox panel = new VBox();
panel.getStyleClass().addAll("sk-surface", "sk-outlined");
panel.setStyle("-fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 12;");
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (utility-classes section)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)
- Naming convention: [02 JavaFX Implementation — `#css-naming`](02-javafx-implementation.md#css-naming)

---

## F3 · Status-text utilities

### 1. Overview & anatomy

Four semantic text-color classes for status/affordance copy (links, success/error messages). Each
maps 1:1 to a status token. **Never** use these decoratively — a green label means "success", not
"I like green" (see P4, [05 Theme & Color System](05-theme-color-system.md)).

```
.sk-accent-text   link / emphasis   →  -sk-accent
.sk-success-text  positive state    →  -sk-success
.sk-warning-text  caution state     →  -sk-warning
.sk-danger-text   error state       →  -sk-danger
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-accent-text` | `swisskit-common.css:135` | Accent text fill — links, inline emphasis |
| `.sk-success-text` | `swisskit-common.css:136` | Success status text |
| `.sk-warning-text` | `swisskit-common.css:137` | Warning status text |
| `.sk-danger-text` | `swisskit-common.css:138` | Error/danger status text |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-accent` | `.sk-accent-text` |
| `-sk-success` | `.sk-success-text` |
| `-sk-warning` | `.sk-warning-text` |
| `-sk-danger` | `.sk-danger-text` |

### 4. States & modifiers

None. The class is the state — to show "this used to be an error and is now ok", swap
`.sk-danger-text` → `.sk-success-text`.

### 5. Layout & sizing

Color-only. Pair with inline font properties as needed.

### 6. JavaFX template

```java
// A hyperlink-styled label
Label link = new Label("Open docs");
link.getStyleClass().add("sk-accent-text");
link.setStyle("-fx-font-size: 13px; -fx-border-color: transparent; -fx-padding: 0;");

// A validation error message
Label err = new Label("Invalid JSON");
err.getStyleClass().add("sk-danger-text");
err.setStyle("-fx-font-size: 12px;");
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (utility-classes section)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F4 · Shape-fill utilities

### 1. Overview & anatomy

Two `-fx-fill` utilities for `Text`/`Shape` nodes (SVG-like glyphs, icon geometry). Distinct from
the `.sk-t*` family which sets `-fx-text-fill` — JavaFX uses different properties for text fill vs.
shape fill, so these are needed for any icon drawn as a `Shape`.

```
.sk-fill-2  secondary fill  →  -sk-text-secondary
.sk-fill-3  disabled fill   →  -sk-text-disabled
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-fill-2` | `swisskit-common.css:129` | Secondary `-fx-fill` (Text/Shape) |
| `.sk-fill-3` | `swisskit-common.css:130` | Disabled/weak `-fx-fill` (Text/Shape) |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-text-secondary` | `.sk-fill-2` |
| `-sk-text-disabled` | `.sk-fill-3` |

### 4. States & modifiers

None.

### 5. Layout & sizing

Color-only. Note the host sidebar/nav icons are still set via inline `setStyle("-fx-fill: #…")` in
`Sidebar.NavItem` (a known legacy TODO) — for **new** plugin icons prefer `.sk-fill-2`/`.sk-fill-3`
or the `IconStyle` accent colors documented in [06 Icon System](06-icon-system.md).

### 6. JavaFX template

```java
Text glyph = MdiIconUtil.createIcon("magnify", 16);
glyph.getStyleClass().add("sk-fill-3");   // de-emphasized search glyph
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (utility-classes section)
- Icons: [06 Icon System](06-icon-system.md)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F5 · Scrim

### 1. Overview & anatomy

A full-bleed translucent overlay used behind a modal `Stage` to dim the rest of the UI and focus
attention on the dialog. Applied to a transparent `Stage`'s root region. The dim level is
theme-aware (lighter in light theme so it doesn't muddy a white surface).

```
┌──────────────────────────────────────┐
│           .sk-scrim (dim layer)       │
│    ┌────────────────────────────┐     │
│    │   .sk-dialog (modal body)  │     │
│    │                            │     │
│    └────────────────────────────┘     │
└──────────────────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-scrim` | `swisskit-common.css:139` | Modal backdrop fill |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-scrim` | Backdrop color — `rgba(0,0,0,0.50)` dark / `rgba(15,23,42,0.32)` light |

> **Use the token, never the literal.** Older code wrote `rgba(0,0,0,0.35)` inline; that literal is
> gone. The `.sk-scrim` class is the only supported way to dim.

### 4. States & modifiers

None.

### 5. Layout & sizing

Color-only (`-fx-background-color`). The scrim region should be sized to fill its `Stage`; the host
achieves this by adding the class to a full-size root and using `StageStyle.TRANSPARENT`.

### 6. JavaFX template

```java
StackPane root = new StackPane();
root.getStyleClass().add("sk-scrim");      // dim behind the dialog
// dialog content added as a child, centered
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (utility-classes section)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F6 · Field

### 1. Overview & anatomy

The single text-input component. A bordered, theme-aware input box that elevates on focus. The
class name is `.sk-field` (not `.sk-text-field`). A paired label class provides the small bold
caption above the field.

```
  .sk-field-label  (caption, 11px bold secondary)
  ┌──────────────────────────────────┐
  │ type here…                        │  ← .sk-field
  └──────────────────────────────────┘
   unfocused: border -sk-border, bg -sk-bg
   focused:   border -sk-accent, bg -sk-bg-elevated
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-field` | `swisskit-common.css:152` | Text input (TextField/TextArea) |
| `.sk-field-label` | `swisskit-common.css:166` | Caption label above a field |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg` | Unfocused background |
| `-sk-bg-elevated` | Focused background (lifts the field) |
| `-sk-border` | Unfocused border |
| `-sk-accent` | Focused border (the focus ring color) |
| `-sk-text` | Input text color |
| `-sk-text-secondary` | Label caption color |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Default | `.sk-field` | `-sk-border` border, `-sk-bg` background, 6px radius, `8 12` padding |
| Focused | `.sk-field:focused` (line 162) | border → `-sk-accent`, bg → `-sk-bg-elevated` |

There is **no** `.sk-text-field` — that name does not exist in source.

### 5. Layout & sizing

- Container: a `javafx.scene.control.TextField` (or `TextArea`).
- Geometry (from CSS): `1px` border, `6px` corner radius, padding `8 12 8 12`, font-size `13px`.
- The label is `11px`, bold, secondary-colored.

### 6. JavaFX template

```java
Label caption = new Label("OUTPUT PATH");
caption.getStyleClass().add("sk-field-label");

TextField input = new TextField();
input.getStyleClass().add("sk-field");
input.setPromptText("/path/to/file");
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (input-field section, lines 151–166)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F7 · Button

### 1. Overview & anatomy

Two button variants — and **only two**. There is no base `.sk-btn`; both variants are standalone
classes. Primary is the accent-filled action button; secondary is the bordered ghost button.

```
  .sk-btn-primary        .sk-btn-secondary
  ┌───────────────┐      ┌───────────────┐
  │   Save         │      │   Cancel       │
  └───────────────┘      └───────────────┘
   accent fill, white     hover-tier bg, border
   text; the "go" action  text; the safe/alt action
```

**Rule of one:** a screen should have at most one primary button (the default/confirm action).
Everything else is secondary.

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-btn-primary` | `swisskit-common.css:282` | Accent-filled action button |
| `.sk-btn-secondary` | `swisskit-common.css:296` | Bordered ghost button |

There is no `.sk-btn` base class — do not invent one.

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-accent` | Primary background; hover/press use `derive(-sk-accent, -8%/-16%)` |
| `-sk-bg-hover` | Secondary default background |
| `-sk-bg-selected` | Secondary hover background |
| `-sk-border` / `-sk-border-strong` | Secondary default / hover border |
| `-sk-text` | Secondary text color (primary uses literal `white`) |

### 4. States & modifiers

| Variant | Default | Hover | Pressed |
|---|---|---|---|
| `.sk-btn-primary` | bg `-sk-accent`, text white | bg `derive(-sk-accent,-8%)` (line 292) | bg `derive(-sk-accent,-16%)` (line 293) |
| `.sk-btn-secondary` | bg `-sk-bg-hover`, border `-sk-border` | bg `-sk-bg-selected`, border `-sk-border-strong` (line 307) | (no separate pressed rule) |

Both set `-fx-cursor: hand`. For a disabled button, use `button.setDisable(true)` (JavaFX dims it).

### 5. Layout & sizing

- Geometry (from CSS): `6px` corner radius, padding `8 18 8 18`, font-size `13px`, weight `500`
  (primary). Container: `javafx.scene.control.Button`.

> **Legacy helper note.** `UiUtils.glassBtn(text, primary)` exists in the API as a convenience, but
> it builds buttons with **inline hex** (`#3574F0`) rather than the `.sk-btn-*` classes. For
> theme-correct, re-skinnable buttons, prefer `.sk-btn-primary`/`.sk-btn-secondary` directly.

### 6. JavaFX template

```java
Button ok = new Button("Save");
ok.getStyleClass().add("sk-btn-primary");
ok.setDefaultButton(true);

Button cancel = new Button("Cancel");
cancel.getStyleClass().add("sk-btn-secondary");
cancel.setCancelButton(true);
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (primary/secondary button sections, lines 281–307)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F8 · Combo box

### 1. Overview & anatomy

A themed `ComboBox`/`ChoiceBox`. The closed control mirrors the field look (border + bg), and the
dropdown popup is restyled as an elevated rounded menu with hover and selected rows.

```
  .sk-combo (closed)              popup (.combo-box-popup .list-view)
  ┌──────────────────┐ ▾          ┌──────────────────────┐
  │ Selected item     │            │ ▸ hovered row        │
  └──────────────────┘            │   selected row       │
                                   └──────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-combo` | `swisskit-common.css:196` | Closed combo box |
| `.sk-combo .list-cell` | `swisskit-common.css:205` | Selected cell text |
| `.sk-combo .arrow-button` | `swisskit-common.css:206` | Dropdown arrow button (transparent) |
| `.sk-combo .arrow` | `swisskit-common.css:207` | Dropdown arrow glyph |
| `.combo-box-popup .list-view` | `swisskit-common.css:209` | Popup list (elevated, rounded, shadowed) |
| `.combo-box-popup .list-view .list-cell` | `swisskit-common.css:218` | Popup row |
| `.combo-box-popup .list-view .list-cell:filled:hover` | `swisskit-common.css:224` | Hovered row |
| `.combo-box-popup .list-view .list-cell:filled:selected` | `swisskit-common.css:229` | Selected row |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg` / `-sk-bg-elevated` | Closed control bg / popup bg |
| `-sk-border` | Control + popup border |
| `-sk-bg-hover` / `-sk-bg-selected` | Popup row hover / selected bg |
| `-sk-text` / `-sk-text-secondary` | Row text / arrow glyph |
| `-sk-accent` | Selected row text color |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Closed | `.sk-combo` | `-sk-bg` bg, `-sk-border` border, 6px radius |
| Popup hover | `.list-cell:filled:hover` | bg → `-sk-bg-hover` |
| Popup selected | `.list-cell:filled:selected` | bg → `-sk-bg-selected`, text → `-sk-accent` |

### 5. Layout & sizing

Closed control: `1px` border, `6px` radius, font-size `13px`. Popup: `8px` radius, `1px` border,
`dropshadow(gaussian, rgba(0,0,0,0.40), 16, 0, 0, 6)`. Container: `javafx.scene.control.ComboBox`.

### 6. JavaFX template

```java
ComboBox<String> box = new ComboBox<>();
box.getStyleClass().add("sk-combo");
box.getItems().addAll("JSON", "YAML", "XML");
box.getSelectionModel().select(0);
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (combo-box section, lines 195–233)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F9 · Checkbox

### 1. Overview & anatomy

A themed `CheckBox`. The unchecked box uses the field look (border + base bg); when selected the box
fills with the accent color and the checkmark turns white.

```
  ☐  .sk-checkbox (unchecked)     ☑  .sk-checkbox:selected
      box: -sk-bg / -sk-border        box: -sk-accent, mark: white
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-checkbox` | `swisskit-common.css:270` | Checkbox label + text |
| `.sk-checkbox .box` | `swisskit-common.css:271` | Unchecked box |
| `.sk-checkbox:selected .box` | `swisskit-common.css:278` | Selected box fill |
| `.sk-checkbox:selected .mark` | `swisskit-common.css:279` | Checkmark mark |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-text` | Label text color |
| `-sk-bg` | Unchecked box background |
| `-sk-border` | Unchecked box border |
| `-sk-accent` | Selected box fill + border |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Unchecked | `.sk-checkbox .box` | `-sk-bg` bg, `-sk-border` border, 4px radius |
| Selected | `.sk-checkbox:selected .box` | bg + border → `-sk-accent` |
| Selected mark | `.sk-checkbox:selected .mark` | mark fill → white |

### 5. Layout & sizing

Box: `1px` border, `4px` corner radius. Label text: `13px`. Container: `javafx.scene.control.CheckBox`.

### 6. JavaFX template

```java
CheckBox cb = new CheckBox("Pretty-print output");
cb.getStyleClass().add("sk-checkbox");
cb.setSelected(true);
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (checkbox section, lines 269–279)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F10 · Table

### 1. Overview & anatomy

A themed `TableView`. Elevated rounded surface, hover-tier header row, transparent cell borders,
and a hover + selected state on rows. The selected row's text turns accent-colored.

```
┌──────────────────────────────────────────────┐  .sk-table (elevated, 8px radius)
│ NAME            TYPE             SIZE         │  column-header (hover-tier bg)
├──────────────────────────────────────────────┤
│ json-formatter  builtin          12 KB        │  table-row-cell:hover → -sk-bg-hover
│ image-resizer   plugin           ───          │  table-row-cell:selected → -sk-bg-selected
│                                              │       (selected cell text → -sk-accent)
└──────────────────────────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-table` | `swisskit-common.css:251` | Table surface |
| `.sk-table .column-header-background` | `swisskit-common.css:259` | Header strip bg |
| `.sk-table .column-header` | `swisskit-common.css:260` | Header cell |
| `.sk-table .column-header .label` | `swisskit-common.css:261` | Header text |
| `.sk-table .table-cell` | `swisskit-common.css:262` | Body cell |
| `.sk-table .table-row-cell` | `swisskit-common.css:263` | Body row |
| `.sk-table .table-row-cell:selected` | `swisskit-common.css:264` | Selected row |
| `.sk-table .table-row-cell:selected .table-cell` | `swisskit-common.css:265` | Selected cell text |
| `.sk-table .table-row-cell:hover` | `swisskit-common.css:266` | Hovered row |
| `.sk-table .placeholder .label` | `swisskit-common.css:267` | Empty-state text |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Table surface bg |
| `-sk-border` | Surface + header-cell border |
| `-sk-bg-hover` | Header strip bg + row hover bg |
| `-sk-bg-selected` | Selected row bg |
| `-sk-text` | Body cell text |
| `-sk-text-secondary` | Header text |
| `-sk-text-disabled` | Empty-state placeholder text |
| `-sk-accent` | Selected row cell text |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Header | `.column-header-background` / `.column-header` | hover-tier bg; bottom border under each header |
| Row hover | `.table-row-cell:hover` | bg → `-sk-bg-hover` |
| Row selected | `.table-row-cell:selected` | bg → `-sk-bg-selected`; cell text → `-sk-accent` |
| Empty | `.placeholder .label` | disabled-color text |

Cell borders are forced transparent (`-fx-table-cell-border-color: transparent`) for a clean look.

### 5. Layout & sizing

Surface: `1px` border, `8px` radius. Header text: `12px`, weight `500`. Body cell: `13px`, padding
`6 10 6 10`. Container: `javafx.scene.control.TableView`.

### 6. JavaFX template

```java
TableView<Item> table = new TableView<>();
table.getStyleClass().add("sk-table");
// add TableColumn<Item,String> as usual; ColumnHeader/cell styling is automatic
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (table section, lines 250–267)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F11 · Tab pane

### 1. Overview & anatomy

A themed `TabPane` with flat, IDEA-style tabs: a transparent tab area, an underline-style selected
indicator (a 2px accent strip under the selected tab), and a transparent border under the whole
header. **The selectors are nested** — there is no standalone `.sk-tab` class.

```
  .sk-tab-pane .tab   (transparent, padded)
  ┌────────┬────────┬────────┐
  │  Tab 1 │  Tab 2 │  Tab 3 │   ← .sk-tab-pane .tab-header-area (1px bottom border)
  │════════│        │        │   ← .sk-tab-pane .tab:selected  (2px accent underline)
  └────────┴────────┴────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-tab-pane` | `swisskit-common.css:169` | TabPane root (transparent, min tab width 100px) |
| `.sk-tab-pane .tab-header-area` | `swisskit-common.css:170` | Header padding |
| `.sk-tab-pane .tab-header-area .tab-header-background` | `swisskit-common.css:171` | Header bg + bottom border |
| `.sk-tab-pane .tab` | `swisskit-common.css:176` | A single tab |
| `.sk-tab-pane .tab .tab-label` | `swisskit-common.css:185` | Tab label text |
| `.sk-tab-pane .tab:hover` | `swisskit-common.css:186` | Hovered tab |
| `.sk-tab-pane .tab:selected` | `swisskit-common.css:187` | Selected tab (underline) |
| `.sk-tab-pane .tab:selected .tab-label` | `swisskit-common.css:192` | Selected tab text |
| `.sk-tab-pane .tab:selected .focus-indicator` | `swisskit-common.css:193` | Suppress focus ring |

> **No standalone `.sk-tab`.** The rule is always `.sk-tab-pane .tab` (descendant). A bare
> `.sk-tab` does not exist in source and will not match anything.

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-border` | Header bottom border |
| `-sk-text-secondary` | Unselected tab label |
| `-sk-text` | Selected tab label |
| `-sk-bg-hover` | Tab hover bg |
| `-sk-bg-selected` | Selected tab bg |
| `-sk-accent` | Selected tab underline (bottom border) |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Unselected | `.tab` + `.tab .tab-label` | transparent bg; secondary text; padding `8 20` |
| Hover | `.tab:hover` | bg → `-sk-bg-hover` |
| Selected | `.tab:selected` | bg → `-sk-bg-selected`; 2px bottom border `-sk-accent` |
| Selected label | `.tab:selected .tab-label` | text → `-sk-text` |
| Focus ring | `.tab:selected .focus-indicator` | border forced transparent (no inner ring) |

The tab corner radius is `6px 6px 0 0` (top-only).

### 5. Layout & sizing

- `tab-min-width: 100px`, `tab-max-height: 36px`.
- Tab padding: `8 20 8 20`. Label: `13px`, weight `500`.
- Header area padding: `4 8 0 8`. Container: `javafx.scene.control.TabPane`.

### 6. JavaFX template

```java
TabPane tabs = new TabPane();
tabs.getStyleClass().add("sk-tab-pane");
tabs.getTabs().addAll(
    new Tab("Input",  inputPane),
    new Tab("Output", outputPane)
);
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (TabPane section, lines 168–193)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F12 · Dialog

### 1. Overview & anatomy

A themed dialog surface for a standalone `Stage` (not the native `Alert`). Elevated rounded card
with a 1px border and a soft drop shadow that uses the `-sk-shadow` token. Pair with `.sk-scrim`
([F5](#f5--scrim)) for the backdrop.

```
   .sk-scrim (dim)
   ┌────────────────────────────────────┐  .sk-dialog  (elevated, 10px radius)
   │  Title                              │     dropshadow(gaussian, -sk-shadow, 30, 0, 0, 12)
   │  Body message goes here.            │
   │            [ Cancel ]  [ OK ]       │
   └────────────────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-dialog` | `swisskit-common.css:142` | Dialog surface |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Dialog background |
| `-sk-border` | 1px frame border |
| `-sk-shadow` | Drop shadow color — `rgba(0,0,0,0.45)` dark / `rgba(15,23,42,0.18)` light |

> **Shadow uses the token.** The dropshadow is `dropshadow(gaussian, -sk-shadow, 30, 0, 0, 12)` —
> not a hard-coded `rgba(0,0,0,…)` literal. Keep it that way.

### 4. States & modifiers

None — `.sk-dialog` is a static surface. Buttons inside use `.sk-btn-primary`/`.sk-btn-secondary`
or `.sk-notif-ok`/`.sk-notif-cancel`.

### 5. Layout & sizing

`1px` border, `10px` corner radius. Shadow: radius 30, spread 0, offset `(0, 12)`. Container: a
`VBox`/`StackPane` set as the root of a `Stage(StageStyle.TRANSPARENT)`.

### 6. JavaFX template

```java
VBox dialog = new VBox(12);
dialog.getStyleClass().add("sk-dialog");
dialog.setPadding(new Insets(20, 24, 16, 24));
// add title label, body, and a button bar of .sk-btn-secondary / .sk-btn-primary
```

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (dialog section, lines 141–149)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)
- Theming a standalone Stage: [02 JavaFX Implementation — `#plugin-skeleton`](02-javafx-implementation.md#plugin-skeleton)

---

## F13 · Notification

### 1. Overview & anatomy

The glassmorphism notification system, exposed to plugins via
[`SkNotification`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/SkNotification.java)
(`toast` / `notify` / `confirm`). It is a self-contained elevated card with a severity-colored
circular icon, a wrapped message, and an optional button bar. **There is no `.sk-badge` and no
`.sk-notification`** — the family is `.sk-notif-*`.

```
┌────────────────────────────────────────────────┐  .sk-notif-root (420px, 10px radius)
│   ╭───╮                                         │     dropshadow(gaussian, -sk-shadow, 28, 0, 0, 10)
│   │ ⚠ │  Warning title                          │
│   ╰───╯  Body message wraps inside 360px.        │
│                                                │
│                          [ Cancel ]  [   OK   ] │  .sk-notif-btn-bar
└────────────────────────────────────────────────┘
   icon: .sk-notif-icon (32px circle) + severity class
   severity fills the icon circle with a SOFT token and tints the glyph
```

The severity class is applied to the **icon** node (not the root). It sets both the glyph color and
the icon-circle background:

```
.sk-notif-info     glyph -sk-accent   bg -sk-accent-soft
.sk-notif-success  glyph -sk-success  bg -sk-success-soft
.sk-notif-warning  glyph -sk-warning  bg -sk-warning-soft
.sk-notif-error    glyph -sk-danger   bg -sk-danger-soft
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-notif-root` | `swisskit-common.css:310` | Card surface (elevated, bordered, shadowed, 420px wide) |
| `.sk-notif-icon` | `swisskit-common.css:320` | 32px circular icon container |
| `.sk-notif-info` | `swisskit-common.css:326` | Info severity (icon glyph + soft bg) |
| `.sk-notif-success` | `swisskit-common.css:327` | Success severity |
| `.sk-notif-warning` | `swisskit-common.css:328` | Warning severity |
| `.sk-notif-error` | `swisskit-common.css:329` | Error severity |
| `.sk-notif-message` | `swisskit-common.css:330` | Body text (wraps at 360px) |
| `.sk-notif-btn-bar` | `swisskit-common.css:331` | Button container |
| `.sk-notif-ok` | `swisskit-common.css:332` | Primary OK button |
| `.sk-notif-cancel` | `swisskit-common.css:344` | Secondary Cancel button |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Card background |
| `-sk-border` | Card frame border |
| `-sk-shadow` | Card drop shadow color |
| `-sk-text` | Message text fill |
| `-sk-accent` / `-sk-accent-soft` | Info glyph + soft bg; OK button bg |
| `-sk-success` / `-sk-success-soft` | Success glyph + soft bg |
| `-sk-warning` / `-sk-warning-soft` | Warning glyph + soft bg |
| `-sk-danger` / `-sk-danger-soft` | Error glyph + soft bg |
| `-sk-bg-hover` / `-sk-bg-selected` | Cancel button bg / hover |
| `-sk-text-secondary` | Cancel button text |

> **Soft colors use the new soft tokens.** The severity backgrounds are `-sk-success-soft`,
> `-sk-warning-soft`, `-sk-danger-soft`, and `-sk-accent-soft` — **not** the old
> `rgba(76,217,123,0.15)` literal, which has been removed. Verify with the current source.

### 4. States & modifiers

| Element | Default | Hover |
|---|---|---|
| `.sk-notif-ok` | bg `-sk-accent`, white text (line 332) | bg `derive(-sk-accent,-8%)` (line 343) |
| `.sk-notif-cancel` | bg `-sk-bg-hover`, border `-sk-border`, secondary text (line 344) | bg `-sk-bg-selected`, text → `-sk-text` (line 355) |

The four severity classes are applied as a **second** class on the `.sk-notif-icon` node alongside
`.sk-notif-icon` — exactly as `SkNotification` does (`getStyleClass().addAll("sk-notif-icon", type.styleClass)`).

### 5. Layout & sizing

- Card: pref width `420px`, max `480px`, `10px` radius, `1px` border, padding (set in Java)
  `20 24 16 24`. Shadow: radius 28, spread 0, offset `(0, 10)`.
- Icon: `32×32` circle, glyph `22px`.
- Message: `13.5px`, line-spacing `2px`, wrapping width `360px` (set in Java).
- OK/Cancel: `12.5px`, `6px` radius, padding `6 20`. Container: a transparent `Stage` with
  `Themes.applyTo(scene)` so tokens resolve.

### 6. JavaFX template

Plugins should call the helper rather than build the markup by hand:

```java
// Auto-dismiss toast (~2.5s)
SkNotification.toast(view, SkNotification.Type.SUCCESS, "Saved");

// Modal with OK
SkNotification.notify(view, SkNotification.Type.WARNING, "Check your input");

// Modal confirm (blocks; false on timeout/close)
if (SkNotification.confirm(view, "Delete?", "This cannot be undone.")) {
    // ...do the destructive action
}
```

If you must construct one manually (rare), mirror `SkNotification.showOverlay`: a `.sk-notif-root`
`VBox` containing an `HBox` of a `.sk-notif-icon`+severity `Label` and a `.sk-notif-message` `Text`,
then a `.sk-notif-btn-bar` of `.sk-notif-cancel` + `.sk-notif-ok` `Button`s.

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (notification section, lines 309–355)
- Java: `SwissKitJ-Api/src/main/java/fan/summer/api/component/SkNotification.java`
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## F14 · Step wizard indicator

### 1. Overview & anatomy

The visual indicator for `StepWizard` — a horizontal row of numbered dots connected by lines.
Past steps show a green check, the current step is an accent-colored dot with a pulse, and future
steps are idle. The connector line between two completed steps turns green.

```
   ✓─────────②─────────③        (current = step 2, 0-based index 1)
   done      current    idle
   -sk-success  -sk-accent  -sk-bg-selected / -sk-border
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.sk-step-done` | `swisskit-common.css:358` | Completed dot (fill + stroke `-sk-success`) |
| `.sk-step-current` | `swisskit-common.css:359` | Current dot (fill + stroke `-sk-accent`) |
| `.sk-step-idle` | `swisskit-common.css:360` | Future dot (fill `-sk-bg-selected`, stroke `-sk-border`) |
| `.sk-step-line-done` | `swisskit-common.css:361` | Completed connector line (`-sk-success`) |
| `.sk-step-line-idle` | `swisskit-common.css:362` | Future connector line (`-sk-border`) |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-success` | Done dot fill/stroke + done line |
| `-sk-accent` | Current dot fill/stroke |
| `-sk-bg-selected` | Idle dot fill (visible in both themes) |
| `-sk-border` | Idle dot stroke + idle line |

### 4. States & modifiers

The state classes are **swapped in Java** by `StepWizard.refreshIndicator()` (a dot carries exactly
one of `sk-step-done` / `sk-step-current` / `sk-step-idle` at a time). The number/check label inside
each dot uses `.sk-t1` (done/current) or `.sk-t3` (idle).

### 5. Layout & sizing

Dots are `Circle(12)` inside a `24×24` `StackPane`, stroke-width `1.5`. Connector lines are `2px`
tall `Region`s, min-width `40`, growing to fill. The wizard body uses `.sk-btn-primary`/secondary
styling for its footer buttons (currently via inline hex in `StepWizard` — see the button component).

### 6. JavaFX template

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  selectNode,  () -> file != null);
wizard.addStep("Configure",    configNode,  () -> configValid);
wizard.addStep("Confirm",      confirmNode, () -> true);
wizard.build();
```

(Indicator styling is automatic — you do not apply the `.sk-step-*` classes yourself.)

### 7. References

- CSS: `SwissKitJ-Api/src/main/resources/css/swisskit-common.css` (StepWizard indicator section, lines 357–362)
- Java: `SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java`
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

# Part B — Shell Components

> Source: `SwissKit/src/main/resources/css/shell.css`. **Host application only.** These classes are
> app-shell chrome — they are not loaded onto plugin scenes and a plugin must not depend on them.
> They are documented here so the shell itself can be regenerated faithfully.

The shell lives by one signature IDEA-New-UI rule:

> **Selection = a neutral-gray fill + a thin accent strip, never a blue flood.** The selected nav
> item is `-sk-bg-selected` with a **3px left** `-sk-accent` border — not an accent-filled row.
> (See P3 in [05 Theme & Color System](05-theme-color-system.md).)

---

## S1 · Navigation item

### 1. Overview & anatomy

A single row in the left sidebar: icon + label + optional count badge. Flat by default; the active
item gets the neutral-fill-plus-left-strip treatment. Hosted in `Sidebar` / its inner `NavItem`.

```
   .nav-item (flat, 6px radius)
   ┌─────────────────────────────────────┐
   │ ▢  All Tools                 [ 3 ]  │  icon  label (Hgrow)  badge
   └─────────────────────────────────────┘
       │
       │  hover → bg -sk-bg-hover, label → -sk-text
       │  active → bg -sk-bg-selected + 3px LEFT -sk-accent border
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.nav-item` | `shell.css:54` | Item row |
| `.nav-item-icon` | `shell.css:65` | Icon glyph |
| `.nav-item-text` | `shell.css:66` | Label text |
| `.nav-item:hover` | `shell.css:67` | Hovered row |
| `.nav-item.active` | `shell.css:69` | Active (selected) row |
| `.nav-item.active .nav-item-text` | `shell.css:74` | Active label |
| `.nav-badge` | `shell.css:76` | Count badge |
| `.nav-badge-new` | `shell.css:84` | "New" badge variant |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-hover` | Row hover bg |
| `-sk-bg-selected` | Active row bg (the neutral fill) |
| `-sk-accent` | Active row **left** 3px border |
| `-sk-text-secondary` | Icon + label default (and badge text) |
| `-sk-text` | Hovered/active label |
| `-sk-success` | `.nav-badge-new` text |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Default | `.nav-item` | transparent bg, transparent border, `6px` radius, pref-height `32px` |
| Hover | `.nav-item:hover` | bg → `-sk-bg-hover`; label → `-sk-text` |
| Active | `.nav-item.active` | bg → `-sk-bg-selected`; left border `3px` `-sk-accent` (the signature rule) |
| Active label | `.nav-item.active .nav-item-text` | text → `-sk-text`, weight `500` |
| Collapsed | `.sidebar.collapsed .nav-item` | icon centered, padding `8 0 8 0`; text/badge opacity 0 |

The active border is written as `transparent transparent transparent -sk-accent` with width
`0 0 0 3px` — i.e. **left side only**. The icon's hover/active fill is currently set inline in
`Sidebar.NavItem` (`#9AA0A6` idle, `#3574F0` active) — a known legacy TODO; the CSS `.nav-item-icon`
rule declares `-sk-text-secondary` as the intended fill.

### 5. Layout & sizing

Row: `6px` radius, padding `7 10 7 12`, spacing `10px`, pref-height `32px`, alignment
`CENTER_LEFT`. Icon: `16px` MDI glyph, min-width `18px`. Badge: `20px` radius pill, padding
`1 7`, font `10px` bold. Container: `HBox` (`Sidebar.NavItem extends HBox`).

### 6. JavaFX template

The shell builds these via `Sidebar`/`NavItem`; a faithful reconstruction:

```java
HBox item = new HBox();
item.getStyleClass().add("nav-item");
item.setAlignment(Pos.CENTER_LEFT);
item.setSpacing(10);
item.setPrefHeight(32);

Text icon = MdiIconUtil.createIcon("view-grid", 16);
icon.getStyleClass().add("nav-item-icon");

Label text = new Label("All Tools");
text.getStyleClass().add("nav-item-text");
HBox.setHgrow(text, Priority.ALWAYS);

Label badge = new Label("3");
badge.getStyleClass().add("nav-badge");

item.getChildren().addAll(icon, text, badge);
// on selection: item.getStyleClass().add("active");
```

### 7. References

- CSS: `SwissKit/src/main/resources/css/shell.css` (sidebar section, lines 53–84)
- Java: `SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java` (`NavItem` inner class)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)
- Icons: [06 Icon System](06-icon-system.md)

---

## S2 · Search bar

### 1. Overview & anatomy

The IDEA-"Search Everywhere"-style pill at the top of the content area. A fully rounded container
holding a search icon, a transparent `TextField`, and a keyboard-shortcut hint chip. It lightens
its border to the accent on focus-within.

```
   .search-bar (pill, 999px radius, 34px tall)
   ╭──────────────────────────────────────────╮
   │ 🔍   Search tools…               ⌘K       │
   ╰──────────────────────────────────────────╯
    icon  .search-field (transparent)   .search-kbd
       focused-within → border -sk-accent, bg -sk-bg
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.search-bar` | `shell.css:93` | Pill container |
| `.search-bar:focused-within` | `shell.css:103` | Focused state (any child focused) |
| `.search-field` | `shell.css:107` | Inner TextField (transparent) |
| `.search-field:focused` | `shell.css:114` | Keep inner field transparent on focus |
| `.search-icon` | `shell.css:214` | Leading magnifier glyph |
| `.search-kbd` | `shell.css:215` | Shortcut hint chip |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Default pill bg |
| `-sk-bg` | Focused-within pill bg |
| `-sk-border` | Default pill border |
| `-sk-accent` | Focused-within border |
| `-sk-text` | Input text |
| `-sk-text-disabled` | Prompt text + icon + kbd hint |

### 4. States & modifiers

| State | Selector | Effect |
|---|---|---|
| Default | `.search-bar` | `-sk-bg-elevated` bg, `-sk-border` border, `999px` radius |
| Focused-within | `.search-bar:focused-within` | bg → `-sk-bg`, border → `-sk-accent` |
| Inner field focus | `.search-field:focused` | stays transparent (no inner box) |

The inner `.search-field` is intentionally borderless/transparent so the pill itself is the only
visible frame.

### 5. Layout & sizing

Pill: `999px` radius, pref-height `34px`, padding `0 14`, spacing `10px`. Field: `13px`, prompt in
disabled color. Kbd chip: `10px`, monospace, `4px` radius, padding `1 5`. Container: `HBox` built
in `ContentArea.buildSearchBar()`.

### 6. JavaFX template

```java
Label icon = new Label("🔍");
icon.getStyleClass().add("search-icon");

TextField field = new TextField();
field.getStyleClass().add("search-field");
field.setPromptText("Search tools…");
HBox.setHgrow(field, Priority.ALWAYS);

Label kbd = new Label("⌘K");
kbd.getStyleClass().add("search-kbd");

HBox bar = new HBox(10, icon, field, kbd);
bar.getStyleClass().add("search-bar");
bar.setAlignment(Pos.CENTER_LEFT);
bar.setPrefHeight(34);
```

### 7. References

- CSS: `SwissKit/src/main/resources/css/shell.css` (search-bar section, lines 92–114; kbd lines 213–225)
- Java: `SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java` (`buildSearchBar()`)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## S3 · Tool card

### 1. Overview & anatomy

A single tile in the tool grid. Elevated rounded card with a colored icon (in a 48px wrapper that
carries an `.ic-*` accent class), the tool name, a short description, and a built-in/plugin tag.
Hover lifts the border to strong and bumps the bg to hover-tier; Java adds a scale + icon-glow on
hover and a staggered entry animation.

```
   .tool-card (elevated, 8px radius, 152×128)
   ┌───────────────────────────┐
   │                       ★    │  favorite star (top-right, Java)
   │   ╭────╮                   │
   │   │ ic │  Tool Name        │  .tool-icon-wrap (+ .ic-*)  .tool-name
   │   ╰────╯  Short desc…      │                              .tool-desc
   │                            │
   │   BUILTIN                  │  .tool-tag (+ .tool-tag-plugin)
   └───────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.tool-card` | `shell.css:117` | Card surface |
| `.tool-card:hover` | `shell.css:129` | Hover lift |
| `.tool-icon-wrap` | `shell.css:131` | 48px icon container (transparent; color from `.ic-*`/Java) |
| `.ic-blue` … `.ic-gray` | `shell.css:138–144` | Icon accent classes — **empty CSS rules** (color/glow set in Java) |
| `.tool-name` | `shell.css:146` | Tool name text |
| `.tool-desc` | `shell.css:147` | Description text |
| `.tool-tag` | `shell.css:148` | Built-in/plugin tag |
| `.tool-tag-plugin` | `shell.css:158` | Plugin-tag variant (green) |

> **`.ic-*` rules are intentionally empty.** They carry no CSS properties — the icon fill and the
> `DropShadow` glow are injected in Java from `IconStyle.getColor()` (see [06 Icon System](06-icon-system.md)).
> The class exists only as a semantic hook / future CSS hook.

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Card bg |
| `-sk-bg-hover` | Hover bg |
| `-sk-border` / `-sk-border-strong` | Default / hover border |
| `-sk-text` | Tool name |
| `-sk-text-secondary` | Description + tag text |
| `-sk-success` | `.tool-tag-plugin` text (+ its inline green tint) |

### 4. States & modifiers

| State | Selector / handler | Effect |
|---|---|---|
| Default | `.tool-card` | `-sk-bg-elevated`, `-sk-border`, `8px` radius |
| Hover (CSS) | `.tool-card:hover` | bg → `-sk-bg-hover`, border → `-sk-border-strong` |
| Hover (Java) | `ToolCard` mouse handlers | scale → 1.03, icon glow radius 12→20 |
| Press (Java) | click handler | scale dip to 0.97 then callback |
| Entry (Java) | constructor | fade+translate+scale over 280ms (staggered by 35ms/card) |
| Background-running | Java | green pulsing dot top-right |

### 5. Layout & sizing

Card: `8px` radius, pref `152×128`, padding `14`, spacing `3px`. Icon wrap: `48×48`. Name: `13px`,
weight `500`. Desc: `11px`, wrap-text. Tag: `10px`, `4px` radius, padding `1 6`. Container:
`StackPane` (`ToolCard extends StackPane`) wrapping an inner `VBox`.

### 6. JavaFX template

The shell builds cards via `new ToolCard(plugin, onSelect, registry, favoriteService)`. A faithful
manual build:

```java
VBox card = new VBox();
card.getStyleClass().add("tool-card");
card.setSpacing(3);

Text icon = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);
icon.setStyle("-fx-fill: rgba(...);");          // from IconStyle.getColor()
StackPane wrap = new StackPane(icon);
wrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass());
wrap.setPrefSize(48, 48);

Label name = new Label(plugin.getName());
name.getStyleClass().add("tool-name");
Label desc = new Label(plugin.getDescription());
desc.getStyleClass().add("tool-desc");
desc.setWrapText(true);
Label tag = new Label("BUILTIN");
tag.getStyleClass().add("tool-tag");

card.getChildren().addAll(wrap, name, desc, tag);
```

### 7. References

- CSS: `SwissKit/src/main/resources/css/shell.css` (tool-card section, lines 116–158)
- Java: `SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java`
- Icons: [06 Icon System](06-icon-system.md) (and `IconStyle` enum)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## S4 · Detail panel

### 1. Overview & anatomy

The slide-in panel on the right of the content area, shown when a tool card is selected. Elevated
surface with a left border + inset shadow, the tool icon, name + version/type meta, a description,
Launch/Uninstall/Favorite buttons, and a properties list (version/type/category). It animates in
from the right (translateX 260→0).

```
   .detail-panel (elevated, left border, 260px wide)
   ┌──────────────────────────────┐
   │                          [✕]  │  close (.sk-t3)
   │   ╭────╮                      │
   │   │ ic │  Tool Name           │  .tool-icon-wrap  .tool-name
   │   ╰────╯  v1.2.0 · builtin    │                   .status-text (meta)
   │   Description wraps here.     │  .tool-desc
   │                              │
   │   [      LAUNCH         ]    │  .detail-launch-btn (accent fill)
   │   [    UNINSTALL        ]    │  inline red-tinted button
   │   [   ADD FAVORITE      ]    │  inline amber-tinted button
   │                              │
   │   Version        1.2.0       │  .sk-t3 key / .sk-t2 value (propRow)
   │   Type           builtin     │
   │   Category       text        │
   └──────────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.detail-panel` | `shell.css:161` | Panel surface |
| `.detail-launch-btn` | `shell.css:171` | Launch button (accent fill) |
| `.detail-launch-btn:hover` | `shell.css:181` | Launch hover |
| `.detail-launch-btn:pressed` | `shell.css:182` | Launch pressed |

The panel also reuses foundation classes: `.tool-icon-wrap`, `.tool-name`, `.tool-desc`,
`.status-text` (meta), and `.sk-t1`/`.sk-t2`/`.sk-t3` (close button + property key/value rows).

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Panel bg |
| `-sk-border` | Left border |
| `-sk-accent` | Launch button bg (+ hover/press `derive`) |
| `-sk-text` / `-sk-text-secondary` / `-sk-text-disabled` | Name / desc / close+prop-key |

> The panel's drop shadow in shell.css still uses an `rgba(0,0,0,0.30)` literal (offset `(-4,0)`) —
> this is a host-shell detail, not a tokenized foundation shadow.

### 4. States & modifiers

| Element | Default | Hover | Pressed |
|---|---|---|---|
| `.detail-launch-btn` | bg `-sk-accent`, white text | bg `derive(-sk-accent,-8%)` | bg `derive(-sk-accent,-16%)` |

The Uninstall/Favorite buttons are styled **inline** in `DetailPanel` (red/amber tints) rather than
via reusable classes — they are one-off affordances. The slide-in/out is driven by a `Timeline` in
Java (300ms in / 250ms out, spline ease).

### 5. Layout & sizing

Panel: pref/min/max width `260px`, padding `20 16 20 16`, spacing `10px`, left border `1px`, no
radius. Launch button: full-width, `6px` radius, pref-height `34px`. Container: `VBox`
(`DetailPanel extends VBox`). The panel is overlaid in a `StackPane` aligned `TOP_RIGHT` so the grid
doesn't reflow while it slides.

### 6. JavaFX template

The shell uses `new DetailPanel()` then `detailPanel.show(plugin)` / `detailPanel.hide()`. Manual
core build:

```java
VBox panel = new VBox();
panel.getStyleClass().add("detail-panel");
panel.setPrefWidth(260);

Button launch = new Button("Launch");
launch.getStyleClass().add("detail-launch-btn");
launch.setMaxWidth(Double.MAX_VALUE);
```

### 7. References

- CSS: `SwissKit/src/main/resources/css/shell.css` (detail-panel section, lines 160–182)
- Java: `SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java`
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)
- Icons: [06 Icon System](06-icon-system.md)

---

## S5 · Status bar

### 1. Overview & anatomy

The thin bar pinned to the bottom of the main window. Left-aligned status text (tool/plugin counts)
separated by a dim dot, a live clock on the right, and a small pulsing green activity dot. The text
uses a monospaced font for a "console" feel.

```
   .statusbar (elevated, top border, 28px tall)
   ┌──────────────────────────────────────────────────────────┐
   │ ● 12 tools  ·  3 plugins                    14:02:09      │
   │ ↑ activity dot  ↑.status-text   ↑.status-sep    ↑clock     │
   └──────────────────────────────────────────────────────────┘
```

### 2. CSS classes

| Class | Source line | Purpose |
|---|---|---|
| `.statusbar` | `shell.css:185` | Bar container |
| `.status-text` | `shell.css:194` | Status/clock text (monospaced) |
| `.status-sep` | `shell.css:200` | The "·" separator dot |

### 3. Tokens used

| Token | Role |
|---|---|
| `-sk-bg-elevated` | Bar background |
| `-sk-border` | Top border |
| `-sk-text-secondary` | Status/clock text |
| `-sk-text-disabled` | Separator dot |

The pulsing activity dot is a Java `Circle` (`Color.web("#4cd97b")` + `Glow`) with a
`FadeTransition` — its color is currently inline, not tokenized.

### 4. States & modifiers

None — the status bar is a static read-out strip.

### 5. Layout & sizing

Bar: pref/min-height `28px`, top border `1px`, padding `0 16`, spacing `12px`. Text: `12px`,
monospaced (`"SF Mono", "Consolas", "Microsoft YaHei", monospace`). Container: `HBox` built in
`MainWindow.buildStatusBar()`. A `Region` with `Hgrow.ALWAYS` pushes the clock to the right.

### 6. JavaFX template

```java
HBox bar = new HBox(12);
bar.getStyleClass().add("statusbar");
bar.setAlignment(Pos.CENTER_LEFT);
bar.setPadding(new Insets(0, 16, 0, 16));

Label tools = new Label("12 tools");
tools.getStyleClass().add("status-text");

Label sep = new Label("·");
sep.getStyleClass().add("status-sep");

Label plugins = new Label("3 plugins");
plugins.getStyleClass().add("status-text");

Region spacer = new Region();
HBox.setHgrow(spacer, Priority.ALWAYS);

Label clock = new Label("14:02:09");
clock.getStyleClass().add("status-text");

bar.getChildren().addAll(tools, sep, plugins, spacer, clock);
```

### 7. References

- CSS: `SwissKit/src/main/resources/css/shell.css` (statusbar section, lines 184–200)
- Java: `SwissKit/src/main/java/fan/summer/ui/MainWindow.java` (`buildStatusBar()`)
- Tokens: [05 Theme & Color System — Token Reference Table](05-theme-color-system.md#token-reference-table)

---

## Appendix · Class-to-component matrix

A reverse index: given a class, find its component.

### Foundation classes (`swisskit-common.css`)

| Class | Component |
|---|---|
| `.sk-t1` `.sk-t2` `.sk-t3` | [F1 Text utilities](#f1--text-utilities) |
| `.sk-fill-2` `.sk-fill-3` | [F4 Shape-fill utilities](#f4--shape-fill-utilities) |
| `.sk-surface` `.sk-surface-soft` | [F2 Surface utilities](#f2--surface-utilities) |
| `.sk-outlined` `.sk-outlined-strong` | [F2 Surface utilities](#f2--surface-utilities) |
| `.sk-accent-text` `.sk-success-text` `.sk-warning-text` `.sk-danger-text` | [F3 Status-text utilities](#f3--status-text-utilities) |
| `.sk-scrim` | [F5 Scrim](#f5--scrim) |
| `.sk-field` `.sk-field-label` | [F6 Field](#f6--field) |
| `.sk-btn-primary` `.sk-btn-secondary` | [F7 Button](#f7--button) |
| `.sk-combo` | [F8 Combo box](#f8--combo-box) |
| `.sk-checkbox` | [F9 Checkbox](#f9--checkbox) |
| `.sk-table` | [F10 Table](#f10--table) |
| `.sk-tab-pane` | [F11 Tab pane](#f11--tab-pane) |
| `.sk-dialog` | [F12 Dialog](#f12--dialog) |
| `.sk-notif-root` `.sk-notif-icon` `.sk-notif-info` `.sk-notif-success` `.sk-notif-warning` `.sk-notif-error` `.sk-notif-message` `.sk-notif-ok` `.sk-notif-cancel` | [F13 Notification](#f13--notification) |
| `.sk-step-done` `.sk-step-current` `.sk-step-idle` `.sk-step-line-done` `.sk-step-line-idle` | [F14 Step wizard indicator](#f14--step-wizard-indicator) |

### Shell classes (`shell.css`)

| Class | Component |
|---|---|
| `.nav-item` `.nav-item-icon` `.nav-item-text` `.nav-badge` `.nav-badge-new` | [S1 Navigation item](#s1--navigation-item) |
| `.search-bar` `.search-field` `.search-icon` `.search-kbd` | [S2 Search bar](#s2--search-bar) |
| `.tool-card` `.tool-icon-wrap` `.ic-*` `.tool-name` `.tool-desc` `.tool-tag` `.tool-tag-plugin` | [S3 Tool card](#s3--tool-card) |
| `.detail-panel` `.detail-launch-btn` | [S4 Detail panel](#s4--detail-panel) |
| `.statusbar` `.status-text` `.status-sep` | [S5 Status bar](#s5--status-bar) |

### Names that do NOT exist (do not use)

| Nonexistent name | Correct alternative |
|---|---|
| `.sk-btn` | `.sk-btn-primary` or `.sk-btn-secondary` |
| `.sk-text-field` | `.sk-field` |
| `.sk-badge` | `.nav-badge` (shell) or `.tool-tag` (shell) |
| `.sk-notification` | `.sk-notif-*` family |
| `.sk-tab` (standalone) | `.sk-tab-pane .tab` (nested) |

---

## References

### Source files (canonical)

- Foundation CSS: [`SwissKitJ-Api/src/main/resources/css/swisskit-common.css`](../../SwissKitJ-Api/src/main/resources/css/swisskit-common.css)
- Shell CSS: [`SwissKit/src/main/resources/css/shell.css`](../../SwissKit/src/main/resources/css/shell.css)
- Shell Java:
  - [`SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java`](../../SwissKit/src/main/java/fan/summer/ui/sidebar/Sidebar.java) (`NavItem`)
  - [`SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ContentArea.java) (search/grid)
  - [`SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java`](../../SwissKit/src/main/java/fan/summer/ui/content/ToolCard.java)
  - [`SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java`](../../SwissKit/src/main/java/fan/summer/ui/content/DetailPanel.java)
  - [`SwissKit/src/main/java/fan/summer/ui/MainWindow.java`](../../SwissKit/src/main/java/fan/summer/ui/MainWindow.java) (status bar)
- Foundation Java:
  - [`SwissKitJ-Api/src/main/java/fan/summer/api/component/SkNotification.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/SkNotification.java)
  - [`SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java)
  - [`SwissKitJ-Api/src/main/java/fan/summer/api/IconStyle.java`](../../SwissKitJ-Api/src/main/java/fan/summer/api/IconStyle.java)

### Sibling UI design docs

- [01 Design System](01-design-system.md)
- [02 JavaFX Implementation](02-javafx-implementation.md) — [`#css-naming`](02-javafx-implementation.md#css-naming), [`#plugin-skeleton`](02-javafx-implementation.md#plugin-skeleton)
- [05 Theme & Color System](05-theme-color-system.md) — [`#token-reference-table`](05-theme-color-system.md#token-reference-table)
- [06 Icon System](06-icon-system.md)
