# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **4.0.0 (this branch) is a headless web + desktop app — JavaFX has been deleted.** The Java
> process is a loopback Spring Boot web server (`HeadlessLauncher`); the UI is a Vue 3.5 + TS
> SPA (`frontend/`), served in the browser or wrapped by a Tauri 2.0 desktop shell (`desktop/`).
> Much of the JavaFX-era detail below (theming CSS, layout pitfalls, `StepWizard`, `createView()`)
> is **historical** — retained for the still-JavaFX `ZhiFlow-Api` preview classes but no longer
> describes the running app. See **4.0.0 Headless Architecture** near the top.

## 4.0.0 Headless Architecture (Phase 1)

The reactor is a **parent POM** (`pom.xml`) with modules `ZhiFlow-Api`, `plugin-markdown`,
`ZhiFlow` (each has `<parent>`; version via `${revision}`). Plus non-Maven top-level dirs:
`frontend/` (Vue) and `desktop/` (Tauri).

- **Backend** — `fan.summer.zhiflow.HeadlessLauncher` boots `AiSpringContext.startWeb(port)`
  (embedded Tomcat, `127.0.0.1` only). CLI: `--port=<n>` (defaults to `24056`; `0` = free port,
  prints `ZHIFLOW_PORT=<n>` to stdout for the sidecar; falls back to a free port if the fixed one
  is taken), `--token=<t>` (per-launch auth, sent as `X-ZhiFlow-Token`; the SSE stream accepts
  `?token=`). Controllers in `fan.summer.zhiflow.web.*`; `PluginRegistryService` collects
  `ZhiFlowPluginV2` beans and registers their `aiTools()`.
- **Endpoints** — `/api/health`, `/api/plugins`, `/api/plugins/{id}/invoke`, `/plugin-ui/{id}/**`,
  `/api/settings` (GET/PUT), `/api/ai/chat` (POST → `{streamId}`), `/api/ai/stream` (SSE: events
  `token`/`thinking`/`tool`/`done`/`error`). AI chat is a permanent core built-in, never a plugin.
- **Plugin v2** — `ZhiFlowPluginV2` (in `ZhiFlow-Api`): `descriptor()` + `invoke(action, args)` +
  `aiTools()`. UI ships as an ESM micro-frontend at `PluginDescriptor.uiEntry`, served from the
  plugin module's `src/main/resources/ui/{id}/`.
- **Frontend** — `frontend/` Vue 3.5.39 + TS + Pinia + vue-router. `--sk-*` tokens ported from
  `zhiflow-common.css`; Vue shared with plugin bundles via an import map. MF host dynamically
  `import()`s `uiEntry` and calls the bundle's `default.mount(el, ctx)`.
- **Desktop** — `desktop/` Tauri 2.0; `src-tauri/src/main.rs` spawns the jar sidecar, waits on
  health, injects `window.__ZHIFLOW_TOKEN__`/`__ZHIFLOW_API_BASE__`. Needs Rust + `tauri-cli`.

## Build & Run

**No system Maven required in-editor.** In-IDE, use IntelliJ's Maven (tool window or MCP
`mcp__idea__build_project`). From a shell you may use IDEA's bundled Maven binary directly.

```bash
# Reactor order: API → plugin-markdown → app (API must be installed first)
mvn -f ZhiFlow-Api/pom.xml install -DskipTests
mvn -f plugin-markdown/pom.xml install -DskipTests
mvn -f ZhiFlow/pom.xml clean package -DskipTests

# Rebuild a plugin's micro-frontend bundle (emits into resources/ui/markdown/)
cd plugin-markdown/ui-src && npm install && npm run build

# Run the headless backend (loopback web server, no window; binds 24056 by default)
java -jar ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar --token=<t>

# Run the Vue frontend (dev; proxies /api and /plugin-ui to localhost:24056)
cd frontend && npm install && npm run dev

# End-to-end smoke test (boots the jar, probes every endpoint)
scripts/e2e-smoke.sh
```

GitHub Actions handles multi-platform builds. Tauri desktop packaging (signed installers, bundled
JRE) is a later phase (Phase F-prod).

## Module Structure

| Module / dir | Purpose |
|--------|---------|
| `ZhiFlow-Api` | Plugin v2 contract (`ZhiFlowPluginV2`, `PluginDescriptor`), AI contract (`AiService`/`AiTool`), `IconStyle`/`ToolCategory`. (Still contains JavaFX-era v1 `ZhiFlowPlugin` + preview/theme/component classes, unused by the headless runtime.) |
| `plugin-markdown` | First official v2 plugin: backend `@Component` (commonmark render) + Vue micro-frontend (`ui-src/` → `resources/ui/markdown/index.js`) |
| `ZhiFlow` | Headless Spring Boot backend — REST/SSE controllers, `HeadlessLauncher`, `PluginRegistryService`, AI backends, H2/MyBatis |
| `frontend/` | Vue 3.5 + TS SPA (browser + Tauri) |
| `desktop/` | Tauri 2.0 desktop shell (Java sidecar) |

Official plugins live in a separate repository: [MuskStark/ZhiFlow-Plugin](https://github.com/MuskStark/ZhiFlow-Plugin). They are built independently and dropped into `.zhiflow/plugin/` as JARs at runtime. All plugins declare `ZhiFlow-Api` as `provided` scope. The main app provides it at runtime via the fat JAR.

## Architecture

**Entry point**: `fan.summer.Launcher` (fat-JAR manifest) → `fan.summer.app.ZhiFlowApp` (JavaFX `Application`).

**Startup sequence** (in `ZhiFlowApp.start()`):
1. Install the plugin logger binder; init H2/MyBatis; apply the saved i18n language and theme (dark default, via `ThemeService.set(...)`; the main scene is later registered with `ThemeService.registerScene(scene)`)
2. Resolve the plugins directory (`<user.dir>/.zhiflow/plugin/`)
3. Create `PluginLoader` + `PluginRegistry`
4. Create `FavoriteService` (loads bookmarked plugin IDs from DB)
5. Register built-in tools via `BuiltinToolRegistrar` (bypasses JAR loading, routes through `PluginRegistry.addPlugins`, which auto-registers each plugin's `aiTools()`)
6. Initialize cloud AI backends if mode is `openai`/`anthropic`; **local mode is lazy** — deferred until the AI tool is first opened
7. Build `MainWindow` and display it
8. Start `PluginLoader` (scans the plugins dir and watches for changes)

> Step 5 registers both the plugin UI and any AI tools the plugin declares via `aiTools()` in one pass — there is no separate AI-tool registrar step anymore.

**UI structure** (all in `fan.summer.ui.*`):
- `MainWindow` — root `StackPane` wrapping a `BorderPane` body; owns `Sidebar`, `ContentArea`, status bar. **Native OS window chrome** (`StageStyle.DECORATED`) — no custom title bar, no orbs/clipping.
- `Sidebar` — category-based navigation; categories are `all / text / image / dev / net / other / favorites`. **Collapsible** (`«`/`»` toggle: label view ⇄ 48px icon-strip; state persisted via the `sidebar.collapsed` setting). Footer holds Settings / About / theme-toggle items.
- `ContentArea` — shows `ToolCard` grid or active tool view; manages `DetailPanel` and the back-bar for returning from a tool
- `DetailPanel` — slide-in panel showing plugin metadata; has a Launch button that fires `onLaunch`

**Navigation flow**: `ToolCard` click → `DetailPanel.show()` → Launch button → `MainWindow.wireEvents` callback → `registry.activate(plugin)` + `contentArea.showPage(plugin.createView(), title)`. The back bar (shown by `ContentArea`) calls `registry.deactivate()` on return.

**Theming**: IDEA 2025 New UI look — flat, token-based, with switchable **dark / light** themes (dark default; persisted in the `theme` setting). `fan.summer.api.theme.ThemeService` (API module, no DB dependency) holds the active `Theme.DARK`/`Theme.LIGHT`, stamps a `theme-dark`/`theme-light` class on every registered scene root, and fires `onChange` listeners. Looked-up color tokens (`-sk-bg`, `-sk-bg-elevated`, `-sk-text`, `-sk-accent`, `-sk-border`, …) are declared per theme in `zhiflow-common.css`; swapping the root class re-resolves every token with **no stylesheet reload**. The host loads/persists the choice; `Themes.applyTo(scene)` delegates to `ThemeService.registerScene(scene)`.

Three-layer CSS structure:

| File | Module | Scope |
|---|---|---|
| `css/zhiflow-common.css` | `ZhiFlow-Api` | `-sk-*` token definitions (under `.theme-dark` / `.theme-light`), scrollbars, progress bar, `.sk-*` utility classes (dialog/field/tab-pane/combo/table/checkbox/btn-primary/btn-secondary/notif-*), `.section-title`/`.section-header`. Loaded into the main Scene + available to any third-party plugin. |
| `css/shell.css` | `ZhiFlow` | App-shell only — `.app-root`, `.sidebar` (+ `.collapsed`), `.search-bar`, `.tool-card`, `.detail-panel`, `.statusbar`, `.store-*`. Fully token-based. Loaded into the main Scene by `ZhiFlowApp`. |
| `css/builtin.css` | `ZhiFlow` | Reserved for built-in tool styling. Currently empty placeholder. |

Plugins embedded in the main Scene (the normal `createView()` flow) automatically inherit all three stylesheets via scene graph propagation — no action needed. Plugins that open their own `Stage`/`Scene` should call `fan.summer.api.theme.Themes.applyTo(scene)` to load the common stylesheet and stamp the active theme class on the root.

Plugin icon background colors are CSS classes: `ic-blue / ic-purple / ic-teal / ic-amber / ic-red / ic-pink / ic-gray` (declared in `shell.css`; actual color injection happens in Java via `DropShadow` per `IconStyle`).

> **v3.2.0 rename (BREAKING for plugin authors):** the old `.glass-*` utility classes were renamed to `.sk-*`. See the rename table in `CHANGELOG.md` `[3.2.0]`. External plugins still referencing `.glass-*` must update.

**Database**: H2 file at `.zhiflow/zhiflow.db` relative to the runtime working directory. Schema initialized from `init.sql`. Accessed via MyBatis; mapper XMLs are in `src/main/resources/mapper/`.

**i18n**: `src/main/resources/i18n/messages.properties` (English default), `messages_zh.properties` (Chinese).

**JSON**: Use `fan.summer.api.json.JsonHelper` (Gson-based). Old `JsonBuilder`/`JsonParser` are deleted.

**AI Markdown**: AI responses render via `WebView` through `MarkdownRenderer.render(md, Theme)` — theme-aware (dark `#1e1e2e` / light `#ffffff` CSS palettes). `AiChatPlugin` derives the WebView background from the active theme and re-renders the whole conversation live when the theme flips. Auto-resize height to content.

**AI tools**: Plugins self-declare AI tools via `ZhiFlowPlugin.aiTools()` (v3.1.0+); the `PluginRegistry` auto-registers/unregisters them with `AiServiceProvider` on plugin add/remove (including hot-reload). Use `ToolExecutor` + `ToolSchemaBuilder` for execution and schema generation. See "### Plugin AI tools (v3.1.0+)" below for the full pattern.

**Local tool-calling model**: Qwen3-4B (Hermes `<tool_call>` format, displayed `<think>` reasoning). Detected by filename containing `qwen3`; routed via `LocalChatBackend.chatQwen3Native` + `ThinkingStreamSegmenter` (splits the token stream into THINK/CONTENT regions, suppresses `<tool_call>`) + `Qwen3Adapter` (Hermes system-prompt directive + `/no_think` toggle). THINK segments stream to `AiStreamCallback.onThinking` and render as collapsed cards (`MarkdownRenderer.renderCollapsible`); thinking is stripped (`ThinkingStreamSegmenter.stripThink`) before history/answer so it never enters the next prompt. Tool-call parsing for Qwen2.5 / Qwen3 / generic all live in `ToolCallParser`. FunctionGemma support was removed in v3.1.0.

## Reusable UI Component: StepWizard

`fan.summer.api.component.StepWizard` (in `ZhiFlow-Api`) is a ready-made multi-step wizard container for use inside any plugin's `createView()`.

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  step1Node, () -> filePath != null);
wizard.addStep("Split mode",   step2Node, () -> modeSelected);
wizard.addStep("Output path",  step3Node, () -> outputPath != null);
wizard.build();   // must call after all addStep() calls

// Intercept step transitions (e.g., trigger async work when moving from step 0 → 1):
wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) startAnalysis();
});

// Programmatic navigation:
wizard.goTo(2);
boolean last = wizard.isLastStep();
```

The wizard renders step dots with done/active/idle states, animated slide transitions between steps, and Back/Next buttons. The `canProceed` supplier is evaluated on every Next click — return `false` to trigger a shake animation and block advancement.

## Plugin Development

**Interface**: `fan.summer.api.ZhiFlowPlugin` (in `ZhiFlow-Api`)

```java
public interface ZhiFlowPlugin {
    String getId();                       // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    ToolCategory getCategory();           // DEV / TEXT / IMAGE / NET / OTHER
    String getVersion();
    String getMdiIcon();                  // Material Design Icons name, e.g. "file-excel"
    default IconStyle getIconStyle() { return IconStyle.BLUE; }   // maps to ic-* CSS class
    default ToolType getType()     { return ToolType.PLUGIN; }    // PLUGIN / BUILTIN
    default void init(PluginHost host) {}  // v3.2.0+: host facade (settings/tasks/i18n/theme/notifications)

    Node createView();                    // called once; result cached and reused
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}

    // Background-task lifecycle (defaults are no-ops):
    default boolean hasRunningTasks() { return false; }
    default void onBackground() {}      // entered background while tasks running
    default void onForeground() {}      // restored from background
}
```

**PluginHost (v3.2.0+)**: injected via `init(PluginHost)` exactly once (FX thread, before the
plugin is visible in the registry and before `aiTools()` registration). Provides `settings()`
(namespaced KV, H2-backed; preview mode uses `~/.zhiflow/preview-settings/`), `tasks()`
(TCCL-safe background tasks — running tasks automatically keep the plugin backgrounded, merged
with `hasRunningTasks()` via `PluginRegistry.isBusy`), `i18n()` (`registerBundle` without a
ClassLoader parameter), `theme()`, `notifications()`, `logger()`. Old static entry points remain
valid. See `docs/plugins/plugin-host.md`.

**External plugins** (JAR-based):
1. Implement `ZhiFlowPlugin`
2. Declare in `META-INF/services/fan.summer.api.ZhiFlowPlugin`
3. Drop JAR into `.zhiflow/plugin/` directory; hot-reload is supported

**Built-in tools** skip SPI entirely — `BuiltinToolRegistrar.register()` adds them directly to `PluginRegistry`. See existing tools there as templates.

### Plugin logging

Plugins should use `fan.summer.api.log.LoggerFactory` (in `ZhiFlow-Api`) rather than depending on SLF4J directly. The host installs a binder at startup that routes plugin log calls into the same SLF4J + Logback backbone used by the host (console at INFO+, rolling file at DEBUG+ under `.zhiflow/logs/zhiflow.log`, daily rotation, 7-day retention).

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class MyPlugin implements ZhiFlowPlugin {
    private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

    @Override public void onActivate() {
        log.info("Activated, taskId={}", currentTaskId);
    }
}
```

Use SLF4J-style `{}` placeholders — formatting is deferred until the level is actually enabled. If the host has not installed a binder (e.g. plugin unit tests), `LoggerFactory` returns a silent no-op logger, so it is safe to call from anywhere.

### Plugin AI tools (v3.1.0+)

Plugins can expose AI tools by overriding the default `aiTools()` method:

```java
public class MyPlugin implements ZhiFlowPlugin {
    @Override
    public List<AiTool> aiTools() {
        return List.of(new MyAiTool(this));
    }
}
```

Tools are auto-registered with `AiServiceProvider` when the plugin is added to the registry, and auto-unregistered on plugin removal (including JAR hot-reload). No manual registration needed — the old `BuiltinAiToolRegistrar` is gone.

#### Cloud / local capability declaration

Each `AiTool` can declare its visibility per backend mode:

```java
public class MyAiTool implements AiTool {
    // ... getName, getDescription, getParameters, execute ...

    @Override public boolean supportsLocal() { return false; }  // hide from local (Qwen3-4B)
    @Override public boolean supportsCloud() { return true; }   // visible in cloud (default)

    @Override public String getLocalDescription() {
        return "Short Qwen3-friendly description with enum: a|b|c.";
    }

    @Override public List<AiToolParam> getLocalParameters() {
        // Simplified schema for local mode
        return List.of(AiToolParam.of("x", "string", "X"));
    }
}
```

Filter rules:
- Local mode (Qwen3-4B): tools with `supportsLocal()==true` only.
- Cloud mode (OpenAI/Anthropic): tools with `supportsCloud()==true` only.
- Switching mode takes effect on the next chat call — no re-registration.

#### Tool return JSON contract

All tools return JSON via `AiToolResult.success(jsonString)`:

```json
{ "success": true, "summary": "<one-line summary>", ...payload }
```

On error, use `AiToolResult.error(jsonString)`:

```json
{ "success": false, "error": "<message>" }
```

The `summary` field is what the model primarily reads; payload fields are read on demand. This keeps small models like Qwen3-4B from drowning in detail.

## JavaFX Layout Pitfalls

The app shell relies on deeply nested `StackPane` / `HBox` / `VBox` / `ScrollPane` / `BorderPane` containers. The following layout traps have all caused real bugs in this codebase — review them before changing any plugin/page layout.

### 1. `Control.maxWidth` defaults to `USE_COMPUTED_SIZE`, not `MAX_VALUE`

`ScrollPane`, `Button`, `ProgressBar`, and every other `Control` subclass have `maxWidth = USE_COMPUTED_SIZE` by default — meaning `getMaxWidth()` returns `prefWidth`. Inside a `StackPane` (or any parent that tries to stretch children), a `Control` therefore stops growing at its preferred size and leaves the rest of the area unused. Pane subclasses (`VBox`, `HBox`, `StackPane`, `Pane`) default to `Double.MAX_VALUE` and stretch correctly.

When a `Control` needs to fill its parent (e.g. a page-level `ScrollPane` inside a `StackPane`):
```java
sp.setMaxWidth(Double.MAX_VALUE);
sp.setMaxHeight(Double.MAX_VALUE);
```

### 2. Never set `prefWidth = Double.MAX_VALUE` on any control

Use `setMaxWidth(Double.MAX_VALUE)` plus `HBox.setHgrow(node, Priority.ALWAYS)` (or `VBox.setVgrow`) instead. `prefWidth = MAX_VALUE` poisons the parent chain — every ancestor's `prefWidth` becomes infinite, which makes `ScrollPane.fitToWidth` and viewport sizing collapse so the whole page renders at its minimum width. This bug bit the plugin store install row: a `ProgressBar` with `setPrefWidth(Double.MAX_VALUE)` made the entire `OnlineStorePane` content collapse to ~160px once plugin cards were displayed.

Correct pattern for "fill the rest of an HBox":
```java
ProgressBar bar = new ProgressBar();
bar.setMaxWidth(Double.MAX_VALUE);     // allow stretching
HBox.setHgrow(bar, Priority.ALWAYS);   // ask the HBox to give it the leftover space
```

### 3. Never bind `maxWidthProperty` to the node's own `widthProperty`

This pattern is a circular dependency:
```java
desc.maxWidthProperty().bind(widthProperty().subtract(48));  // ❌
```

On the first layout pass, `widthProperty()` is `0`, so `maxWidth` becomes `-48`, and the layout converges to the node's `minWidth`. Use this instead:
```java
desc.setWrapText(true);
desc.setMaxWidth(Double.MAX_VALUE);    // ✅ let the parent VBox constrain the width
```

For a wrapping `Label` inside a `VBox` with padding, `setMaxWidth(Double.MAX_VALUE) + setWrapText(true)` is sufficient — the `VBox` already constrains the label to its inner width, and the label wraps at that width.

### 4. CSS stylesheet rules override Java property setters

Inline styles (`setStyle(...)`) override stylesheet rules, but Java property setters (`setPrefWidth`, `setMinWidth`, `setMaxWidth`, ...) do NOT — stylesheet wins. The shell's `.sidebar` CSS class declares `-fx-min-width: 200px; -fx-pref-width: 220px; -fx-max-width: 260px;`, so re-using `.sidebar` on a nested navigation pane silently forces that pane to 200–260px regardless of any `setPrefWidth(180)` calls in Java.

When you need different dimensions for a visually-similar pane, either pick a new style class or set the appearance via `setStyle(...)`:
```java
sidebar.setPrefWidth(180);
sidebar.setStyle(
    "-fx-background-color: rgba(255,255,255,0.022);" +
    "-fx-border-color: rgba(255,255,255,0.10);" +
    "-fx-border-width: 0 1 0 0;"
);
```

### 5. Toggling page visibility in a `StackPane`: also toggle `managed`

`setVisible(false)` hides a node but leaves it `managed`, so it still contributes to the `StackPane`'s preferred size and consumes layout cycles. When swapping pages, toggle both:
```java
for (int j = 0; j < pages.length; j++) {
    pages[j].setVisible(j == idx);
    pages[j].setManaged(j == idx);
}
```

### 6. `stage.isMaximized()` is unreliable on macOS `StageStyle.TRANSPARENT`

> **v3.2.0:** The main window is now `StageStyle.DECORATED` (native OS chrome) and `WindowResizeHelper` has been deleted, so this pitfall **no longer affects the app shell**. The caution below still applies to any plugin that opens its own transparent/undecorated `Stage`.

JavaFX on macOS reports `stage.isMaximized() == true` from app start for transparent/undecorated stages, even though the window is visibly not maximized (and `stage.getWidth()/getHeight()` confirm normal size). Any code that gates behavior on `isMaximized()` will silently fail.

This bit `WindowResizeHelper`: an early-bail `if (stage.isMaximized()) return;` killed cursor changes AND drag-resize, making it look like mouse events weren't reaching the scene at all. The fix is to not consult `isMaximized()` at all in resize logic — an edge drag on a truly-maximized window naturally un-maximizes via `stage.setX/setWidth`, which is the correct UX. The maximize button (`stage.setMaximized(!stage.isMaximized())`) still works because the toggle ends up correct after a click.

If you genuinely need to know whether the stage is maximized, track it yourself via a listener on `stage.maximizedProperty()` *changes* rather than reading the current value.

### Checklist before changing any page/plugin layout

- [ ] If you add a `ScrollPane` inside a `StackPane`, set `setMaxWidth(Double.MAX_VALUE)` and `setMaxHeight(Double.MAX_VALUE)`.
- [ ] If you want a node to "fill the rest", use `setMaxWidth(Double.MAX_VALUE)` + `HBox/VBox.setHgrow/Vgrow(node, Priority.ALWAYS)`. Never `setPrefWidth(Double.MAX_VALUE)`.
- [ ] No binding of `maxWidthProperty` to the node's own `widthProperty` (or any property of an ancestor that itself depends on the node's size).
- [ ] If you re-use a shell CSS class (`.sidebar`, `.tool-card`, etc.) on a different component, verify the CSS doesn't impose size constraints you didn't intend; otherwise use a fresh class or inline style.
- [ ] When swapping `StackPane` children, toggle both `setVisible` and `setManaged`.
- [ ] Never branch on `stage.isMaximized()` for `StageStyle.TRANSPARENT` windows on macOS — it lies (moot for the v3.2.0 `DECORATED` shell; still relevant for plugin-opened transparent stages). Track maximization state from the maximize toggle instead.

## Branch Status — v3.0.0-JavaFX

This is the JavaFX codebase (the Swing/FlatLaf port shipped in 3.0.0). Legacy Swing classes remain in `backup/ZhiFlow/` and `backup/ZhiFlow-Api/` under the project root as a porting reference, and are **excluded from Maven compilation** via `<excludes>` in `ZhiFlow/pom.xml`. Do not move files out of `backup/` — treat them as read-only reference for any tool whose JavaFX port still needs work.

The plugin interface was also renamed: the old `fan.summer.api.KitPage` (Swing `JPanel`-based) is replaced by `fan.summer.api.ZhiFlowPlugin` (JavaFX `Node`-based).

## Excel Splitter — Porting Reference

The backup Swing implementation at `backup/ZhiFlow/java/fan/summer/kitpage/excel/` is the authoritative reference for the Excel split logic. Key classes:

| Backup class | Role |
|---|---|
| `ExcelKitPage` | Top-level Swing page (UI only — replace with JavaFX + StepWizard) |
| `ExcelAnalysisWorker` | Reads all sheets + row-0 headers via Apache POI → `Map<String, Map<Integer, String>>` |
| `ExcelSplitWorker` | Three split modes — see below |
| `NoModelDataListener` | Apache Fesod `AnalysisEventListener` that caches rows as `List<Map<Integer,Object>>` |
| `ExcelUtil` | POI helpers: `appendSheet`, `appendDataRowsByPoi`, `copyEntireSheet`, `normalizeOrInvalid` |
| `FileNameUtil` | `getFileName(String)` — strips extension |

**Three split modes** (set via `ExcelSplitWorker.setXxxModel()`):

| Mode key | Method | What it does |
|---|---|---|
| `SSM` | `setSplitSheetModel(Set<String> sheets)` | One output file per selected sheet |
| `SCM` | `setSplitColumnModel(String sheet, String column)` | Groups rows by unique column value → one file per value |
| `SCPM` | `setComplexSplitModel(String taskId)` | Multi-config: reads `ComplexSplitConfigEntity` rows from H2; supports normal split + copy-all (headerIndex==-1 && columnIndex==-1) |

**Analysis result map shape**: `Map<sheetName, Map<columnIndex, columnHeader>>` — the outer key is sheet name (insertion order preserved via `LinkedHashMap`), the inner key is the zero-based column index from POI.

**Fesod library** (`org.apache.fesod:fesod-sheet`) is used for high-throughput reading/writing in split operations. Core pattern:
```java
// Read
NoModelDataListener listener = new NoModelDataListener();
try (ExcelReader reader = FesodSheet.read(file).build()) {
    ReadSheet sheet = FesodSheet.readSheet(sheetName)
        .headRowNumber(headerRowIndex)
        .registerReadListener(listener).build();
    reader.read(sheet);
}
List<Map<Integer, Object>> rows = listener.getCachedDataList();

// Write
FesodSheet.write(outputFile)
    .sheet(sheetName)
    .head(buildHeaders(headerMap))   // List<List<String>>
    .doWrite(buildRows(headerMap, rows));  // List<List<Object>>
```

**Complex split DB entity**: `ComplexSplitConfigEntity` fields — `taskId`, `fieldName` (original filename), `sheetName`, `headerIndex` (1-based row of headers), `columnIndex` (1-based column to split by). A row with `headerIndex == -1 && columnIndex == -1` means "copy entire sheet to all output files".

## Custom Commands

- `/docs-updater` — Updates version numbers and content across `docs/`, `README.md`, and `CHANGELOG.md` to match current pom.xml versions.
- `/release <version>` — Full release workflow: bumps all pom.xml versions, updates `CHANGELOG.md`, commits, creates and pushes a `v{version}` git tag, which triggers GitHub Actions for multi-platform builds.

## Commit Convention

Follow conventional commits with emojis:
- `✨` / `:sparkles:` — new feature
- `🐛` / `:bug:` — bug fix
- `♻️` / `:recycle:` — refactor
- `📝` / `:memo:` — documentation
- `⬆️` / `:arrow_up:` — dependency upgrade
