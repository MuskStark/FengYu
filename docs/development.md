# Development Guide

## Prerequisites

- **JDK 21+**
- **Maven 3.8+**
- **IntelliJ IDEA** (recommended)
- **Git**

## Setup

```bash
git clone https://github.com/MuskStark/FengYu.git
cd FengYu

mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
```

## Project Structure

```
FengYu/
├── FengYu-Api/                        # Shared API module
│   └── src/main/java/fan/summer/api/
│       ├── SwissKitJPlugin.java          # Plugin interface
│       ├── PluginContext.java            # TCCL switching for plugin isolation
│       ├── ToolCategory.java             # Category enum
│       ├── IconStyle.java                # Icon style enum
│       ├── ToolType.java                 # Type enum (BUILTIN / PLUGIN)
│       ├── component/
│       │   └── StepWizard.java           # Multi-step wizard
│       ├── log/
│       │   ├── LoggerFactory.java
│       │   └── PluginLogger.java
│       └── theme/
│           └── Themes.java
├── FengYu/                             # Main JavaFX application
│   └── src/main/java/fan/summer/
│       ├── Launcher.java                 # Entry point
│       ├── app/FengYuApp.java         # JavaFX Application
│       ├── buildintool/                  # Built-in tools
│       ├── plugin/                       # Plugin loading
│       └── ui/                           # App shell UI
├── backup/                               # Legacy Swing code (excluded from build)
├── docs/                                 # Documentation
└── pom.xml                               # Root aggregator
```

### Module Dependencies

| Module | Depends on | Scope |
|--------|-----------|-------|
| `FengYu-Api` | JavaFX | compile |
| `FengYu` | `FengYu-Api` | compile |
| External plugins | `FengYu-Api` | provided |

## Plugin Development

### Creating an External Plugin

**1. Maven setup**

```xml
<dependencies>
    <dependency>
        <groupId>fan.summer.fengyu.api</groupId>
        <artifactId>FengYu-Api</artifactId>
        <version>3.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**2. Implement the interface**

```java
package plugin.example.mytool;

import fan.summer.fengyu.api.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MyToolPlugin implements SwissKitJPlugin {

    @Override public String getId()          { return "com.example.my-tool"; }
    @Override public String getName()        { return "My Tool"; }
    @Override public String getDescription() { return "Does something useful"; }
    @Override public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()     { return "wrench"; }
    @Override public IconStyle getIconStyle(){ return IconStyle.TEAL; }

    @Override
    public Node createView() {
        VBox root = new VBox(16);
        root.getChildren().add(new Label("My Tool"));
        return root;
    }
}
```

**3. Register via SPI**

Create `META-INF/services/fan.summer.fengyu.api.SwissKitJPlugin`:

```
plugin.example.mytool.MyToolPlugin
```

**4. Deploy**

Package as a fat JAR and drop into the host's `plugins/` directory. Hot-reload is supported.

### Lifecycle Methods

| Method | When Called | Typical Use |
|--------|-------------|-------------|
| `createView()` | First launch | Build and return UI node. Called once, cached. |
| `onActivate()` | Tool brought to foreground | Resume timers, refresh data |
| `onDeactivate()` | Tool moved to background | Pause timers, persist state |
| `onUnload()` | Plugin being unloaded | Release threads, file handles |

### Navigation Flow

1. User clicks `ToolCard` → `DetailPanel` slides in
2. User clicks Launch → `registry.activate(plugin)` → `contentArea.showPage(plugin.createView())`
3. Back bar → `registry.deactivate()`

### Plugin Resource Isolation

External plugins are loaded with a child-first `ClassLoader` and registered with `PluginContext`. The host automatically handles TCCL switching — plugin authors do not need any `ClassLoader` awareness. For plugins that open their own `Stage`/`Scene`, resource lookups via `ServiceLoader` or resource bundles will work correctly as long as the code runs within a lifecycle method or an event handler on the plugin's view node.

## Built-in Tools

Built-in tools skip SPI. Register in `BuiltinToolRegistrar`:

```java
List<SwissKitJPlugin> builtins = List.of(
    new AiChatPlugin(),
    new JsonFormatterPlugin(),
    // ...
    new MyBuiltinPlugin()   // Add here
);
```

Set `getType()` to return `PluginType.BUILTIN`.

## UI Components

### StepWizard

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Select file",  fileSelectPane, () -> filePath != null);
wizard.addStep("Split mode",   modePane,       () -> modeSelected);
wizard.addStep("Output",       outputPane,     () -> outputDir != null);
wizard.build();

wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) startAnalysis();
});
```

- Dot navigation with done/active/idle states
- Animated slide transitions
- `canProceed` supplier blocks advancement with shake animation
- Last step changes Next button to "Complete"

### Layout

Use JavaFX layouts: `VBox`, `HBox`, `GridPane`, `BorderPane`, `StackPane`, `ScrollPane`.

### Theming

Three-layer CSS:

| File | Scope |
|------|-------|
| `fengyu-common.css` | Shared variables, `.glass-*` utilities, `.section-title` |
| `shell.css` | App chrome — titlebar, sidebar, cards, panels |
| `builtin.css` | Built-in tool styling |

Plugins in the main Scene inherit all styles automatically. For standalone windows:

```java
Themes.applyTo(scene);
```

Available classes: `.glass-dialog`, `.glass-field`, `.glass-combo`, `.glass-table`, `.glass-checkbox`, `.glass-btn-primary`, `.glass-btn-secondary`, `.glass-tab-pane`, `.section-title`.

## Logging

```java
import fan.summer.fengyu.api.log.LoggerFactory;
import fan.summer.fengyu.api.log.PluginLogger;

private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

log.info("Processing file: {}", file);
log.error("Failed: {}", file, exception);
```

Backend: SLF4J + Logback. Console at INFO+, rolling file at DEBUG+ under `.fengyu/logs/`. Safe no-op logger in tests.

## Background Processing

Use JavaFX `Task` for long-running operations:

```java
Task<Void> task = new Task<>() {
    @Override
    protected Void call() throws Exception {
        updateMessage("Processing...");
        updateProgress(current, total);
        return null;
    }
};
progressBar.progressProperty().bind(task.progressProperty());
new Thread(task).start();
```

Always use `Platform.runLater()` for UI updates from background threads.

## Contributing

### Branch Naming

- `feature/` — New features
- `bugfix/` — Bug fixes
- `docs/` — Documentation
- `refactor/` — Refactoring

### Commit Convention

Use conventional commits with emojis:

| Prefix | Emoji | Purpose |
|--------|-------|---------|
| `✨ feat:` | `:sparkles:` | New feature |
| `🐛 fix:` | `:bug:` | Bug fix |
| `♻️ refactor:` | `:recycle:` | Refactoring |
| `📝 docs:` | `:memo:` | Documentation |
| `⬆️ deps:` | `:arrow_up:` | Dependency upgrade |

### Pull Requests

1. Fork and create a feature branch
2. Build: `mvn clean package -f FengYu/pom.xml -DskipTests`
3. Commit with conventional commit format
4. Push and open a PR against `main`

### Reporting Issues

Include: OS, Java version, FengYu version, steps to reproduce, expected vs actual behavior.
