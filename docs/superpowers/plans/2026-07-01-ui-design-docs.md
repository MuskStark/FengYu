# SwissKit UI Design Documentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a bilingual (English + Chinese), code-accurate UI design system doc set of 8 documents under `docs/ui-design/` + `docs/zh/ui-design/`, anchored to the real SwissKitJ JavaFX codebase, that can guide an AI (or human) to build UI consistent with the JetBrains IDEA 2025 New UI language already implemented in v3.2.0.

**Architecture:** Each document is a single deep Markdown file (target 800–2500 lines), following a fixed 7-section structure (Overview → Principles → Spec tables → JavaFX templates → AI checklist → Anti-patterns → References). A "single source of truth + cross-link" convention prevents duplication: tokens live in 05, CSS naming in 02, icons in 06, animations in 07. Every class name, token value, and JavaFX signature cited must be verifiable against the real source via grep — each task ends with a consistency-verification step (the "test cycle" for documentation).

**Tech Stack:** Markdown rendered by docsify 4 (vue theme, no mermaid). Diagrams use ASCII art (`┌──┐`) consistent with existing `docs/architecture.md`. Fonts use `-fx-font-family` stack `"SF Pro Text", "Inter", "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`. All JavaFX code examples target JavaFX 21.

## Global Constraints

These apply to EVERY document and every task:

1. **Bilingual**: every doc has an English version under `docs/ui-design/` and a Chinese mirror under `docs/zh/ui-design/` with identical structure and content (translate prose; keep code, class names, token names, file paths verbatim).
2. **No mermaid**: diagrams are ASCII art + markdown tables + fenced code blocks only.
3. **Exact CSS class names** (verified against source — do NOT invent names):
   - Field is `.sk-field` (NOT `.sk-text-field`). Focus border switches `-sk-border` → `-sk-accent`.
   - Buttons are `.sk-btn-primary` and `.sk-btn-secondary` only (there is NO `.sk-btn` bare class).
   - Notifications are `.sk-notif-*` (`.sk-notif-root`, `.sk-notif-info`, `.sk-notif-success`, `.sk-notif-warning`, `.sk-notif-error`). There is NO `.sk-badge` and NO `.sk-notification`.
   - Tabs are nested selectors `.sk-tab-pane .tab` (no standalone `.sk-tab`).
   - IconStyle accent classes `.ic-blue/.ic-purple/.ic-teal/.ic-amber/.ic-red/.ic-pink/.ic-gray` are EMPTY CSS rules — color/glow come from Java (`IconStyle.getColor()` + `DropShadow`), not CSS.
4. **Exact token values** (from `zhiflow-common.css` `.theme-dark`/`.theme-light`):

   | Token | Dark | Light |
   |-------|------|-------|
   | `-sk-bg` | `#1E1E1E` | `#FFFFFF` |
   | `-sk-bg-elevated` | `#2B2B2B` | `#F7F8FA` |
   | `-sk-bg-hover` | `#363636` | `#EBECEF` |
   | `-sk-bg-selected` | `#393B40` | `#DFE1E5` |
   | `-sk-border` | `#3C3F41` | `#DADCE0` |
   | `-sk-border-strong` | `#555555` | `#C9CDD3` |
   | `-sk-text` | `#D0D0D0` | `#1E1E1E` |
   | `-sk-text-secondary` | `#9AA0A6` | `#5A5D60` |
   | `-sk-text-disabled` | `#6B6F73` | `#A0A4A8` |
   | `-sk-accent` | `#3574F0` | `#3574F0` |
   | `-sk-accent-soft` | `rgba(53,116,240,0.18)` | `rgba(53,116,240,0.14)` |
   | `-sk-success` | `#5BB065` | `#3C914A` |
   | `-sk-warning` | `#F0A732` | `#C2751C` |
   | `-sk-danger` | `#F75464` | `#E53935` |

5. **Exact JavaFX signatures** (do not paraphrase):
   - Plugin contract `SwissKitJPlugin` (16 methods, 8 required / 8 default): `getId()`, `getName()`, `getDescription()`, `getCategory()`, `getVersion()`, `getMdiIcon()`, `createView()`, plus defaults `getIconStyle()`→`IconStyle.BLUE`, `getType()`→`ToolType.PLUGIN`, `onActivate/onDeactivate/onUnload/onBackground/onForeground`, `hasRunningTasks()`→false, `aiTools()`→`List.of()`.
   - `ThemeService`: `enum Theme { DARK, LIGHT }` (nested); `static Theme current()`, `static void set(Theme)`, `static void registerScene(Scene)`, `static void onChange(Consumer<Theme>)`, `static void removeListener(Consumer<Theme>)`. All FX-thread-only.
   - Plugin-facing theme helper: `Themes.applyTo(scene)` (NOT `ThemeService` for plugin code) and `Themes.COMMON_CSS = "/css/zhiflow-common.css"`.
   - Icons: `MdiIconUtil.createIcon(String name, double size)` and `createIcon(String name, double size, String extraStyle)` → returns `javafx.scene.text.Text`.
   - `IconStyle` enum: `BLUE/PURPLE/TEAL/AMBER/RED/PINK/GRAY` each with `getCssClass()` (e.g. `ic-blue`) and `getColor()` (`javafx.scene.paint.Color`). `fromCssClass(String)` case-insensitive, default BLUE.
   - `ToolCategory` enum: `DEV("dev")/TEXT("text")/IMAGE("image")/NET("net")/OTHER("other")`, each with `getId()`/`getI18nKey()`/`fromId(String)`.
   - `ToolType` enum: `BUILTIN("builtin")/PLUGIN("plugin")`, each with `getId()`/`isBuiltin()`/`isPlugin()`.
6. **Builtin tools (11)** for citation as reference implementations: AiChat (`builtin.ai-chat`, OTHER, `robot-outline`, PURPLE), JsonFormatter (`builtin.json-formatter`, DEV, `code-json`, BLUE), Base64 (`builtin.base64`, DEV, `base64`, TEAL), HashCalculator (`builtin.hash`, DEV, `key-variant`, AMBER), ExcelSplitter (`fan.summer.buildin.excelsplitter`, OTHER, `file-excel`, TEAL), ColorConverter (`builtin.color`, IMAGE, `palette`, PINK), MarkdownEditor (`builtin.markdown`, TEXT, `language-markdown`, BLUE), Email (`builtin.email`, NET, `email`, BLUE), EmailArchive (`fan.summer.buildin.email-archive`, NET, `email-check`, TEAL), PdfTool (`builtin.pdf-tool`, OTHER, `file-pdf-box`, RED), BrowserAutomate (`fan.summer.buildin.browser-automate`, DEV, `web`, TEAL).
7. **Real animations** to cite (file:line in `SwissKit/src/main/java/fan/summer/`): MainWindow entry fade 250ms (`ui/MainWindow.java:311`); StatusBar pulse dot 2500ms infinite (`ui/MainWindow.java:147`); clock Timeline 1s tick (`ui/MainWindow.java:321`); ContentArea staggered card entry 240ms +35ms stagger (`ui/content/ContentArea.java:335`); showPage cross-fade 220ms in/180ms out (`ui/content/ContentArea.java:399`); grid slide-in 280ms (`ui/content/ContentArea.java:422`); ToolCard entry 280ms custom interpolator (`ui/content/ToolCard.java:163`); ToolCard bg-running pulse 2500ms (`ui/content/ToolCard.java:106`); ToolCard favorite-star pop 150ms EASE_OUT (`ui/content/ToolCard.java:128`); ToolCard hover scale 150ms / click scale 100ms (`ui/content/ToolCard.java:138`,`:156`); DetailPanel slide-in 300ms / slide-out 250ms Timeline SPLINE(0.4,0,0.2,1) (`ui/content/DetailPanel.java:341`,`:361`); Sidebar active-item scale pop 160ms SPLINE(0.34,0.9,0.64,1) (`ui/sidebar/Sidebar.java:418`); AiChat webview blink 800ms (`buildintool/ai/AiChatPlugin.java:747`); Email ToggleSwitch thumb slide 150ms (`buildintool/email/ToggleSwitch.java:54`).
8. **Source-of-truth files** every doc must cross-link:
   - Tokens: `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css`
   - Shell CSS: `SwissKit/src/main/resources/css/shell.css`
   - Builtin CSS: `SwissKit/src/main/resources/css/builtin.css`
   - Theme code: `SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java`, `Themes.java`
   - Plugin contract: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`
   - Enums: `SwissKitJ-Api/src/main/java/fan/summer/api/IconStyle.java`, `ToolCategory.java`, `ToolType.java`
   - Icons: `SwissKitJ-Api/src/main/java/fan/summer/api/MdiIconUtil.java`
   - Layout: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`, `ui/MainWindow.java`, `ui/sidebar/Sidebar.java`, `ui/content/ContentArea.java`, `ui/content/ToolCard.java`, `ui/content/DetailPanel.java`
   - Registrar: `SwissKit/src/main/java/fan/summer/registrar/BuiltinToolRegistrar.java`
   - Existing plugin UI guide to stay consistent with: `docs/plugins/ui.md`
   - Authoritative New UI spec: `docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`
9. **7-section structure** every component/spec doc must follow: ① Overview, ② Design Principles, ③ Spec tables (tokens/classes/sizes), ④ JavaFX implementation template, ⑤ AI development checklist, ⑥ Anti-patterns, ⑦ References.

---

## File Structure

```
docs/ui-design/                       docs/zh/ui-design/
├── README.md            (index)      ├── README.md
├── 01-design-system.md               ├── 01-design-system.md
├── 02-javafx-implementation.md       ├── 02-javafx-implementation.md
├── 03-component-library.md           ├── 03-component-library.md
├── 04-interaction-guidelines.md      ├── 04-interaction-guidelines.md
├── 05-theme-color-system.md          ├── 05-theme-color-system.md
├── 06-icon-system.md                 ├── 06-icon-system.md
├── 07-animation-guidelines.md        ├── 07-animation-guidelines.md
└── 08-accessibility-guide.md         └── 08-accessibility-guide.md
```

Modified files:
- `docs/_sidebar.md` — add "UI Design" group linking to `ui-design/`.
- `docs/zh/_sidebar.md` — add "UI 设计" group linking to `ui-design/`.

**Ordering rationale (dependency-driven):** Tokens (05) are the foundation everything references, so written first. Naming conventions (02) follow. Icons (06) are referenced by components (03). Components (03) come after 02/05/06. Global system (01) synthesizes the above. Animation (07) and interaction (04) reference components. Accessibility (08) references tokens. README + sidebar last to link everything.

---

## Task 1: 05 Theme & Color System (English + Chinese)

**Why first:** The authoritative token table. Every later doc links here for exact values.

**Files:**
- Create: `docs/ui-design/05-theme-color-system.md`
- Create: `docs/zh/ui-design/05-theme-color-system.md`
- Reference (do not modify): `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css`, `SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java`, `SwissKitJ-Api/src/main/java/fan/summer/api/theme/Themes.java`, `SwissKit/src/main/java/fan/summer/ai/util/MarkdownRenderer.java`

**Interfaces:**
- Consumes: spec `docs/superpowers/specs/2026-07-01-ui-design-docs-design.md` §5.05; token values from Global Constraint #4.
- Produces: the canonical "Token Reference Table" anchor `#token-reference-table` that tasks 2,3,4,6,7,8 link to.

- [ ] **Step 1: Write the English doc `docs/ui-design/05-theme-color-system.md`**

  Full 7-section structure. Include:
  - **① Overview**: this doc is the single source of truth for `-sk-*` color tokens; dual-theme (dark/light); token = JavaFX looked-up color resolved via `.theme-dark`/`.theme-light` on scene root.
  - **② Design Principles**: neutral-gray dominant (IDEA New UI core); accent `#3574F0` used sparingly (key actions + selected-state indicator only); selected state = `-sk-bg-selected` neutral fill + 3px left accent strip (NOT blue flood); status colors strictly semantic; colors never hardcoded in `setStyle()` (won't re-resolve on theme switch).
  - **③ Spec tables** — the **Token Reference Table** (`id="token-reference-table"`): all 14 tokens with dark/light hex, purpose, "use on" guidance. Add a second table: "Token → CSS utility class" mapping (e.g. `-sk-text`→`.sk-t1`, `-sk-text-secondary`→`.sk-t2`/`.sk-fill-2`, `-sk-bg-elevated`→`.sk-surface`, `-sk-bg-hover`→`.sk-surface-soft`, `-sk-border`→`.sk-outlined`, `-sk-border-strong`→`.sk-outlined-strong`). Add a contrast matrix table: which text token on which bg token passes WCAG AA (4.5:1) in each theme — cite 08 for full a11y.
  - **④ JavaFX template**: show the theme-switching lifecycle — how `ThemeService.registerScene(scene)` loads `zhiflow-common.css` and stamps the class; how `ThemeService.set(Theme.LIGHT)` swaps the class (no reload, no flicker); the `onChange(Consumer<Theme>)` listener pattern; the WebView sync pattern from `MarkdownRenderer` (separate embedded dark/light CSS, re-render on change). Show the plugin-facing helper `Themes.applyTo(scene)` for standalone windows. Include copyable code blocks.
  - **⑤ AI checklist**: "When generating themed UI you MUST: use `-sk-*` tokens or `.sk-t*`/`.sk-surface*` utility classes, never hex in setStyle(); for standalone Stage call `Themes.applyTo(scene)`; register `ThemeService.onChange` for custom rendering (e.g. WebView/canvas); persist choice via key `"theme"` = `"dark"`/`"light"`."
  - **⑥ Anti-patterns**: `setStyle("-fx-background-color: #2B2B2B")` (breaks on theme switch — use `-sk-bg-elevated`); using `-sk-accent` as a large background fill (too loud — neutral bg + accent only for action/selection); inventing new token names not in the table.
  - **⑦ References**: link to `zhiflow-common.css`, `ThemeService.java`, `Themes.java`, `MarkdownRenderer.java`, the New UI spec, and sibling docs 01/02/08.

- [ ] **Step 2: Write the Chinese mirror `docs/zh/ui-design/05-theme-color-system.md`**

  Same structure, translated prose. Keep all token names (`-sk-bg`), hex values, class names (`.sk-t1`), file paths, code blocks verbatim (untranslated).

- [ ] **Step 3: Verify token values match source**

  Run:
  ```bash
  grep -nE '\-sk-(bg|bg-elevated|bg-hover|bg-selected|border|border-strong|text|text-secondary|text-disabled|accent|accent-soft|success|warning|danger):' SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
  ```
  Expected: each of the 14 tokens appears under both `.theme-dark` and `.theme-light` with the exact hex values in Global Constraint #4. If any value in the doc differs from this grep output, fix the doc.

- [ ] **Step 4: Verify ThemeService method names match source**

  Run:
  ```bash
  grep -nE 'public (static )?(void|Theme) (current|set|registerScene|onChange|removeListener)' SwissKitJ-Api/src/main/java/fan/summer/api/theme/ThemeService.java
  ```
  Expected: 5 method signatures matching Global Constraint #5. Fix doc if mismatched.

- [ ] **Step 5: Commit**

  ```bash
  git add docs/ui-design/05-theme-color-system.md docs/zh/ui-design/05-theme-color-system.md
  git commit -m "📝 docs(ui): 05 Theme & Color System (bilingual)"
  ```

---

## Task 2: 02 JavaFX Implementation Guide (English + Chinese)

**Files:**
- Create: `docs/ui-design/02-javafx-implementation.md`
- Create: `docs/zh/ui-design/02-javafx-implementation.md`
- Reference: `SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java`, `IconStyle.java`, `ToolCategory.java`, `ToolType.java`, `theme/Themes.java`; `docs/plugins/ui.md`

**Interfaces:**
- Consumes: plugin contract from Global Constraint #5; theme linking from Task 1 (`05-theme-color-system.md`).
- Produces: the canonical "CSS Class Naming Convention" anchor (`#css-naming`) and "Plugin Skeleton Template" anchor (`#plugin-skeleton`) that Task 3 links to.

- [ ] **Step 1: Write English `docs/ui-design/02-javafx-implementation.md`**

  7 sections. Content:
  - **① Overview**: tech-stack premise — JavaFX 21, UI built in code (NO FXML), CSS-themed via looked-up colors. This doc is the dev playbook for turning design into code.
  - **② Design Principles**: code-is-the-UI; colors via tokens/classes only; sizes/padding may be inline; one cached view per plugin; CSS class naming follows `sk-` prefix BEM-lite.
  - **③ Spec tables**:
    - `SwissKitJPlugin` method table: all 16 methods, exact signature, required/default, one-line purpose, return type. (Required: `getId/getName/getDescription/getCategory/getVersion/getMdiIcon/createView`. Default: `getIconStyle→BLUE`, `getType→PLUGIN`, `onActivate/onDeactivate/onUnload/onBackground/onForeground`, `hasRunningTasks→false`, `aiTools→List.of()`.)
    - **CSS Class Naming Convention** (`id="css-naming"`): `.sk-` prefix for shared components (from common.css); shell classes unprefixed (`nav-item`, `tool-card`, `search-bar`, `statusbar`); the `.glass-*`→`.sk-*` v3.2.0 breaking migration (cite the New UI spec §7); status-color modifier convention.
    - Layout-container selection table: GridPane (forms), VBox/HBox (linear stacks), BorderPane (top/center/bottom regions), FlowPane (wrapping card grids), StackPane (overlay/page switching), ScrollPane (scrollable content with `.content-scroll` for thin scrollbar).
  - **④ JavaFX template** — **Plugin Skeleton** (`id="plugin-skeleton"`): a complete, compilable `SwissKitJPlugin` implementation skeleton using `{{base-package}}`/`{{Name}}`/`{{slug}}` placeholders (consistent with `docs/plugins/ui.md`). Show: package + imports, all 8 required methods returning realistic values (`getId`→`"builtin.<slug>"`, `getName`→`I18n.get("builtin.<slug>.name")`, `getCategory`→`ToolCategory.X`, `getMdiIcon`→`"<mdi-name>"` without `mdi` prefix, `getIconStyle`→`IconStyle.X`, `getType`→`ToolType.BUILTIN`), `createView()` building a GridPane, lifecycle hooks, `aiTools()`. Also show: how to render an icon (`MdiIconUtil.createIcon(name, size)`), how to theme a standalone Stage (`Themes.applyTo(scene)`), the I18n patterns from `ui.md` (`I18n.bind`, `I18n.get`, `I18n.addListener`), and the three layout pitfalls from `ui.md` (ScrollPane sizing, HBox fill with `setHgrow`+`setMaxWidth(MAX_VALUE)`, StackPane page-switch toggling `visible`+`managed`).
  - **⑤ AI checklist**: "When generating a plugin you MUST: implement `SwissKitJPlugin` directly (not a wrapper); cache the `createView()` result; return MDI name without `mdi-` prefix; override `getType()` for builtins; never load `zhiflow-common.css` yourself (`Themes.applyTo`/`ThemeService.registerScene` does it); never set inline hex colors."
  - **⑥ Anti-patterns**: separate `*PluginUi` wrapper class (all 11 builtins implement directly in one class); `setPrefWidth(Double.MAX_VALUE)` on HBox children (use `setHgrow`+`setMaxWidth`); loading common CSS manually; calling `ThemeService` internals from plugin code (use `Themes.applyTo`).
  - **⑦ References**: link to `SwissKitJPlugin.java`, `IconStyle.java`, `ToolCategory.java`, `ToolType.java`, `MdiIconUtil.java`, `Themes.java`, `docs/plugins/ui.md`, sibling 05/06.

- [ ] **Step 2: Write Chinese mirror `docs/zh/ui-design/02-javafx-implementation.md`** (translated prose, verbatim code/paths).

- [ ] **Step 3: Verify plugin contract methods**

  Run:
  ```bash
  grep -nE '(default )?(String|Node|ToolCategory|IconStyle|ToolType|boolean|List<AiTool>|void) (getId|getName|getDescription|getCategory|getVersion|getMdiIcon|getIconStyle|getType|createView|onActivate|onDeactivate|onUnload|onBackground|onForeground|hasRunningTasks|aiTools)\b' SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java
  ```
  Expected: 16 method declarations. Fix doc if any method/signature in the doc is not found.

- [ ] **Step 4: Verify `.glass-*`→`.sk-*` migration exists in New UI spec**

  Run:
  ```bash
  grep -niE 'glass.*sk-|sk-.*glass' docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md | head
  ```
  Expected: at least the mention of the rename. If absent, soften the doc to "see New UI spec §7" rather than asserting a specific mapping.

- [ ] **Step 5: Commit**

  ```bash
  git add docs/ui-design/02-javafx-implementation.md docs/zh/ui-design/02-javafx-implementation.md
  git commit -m "📝 docs(ui): 02 JavaFX Implementation Guide (bilingual)"
  ```

---

## Task 3: 06 Icon System (English + Chinese)

**Files:**
- Create: `docs/ui-design/06-icon-system.md`
- Create: `docs/zh/ui-design/06-icon-system.md`
- Reference: `SwissKitJ-Api/src/main/java/fan/summer/api/MdiIconUtil.java`, `IconStyle.java`, `SwissKit/src/main/resources/fonts/mdi-codemap.properties`, `shell.css` (`.ic-*` classes), `BuiltinToolRegistrar.java`

**Interfaces:**
- Consumes: `IconStyle`/`MdiIconUtil` from Global Constraint #5; the 11 builtin icon mappings from Global Constraint #6.
- Produces: the "Icon Reference" anchor (`#icon-reference`) that Task 3 (components) links to.

- [ ] **Step 1: Write English `docs/ui-design/06-icon-system.md`**

  7 sections:
  - **① Overview**: icon library = Material Design Icons webfont; codepoints in `mdi-codemap.properties`; accessed via `MdiIconUtil`.
  - **② Principles**: line/fill style consistency; visual weight balance; icons convey meaning (never decorative-only); color follows `-sk-text` by default, `IconStyle` accent for emphasis; never mix icon libraries; never use emoji as icons.
  - **③ Spec tables**:
    - `MdiIconUtil` API: `createIcon(name, size)` → `Text`; `createIcon(name, size, extraStyle)`; `getCodepoint(name)`; `getFont(size)`. Note fallback to `"star"` on unknown name.
    - Size scale: 16px (status/inline), 18px (nav-item icon), 20px (small UI), 24px (standard/card icon), 32px (large). Cite `nav-item-icon` 18px and `tool-icon-wrap` 48px from shell.css.
    - `IconStyle` table: 7 values with cssClass (`ic-blue`…), RGB color, and the builtin tools that use each (BLUE→Json/Markdown/Email, PURPLE→AiChat, TEAL→Base64/Excel/EmailArchive/Browser, AMBER→Hash, PINK→Color, RED→Pdf, GRAY→none by default).
    - Note: `.ic-*` are EMPTY CSS rules; color + `DropShadow` glow are applied from Java via `IconStyle.getColor()`.
  - **④ JavaFX template**: copyable — how to create a default icon (`Text t = MdiIconUtil.createIcon("file-excel", 24)`), set color via `-sk-*` (`t.setStyle("-fx-fill: -sk-text-secondary;")` — note inline style CAN reference looked-up colors only when set as a CSS variable string evaluated by the node... actually document the correct pattern: use `.sk-fill-2` styleclass OR set fill from Java `t.setFill(IconStyle.TEAL.getColor())`). Show tool-card icon rendering: `MdiIconUtil.createIcon(plugin.getMdiIcon(), 24)` inside `.tool-icon-wrap` with the plugin's `IconStyle` color + glow. Show how to look up an MDI name from `mdi-codemap.properties`.
  - **⑤ AI checklist**: "MUST: return MDI name without `mdi-`/`mdi-` prefix in `getMdiIcon()`; pick an `IconStyle` for emphasis (color from Java, not CSS); size per scale; never emoji; verify the name exists in mdi-codemap.properties before using."
  - **⑥ Anti-patterns**: prefixing `mdi-` in `getMdiIcon()` (it breaks); relying on `.ic-*` CSS to set color (empty rules — use Java color); decorative icons without labels (a11y).
  - **⑦ References**: `MdiIconUtil.java`, `IconStyle.java`, `mdi-codemap.properties`, `shell.css`, `BuiltinToolRegistrar.java`, sibling 03.

- [ ] **Step 2: Write Chinese mirror.**

- [ ] **Step 3: Verify MdiIconUtil signatures**

  Run:
  ```bash
  grep -nE 'public static (Text|String|Font) (createIcon|getCodepoint|getFont)' SwissKitJ-Api/src/main/java/fan/summer/api/MdiIconUtil.java
  ```
  Expected: 4 signatures. Fix doc if mismatched.

- [ ] **Step 4: Verify IconStyle values**

  Run:
  ```bash
  grep -nE '^\s+(BLUE|PURPLE|TEAL|AMBER|RED|PINK|GRAY)\(' SwissKitJ-Api/src/main/java/fan/summer/api/IconStyle.java
  ```
  Expected: 7 enum constants. Fix doc if mismatched.

- [ ] **Step 5: Commit**

  ```bash
  git add docs/ui-design/06-icon-system.md docs/zh/ui-design/06-icon-system.md
  git commit -m "📝 docs(ui): 06 Icon System (bilingual)"
  ```

---

## Task 4: 03 Component Library (English + Chinese)

**Files:**
- Create: `docs/ui-design/03-component-library.md`
- Create: `docs/zh/ui-design/03-component-library.md`
- Reference: `SwissKitJ-Api/src/main/resources/css/zhiflow-common.css`, `SwissKit/src/main/resources/css/shell.css`, `SwissKit/src/main/resources/css/builtin.css`; UI classes in `ui/sidebar/Sidebar.java`, `ui/content/ContentArea.java`, `ui/content/ToolCard.java`, `ui/content/DetailPanel.java`, `ui/MainWindow.java`

**Interfaces:**
- Consumes: tokens from Task 1; naming from Task 2; icons from Task 3; animations from Task 7 (link forward — animations cited by name, full spec in 07).
- Produces: per-component anchors (`#comp-button`, `#comp-tool-card`, etc.) that Task 6 (interaction) links to.

- [ ] **Step 1: Write English `docs/ui-design/03-component-library.md`**

  7-section structure applied PER component. Two groups:

  **Group A — Foundation components** (from `zhiflow-common.css`), each with 7 sub-sections:
  1. Primary Button `.sk-btn-primary` (accent fill, derive -8%/-16% on hover/press) and Secondary Button `.sk-btn-secondary` (bg-hover fill, border). NOTE: there is no `.sk-btn`.
  2. Input Field `.sk-field` (bg `-sk-bg`, border `-sk-border`, focus → border `-sk-accent` + bg `-sk-bg-elevated`), Label `.sk-field-label`.
  3. ComboBox `.sk-combo` + popup `.combo-box-popup .list-view` (selected → text `-sk-accent`).
  4. Checkbox `.sk-checkbox` (box border `-sk-border`, selected → bg+border `-sk-accent`, mark white).
  5. Table `.sk-table` (header bg `-sk-bg-hover`, selected row bg `-sk-bg-selected` + cell text `-sk-accent`, hover `-sk-bg-hover`, empty placeholder `-sk-text-disabled`).
  6. Tabs `.sk-tab-pane .tab` (label `-sk-text-secondary`, hover `-sk-bg-hover`, selected → bg `-sk-bg-selected` + 2px bottom accent border).
  7. Dialog `.sk-dialog` (bg `-sk-bg-elevated`, border, radius 10px, dropshadow).
  8. Scrollbar (global 8px / content-scroll 4px, thumb `-sk-text-disabled`→`-sk-text-secondary` on hover).
  9. Progress bar `.progress-bar` (6px, track `-sk-bg-hover`, bar `-sk-accent`; variants `.success`/`.danger`; indeterminate gradient).
  10. Notification `.sk-notif-*` (`.sk-notif-root` container, `.sk-notif-info/success/warning/error` severity with tinted bg + colored icon+text, `.sk-notif-message`, `.sk-notif-ok`/`.sk-notif-cancel` buttons).
  11. Section header `.section-header` / title `.section-title`, Separator `.separator .line`.
  12. Utility classes `.sk-t1/.sk-t2/.sk-t3/.sk-fill-2/.sk-fill-3/.sk-surface/.sk-surface-soft/.sk-outlined/.sk-outlined-strong` (the inline-style workaround: sizes inline, colors via these classes).

  **Group B — Shell components** (from `shell.css`, full detail per spec decision), each with 7 sub-sections:
  1. Sidebar NavItem `.nav-item` (height 32px, radius 6px; default icon+text `-sk-text-secondary`; hover `-sk-bg-hover`; **active = `-sk-bg-selected` + left 3px `-sk-accent` border** — the signature IDEA New UI rule). Cite `Sidebar.java`.
  2. Search Bar `.search-bar` (capsule radius 999px, height 34px; `:focused-within` → bg `-sk-bg`, border `-sk-accent`; `.search-kbd` shortcut hint). Cite `ContentArea.java`, `⌘K`.
  3. Tool Card `.tool-card` (152×128px, radius 8px; hover → `-sk-bg-hover`+`-sk-border-strong` + 150ms scale 1.03; `.tool-icon-wrap` 48px; `.tool-name`/`.tool-desc`; `.tool-tag`/`.tool-tag-plugin`). Cite `ToolCard.java`.
  4. Detail Panel `.detail-panel` (right slide-in, pref-width 260px; `.detail-launch-btn` accent). Cite `DetailPanel.java` slide-in 300ms.
  5. Status Bar `.statusbar` (height 28px; `.status-text` mono 12px; `.status-sep` dot). Cite `MainWindow.java` pulse.

  For EACH component, the per-component 7-sections:
  - **Overview**: one-line purpose.
  - **Principles**: when to use / not use.
  - **Spec table**: exact CSS classes, the `-sk-*` tokens each maps to, dimensions, radii, state colors.
  - **JavaFX template**: copyable instantiation (e.g. `Button b = new Button("OK"); b.getStyleClass().add("sk-btn-primary");`).
  - **AI checklist**: N musts.
  - **Anti-patterns**: e.g. using `.sk-btn` (doesn't exist); inline hex instead of `.sk-t*`.
  - **References**: CSS file + Java file + token link (05) + icon link (06) + animation link (07).

- [ ] **Step 2: Write Chinese mirror** (per-component structure identical, prose translated, code/classes/paths verbatim).

- [ ] **Step 3: Verify all cited CSS classes exist in source**

  Run for common.css classes:
  ```bash
  grep -nE '\.sk-(btn-primary|btn-secondary|field|field-label|combo|checkbox|table|tab-pane|dialog|notif-root|notif-info|notif-success|notif-warning|notif-error|notif-message|notif-ok|notif-cancel|t1|t2|t3|fill-2|fill-3|surface|surface-soft|outlined|outlined-strong)\b' SwissKitJ-Api/src/main/resources/css/zhiflow-common.css
  ```
  Run for shell.css classes:
  ```bash
  grep -nE '\.(nav-item|search-bar|tool-card|tool-icon-wrap|tool-name|tool-desc|tool-tag|detail-panel|detail-launch-btn|statusbar|status-text|status-sep|ic-blue|ic-purple|ic-teal|ic-amber|ic-red|ic-pink|ic-gray)\b' SwissKit/src/main/resources/css/shell.css
  ```
  Expected: every class the doc cites appears in the grep output. Any class in the doc NOT found here must be corrected in the doc.

- [ ] **Step 4: Commit**

  ```bash
  git add docs/ui-design/03-component-library.md docs/zh/ui-design/03-component-library.md
  git commit -m "📝 docs(ui): 03 Component Library (bilingual)"
  ```

---

## Task 5: 01 UI Design System (English + Chinese)

**Files:**
- Create: `docs/ui-design/01-design-system.md`
- Create: `docs/zh/ui-design/01-design-system.md`
- Reference: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`, `ui/MainWindow.java`; `docs/superpowers/specs/2026-06-30-idea-new-ui-redesign-design.md`; `docs/architecture.md` (for ASCII-diagram style consistency)

**Interfaces:**
- Consumes: synthesized view of all prior tasks (philosophy references 02/05/06, layout references the shell components in 03).
- Produces: the top-level entry doc; cross-links to all others.

- [ ] **Step 1: Write English `docs/ui-design/01-design-system.md`**

  7 sections (adapted — this is a philosophy doc, "components" → "layout primitives"):
  - **① Overview**: the UI "constitution" — design philosophy and cross-cutting principles.
  - **② Design Principles** (4): Functional-first; restrained IDEA New UI aesthetics; dark/light theme parity; plugins blend as native. Each principle with concrete do/don't.
  - **③ Spec tables / Layout**:
    - Global layout ASCII diagram: native OS titlebar → `[ Sidebar | ContentArea ]` → StatusBar (match style of `docs/architecture.md`). Initial size 960×620, min 800×520 (cite `SwissKitJApp`).
    - Typography: font stack (Global Constraint), 13px base, size scale table (11/12/13/13.5/15px and where each is used — cite `.section-title` 11px, `.status-text` 12px, `.tool-name` 13px, `.ai-msg-text` 13.5px, `.section-header` 15px).
    - Spacing grid: 4px base unit, scale (4/8/12/16/20/24), where used.
    - Radius: capsule 999px (search bar, AI input bar) vs standard 6px (buttons/fields) / 8px (cards/tables/popups) / 10px (dialogs/notifications).
    - Elevation/shadow: restrained — `.sk-dialog`, `.detail-panel`, `.sk-notif-root` use dropshadow; flat everywhere else.
    - Information hierarchy: `-sk-text` (primary) / `-sk-text-secondary` (secondary) / `-sk-text-disabled` (disabled/hint).
  - **④ Differences from IDEA New UI**: SwissKit-specific additions — tool-card grid (FlowPane of ToolCards), sidebar category sections (DEV/TEXT/IMAGE/NET/OTHER + AI/Plugins/Favorites), detail panel. What's intentionally the same (accent, neutral-gray selection, flat surfaces).
  - **⑤ AI checklist**: "MUST: follow 4 principles; use the typography/spacing/radius scales; prefer flat over shadow; link to 03 for components and 05 for exact color values."
  - **⑥ Anti-patterns**: glassmorphism (deprecated in v3.2.0 — the `.glass-*`→`.sk-*` migration); gratuitous shadows; blue-flood selection.
  - **⑦ References**: New UI spec, `SwissKitJApp.java`, `MainWindow.java`, `docs/architecture.md`, all sibling docs.

- [ ] **Step 2: Write Chinese mirror.**

- [ ] **Step 3: Verify layout facts**

  Run:
  ```bash
  grep -nE '(960|620|800|520|StageStyle.DECORATED)' SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
  ```
  Expected: dimensions and the `StageStyle.DECORATED` (native chrome) line. Fix doc if mismatched.

- [ ] **Step 4: Commit**

  ```bash
  git add docs/ui-design/01-design-system.md docs/zh/ui-design/01-design-system.md
  git commit -m "📝 docs(ui): 01 UI Design System (bilingual)"
  ```

---

## Task 6: 07 Animation Guidelines (English + Chinese)

**Files:**
- Create: `docs/ui-design/07-animation-guidelines.md`
- Create: `docs/zh/ui-design/07-animation-guidelines.md`
- Reference: animation sites in Global Constraint #7; `javafx.animation.*` classes

**Interfaces:**
- Consumes: the real animations listed in Global Constraint #7.
- Produces: the "Animation Token" table (durations/easings) that Task 4 (components) and Task 7 (interaction) reference.

- [ ] **Step 1: Write English `docs/ui-design/07-animation-guidelines.md`**

  7 sections:
  - **① Overview**: motion serves feedback, stays out of the way; IDEA New UI restraint; JavaFX `javafx.animation` toolkit.
  - **② Principles**: purposeful (every animation answers "what just happened"); fast (≤300ms for UI feedback); non-blocking (never delay input); reversible-friendly; theme switch is INSTANT (no fade — just class swap, cite `ThemeService.set`).
  - **③ Spec tables** — **Animation Tokens**:
    - Duration scale: fast 100–150ms (button/click/hover), normal 220–280ms (transitions/entry), slow 300ms+ (panels). Cite the real durations: pop 150ms, hover scale 150ms, click scale 100ms, card entry 240/280ms, cross-fade 220/180ms, panel slide 300/250ms, grid slide 280ms, entry fade 250ms, pulse 2500ms.
    - Easing: `Interpolator.EASE_OUT` (exits/entries), `EASE_IN` (slide-out opacity), `SPLINE(0.4,0,0.2,1)` (panel translate — material standard), `SPLINE(0.34,0.9,0.64,1)` (sidebar pop), ToolCard custom `curve(t)=1-(1-t)^3·cos(t·2π)`.
  - **④ JavaFX templates** — for EACH real animation, a copyable code block matching source. Cover: FadeTransition (entry/pulse/cross-fade/blink), TranslateTransition (grid slide/ToggleSwitch thumb), ScaleTransition (card hover/click/star pop/sidebar pop), Timeline (DetailPanel slide-in/out, clock), ParallelTransition (combined fade+translate+scale), PauseTransition (card stagger delay). Each template cites the source file:line.
  - **⑤ "Suggested but not yet implemented" standard animations** (per approved spec): list-reorder slide, dialog enter scale+fade, toast slide-in-fade-out, checkbox check-mark transition, loading spinner, focus-ring fade-in. For each: rationale, suggested duration/easing, copyable JavaFX template, and note "(not yet in codebase — proposed standard)".
  - **⑥ AI checklist + Anti-patterns**: "MUST: stay ≤300ms for feedback; use the token durations/easings; never animate on data-load/text-input/theme-switch; close/reverse animations on state change." Anti-patterns: animating theme switch; >500ms UI feedback; animating layout properties causing reflow; starting a new animation without stopping the prior (overlap jitter).
  - **⑦ References**: all animation source files from Global Constraint #7, sibling 03/04.

- [ ] **Step 2: Write Chinese mirror.**

- [ ] **Step 3: Verify each cited animation exists**

  Run:
  ```bash
  grep -rnE 'FadeTransition|TranslateTransition|ScaleTransition|Timeline|ParallelTransition|PauseTransition|Interpolator' SwissKit/src/main/java/fan/summer/ui/ SwissKit/src/main/java/fan/summer/buildintool/ | grep -E '(Duration|Interpolator|Transition)'
  ```
  Expected: hits in `MainWindow.java`, `ContentArea.java`, `ToolCard.java`, `DetailPanel.java`, `Sidebar.java`, `AiChatPlugin.java`, `ToggleSwitch.java` matching the file:line citations in Global Constraint #7. Any animation the doc describes that has no grep hit must be moved to the "suggested/not yet implemented" section.

- [ ] **Step 4: Commit**

  ```bash
  git add docs/ui-design/07-animation-guidelines.md docs/zh/ui-design/07-animation-guidelines.md
  git commit -m "📝 docs(ui): 07 Animation Guidelines (bilingual)"
  ```

---

## Task 7: 04 Interaction Guidelines (English + Chinese)

**Files:**
- Create: `docs/ui-design/04-interaction-guidelines.md`
- Create: `docs/zh/ui-design/04-interaction-guidelines.md`
- Reference: `ui/sidebar/Sidebar.java`, `ui/content/ContentArea.java`, `ui/MainWindow.java`, `ui/store/PluginStoreUi.java`, `ui/setting/SwissKitJSettingUi.java`

**Interfaces:**
- Consumes: component anchors from Task 4; animations from Task 6; theme toggle from Task 1.
- Produces: interaction-flow descriptions that 08 (a11y) references for keyboard flows.

- [ ] **Step 1: Write English `docs/ui-design/04-interaction-guidelines.md`**

  7 sections (adapted — interaction flows, not components):
  - **① Overview**: how users navigate, discover, and act in SwissKit.
  - **② Principles**: discoverability (search + cards); progressive disclosure (detail panel); forgiving (undo/confirm for destructive); consistent feedback (4 states).
  - **③ Flow tables**:
    - Navigation: sidebar expand/collapse (persisted via `sidebar.collapsed` key), category switch, favorites, theme toggle entry (footer).
    - Tool discovery flow: search (`⌘K`) → card hover (150ms scale) → detail panel slide-in (300ms) → launch → `showPage` cross-fade (220/180ms) with view caching.
    - Plugin lifecycle: activate/deactivate/uninstall with confirmation + feedback; `hasRunningTasks()` → background instead of deactivate (cite `ToolCard` bg-running pulse).
    - Plugin store: install (progress), local-installed tab switch (cite `PluginStoreUi`).
    - Form: validation timing (on blur/submit), error message placement (below field), submit feedback.
    - Keyboard: `⌘K` search focus, Esc close panel/dialog, Tab focus order, Enter activate.
    - 4-state feedback: loading / empty / error / success — when each shows, which component (cite `.sk-notif-*`, `.progress-bar`, `.sk-table` placeholder).
    - Destructive ops: second confirmation dialog (`.sk-dialog`), clear irreversible-action copy.
  - **④ JavaFX template**: event-wiring patterns — sidebar `onSelect` callback, card click → `DetailPanel.show` → launch → `contentArea.showPage(cachedView)`, `ThemeService.onChange` for re-render, confirmation dialog pattern.
  - **⑤ AI checklist**: "MUST: cache views; cross-fade on page switch; confirm destructive ops; show one of the 4 states; wire ⌘K and Esc; persist sidebar collapse."
  - **⑥ Anti-patterns**: rebuilding view on every activation (cache it); no empty/error state; destructive action without confirm; blocking the FX thread for async ops.
  - **⑦ References**: the UI Java files, `PluginStoreUi.java`, `SwissKitJSettingUi.java`, sibling 03/06/07.

- [ ] **Step 2: Write Chinese mirror.**

- [ ] **Step 3: Verify interaction facts**

  Run:
  ```bash
  grep -rnE '(showPage|crossFade|sidebar.collapsed|hasRunningTasks|onSelect|onChange)' SwissKit/src/main/java/fan/summer/ui/
  ```
  Expected: methods/keys the doc cites appear in source. Fix doc if mismatched.

- [ ] **Step 4: Commit**

  ```bash
  git add docs/ui-design/04-interaction-guidelines.md docs/zh/ui-design/04-interaction-guidelines.md
  git commit -m "📝 docs(ui): 04 Interaction Guidelines (bilingual)"
  ```

---

## Task 8: 08 Accessibility Guide (English + Chinese)

**Files:**
- Create: `docs/ui-design/08-accessibility-guide.md`
- Create: `docs/zh/ui-design/08-accessibility-guide.md`
- Reference: `zhiflow-common.css` (tokens), `docs/ui-design/05-theme-color-system.md` (contrast matrix)

**Interfaces:**
- Consumes: token contrast matrix from Task 1; keyboard flows from Task 7.
- Produces: the a11y checklist that all component docs should satisfy.

- [ ] **Step 1: Write English `docs/ui-design/08-accessibility-guide.md`**

  7 sections:
  - **① Overview**: a11y ensures UI is usable by all; this is the checklist every component must pass.
  - **② Principles**: perceivable / operable / understandable / robust (POUR).
  - **③ Spec tables**:
    - Contrast requirements: text ≥4.5:1, large text ≥3:1 (WCAG AA). Cross-link to 05's contrast matrix. List the PASSING combos (e.g. `-sk-text` on `-sk-bg` in both themes; `-sk-text-secondary` on `-sk-bg` — verify ratio; warn if a combo like `-sk-text-disabled` on `-sk-bg` fails).
    - "Not color alone" rule: status conveyed by icon + text + color (cite `.sk-notif-*` severity = icon + message + tinted bg).
  - **④ JavaFX template**: keyboard operability (all actions reachable via keyboard, visible focus ring), focus management (dialog open → focus first control, close → restore focus, page switch → move focus), `AccessibleRole`/`accessibleText` usage for screen readers, reduced-motion degradation strategy (JavaFX has no media query — provide a static `boolean reduceMotion` flag pattern that disables/shortens animations).
  - **⑤ AI checklist**: "MUST: contrast ≥4.5:1 for text; never color-alone status; all actions keyboard-reachable; visible focus; set AccessibleRole + accessibleText on custom controls; provide reduced-motion path; Esc closes dialogs."
  - **⑥ Anti-patterns**: relying on color for error state; low-contrast disabled text as the only affordance; trapping focus; no Esc handler; long un-animatable layout shifts.
  - **⑦ References**: `zhiflow-common.css`, sibling 05 (contrast), 07 (reduced motion), 04 (keyboard flows).

- [ ] **Step 2: Write Chinese mirror.**

- [ ] **Step 3: Sanity-check contrast claims** (manual note, not a hard grep — contrast ratios require computation; instruct author to compute key pairs with a WCAG tool and only assert ratios they verified). The step's deliverable: the doc's contrast table only contains rows the author has numerically verified.

- [ ] **Step 4: Commit**

  ```bash
  git add docs/ui-design/08-accessibility-guide.md docs/zh/ui-design/08-accessibility-guide.md
  git commit -m "📝 docs(ui): 08 Accessibility Guide (bilingual)"
  ```

---

## Task 9: README index + docsify sidebar navigation

**Files:**
- Create: `docs/ui-design/README.md`
- Create: `docs/zh/ui-design/README.md`
- Modify: `docs/_sidebar.md`
- Modify: `docs/zh/_sidebar.md`

**Interfaces:**
- Consumes: all 8 docs from Tasks 1–8.

- [ ] **Step 1: Read existing sidebars to match format**

  Run:
  ```bash
  cat docs/_sidebar.md docs/zh/_sidebar.md
  ```
  Observe: heading style (`- [` link `]`), nesting indentation, existing groupings. Match this exactly when inserting the new "UI Design" / "UI 设计" group.

- [ ] **Step 2: Write English README `docs/ui-design/README.md`**

  An index page: short intro (SwissKit UI Design System — 8 docs following JetBrains IDEA 2025 New UI), and a table listing all 8 docs with: number, title, one-line description, link, target audience. Plus a "How to use these docs" note: read 01 for philosophy, 02+05 to start coding, 03 per component, etc. Plus the single-source-of-truth cross-link convention.

- [ ] **Step 3: Write Chinese README `docs/zh/ui-design/README.md`** (translated).

- [ ] **Step 4: Update `docs/_sidebar.md`** — add a "UI Design" group (or under an existing appropriate group) linking to `ui-design/README.md` and the 8 docs. Use relative paths from docsify root (`ui-design/01-design-system` etc., no `.md`).

- [ ] **Step 5: Update `docs/zh/_sidebar.md`** — add "UI 设计" group linking to `ui-design/` subpaths under `zh/`.

- [ ] **Step 6: Verify all links resolve (file existence)**

  Run:
  ```bash
  for f in 01-design-system 02-javafx-implementation 03-component-library 04-interaction-guidelines 05-theme-color-system 06-icon-system 07-animation-guidelines 08-accessibility-guide; do
    for d in docs/ui-design docs/zh/ui-design; do
      test -f "$d/$f.md" && echo "OK  $d/$f.md" || echo "MISS $d/$f.md"
    done
  done
  ```
  Expected: all OK, no MISS. Also verify the index READMEs exist:
  ```bash
  test -f docs/ui-design/README.md && test -f docs/zh/ui-design/README.md && echo "READMEs OK"
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add docs/ui-design/README.md docs/zh/ui-design/README.md docs/_sidebar.md docs/zh/_sidebar.md
  git commit -m "📝 docs(ui): index READMEs + sidebar navigation (bilingual)"
  ```

---

## Final Verification

- [ ] **Step 1: Cross-link integrity**

  Run a scan for the cross-link anchors defined as "Produces" in each task to confirm they're referenced elsewhere:
  ```bash
  grep -rln '05-theme-color-system' docs/ui-design/ docs/zh/ui-design/
  grep -rln '02-javafx-implementation' docs/ui-design/ docs/zh/ui-design/
  grep -rln '06-icon-system' docs/ui-design/ docs/zh/ui-design/
  ```
  Expected: each token-defining doc is linked from multiple others (confirms the single-source convention is wired up).

- [ ] **Step 2: No invented class names**

  Run a forbidden-names check across all new docs (these names do NOT exist in the CSS and must not appear as if they do):
  ```bash
  grep -rnE '\.sk-btn\b|\.sk-text-field|\.sk-badge\b|\.sk-notification\b|\.sk-tab\b' docs/ui-design/ docs/zh/ui-design/ && echo "FOUND FORBIDDEN NAMES — fix" || echo "clean"
  ```
  Expected: `clean` (no output, exit non-zero of grep). If any forbidden name appears (outside an explicit "does not exist" anti-pattern note), fix it.

- [ ] **Step 3: No mermaid**

  ```bash
  grep -rn '```mermaid' docs/ui-design/ docs/zh/ui-design/ && echo "MERMAID FOUND — remove" || echo "clean"
  ```
  Expected: `clean`.

- [ ] **Step 4: Bilingual parity**

  ```bash
  for f in 01-design-system 02-javafx-implementation 03-component-library 04-interaction-guidelines 05-theme-color-system 06-icon-system 07-animation-guidelines 08-accessibility-guide README; do
    en=$(wc -l < "docs/ui-design/$f.md" 2>/dev/null || echo 0)
    zh=$(wc -l < "docs/zh/ui-design/$f.md" 2>/dev/null || echo 0)
    echo "$f: en=$en zh=$zh"
  done
  ```
  Expected: every file has a non-zero line count on both sides; ratios roughly comparable (Chinese may differ by ±20%). Any 0 indicates a missing mirror — fix it.

- [ ] **Step 5: Final commit (if any fixes)**

  ```bash
  git add -A docs/ui-design/ docs/zh/ui-design/ docs/_sidebar.md docs/zh/_sidebar.md
  git commit -m "📝 docs(ui): final cross-link + naming verification"
  ```
