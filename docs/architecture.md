# Architecture

SwissKitJ is a modular, plugin-based desktop toolkit built on JavaFX 21 (JDK 21).
The host application depends only on a small plugin interface; every concrete
tool — whether built-in or shipped as an external JAR — implements the same
`SwissKitJPlugin` contract.

```
┌──────────────────────────────────────────────────────────────────┐
│  External JAR plugins (separate repo, provided scope)             │
│  implement SwissKitJPlugin, dropped into .swisskit/plugin/        │
└───────────────────────────────┬──────────────────────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  SwissKitJ-Api  —  public contract layer (no business logic)      │
│  SwissKitJPlugin · ToolCategory/Type/IconStyle · PluginContext     │
│  AiService/AiTool/AiChatMessage · I18n · Themes · LoggerFactory    │
│  StepWizard · SkNotification · UiUtils · preview components     │
└───────────────────────────────▲──────────────────────────────────┘
                                │ bundled in the fat JAR (provided → runtime)
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  SwissKit  —  JavaFX application shell + built-in tools           │
│  UI Shell · Plugin Layer (Loader/Registry/Context/Favorites)      │
│  AI Subsystem (2 backends · tools · local inference)              │
│  H2 + MyBatis · i18n · Logback · JsonHelper                       │
└──────────────────────────────────────────────────────────────────┘
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `SwissKitJ-Api` | Shared plugin interface (`SwissKitJPlugin`), plugin context & isolation (`PluginContext`), reusable components (`StepWizard`, `SkNotification`, `UiUtils`), theming, i18n, logging API, and the AI service contract (`ChatBackend`, `AiTool`, message records) |
| `SwissKit` | JavaFX application shell — UI, plugin loading, favorites, the AI subsystem, and all built-in tools |

Official plugins live in a [separate repository](https://github.com/MuskStark/SwissKiJ-Plugin). They are built independently and dropped into `.swisskit/plugin/` as JARs at runtime. All plugins declare `SwissKitJ-Api` as `provided` scope; the main app provides it at runtime via the fat JAR.

## Startup Sequence

`fan.summer.Launcher` (fat-JAR manifest entry point) primes the log directory, then
delegates to `fan.summer.app.SwissKitJApp` (JavaFX `Application`). The launcher is a
separate non-`Application` class so the app runs in classpath mode (compatible with
the fat-JAR layout and the JavaFX module system).

In `SwissKitJApp.start()`:

1. Install the plugin logger binder (`LoggerBinder.bind`) so plugin logs route into the shared SLF4J/Logback backbone
2. Initialize the H2 database via MyBatis (`DatabaseInit.init`)
3. Register the i18n bundle and apply the saved language preference
4. Resolve the plugins directory (`<user.dir>/.swisskit/plugin/`)
5. Create `PluginLoader` + `PluginRegistry` (the registry wires itself to the loader)
6. Create `FavoriteService` (loads bookmarked plugin IDs from the database)
7. Register built-in tools via `BuiltinToolRegistrar` — this routes the list through `PluginRegistry.addPlugins`, which also auto-registers each plugin's `aiTools()` with `AiServiceProvider`
8. Initialize cloud AI backends (OpenAI/Anthropic) if the saved mode is `openai`/`anthropic`; **local mode is deferred** until the AI tool is first opened
9. Build and display the `MainWindow`
10. Attach `WindowResizeHelper` for edge/corner drag resize
11. Start `PluginLoader` (scans the plugins dir and watches for changes)

> As of v3.1.0 there is no separate AI-tool registration step. Plugins self-declare their
> AI tools via `SwissKitJPlugin.aiTools()`, and the registry handles registration on add
> and unregistration on remove (including hot-reload). The old `BuiltinAiToolRegistrar`
> class has been removed.

## UI Structure

| Component | Role |
|-----------|------|
| `MainWindow` | Root `StackPane`; owns `TitleBar`, `Sidebar`, `ContentArea`, status bar; composes animated background orbs |
| `Sidebar` | Category-based navigation with search; categories: all / text / image / dev / net / other / favorites |
| `ContentArea` | Shows the `ToolCard` grid or an active tool view; manages the `DetailPanel` overlay and the back-bar |
| `DetailPanel` | Slide-in panel showing plugin metadata, with Launch, Favorite toggle, and Uninstall (external plugins only) buttons |
| `TitleBar` | Custom window chrome (window is `StageStyle.TRANSPARENT`) |

### Navigation Flow

`ToolCard` click → `DetailPanel.show()` → Launch button → `registry.activate(plugin)` +
`contentArea.showPage(plugin.createView(), title)`. The returned `Node` is created once
and cached by `MainWindow`. The back bar calls `registry.deactivate()` on return; if the
plugin reports running background tasks, it is moved to the background set instead of
being fully deactivated.

## Plugin System

### Interface

```java
public interface SwissKitJPlugin {
    String getId();                       // reverse-domain ID
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

`ToolCategory`, `ToolType`, and `IconStyle` are enums (each value carries a lowercase
`id`/CSS class used for serialisation and styling). `createView()` is invoked exactly
once per plugin; the host caches the returned `Node`.

### Registration

- **Built-in tools**: registered directly by `BuiltinToolRegistrar` (10 tools: AI Chat,
  JSON/Base64/Hash dev tools, Excel Splitter, Color Converter, Markdown Editor, Email,
  Email Archive, PDF). No SPI involved.
- **External plugins**: implement `SwissKitJPlugin`, declare it in
  `META-INF/services/fan.summer.zhiflow.api.SwissKitJPlugin`, and drop the JAR into
  `.swisskit/plugin/`. Hot-reload is supported via a directory watcher.

### Plugin Loading & Class Isolation

Each external JAR is loaded by a dedicated `ChildFirstResourceClassLoader`, a
`URLClassLoader` that resolves **resources child-first** but keeps **class loading
parent-first**:

- *Resources* (`mybatis-config.xml`, `init.sql`, `mapper/*.xml`, i18n bundles) are taken
  from the plugin JAR first, so a host resource with the same name cannot shadow a
  plugin-bundled one.
- *Classes* are still resolved parent-first, so shared API types like `SwissKitJPlugin`
  resolve to the same `Class` objects the host loaded — keeping `ServiceLoader`, casts,
  and `instanceof` working.

Before opening a `URLClassLoader`, the loader copies the JAR to a temporary file. The
`ClassLoader` is bound to the temp copy, so the **original JAR is never file-locked**
(essential on Windows) and can be deleted immediately on uninstall.

Hot-reload is debounced (1.5 s) and runs on a dedicated scheduler thread so rapid
`ENTRY_MODIFY` events coalesce and the watch thread is never blocked. A `cleanupStaleTempCopies()`
pass on startup removes temp JARs left behind by a previous crash. `uninstallPlugin(plugin)`
unloads the JAR and deletes the original file.

### Plugin Context (thread-context ClassLoader)

Each loaded plugin is registered with `fan.summer.zhiflow.api.PluginContext`, which associates
the plugin instance with its `ClassLoader` (held via `WeakReference` so a missed
`unregister()` still allows GC). The host wraps every call into a plugin so the correct
ClassLoader is on the thread-context ClassLoader (TCCL):

```java
Node view = PluginContext.callWith(plugin, plugin::createView);   // TCCL set + restored
PluginContext.wrapEvents(plugin, view);                           // EventDispatcher wrapped
```

`wrapEvents` replaces the node's `EventDispatcher` so background threads spawned from
event handlers inherit the right TCCL — letting plugin code use `ServiceLoader`, MyBatis,
and resource-bundle lookups without any ClassLoader awareness. Lifecycle callbacks
(`onUnload`) are likewise invoked through `PluginContext.runWith`.

### Favorites

`FavoriteService` holds an in-memory `ObservableSet<String>` of favorited plugin IDs,
loaded from the `plugin_favorites` table at startup. All mutations (`toggle`/`add`/`remove`)
persist immediately to the database and fire a change callback (on the JavaFX thread).
UI components observe the set reactively. It is a singleton (`FavoriteService.getInstance()`).

### Plugin Logging

Plugins use `fan.summer.zhiflow.api.log.LoggerFactory`, which routes to SLF4J/Logback when the
host is running (rolling file under `.swisskit/logs/swisskit.log`, daily rotation, 7-day
retention) and returns a silent no-op logger in tests. Use SLF4J-style `{}` placeholders.

## AI Subsystem

### Service abstraction

`ChatBackend` (`SwissKitJ-Api`) is the inference contract: `loadModel`/`unloadModel`/`isReady`,
streaming `chat(history, callback)`, generation control (`cancelGeneration`/`isGenerating`).
Tool registration is global via `AiServiceProvider` (no longer on the backend interface itself).
`AiServiceProvider` is the **static singleton** that holds the active backend, the current
mode label, state-change listeners, and the global tool registry. Mode switches go through
`switchMode(mode, service)`, which unloads the previous backend before installing the new
one and notifies listeners.

There are two backends, both implementing the `ChatBackend` interface:

| Backend | When | Notes |
|---------|------|-------|
| `LocalChatBackend` | local mode | GGUF inference. Native path runs in a **child JVM process** (`NativeWorkerClient`) so a native crash cannot kill the host; crashes ≤3 trigger an auto-restart, ≥3 trigger a fallback to the pure-Java engine. Configured as `java` or `native` via `ai.local.backend`. **Lazy-loaded** the first time the AI tool opens. |
| `CloudChatBackend` | openai / anthropic mode | Unified cloud backend for both providers. Constructed via `CloudChatBackend.openAi(...)` or `.anthropic(...)` static factories. Backed by LangChain4j's `OpenAiStreamingChatModel` / `AnthropicStreamingChatModel`. HTTP/SSE, tool-loop plumbing, and stream bridging are entirely delegated to LangChain4j; the host only provides `AiChatMessage`↔LC4j mapping and the multi-round tool loop driver. |

AI settings are read through `AiConfigService` (DB-direct, no UI dependency) so the startup
path and AI services never depend on the settings UI class.

### Tool calling

The model can invoke tools during generation. Each `AiTool` declares a name, description,
parameter list, and an `execute(Map) → AiToolResult`. `ToolExecutor` dispatches calls and
feeds results back into the conversation history; the multi-round loop is bounded at
`MAX_TOOL_ROUNDS = 5` in every backend. Schema generation is split: cloud backends
(OpenAI / Anthropic) use `AiToolToToolSpecification` to build LangChain4j
`ToolSpecification` objects (structurally forwarded to the API); local mode uses
`ToolSchemaBuilder` to inject a markdown section into the system prompt. Local models emit
tool calls as text, parsed by `ToolCallParser` (Qwen delimiter and generic-JSON patterns).
Callbacks (`onToken`, `onToolCall`, `onToolResult`, `onComplete`, `onError`) are always
delivered on the JavaFX Application Thread.

### Slash commands

`SlashCommandHandler` lets the AI chat run a guided, single-tool call: it calls
`AiServiceProvider.setConstrainedTool(name)` so `getTools()` returns **only that one tool**,
letting a small local model focus on exactly the tool the user requested. The constraint is a
global `volatile` field (inference runs on a virtual thread that does not inherit thread-local
state) and **must be cleared** in the `onComplete`/`onError` callback.

### Browser automation planner (important invariant)

The `browser_automate` tool runs an observe→think→act loop using the configured AI service
as a planner. The planner call deliberately **bypasses `AiService.chat()`** and makes a direct,
no-tools HTTP request (`SynchronousChatHelper`). If it went through the normal chat path, the
planner would see `browser_automate` itself as a registered tool and recurse into it. Because
of this direct-call approach, the planner currently supports **OpenAI-compatible backends only**.

## CSS Theming

Three-layer glassmorphism dark theme:

| File | Module | Scope |
|------|--------|-------|
| `css/zhiflow-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, progress bar, `.glass-*` utility classes, `.section-title`/`.section-header` |
| `css/shell.css` | `SwissKit` | App chrome — titlebar, sidebar, search bar, tool cards, detail panel, status bar, `.ic-*` icon classes |
| `css/builtin.css` | `SwissKit` | Built-in tool styling |

Plugins embedded in the main Scene inherit all three stylesheets automatically via scene-graph
propagation. Plugins that open their own `Stage`/`Scene` should call `Themes.applyTo(scene)`.

Sidebar icons use an embedded Material Design Icons webfont (`mdi-codemap.properties` +
`MdiIconUtil`) so glyphs render consistently across platforms (Windows in particular).

## Database

H2 file at `.swisskit/swisskit.db` relative to the working directory (`AUTO_SERVER` mode).
The schema is initialized from `init.sql` and accessed via MyBatis with XML mappers in
`src/main/resources/mapper/`. Main tables:

| Table | Purpose |
|-------|---------|
| `app_setting` | Key-value store (language, AI config, model path, …) |
| `plugin_manager` | Installed external plugins (version, disabled flag, update URL) |
| `plugin_favorites` | Bookmarked plugin IDs |
| `complex_split_config` | Excel complex-split task configs |
| `swiss_kit_setting_email` | Email send/IMAP account settings |
| `email_address_book` / `email_tag` | Contacts and tags |
| `email_mass_sent_config` / `email_sent_log` | Mass-send configs and audit log |
| `email_archive` | Archived received messages |
| `menu_order` | Drag-and-drop reordering |

`DatabaseInit` injects the dynamic DB URL into `mybatis-config.xml` via a `Properties`
placeholder at startup.

## Build

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -f SwissKit/pom.xml -DskipTests
java -jar SwissKit/target/SwissKitJ-3.1.0.jar
```

The fat JAR is built by `maven-shade-plugin` (main class `fan.summer.Launcher`) and bundles
JavaFX native libraries for all platforms (`.dll`/`.so`/`.dylib`). On Windows the `windows-exe`
profile is auto-activated and also produces `SwissKit.exe` via Launch4j. The three POMs are
**standalone** (no parent); GitHub Actions handles cross-platform release builds.

## Key Invariants

These contracts must not be broken by future changes:

- **`SwissKitJPlugin` is the ABI boundary** with the external plugin repository — any new
  method must have a `default` implementation.
- **`SwissKitJ-Api` stays free of business dependencies** (only `javafx`, `provided`); host
  classes must not leak into the API module.
- **Every call into a plugin goes through `PluginContext`** (`callWith`/`runWith`, plus
  `wrapEvents` after `createView`), so plugin libraries can locate their own resources.
- **Class loading stays parent-first** even though resource loading is child-first — making
  classes child-first would duplicate `SwissKitJPlugin` and break SPI/casts.
- **AI stream callbacks are delivered on the JavaFX Application Thread.**
- **The browser planner must bypass `AiService.chat()`** to avoid recursive tool invocation.
- **A set `constrainedTool` must always be cleared** in the completion/error callback.
