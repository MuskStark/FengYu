# Architecture

SwissKitJ is built with a modular, plugin-based architecture on JavaFX 21.

## Module Structure

| Module | Purpose |
|--------|---------|
| `SwissKitJ-Api` | Shared plugin interface (`SwissKitJPlugin`), reusable components (`StepWizard`), theming, logging API |
| `SwissKit` | JavaFX application shell — UI, plugin loading, built-in tools |

Official plugins live in a [separate repository](https://github.com/MuskStark/SwissKiJ-Plugin). They are built independently and dropped into `plugins/` as JARs at runtime. All plugins declare `SwissKitJ-Api` as `provided` scope. The main app provides it at runtime via the fat JAR.

## Startup Sequence

`fan.summer.Launcher` (fat-JAR manifest entry point) → `fan.summer.app.SwissKitJApp` (JavaFX `Application`).

In `SwissKitJApp.start()`:

1. Resolve `plugins/` directory (JAR sibling in production, `./plugins/` in dev)
2. Create `PluginLoader` + `PluginRegistry`
3. Register built-in tools via `BuiltinToolRegistrar`
4. Create `FavoriteService` (loads favorites from DB)
5. Build `MainWindow` and display it
6. Initialize remote AI backends (OpenAI/Anthropic) if configured; local backend is deferred until the AI tool is first opened
7. Attach `WindowResizeHelper` for edge/corner drag resize
8. Start `PluginLoader` (scans `plugins/` dir and watches for changes)

## UI Structure

| Component | Role |
|-----------|------|
| `MainWindow` | Root `StackPane`; owns `TitleBar`, `Sidebar`, `ContentArea`, status bar |
| `Sidebar` | Category-based navigation with search bar; categories: all / text / image / dev / net / other / favorites |
| `ContentArea` | Shows `ToolCard` grid or active tool view; manages `DetailPanel` and back-bar |
| `DetailPanel` | Slide-in panel showing plugin metadata with Launch, Uninstall (external plugins only), and Favorite toggle buttons |
| `TitleBar` | Custom window chrome (window is `StageStyle.TRANSPARENT`) |

### Navigation Flow

`ToolCard` click → `DetailPanel.show()` → Launch button → `registry.activate(plugin)` + `contentArea.showPage(plugin.createView(), title)`.

The back bar calls `registry.deactivate()` on return.

## Plugin System

### Interface

```java
public interface SwissKitJPlugin {
    String getId();          // reverse-domain ID
    String getName();
    String getDescription();
    ToolCategory getCategory();
    String getVersion();
    String getIconText();    // emoji or single char
    default IconStyle getIconStyle() { return IconStyle.BLUE; }
    default PluginType getType()    { return PluginType.PLUGIN; }

    Node createView();       // called once; result cached
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
}
```

### Registration

- **Built-in tools**: Registered directly by `BuiltinToolRegistrar` — no SPI needed.
- **External plugins**: Implement `SwissKitJPlugin`, declare in `META-INF/services/fan.summer.api.SwissKitJPlugin`, drop JAR into `plugins/`. Hot-reload via file watcher.

### Plugin Loading

External plugins are loaded by a **child-first** `ClassLoader` (`ChildFirstResourceClassLoader`) that resolves classes and resources from the plugin JAR before delegating to the host. This prevents the host's classpath from shadowing plugin-bundled dependencies.

Each loaded plugin is registered with `PluginContext`, which associates the plugin instance with its `ClassLoader`. The host wraps every plugin lifecycle call (`createView()`, `onActivate()`, etc.) with `PluginContext.runWith()`/`callWith()` to set the correct thread-context ClassLoader, and wraps the plugin node's `EventDispatcher` via `PluginContext.wrapEvents()` so background threads inherit the right TCCL.

### Plugin Logging

Plugins use `fan.summer.api.log.LoggerFactory` which routes to SLF4J/Logback when the host is running, and returns a silent no-op logger in tests.

## CSS Theming

Three-layer glassmorphism dark theme:

| File | Module | Scope |
|------|--------|-------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, `.glass-*` utilities |
| `css/shell.css` | `SwissKit` | App chrome — titlebar, sidebar, cards, panels |
| `css/builtin.css` | `SwissKit` | Built-in tool styling |

Plugins embedded in the main Scene inherit all stylesheets automatically. Plugins with their own `Stage`/`Scene` should call `Themes.applyTo(scene)`.

## Database

H2 file at `.swisskit/swisskit.db` relative to the working directory. Schema initialized from `init.sql`. Accessed via MyBatis with XML mappers in `src/main/resources/mapper/`.

## Build

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -f SwissKit/pom.xml -DskipTests
java -jar SwissKit/target/SwissKitJ-3.0.0.jar
```

The fat JAR is built by `maven-shade-plugin` and bundles JavaFX native libraries for all platforms (`.dll`, `.so`, `.dylib`).
