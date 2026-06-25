---
name: create-builtin-tool
description: Use when creating a new built-in tool for SwissKitJ. All built-in tools must implement both the visual UI (SwissKitJPlugin) and AI-callable interface (AiTool).
disable-model-invocation: true
---

# Create Built-in Tool

Scaffold a new built-in tool that is both a visual plugin and AI-callable.

## Usage

`/create-builtin-tool <tool-name> <category>`

- `tool-name`: kebab-case identifier (e.g. `pdf-merger`, `image-resizer`)
- `category`: one of `dev`, `text`, `image`, `net`, `other`

## Architecture

Every built-in tool has **two halves**:

| Half | Interface | Location | Purpose |
|------|-----------|----------|---------|
| **UI Plugin** | `SwissKitJPlugin` | `buildintool/<toolname>/<Name>Plugin.java` | Visual JavaFX interface shown in the app |
| **AI Tool(s)** | `AiTool` | `buildintool/ai/<Name>AiTool.java` | Callable by the AI chat (SwissKitJClaw) |

Both halves share state via a config/field object held by the Plugin instance. AI tools receive the plugin reference in their constructor.

## Files to Create / Modify

### Checklist

```
CREATE  buildintool/<toolname>/<Name>Plugin.java    ← SwissKitJPlugin impl (UI + aiTools() override)
CREATE  buildintool/<toolname>/<Name>Config.java    ← shared state POJO (if needed)
CREATE  buildintool/<toolname>/<Name>Worker.java    ← core logic (if needed)
CREATE  buildintool/ai/<Name>AiTool.java            ← AiTool impl (at least one)
MODIFY  Registrar/BuiltinToolRegistrar.java         ← add to List.of(...)
MODIFY  resources/i18n/messages.properties          ← i18n keys
MODIFY  resources/i18n/messages_en.properties       ← i18n keys (if exists)
OPT     resources/init.sql                          ← DB table (if tool uses H2)
OPT     resources/mapper/<name>/                    ← MyBatis mapper (if tool uses H2)
```

## 1. UI Plugin — SwissKitJPlugin Implementation

```java
package fan.summer.buildintool.<toolname>;

import fan.summer.api.*;
import fan.summer.api.i18n.I18n;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;

public class <Name>Plugin implements SwissKitJPlugin {

    private static final PluginLogger log = LoggerFactory.getLogger(<Name>Plugin.class);
    private Node view;
    private final <Name>Config config = new <Name>Config();

    // ── Metadata ──────────────────────────────────────

    @Override public String getId()          { return "fan.summer.buildin.<toolname>"; }
    @Override public String getName()        { return I18n.get("builtin.<toolname>.name"); }
    @Override public String getDescription() { return I18n.get("builtin.<toolname>.desc"); }
    @Override public ToolCategory getCategory()    { return ToolCategory.<CATEGORY>; }
    @Override public String getVersion()     { return "1.0.0"; }
    @Override public String getMdiIcon()    { return "<mdi-icon-name>"; }     // no "mdi-" prefix
    @Override public IconStyle getIconStyle()   { return IconStyle.<COLOR>; }
    @Override public ToolType getType()        { return ToolType.BUILTIN; }

    // ── Lifecycle ─────────────────────────────────────

    @Override public void onActivate()   { log.info("<Name> activated"); }
    @Override public void onDeactivate() { log.info("<Name> deactivated"); }

    @Override
    public Node createView() {
        if (view != null) return view;
        // Build UI here...
        VBox root = new VBox();
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: transparent;");
        view = root;
        return view;
    }

    /** Shared config for AI tools to read/write. */
    public <Name>Config getConfig() { return config; }
}
```

**Metadata rules:**
- `getId()`: must follow `fan.summer.buildin.<toolname>` pattern
- `getMdiIcon()`: pick from [pictogrammers.com/library/mdi](https://pictogrammers.com/library/mdi/) — no `mdi-` prefix
- `getIconStyle()`: pick from `BLUE, PURPLE, TEAL, AMBER, RED, PINK, GRAY`
- `getType()`: always `ToolType.BUILTIN`
- `getCategory()`: map to the corresponding `ToolCategory` enum

## 2. AI Tool — AiTool Implementation

### Simple tool (no plugin state needed)

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class <Name>AiTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(<Name>AiTool.class);

    @Override public String getName()        { return "<toolname>"; }
    @Override public String getDescription() { return "Description for the AI model. Args: param (type) — explanation."; }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("input", "string", "The input to process", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String input = (String) args.get("input");
        if (input == null || input.isBlank()) return AiToolResult.error("input is required");

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("output", processInput(input));
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("<toolname> error: {}", e.getMessage());
            return AiToolResult.error("Error: " + e.getMessage());
        }
    }
}
```

### Plugin-bound tool (reads/writes shared plugin state)

```java
public class <Name>AiTool implements AiTool {
    private final <Name>Plugin plugin;

    public <Name>AiTool(<Name>Plugin plugin) { this.plugin = plugin; }

    @Override public AiToolResult execute(Map<String, Object> args) {
        <Name>Config config = plugin.getConfig();
        // Read/write config, invoke logic, return result
        // ...
        return AiToolResult.success(JsonHelper.toJson(result));
    }
}
```

**AI tool conventions:**
- Tool name: use `snake_case` (e.g. `excel_analyze`, `base64`)
- For multi-step tools, create one AiTool per step (see Excel: `excel_analyze`, `excel_configure`, `excel_execute`)
- Always return JSON via `JsonHelper.toJson()` wrapped in `AiToolResult.success()` / `AiToolResult.error()`
- Always validate required arguments before processing
- Always use `LoggerFactory.getLogger()` for logging — never SLF4J directly

## 3. Registration

### BuiltinToolRegistrar.java (UI + AI)

Add the new plugin to the `List.of(...)` in `register()`:

```java
// In BuiltinToolRegistrar.register():
List<SwissKitJPlugin> builtins = List.of(
    // ... existing tools ...
    new <Name>Plugin()    // ← add here
);
```

`BuiltinToolRegistrar` routes the list through `PluginRegistry.addPlugins(...)`, which — as of v3.1.0 — also auto-registers each plugin's `aiTools()` with `AiServiceProvider`. There is no separate AI-tool registration step. The old `BuiltinAiToolRegistrar` class is gone.

### Exposing AI tools via the plugin (v3.1.0+)

Each plugin self-declares its AI tools by overriding `aiTools()`:

```java
public class <Name>Plugin implements SwissKitJPlugin {
    private final <Name>Config config = new <Name>Config();

    @Override
    public List<AiTool> aiTools() {
        return List.of(
            new <Name>AiTool(this)        // plugin-bound tool
            // ...add more tools here...
        );
    }

    public <Name>Config getConfig() { return config; }
}
```

The registry handles registration on add and unregistration on remove (including hot-reload). For standalone UI-less tools (no visual plugin), host them on a minimal `SwissKitJPlugin` whose `createView()` returns an empty pane — see `BrowserAutomatePlugin` as a template.

#### Cloud / local capability declaration

Each `AiTool` can declare per-mode visibility (defaults: both true) and provide a simplified local-mode description/parameter set:

```java
@Override public boolean supportsLocal() { return false; }  // hide from local (Qwen3-4B)
@Override public boolean supportsCloud() { return true; }   // visible in cloud

@Override public String getLocalDescription() {
    return "Short Qwen3-friendly description with enum: a|b|c.";
}

@Override public List<AiToolParam> getLocalParameters() {
    return List.of(AiToolParam.of("x", "string", "X"));
}
```

See `CLAUDE.md` → "### Plugin AI tools (v3.1.0+)" for the full filter rules and the tool-return JSON contract (`{success, summary, ...payload}`).

## 4. i18n

Add keys to both `messages.properties` and `messages_en.properties`:

```properties
# Built-in: <Tool Name>
builtin.<toolname>.name=<Display Name>
builtin.<toolname>.desc=<One-line description>
builtin.<toolname>.<key>=<value>
```

All user-visible strings must use `I18n.get("key")`. Never hardcode text in Java.

## 5. Multi-step Wizard UI (Optional)

For tools with a multi-step workflow, use `StepWizard` from `SwissKitJ-Api`:

```java
StepWizard wizard = new StepWizard();
wizard.addStep("Step 1 Title", step1Node, () -> validateStep1());
wizard.addStep("Step 2 Title", step2Node, () -> validateStep2());
wizard.build();

wizard.setOnStepChanged((from, to, total) -> {
    if (from == 0 && to == 1) refreshStep2();
});

VBox root = new VBox(wizard);
VBox.setVgrow(wizard, Priority.ALWAYS);
root.setPadding(new Insets(24));
root.setStyle("-fx-background-color: transparent;");
```

## 6. Database Support (Optional)

If the tool needs H2 persistence:

1. Add table DDL to `resources/init.sql`
2. Create entity class in `database/entity/<group>/`
3. Create mapper interface in `database/mapper/<group>/`
4. Create mapper XML in `resources/mapper/<group>/`
5. Access via `DatabaseInit.getSqlSession()` → `session.getMapper(XxxMapper.class)`

## JavaFX Layout Checklist

Before completing any built-in tool UI, verify:

- [ ] `ScrollPane` inside `StackPane` has `setMaxWidth(Double.MAX_VALUE)` and `setMaxHeight(Double.MAX_VALUE)`
- [ ] "Fill the rest" uses `setMaxWidth(Double.MAX_VALUE)` + `HBox/VBox.setHgrow(node, Priority.ALWAYS)` — never `setPrefWidth(Double.MAX_VALUE)`
- [ ] No binding of `maxWidthProperty` to own `widthProperty` (circular dependency)
- [ ] Swapping pages in `StackPane`: toggle both `setVisible()` and `setManaged()`
- [ ] Root VBox always has `setStyle("-fx-background-color: transparent;")`
- [ ] All controls use inline glassmorphism styles (rgba colors on dark background)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Forgetting `ToolType.BUILTIN` | Always return `ToolType.BUILTIN` from `getType()` |
| Using SLF4J directly | Use `fan.summer.api.log.LoggerFactory.getLogger()` |
| Hardcoding user-visible strings | Use `I18n.get("key")` for everything |
| Not making the tool AI-callable | Every built-in tool MUST have at least one `AiTool` implementation |
| AI tool not registered | Override `aiTools()` on the plugin class — the registry auto-registers them when the plugin is added (v3.1.0+). No separate registrar exists. |
| `prefWidth = MAX_VALUE` | Use `setMaxWidth(Double.MAX_VALUE)` + grow priority instead |
| Forgetting `JsonHelper.toJson()` for AI results | Always wrap structured results in JSON via `JsonHelper.toJson()` |

## Build Verification

After creating all files, verify with IDEA MCP:

```
mcp__idea__build_project (rebuild=true)
```

Check for compilation errors. Fix any import issues or missing i18n keys.
