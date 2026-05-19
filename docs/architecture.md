# Architecture

SwissKit is built with a modular, plugin-based architecture on JavaFX 21.

## Module Structure

| Module | Purpose |
|--------|---------|
| `SwissKitJ-Api` | Shared plugin interface (`SwissKitJPlugin`), reusable components (`StepWizard`), theming utilities, logging API |
| `SwissKit` | JavaFX application shell — UI, plugin loading, built-in tools |
| `OfficalPlugin/*` | Bundled plugins (HappyLearning, Qcc, Mouse) |

All plugins declare `SwissKitJ-Api` as `provided` scope. The main app provides it at runtime via the fat JAR.

## Startup Sequence

`fan.summer.Launcher` (fat-JAR manifest entry point) → `fan.summer.app.SwissKitJApp` (JavaFX `Application`).

In `SwissKitJApp.start()`:

1. Resolve `plugins/` directory (JAR sibling in production, `./plugins/` in dev)
2. Create `PluginLoader` + `PluginRegistry`
3. Register built-in tools via `BuiltinToolRegistrar` (7 tools across 5 categories)
4. Build `MainWindow` and display it
5. Start `PluginLoader` (scans `plugins/` dir and watches for file changes)

## UI Structure

All UI lives in `fan.summer.ui.*`:

| Component | Role |
|-----------|------|
| `MainWindow` | Root `StackPane`; owns `TitleBar`, `Sidebar`, `ContentArea`, status bar |
| `Sidebar` | Category-based navigation with search bar; categories: all / text / image / dev / net / other |
| `ContentArea` | Shows `ToolCard` grid or active tool view; manages `DetailPanel` and back-bar |
| `DetailPanel` | Slide-in panel showing plugin metadata with a Launch button |
| `TitleBar` | Custom window chrome (window is `StageStyle.TRANSPARENT`) |

### Navigation Flow

`ToolCard` click → `DetailPanel.show()` → Launch button → `registry.activate(plugin)` + `contentArea.showPage(plugin.createView(), title)`.

The back bar (shown by `ContentArea`) calls `registry.deactivate()` on return.

## Plugin System

### Interface (`SwissKitJPlugin`)

```java
public interface SwissKitJPlugin {
    String getId();          // reverse-domain ID, e.g. "com.example.my-tool"
    String getName();
    String getDescription();
    ToolCategory getCategory();    // TEXT / IMAGE / DEV / NET / OTHER
    String getVersion();
    String getIconText();    // emoji or single char
    default IconStyle getIconStyle() { return IconStyle.IC_BLUE; }
    default PluginType getType()    { return PluginType.PLUGIN; }

    Node createView();       // called once; result cached and reused
    default void onActivate()   {}
    default void onDeactivate() {}
    default void onUnload()     {}
}
```

### Registration

- **Built-in tools**: Registered directly by `BuiltinToolRegistrar` — no SPI needed.
- **External plugins**: Implement `SwissKitJPlugin`, declare in `META-INF/services/fan.summer.api.SwissKitJPlugin`, drop JAR into `plugins/`. Hot-reload via file watcher.

### Plugin Logging

Plugins use `fan.summer.api.log.LoggerFactory` which routes to SLF4J/Logback when the host app is running, and returns a silent no-op logger in tests.

## CSS Theming

Three-layer architecture (glassmorphism dark theme):

| File | Module | Scope |
|------|--------|-------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, `.glass-*` utilities |
| `css/shell.css` | `SwissKit` | App chrome — titlebar, sidebar, tool cards, detail panel, status bar |
| `css/builtin.css` | `SwissKit` | Reserved for built-in tool styling |

Plugins embedded in the main Scene automatically inherit all three stylesheets via scene-graph propagation. Plugins opening their own `Stage`/`Scene` can call `Themes.applyTo(scene)` for the common utilities.

## Database

H2 embedded database at `.swisskit/swisskit.db` relative to the working directory. Schema initialized from `init.sql`. Data access via MyBatis with XML mappers in `src/main/resources/mapper/`.

## Threading

JavaFX uses a single UI thread (the JavaFX Application Thread). All UI updates must happen on this thread:

- Use `Platform.runLater()` for UI updates from background threads.
- Long-running work (file I/O, network, DB) should run on a background thread via `Task` or `ExecutorService`.

## Build

```bash
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -DskipTests
java -jar SwissKit/target/SwissKitJ-3.0.0-alpha.1.jar
```

The fat JAR is built by `maven-shade-plugin` and bundles JavaFX native libraries for all three platforms (Windows `.dll`, Linux `.so`, macOS `.dylib`).
