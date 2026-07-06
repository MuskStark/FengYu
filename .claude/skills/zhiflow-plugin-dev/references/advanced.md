# Advanced: AI Tools, Background Tasks, Persistence, Pitfalls

## AiTool

A plugin can expose tools that the host's AI chat can invoke (the model decides to call them
based on their description, passes arguments, and receives a result). Implement `AiTool` and
return it from `aiTools()`.

**Contract**
([`AiTool` source](https://github.com/MuskStark/ZhiFlow/blob/main/ZhiFlow-Api/src/main/java/fan/summer/api/ai/AiTool.java)):

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
[`BuiltinJsonFormatTool.java`](https://github.com/MuskStark/ZhiFlow/blob/main/ZhiFlow/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java).

## Background tasks (`host.tasks()`)

Never block the JavaFX Application thread. In 3.2.0, submit long work (file I/O, network,
computation) through the injected facade — **not** a raw `javafx.concurrent.Task` or a
hand-managed `Thread`. `host.tasks()` runs the work on a background thread with the plugin's
ClassLoader already on the TCCL (so bundled H2/MyBatis/`ServiceLoader` resolve), invokes
`onSuccess`/`onError` on the FX thread, logs uncaught throwables, and — crucially — keeps the
plugin alive in the background while the job runs. That last point means you do **not** override
`hasRunningTasks()` for facade-submitted work: the host ORs `tasks().runningCount()` with
`hasRunningTasks()`, so a running task already blocks the back-click eviction.

```java
// Fire-and-forget:
host.tasks().submit("{{slug}}-cleanup", () -> deleteTempFiles());

// With result callbacks (both land on the FX thread — safe to touch the scene graph):
private void startJob(ProgressBar bar, TextArea output) {
    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    host.tasks().submit("{{slug}}-export",
        () -> doHeavyExport(inputArea.getText()),          // background; TCCL set
        result -> { bar.setProgress(1); output.setText(result); },   // FX thread
        error  -> { bar.setProgress(0);                              // FX thread
                    output.setText("Failed: " + error.getMessage()); });
}
```

`submit(...)` returns a `TaskHandle` for cancellation/status queries. On plugin unload the host
calls `cancelAll()` for you (interrupting running tasks), so a long loop should check
`Thread.currentThread().isInterrupted()` and bail. You no longer need a manual `onUnload`
`cancel()` for facade tasks.

> Only reach for a raw `Task`/`updateProgress` binding when you need JavaFX's built-in
> progress-property plumbing *and* you have no host — which, in 3.2.0, you always do. Prefer
> `host.tasks()`. Never call scene-graph APIs from the background `work` lambda; do UI updates
> in the `onSuccess`/`onError` callbacks (already on the FX thread) or via `Platform.runLater`.

## Small settings/preferences → `host.settings()` (not a bundled DB)

For a plugin's own preferences (last-used directory, remembered options), use the host's
namespaced KV store — don't stand up an H2 database just for a handful of strings:

```java
String lastDir = host.settings().get("last.dir", System.getProperty("user.home"));
host.settings().put("last.dir", chosenDir);   // async persist; put(k, null) == remove
```

Data is isolated per `pluginId()` and survives hot-reload; the host clears it on explicit
uninstall. Reserve a bundled database (below) for genuine plugin *datasets*.

## Persistence (H2 + MyBatis) — optional, plugin-bundled

The host does **not** expose its database layer to plugins. If your plugin needs persistence
of real datasets (not just preferences — use `host.settings()` for those), bundle your own
H2 + MyBatis (default scope, shaded into your JAR). The
`ChildFirstResourceClassLoader` ensures your `mybatis-config.xml` and mapper XMLs resolve from
**your JAR** first.

- DB path convention: `.zhiflow/plugins/database/pl_{{slug}}` (relative to `user.dir`).
- JDBC URL: `jdbc:h2:file:<path>;AUTO_SERVER=TRUE;INIT=CREATE SCHEMA IF NOT EXISTS PUBLIC\;SET SCHEMA PUBLIC`
  (use forward slashes even on Windows).
- MyBatis settings: `mapUnderscoreToCamelCase=true`, `localCacheScope=STATEMENT`,
  `cacheEnabled=false`, `jdbcTypeForNull=NULL`, UNPOOLED datasource, `org.h2.Driver`.
- **CRITICAL:** the XML `<mapper namespace>` must **exactly equal** the Java interface FQCN,
  or you get a `BindingException`.

Full setup (DDL, `init.sql`, mapper XML) is documented in the
[Database Layer guide](https://muskstark.github.io/ZhiFlow/#/plugins/database).

## Excel I/O (FesodSheet) — optional

Bundle `org.apache.fesod:fesod-sheet` (default scope). DTOs annotated with
`@ExcelProperty(index=...)`; read via a `ReadListener<T>` (batch flush through a mapper), write
via `FesodSheet.write(file).sheet(name).head(...).doWrite(...)`. Heavy reads must run inside a
`Task` (see above). Full API in the [Excel I/O guide](https://muskstark.github.io/ZhiFlow/#/plugins/excel).

## Pitfalls digest

The recurring failures, each with its cause and fix:

| Symptom | Cause | Fix |
|---|---|---|
| **Plugin doesn't appear in the host** | SPI file missing, at wrong path, or overwritten by shade | File must be `META-INF/services/fan.summer.zhiflow.api.ZhiFlowPlugin` (not `services/` at root); shade plugin needs `ServicesResourceTransformer`. Verify: `unzip -p target/*.jar META-INF/services/fan.summer.zhiflow.api.ZhiFlowPlugin` |
| **Plugin visible but UI shows raw keys** (`plugin.csv-sorter.name`) | i18n bundle not registered | Call `host.i18n().registerBundle("i18n.messages")` at the start of `createView()` (the 3.2.0 facade form — no ClassLoader arg) |
| **`NullPointerException` reading `host` in `createView()`/`getName()`** | Forgot `init(PluginHost)`, or ran standalone without a host | Implement `init()` and store the reference; in dev, launch via `PluginPreviewWindow` (it injects the host) — see [migration.md §Dev-mode note](migration.md) |
| **Plugin throws `NoClassDefFoundError: javafx/application/Application` in dev** | `DevLauncher` imports JavaFX | `DevLauncher` must have ZERO JavaFX imports — it only calls `{{Name}}DevApp.main(args)` |
| **Colors are frozen / wrong on theme switch** | Inline hex in `setStyle` | Replace hex with `-sk-*` tokens or `.sk-*` classes (a `-sk-*` token string resolves; a hex literal doesn't) |
| **Standalone popup window renders unthemed** | Didn't apply theme to the popup scene | Call `host.theme().applyTo(scene)` on the popup's `Scene` (embedded views don't need this; `Themes.applyTo` is the forbidden legacy static) |
| **`.glass-*` styled node renders unstyled** | Using pre-3.2.0 CSS class names | Rename to the `.sk-*` equivalent (`.glass-dialog` → `.sk-dialog`, etc.) |
| **ScrollPane shows a tiny box** | Max size clamped in a Pane | `setMaxWidth(Double.MAX_VALUE)` + `setMaxHeight(Double.MAX_VALUE)` |
| **HBox/VBox layout collapses** | Used `setPrefWidth(MAX_VALUE)` | Use `setMaxWidth(MAX_VALUE)` + `HBox.setHgrow(node, Priority.ALWAYS)` instead |
| **StackPane shows hidden pages occupying space** | Toggled only `visible` | Toggle BOTH `setVisible` and `setManaged` |
| **`BindingException` from MyBatis** | Mapper XML namespace ≠ interface FQCN | Make `<mapper namespace>` exactly equal the Java interface FQCN |
| **Back-click kills a running background job** | Ran the job on a raw `Task`/`Thread` the host can't see | Submit via `host.tasks().submit(...)` — running tasks auto-keep the plugin alive (no `hasRunningTasks()` override needed) |
| **Plugin's classes/`ServiceLoader`/MyBatis can't find resources** | (Unusual) TCCL not set | The host sets the TCCL via `PluginContext` for you — make sure you're not spawning threads that shed it; spawn from event handlers (which are wrapped) |
| **`*PluginUi` wrapper compiles but the host won't load it** | Wrapper isn't the SPI entry | Implement `ZhiFlowPlugin` in ONE class; the SPI file points at it |

For the exhaustive list, see the
[Common Pitfalls doc](https://muskstark.github.io/ZhiFlow/#/plugins/pitfalls) (12 entries —
note it predates 3.2.0 slightly; cross-check method names against [contract.md](contract.md)).
