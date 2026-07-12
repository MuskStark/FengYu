# 06 · Icon System

> **Role:** This is the **single source of truth** for icons in FengYu — the icon library,
> the `MdiIconUtil` API, the size scale, the `IconStyle` accent palette, and the one **non-obvious
> trap** that catches every first-time author: the `.ic-*` CSS classes are **empty**, so icon color
> + glow are applied from **Java**, never from CSS. Component Library (doc 03) links here for icon
> usage; bookmark the anchor [`#icon-reference`](#icon-reference).

| | |
|---|---|
| **Doc type** | Icon spec + rendering API |
| **Audience** | Plugin authors, AI code generators, anyone who puts a glyph on screen |
| **Source of truth** | [`FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java) · [`IconStyle.java`](../../FengYu-Api/src/main/java/fan/summer/api/IconStyle.java) |
| **Related** | [02 JavaFX Implementation](02-javafx-implementation.md) · [03 Component Library](03-component-library.md) · [05 Theme & Color System](05-theme-color-system.md) |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Spec Tables](#3-spec-tables)
   - [3.1 `MdiIconUtil` API](#mdiiconutil-api)
   - [3.2 Size Scale](#size-scale)
   - [3.3 `IconStyle` Accent Palette](#iconstyle-accent-palette)
   - [3.4 Built-in Tool Icon Mappings](#built-in-tool-icon-mappings)
4. [JavaFX Implementation Template](#4-javafx-implementation-template)
5. [AI Development Checklist](#5-ai-development-checklist)
6. [Anti-patterns](#6-anti-patterns)
7. [References](#7-references)

---

## 1. Overview

FengYu renders **all** icons from a single bundled webfont: **Material Design Icons** (MDI) by
Pictogrammers. There are no PNGs, no SVGs, no emoji — every glyph is a Unicode codepoint drawn from
the `MaterialDesignIcons` font family.

```
┌──────────────────────────────────────────────────────────────────────┐
│  How an icon reaches the screen                                       │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│   plugin.getMdiIcon()        "file-excel"   (name, NO "mdi-" prefix) │
│            │                                                          │
│            ▼                                                          │
│   MdiIconUtil.createIcon(name, 45)                                    │
│            │  ┌─ looks up codepoint in mdi-codemap.properties ──┐     │
│            │  │  file-excel → \uDB80\uDE1B                      │     │
│            │  │  (unknown name? → falls back to "star")         │     │
│            │  └─────────────────────────────────────────────────┘     │
│            ▼                                                          │
│   new Text(codepoint)  +  Font "MaterialDesignIcons" @ size          │
│            │                                                          │
│            ▼                                                          │
│   host fills it:   icon.setFill(plugin.getIconStyle().getColor())    │
│   host glows it:   DropShadow(color, radius 12, spread 0.15)         │
│            │                                                          │
│            ▼                                                          │
│   StackPane.tool-icon-wrap (48×48)  →  tool card                     │
└──────────────────────────────────────────────────────────────────────┘
```

### The three resources

| What | Where | Notes |
|---|---|---|
| **Codepoint map** | [`FengYu-Api/src/main/resources/fonts/mdi-codemap.properties`](../../FengYu-Api/src/main/resources/fonts/mdi-codemap.properties) | **7 448** name → codepoint entries. Keys are bare MDI names (`file-excel`, **not** `mdi-file-excel`). Loaded lazily on first `MdiIconUtil` use. |
| **Webfont binary** | `/fonts/materialdesignicons-webfont.ttf` (classpath, bundled in the API module) | Font family registered as `MaterialDesignIcons`. Bundled alongside the code map. |
| **Rendering API** | [`MdiIconUtil.java`](../../FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java) | The **only** entry point plugin code should use. |

### The one API you use

```java
import fan.summer.fengyu.api.MdiIconUtil;
import javafx.scene.text.Text;

// 1. name WITHOUT the "mdi-" prefix; size in logical pixels
Text icon = MdiIconUtil.createIcon("file-excel", 24.0);

// 2. unknown names silently fall back to the "star" glyph — verify the spelling!
Text fallback = MdiIconUtil.createIcon("does-not-exist", 24.0); // renders ★
```

The full icon catalog (for browsing names visually) is at the
[MDI library](https://pictogrammers.com/library/mdi/). A 20-line crash course on creating icons
lives in [02 JavaFX Implementation §4.2](02-javafx-implementation.md#42-icons--mdiiconutil); this
doc is the deep dive.

---

## 2. Design Principles

### P1 — One library, one renderer

Every icon in the product comes from the MDI webfont, drawn through `MdiIconUtil.createIcon(...)`.
**Never** introduce a second icon source — no SF Symbols, no FontAwesome, no hand-rolled SVG paths,
no `ImageView` with an icon asset. Mixing libraries shatters visual consistency (stroke weight,
optical sizing, grid) and doubles the font payload. If a needed glyph is missing, register it with
`putIcon()` (P6) — do not pull in another font.

### P2 — Icons convey meaning, never decorate

An icon must carry information that the label (or context) does not already fully convey on its
own. If removing the icon would change nothing about comprehension, the icon is decoration and
should be dropped. A tool's `getMdiIcon()` glyph is the primary visual identity of that tool — pick
one that reads as the tool's *verb or noun* (`email` for an email tool, `file-excel` for an
Excel splitter), not a generic sparkle.

> **A11y:** An icon by itself is not a label. Tool cards always pair the glyph with a `.tool-name`
> label; do not ship an icon-only control without an accessible name (`accessibleText` / tooltip).

### P3 — Names are bare; never prefix `mdi-`

`getMdiIcon()` and `createIcon(name, …)` both take the **MDI name without the `mdi-` prefix**:
`"file-excel"`, `"code-json"`, `"folder-open"`. The catalog website lists icons as
`mdi-file-excel`; **strip the prefix** before returning it. A prefixed name is unknown to the
codemap and silently falls back to `star`.

```
correct:   "file-excel"      ✅   renders the Excel glyph
WRONG:     "mdi-file-excel"  ❌   not in codemap → renders "star" silently
```

### P4 — Color follows the token system; `IconStyle` is the only exception

Generic UI icons (nav items, toolbar buttons, status glyphs) take their fill from the theme token
system defined in [05 Theme & Color System](05-theme-color-system.md): they render in
`-sk-text-secondary` / `-sk-text-disabled` so they recolor automatically when the user switches
dark↔light. Use the **utility styleclasses** `.sk-fill-2` / `.sk-fill-3` on a `Text` node for this
— do **not** write inline `setStyle("-fx-fill: -sk-text-secondary;")` (see [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle):
inline `setStyle` cannot resolve looked-up color variables).

**Tool icons are the exception.** A tool's accent color is a **brand identity**, not theme text,
so it is set from Java via `IconStyle.getColor()` (see [§4](#4-javafx-implementation-template)).
This deliberately does **not** follow the theme.

### P5 — Pick an `IconStyle`, but the host renders it

When you author a tool, you return an `IconStyle` (one of seven accents) from `getIconStyle()`.
**You do not color the node yourself for tool icons** — the host (`ToolCard`, `DetailPanel`) reads
`getIconStyle().getColor()`, fills the `Text`, and applies the `DropShadow` glow. Your job is only
to *choose* the style; see the [critical `.ic-*` note](#the-critical-ic--trap) below.

### P6 — Extend, don't fork

For plugin-specific glyphs not in the bundled set, register them at runtime:

```java
MdiIconUtil.putIcon("my-plugin-mark", "\uDB81\uDC93"); // codepoint from your font subset
```

Then `createIcon("my-plugin-mark", 24)` works like any builtin. This keeps the single-renderer
contract (P1) intact.

---

## 3. Spec Tables

<a id="icon-reference"></a>

### MdiIconUtil API

All methods are `static` on `fan.summer.fengyu.api.MdiIconUtil`. The class loads the codemap and webfont
lazily and caches them for the process lifetime.

| Signature | Returns | Behavior |
|---|---|---|
| `createIcon(String name, double size)` | `javafx.scene.text.Text` | Glyph `Text` at `size` px. Unknown `name` → falls back to the `"star"` glyph. Default fill is **white** (`-fx-fill: white;`); override per [§4](#4-javafx-implementation-template). |
| `createIcon(String name, double size, String extraStyle)` | `javafx.scene.text.Text` | As above, with extra inline CSS appended after the default white fill (e.g. `"-fx-fill: #FF5722;"`). `extraStyle` may be `null` (then the 1-arg form). |
| `getCodepoint(String name)` | `String` | The raw Unicode codepoint string (a surrogate pair) for `name`; unknown → `"star"`'s codepoint. Useful for embedding in a `Label`/`Text` you build yourself. |
| `getFont(double size)` | `javafx.scene.text.Font` | The `MaterialDesignIcons` font at `size`; `null` if the webfont resource could not be loaded. |
| `putIcon(String name, String codepoint)` | `void` | Registers/overrides a name → codepoint mapping at runtime (plugin custom glyphs). |

> **Verification anchor** — the four read signatures above are exactly:
> ```
> public static Text   createIcon(String iconName, double size)
> public static Text   createIcon(String iconName, double size, String extraStyle)
> public static String getCodepoint(String iconName)
> public static Font   getFont(double size)
> ```
> plus `public static void putIcon(String name, String codepoint)`.

**Fallback behavior** — both `createIcon` and `getCodepoint` use
`CODEMAP.getOrDefault(name, CODEMAP.get("star"))`. A typo therefore renders *something* (a star)
silently. Always confirm the name exists in
[`mdi-codemap.properties`](../../FengYu-Api/src/main/resources/fonts/mdi-codemap.properties)
before shipping.

### Size Scale

| Size | Token intent | Where it's used | Source |
|---|---|---|---|
| **16 px** | inline / status glyph | Sidebar nav icons (`Sidebar` uses `createIcon(mdi, 16, …)`) | code |
| **18 px** | nav-item icon | `.nav-item-icon { -fx-min-width: 18px; }` | [`shell.css`](../../FengYu/src/main/resources/css/shell.css) |
| **20 px** | small UI control | compact toolbar / chip glyphs | convention |
| **24 px** | standard / card icon | the default for a standalone icon you render in a plugin view | convention |
| **32 px** | large inline | empty-state hero glyph | convention |
| **45 px** | tool-card glyph | `MdiIconUtil.createIcon(plugin.getMdiIcon(), 45)` inside the 48 px wrap | [`ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java) |
| **48 px** | tool-icon-wrap *container* | `.tool-icon-wrap { -fx-pref-width: 48px; -fx-pref-height: 48px; }` | [`shell.css`](../../FengYu/src/main/resources/css/shell.css) |
| **50 px** | detail-panel hero | `MdiIconUtil.createIcon(p.getMdiIcon(), 50)` | [`DetailPanel.java`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) |

```
16 ─── inline / status        24 ─── standard card icon
18 ─── nav-item               32 ─── large inline
20 ─── small control          45 ─── tool-card glyph (in 48px wrap)
                              50 ─── detail-panel hero
```

> **Wrap vs glyph:** the `tool-icon-wrap` *container* is 48 px; the *glyph* drawn inside it is
> rendered at 45 px (so there is a small optical inset). When citing "tool icon size", say 45 px
> (glyph) or 48 px (tile) — be explicit which.

### IconStyle Accent Palette

`fan.summer.fengyu.api.IconStyle` — seven accent styles. Each carries a **CSS class name** and a
**`javafx.scene.paint.Color`**. The CSS class is applied to the icon *wrapper*; the color is
applied to the `Text` *glyph* from Java. (See [the trap](#the-critical-ic--trap).)

| `IconStyle` | CSS class | RGB | `Color` equivalent | Default fill of... |
|---|---|---|---|---|
| `BLUE` | `ic-blue` | **99, 130, 255** | `Color.rgb(99, 130, 255)` | (the `getIconStyle()` default) |
| `PURPLE` | `ic-purple` | **160, 110, 255** | `Color.rgb(160, 110, 255)` | AiChat |
| `TEAL` | `ic-teal` | **40, 210, 140** | `Color.rgb(40, 210, 140)` | Base64 / Excel / EmailArchive / Browser |
| `AMBER` | `ic-amber` | **255, 185, 50** | `Color.rgb(255, 185, 50)` | HashCalculator |
| `RED` | `ic-red` | **255, 100, 100** | `Color.rgb(255, 100, 100)` | PdfTool |
| `PINK` | `ic-pink` | **245, 100, 160** | `Color.rgb(245, 100, 160)` | ColorConverter |
| `GRAY` | `ic-gray` | **200, 200, 210** | `Color.rgb(200, 200, 210)` | *(unused by any builtin)* |

**Methods on `IconStyle`:**

| Method | Returns | Behavior |
|---|---|---|
| `getCssClass()` | `String` | The wrapper class, e.g. `"ic-teal"`. |
| `getColor()` | `javafx.scene.paint.Color` | The accent color used to fill the glyph **and** the `DropShadow` glow. |
| `static fromCssClass(String)` | `IconStyle` | Case-insensitive lookup by CSS class; returns `BLUE` if `null` / unknown. |

> The 7 RGB triples above are the **only** accent values in the product. Do not invent new icon
> colors. If you need a non-accent color, it must come from the token system in
> [05 Theme & Color System](05-theme-color-system.md).

### Built-in Tool Icon Mappings

The 11 builtin tools (registered in
[`BuiltinToolRegistrar`](../../FengYu/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java))
map onto `IconStyle` accents as follows. `getMdiIcon()` values are bare names.

| Tool (plugin class) | `getMdiIcon()` | `getIconStyle()` | RGB |
|---|---|---|---|
| `JsonFormatterPlugin` | `code-json` | `BLUE` | 99, 130, 255 |
| `MarkdownEditorPlugin` | `language-markdown` | `BLUE` | 99, 130, 255 |
| `EmailPlugin` | `email` | `BLUE` | 99, 130, 255 |
| `AiChatPlugin` | `robot-outline` | `PURPLE` | 160, 110, 255 |
| `Base64Plugin` | `base64` | `TEAL` | 40, 210, 140 |
| `ExcelSplitterPlugin` | `file-excel` | `TEAL` | 40, 210, 140 |
| `EmailArchivePlugin` | `email-check` | `TEAL` | 40, 210, 140 |
| `BrowserAutomatePlugin` | `web` | `TEAL` | 40, 210, 140 |
| `HashCalculatorPlugin` | `key-variant` | `AMBER` | 255, 185, 50 |
| `ColorConverterPlugin` | `palette` | `PINK` | 245, 100, 160 |
| `PdfToolPlugin` | `file-pdf-box` | `RED` | 255, 100, 100 |

**Style distribution:** `GRAY` is used by **no** builtin tool by default; new tools may adopt it for
neutral/utility tools. When adding a tool, prefer an `IconStyle` that is **underused** in the same
screen so the grid stays visually balanced — don't pile four more tools onto `BLUE`.

---

## 4. JavaFX Implementation Template

<a id="the-critical-ic--trap"></a>

### The critical `.ic-*` trap — read this first

The `.ic-blue`, `.ic-purple`, … `.ic-gray` rules in
[`shell.css`](../../FengYu/src/main/resources/css/shell.css) are **empty**:

```css
/* 图标配色 — 颜色注入到 Text 节点上, glow 由 Java 代码通过 DropShadow 设置 */
.ic-blue   { }
.ic-purple { }
.ic-teal   { }
.ic-amber  { }
.ic-red    { }
.ic-pink   { }
.ic-gray   { }
```

They carry **no color, no background, no border**. The class is applied to the icon *wrapper* purely
so the rest of the app (and screenshots) can *name* which style a tile uses — it is **not** a color
hook. The actual color and glow come from **Java**:

```
WRONG mental model:
   add ".ic-blue" to the wrapper  ──▶  expect a blue icon        ❌  (empty rule → nothing)

CORRECT reality:
   icon.setFill(style.getColor())  ──▶  blue glyph               ✅
   glow.setColor(style.getColor().deriveColor(0,1,1,0.75))       ✅  (the glow)
   wrapper.getStyleClass().add(style.getCssClass())              ◻  (labeling only)
```

**An AI that sets `.ic-blue` expecting blue will get a white icon.** Always fill the `Text` from
Java via `IconStyle.getColor()` for tool icons.

### 4.1 Create a generic, theme-following icon (nav / toolbar / status)

For icons that should recolor with the theme (the common case for non-tool UI), use the
`.sk-fill-2` / `.sk-fill-3` **utility styleclasses** from
[`fengyu-common.css`](../../FengYu-Api/src/main/resources/css/fengyu-common.css):

```css
/* fengyu-common.css */
.sk-fill-2 { -fx-fill: -sk-text-secondary; }   /* Text/Shape fill (secondary) */
.sk-fill-3 { -fx-fill: -sk-text-disabled; }    /* Text/Shape fill (disabled)  */
```

```java
import fan.summer.fengyu.api.MdiIconUtil;
import javafx.scene.text.Text;

// secondary-tone icon that follows dark/light theme automatically
Text navIcon = MdiIconUtil.createIcon("folder-open", 18.0);
navIcon.getStyleClass().add("sk-fill-2");   // resolves -sk-text-secondary via CSS
```

> **Why a styleclass and not inline `setStyle`?** `setStyle("-fx-fill: -sk-text-secondary;")` does
> **not** work — inline style strings cannot resolve looked-up color variables (see
> [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle)). The `.sk-fill-*`
> styleclass is evaluated by the CSS engine against the node's scene, so it resolves the token
> *and* follows theme switches. Use it.

### 4.2 Set a literal / accent color directly (rare)

If you must use a one-off color that is **not** a token and **not** an `IconStyle` (discouraged),
set the fill on the returned `Text` node from Java — this *does* work for literal colors:

```java
Text warn = MdiIconUtil.createIcon("alert", 16.0);
warn.setFill(javafx.scene.paint.Color.web("#FFB320"));   // literal hex is fine from Java
// OR use the 3-arg form, which appends to the default white fill:
Text warn2 = MdiIconUtil.createIcon("alert", 16.0, "-fx-fill: #FFB320;");
```

Prefer `IconStyle.AMBER.getColor()` over a literal when the meaning is "amber accent".

### 4.3 Render a tool-card icon (the canonical pattern)

This is exactly what [`ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java)
does. The plugin supplies the *name* (`getMdiIcon()`) and the *style* (`getIconStyle()`); the host
does the fill + glow:

```java
import fan.summer.fengyu.api.IconStyle;
import fan.summer.fengyu.api.MdiIconUtil;
import fan.summer.fengyu.api.SwissKitJPlugin;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

SwissKitJPlugin plugin = /* ... */;

// 1. read the accent color the plugin chose
Color iconColor = plugin.getIconStyle().getColor();          // e.g. TEAL → rgb(40,210,140)

// 2. render the glyph at 45px, fill it from Java (NOT from .ic-* CSS!)
Text iconText = MdiIconUtil.createIcon(plugin.getMdiIcon(), 45);   // bare name, no "mdi-"
iconText.setStyle(String.format("-fx-fill: rgba(%d,%d,%d,1.0);",
        (int)(iconColor.getRed()   * 255),
        (int)(iconColor.getGreen() * 255),
        (int)(iconColor.getBlue()  * 255)));
//   equivalently:  iconText.setFill(iconColor);

// 3. add the glow — same accent, slightly transparent
DropShadow glow = new DropShadow();
glow.setColor(iconColor.deriveColor(0, 1, 1, 0.75));   // alpha 0.75
glow.setRadius(12);
glow.setSpread(0.15);
iconText.setEffect(glow);

// 4. wrapper gets BOTH classes: the sizing class (real) + the .ic-* class (label only)
StackPane iconWrap = new StackPane(iconText);
iconWrap.getStyleClass().addAll("tool-icon-wrap", plugin.getIconStyle().getCssClass());
iconWrap.setPrefSize(48, 48);
iconWrap.setMinSize(48, 48);
```

The glow parameters (`radius 12`, `spread 0.15`, `deriveColor(0,1,1,0.75)`) are the established
house values — reuse them verbatim so all tool icons glow identically.

### 4.4 Authoring a plugin's icon contract

In your plugin, just return the two values — do **not** build or color the node yourself:

```java
@Override public String getMdiIcon()    { return "file-excel"; }   // bare name, no "mdi-"
@Override public IconStyle getIconStyle() { return IconStyle.TEAL; } // host does the rest
```

If you override `getIconStyle()`, return one of the seven `IconStyle` values — never a hand-built
color. The `default` is `IconStyle.BLUE`.

### 4.5 Looking up an MDI name (verifying it exists)

Before shipping a new `getMdiIcon()` string, confirm the key exists in the codemap:

```java
// returns the "star" codepoint if the name is unknown — so check for "star" mismatch,
// or better, grep the properties file:
String cp = MdiIconUtil.getCodepoint("file-excel");  // "\uDB80\uDE1B"
```

```bash
# confirm a name is in the bundled codemap (7448 entries)
grep -nE '^file-excel=' FengYu-Api/src/main/resources/fonts/mdi-codemap.properties
# → file-excel=\uDB80\uDE1B
```

Browse names visually at the [MDI library](https://pictogrammers.com/library/mdi/), then strip the
`mdi-` prefix before returning the name.

---

## 5. AI Development Checklist

When generating icon-related code, verify **every** item:

- [ ] **Name format** — `getMdiIcon()` / `createIcon(name, …)` returns/uses the bare MDI name with
      **no** `mdi-` prefix (`"file-excel"`, not `"mdi-file-excel"`).
- [ ] **Name exists** — the name is present in
      [`mdi-codemap.properties`](../../FengYu-Api/src/main/resources/fonts/mdi-codemap.properties);
      unknown names silently fall back to `star`.
- [ ] **Size on scale** — the size is one of `16 / 18 / 20 / 24 / 32 / 45 / 50` (see
      [Size Scale](#size-scale)); tool-card glyph = 45, container = 48.
- [ ] **Tool icon color from Java, not CSS** — filled via
      `plugin.getIconStyle().getColor()` (or `icon.setFill(IconStyle.X.getColor())`). The `.ic-*`
      class is **labeling only** (empty rule).
- [ ] **Generic UI icon follows theme** — non-tool icons use the `.sk-fill-2` / `.sk-fill-3`
      styleclass, **not** inline `setStyle("-fx-fill: -sk-text-secondary;")` (which cannot resolve
      looked-up colors).
- [ ] **`IconStyle` chosen for meaning + balance** — one of the 7 values; prefer an underused
      accent on the target screen; `GRAY` is available for neutral tools.
- [ ] **No emoji as icons** — emoji are a different font with different metrics; use an MDI glyph.
- [ ] **No second icon library** — no FontAwesome / SF Symbols / SVG icon paths; extend with
      `putIcon()` if a glyph is missing.
- [ ] **Icon is labeled** — an icon-only control has an accessible name / tooltip; tool cards always
      pair glyph with `.tool-name`.
- [ ] **Glow uses house values** — if rendering a tool tile, `DropShadow` radius `12`, spread
      `0.15`, color `style.getColor().deriveColor(0,1,1,0.75)`.

---

## 6. Anti-patterns

### AP1 — Returning/using an MDI name with the `mdi-` prefix

```java
@Override public String getMdiIcon() { return "mdi-file-excel"; }   // ❌
```

`"mdi-file-excel"` is **not** a key in `mdi-codemap.properties` (keys are bare). It silently falls
back to `star`, so the tool card shows a generic star with no compile error. **Always strip the
prefix:** `"file-excel"`. (See [02 AP6](02-javafx-implementation.md#ap6--returning-an-mdi-name-with-the-mdi--prefix).)

### AP2 — Relying on `.ic-*` CSS to color the icon

```java
// ❌ expects the wrapper class to paint the glyph blue
iconWrap.getStyleClass().addAll("tool-icon-wrap", "ic-blue");
// (never calls setFill / setStyle on the Text)
```

The `.ic-blue` rule is **empty** (`{ }`). The glyph stays its default **white**. The host fills the
`Text` from Java via `getIconStyle().getColor()` — see [the trap](#the-critical-ic--trap). If you
are rendering a tool icon yourself, you must do the `setFill`/`setStyle` step.

### AP3 — Coloring a generic icon with inline `setStyle` referencing a token

```java
Text t = MdiIconUtil.createIcon("folder-open", 18.0);
t.setStyle("-fx-fill: -sk-text-secondary;");   // ❌ does NOT resolve
```

Inline `setStyle` strings cannot resolve looked-up color variables, so `-sk-text-secondary` is
dropped and the fill is unchanged (see [05 §P5](05-theme-color-system.md#p5--colors-live-in-css-never-in-setstyle)).
Use the `.sk-fill-2` styleclass instead: `t.getStyleClass().add("sk-fill-2");`.
(Literal hex in `setStyle` *does* work — AP3 is specifically about token references.)

### AP4 — Decorative / unlabeled icon-only control

An icon with no text label and no `accessibleText`/tooltip is invisible to assistive tech and
ambiguous to sighted users. Tool cards always include `.tool-name`; if you build an icon-only
button, set a tooltip and accessible text.

### AP5 — Mixing in a second icon font / emoji

Pulling in FontAwesome, SF Symbols, an SVG icon set, or using emoji (`📧`) as an icon breaks stroke
and grid consistency and adds font weight. Use MDI exclusively; register missing glyphs with
`MdiIconUtil.putIcon()`.

### AP6 — Inventing a new icon color

Hardcoding a brand-new RGB for a tool icon (e.g. `Color.rgb(10, 200, 255)`) fragments the palette.
Tool accents come only from `IconStyle`; any other color must be a token from
[05 Theme & Color System](05-theme-color-system.md).

### AP7 — Guessing the size

Using `createIcon(name, 13)` or `createIcon(name, 50)` in a tool card breaks the visual grid. Sizes
are on the [Size Scale](#size-scale); the tool-card glyph is **45 px** in a **48 px** wrap, the
detail hero is **50 px**, nav icons are **16–18 px**.

---

## 7. References

### Source files (canonical)

| What | Path |
|---|---|
| MDI renderer (`createIcon`, `getCodepoint`, `getFont`, `putIcon`) | [`FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java`](../../FengYu-Api/src/main/java/fan/summer/api/MdiIconUtil.java) |
| Icon accent styles (`BLUE`…`GRAY`, CSS class, color, `fromCssClass`) | [`FengYu-Api/src/main/java/fan/summer/api/IconStyle.java`](../../FengYu-Api/src/main/java/fan/summer/api/IconStyle.java) |
| Plugin icon contract (`getMdiIcon`, `getIconStyle`) | [`FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`](../../FengYu-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java) |
| Codepoint map (7 448 entries, bare-name keys) | [`FengYu-Api/src/main/resources/fonts/mdi-codemap.properties`](../../FengYu-Api/src/main/resources/fonts/mdi-codemap.properties) |
| Webfont binary | `/fonts/materialdesignicons-webfont.ttf` (classpath, API module) |
| Empty `.ic-*` rules + `.tool-icon-wrap` (48px) + `.nav-item-icon` (18px) | [`FengYu/src/main/resources/css/shell.css`](../../FengYu/src/main/resources/css/shell.css) |
| `.sk-fill-2` / `.sk-fill-3` utility classes | [`FengYu-Api/src/main/resources/css/fengyu-common.css`](../../FengYu-Api/src/main/resources/css/fengyu-common.css) |
| Canonical tool-icon renderer (45px glyph + glow) | [`FengYu/src/main/java/fan/summer/ui/content/ToolCard.java`](../../FengYu/src/main/java/fan/summer/ui/content/ToolCard.java) |
| Detail-panel hero renderer (50px glyph + glow) | [`FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java`](../../FengYu/src/main/java/fan/summer/ui/content/DetailPanel.java) |
| 11 builtin tool icon mappings | [`FengYu/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java`](../../FengYu/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java) |

### Design baseline

| What | Path |
|---|---|
| Icon catalog (browse names visually) | [Material Design Icons — Pictogrammers](https://pictogrammers.com/library/mdi/) |

### Sibling UI design docs

| Doc | Link |
|---|---|
| 02 — JavaFX Implementation (§4.2 icon crash course, AP6 `mdi-` prefix) | [02-javafx-implementation.md](02-javafx-implementation.md) |
| 03 — Component Library (links here for icon usage) | [03-component-library.md](03-component-library.md) |
| 05 — Theme & Color System (`-sk-*` tokens, `.sk-fill-*`, P5 inline-style rule) | [05-theme-color-system.md](05-theme-color-system.md) |
