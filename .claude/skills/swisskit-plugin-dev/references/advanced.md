# Advanced: AI Tools, Background Tasks, Persistence, Pitfalls

## AiTool

A plugin can expose tools that the host's AI chat can invoke (the model decides to call them
based on their description, passes arguments, and receives a result). Implement `AiTool` and
return it from `aiTools()`.

**Contract**
([`AiTool` source](https://github.com/MuskStark/SwissKitJ/blob/main/SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiTool.java)):

| Method | Required? | Purpose |
|---|---|---|
| `String getName()` | yes | Unique tool name |
| `String getDescription()` | yes | Shown to the model — be precise about what it does |
| `List<AiToolParam> getParameters()` | yes | Declared parameters |
| `AiToolResult execute(Map<String, Object> arguments)` | yes | Invoked when the model calls the tool |
| `String getLocalDescription()` | default → `getDescription()` | Override for local-mode-specific copy |
| `List<AiToolParam> getLocalParameters()` | default → `getParameters()` | Override for local-mode |
| `boolean supportsLocal()` | default `true` | Available in local inference mode |
| `boolean supportsCloud()` | default `true` | Available in cloud mode |

`AiToolParam` is a record `(String name, String type, String description, boolean required, List<String> enumValues)`
with factory `AiToolParam.of(...)` (3 overloads). `AiToolResult` is a record
`(boolean success, String output)` with `AiToolResult.success(String)` and
`AiToolResult.error(String)` factories.

**Pattern:**
```java
public class JsonFormatTool implements AiTool {
    @Override public String getName()        { return "format-json"; }
    @Override public String getDescription() { return "Pretty-prints a JSON string."; }
    @Override public List<AiToolParam> getParameters() {
        return List.of(AiToolParam.of("input", "string", "The JSON to format", true));
    }
    @Override
    public AiToolResult execute(Map<String, Object> args) {
        try {
            return AiToolResult.success(prettyPrint((String) args.get("input")));
        } catch (Exception e) {
            return AiToolResult.error("Invalid JSON: " + e.getMessage());
        }
    }
}

// In your plugin class:
@Override public List<AiTool> aiTools() { return List.of(new JsonFormatTool()); }
```

The host auto-registers your tools via `AiServiceProvider` when the plugin loads and
**auto-unregisters** them by name on unload. Duplicate tool names overwrite with a warning.
Tool `execute` runs through `PluginContext` (correct TCCL).

Reference builtin example:
[`BuiltinJsonFormatTool.java`](https://github.com/MuskStark/SwissKitJ/blob/main/SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java).

## Background tasks (`javafx.concurrent.Task`)

Never block the JavaFX Application thread. For long work (file I/O, network, computation),
subclass `Task<Void>` and run it on a worker thread; update the UI via the task's thread-safe
hooks (`updateProgress`, `updateMessage`, `updateValue`) or `Platform.runLater`.

```java
import javafx.concurrent.Task;
import javafx.application.Platform;

private Task<Void> runningTask;   // keep a reference so hasRunningTasks can report truthfully

private void startJob(ProgressBar bar, TextArea output) {
    runningTask = new Task<>() {
        @Override protected Void call() throws Exception {
            for (int i = 0; i < total; i++) {
                if (isCancelled()) break;
                // ... do chunk of work ...
                updateProgress(i, total);          // thread-safe → bar.progressProperty
                updateMessage("Processed " + i);    // thread-safe → bar.accessibleText / label
            }
            return null;
        }
    };
    bar.progressProperty().bind(runningTask.progressProperty());

    runningTask.setOnSucceeded(e -> {
        // onSucceeded runs on the FX thread — safe to touch UI directly
        output.setText("Done");
    });
    runningTask.setOnFailed(e ->
        output.setText("Failed: " + runningTask.getException().getMessage()));

    Thread t = new Thread(runningTask, "{{slug}}-worker");
    t.setDaemon(true);
    t.start();
}
```

**The `hasRunningTasks()` contract** — override it so the host knows not to evict your view
when the user navigates away:
```java
@Override
public boolean hasRunningTasks() {
    return runningTask != null && runningTask.isRunning();
}
```
While this returns `true`, the host fires `onBackground` (not `onDeactivate`) and keeps your
view cached, so work continues. Cancel on `onUnload`:
```java
@Override public void onUnload() {
    if (runningTask != null) runningTask.cancel();
}
```

> Don't call JavaFX scene-graph APIs (`setText`, `setDisable`, ...) directly from `call()` —
> use the `updateXxx` hooks or wrap in `Platform.runLater(() -> ...)`.

## Persistence (H2 + MyBatis) — optional, plugin-bundled

The host does **not** expose its database layer to plugins. If your plugin needs persistence,
bundle your own H2 + MyBatis (default scope, shaded into your JAR). The
`ChildFirstResourceClassLoader` ensures your `mybatis-config.xml` and mapper XMLs resolve from
**your JAR** first.

- DB path convention: `.swisskit/plugins/database/pl_{{slug}}` (relative to `user.dir`).
- JDBC URL: `jdbc:h2:file:<path>;AUTO_SERVER=TRUE;INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC\;SET SCHEMA PUBLIC`
  (use forward slashes even on Windows).
- MyBatis settings: `mapUnderscoreToCamelCase=true`, `localCacheScope=STATEMENT`,
  `cacheEnabled=false`, `jdbcTypeForNull=NULL`, UNPOOLED datasource, `org.h2.Driver`.
- **CRITICAL:** the XML `<mapper namespace>` must **exactly equal** the Java interface FQCN,
  or you get a `BindingException`.

Full setup (DDL, `init.sql`, mapper XML) is documented in the
[Database Layer guide](https://muskstark.github.io/SwissKitJ/#/plugins/database).

## Excel I/O (FesodSheet) — optional

Bundle `org.apache.fesod:fesod-sheet` (default scope). DTOs annotated with
`@ExcelProperty(index=...)`; read via a `ReadListener<T>` (batch flush through a mapper), write
via `FesodSheet.write(file).sheet(name).head(...).doWrite(...)`. Heavy reads must run inside a
`Task` (see above). Full API in the [Excel I/O guide](https://muskstark.github.io/SwissKitJ/#/plugins/excel).

## Pitfalls digest

The recurring failures, each with its cause and fix:

| Symptom | Cause | Fix |
|---|---|---|
| **Plugin doesn't appear in the host** | SPI file missing, at wrong path, or overwritten by shade | File must be `META-INF/services/fan.summer.api.SwissKitJPlugin` (not `services/` at root); shade plugin needs `ServicesResourceTransformer`. Verify: `unzip -p target/*.jar META-INF/services/fan.summer.api.SwissKitJPlugin` |
| **Plugin visible but UI shows raw keys** (`plugin.csv-sorter.name`) | i18n bundle not registered | Call `I18n.registerPluginBundle("i18n.messages", getClass().getClassLoader())` at the start of `createView()` |
| **Plugin throws `NoClassDefFoundError: javafx/application/Application` in dev** | `DevLauncher` imports JavaFX | `DevLauncher` must have ZERO JavaFX imports — it only calls `{{Name}}DevApp.main(args)` |
| **Colors are frozen / wrong on theme switch** | Inline hex in `setStyle` | Replace hex with `-sk-*` tokens or `.sk-*` classes (a `-sk-*` token string resolves; a hex literal doesn't) |
| **Standalone popup window renders unthemed** | Didn't apply theme to the popup scene | Call `Themes.applyTo(scene)` on the popup's `Scene` (embedded views don't need this) |
| **ScrollPane shows a tiny box** | Max size clamped in a Pane | `setMaxWidth(Double.MAX_VALUE)` + `setMaxHeight(Double.MAX_VALUE)` |
| **HBox/VBox layout collapses** | Used `setPrefWidth(MAX_VALUE)` | Use `setMaxWidth(MAX_VALUE)` + `HBox.setHgrow(node, Priority.ALWAYS)` instead |
| **StackPane shows hidden pages occupying space** | Toggled only `visible` | Toggle BOTH `setVisible` and `setManaged` |
| **`BindingException` from MyBatis** | Mapper XML namespace ≠ interface FQCN | Make `<mapper namespace>` exactly equal the Java interface FQCN |
| **Back-click kills a running background job** | `hasRunningTasks()` lies (default `false`) | Override to return `true` while the task runs; cancel on `onUnload` |
| **Plugin's classes/`ServiceLoader`/MyBatis can't find resources** | (Unusual) TCCL not set | The host sets the TCCL via `PluginContext` for you — make sure you're not spawning threads that shed it; spawn from event handlers (which are wrapped) |
| **`*PluginUi` wrapper compiles but the host won't load it** | Wrapper isn't the SPI entry | Implement `SwissKitJPlugin` in ONE class; the SPI file points at it |

For the exhaustive list, see the
[Common Pitfalls doc](https://muskstark.github.io/SwissKitJ/#/plugins/pitfalls) (12 entries —
note it predates 3.2.0 slightly; cross-check method names against [contract.md](contract.md)).
