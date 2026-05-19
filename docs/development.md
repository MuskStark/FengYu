# Development Guide

This guide covers everything you need to know to contribute to SwissKit, from setting up your development environment to building plugins and built-in tools.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Setting Up Development Environment](#setting-up-development-environment)
- [Project Structure](#project-structure)
- [Code Standards](#code-standards)
- [Plugin Development](#plugin-development)
- [Built-in Tools](#built-in-tools)
- [Background Processing](#background-processing)
- [UI Components](#ui-components)
- [Theming](#theming)
- [Logging](#logging)
- [Testing](#testing)
- [Building](#building)
- [Common Tasks](#common-tasks)

## Prerequisites

Before you start developing, ensure you have:

- **JDK 21 or higher** (required for JavaFX 21+)
- **Maven 3.8 or higher**
- **IntelliJ IDEA** (recommended) or your preferred IDE
- **Git**

### Verify Installation

```bash
# Check Java version
java -version

# Check Maven version
mvn -version

# Check Git version
git --version
```

## Setting Up Development Environment

### 1. Clone the Repository

```bash
git clone https://github.com/MuskStark/SwissKitJ.git
cd SwissKitJ
```

### 2. Import into IDE

**IntelliJ IDEA**:

1. Open IntelliJ IDEA
2. Select "Open" and choose the project directory
3. Wait for Maven to download dependencies
4. The project will be automatically recognized as a multi-module Maven project

### 3. Build the Project

The API module must be installed first before anything else compiles:

```bash
# Step 1: Install the API module
mvn install -f SwissKitJ-Api/pom.xml -DskipTests

# Step 2: Build the full project
mvn clean package -DskipTests
```

To build only the main app module and its dependencies:

```bash
mvn clean package -pl SwissKit -am -DskipTests
```

### 4. Run the Application

```bash
java -jar SwissKit/target/SwissKit-3.0.0-alpha.1.jar
```

Or run `fan.summer.Launcher` directly from your IDE.

## Project Structure

```
SwissKitJ/
├── SwissKitJ-Api/                        # Shared API module
│   └── src/main/java/fan/summer/api/
│       ├── SwissKitJPlugin.java          # Plugin interface
│       ├── ToolCategory.java             # Category enum
│       ├── IconStyle.java                # Icon style enum
│       ├── ToolType.java                 # Type enum (BUILTIN / PLUGIN)
│       ├── component/
│       │   └── StepWizard.java           # Reusable multi-step wizard
│       ├── log/
│       │   ├── LoggerFactory.java        # Plugin logger entry point
│       │   └── PluginLogger.java         # Logger interface
│       └── theme/
│           └── Themes.java               # Theme utility (CSS loading)
├── SwissKit/                             # Main JavaFX application
│   └── src/main/java/fan/summer/
│       ├── Launcher.java                 # Entry point (fat JAR)
│       ├── app/
│       │   └── SwissKitJApp.java         # JavaFX Application
│       ├── buildintool/                  # Built-in tool implementations
│       │   ├── dev/                      #   Base64, Hash, JSON
│       │   ├── email/                    #   Email sender
│       │   ├── excelsplitter/            #   Excel splitter
│       │   ├── image/                    #   Color converter
│       │   └── text/                     #   Markdown editor
│       ├── plugin/                       # Plugin loading and registry
│       ├── ui/                           # App shell UI (MainWindow, Sidebar, etc.)
│       ├── Registrar/
│       │   └── BuiltinToolRegistrar.java # Registers built-in tools
│       └── util/                         # Utilities
├── OfficalPlugin/                        # Official external plugins
│   ├── SwissKitJ-Plugin-HappyLearning/   #   Auto-learning plugin
│   ├── SwissKitJ-Plugin-Qcc/             #   CSV-to-Excel converter
│   └── SwissKit-Plugin-Mouse/            #   Mouse automation
├── backup/                               # Legacy Swing code (excluded from build)
├── docs/                                 # Documentation
└── pom.xml                               # Root Maven configuration
```

### Module Dependencies

| Module | Depends on | Scope |
|--------|-----------|-------|
| `SwissKitJ-Api` | JavaFX | compile |
| `SwissKit` | `SwissKitJ-Api` | compile |
| `OfficalPlugin/*` | `SwissKitJ-Api` | provided |

All plugins declare `SwissKitJ-Api` as `provided` scope. The main application provides it at runtime via the fat JAR.

### Key Packages

| Package | Purpose |
|---------|---------|
| `fan.summer.api` | Plugin interface and shared enums |
| `fan.summer.api.component` | Reusable UI components (StepWizard) |
| `fan.summer.api.log` | Plugin logging API |
| `fan.summer.api.theme` | Theme utilities for standalone plugin windows |
| `fan.summer.ui` | Application shell: MainWindow, Sidebar, ContentArea, TitleBar |
| `fan.summer.plugin` | Plugin discovery, loading, and registry |
| `fan.summer.buildintool.*` | Built-in tool implementations |
| `fan.summer.app` | JavaFX Application subclass |

## Code Standards

### Naming Conventions

**Packages**:

- Built-in tools: `fan.summer.buildintool.{category}`
- External plugins: `plugin.{org}.{toolname}` (e.g., `plugin.swisskitj`)
- Listeners and workers: use descriptive sub-packages

**Classes**:

- Plugin implementations: `{ToolName}Plugin`
- Built-in tools: `{ToolName}Plugin` under `fan.summer.buildintool.{category}`
- Utilities: `{Function}Util`

**Methods and Variables**:

- Use camelCase
- Be descriptive and concise

```java
// Good
public void analyzeExcelFile(Path filePath) { }
private int currentProgress;

// Bad
public void analyzeExcel() { }
private int cp;
```

### Documentation

- Add Javadoc for all public methods
- Use clear, concise descriptions
- Include parameters and return values

```java
/**
 * Analyzes an Excel file and extracts header information.
 *
 * @param filePath the path to the Excel file to analyze
 * @return a map of sheet names to their headers
 * @throws IOException if the file cannot be read
 */
public Map<String, Map<Integer, String>> analyzeExcel(Path filePath) throws IOException {
    // Implementation
}
```

### Language

- All code comments must be in **English**
- All UI text must be in **English**
- Variable and method names in English

### UI Standards

- Use JavaFX layouts: `VBox`, `HBox`, `GridPane`, `BorderPane`, `StackPane`
- Font styling via CSS, not inline `setFont()` calls
- Use the CSS class constants in `IconStyle` for icon backgrounds
- Prefer `StepWizard` for multi-step workflows

```java
VBox container = new VBox(16);
container.setPadding(new Insets(24));
container.setAlignment(Pos.TOP_LEFT);

Label title = new Label("My Tool");
title.getStyleClass().add("section-title");
container.getChildren().add(title);
```

## Plugin Development

SwissKit supports two kinds of tools: **built-in tools** compiled directly into the application, and **external plugins** loaded at runtime from JAR files. Both implement the same `SwissKitJPlugin` interface.

### The SwissKitJPlugin Interface

```java
package fan.summer.api;

import fan.summer.api.*;
import javafx.scene.Node;

public interface SwissKitJPlugin {
    // ── Metadata (used by sidebar, search, and detail panel) ──

    /** Globally unique ID; reverse-domain notation recommended: com.example.my-tool */
    String getId();

    /** Display name shown on the tool card, e.g. "JSON Formatter" */
    String getName();

    /** One-line description shown on the card and detail panel */
    String getDescription();

    /** Category matching sidebar navigation */
    ToolCategory getCategory();

    /** Version string, e.g. "1.0.0" */
    String getVersion();

    /** Material Design Icons name (without "mdi" prefix), e.g. "file-excel" */
    String getMdiIcon();

    /** CSS class and colour for the icon background */
    default IconStyle getIconStyle() { return IconStyle.BLUE; }

    /** Type tag: BUILTIN for built-in tools, PLUGIN for external plugins */
    default ToolType getType() { return ToolType.PLUGIN; }

    // ── UI lifecycle ──

    /** Returns the main UI node for this tool. Called once; result is cached. */
    Node createView();

    /** Called when the tool is brought to the foreground */
    default void onActivate() {}

    /** Called when the tool is moved to the background */
    default void onDeactivate() {}

    /** Called before the plugin is unloaded. Release resources here. */
    default void onUnload() {}
}
```

### Category Enum

```java
public enum ToolCategory {
    DEV,    // developer tools
    TEXT,   // text processing
    IMAGE,  // image processing
    NET,    // network tools
    OTHER   // miscellaneous
}
```

### IconStyle Enum

```java
public enum IconStyle {
    BLUE("ic-blue",   Color.rgb(99, 130, 255)),
    PURPLE("ic-purple", Color.rgb(160, 110, 255)),
    TEAL("ic-teal",   Color.rgb(40, 210, 140)),
    AMBER("ic-amber", Color.rgb(255, 185, 50)),
    RED("ic-red",    Color.rgb(255, 100, 100)),
    PINK("ic-pink",   Color.rgb(245, 100, 160)),
    GRAY("ic-gray",   Color.rgb(200, 200, 210));
}
```

### Creating an External Plugin

**1. Set up your Maven project**

```xml
<dependencies>
    <dependency>
        <groupId>fan.summer.api</groupId>
        <artifactId>SwissKitJ-Api</artifactId>
        <version>3.0.0-alpha.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

**2. Implement the interface**

```java
package plugin.swisskitj.mytool;

import fan.summer.api.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class MyToolPlugin implements SwissKitJPlugin {

    @Override
    public String getId()          { return "com.example.my-tool"; }
    @Override
    public String getName()        { return "My Tool"; }
    @Override
    public String getDescription() { return "Does something useful"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override
    public String getVersion()     { return "1.0.0"; }
    @Override
    public String getMdiIcon()     { return "wrench"; }
    @Override
    public IconStyle getIconStyle(){ return IconStyle.TEAL; }

    @Override
    public Node createView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(24));
        root.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("My Tool");
        title.getStyleClass().add("section-title");
        root.getChildren().add(title);

        return root;
    }
}
```

**3. Register via SPI**

Create `META-INF/services/fan.summer.api.SwissKitJPlugin` with the fully-qualified class name:

```
plugin.swisskitj.mytool.MyToolPlugin
```

**4. Package and deploy**

Package your plugin as a fat JAR (include all private dependencies). Drop the JAR into the host application's `plugins/` directory. Hot-reload is supported -- the plugin loader watches the directory for changes.

### Lifecycle Methods

| Method | When Called | Typical Use |
|--------|-------------|-------------|
| `createView()` | First time the user launches the tool | Build and return the UI node. Called once, result cached. |
| `onActivate()` | Tool is brought to the foreground | Resume timers, refresh data, re-register listeners |
| `onDeactivate()` | Tool is moved to the background | Pause timers, persist state, unregister listeners |
| `onUnload()` | Plugin is being unloaded | Release threads, file handles, DB connections |

### Navigation Flow

1. User clicks a `ToolCard` in the sidebar grid
2. `DetailPanel` slides in showing plugin metadata (name, description, version, icon)
3. User clicks the Launch button
4. `registry.activate(plugin)` is called, triggering `onActivate()`
5. `contentArea.showPage(plugin.createView(), title)` embeds the tool's UI
6. When the user clicks the back bar, `registry.deactivate()` is called, triggering `onDeactivate()`

## Built-in Tools

Built-in tools skip SPI entirely. They are registered directly in `BuiltinToolRegistrar`.

### Adding a Built-in Tool

**1. Create the implementation class**

```java
package fan.summer.buildintool.dev;

import fan.summer.api.*;
import javafx.scene.Node;

public class MyBuiltinPlugin implements SwissKitJPlugin {

    @Override
    public String getId()          { return "builtin.my-tool"; }
    @Override
    public String getName()        { return "My Tool"; }
    @Override
    public String getDescription() { return "A built-in developer tool"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override
    public String getVersion()     { return "3.0.0-alpha.1"; }
    @Override
    public String getMdiIcon()     { return "wrench"; }
    @Override
    public IconStyle getIconStyle(){ return IconStyle.BLUE; }
    @Override
    public ToolType getType()      { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        // Build and return the tool's UI
    }
}
```

**2. Register in `BuiltinToolRegistrar`**

```java
// In fan.summer.Registrar.BuiltinToolRegistrar.register()
List<SwissKitJPlugin> builtins = List.of(
    new JsonFormatterPlugin(),
    new Base64Plugin(),
    // ...
    new MyBuiltinPlugin()   // <-- Add here
);
```

**3. Build and run**

```bash
mvn clean package -pl SwissKit -am -DskipTests
java -jar SwissKit/target/SwissKit-3.0.0-alpha.1.jar
```

## Background Processing

For time-consuming operations, use JavaFX's `Task` class instead of `SwingWorker`.

### JavaFX Task Pattern

```java
import javafx.concurrent.Task;
import javafx.application.Platform;

public class MyToolPlugin implements SwissKitJPlugin {
    private ProgressBar progressBar;
    private Button processButton;

    private void startProcessing() {
        processButton.setDisable(true);
        progressBar.setProgress(0);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                int totalSteps = 100;
                for (int i = 0; i <= totalSteps; i++) {
                    Thread.sleep(50);  // Simulate work
                    updateProgress(i, totalSteps);
                    updateMessage("Processing... " + i + "%");
                }
                return null;
            }
        };

        // Bind progress to UI
        progressBar.progressProperty().bind(task.progressProperty());

        task.setOnSucceeded(e -> {
            progressBar.setProgress(1);
            progressBar.progressProperty().unbind();
            processButton.setDisable(false);
        });

        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            processButton.setDisable(false);
            Throwable ex = task.getException();
            showError(ex.getMessage());
        });

        new Thread(task).start();
    }
}
```

### Updating UI from Background Threads

Use `Platform.runLater()` instead of `SwingUtilities.invokeLater()`:

```java
// Correct: schedule UI update on the JavaFX Application Thread
Platform.runLater(() -> statusLabel.setText("Done"));

// Wrong: calling UI methods from a background thread
statusLabel.setText("Done");  // Will throw IllegalStateException
```

### Showing Dialogs

Use JavaFX `Alert` instead of `JOptionPane`:

```java
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

private void showError(String message) {
    Alert alert = new Alert(AlertType.ERROR);
    alert.setTitle("Error");
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
}

private void showInfo(String message) {
    Alert alert = new Alert(AlertType.INFORMATION);
    alert.setTitle("Information");
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
}
```

## UI Components

### StepWizard

`fan.summer.api.component.StepWizard` is a ready-made multi-step wizard container. Use it for any tool that follows a step-by-step workflow (e.g., file selection, configuration, execution).

```java
import fan.summer.api.component.StepWizard;

// Create the wizard
StepWizard wizard = new StepWizard();

// Add steps: title, content node, validation supplier
wizard.addStep("Select file",  fileSelectNode, () -> filePath != null);
wizard.addStep("Split mode",   modeSelectNode, () -> modeSelected);
wizard.addStep("Run split",    progressNode,   () -> true);

// Required: call build() after all addStep() calls
wizard.build();

// Listen for step changes (e.g., trigger analysis on forward)
wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) {
        startAnalysis();
    }
});

// Programmatic navigation
wizard.goTo(2);
boolean isLast = wizard.isLastStep();
```

**Behavior**:

- Renders step dots with done/active/idle visual states
- Animated slide transitions between steps (with direction awareness)
- Back/Next buttons in the footer
- The `canProceed` supplier (third argument to `addStep`) is evaluated on every Next click
- Returning `false` triggers a shake animation on the Next button and blocks advancement
- On the last step, the Next button text changes to "Complete"

### Layout Recommendations

Use JavaFX layout panes:

| Pane | Use For |
|------|---------|
| `VBox` | Vertical stacking with spacing |
| `HBox` | Horizontal row of elements |
| `BorderPane` | Top/center/bottom/left/right layout |
| `GridPane` | Grid-based forms |
| `StackPane` | Overlapping elements, centering |
| `FlowPane` | Wrapping flow layout |
| `ScrollPane` | Scrollable content |

```java
VBox root = new VBox(12);
root.setPadding(new Insets(20));
root.setAlignment(Pos.TOP_LEFT);

Label heading = new Label("Excel Splitter");
heading.getStyleClass().add("section-title");

// Content fills remaining space
VBox.setVgrow(contentArea, Priority.ALWAYS);
root.getChildren().addAll(heading, contentArea);
```

## Theming

SwissKit uses a three-layer CSS architecture with a glassmorphism dark theme:

| File | Module | Scope |
|------|--------|-------|
| `css/swisskit-common.css` | `SwissKitJ-Api` | Shared variables, scrollbars, progress bar, `.glass-*` utility classes, `.section-title`/`.section-header` |
| `css/shell.css` | `SwissKit` | App shell only: `.titlebar`, `.sidebar`, `.search-bar`, `.tool-card`, `.detail-panel`, `.statusbar` |
| `css/builtin.css` | `SwissKit` | Built-in tool styling (reserved) |

### For Plugins Embedded in the Main Scene

Plugins whose `createView()` returns a Node that the host embeds in the main Scene automatically inherit all three stylesheets via scene graph propagation. No action needed.

### For Plugins with Their Own Stage/Scene

If your plugin opens its own window, apply the common stylesheet:

```java
import fan.summer.api.theme.Themes;

Stage popup = new Stage();
Scene scene = new Scene(root);
Themes.applyTo(scene);  // Loads shared glass-* utility classes
popup.setScene(scene);
popup.show();
```

### Available CSS Classes (from swisskit-common.css)

| Class | Purpose |
|-------|---------|
| `.glass-dialog` | Dialog/popup background style |
| `.glass-field` | Text field style |
| `.glass-combo` | Combo box style |
| `.glass-table` | Table view style |
| `.glass-checkbox` | Checkbox style |
| `.glass-btn-primary` | Primary action button |
| `.glass-btn-secondary` | Secondary action button |
| `.glass-tab-pane` | Tab pane style |
| `.section-title` | Section heading text |
| `.section-header` | Section header container |

## Logging

Plugins should use `fan.summer.api.log.LoggerFactory` rather than depending on any logging framework directly.

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class MyPlugin implements SwissKitJPlugin {
    private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

    @Override
    public void onActivate() {
        log.info("Activated, taskId={}", currentTaskId);
    }

    private void processFile(Path file) {
        try {
            log.debug("Processing file: {}", file);
            // ...
            log.info("File processed successfully: {} ({} rows)", file.getName(), rowCount);
        } catch (Exception e) {
            log.error("Failed to process file: {}", file, e);
        }
    }
}
```

**Logging levels**: `trace`, `debug`, `info`, `warn`, `error` -- all with SLF4J-style `{}` placeholder support.

**Backend**: The host application installs a binder at startup that routes plugin log calls through SLF4J + Logback:
- Console output at INFO+ level
- Rolling file at DEBUG+ level under `.swisskit/logs/swisskit.log`
- Daily rotation with 7-day retention

**Unit testing**: If the host has not installed a binder (e.g., during plugin unit tests), `LoggerFactory` returns a silent no-op logger, so it is always safe to call.

## Testing

### Running Tests

```bash
mvn test
```

### Writing Tests

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcelSplitterPluginTest {

    @Test
    void testGetId() {
        ExcelSplitterPlugin plugin = new ExcelSplitterPlugin();
        assertEquals("builtin.excel-splitter", plugin.getId());
    }

    @Test
    void testMetadata() {
        ExcelSplitterPlugin plugin = new ExcelSplitterPlugin();
        assertEquals(ToolCategory.DEV, plugin.getCategory());
        assertEquals(ToolType.BUILTIN, plugin.getType());
    }
}
```

## Building

### Clean Build

```bash
# Full project build (install API module first)
mvn install -f SwissKitJ-Api/pom.xml -DskipTests
mvn clean package -DskipTests
```

### Skip Tests

```bash
mvn clean package -DskipTests
```

### Build Only Main App

```bash
mvn clean package -pl SwissKit -am -DskipTests
```

### Create and Run Fat JAR

```bash
# Build
mvn clean package -DskipTests

# Run
java -jar SwissKit/target/SwissKit-3.0.0-alpha.1.jar
```

### Windows Executable

On Windows, the `windows-exe` Maven profile is auto-activated and produces `SwissKit.exe` via Launch4j in `SwissKit/target/`.

## Common Tasks

### Adding a Dependency

Edit the relevant `pom.xml`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>library-name</artifactId>
    <version>1.0.0</version>
</dependency>
```

Then run:

```bash
mvn dependency:resolve
```

### Debugging

Run the application with debug mode in your IDE:

1. Set breakpoints in your code
2. Run `fan.summer.Launcher.main()` in debug mode

For verbose logging, the host already routes logs to `.swisskit/logs/swisskit.log` at DEBUG level. Plugin logs are automatically included.

### Platform-Specific Notes

- **Linux**: JavaFX media dependencies may require `libglib2.0-0`, `libgtk-3-0`, and other system libraries
- **macOS**: JavaFX works out of the box with JDK 21+
- **Windows**: The `windows-exe` profile auto-activates, producing an `.exe` via Launch4j

## Best Practices

### Thread Safety

- Always update UI components on the JavaFX Application Thread
- Use `Platform.runLater()` for UI updates from background threads
- Use `javafx.concurrent.Task` for long-running operations -- its `updateMessage()` and `updateProgress()` methods are thread-safe

```java
// Wrong -- from background thread
statusLabel.setText("Done");

// Correct
Platform.runLater(() -> statusLabel.setText("Done"));

// Even better -- use Task
task.updateMessage("Processing completed");
```

### Error Handling

- Always handle exceptions gracefully
- Provide user-friendly error messages via JavaFX `Alert`
- Log errors for debugging

```java
try {
    processFile(inputPath);
} catch (Exception e) {
    log.error("File processing failed", e);
    Platform.runLater(() -> {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Processing Failed");
        alert.setContentText(e.getMessage());
        alert.showAndWait();
    });
}
```

### Resource Management

- Use try-with-resources for file and I/O operations
- Release resources in `onUnload()` (thread executors, watchers, DB connections)
- Unbind JavaFX properties when a task completes

```java
try (Workbook workbook = WorkbookFactory.create(file)) {
    // Use workbook
} // Automatically closed

@Override
public void onUnload() {
    executorService.shutdownNow();
    watcher.close();
}
```

### Plugin Isolation

- Keep plugin dependencies self-contained in a fat JAR
- Do not assume the host application's classpath contains your dependencies
- Test your plugin JAR in an isolated environment before distribution

## Contributing

See the [Contributing Guide](contributing.md) for guidelines on contributing to SwissKit.

---

**Ready to contribute?** Check out [Contributing](contributing.md) for submission guidelines!
