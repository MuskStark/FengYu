# AI Tool Calling Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Centralize AI tool-calling logic, replace manual JSON building with Gson, and register all built-in tools as AI-callable tools.

**Architecture:** Move the tool registry into `AiServiceProvider` as a single static source of truth. Extract shared `ToolExecutor`, `ToolSchemaBuilder`, and `JsonHelper` utilities. Each `AiService` implementation delegates to these shared components. Register 5 new built-in AI tools (Base64, Hash, JSON Format, Color Convert) plus the existing Excel tools via a new `BuiltinAiToolRegistrar`.

**Tech Stack:** Java 21, JavaFX 21, Gson 2.13.1, existing AI backends (local llama.cpp, OpenAI API, Anthropic API)

---

## File Structure

### New files
| File | Responsibility |
|------|---------------|
| `SwissKit/src/main/java/fan/summer/ai/util/JsonHelper.java` | Gson wrapper + JSON navigation utilities |
| `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java` | Execute tool calls + feed results back to model |
| `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java` | Build tool definitions per backend format |
| `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java` | Register all built-in AI tools at startup |
| `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java` | Base64 encode/decode AI tool |
| `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java` | Hash calculation AI tool |
| `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java` | JSON format/minify AI tool |
| `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java` | Color conversion AI tool |

### Modified files
| File | Change |
|------|--------|
| `SwissKit/pom.xml` | Add Gson dependency |
| `SwissKitJ-Api/.../ai/AiServiceProvider.java` | Add shared tool registry (static ConcurrentHashMap) |
| `SwissKit/.../ai/service/AiServiceImpl.java` | Remove private ToolRegistry, use shared components |
| `SwissKit/.../ai/service/OpenAiService.java` | Remove private ToolRegistry, use shared components |
| `SwissKit/.../ai/service/AnthropicService.java` | Remove private ToolRegistry, use shared components |
| `SwissKit/.../ai/tools/ToolCallParser.java` | Use Gson for JSON parsing |
| `SwissKit/.../buildintool/ai/AiChatPlugin.java` | Remove Excel tool registration |
| `SwissKit/.../buildintool/ai/ExcelAnalyzeTool.java` | Use Gson for JSON output |
| `SwissKit/.../buildintool/ai/ExcelConfigureTool.java` | Use Gson for JSON output |
| `SwissKit/.../buildintool/ai/ExcelExecuteTool.java` | Use Gson for JSON output |
| `SwissKit/.../buildintool/ai/ExcelQueryTool.java` | Use Gson for JSON output |
| `SwissKit/.../app/SwissKitJApp.java` | Call BuiltinAiToolRegistrar.register() |

### Deleted files
| File | Reason |
|------|--------|
| `SwissKit/.../ai/tools/ToolRegistry.java` | Replaced by AiServiceProvider static registry |
| `SwissKit/.../ai/service/JsonBuilder.java` | Replaced by JsonHelper (Gson) |
| `SwissKit/.../ai/service/JsonParser.java` | Replaced by JsonHelper (Gson) |

---

### Task 1: Add Gson dependency and create JsonHelper

**Files:**
- Modify: `SwissKit/pom.xml` (add Gson dependency)
- Create: `SwissKit/src/main/java/fan/summer/ai/util/JsonHelper.java`

- [ ] **Step 1: Add Gson to pom.xml**

Add to `<properties>`:
```xml
<gson.version>2.13.1</gson.version>
```

Add to `<dependencies>`:
```xml
<!-- Gson -->
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>${gson.version}</version>
</dependency>
```

- [ ] **Step 2: Create JsonHelper**

```java
package fan.summer.ai.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public final class JsonHelper {

    private static final Gson GSON = new Gson();

    private JsonHelper() {}

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final Type LIST_TYPE = new TypeToken<List<Object>>() {}.getType();

    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return GSON.fromJson(json, MAP_TYPE);
    }

    public static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return GSON.fromJson(json, LIST_TYPE);
    }

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        return GSON.fromJson(json, Object.class);
    }

    @SuppressWarnings("unchecked")
    public static Object navigate(Map<String, Object> root, String path) {
        Object current = root;
        for (String key : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else if (current instanceof List) {
                try {
                    int idx = Integer.parseInt(key);
                    List<Object> list = (List<Object>) current;
                    current = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    public static String getString(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof Map ? (Map<String, Object>) val : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String path) {
        Object val = navigate(map, path);
        return val instanceof List ? (List<Object>) val : null;
    }
}
```

- [ ] **Step 3: Build verify**

Run: `mvn compile -f SwissKit/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add SwissKit/pom.xml SwissKit/src/main/java/fan/summer/ai/util/JsonHelper.java
git commit -m "♻️ feat(ai): add Gson dependency and JsonHelper utility"
```

---

### Task 2: Move tool registry to AiServiceProvider and delete ToolRegistry

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java`
- Delete: `SwissKit/src/main/java/fan/summer/ai/tools/ToolRegistry.java`

- [ ] **Step 1: Add shared tool registry to AiServiceProvider**

Add the import and static fields/methods to `AiServiceProvider`. The full file becomes:

```java
package fan.summer.api.ai;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AiServiceProvider {

    private static volatile AiService instance;
    private static volatile String currentMode = "local";
    private static final List<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();
    private static final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    private AiServiceProvider() {}

    // ── Service management ────────────────────────────────────

    public static void setService(AiService service) {
        instance = service;
    }

    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }

    public static synchronized void switchMode(String mode, AiService newService) {
        if (instance != null) {
            try {
                instance.unloadModel();
            } catch (Exception e) {
                Logger.getLogger(AiServiceProvider.class.getName())
                    .log(Level.WARNING, "Failed to unload previous AI service", e);
            }
        }
        currentMode = mode;
        instance = newService;
        notifyStateChanged();
    }

    // ── State change listeners ────────────────────────────────

    public static void addOnStateChangeListener(Runnable listener) {
        stateChangeListeners.add(listener);
    }

    public static void removeOnStateChangeListener(Runnable listener) {
        stateChangeListeners.remove(listener);
    }

    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }

    // ── Mode ──────────────────────────────────────────────────

    public static String getCurrentMode() {
        return currentMode;
    }

    public static void setCurrentMode(String mode) {
        currentMode = mode;
    }

    // ── Shared tool registry ──────────────────────────────────

    public static void registerTool(AiTool tool) {
        tools.put(tool.getName(), tool);
    }

    public static void unregisterTool(String name) {
        tools.remove(name);
    }

    public static List<AiTool> getTools() {
        return List.copyOf(tools.values());
    }

    public static boolean hasTools() {
        return !tools.isEmpty();
    }

    public static AiTool getTool(String name) {
        return tools.get(name);
    }
}
```

- [ ] **Step 2: Delete ToolRegistry.java**

Delete file: `SwissKit/src/main/java/fan/summer/ai/tools/ToolRegistry.java`

This will cause compilation errors in the three service implementations — those are fixed in Tasks 4–6.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "♻️ refactor(ai): move tool registry to AiServiceProvider, delete ToolRegistry"
```

---

### Task 3: Create ToolExecutor and ToolSchemaBuilder

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java`
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java`

- [ ] **Step 1: Create ToolExecutor**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private ToolExecutor() {}

    public static AiToolResult execute(String toolName, Map<String, Object> arguments) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
            return AiToolResult.error("Tool not found: " + toolName);
        }
        try {
            log.debug("Executing tool: name={}, arguments={}", toolName, arguments);
            return tool.execute(arguments);
        } catch (Exception e) {
            log.error("Tool execution error: tool={}, error={}", toolName, e.getMessage());
            return AiToolResult.error("Tool execution error: " + e.getMessage());
        }
    }

    public static void executeAndFeed(List<AiToolCall> toolCalls,
                                      List<AiChatMessage> history,
                                      AiStreamCallback callback) {
        for (AiToolCall tc : toolCalls) {
            Platform.runLater(() -> callback.onToolCall(tc));
            log.info("Executing tool: name={}, args={}", tc.name(), tc.arguments());
            AiToolResult result = execute(tc.name(), tc.arguments());
            log.info("Tool result: success={}", result.success());
            Platform.runLater(() -> callback.onToolResult(tc.id(), result));
            history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
        }
    }
}
```

- [ ] **Step 2: Create ToolSchemaBuilder**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;

import java.util.*;

public class ToolSchemaBuilder {

    private ToolSchemaBuilder() {}

    public static List<Map<String, Object>> buildOpenAiTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", tool.getDescription());
            fn.put("parameters", buildJsonSchema(tool.getParameters()));
            result.add(Map.of("type", "function", "function", fn));
        }
        return result;
    }

    public static List<Map<String, Object>> buildAnthropicTools(List<AiTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiTool tool : tools) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("input_schema", buildJsonSchema(tool.getParameters()));
            result.add(t);
        }
        return result;
    }

    public static String buildPromptDefinitions(List<AiTool> tools) {
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("# Tools\n\n");
        sb.append("You can call tools by outputting a JSON object with \"name\" and \"arguments\" fields.\n");
        sb.append("Format:\n```\n{\"name\": \"<tool_name>\", \"arguments\": {<param>: <value>}}\n```\n\n");
        sb.append("IMPORTANT: When a user's request requires using a tool, you MUST call the tool directly. ");
        sb.append("Do NOT describe how to use it or ask for confirmation. Just call it.\n\n");
        sb.append("Example — user says \"analyze this Excel file /path/to/file.xlsx\":\n");
        sb.append("{\"name\": \"excel_analyze\", \"arguments\": {\"filePath\": \"/path/to/file.xlsx\"}}\n\n");
        sb.append("Available tools:\n\n");

        for (AiTool tool : tools) {
            sb.append("### ").append(tool.getName()).append("\n");
            sb.append(tool.getDescription()).append("\n");
            List<AiToolParam> params = tool.getParameters();
            if (!params.isEmpty()) {
                sb.append("Parameters:\n");
                for (AiToolParam p : params) {
                    sb.append("- ").append(p.name()).append(" (").append(p.type()).append(")");
                    if (p.required()) sb.append(" [required]");
                    sb.append(": ").append(p.description()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("After receiving a tool result, you may call another tool or provide a final answer.\n");
        return sb.toString();
    }

    private static Map<String, Object> buildJsonSchema(List<AiToolParam> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (AiToolParam p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type());
            prop.put("description", p.description());
            properties.put(p.name(), prop);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java
git commit -m "♻️ feat(ai): add ToolExecutor and ToolSchemaBuilder utilities"
```

---

### Task 4: Refactor AiServiceImpl

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java`

- [ ] **Step 1: Rewrite AiServiceImpl**

Replace the entire file. Key changes:
- Remove `private final ToolRegistry toolRegistry`
- Replace `toolRegistry.buildToolDefinitions()` → `ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools())`
- Replace `ToolCallParser.parse()` (stays the same for local backend)
- Replace `executeAndFeedToolResults()` → `ToolExecutor.executeAndFeed()`
- Replace `toolRegistry.hasTools()` → `AiServiceProvider.hasTools()`
- Replace `registerTool/unregisterTool/getTools` → delegate to `AiServiceProvider`

```java
package fan.summer.ai.service;

import fan.summer.ai.inference.LlamaRunner;
import fan.summer.ai.inference.StopDetector;
import fan.summer.ai.model.ChatTemplate;
import fan.summer.ai.model.GGUFReader;
import fan.summer.ai.nativejni.GenerateCallback;
import fan.summer.ai.nativejni.GenerateParams;
import fan.summer.ai.nativejni.LlamaContext;
import fan.summer.ai.nativejni.ModelParams;
import fan.summer.ai.nativejni.NativeLoader;
import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private enum Backend { NATIVE, JAVA }

    private final Backend backend;
    private final LlamaRunner javaRunner;
    private LlamaContext nativeContext;
    private ChatTemplate nativeChatTemplate;
    private volatile String loadedModelPath;

    public AiServiceImpl() {
        NativeLoader.load();
        if (NativeLoader.isLoaded()) {
            backend = Backend.NATIVE;
            javaRunner = null;
            log.info("AI backend: native (llama.cpp JNI)");
        } else {
            backend = Backend.JAVA;
            javaRunner = new LlamaRunner();
            log.info("AI backend: pure Java (fallback)");
        }
    }

    // ── Model management ──────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        try {
            loadedModelPath = modelPath.toString();
            log.info("Loading AI model [{}]: {}", backend, modelPath);

            if (backend == Backend.NATIVE) {
                if (nativeContext != null) nativeContext.close();
                ModelParams params = new ModelParams()
                    .modelPath(modelPath.toString())
                    .ctxLength(4096)
                    .threads(Runtime.getRuntime().availableProcessors());
                nativeContext = new LlamaContext(params);

                try {
                    Map<String, Object> meta = GGUFReader.loadMetadata(modelPath);
                    String rawTemplate = meta.get("tokenizer.chat_template") instanceof String s ? s : "";
                    nativeChatTemplate = new ChatTemplate(rawTemplate);
                    log.info("Native chat template: {} (raw len={})",
                             nativeChatTemplate.getType(), rawTemplate.length());
                } catch (Exception e) {
                    log.warn("Failed to read chat template metadata, defaulting to ChatML: {}", e.getMessage());
                    nativeChatTemplate = new ChatTemplate("");
                }
            } else {
                javaRunner.load(modelPath.toString());
            }

            log.info("AI model loaded successfully [{}]", backend);
        } catch (Exception e) {
            throw new AiServiceException("Failed to load model: " + e.getMessage(), e);
        }
    }

    @Override
    public void unloadModel() {
        if (backend == Backend.NATIVE && nativeContext != null) {
            nativeContext.close();
            nativeContext = null;
            nativeChatTemplate = null;
        } else if (javaRunner != null) {
            javaRunner.unload();
        }
        loadedModelPath = null;
    }

    @Override public boolean isReady() {
        if (backend == Backend.NATIVE) return nativeContext != null;
        return javaRunner != null && javaRunner.isReady();
    }

    @Override
    public Optional<String> getModelName() {
        if (backend == Backend.NATIVE) {
            return Optional.ofNullable(loadedModelPath)
                .map(p -> p.substring(p.lastIndexOf('/') + 1));
        }
        return Optional.ofNullable(javaRunner != null ? javaRunner.getModelName() : null);
    }

    @Override public long getMemoryUsage() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ── Chat ──────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("No model loaded"));
            return;
        }

        if (backend == Backend.NATIVE) {
            chatNative(history, temperature, topP, maxTokens, callback);
        } else {
            chatJava(history, temperature, topP, maxTokens, callback);
        }
    }

    // ── Native backend chat ───────────────────────────────────

    private void chatNative(List<AiChatMessage> history, float temperature, float topP,
                            int maxTokens, AiStreamCallback callback) {
        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                String prompt = buildNativePrompt(history, systemPrompt);
                AtomicBoolean hadToolCall = new AtomicBoolean(false);
                generateNativeWithToolLoop(prompt, temperature, topP, maxTokens,
                                           history, callback, 0, hadToolCall);
            } catch (Exception e) {
                log.error("Native generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateNativeWithToolLoop(String prompt, float temperature, float topP,
                                             int maxTokens, List<AiChatMessage> history,
                                             AiStreamCallback callback, int round,
                                             AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS || nativeContext == null) return;

        GenerateParams genParams = new GenerateParams()
            .temperature(temperature).topP(topP).maxTokens(maxTokens);

        StringBuilder response = new StringBuilder();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicInteger tokenCount = new AtomicInteger(0);
        long[] firstTokenNanos = {0L};
        long genStartNanos = System.nanoTime();

        nativeContext.generate(prompt, genParams, new GenerateCallback() {
            @Override
            public boolean onToken(String tokenText) {
                if (stopped.get()) return false;
                if (firstTokenNanos[0] == 0L) firstTokenNanos[0] = System.nanoTime();
                tokenCount.incrementAndGet();

                int prevLen = response.length();
                response.append(tokenText);
                int stopIdx = StopDetector.findStop(response);
                if (stopIdx >= 0) {
                    response.setLength(stopIdx);
                    int safeLen = stopIdx - prevLen;
                    if (safeLen > 0) {
                        String safe = tokenText.substring(0, Math.min(tokenText.length(), safeLen));
                        Platform.runLater(() -> callback.onToken(safe));
                    }
                    stopped.set(true);
                    return false;
                }
                Platform.runLater(() -> callback.onToken(tokenText));
                return true;
            }

            @Override
            public void onDone(String fullText) {
                String finalText = response.toString();
                int n = tokenCount.get();
                long baseNanos = firstTokenNanos[0] != 0L ? firstTokenNanos[0] : genStartNanos;
                long elapsedMs = (System.nanoTime() - baseNanos) / 1_000_000;
                double tokPerSec = (n > 0 && elapsedMs > 0) ? n * 1000.0 / elapsedMs : 0;

                List<AiToolCall> toolCalls = ToolCallParser.parse(finalText);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    history.add(AiChatMessage.assistantWithTools("", toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    String newPrompt = buildNativePrompt(history, buildSystemPrompt());
                    generateNativeWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                               history, callback, round + 1, hadToolCall);
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(finalText) : finalText;
                    Platform.runLater(() -> callback.onComplete(clean, n, tokPerSec));
                }
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> callback.onError(new RuntimeException(message)));
            }
        });
    }

    // ── Java backend chat ─────────────────────────────────────

    private void chatJava(List<AiChatMessage> history, float temperature, float topP,
                          int maxTokens, AiStreamCallback callback) {
        if (javaRunner.isGenerating()) {
            callback.onError(new AiServiceException("Generation already in progress"));
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                String systemPrompt = buildSystemPrompt();
                javaRunner.resetCache();
                String prompt = javaRunner.buildPrompt(history, systemPrompt);

                AtomicBoolean hadToolCall = new AtomicBoolean(false);
                generateJavaWithToolLoop(prompt, temperature, topP, maxTokens,
                                         history, callback, 0, hadToolCall);
            } catch (Exception e) {
                log.error("Java generation error", e);
                Platform.runLater(() -> callback.onError(e));
            }
        });
    }

    private void generateJavaWithToolLoop(String prompt, float temperature, float topP, int maxTokens,
                                           List<AiChatMessage> history, AiStreamCallback callback,
                                           int round, AtomicBoolean hadToolCall) {
        if (round >= MAX_TOOL_ROUNDS) return;

        StringBuilder response = new StringBuilder();
        javaRunner.generate(prompt, temperature, topP, maxTokens, new LlamaRunner.TokenCallback() {
            @Override
            public void onToken(String fragment) {
                response.append(fragment);
                Platform.runLater(() -> callback.onToken(fragment));
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                List<AiToolCall> toolCalls = ToolCallParser.parse(fullResponse);
                if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
                    hadToolCall.set(true);
                    history.add(AiChatMessage.assistantWithTools("", toolCalls));
                    ToolExecutor.executeAndFeed(toolCalls, history, callback);
                    try {
                        String newPrompt = javaRunner.buildPrompt(history, buildSystemPrompt());
                        javaRunner.resetCache();
                        generateJavaWithToolLoop(newPrompt, temperature, topP, maxTokens,
                                                 history, callback, round + 1, hadToolCall);
                    } catch (Exception e) {
                        Platform.runLater(() -> callback.onError(e));
                    }
                } else {
                    String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse) : fullResponse;
                    Platform.runLater(() -> callback.onComplete(clean, tokensGenerated, tokensPerSecond));
                }
            }
        });
    }

    // ── Prompt building ───────────────────────────────────────

    private String buildSystemPrompt() {
        String base = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (toolDefs.isEmpty()) return base;
        return base + "\n\n" + toolDefs;
    }

    private String buildNativePrompt(List<AiChatMessage> history, String systemPrompt) {
        ChatTemplate template = nativeChatTemplate != null ? nativeChatTemplate : new ChatTemplate("");
        return template.buildPrompt(history, systemPrompt);
    }

    // ── Lifecycle ─────────────────────────────────────────────

    @Override public void cancelGeneration() {
        if (javaRunner != null) javaRunner.cancel();
    }

    @Override public boolean isGenerating() {
        if (backend == Backend.NATIVE) return false;
        return javaRunner != null && javaRunner.isGenerating();
    }

    // ── Tool management (delegate to AiServiceProvider) ───────

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java
git commit -m "♻️ refactor(ai): AiServiceImpl uses shared registry and utilities"
```

---

### Task 5: Refactor OpenAiService

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java`

- [ ] **Step 1: Rewrite OpenAiService**

Key changes:
- Remove `private final ToolRegistry toolRegistry`
- Replace `JsonBuilder.toJson()` → `JsonHelper.toJson()`
- Replace `JsonParser.parseObject()` → `JsonHelper.parseObject()`
- Replace `JsonParser.getMap()` → `JsonHelper.getMap()`
- Replace `toolRegistry.buildToolDefinitions()` → `ToolSchemaBuilder.buildPromptDefinitions()`
- Replace `toolRegistry.hasTools()` → `AiServiceProvider.hasTools()`
- Replace `buildOpenAiTools()` → `ToolSchemaBuilder.buildOpenAiTools()`
- Replace inline tool execution → `ToolExecutor.executeAndFeed()`
- Replace `registerTool/unregisterTool/getTools` → `AiServiceProvider`

```java
package fan.summer.ai.service;

import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private volatile boolean generating = false;
    private volatile InputStream activeStream;

    private String endpoint;
    private String apiKey;
    private String modelName;

    public OpenAiService() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for OpenAI mode");
    }

    @Override public void unloadModel() {}

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating; }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("OpenAI service not configured"));
            return;
        }
        generating = true;
        Thread.ofVirtual().start(() -> {
            try {
                chatWithToolLoop(history, temperature, topP, maxTokens, callback, 0, new AtomicBoolean(false));
            } catch (Exception e) {
                log.error("OpenAI generation error", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating = false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, float topP,
                                  int maxTokens, AiStreamCallback callback, int round,
                                  AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        List<Object> messages = buildMessages(history, systemPrompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("messages", messages);
        if (AiServiceProvider.hasTools()) {
            body.put("tools", ToolSchemaBuilder.buildOpenAiTools(AiServiceProvider.getTools()));
        }

        String jsonBody = JsonHelper.toJson(body);
        String url = endpoint + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<InputStream> resp = sendWithRetry(request);

        if (resp.statusCode() != 200) {
            String errBody;
            try (InputStream is = resp.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new AiServiceException("OpenAI API error (HTTP " + resp.statusCode() + "): " + errBody);
        }

        activeStream = resp.body();
        StringBuilder fullResponse = new StringBuilder();
        Map<Integer, StringBuilder> toolCallArgs = new LinkedHashMap<>();
        Map<Integer, String> toolCallNames = new LinkedHashMap<>();
        Map<Integer, String> toolCallIds = new LinkedHashMap<>();
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isEmpty()) continue;

                Map<String, Object> chunk;
                try {
                    chunk = JsonHelper.parseObject(data);
                } catch (Exception e) {
                    log.warn("Malformed SSE chunk, skipping: {}", e.getMessage());
                    continue;
                }
                Map<String, Object> delta = JsonHelper.getMap(chunk, "choices.0.delta");
                if (delta == null) continue;

                String content = (String) delta.get("content");
                if (content != null) {
                    fullResponse.append(content);
                    tokenCount[0]++;
                    String text = content;
                    Platform.runLater(() -> callback.onToken(text));
                }

                List<Object> toolCallsDelta = (List<Object>) delta.get("tool_calls");
                if (toolCallsDelta != null) {
                    for (Object tcObj : toolCallsDelta) {
                        if (!(tcObj instanceof Map)) continue;
                        Map<String, Object> tc = (Map<String, Object>) tcObj;
                        int idx = ((Number) tc.get("index")).intValue();
                        if (tc.containsKey("id")) toolCallIds.put(idx, (String) tc.get("id"));
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        if (fn != null) {
                            if (fn.containsKey("name")) toolCallNames.put(idx, (String) fn.get("name"));
                            if (fn.containsKey("arguments")) {
                                toolCallArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                    .append(fn.get("arguments"));
                            }
                        }
                    }
                }
            }
        }

        List<AiToolCall> toolCalls = new ArrayList<>();
        for (int idx : toolCallArgs.keySet()) {
            String argsJson = toolCallArgs.get(idx).toString();
            Map<String, Object> args;
            try {
                args = JsonHelper.parseObject(argsJson);
                if (args == null) args = Map.of();
            } catch (Exception e) {
                args = Map.of("raw", argsJson);
            }
            toolCalls.add(new AiToolCall(
                toolCallIds.getOrDefault(idx, "tc_" + idx),
                toolCallNames.getOrDefault(idx, "unknown"),
                args
            ));
        }

        long elapsed = System.nanoTime() - startTime;
        double tokPerSec = tokenCount[0] > 0 ? tokenCount[0] * 1_000_000_000.0 / elapsed : 0;

        if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithTools(fullResponse.toString(), toolCalls));
            ToolExecutor.executeAndFeed(toolCalls, history, callback);
            chatWithToolLoop(history, temperature, topP, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            String finalClean = clean;
            int count = tokenCount[0];
            Platform.runLater(() -> callback.onComplete(finalClean, count, tokPerSec));
        }
    }

    private List<Object> buildMessages(List<AiChatMessage> history, String systemPrompt) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.TOOL) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", msg.toolCallId() != null ? msg.toolCallId() : "");
                m.put("content", msg.content() != null ? msg.content() : "");
                messages.add(m);
            } else if (msg.hasToolCalls()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.content() != null ? msg.content() : "");
                List<Object> tcList = new ArrayList<>();
                for (AiToolCall tc : msg.toolCalls()) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", JsonHelper.toJson(tc.arguments()));
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", tc.id());
                    toolCall.put("type", "function");
                    toolCall.put("function", fn);
                    tcList.add(toolCall);
                }
                m.put("tool_calls", tcList);
                messages.add(m);
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    @Override public void cancelGeneration() {
        generating = false;
        InputStream stream = activeStream;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest request) throws Exception {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Request timed out, retrying once: {}", e.getMessage());
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    public String testConnection() {
        try {
            String url = endpoint + "/v1/chat/completions";
            String body = JsonHelper.toJson(Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", "Hi")),
                "max_tokens", 5
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java
git commit -m "♻️ refactor(ai): OpenAiService uses shared registry, Gson, and utilities"
```

---

### Task 6: Refactor AnthropicService

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java`

- [ ] **Step 1: Rewrite AnthropicService**

Same pattern as OpenAiService — remove private ToolRegistry, use shared components + Gson.

```java
package fan.summer.ai.service;

import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolExecutor;
import fan.summer.ai.tools.ToolSchemaBuilder;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnthropicService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private volatile boolean generating = false;
    private volatile InputStream activeStream;

    private String endpoint;
    private String apiKey;
    private String modelName;

    public AnthropicService() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for Anthropic mode");
    }

    @Override public void unloadModel() {}

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating; }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            callback.onError(new AiServiceException("Anthropic service not configured"));
            return;
        }
        generating = true;
        Thread.ofVirtual().start(() -> {
            try {
                chatWithToolLoop(history, temperature, topP, maxTokens, callback, 0, new AtomicBoolean(false));
            } catch (Exception e) {
                log.error("Anthropic generation error", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating = false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, float topP,
                                  int maxTokens, AiStreamCallback callback, int round,
                                  AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = ToolSchemaBuilder.buildPromptDefinitions(AiServiceProvider.getTools());
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        List<Object> messages = buildAnthropicMessages(history);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("stream", true);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        if (AiServiceProvider.hasTools()) {
            body.put("tools", ToolSchemaBuilder.buildAnthropicTools(AiServiceProvider.getTools()));
        }

        String jsonBody = JsonHelper.toJson(body);
        String url = endpoint + "/v1/messages";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<InputStream> resp = sendWithRetry(request);

        if (resp.statusCode() != 200) {
            String errBody;
            try (InputStream is = resp.body()) {
                errBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new AiServiceException("Anthropic API error (HTTP " + resp.statusCode() + "): " + errBody);
        }

        activeStream = resp.body();
        StringBuilder fullResponse = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        StringBuilder currentToolArgs = new StringBuilder();
        String[] currentToolName = {null};
        String[] currentToolId = {null};
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String eventType = "";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                    continue;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.isEmpty()) continue;

                Map<String, Object> chunk;
                try {
                    chunk = JsonHelper.parseObject(data);
                } catch (Exception e) {
                    log.warn("Malformed SSE chunk, skipping: {}", e.getMessage());
                    continue;
                }
                String type = chunk.containsKey("type") ? String.valueOf(chunk.get("type")) : eventType;

                switch (type) {
                    case "content_block_delta" -> {
                        Map<String, Object> delta = (Map<String, Object>) chunk.get("delta");
                        if (delta == null) break;
                        if (delta.containsKey("text")) {
                            String text = (String) delta.get("text");
                            fullResponse.append(text);
                            tokenCount[0]++;
                            Platform.runLater(() -> callback.onToken(text));
                        } else if (delta.containsKey("partial_json")) {
                            currentToolArgs.append(delta.get("partial_json"));
                        }
                    }
                    case "content_block_start" -> {
                        Map<String, Object> contentBlock = (Map<String, Object>) chunk.get("content_block");
                        if (contentBlock != null && "tool_use".equals(contentBlock.get("type"))) {
                            currentToolName[0] = (String) contentBlock.get("name");
                            currentToolId[0] = (String) contentBlock.get("id");
                            currentToolArgs.setLength(0);
                        }
                    }
                    case "content_block_stop" -> {
                        if (currentToolName[0] != null && currentToolId[0] != null) {
                            Map<String, Object> args;
                            try {
                                Object parsed = JsonHelper.parse(currentToolArgs.toString());
                                args = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of("raw", currentToolArgs.toString());
                            } catch (Exception e) {
                                args = Map.of("raw", currentToolArgs.toString());
                            }
                            toolCalls.add(new AiToolCall(currentToolId[0], currentToolName[0], args));
                            currentToolName[0] = null;
                            currentToolId[0] = null;
                        }
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - startTime;
        double tokPerSec = tokenCount[0] > 0 ? tokenCount[0] * 1_000_000_000.0 / elapsed : 0;

        if (!toolCalls.isEmpty() && AiServiceProvider.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithTools(fullResponse.toString(), toolCalls));
            ToolExecutor.executeAndFeed(toolCalls, history, callback);
            chatWithToolLoop(history, temperature, topP, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            String finalClean = clean;
            int count = tokenCount[0];
            Platform.runLater(() -> callback.onComplete(finalClean, count, tokPerSec));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> buildAnthropicMessages(List<AiChatMessage> history) {
        List<Object> messages = new ArrayList<>();
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.SYSTEM) continue;
            if (msg.role() == AiChatMessage.Role.TOOL) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "tool_result");
                content.put("tool_use_id", msg.toolCallId() != null ? msg.toolCallId() : "");
                content.put("content", msg.content() != null ? msg.content() : "");
                messages.add(Map.of("role", "user", "content", List.of(content)));
            } else if (msg.hasToolCalls()) {
                List<Object> contentBlocks = new ArrayList<>();
                if (msg.content() != null && !msg.content().isEmpty()) {
                    contentBlocks.add(Map.of("type", "text", "text", msg.content()));
                }
                for (AiToolCall tc : msg.toolCalls()) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", tc.id());
                    toolUse.put("name", tc.name());
                    toolUse.put("input", tc.arguments());
                    contentBlocks.add(toolUse);
                }
                messages.add(Map.of("role", "assistant", "content", contentBlocks));
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    @Override public void cancelGeneration() {
        generating = false;
        InputStream stream = activeStream;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    private HttpResponse<InputStream> sendWithRetry(HttpRequest request) throws Exception {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Request timed out, retrying once: {}", e.getMessage());
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
    }

    public String testConnection() {
        try {
            String url = endpoint + "/v1/messages";
            String body = JsonHelper.toJson(Map.of(
                "model", modelName,
                "max_tokens", 5,
                "messages", List.of(Map.of("role", "user", "content", "Hi"))
            ));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java
git commit -m "♻️ refactor(ai): AnthropicService uses shared registry, Gson, and utilities"
```

---

### Task 7: Refactor ToolCallParser to use Gson

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java`

- [ ] **Step 1: Rewrite ToolCallParser**

Replace the hand-rolled `parseSimpleJson()` with `JsonHelper.parseObject()`. Keep the regex patterns for Qwen and generic tool call detection.

```java
package fan.summer.ai.tools;

import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.AiToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);

    private static final Pattern QWEN_TOOL_CALL = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?\"name\"\\s*:\\s*\"(.*?)\".*?\"arguments\"\\s*:\\s*(\\{.*?}).*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );

    private static final Pattern GENERIC_TOOL_CALL = Pattern.compile(
        "(?:```(?:json)?\\s*)?\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}(?:\\s*```)?",
        Pattern.DOTALL
    );

    public static List<AiToolCall> parse(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AiToolCall> calls = new ArrayList<>();

        Matcher m = QWEN_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        if (!calls.isEmpty()) {
            log.debug("Parsed {} tool call(s) via Qwen pattern", calls.size());
            return calls;
        }

        m = GENERIC_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        log.debug("Parsed {} tool call(s) via generic pattern", calls.size());
        return calls;
    }

    public static boolean containsToolCallPattern(String text) {
        if (text == null) return false;
        return text.contains("<|tool_call_begin|>")
            || text.contains("\"name\"") && text.contains("\"arguments\"");
    }

    public static String stripToolCalls(String text) {
        if (text == null) return "";
        String result = QWEN_TOOL_CALL.matcher(text).replaceAll("");
        log.debug("stripToolCalls: originalLength={}, resultLength={}", text.length(), result.length());
        return result.trim();
    }

    @SuppressWarnings("unchecked")
    private static AiToolCall buildCall(String name, String argsJson) {
        Map<String, Object> args;
        try {
            Map<String, Object> parsed = JsonHelper.parseObject(argsJson);
            args = parsed != null ? parsed : Map.of("_raw", argsJson);
        } catch (Exception e) {
            args = Map.of("_raw", argsJson);
        }
        return AiToolCall.of(name.trim(), args);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java
git commit -m "♻️ refactor(ai): ToolCallParser uses Gson for JSON parsing"
```

---

### Task 8: Refactor Excel tools to use Gson

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java`

- [ ] **Step 1: Rewrite ExcelAnalyzeTool.execute()**

Replace the entire `execute()` method and delete the `jsonEscape()` method. Use `JsonHelper.toJson()` with a result map:

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ExcelAnalyzeTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelAnalyzeTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelAnalyzeTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_analyze"; }

    @Override public String getDescription() {
        return "Analyze an Excel file and return all sheet names, row counts, and column headers. " +
               "Call this first before configuring the split. " +
               "Argument: filePath (string, required) — absolute path to the .xlsx/.xls file.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(AiToolParam.of("filePath", "string", "Absolute path to the Excel file", true));
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String filePathStr = (String) args.get("filePath");
        if (filePathStr == null || filePathStr.isBlank()) {
            return AiToolResult.error("filePath is required");
        }
        Path filePath = Paths.get(filePathStr.trim());
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            return AiToolResult.error("File not found or not readable: " + filePathStr);
        }

        SplitConfig config = plugin.getSharedSplitConfig();
        config.sourceFile = filePath;

        try {
            Map<String, Map<Integer, String>> analysisResult =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return ExcelSplitter.analyze(filePath);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).get();

            config.analysisResult = analysisResult;

            List<Map<String, Object>> sheets = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, String>> e : analysisResult.entrySet()) {
                Map<String, Object> sheet = new LinkedHashMap<>();
                sheet.put("name", e.getKey());
                sheet.put("headerCount", e.getValue().size());
                sheet.put("headers", new ArrayList<>(e.getValue().values()));
                sheets.add(sheet);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("sheets", sheets);
            result.put("totalSheets", analysisResult.size());
            result.put("sourceFile", filePath.getFileName().toString());

            log.info("excel_analyze success: {} sheets found", analysisResult.size());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("excel_analyze failed: {}", e.getMessage());
            return AiToolResult.error("Analysis failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Rewrite ExcelConfigureTool.execute()**

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

@SuppressWarnings("unchecked")
public class ExcelConfigureTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelConfigureTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelConfigureTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_configure"; }

    @Override public String getDescription() {
        return "Configure the Excel split mode and parameters. " +
               "Must be called after excel_analyze. " +
               "Modes: BY_SHEET (one file per sheet), BY_COLUMN (group by column value), " +
               "COMPLEX (DB-backed multi-config). " +
               "Required args: mode (string). " +
               "BY_SHEET optional: sheets (string[]). " +
               "BY_COLUMN required: splitSheet (string), splitColumn (string). " +
               "COMPLEX required: taskId (string, UUID).";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("mode", "string", "Split mode: BY_SHEET, BY_COLUMN, or COMPLEX", true),
            AiToolParam.of("sheets", "string[]", "Sheet names to export (BY_SHEET mode)", false),
            AiToolParam.of("splitSheet", "string", "Sheet name to split on (BY_COLUMN mode)", false),
            AiToolParam.of("splitColumn", "string", "Column header name to split by (BY_COLUMN mode)", false),
            AiToolParam.of("taskId", "string", "Complex split task ID from DB (COMPLEX mode)", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        if (config.analysisResult == null) {
            return AiToolResult.error("No analysis result. Call excel_analyze first.");
        }

        String modeStr = (String) args.get("mode");
        if (modeStr == null || modeStr.isBlank()) {
            return AiToolResult.error("mode is required (BY_SHEET, BY_COLUMN, or COMPLEX)");
        }

        try {
            SplitConfig.SplitMode mode = SplitConfig.SplitMode.valueOf(modeStr.toUpperCase());
            config.mode = mode;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", true);
            result.put("mode", mode.name());

            switch (mode) {
                case BY_SHEET -> {
                    List<String> sheets = (List<String>) args.get("sheets");
                    if (sheets != null && !sheets.isEmpty()) {
                        config.selectedSheets = new ArrayList<>(sheets);
                    } else {
                        config.selectedSheets = new ArrayList<>(config.analysisResult.keySet());
                    }
                    result.put("selectedSheets", config.selectedSheets);
                    result.put("summary", "Will export " + config.selectedSheets.size() + " sheet(s) as separate files");
                }
                case BY_COLUMN -> {
                    String splitSheet = (String) args.get("splitSheet");
                    String splitColumn = (String) args.get("splitColumn");
                    if (splitSheet == null || splitColumn == null) {
                        return AiToolResult.error("BY_COLUMN mode requires splitSheet and splitColumn");
                    }
                    Map<Integer, String> headers = config.analysisResult.get(splitSheet);
                    if (headers == null) {
                        return AiToolResult.error("Sheet not found: " + splitSheet);
                    }
                    Integer colIdx = null;
                    String foundCol = null;
                    for (Map.Entry<Integer, String> e : headers.entrySet()) {
                        if (e.getValue().equalsIgnoreCase(splitColumn.trim())) {
                            colIdx = e.getKey();
                            foundCol = e.getValue();
                            break;
                        }
                    }
                    if (colIdx == null) {
                        return AiToolResult.error("Column not found: " + splitColumn + ". Available columns: " + headers.values());
                    }
                    config.splitSheet = splitSheet;
                    config.splitColumn = foundCol;
                    config.splitColumnIndex = colIdx;
                    result.put("splitSheet", splitSheet);
                    result.put("splitColumn", foundCol);
                    result.put("splitColumnIndex", colIdx);
                    result.put("summary", "Will split sheet '" + splitSheet + "' by column '" + foundCol + "' (index " + colIdx + ")");
                }
                case COMPLEX -> {
                    String taskId = (String) args.get("taskId");
                    if (taskId == null || taskId.isBlank()) {
                        return AiToolResult.error("COMPLEX mode requires taskId (UUID string)");
                    }
                    config.complexTaskId = taskId;
                    result.put("taskId", taskId);
                    result.put("summary", "Complex split configured with taskId: " + taskId);
                }
            }

            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (IllegalArgumentException e) {
            return AiToolResult.error("Invalid mode: " + modeStr + ". Use BY_SHEET, BY_COLUMN, or COMPLEX.");
        }
    }
}
```

- [ ] **Step 3: Rewrite ExcelExecuteTool.execute()**

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ExcelExecuteTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelExecuteTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelExecuteTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_execute"; }

    @Override public String getDescription() {
        return "Execute the Excel split operation. " +
               "Must be called after excel_analyze and excel_configure. " +
               "Args: outputDir (string, required) — absolute path to output directory; " +
               "filePrefix (string, optional) — prefix for output filenames.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("outputDir", "string", "Absolute path to output directory", true),
            AiToolParam.of("filePrefix", "string", "Optional prefix for output filenames", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        if (config.analysisResult == null) {
            return AiToolResult.error("No analysis result. Call excel_analyze first.");
        }
        if (config.mode == null) {
            return AiToolResult.error("Split mode not configured. Call excel_configure first.");
        }

        String outputDirStr = (String) args.get("outputDir");
        if (outputDirStr == null || outputDirStr.isBlank()) {
            return AiToolResult.error("outputDir is required");
        }
        Path outputDir = Paths.get(outputDirStr.trim());
        if (!Files.exists(outputDir) || !Files.isDirectory(outputDir)) {
            return AiToolResult.error("Output directory does not exist: " + outputDirStr);
        }
        config.outputDir = outputDir;

        String filePrefix = (String) args.get("filePrefix");
        config.filePrefix = (filePrefix != null) ? filePrefix.trim() : "";

        try {
            ExcelSplitter splitter = new ExcelSplitter(config, (pct, msg) -> {
                log.debug("Split progress: {}% - {}", (int)(pct * 100), msg);
            });

            ExcelSplitter.SplitResult result = CompletableFuture.supplyAsync(() -> {
                try {
                    return splitter.split();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).get();

            List<String> fileNames = new ArrayList<>();
            for (Path f : result.outputFiles()) {
                fileNames.add(f.getFileName().toString());
            }

            Map<String, Object> json = new LinkedHashMap<>();
            json.put("success", true);
            json.put("outputFiles", fileNames);
            json.put("fileCount", result.fileCount());
            json.put("summary", "Created " + result.fileCount() + " output file(s) in " + outputDirStr);

            log.info("excel_execute success: {} files created", result.fileCount());
            return AiToolResult.success(JsonHelper.toJson(json));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("excel_execute failed: {}", cause.getMessage());
            return AiToolResult.error("Split failed: " + cause.getMessage());
        } catch (Exception e) {
            log.error("excel_execute error: {}", e.getMessage());
            return AiToolResult.error("Unexpected error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Rewrite ExcelQueryTool.execute()**

```java
package fan.summer.buildintool.ai;

import fan.summer.api.ai.*;
import fan.summer.buildintool.excelsplitter.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class ExcelQueryTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(ExcelQueryTool.class);
    private final ExcelSplitterPlugin plugin;

    public ExcelQueryTool(ExcelSplitterPlugin plugin) { this.plugin = plugin; }

    @Override public String getName() { return "excel_query"; }

    @Override public String getDescription() {
        return "Query the current Excel split configuration state. " +
               "Returns source file, mode, configured sheets/columns, and output directory. " +
               "No arguments required.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of();
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        SplitConfig config = plugin.getSharedSplitConfig();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceFile", config.sourceFile != null ? config.sourceFile.toString() : null);
        result.put("mode", config.mode != null ? config.mode.name() : null);
        result.put("selectedSheets", config.selectedSheets);
        result.put("splitSheet", config.splitSheet);
        result.put("splitColumn", config.splitColumn);
        result.put("splitColumnIndex", config.splitColumnIndex);
        result.put("complexTaskId", config.complexTaskId);
        result.put("outputDir", config.outputDir != null ? config.outputDir.toString() : null);

        log.debug("excel_query returned state");
        return AiToolResult.success(JsonHelper.toJson(result));
    }
}
```

- [ ] **Step 5: Build verify**

Run: `mvn compile -f SwissKit/pom.xml`
Expected: BUILD SUCCESS (may have errors from remaining references — fix any before proceeding)

- [ ] **Step 6: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelAnalyzeTool.java \
       SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelConfigureTool.java \
       SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelExecuteTool.java \
       SwissKit/src/main/java/fan/summer/buildintool/ai/ExcelQueryTool.java
git commit -m "♻️ refactor(ai): Excel tools use Gson for JSON output"
```

---

### Task 9: Create built-in AI tools

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java`
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java`
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java`
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java`

- [ ] **Step 1: Create BuiltinBase64Tool**

Combines encode and decode into one tool with a `mode` parameter.

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class BuiltinBase64Tool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinBase64Tool.class);

    @Override public String getName() { return "base64"; }

    @Override public String getDescription() {
        return "Encode text to Base64 or decode Base64 back to text. " +
               "Args: text (string, required) — the input text; " +
               "mode (string, required) — \"encode\" or \"decode\".";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to encode or decode", true),
            AiToolParam.of("mode", "string", "Operation: \"encode\" or \"decode\"", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String text = (String) args.get("text");
        String mode = (String) args.get("mode");

        if (text == null || text.isBlank()) return AiToolResult.error("text is required");
        if (mode == null || mode.isBlank()) return AiToolResult.error("mode is required (encode or decode)");

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String output;
            if ("encode".equalsIgnoreCase(mode)) {
                output = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
                result.put("mode", "encode");
            } else if ("decode".equalsIgnoreCase(mode)) {
                output = new String(Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8);
                result.put("mode", "decode");
            } else {
                return AiToolResult.error("Invalid mode: " + mode + ". Use \"encode\" or \"decode\".");
            }
            result.put("success", true);
            result.put("output", output);
            log.debug("base64 {} success, inputLength={}", mode, text.length());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("base64 error: {}", e.getMessage());
            return AiToolResult.error("Base64 error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Create BuiltinHashTool**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class BuiltinHashTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinHashTool.class);

    @Override public String getName() { return "hash_calculate"; }

    @Override public String getDescription() {
        return "Calculate hash digest of text. " +
               "Args: text (string, required) — input text; " +
               "algorithm (string, required) — hash algorithm: MD5, SHA-1, SHA-256, or SHA-512.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to hash", true),
            AiToolParam.of("algorithm", "string", "Hash algorithm: MD5, SHA-1, SHA-256, SHA-512", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String text = (String) args.get("text");
        String algorithm = (String) args.get("algorithm");

        if (text == null) return AiToolResult.error("text is required");
        if (algorithm == null || algorithm.isBlank()) return AiToolResult.error("algorithm is required");

        Set<String> allowed = Set.of("MD5", "SHA-1", "SHA-256", "SHA-512");
        String algoUpper = algorithm.toUpperCase().trim();
        if (!allowed.contains(algoUpper)) {
            return AiToolResult.error("Unsupported algorithm: " + algorithm + ". Allowed: MD5, SHA-1, SHA-256, SHA-512");
        }

        try {
            MessageDigest md = MessageDigest.getInstance(algoUpper);
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("algorithm", algoUpper);
            result.put("hash", hex.toString());
            log.debug("hash_calculate success: algo={}", algoUpper);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("hash_calculate error: {}", e.getMessage());
            return AiToolResult.error("Hash error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Create BuiltinJsonFormatTool**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class BuiltinJsonFormatTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinJsonFormatTool.class);

    @Override public String getName() { return "json_format"; }

    @Override public String getDescription() {
        return "Format or minify a JSON string. " +
               "Args: json (string, required) — the JSON string to format; " +
               "minify (boolean, optional, default false) — if true, minify instead of pretty-print.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("json", "string", "JSON string to format or minify", true),
            AiToolParam.of("minify", "boolean", "If true, produce compact JSON; if false, pretty-print", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String json = (String) args.get("json");
        if (json == null || json.isBlank()) return AiToolResult.error("json is required");

        boolean minify = Boolean.TRUE.equals(args.get("minify"));

        try {
            Object parsed = JsonHelper.parse(json);
            if (parsed == null) return AiToolResult.error("Invalid JSON: null result");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);

            if (minify) {
                result.put("output", JsonHelper.toJson(parsed));
                result.put("mode", "minify");
            } else {
                result.put("output", com.google.gson.GsonBuilder.class
                    .getClassLoader() != null
                    ? new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed)
                    : JsonHelper.toJson(parsed));
                result.put("mode", "pretty-print");
            }

            log.debug("json_format success: minify={}", minify);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("json_format error: {}", e.getMessage());
            return AiToolResult.error("Invalid JSON: " + e.getMessage());
        }
    }
}
```

Wait — the pretty-print logic is ugly. Let me use a proper static Gson instance. Here's the corrected version:

```java
package fan.summer.ai.tools;

import com.google.gson.GsonBuilder;
import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class BuiltinJsonFormatTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinJsonFormatTool.class);

    @Override public String getName() { return "json_format"; }

    @Override public String getDescription() {
        return "Format or minify a JSON string. " +
               "Args: json (string, required) — the JSON string to format; " +
               "minify (boolean, optional, default false) — if true, minify instead of pretty-print.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("json", "string", "JSON string to format or minify", true),
            AiToolParam.of("minify", "boolean", "If true, produce compact JSON; if false, pretty-print", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String json = (String) args.get("json");
        if (json == null || json.isBlank()) return AiToolResult.error("json is required");

        boolean minify = Boolean.TRUE.equals(args.get("minify"));

        try {
            Object parsed = JsonHelper.parse(json);
            if (parsed == null) return AiToolResult.error("Invalid JSON: null result");

            String output;
            if (minify) {
                output = JsonHelper.toJson(parsed);
            } else {
                output = new GsonBuilder().setPrettyPrinting().create().toJson(parsed);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("output", output);
            result.put("mode", minify ? "minify" : "pretty-print");

            log.debug("json_format success: minify={}", minify);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("json_format error: {}", e.getMessage());
            return AiToolResult.error("Invalid JSON: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Create BuiltinColorConvertTool**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class BuiltinColorConvertTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinColorConvertTool.class);

    @Override public String getName() { return "color_convert"; }

    @Override public String getDescription() {
        return "Convert a color between HEX, RGB, and HSL formats. " +
               "Args: color (string, required) — color value (e.g. \"#5b8cf7\" or \"91,140,247\"); " +
               "from (string, required) — source format: HEX, RGB, or HSL; " +
               "to (string, required) — target format: HEX, RGB, or HSL.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("color", "string", "Color value to convert", true),
            AiToolParam.of("from", "string", "Source format: HEX, RGB, or HSL", true),
            AiToolParam.of("to", "string", "Target format: HEX, RGB, or HSL", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String color = (String) args.get("color");
        String from = (String) args.get("from");
        String to = (String) args.get("to");

        if (color == null || color.isBlank()) return AiToolResult.error("color is required");
        if (from == null || to == null) return AiToolResult.error("from and to formats are required");

        try {
            int r, g, b;

            from = from.toUpperCase().trim();
            switch (from) {
                case "HEX" -> {
                    String hex = color.trim();
                    if (!hex.startsWith("#")) hex = "#" + hex;
                    java.awt.Color c = java.awt.Color.decode(hex);
                    r = c.getRed(); g = c.getGreen(); b = c.getBlue();
                }
                case "RGB" -> {
                    String[] parts = color.split("[,\\s]+");
                    if (parts.length < 3) return AiToolResult.error("RGB format: \"R, G, B\" (e.g. \"91, 140, 247\")");
                    r = Integer.parseInt(parts[0].trim());
                    g = Integer.parseInt(parts[1].trim());
                    b = Integer.parseInt(parts[2].trim());
                }
                default -> {
                    return AiToolResult.error("Unsupported source format: " + from + ". Use HEX, RGB, or HSL.");
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("input", Map.of("color", color, "format", from));

            to = to.toUpperCase().trim();
            switch (to) {
                case "HEX" -> {
                    result.put("output", String.format("#%02x%02x%02x", r, g, b));
                    result.put("targetFormat", "HEX");
                }
                case "RGB" -> {
                    result.put("output", String.format("%d, %d, %d", r, g, b));
                    result.put("targetFormat", "RGB");
                }
                case "HSL" -> {
                    float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                    result.put("output", String.format("%.0f°, %.0f%%, %.0f%%",
                        hsb[0] * 360, hsb[1] * 100, hsb[2] * 100));
                    result.put("targetFormat", "HSL");
                }
                default -> {
                    return AiToolResult.error("Unsupported target format: " + to + ". Use HEX, RGB, or HSL.");
                }
            }

            log.debug("color_convert success: {} -> {}", from, to);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("color_convert error: {}", e.getMessage());
            return AiToolResult.error("Color conversion error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/BuiltinBase64Tool.java \
       SwissKit/src/main/java/fan/summer/ai/tools/BuiltinHashTool.java \
       SwissKit/src/main/java/fan/summer/ai/tools/BuiltinJsonFormatTool.java \
       SwissKit/src/main/java/fan/summer/ai/tools/BuiltinColorConvertTool.java
git commit -m "✨ feat(ai): add built-in AI tools (Base64, Hash, JSON Format, Color Convert)"
```

---

### Task 10: Create BuiltinAiToolRegistrar and wire into app

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java`
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java`

- [ ] **Step 1: Create BuiltinAiToolRegistrar**

```java
package fan.summer.ai.tools;

import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.buildintool.ai.*;
import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;
import fan.summer.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class BuiltinAiToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BuiltinAiToolRegistrar.class);

    public static void register() {
        AiServiceProvider.registerTool(new BuiltinBase64Tool());
        AiServiceProvider.registerTool(new BuiltinHashTool());
        AiServiceProvider.registerTool(new BuiltinJsonFormatTool());
        AiServiceProvider.registerTool(new BuiltinColorConvertTool());

        registerExcelTools();

        log.info("Built-in AI tools registered: base64, hash_calculate, json_format, color_convert, excel_*");
    }

    private static void registerExcelTools() {
        PluginRegistry registry = PluginRegistry.getInstance();
        if (registry == null) return;

        Optional<ExcelSplitterPlugin> opt = registry.findPlugin("fan.summer.buildin.excelsplitter")
            .map(p -> (ExcelSplitterPlugin) p);
        if (opt.isEmpty()) return;

        ExcelSplitterPlugin plugin = opt.get();
        AiServiceProvider.registerTool(new ExcelAnalyzeTool(plugin));
        AiServiceProvider.registerTool(new ExcelConfigureTool(plugin));
        AiServiceProvider.registerTool(new ExcelExecuteTool(plugin));
        AiServiceProvider.registerTool(new ExcelQueryTool(plugin));
        AiServiceProvider.registerTool(new ExcelCancelTool());
        log.info("Excel AI tools registered (5 tools)");
    }
}
```

- [ ] **Step 2: Add BuiltinAiToolRegistrar.register() call to SwissKitJApp.start()**

In `SwissKitJApp.java`, add after the `initializeAiBackend()` call (line ~98), before `mainWindow = new MainWindow(...)`:

```java
// ── Register built-in AI tools ─────────────────────────────
BuiltinAiToolRegistrar.register();
log.info("Built-in AI tools registered");
```

Add the import at the top:
```java
import fan.summer.ai.tools.BuiltinAiToolRegistrar;
```

The full start method sequence becomes:
1. Logger binder
2. Database init
3. I18n
4. Plugin directory
5. PluginLoader + PluginRegistry
6. BuiltinToolRegistrar (UI plugins)
7. initializeAiBackend()
8. **BuiltinAiToolRegistrar.register()**  ← NEW
9. MainWindow
10. Scene setup
11. loader.start()

- [ ] **Step 3: Clean up AiChatPlugin — remove Excel tool registration**

In `AiChatPlugin.java`:
- Remove the `excelPlugin` field and `toolRegistrationListener` field
- Remove the `registerExcelTools()` method entirely
- Simplify `onActivate()` to just log activation
- Remove the `onDeactivate()` body (just log, no state change listener)

```java
@Override
public void onActivate() {
    log.info("AI Chat plugin activated");
}

@Override
public void onDeactivate() {
    log.info("AI Chat plugin deactivated");
}
```

Remove these imports that are no longer needed:
- `import fan.summer.buildintool.excelsplitter.ExcelSplitterPlugin;`
- `import fan.summer.buildintool.ai.ExcelAnalyzeTool;`
- `import fan.summer.buildintool.ai.ExcelConfigureTool;`
- `import fan.summer.buildintool.ai.ExcelExecuteTool;`
- `import fan.summer.buildintool.ai.ExcelQueryTool;`
- `import fan.summer.buildintool.ai.ExcelCancelTool;`
- `import fan.summer.plugin.PluginRegistry;`

- [ ] **Step 4: Build verify**

Run: `mvn compile -f SwissKit/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java \
       SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java \
       SwissKit/src/main/java/fan/summer/buildintool/ai/AiChatPlugin.java
git commit -m "✨ feat(ai): add BuiltinAiToolRegistrar, wire into startup, clean AiChatPlugin"
```

---

### Task 11: Delete JsonBuilder and JsonParser

**Files:**
- Delete: `SwissKit/src/main/java/fan/summer/ai/service/JsonBuilder.java`
- Delete: `SwissKit/src/main/java/fan/summer/ai/service/JsonParser.java`

- [ ] **Step 1: Verify no remaining references**

Run: `grep -r "JsonBuilder\|JsonParser" SwissKit/src/main/java/ --include="*.java" -l`
Expected: No results (all references replaced by JsonHelper)

If any files still reference JsonBuilder/JsonParser, update them to use JsonHelper first.

- [ ] **Step 2: Delete files**

```bash
rm SwissKit/src/main/java/fan/summer/ai/service/JsonBuilder.java
rm SwissKit/src/main/java/fan/summer/ai/service/JsonParser.java
```

- [ ] **Step 3: Final build verify**

Run: `mvn clean compile -f SwissKit/pom.xml`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "♻️ refactor(ai): delete JsonBuilder and JsonParser, fully replaced by Gson/JsonHelper"
```

---

### Task 12: Full build and package verification

**Files:** None (verification only)

- [ ] **Step 1: Install API module**

Run: `mvn install -f SwissKitJ-Api/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Package main app**

Run: `mvn clean package -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS, produces `SwissKit/target/SwissKitJ-3.0.0-beta.1.jar`

- [ ] **Step 3: Verify no duplicate tool registration**

Search for any remaining `new ToolRegistry()` or duplicate `registerTool` calls:
```bash
grep -rn "new ToolRegistry\|registerTool" SwissKit/src/main/java/ --include="*.java"
```

Expected: Only `BuiltinAiToolRegistrar.register()` calls and `AiServiceProvider.registerTool()` in utility classes. No `new ToolRegistry()`.

- [ ] **Step 4: Verify no JsonBuilder/JsonParser references**

```bash
grep -rn "JsonBuilder\|JsonParser" SwissKit/src/main/java/ --include="*.java"
```

Expected: No results.
