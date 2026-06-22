# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

All modules have **standalone POMs** with no parent dependency — each can be built independently.

**No system Maven installed.** All Maven operations (compile, package, install, clean) must go through **IntelliJ IDEA's built-in Maven** — either the Maven tool window (right sidebar) or the IDEA MCP tools (`mcp__idea__build_project`, `mcp__idea__execute_terminal_command`). Never run `mvn` in a regular shell — it will fail.

```bash
# Build and install the API module (required first — other modules depend on it)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Build the main app
mvn clean package -f SwissKit/pom.xml -DskipTests

# Run the application
java -jar SwissKit/target/SwissKitJ-3.0.0.jar
```

To build all modules from the repo root (root POM is a simple aggregator):
```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests && mvn clean package -f SwissKit/pom.xml -DskipTests
```

On Windows, the `windows-exe` Maven profile is auto-activated and produces `SwissKit.exe` via Launch4j. GitHub Actions handles multi-platform builds — local Maven is not required for releases.

## Module Structure

| Module | Purpose |
|--------|---------|
| `SwissKitJ-Api` | Shared plugin interface + plugin context isolation + AI service contract + reusable UI components (`SwissKitJPlugin`, `PluginContext`, `AiService`/`AiTool`, `StepWizard`) |
| `SwissKit` | Main JavaFX application — UI shell, plugin loading, built-in tools |

Official plugins live in a separate repository: [MuskStark/SwissKiJ-Plugin](https://github.com/MuskStark/SwissKiJ-Plugin). They are built independently and dropped into `.swisskit/plugin/` as JARs at runtime. All plugins declare `SwissKitJ-Api` as `provided` scope. The main app provides it at runtime via the fat JAR.

## Architecture

**Entry point**: `fan.summer.Launcher` (fat-JAR manifest) → `fan.summer.app.SwissKitJApp` (JavaFX `Application`).

**Startup sequence** (in `SwissKitJApp.start()`):
1. Install the plugin logger binder; init H2/MyBatis; apply the saved i18n language
2. Resolve the plugins directory (`<user.dir>/.swisskit/plugin/`)
3. Create `PluginLoader` + `PluginRegistry`
4. Create `FavoriteService` (loads bookmarked plugin IDs from DB)
5. Register built-in tools via `BuiltinToolRegistrar` (bypasses JAR loading, directly adds to registry)
6. Initialize cloud AI backends if mode is `openai`/`anthropic`; **local mode is lazy** — deferred until the AI tool is first opened
7. Register built-in AI tools via `BuiltinAiToolRegistrar`
8. Build `MainWindow` and display it
9. Start `PluginLoader` (scans the plugins dir and watches for changes)

> Steps 5→7 are order-coupled: `BuiltinAiToolRegistrar` looks up `ExcelSplitterPlugin`/`EmailArchivePlugin` from the live registry, so those AI tools only register if the built-in tools registered first.

**UI structure** (all in `fan.summer.ui.*`):
- `MainWindow` — root `StackPane`; owns `TitleBar`, `Sidebar`, `ContentArea`, status bar
- `Sidebar` — category-based navigation; categories are `all / text / image / dev / net / other / favorites`
- `ContentArea` — shows `ToolCard` grid or active tool view; manages `DetailPanel` and the back-bar for returning from a tool
- `DetailPanel` — slide-in panel showing plugin metadata; has a Launch button that fires `onLaunch`
- `TitleBar` — custom window chrome (window is `StageStyle.TRANSPARENT`)

**Navigation flow**: `ToolCard` click → `DetailPanel.show()` → Launch button → `MainWindow.wireEvents` callback → `registry.activate(plugin)` + `contentArea.showPage(plugin.createView(), title)`. The back bar (shown by `ContentArea`) calls `registry.deactivate()` on return.

**Theming**: Three-layer CSS structure (glassmorphism dark theme).

| File | Module | Scope |
|---|---|---|
| `css/swisskit-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, progress bar, `.glass-*` utility classes (dialog/field/tab-pane/combo/table/checkbox/btn-primary/btn-secondary), `.section-title`/`.section-header`. Loaded into the main Scene + available to any third-party plugin. |
| `css/shell.css` | `SwissKit` | App-shell only — `.titlebar`, `.sidebar`, `.search-bar`, `.tool-card`, `.detail-panel`, `.statusbar`. Loaded into the main Scene by `SwissKitJApp`. |
| `css/builtin.css` | `SwissKit` | Reserved for built-in tool styling. Currently empty placeholder. |

Plugins embedded in the main Scene (the normal `createView()` flow) automatically inherit all three stylesheets via scene graph propagation — no action needed. Plugins that open their own `Stage`/`Scene` should call `fan.summer.api.theme.Themes.applyTo(scene)` to get the common utility classes.

Plugin icon background colors are CSS classes: `ic-blue / ic-purple / ic-teal / ic-amber / ic-red / ic-pink / ic-gray` (declared in `shell.css`; actual color injection happens in Java via `DropShadow` per `IconStyle`).

**Database**: H2 file at `.swisskit/swisskit.db` relative to the runtime working directory. Schema initialized from `init.sql`. Accessed via MyBatis; mapper XMLs are in `src/main/resources/mapper/`.

**i18n**: `src/main/resources/i18n/messages.properties` (Chinese default), `messages_en.properties` (English).

**JSON**: Use `fan.summer.api.json.JsonHelper` (Gson-based). Old `JsonBuilder`/`JsonParser` are deleted.

**AI Markdown**: AI responses render via `WebView` with dark theme `#1e1e2e`; auto-resize height to content.

**AI tools**: Register via `BuiltinAiToolRegistrar`; use `ToolExecutor` + `ToolSchemaBuilder` for execution and schema generation.

**Local tool-calling model**: Qwen3-4B (Hermes `<tool_call>` format, displayed `<think>` reasoning). Detected by filename containing `qwen3`; routed via `LocalChatBackend.chatQwen3Native` + `ThinkingStreamSegmenter` (splits the token stream into THINK/CONTENT regions, suppresses `<tool_call>`) + `Qwen3Adapter` (Hermes system-prompt directive + `/no_think` toggle). THINK segments stream to `AiStreamCallback.onThinking` and render as collapsed cards (`MarkdownRenderer.renderCollapsible`); thinking is stripped (`ThinkingStreamSegmenter.stripThink`) before history/answer so it never enters the next prompt. Tool-call parsing for Qwen2.5 / Qwen3 / generic all live in `ToolCallParser`. FunctionGemma support was removed in v3.1.0.

## Reusable UI Component: StepWizard

`fan.summer.api.component.StepWizard` (in `SwissKitJ-Api`) is a ready-made multi-step wizard container for use inside any plugin's `createView()`.

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

**Interface**: `fan.summer.api.SwissKitJPlugin` (in `SwissKitJ-Api`)

```java
public interface SwissKitJPlugin {
    String getId();                       // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    ToolCategory getCategory();           // DEV / TEXT / IMAGE / NET / OTHER
    String getVersion();
    String getMdiIcon();                  // Material Design Icons name, e.g. "file-excel"
    default IconStyle getIconStyle() { return IconStyle.BLUE; }   // maps to ic-* CSS class
    default ToolType getType()     { return ToolType.PLUGIN; }    // PLUGIN / BUILTIN

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

**External plugins** (JAR-based):
1. Implement `SwissKitJPlugin`
2. Declare in `META-INF/services/fan.summer.api.SwissKitJPlugin`
3. Drop JAR into `.swisskit/plugin/` directory; hot-reload is supported

**Built-in tools** skip SPI entirely — `BuiltinToolRegistrar.register()` adds them directly to `PluginRegistry`. See existing tools there as templates.

### Plugin logging

Plugins should use `fan.summer.api.log.LoggerFactory` (in `SwissKitJ-Api`) rather than depending on SLF4J directly. The host installs a binder at startup that routes plugin log calls into the same SLF4J + Logback backbone used by the host (console at INFO+, rolling file at DEBUG+ under `.swisskit/logs/swisskit.log`, daily rotation, 7-day retention).

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class MyPlugin implements SwissKitJPlugin {
    private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

    @Override public void onActivate() {
        log.info("Activated, taskId={}", currentTaskId);
    }
}
```

Use SLF4J-style `{}` placeholders — formatting is deferred until the level is actually enabled. If the host has not installed a binder (e.g. plugin unit tests), `LoggerFactory` returns a silent no-op logger, so it is safe to call from anywhere.

## JavaFX Layout Pitfalls

The glassmorphism shell relies on deeply nested `StackPane` / `HBox` / `VBox` / `ScrollPane` containers. The following layout traps have all caused real bugs in this codebase — review them before changing any plugin/page layout.

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

JavaFX on macOS reports `stage.isMaximized() == true` from app start for transparent/undecorated stages, even though the window is visibly not maximized (and `stage.getWidth()/getHeight()` confirm normal size). Any code that gates behavior on `isMaximized()` will silently fail.

This bit `WindowResizeHelper`: an early-bail `if (stage.isMaximized()) return;` killed cursor changes AND drag-resize, making it look like mouse events weren't reaching the scene at all. The fix is to not consult `isMaximized()` at all in resize logic — an edge drag on a truly-maximized window naturally un-maximizes via `stage.setX/setWidth`, which is the correct UX. The maximize button (`stage.setMaximized(!stage.isMaximized())`) still works because the toggle ends up correct after a click.

If you genuinely need to know whether the stage is maximized, track it yourself via a listener on `stage.maximizedProperty()` *changes* rather than reading the current value.

### Checklist before changing any page/plugin layout

- [ ] If you add a `ScrollPane` inside a `StackPane`, set `setMaxWidth(Double.MAX_VALUE)` and `setMaxHeight(Double.MAX_VALUE)`.
- [ ] If you want a node to "fill the rest", use `setMaxWidth(Double.MAX_VALUE)` + `HBox/VBox.setHgrow/Vgrow(node, Priority.ALWAYS)`. Never `setPrefWidth(Double.MAX_VALUE)`.
- [ ] No binding of `maxWidthProperty` to the node's own `widthProperty` (or any property of an ancestor that itself depends on the node's size).
- [ ] If you re-use a shell CSS class (`.sidebar`, `.tool-card`, etc.) on a different component, verify the CSS doesn't impose size constraints you didn't intend; otherwise use a fresh class or inline style.
- [ ] When swapping `StackPane` children, toggle both `setVisible` and `setManaged`.
- [ ] Never branch on `stage.isMaximized()` for `StageStyle.TRANSPARENT` windows on macOS — it lies. Track maximization state from the maximize toggle instead.

## Branch Status — v3.0.0-JavaFX

This is the JavaFX codebase (the Swing/FlatLaf port shipped in 3.0.0). Legacy Swing classes remain in `backup/SwissKit/` and `backup/SwissKitJ-Api/` under the project root as a porting reference, and are **excluded from Maven compilation** via `<excludes>` in `SwissKit/pom.xml`. Do not move files out of `backup/` — treat them as read-only reference for any tool whose JavaFX port still needs work.

The plugin interface was also renamed: the old `fan.summer.api.KitPage` (Swing `JPanel`-based) is replaced by `fan.summer.api.SwissKitJPlugin` (JavaFX `Node`-based).

## Excel Splitter — Porting Reference

The backup Swing implementation at `backup/SwissKit/java/fan/summer/kitpage/excel/` is the authoritative reference for the Excel split logic. Key classes:

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
