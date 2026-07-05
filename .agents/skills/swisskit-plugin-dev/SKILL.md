---
name: swisskit-plugin-dev
description: Build, scaffold, debug, and theme SwissKitJ plugins — external JAR tools that implement the `SwissKitJPlugin` interface and load into the SwissKitJ JavaFX host. Use whenever the user wants to create a SwissKitJ plugin/tool, work with the plugin API (SwissKitJPlugin, ToolCategory, IconStyle, MdiIconUtil, I18n, Themes), add an AI tool to a plugin, fix a plugin that won't load or renders unthemed, scaffold a plugin project, or mentions SwissKitJ plugin development, `.swisskit/plugin`, plugin SPI / ServiceLoader, or the SwissKitJ-Api. Also use when editing code that `implements SwissKitJPlugin` or that lives under `SwissKitJ-Api`.
---

# SwissKitJ Plugin Development

You are an expert author of **SwissKitJ plugins** — external JAR tools that implement
`fan.summer.api.SwissKitJPlugin`, register via Java `ServiceLoader` SPI, and hot-load into the
SwissKitJ JavaFX host (dropped into `.swisskit/plugin/`). This skill makes you produce plugins
that **load cleanly and render theme-correctly** the first time, avoiding the recurring
pitfalls (wrong SPI path, missing shade transformer, inline hex colors, unregistered i18n
bundle, `*PluginUi` wrapper classes).

The SwissKitJ host is a JavaFX 21 app implementing the JetBrains IDEA 2025 "New UI". Plugins
blend in as native tools: they share the host's theme tokens (`-sk-*`), foundation components
(`.sk-*`), fonts, and icons. There is no "plugin look."

**Current API version: 3.2.0.**

> **This skill is portable and cross-agent.** It follows the open Agent Skills standard
> (a `SKILL.md` with `name` + `description` frontmatter + Markdown body), so the **same files**
> work in **ZCode** and **Claude Code** (and any compatible agent). It works identically
> inside the SwissKitJ repo, the official plugin repo, and a third-party developer's project:
> all references use absolute URLs (no relative paths) and the must-know spec facts are
> inlined so it's usable offline. Install instructions for both agents are in
> [`INSTALL.md`](INSTALL.md).

### Reference URLs (memorize — used throughout this skill)

- **Docs site (rendered, best for reading):** `https://muskstark.github.io/SwissKitJ/`
- **Source (best for citing `file:line`):** `https://github.com/MuskStark/SwissKitJ/blob/main/`
- **UI design spec (the authority):** `docs/ui-design/` → e.g.
  `https://muskstark.github.io/SwissKitJ/#/ui-design/05-theme-color-system` (docsify) or
  `https://github.com/MuskStark/SwissKitJ/blob/main/docs/ui-design/05-theme-color-system.md` (raw)
- **API source root:** `SwissKitJ-Api/src/main/java/fan/summer/api/`

> The older `docs/plugins/` markdown predates the 3.2.0 contract in places (it lists ~9
> methods and shows a discouraged `*PluginUi` wrapper). This skill uses the **current 16-method
> contract** and the **single-class pattern** verified against source. Treat the rendered
> [plugins docs](https://muskstark.github.io/SwissKitJ/#/plugins/) as supporting context, not
> authoritative.

## When to use this skill

**Use** for anything about **external JAR plugins** (`ToolType.PLUGIN`): scaffolding a new
one, editing an existing one, adding features, theming its UI, exposing an AI tool, fixing a
load/render bug.

**Don't use** for the host's **builtin tools** (under
[`buildintool/`](https://github.com/MuskStark/SwissKitJ/tree/main/SwissKit/src/main/java/fan/summer/buildintool),
registered by `BuiltinToolRegistrar`, `ToolType.BUILTIN`). Those are host code compiled into
the app, not loadable plugins — they have no SPI file, no shade step, and no plugin
ClassLoader. If the user is editing a builtin, ordinary JavaFX/Java guidance applies; this
skill's scaffold/SPI/deploy sections don't apply (but the UI/tokens sections still do).

## The fast path

Figure out which of these the user needs, then read the matching reference before coding:

| User wants | Start here |
|---|---|
| **Scaffold a brand-new plugin** | Copy `assets/plugin-template/`, substitute placeholders, follow [references/scaffold.md](references/scaffold.md) |
| **Edit/extend an existing plugin** | Find the `*Plugin.java` (implements `SwissKitJPlugin`); re-read [references/contract.md](references/contract.md) for the method you're touching |
| **Build the UI / theme it** | [references/ui-and-tokens.md](references/ui-and-tokens.md) — tokens, `.sk-*` classes, layout pitfalls |
| **Add an AI tool / async work / persistence** | [references/advanced.md](references/advanced.md) |
| **Plugin won't load** | [references/advanced.md](references/advanced.md) §Pitfalls → SPI file + `ServicesResourceTransformer` |
| **Plugin loads but renders unthemed/broken** | [references/ui-and-tokens.md](references/ui-and-tokens.md) → inline hex / wrong ClassLoader / standalone Stage not themed |

**Reference files are read on demand** — don't load them all upfront. Pick from the table
above. The non-negotiables below are always in force.

## The non-negotiables

These are the rules that, when broken, account for almost every broken plugin. Follow them
even if a copied example does otherwise.

1. **One class, implements `SwissKitJPlugin` directly.** No separate `*PluginUi` /
   `*ViewController` wrapper. All 11 builtins implement the interface in a single class. Put
   the UI-building logic in `createView()` (and private helpers) within that class.

2. **Implement the 7 required methods** (the other 9 have sane defaults). Full signatures in
   [references/contract.md](references/contract.md); the essential returns:
   - `getId()` → globally unique, reverse-domain (e.g. `"com.example.csv-sorter"`)
   - `getName()` / `getDescription()` → `I18n.get(...)` (not hardcoded strings)
   - `getCategory()` → a `ToolCategory` (`DEV`/`TEXT`/`IMAGE`/`NET`/`OTHER`)
   - `getVersion()` → `"major.minor.patch"`
   - `getMdiIcon()` → **bare MDI name, no `mdi-` prefix** (e.g. `"code-json"`, not `"mdi-code-json"`)
   - `createView()` → a `javafx.scene.Node` (see rule 3)

3. **`createView()` runs exactly once.** The host caches the returned `Node` and reuses it on
   every activation — it is **not** called again. So: build the UI once, store references to
   controls you need later in **fields**, and return the cached root. Register the i18n
   bundle here (rule 4), before building the UI.

4. **Register the i18n bundle in `createView()` with the plugin's own ClassLoader:**
   ```java
   I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
   ```
   Skip this and every `I18n.get(key)` / `I18n.bind(...)` returns the raw key. (The host also
   auto-registers it on load, but registering in `createView()` is required for the `dev`
   profile to work standalone.)

5. **Color only via `-sk-*` tokens or `.sk-*` classes — never inline hex.**
   - **Wrong:** `setStyle("-fx-background-color: #2B2B2B;")` — frozen, breaks on theme switch.
   - **Right:** `getStyleClass().add("sk-surface")` or `setStyle("-fx-background-color: -sk-bg-elevated;")`
     (a `-sk-*` token string *does* resolve as a looked-up color; a hex literal does not).
   - Sizes, padding, radius may be inline; colors must be tokens/classes. See
     [references/ui-and-tokens.md](references/ui-and-tokens.md).

6. **SPI registration file is mandatory and path-sensitive.**
   - Path: `src/main/resources/META-INF/services/fan.summer.api.SwissKitJPlugin`
   - Content: one line — the fully-qualified class name of your plugin class.
   - The `maven-shade-plugin` MUST include `ServicesResourceTransformer` or the file gets
     overwritten during shading and the plugin is invisible. Verify post-build:
     `unzip -p target/*.jar META-INF/services/fan.summer.api.SwissKitJPlugin`

7. **Async work → off the FX thread, with `Platform.runLater` for UI updates.** Use
   `javafx.concurrent.Task<Void>` for background jobs; bind a `.progress-bar` to its
   `progressProperty`. If work continues after the user navigates away, override
   **`hasRunningTasks()`** to return `true` so the host keeps the plugin's view cached instead
   of deactivating it. Full pattern in [references/advanced.md](references/advanced.md).

8. **UI MUST follow the main project's UI design spec.** The plugin is a guest in the
   SwissKitJ shell — it must blend in as native, never invent its own look. The authoritative
   design system is the **`docs/ui-design/`** doc set (rendered:
   `https://muskstark.github.io/SwissKitJ/#/ui-design/`; source:
   `https://github.com/MuskStark/SwissKitJ/blob/main/docs/ui-design/`).
   [references/ui-and-tokens.md](references/ui-and-tokens.md) inlines the must-know facts so
   the skill works offline, but the spec wins on conflict. The non-negotiable UI rules:
   - **Colors** — `-sk-*` tokens / `.sk-*` classes only, **never inline hex** (breaks on theme
     switch; a `-sk-*` token string *does* resolve as a looked-up color). The full token table
     is inlined in [references/ui-and-tokens.md](references/ui-and-tokens.md) and online at
     [05 Theme & Color System](https://muskstark.github.io/SwissKitJ/#/ui-design/05-theme-color-system).
   - **Components** — use the exact `.sk-*` classes specified in
     [03 Component Library](https://muskstark.github.io/SwissKitJ/#/ui-design/03-component-library):
     `.sk-field`, `.sk-btn-primary`, `.sk-btn-secondary`, `.sk-table`, `.sk-dialog`,
     `.sk-notif-*`, etc. There is **no** `.sk-btn`, `.sk-text-field`, `.sk-badge`, or
     `.sk-notification` (these names don't exist — see anti-patterns in 03).
   - **Typography** — global font stack `"SF Pro Text", "Inter", "Segoe UI", "PingFang SC",
     "Microsoft YaHei", sans-serif` (never override `-fx-font-family`); sizes from the
     11/12/13/13.5/15 px scale; spacing on the 4 px grid; radii 6/8/10/999 px
     ([01 Design System](https://muskstark.github.io/SwissKitJ/#/ui-design/01-design-system)).
   - **Motion** — reuse the token durations/easings; interaction feedback ≤ 300 ms; **never
     animate the theme switch**
     ([07 Animation Guidelines](https://muskstark.github.io/SwissKitJ/#/ui-design/07-animation-guidelines)).
   - **Interaction & a11y** — cache the view; cross-fade on page switch; confirm destructive
     ops; show one of loading/empty/error/success
     ([04](https://muskstark.github.io/SwissKitJ/#/ui-design/04-interaction-guidelines));
     ≥ 4.5:1 contrast, never status-by-color-alone, keyboard-reachable, Esc closes
     ([08 Accessibility](https://muskstark.github.io/SwissKitJ/#/ui-design/08-accessibility-guide)).

   The one-line rule: **if the main project's design spec covers it, follow the spec — don't
   freelance.**

## The plugin shape (minimal)

A correct plugin's heart looks like this (full version in the template + contract reference):

```java
package com.example.csvsorter;

import fan.summer.api.*;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import java.util.List;

public class CsvSorterPlugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(CsvSorterPlugin.class);
    private static final String P = "plugin.csv-sorter.";   // i18n key prefix

    @Override public String getId()          { return "com.example.csv-sorter"; }
    @Override public String getName()        { return I18n.get(P + "name"); }
    @Override public String getDescription() { return I18n.get(P + "desc"); }
    @Override public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "sort-alphabetical-variant"; }
    @Override public IconStyle getIconStyle() { return IconStyle.TEAL; }   // default is BLUE

    @Override
    public Node createView() {
        I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader());
        VBox root = new VBox(8);
        // build UI with .sk-* classes / -sk-* tokens ...
        return root;
    }
}
```

For the complete, copyable scaffold (pom.xml, dev launcher, SPI file, i18n bundles), use
`assets/plugin-template/` and see [references/scaffold.md](references/scaffold.md).

## Reference map

| File | Read when |
|---|---|
| [references/contract.md](references/contract.md) | You need exact method signatures, default values, or lifecycle firing rules (when does `onBackground` vs `onDeactivate` fire?) |
| [references/scaffold.md](references/scaffold.md) | Scaffolding a new project, configuring the pom/shade plugin, setting up the dev launcher, or using `PluginPreviewWindow` to test without deploying |
| [references/ui-and-tokens.md](references/ui-and-tokens.md) | Building or theming the UI — the `.sk-*` class list, `-sk-*` tokens, layout pitfalls, `GlassNotification`/`StepWizard`/`UiUtils` APIs, icon + i18n patterns |
| [references/advanced.md](references/advanced.md) | Adding an AI tool (`aiTools()`), background tasks, persistence (H2/MyBatis) or Excel (FesodSheet), or diagnosing a load/render failure (the pitfalls digest) |
