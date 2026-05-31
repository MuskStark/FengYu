# AI Tool Calling Refactor Design

**Date**: 2026-05-27
**Branch**: v3.0.0-beta.2

## Goal

Refactor the AI tool-calling logic to eliminate duplication, centralize the shared tool registry, introduce Gson for JSON handling, and register all built-in tools as AI-callable tools.

## Current Problems

1. **Duplicated tool loop logic** — each service (local/OpenAI/Anthropic) independently implements the same tool call-execute-feed loop.
2. **Per-service tool registry** — each `AiService` has its own `ToolRegistry`; switching backends loses registered tools.
3. **Duplicated JSON Schema building** — `buildJsonSchema()` is copy-pasted across `OpenAiService` and `AnthropicService`.
4. **Manual JSON string building** — 5 separate `jsonEscape()` methods across Excel tools; hand-rolled JSON parser in `ToolCallParser`.
5. **Excel-only AI tools** — only Excel tools are exposed to AI; other built-in tools (Base64, Hash, JSON, Color) are not.

## Design

### 1. Shared ToolRegistry in AiServiceProvider

Move the `ToolRegistry` instance from each `AiService` implementation to `AiServiceProvider` as a static field.

**Changes**:
- `AiServiceProvider` gains `private static final ToolRegistry toolRegistry = new ToolRegistry()` and `public static ToolRegistry getToolRegistry()`.
- `AiServiceImpl`, `OpenAiService`, `AnthropicService` remove their private `ToolRegistry` fields. All references go through `AiServiceProvider.getToolRegistry()`.
- `AiService.registerTool()`, `unregisterTool()`, `getTools()` in each implementation delegate to `AiServiceProvider.getToolRegistry()`.
- `switchMode()` no longer loses tools — the registry is independent of the active service.

### 2. ToolExecutor — shared execute-and-feed-result loop

Extract the duplicated tool execution logic into `ToolExecutor` in `fan.summer.ai.tools`.

```java
public class ToolExecutor {
    public static void executeAndFeed(
            List<AiToolCall> toolCalls,
            List<AiChatMessage> history,
            AiStreamCallback callback) {
        ToolRegistry registry = AiServiceProvider.getToolRegistry();
        for (AiToolCall tc : toolCalls) {
            Platform.runLater(() -> callback.onToolCall(tc));
            AiToolResult result = registry.execute(tc.name(), tc.arguments());
            Platform.runLater(() -> callback.onToolResult(tc.id(), result));
            history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
        }
    }
}
```

Each service replaces its copy-pasted tool execution block with `ToolExecutor.executeAndFeed(...)`.

### 3. ToolSchemaBuilder — centralized JSON Schema generation

Extract schema-building into `ToolSchemaBuilder` in `fan.summer.ai.tools`.

```java
public class ToolSchemaBuilder {
    public static List<Object> buildOpenAiTools(List<AiTool> tools);
    public static List<Object> buildAnthropicTools(List<AiTool> tools);
    public static String buildPromptDefinitions(List<AiTool> tools);
    private static Map<String, Object> buildJsonSchema(List<AiToolParam> params);
}
```

- `buildOpenAiTools()` — OpenAI format: `{ type: "function", function: { name, description, parameters: { schema } } }`
- `buildAnthropicTools()` — Anthropic format: `{ name, description, input_schema: { schema } }`
- `buildPromptDefinitions()` — text descriptions for system prompt injection (local backend). Moves from `ToolRegistry.buildToolDefinitions()`.
- `buildJsonSchema()` — shared JSON Schema object builder.

`ToolRegistry` becomes a pure registry (register/unregister/execute) with no formatting logic.

### 4. Gson Integration

**Dependency**: Add `com.google.code.gson:gson:2.13.1` to `SwissKit/pom.xml`.

**JsonHelper utility** in `fan.summer.ai.util`:

```java
public final class JsonHelper {
    private static final Gson GSON = new Gson();
    public static String toJson(Object obj) { return GSON.toJson(obj); }
    public static <T> T fromJson(String json, Class<T> type) { return GSON.fromJson(json, type); }
    public static <T> T fromJson(String json, Type type) { return GSON.fromJson(json, type); }
}
```

**Replacements**:

| Location | Before | After |
|----------|--------|-------|
| `ExcelAnalyzeTool.execute()` | `StringBuilder` + `jsonEscape()` | `JsonHelper.toJson(resultMap)` |
| `ExcelConfigureTool.execute()` | `String.format()` + `jsonEscape()` | `JsonHelper.toJson(resultMap)` |
| `ExcelExecuteTool.execute()` | `StringBuilder` + `jsonEscape()` | `JsonHelper.toJson(resultMap)` |
| `ExcelQueryTool.execute()` | `StringBuilder` + `jsonEscape()` | `JsonHelper.toJson(resultMap)` |
| `ToolCallParser.parseSimpleJson()` | Hand-rolled JSON parser | `JsonHelper.fromJson()` |
| `JsonBuilder.toJson()` | Existing util class | `JsonHelper.toJson()` |
| `JsonParser.parseObject()` / `parse()` | Existing util class | `JsonHelper.fromJson()` |

All 5 `jsonEscape()` methods deleted. `JsonBuilder` and `JsonParser` classes deleted if fully replaced.

### 5. Register Built-in Tools as AI Tools

**New class**: `BuiltinAiToolRegistrar` in `fan.summer.ai.tools`.

Called once from `SwissKitJApp.start()` after `AiServiceProvider` is initialized.

**New AI tool implementations**:

| Class | Tool Name | Description |
|-------|-----------|-------------|
| `Base64EncodeTool` | `base64_encode` | Encode text to Base64. Arg: `text` (string, required) |
| `Base64DecodeTool` | `base64_decode` | Decode Base64 to text. Arg: `text` (string, required) |
| `HashCalculateTool` | `hash_calculate` | Compute hash. Args: `text` (string, required), `algorithm` (string, required: MD5/SHA-1/SHA-256) |
| `JsonFormatTool` | `json_format` | Format JSON. Args: `json` (string, required), `minify` (boolean, optional, default false) |
| `ColorConvertTool` | `color_convert` | Convert color format. Args: `color` (string, required), `from` (string: HEX/RGB/HSL), `to` (string: HEX/RGB/HSL) |

**Excel tools**: Move registration from `AiChatPlugin.onActivate()` to `BuiltinAiToolRegistrar`. The `ExcelAnalyzeTool`, `ExcelConfigureTool`, `ExcelExecuteTool` constructors still take `ExcelSplitterPlugin` — the registrar resolves it from `PluginRegistry.getInstance()`.

**Registration flow**:
1. `SwissKitJApp.start()` creates `PluginRegistry` and `BuiltinToolRegistrar` (UI plugins).
2. `AiServiceProvider` is initialized with the default `AiService`.
3. `BuiltinAiToolRegistrar.register()` is called — registers all AI tools into `AiServiceProvider.getToolRegistry()`.
4. Tools are available immediately regardless of which UI plugin is active.

## Files Changed

### New files
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java`
- `SwissKit/src/main/java/fan/summer/ai/util/JsonHelper.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64EncodeTool.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64DecodeTool.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashCalculateTool.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java`
- `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java`

### Modified files
- `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java` — add shared `ToolRegistry`
- `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` — remove private `ToolRegistry`, use `ToolExecutor`, `ToolSchemaBuilder`, `JsonHelper`
- `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java` — same as above
- `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java` — same as above
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolRegistry.java` — remove `buildToolDefinitions()`, keep pure registry
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java` — use Gson for JSON parsing
- `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java` — remove Excel tool registration logic
- `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java` — use Gson for JSON output
- `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java` — use Gson for JSON output
- `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java` — use Gson for JSON output
- `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java` — use Gson for JSON output
- `SwissKit/pom.xml` — add Gson dependency
- `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` — call `BuiltinAiToolRegistrar.register()`

### Deleted files (if fully replaced)
- `SwissKit/src/main/java/fan/summer/ai/service/JsonBuilder.java` — replaced by `JsonHelper`
- `SwissKit/src/main/java/fan/summer/ai/service/JsonParser.java` — replaced by `JsonHelper`
