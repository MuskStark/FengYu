# API Reference

This section provides detailed API documentation for SwissKitJ's core interfaces, enums, and reusable components.

## Table of Contents

- [Module Overview](#module-overview)
- [SwissKitJPlugin Interface](#swisskitjplugin-interface)
- [Enums](#enums)
  - [ToolCategory](#toolcategory)
  - [ToolType](#tooltype)
  - [IconStyle](#iconstyle)
- [StepWizard Component](#stepwizard-component)
- [Plugin Logging](#plugin-logging)
  - [LoggerFactory](#loggerfactory)
  - [PluginLogger](#pluginlogger)
- [Theming (Themes Utility)](#theming-themes-utility)
- [Database Layer](#database-layer)
- [Worker Classes](#worker-classes)
- [Utility Classes](#utility-classes)
- [Listeners](#listeners)
- [Callback Interfaces](#callback-interfaces)
- [Entity Classes](#entity-classes)

---

## Module Overview

SwissKitJ uses a multi-module architecture. Key components are organized as follows:

| Module            | Package                           | Description                                   |
|-------------------|-----------------------------------|-----------------------------------------------|
| `SwissKitJ-Api`   | `fan.summer.api`                  | Core plugin interface, enums                  |
| `SwissKitJ-Api`   | `fan.summer.api.component`        | Reusable UI components (StepWizard)           |
| `SwissKitJ-Api`   | `fan.summer.api.log`              | Plugin logging abstraction                    |
| `SwissKitJ-Api`   | `fan.summer.api.theme`            | Theme/style utilities                         |
| `SwissKit` (main) | `fan.summer.database`             | Database layer (H2 + MyBatis)                 |
| `SwissKit` (main) | `fan.summer.kitpage`              | Tool implementations                          |
| `SwissKit` (main) | `fan.summer.utils`                | Utility classes                               |

---

## SwissKitJPlugin Interface

The `SwissKitJPlugin` interface is the single contract for all tools and plugins in SwissKitJ. Every tool -- whether a built-in shipped with the host application or an external JAR-based plugin -- must implement this interface.

### Location

```
SwissKitJ-Api/src/main/java/fan/summer/api/SwissKitJPlugin.java
```

### Interface Definition

```java
package fan.summer.api;

import javafx.scene.Node;

public interface SwissKitJPlugin {

    // ── Metadata ────────────────────────────────────

    /** Globally unique ID; reverse-domain notation recommended, e.g. "com.example.my-tool" */
    String getId();

    /** Display name shown on the tool card, e.g. "JSON Formatter" */
    String getName();

    /** One-line description shown on the card and detail panel */
    String getDescription();

    /** Category matching sidebar navigation. See {@link ToolCategory}. */
    ToolCategory getCategory();

    /** Version string, e.g. "1.0.0" */
    String getVersion();

    /**
     * Material Design Icons class name (without "mdi" prefix), e.g. "file-excel".
     * Full list: https://pictogrammers.com/library/mdi/
     */
    String getMdiIcon();

    /** CSS class and colour for the icon background. See {@link IconStyle}. */
    default IconStyle getIconStyle() { return IconStyle.BLUE; }

    /** Type tag: {@link ToolType#BUILTIN} for built-ins, {@link ToolType#PLUGIN} (default) for external plugins. */
    default ToolType getType() { return ToolType.PLUGIN; }

    // ── UI lifecycle ────────────────────────────────

    /**
     * Returns the main UI node for this tool.
     * The host embeds it inside the content area StackPane.
     * Created once on first call; the same instance is reused thereafter.
     */
    Node createView();

    /** Called when the tool is brought to the foreground. Override to resume state, start timers, etc. */
    default void onActivate() {}

    /** Called when the tool is moved to the background. Override to pause timers, persist state, etc. */
    default void onDeactivate() {}

    /** Called before the plugin is unloaded. Release all resources here: threads, file handles, etc. */
    default void onUnload() {}
}
```

### Methods

| Method            | Return Type  | Description                                                  |
|-------------------|--------------|--------------------------------------------------------------|
| `getId()`         | `String`     | Globally unique identifier (reverse-domain recommended)      |
| `getName()`       | `String`     | Display name shown on the tool card                          |
| `getDescription()`| `String`     | One-line description shown on the card and detail panel      |
| `getCategory()`   | `ToolCategory`| Category matching sidebar navigation                        |
| `getVersion()`    | `String`     | Version string (e.g. "1.0.0")                                |
| `getMdiIcon()`    | `String`     | Material Design Icons class name without "mdi" prefix        |
| `getIconStyle()`  | `IconStyle`  | CSS class and colour for the icon background (default: BLUE) |
| `getType()`       | `ToolType`   | Type tag: BUILTIN or PLUGIN (default)                        |
| `createView()`    | `Node`       | Returns the main JavaFX UI node for this tool                |
| `onActivate()`    | `void`       | Called when the tool is brought to the foreground            |
| `onDeactivate()`  | `void`       | Called when the tool is moved to the background              |
| `onUnload()`      | `void`       | Called before the plugin is unloaded                         |

### Example Implementation (Built-in Tool)

```java
import fan.summer.api.*;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

public class ExampleTool implements SwissKitJPlugin {

    private VBox view;

    @Override
    public String getId()          { return "com.swisskit.example"; }
    @Override
    public String getName()        { return "Example Tool"; }
    @Override
    public String getDescription() { return "Demonstrates the plugin interface"; }
    @Override
    public ToolCategory getCategory() { return ToolCategory.DEV; }
    @Override
    public String getVersion()     { return "1.0.0"; }
    @Override
    public String getMdiIcon()     { return "code-braces"; }
    @Override
    public IconStyle getIconStyle() { return IconStyle.TEAL; }
    @Override
    public ToolType getType()      { return ToolType.BUILTIN; }

    @Override
    public Node createView() {
        if (view == null) {
            view = new VBox(12, new Label("Welcome to Example Tool"));
            view.setStyle("-fx-padding: 24;");
        }
        return view;
    }
}
```

### Example Implementation (External Plugin)

A plugin JAR also requires an SPI service descriptor so the host application can discover it.

1. Implement `SwissKitJPlugin` as shown above (set `getType()` to return `ToolType.PLUGIN`, or use the default).
2. Create `META-INF/services/fan.summer.api.SwissKitJPlugin` in the JAR with the fully-qualified class name:

```
com.example.mytool.MyPlugin
```

3. Declare `SwissKitJ-Api` as a `provided` scope dependency in `pom.xml`:

```xml
<dependency>
    <groupId>fan.summer.api</groupId>
    <artifactId>SwissKitJ-Api</artifactId>
    <version>3.0.0</version>
    <scope>provided</scope>
</dependency>
```

4. Package the plugin as a fat JAR and drop it into the host application's `plugins/` directory. The host scans the directory on startup and watches for changes, enabling hot-reload.

### Plugin Registration Flow

- **External plugins**: discovered by `PluginLoader` via Java SPI (`ServiceLoader`) from `META-INF/services/` inside each JAR in the `plugins/` directory. Added to the `PluginRegistry` and displayed in the sidebar under the matching `ToolCategory`.
- **Built-in tools**: registered directly by `BuiltinToolRegistrar` without SPI. They bypass JAR loading and appear in the sidebar immediately.

---

## Enums

### ToolCategory

Categories used by sidebar navigation and tool grouping.

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/ToolCategory.java`

```java
package fan.summer.api;

public enum ToolCategory {
    DEV("dev", "developer.tools"),
    TEXT("text", "text.processing"),
    IMAGE("image", "image.processing"),
    NET("net", "network.tools"),
    OTHER("other", "other.tools");

    /** Lowercase identifier matching the legacy string (e.g. "dev"). */
    public String getId() { ... }

    /** Key for i18n resource bundle lookups (e.g. "developer.tools"). */
    public String getI18nKey() { ... }

    /** Convert from a legacy string. Returns OTHER for unrecognised input. */
    public static ToolCategory fromId(String id) { ... }
}
```

| Constant | `getId()`   | `getI18nKey()`     | Description           |
|----------|-------------|--------------------|-----------------------|
| `DEV`    | `"dev"`     | `"developer.tools"`| Developer utilities   |
| `TEXT`   | `"text"`    | `"text.processing"`| Text processing       |
| `IMAGE`  | `"image"`   | `"image.processing"`| Image processing     |
| `NET`    | `"net"`     | `"network.tools"`  | Network tools         |
| `OTHER`  | `"other"`   | `"other.tools"`    | Miscellaneous         |

---

### ToolType

Distinguishes built-in tools from externally loaded plugins.

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/ToolType.java`

```java
package fan.summer.api;

public enum ToolType {
    BUILTIN("builtin"),
    PLUGIN("plugin");

    /** Lowercase identifier matching the legacy string (e.g. "builtin"). */
    public String getId() { ... }

    public boolean isBuiltin() { return this == BUILTIN; }
    public boolean isPlugin()  { return this == PLUGIN; }
}
```

| Constant   | `getId()`    | `isBuiltin()` | `isPlugin()` |
|------------|--------------|---------------|--------------|
| `BUILTIN`  | `"builtin"`  | `true`        | `false`      |
| `PLUGIN`   | `"plugin"`   | `false`       | `true`       |

---

### IconStyle

CSS class names and associated colours for tool icon backgrounds.

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/IconStyle.java`

```java
package fan.summer.api;

import javafx.scene.paint.Color;

public enum IconStyle {
    BLUE("ic-blue",   Color.rgb(99, 130, 255)),
    PURPLE("ic-purple", Color.rgb(160, 110, 255)),
    TEAL("ic-teal",   Color.rgb(40, 210, 140)),
    AMBER("ic-amber", Color.rgb(255, 185, 50)),
    RED("ic-red",    Color.rgb(255, 100, 100)),
    PINK("ic-pink",   Color.rgb(245, 100, 160)),
    GRAY("ic-gray",   Color.rgb(200, 200, 210));

    /** CSS class applied to the icon wrapper, e.g. "ic-blue". */
    public String getCssClass() { ... }

    /** The colour used for Text fill and DropShadow glow. */
    public Color getColor() { ... }

    /** Convert from a legacy CSS class string. Returns BLUE for unrecognised input. */
    public static IconStyle fromCssClass(String cssClass) { ... }
}
```

| Constant | CSS Class      | Colour (RGB)          |
|----------|----------------|------------------------|
| `BLUE`   | `"ic-blue"`    | `rgb(99, 130, 255)`   |
| `PURPLE` | `"ic-purple"`  | `rgb(160, 110, 255)`  |
| `TEAL`   | `"ic-teal"`    | `rgb(40, 210, 140)`   |
| `AMBER`  | `"ic-amber"`   | `rgb(255, 185, 50)`   |
| `RED`    | `"ic-red"`     | `rgb(255, 100, 100)`  |
| `PINK`   | `"ic-pink"`    | `rgb(245, 100, 160)`  |
| `GRAY`   | `"ic-gray"`    | `rgb(200, 200, 210)`  |

---

## StepWizard Component

`StepWizard` is a reusable multi-step wizard container for use inside any plugin's `createView()`. It renders step dots with done/active/idle states, animated slide transitions between steps, and Back/Next buttons with built-in validation.

### Location

```
SwissKitJ-Api/src/main/java/fan/summer/api/component/StepWizard.java
```

### Constructor

```java
public StepWizard()
```

Creates an empty wizard with a transparent background. Call `addStep()` for each step, then `build()` to construct the UI.

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `addStep(String title, Node content, BooleanSupplier canProceed)` | `void` | Adds a step. `title` is displayed in the step indicator; `content` is the JavaFX Node to show; `canProceed` is evaluated on every Next click (return `false` to block advancement with a shake animation). |
| `build()` | `void` | **Must be called once** after all steps are added. Constructs the step indicator, content pane, and footer buttons. |
| `setOnStepChanged(StepChangeListener listener)` | `void` | Registers a listener that fires every time the current step changes. |
| `goTo(int index)` | `void` | Navigates to the step at the given 0-based index. |
| `getCurrentStep()` | `int` | Returns the current 0-based step index. |
| `getTotalSteps()` | `int` | Returns the total number of steps. |
| `isLastStep()` | `boolean` | Returns `true` if the current step is the last one. |

### StepChangeListener Interface

```java
public interface StepChangeListener {
    void onStepChanged(int from, int to, int total);
}
```

| Parameter | Description |
|-----------|-------------|
| `from`    | The previous step index (0-based) |
| `to`      | The new step index (0-based) |
| `total`   | Total number of steps |

### Example Usage

```java
StepWizard wizard = new StepWizard();

// Step 1: File selection
wizard.addStep("Select file", fileSelectPane, () -> selectedFile != null);

// Step 2: Split mode configuration
wizard.addStep("Split mode", splitModePane, () -> modeSelected);

// Step 3: Output directory
wizard.addStep("Output path", outputPathPane, () -> outputDir != null && Files.isDirectory(outputDir));

wizard.build();  // must call after all addStep() calls

// Intercept step transitions to trigger async work
wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) {
        startFileAnalysis();
    }
});

// Check state
if (wizard.isLastStep()) {
    startProcessing();
}

// Programmatic navigation
wizard.goTo(2);
```

The Back button is automatically disabled on the first step. On the last step, the Next button text changes to "Complete". If a step's `canProceed` supplier returns `false`, the Next button vibrates (shake animation) and the transition is blocked.

---

## Plugin Logging

SwissKitJ provides a logging abstraction so plugins can write log messages without depending on any specific logging framework. The host application installs a real logger binder (backed by SLF4J + Logback) at startup; until then, all loggers are silent no-ops -- safe for unit tests and isolated plugin loading.

The host's Logback configuration writes INFO+ to the console and DEBUG+ to rolling log files under `.swisskit/logs/swisskit.log` (daily rotation, 7-day retention).

### LoggerFactory

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/log/LoggerFactory.java`

```java
package fan.summer.api.log;

public final class LoggerFactory {
    /** Returns a logger named after the given class. */
    public static PluginLogger getLogger(Class<?> clazz) { ... }

    /** Returns a logger with the given name (dotted category). */
    public static PluginLogger getLogger(String name) { ... }
}
```

### PluginLogger

**Location**: `SwissKitJ-Api/src/main/java/fan/summer/api/log/PluginLogger.java`

The `PluginLogger` interface follows the SLF4J `{}` placeholder convention. All methods are safe to call from any thread.

```java
package fan.summer.api.log;

public interface PluginLogger {
    String getName();

    // Level checks
    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();

    // Trace
    void trace(String message);
    void trace(String format, Object arg);
    void trace(String format, Object arg1, Object arg2);
    void trace(String format, Object... args);
    void trace(String message, Throwable t);

    // Debug
    void debug(String message);
    void debug(String format, Object arg);
    void debug(String format, Object arg1, Object arg2);
    void debug(String format, Object... args);
    void debug(String message, Throwable t);

    // Info
    void info(String message);
    void info(String format, Object arg);
    void info(String format, Object arg1, Object arg2);
    void info(String format, Object... args);
    void info(String message, Throwable t);

    // Warn
    void warn(String message);
    void warn(String format, Object arg);
    void warn(String format, Object arg1, Object arg2);
    void warn(String format, Object... args);
    void warn(String message, Throwable t);

    // Error
    void error(String message);
    void error(String format, Object arg);
    void error(String format, Object arg1, Object arg2);
    void error(String format, Object... args);
    void error(String message, Throwable t);
}
```

### Example Usage

```java
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

public class MyPlugin implements SwissKitJPlugin {
    private static final PluginLogger log = LoggerFactory.getLogger(MyPlugin.class);

    @Override
    public void onActivate() {
        log.info("Plugin activated, taskId={}, config={}", taskId, config);
    }

    @Override
    public void onDeactivate() {
        log.debug("Plugin deactivated, saving state...");
    }

    @Override
    public void onUnload() {
        log.info("Plugin unloaded");
    }

    private void processFile(Path file) {
        try {
            log.debug("Processing file: {}", file);
            // ... work ...
            log.info("File processed successfully: {} rows", rowCount);
        } catch (Exception e) {
            log.error("Failed to process file: {}", file, e);
        }
    }
}
```

---

## Theming (Themes Utility)

Plugins embedded in the main Scene (the normal `createView()` flow) automatically inherit all three host stylesheets via scene graph propagation -- no action needed.

Plugins that open their own `Stage` or `Scene` (e.g. dialog windows) should call `Themes.applyTo(Scene)` to load the common utility CSS classes (glassmorphism dark theme).

### Location

```
SwissKitJ-Api/src/main/java/fan/summer/api/theme/Themes.java
```

```java
package fan.summer.api.theme;

import javafx.scene.Scene;

public final class Themes {
    /** Common stylesheet resource path inside the API JAR. */
    public static final String COMMON_CSS = "/css/swisskit-common.css";

    /** Returns the external form URL of the common stylesheet. */
    public static String commonStylesheetUrl() { ... }

    /** Applies the common stylesheet to an independent Scene (idempotent). */
    public static void applyTo(Scene scene) { ... }
}
```

### Example Usage

```java
// Plugin opens its own dialog Stage
Stage dialog = new Stage();
dialog.setTitle("Settings");
Scene scene = new Scene(dialogRoot, 600, 400);
Themes.applyTo(scene);  // loads glassmorphism utilities
dialog.setScene(scene);
dialog.show();
```

---

## Database Layer

### DatabaseInit

Handles database initialisation and MyBatis configuration. The database is an H2 file stored at `.swisskit/swisskit.db` relative to the runtime working directory. Schema is initialised from `init.sql` on first run.

```java
package fan.summer.database;

public class DatabaseInit {
    private static SqlSessionFactory sqlSessionFactory;

    /** Initialises the database and MyBatis. Creates tables from init.sql if not present. */
    public static void init() { }

    /** Returns a new SqlSession for database operations. */
    public static SqlSession getSqlSession() { }

    /** Returns the SqlSessionFactory. */
    public static SqlSessionFactory getSqlSessionFactory() { }
}
```

### Mapper Interfaces

Mapper XML files are located in `src/main/resources/mapper/`.

#### EmailAddressBookMapper

```java
public interface EmailAddressBookMapper {
    void insert(EmailAddressBookEntity entity);
    List<EmailAddressBookEntity> selectEmailAddressBook();
}
```

#### EmailTagMapper

```java
public interface EmailTagMapper {
    void insert(EmailTagEntity entity);
    void update(EmailTagEntity entity);
    List<EmailTagEntity> selectAll();
}
```

#### EmailSentLogMapper

```java
public interface EmailSentLogMapper {
    void insert(EmailSentLogEntity entity);
    List<EmailSentLogEntity> selectAll();
}
```

---

## Worker Classes

Workers perform background operations and report results or progress to the UI thread.

### ExcelAnalysisWorker

Reads all sheets and row-0 headers from an Excel file using Apache POI. Produces a `Map<String, Map<Integer, String>>` (sheet name to column index to header text).

```java
public ExcelAnalysisWorker(
    Path filePath,
    ProgressBar progressBar,
    Button startBtn,
    ExcelAnalysisCallback callback
)
```

### ExcelSplitWorker

Splits an Excel file into multiple output files. Supports three modes:
- **SSM (Split Sheet Model)**: one output file per selected sheet.
- **SCM (Split Column Model)**: groups rows by unique column value within a sheet, producing one file per group.
- **SCPM (Complex Split Model)**: multi-config split driven by `ComplexSplitConfigEntity` rows from the database; supports normal split and copy-all (when `headerIndex == -1 && columnIndex == -1`).

```java
public ExcelSplitWorker(
    Path outputPath,
    Path orgFilePath,
    ProgressBar progressBar,
    Button button,
    Node parentNode
)
```

### EmailSentWorker

Sends emails in the background using the Simple Java Mail library.

```java
public EmailSentWorker(
    String subject,
    String body,
    String taskId,
    boolean isMassSent,
    ProgressBar progressBar
)
```

---

## Utility Classes

### AppInfo

Application version and name constants.

**Location**: `src/main/java/fan/summer/utils/AppInfo.java`

```java
public abstract class AppInfo {
    public static final String VERSION = resolveVersion();
    public static final String NAME = loadProperty("app.name", "SwissKit");

    public static String getVersion();
    public static String getName();
    public static String getFullName();  // NAME + "-" + VERSION
}
```

### EmailUtil

Email sending utility using the Simple Java Mail library. Automatically loads SMTP configuration from the database.

**Location**: `src/main/java/fan/summer/utils/EmailUtil.java`

```java
public class EmailUtil {
    /** Sends a plain text email. */
    public static void sendText(String to, String subject, String body);

    /** Tests SMTP connection using current configuration. */
    public static boolean testConnection();

    /** Sends an email with full configuration. */
    public static void sendEmail(EmailMessage message);
}
```

**Example Usage**:

```java
// Plain text email
EmailUtil.sendText("to@example.com", "Subject", "Hello!");

// HTML email with attachments
EmailUtil.sendEmail(
    EmailMessage.builder()
        .to("a@example.com", "b@example.com")
        .cc("c@example.com")
        .subject("Monthly Report")
        .htmlBody("<h1>Report</h1>")
        .attachments(new File("report.pdf"))
        .build()
);

// Test connection
if (EmailUtil.testConnection()) {
    System.out.println("SMTP connection successful!");
}
```

### StringUtil

String validation utilities.

**Location**: `src/main/java/fan/summer/utils/StringUtil.java`

```java
public class StringUtil {
    /** Validates email address format. */
    public static boolean checkEmail(String email);
}
```

---

## Listeners

### HeaderListener

Listener for extracting Excel headers via the Fesod streaming reader.

**Location**: `src/main/java/fan/summer/kitpage/excel/listener/HeaderListener.java`

```java
public class HeaderListener extends AnalysisEventListener<Map<Integer, String>> {
    /** Returns the extracted headers (column index -> header text). */
    public Map<Integer, String> getHeaders();
    public void clear();
}
```

### NoModelDataListener

Listener for streaming Excel row data into memory.

**Location**: `src/main/java/fan/summer/kitpage/excel/listener/NoModelDataListener.java`

```java
public class NoModelDataListener extends AnalysisEventListener<Map<Integer, Object>> {
    /** Returns all cached rows (each row is column index -> cell value). */
    public List<Map<Integer, Object>> getCachedDataList();
    public void clear();
}
```

---

## Callback Interfaces

### QueryAllEmailInfoCallBack

Callback for email address book queries.

```java
public interface QueryAllEmailInfoCallBack {
    void onSuccess(List<EmailAddressBookEntity> emailAddressBookEntities);
    void onFailure(Exception e);
}
```

### ExcelAnalysisCallback

Callback for Excel analysis results.

```java
public interface ExcelAnalysisCallback {
    void onSuccess(Map<String, Map<Integer, String>> result);
    void onFailure(Exception e);
}
```

The result map shape: outer key is the sheet name (insertion order preserved via `LinkedHashMap`), inner key is the zero-based column index from POI, and the value is the column header text.

---

## Entity Classes

### EmailAddressBookEntity

```java
@Data
public class EmailAddressBookEntity {
    private Integer id;
    private String emailAddress;
    private String nickname;
    private String tags;  // JSON array
}
```

### EmailTagEntity

```java
@Data
public class EmailTagEntity {
    private Long id;
    private String tag;
}
```

### EmailSentLogEntity

```java
@Data
public class EmailSentLogEntity {
    private Long id;
    private String to;
    private String cc;
    private String bcc;
    private String subject;
    private String content;
    private String attachment;
    private Date sendTime;
    private boolean isSuccess;
}
```

### ComplexSplitConfigEntity

Used by the Excel SCPM (Complex Split) mode. A row with `headerIndex == -1 && columnIndex == -1` means "copy entire sheet to all output files".

```java
@Data
public class ComplexSplitConfigEntity {
    private Long id;
    private String taskId;
    private String fieldName;
    private String sheetName;
    private Integer headerIndex;   // 1-based row of headers
    private Integer columnIndex;   // 1-based column to split by
}
```

---

**Need more help?** Check out the [Development Guide](development.md) for implementation examples.
