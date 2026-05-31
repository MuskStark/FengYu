# AI Model Source Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenAI-compatible and Anthropic Claude API backends to the AI chat, with a settings UI mode switcher and auto-initialize on startup.

**Architecture:** Three `AiService` implementations (existing local + new `OpenAiService` + new `AnthropicService`) selected by a `ai.mode` DB setting. `AiServiceProvider` gains a factory method. Settings UI adds a mode selector with per-mode config panels. App startup auto-initializes the active backend.

**Tech Stack:** Java 21, `java.net.http.HttpClient` for SSE streaming, H2/MyBatis for settings persistence, JavaFX for UI. No new external dependencies.

---

## File Map

### New files
- `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java` — OpenAI `/v1/chat/completions` streaming client
- `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java` — Anthropic `/v1/messages` streaming client

### Modified files
- `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java` — add `switchMode()` factory
- `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` — extract shared tool logic, no functional change
- `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — restructure AI tab with mode selector
- `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java` — auto-initialize AI backend on startup
- `SwissKit/src/main/resources/i18n/messages.properties` — new English labels
- `SwissKit/src/main/resources/i18n/messages_zh.properties` — new Chinese labels

---

### Task 1: Add i18n keys for AI mode switching

**Files:**
- Modify: `SwissKit/src/main/resources/i18n/messages.properties`
- Modify: `SwissKit/src/main/resources/i18n/messages_zh.properties`

- [ ] **Step 1: Add English i18n keys**

Append to `SwissKit/src/main/resources/i18n/messages.properties` after the existing `setting.ai.*` block (after line 91):

```properties
setting.ai.mode=AI Mode
setting.ai.mode.local=Local Model
setting.ai.mode.openai=OpenAI Compatible
setting.ai.mode.anthropic=Anthropic Claude
setting.ai.endpoint=API Endpoint
setting.ai.apiKey=API Key
setting.ai.modelName=Model Name
setting.ai.testConnection=Test Connection
setting.ai.testSuccess=Connection successful.
setting.ai.testFailed=Connection failed: {0}
setting.ai.autoLoading=Auto-loading model: {0}
setting.ai.autoLoadFailed=Auto-load failed: {0}
```

- [ ] **Step 2: Add Chinese i18n keys**

Append to `SwissKit/src/main/resources/i18n/messages_zh.properties` after the existing `setting.ai.*` block (after line 91):

```properties
setting.ai.mode=AI 模式
setting.ai.mode.local=本地模型
setting.ai.mode.openai=OpenAI 兼容
setting.ai.mode.anthropic=Anthropic Claude
setting.ai.endpoint=API 地址
setting.ai.apiKey=API 密钥
setting.ai.modelName=模型名称
setting.ai.testConnection=测试连接
setting.ai.testSuccess=连接成功。
setting.ai.testFailed=连接失败：{0}
setting.ai.autoLoading=自动加载模型：{0}
setting.ai.autoLoadFailed=自动加载失败：{0}
```

- [ ] **Step 3: Build and verify no compilation errors**

Run: `mvn install -f SwissKitJ-Api/pom.xml -DskipTests && mvn compile -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add SwissKit/src/main/resources/i18n/messages.properties SwissKit/src/main/resources/i18n/messages_zh.properties
git commit -m "📝 feat(i18n): add i18n keys for AI mode switching"
```

---

### Task 2: Add `switchMode()` factory to `AiServiceProvider`

**Files:**
- Modify: `SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java`

- [ ] **Step 1: Add factory method**

Add the following imports and method to `AiServiceProvider.java`. The full file becomes:

```java
package fan.summer.api.ai;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AiServiceProvider {

    private static volatile AiService instance;
    private static volatile String currentMode = "local";
    private static final List<Runnable> stateChangeListeners = new CopyOnWriteArrayList<>();

    private AiServiceProvider() {}

    public static void setService(AiService service) {
        instance = service;
    }

    public static Optional<AiService> getService() {
        return Optional.ofNullable(instance);
    }

    public static void addOnStateChangeListener(Runnable listener) {
        stateChangeListeners.add(listener);
    }

    public static void notifyStateChanged() {
        for (Runnable listener : stateChangeListeners) {
            listener.run();
        }
    }

    /** Returns the current mode string: "local", "openai", or "anthropic". */
    public static String getCurrentMode() {
        return currentMode;
    }

    /**
     * Set the current mode label. Does NOT create the service —
     * the caller (host app) is responsible for instantiating the
     * appropriate AiService and passing it to {@link #setService(AiService)}.
     *
     * @param mode one of "local", "openai", "anthropic"
     */
    public static void setCurrentMode(String mode) {
        currentMode = mode;
    }
}
```

- [ ] **Step 2: Build API module**

Run: `mvn install -f SwissKitJ-Api/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add SwissKitJ-Api/src/main/java/fan/summer/api/ai/AiServiceProvider.java
git commit -m "✨ feat(api): add mode tracking to AiServiceProvider"
```

---

### Task 3: Create `OpenAiService`

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java`

- [ ] **Step 1: Write `OpenAiService`**

Create `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java`:

```java
package fan.summer.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolRegistry;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class OpenAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private volatile boolean generating = false;

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
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
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

    @Override public Optional<String> getModelName() {
        return Optional.ofNullable(modelName);
    }

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

    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, float topP,
                                  int maxTokens, AiStreamCallback callback, int round,
                                  AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = toolRegistry.buildToolDefinitions();
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        // Build request body
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("messages", buildMessages(history, systemPrompt));
        if (toolRegistry.hasTools()) {
            body.put("tools", buildOpenAiTools());
        }

        String jsonBody = MAPPER.writeValueAsString(body);
        String url = endpoint + "/v1/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<HttpResponse.BodySubscriber<String>> response =
            httpClient.send(request, responseInfo ->
                HttpResponse.BodySubscribers.ofLines(java.nio.charset.StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            String errBody = response.body() != null ? response.body().toString() : "HTTP " + response.statusCode();
            throw new AiServiceException("OpenAI API error (HTTP " + response.statusCode() + "): " + errBody);
        }

        // Parse SSE stream
        StringBuilder fullResponse = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        Map<Integer, StringBuilder> toolCallArgs = new LinkedHashMap<>();
        Map<Integer, String> toolCallNames = new LinkedHashMap<>();
        Map<Integer, String> toolCallIds = new LinkedHashMap<>();
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        var lineStream = response.body();
        // We need to read lines from the BodySubscriber
        // Since we used ofLines(), response.body() returns a BodySubscriber<String>
        // Actually we need a different approach for streaming

        // Re-send with InputStream body handler
        HttpResponse<InputStream> streamResp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(streamResp, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;

                JsonNode chunk = MAPPER.readTree(data);
                JsonNode delta = chunk.at("/choices/0/delta");
                if (delta == null || delta.isMissingNode()) continue;

                // Text content
                if (delta.has("content") && !delta.get("content").isNull()) {
                    String text = delta.get("content").asText();
                    fullResponse.append(text);
                    tokenCount[0]++;
                    Platform.runLater(() -> callback.onToken(text));
                }

                // Tool calls
                if (delta.has("tool_calls")) {
                    for (JsonNode tc : delta.get("tool_calls")) {
                        int idx = tc.get("index").asInt();
                        if (tc.has("id")) toolCallIds.put(idx, tc.get("id").asText());
                        if (tc.has("function")) {
                            JsonNode fn = tc.get("function");
                            if (fn.has("name")) toolCallNames.put(idx, fn.get("name").asText());
                            if (fn.has("arguments")) {
                                toolCallArgs.computeIfAbsent(idx, k -> new StringBuilder())
                                    .append(fn.get("arguments").asText());
                            }
                        }
                    }
                }
            }
        }

        // Assemble tool calls
        for (int idx : toolCallArgs.keySet()) {
            String argsJson = toolCallArgs.get(idx).toString();
            Map<String, Object> args;
            try {
                JsonNode argsNode = MAPPER.readTree(argsJson);
                args = new LinkedHashMap<>();
                argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), unwrapJson(e.getValue())));
            } catch (Exception e) {
                args = Map.of("raw", argsJson);
            }
            toolCalls.add(new AiToolCall(toolCallIds.get(idx), toolCallNames.get(idx), args));
        }

        long elapsed = System.nanoTime() - startTime;
        double tokPerSec = tokenCount[0] > 0 ? tokenCount[0] * 1_000_000_000.0 / elapsed : 0;

        if (!toolCalls.isEmpty() && toolRegistry.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithTools(fullResponse.toString(), toolCalls));
            for (AiToolCall tc : toolCalls) {
                Platform.runLater(() -> callback.onToolCall(tc));
                AiToolResult result = toolRegistry.execute(tc.name(), tc.arguments());
                Platform.runLater(() -> callback.onToolResult(tc.id(), result));
                history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
            }
            chatWithToolLoop(history, temperature, topP, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            String finalClean = clean;
            Platform.runLater(() -> callback.onComplete(finalClean, tokenCount[0], tokPerSec));
        }
    }

    private Object unwrapJson(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }

    private List<Map<String, Object>> buildMessages(List<AiChatMessage> history, String systemPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.TOOL) {
                messages.add(Map.of("role", "tool", "tool_call_id", msg.toolCallId(), "content", msg.content()));
            } else if (msg.hasToolCalls()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.content());
                m.put("tool_calls", msg.toolCalls().stream().map(tc -> {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", tc.name());
                    fn.put("arguments", MAPPER.writeValueAsString(tc.arguments()));
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", tc.id());
                    toolCall.put("type", "function");
                    toolCall.put("function", fn);
                    return toolCall;
                }).collect(Collectors.toList()));
                messages.add(m);
            } else {
                messages.add(Map.of("role", msg.role().name().toLowerCase(), "content", msg.content()));
            }
        }
        return messages;
    }

    private List<Map<String, Object>> buildOpenAiTools() {
        return toolRegistry.getAll().stream().map(tool -> {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getName());
            fn.put("description", tool.getDescription());
            fn.put("parameters", tool.getParameters());
            return Map.<String, Object>of("type", "function", "function", fn);
        }).collect(Collectors.toList());
    }

    @Override public void cancelGeneration() { generating = false; }

    @Override public void registerTool(AiTool tool) { toolRegistry.register(tool); }
    @Override public void unregisterTool(String toolName) { toolRegistry.unregister(toolName); }
    @Override public List<AiTool> getTools() { return toolRegistry.getAll(); }

    /**
     * Test the connection by sending a minimal chat request.
     * @return error message if failed, null if successful
     */
    public String testConnection() {
        try {
            String url = endpoint + "/v1/chat/completions";
            String body = MAPPER.writeValueAsString(Map.of(
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

- [ ] **Step 2: Build and fix compilation**

Run: `mvn compile -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS. Fix any import issues (the code uses Jackson's ObjectMapper — verify it's on the classpath or switch to manual JSON building).

Note: If Jackson is not available, replace `ObjectMapper` usage with manual string building or `javax.json`. Check pom.xml dependencies first. The project uses MyBatis which typically brings in Jackson. If not, use simple string concatenation for JSON building.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java
git commit -m "✨ feat(ai): add OpenAI-compatible API backend service"
```

---

### Task 4: Create `AnthropicService`

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java`

- [ ] **Step 1: Write `AnthropicService`**

Create `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java`:

```java
package fan.summer.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.ai.tools.ToolCallParser;
import fan.summer.ai.tools.ToolRegistry;
import fan.summer.api.ai.*;
import fan.summer.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class AnthropicService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_ROUNDS = 5;

    private final HttpClient httpClient;
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private volatile boolean generating = false;

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
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
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

    @Override public Optional<String> getModelName() {
        return Optional.ofNullable(modelName);
    }

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
                chatWithToolLoop(history, temperature, maxTokens, callback, 0, new AtomicBoolean(false));
            } catch (Exception e) {
                log.error("Anthropic generation error", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating = false;
            }
        });
    }

    private void chatWithToolLoop(List<AiChatMessage> history, float temperature, int maxTokens,
                                  AiStreamCallback callback, int round, AtomicBoolean hadToolCall) throws Exception {
        if (round >= MAX_TOOL_ROUNDS) return;

        String systemPrompt = SwissKitJSettingUi.getAiSystemPrompt();
        String toolDefs = toolRegistry.buildToolDefinitions();
        if (!toolDefs.isEmpty()) systemPrompt += "\n\n" + toolDefs;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", true);
        body.put("system", systemPrompt);
        body.put("messages", buildAnthropicMessages(history));
        if (toolRegistry.hasTools()) {
            body.put("tools", buildAnthropicTools());
        }

        String jsonBody = MAPPER.writeValueAsString(body);
        String url = endpoint + "/v1/messages";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(120))
            .build();

        HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (resp.statusCode() != 200) {
            String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new AiServiceException("Anthropic API error (HTTP " + resp.statusCode() + "): " + errBody);
        }

        // Parse SSE stream
        StringBuilder fullResponse = new StringBuilder();
        List<AiToolCall> toolCalls = new ArrayList<>();
        StringBuilder currentToolArgs = new StringBuilder();
        String currentToolName = null;
        String currentToolId = null;
        long startTime = System.nanoTime();
        int[] tokenCount = {0};

        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String eventType = "";
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    eventType = line.substring(7).trim();
                    continue;
                }
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();

                JsonNode chunk = MAPPER.readTree(data);
                String type = chunk.has("type") ? chunk.get("type").asText() : eventType;

                switch (type) {
                    case "content_block_delta" -> {
                        JsonNode delta = chunk.at("/delta");
                        if (delta.has("text")) {
                            String text = delta.get("text").asText();
                            fullResponse.append(text);
                            tokenCount[0]++;
                            Platform.runLater(() -> callback.onToken(text));
                        } else if (delta.has("partial_json")) {
                            currentToolArgs.append(delta.get("partial_json").asText());
                        }
                    }
                    case "content_block_start" -> {
                        JsonNode contentBlock = chunk.at("/content_block");
                        if (contentBlock.has("type") && "tool_use".equals(contentBlock.get("type").asText())) {
                            currentToolName = contentBlock.get("name").asText();
                            currentToolId = contentBlock.get("id").asText();
                            currentToolArgs.setLength(0);
                        }
                    }
                    case "content_block_stop" -> {
                        if (currentToolName != null && currentToolId != null) {
                            Map<String, Object> args;
                            try {
                                JsonNode argsNode = MAPPER.readTree(currentToolArgs.toString());
                                args = new LinkedHashMap<>();
                                argsNode.fields().forEachRemaining(e -> args.put(e.getKey(), unwrapJson(e.getValue())));
                            } catch (Exception e) {
                                args = Map.of("raw", currentToolArgs.toString());
                            }
                            toolCalls.add(new AiToolCall(currentToolId, currentToolName, args));
                            currentToolName = null;
                            currentToolId = null;
                        }
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - startTime;
        double tokPerSec = tokenCount[0] > 0 ? tokenCount[0] * 1_000_000_000.0 / elapsed : 0;

        if (!toolCalls.isEmpty() && toolRegistry.hasTools()) {
            hadToolCall.set(true);
            history.add(AiChatMessage.assistantWithTools(fullResponse.toString(), toolCalls));
            for (AiToolCall tc : toolCalls) {
                Platform.runLater(() -> callback.onToolCall(tc));
                AiToolResult result = toolRegistry.execute(tc.name(), tc.arguments());
                Platform.runLater(() -> callback.onToolResult(tc.id(), result));
                history.add(AiChatMessage.toolResult(tc.id(), tc.name(), result.output()));
            }
            chatWithToolLoop(history, temperature, maxTokens, callback, round + 1, hadToolCall);
        } else {
            String clean = hadToolCall.get() ? ToolCallParser.stripToolCalls(fullResponse.toString()) : fullResponse.toString();
            String finalClean = clean;
            Platform.runLater(() -> callback.onComplete(finalClean, tokenCount[0], tokPerSec));
        }
    }

    private Object unwrapJson(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }

    private List<Map<String, Object>> buildAnthropicMessages(List<AiChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (AiChatMessage msg : history) {
            if (msg.role() == AiChatMessage.Role.SYSTEM) continue; // system goes as top-level field
            if (msg.role() == AiChatMessage.Role.TOOL) {
                // Anthropic: tool results go in a user message with tool_result content blocks
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("type", "tool_result");
                content.put("tool_use_id", msg.toolCallId());
                content.put("content", msg.content());
                messages.add(Map.of("role", "user", "content", List.of(content)));
            } else if (msg.hasToolCalls()) {
                List<Object> contentBlocks = new ArrayList<>();
                if (!msg.content().isEmpty()) {
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

    private List<Map<String, Object>> buildAnthropicTools() {
        return toolRegistry.getAll().stream().map(tool -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("input_schema", tool.getParameters());
            return t;
        }).collect(Collectors.toList());
    }

    @Override public void cancelGeneration() { generating = false; }

    @Override public void registerTool(AiTool tool) { toolRegistry.register(tool); }
    @Override public void unregisterTool(String toolName) { toolRegistry.unregister(toolName); }
    @Override public List<AiTool> getTools() { return toolRegistry.getAll(); }

    public String testConnection() {
        try {
            String url = endpoint + "/v1/messages";
            String body = MAPPER.writeValueAsString(Map.of(
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

- [ ] **Step 2: Build and fix compilation**

Run: `mvn compile -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS. Same Jackson note as Task 3 — verify dependency.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java
git commit -m "✨ feat(ai): add Anthropic Claude API backend service"
```

---

### Task 5: Restructure AI Settings UI with mode selector

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java`

- [ ] **Step 1: Add mode-related DB keys and helper methods**

At the top of `SwissKitJSettingUi`, add these constants after the existing `AI_*` constants (after line 330):

```java
private static final String AI_MODE_KEY = "ai.mode";
private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
private static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
private static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
```

Add these static getters after the existing `getAiSystemPrompt()` method:

```java
public static String getAiMode() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_MODE_KEY);
        if (entity != null && entity.getSettingValue() != null) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "local";
}

public static String getAiOpenAiEndpoint() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_OPENAI_ENDPOINT_KEY);
        if (entity != null && !entity.getSettingValue().isBlank()) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "https://api.openai.com";
}

public static String getAiOpenAiApiKey() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_OPENAI_API_KEY_KEY);
        if (entity != null) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "";
}

public static String getAiOpenAiModel() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_OPENAI_MODEL_KEY);
        if (entity != null && !entity.getSettingValue().isBlank()) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "gpt-4o";
}

public static String getAiAnthropicEndpoint() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_ANTHROPIC_ENDPOINT_KEY);
        if (entity != null && !entity.getSettingValue().isBlank()) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "https://api.anthropic.com";
}

public static String getAiAnthropicApiKey() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_ANTHROPIC_API_KEY_KEY);
        if (entity != null) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "";
}

public static String getAiAnthropicModel() {
    try (SqlSession session = DatabaseInit.getSqlSession()) {
        AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
        AppSettingEntity entity = mapper.selectByKey(AI_ANTHROPIC_MODEL_KEY);
        if (entity != null && !entity.getSettingValue().isBlank()) return entity.getSettingValue();
    } catch (Exception ignored) {}
    return "claude-sonnet-4-20250514";
}
```

- [ ] **Step 2: Rewrite `buildAiModelTab()` with mode selector and panels**

Replace the existing `buildAiModelTab()` method (lines 332-519) with a new version that:
1. Adds a mode ComboBox at the top
2. Creates three config panels (local / openai / anthropic) as separate VBoxes
3. Wraps them in a StackPane and toggles visibility based on mode selection
4. Shared generation params (temperature, topP, maxTokens, systemPrompt) stay below the mode panels
5. On mode change: saves `ai.mode`, calls `initializeAiService()` to switch backend

The new `buildAiModelTab()` should be structured as:

```java
private static VBox buildAiModelTab() {
    VBox root = new VBox(16);
    root.setPadding(new Insets(20));
    root.setStyle("-fx-background-color: transparent;");

    Label title = sectionTitle(I18n.get("setting.ai.title"));

    // ── Mode selector ─────────────────────────────
    Label modeLabel = subLabel(I18n.get("setting.ai.mode"));
    ComboBox<String> modeCombo = new ComboBox<>(
        FXCollections.observableArrayList(
            I18n.get("setting.ai.mode.local"),
            I18n.get("setting.ai.mode.openai"),
            I18n.get("setting.ai.mode.anthropic")
        )
    );
    modeCombo.getStyleClass().add("glass-combo");
    modeCombo.setMaxWidth(250);
    loadAiSetting(AI_MODE_KEY, val -> {
        String label = switch (val) {
            case "openai" -> I18n.get("setting.ai.mode.openai");
            case "anthropic" -> I18n.get("setting.ai.mode.anthropic");
            default -> I18n.get("setting.ai.mode.local");
        };
        modeCombo.setValue(label);
    });
    if (modeCombo.getValue() == null) modeCombo.setValue(I18n.get("setting.ai.mode.local"));

    HBox modeRow = new HBox(10, modeLabel, modeCombo);
    modeRow.setAlignment(Pos.CENTER_LEFT);

    // ── Mode-specific panels ───────────────────────
    VBox localPanel = buildLocalModelPanel();
    VBox openaiPanel = buildOpenAiPanel();
    VBox anthropicPanel = buildAnthropicPanel();

    StackPane modeStack = new StackPane(localPanel, openaiPanel, anthropicPanel);
    modeStack.setStyle("-fx-background-color: transparent;");

    // Initial visibility
    String initMode = modeCombo.getValue();
    showModePanel(modeStack, initMode);

    modeCombo.setOnAction(e -> {
        String selected = modeCombo.getValue();
        showModePanel(modeStack, selected);
        String modeKey = modeLabelToKey(selected);
        saveAiSetting(AI_MODE_KEY, modeKey);
        initializeAiService(modeKey);
    });

    // ── Shared generation parameters ───────────────
    Label paramTitle = sectionTitle(I18n.get("setting.ai.genParams"));

    // Temperature
    Label tempValue = new Label("0.7");
    tempValue.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
    Slider tempSlider = new Slider(0, 2, 0.7);
    tempSlider.setShowTickLabels(true);
    tempSlider.setShowTickMarks(true);
    tempSlider.setMajorTickUnit(0.5);
    tempSlider.setMinorTickCount(4);
    tempSlider.setBlockIncrement(0.1);
    tempSlider.setPrefWidth(300);
    tempSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
        tempValue.setText(String.format("%.2f", newVal.doubleValue()));
        saveAiSetting(AI_TEMPERATURE_KEY, String.format("%.2f", newVal.doubleValue()));
    });
    loadAiSetting(AI_TEMPERATURE_KEY, val -> {
        try { tempSlider.setValue(Double.parseDouble(val)); } catch (NumberFormatException ignored) {}
    });
    HBox tempRow = new HBox(10, tempSlider, tempValue);
    tempRow.setAlignment(Pos.CENTER_LEFT);

    // Top P
    Label topPValue = new Label("0.9");
    topPValue.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");
    Slider topPSlider = new Slider(0, 1, 0.9);
    topPSlider.setShowTickLabels(true);
    topPSlider.setShowTickMarks(true);
    topPSlider.setMajorTickUnit(0.25);
    topPSlider.setMinorTickCount(3);
    topPSlider.setBlockIncrement(0.05);
    topPSlider.setPrefWidth(300);
    topPSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
        topPValue.setText(String.format("%.2f", newVal.doubleValue()));
        saveAiSetting(AI_TOP_P_KEY, String.format("%.2f", newVal.doubleValue()));
    });
    loadAiSetting(AI_TOP_P_KEY, val -> {
        try { topPSlider.setValue(Double.parseDouble(val)); } catch (NumberFormatException ignored) {}
    });
    HBox topPRow = new HBox(10, topPSlider, topPValue);
    topPRow.setAlignment(Pos.CENTER_LEFT);

    // Max tokens
    Spinner<Integer> maxTokensSpinner = new Spinner<>();
    maxTokensSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(64, 4096, 512, 64));
    maxTokensSpinner.getStyleClass().add("glass-field");
    maxTokensSpinner.setPrefWidth(120);
    maxTokensSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
        saveAiSetting(AI_MAX_TOKENS_KEY, String.valueOf(newVal));
    });
    loadAiSetting(AI_MAX_TOKENS_KEY, val -> {
        try { maxTokensSpinner.getValueFactory().setValue(Integer.parseInt(val)); } catch (NumberFormatException ignored) {}
    });

    // System prompt
    TextField sysPromptField = textField(null, "You are a helpful assistant.");
    HBox.setHgrow(sysPromptField, Priority.ALWAYS);
    sysPromptField.textProperty().addListener((obs, oldVal, newVal) -> {
        saveAiSetting(AI_SYSTEM_PROMPT_KEY, newVal);
    });
    loadAiSetting(AI_SYSTEM_PROMPT_KEY, val -> sysPromptField.setText(val));

    root.getChildren().addAll(
        title, modeRow, modeStack,
        paramTitle,
        labeled(I18n.get("setting.ai.temperature"), tempRow),
        labeled(I18n.get("setting.ai.topP"), topPRow),
        labeled(I18n.get("setting.ai.maxTokens"), maxTokensSpinner),
        labeled(I18n.get("setting.ai.systemPrompt"), sysPromptField)
    );

    return root;
}
```

- [ ] **Step 3: Add helper methods for mode panel switching and panel builders**

Add these methods to `SwissKitJSettingUi`:

```java
private static String modeLabelToKey(String label) {
    if (label == null) return "local";
    if (label.equals(I18n.get("setting.ai.mode.openai"))) return "openai";
    if (label.equals(I18n.get("setting.ai.mode.anthropic"))) return "anthropic";
    return "local";
}

private static void showModePanel(StackPane stack, String modeLabel) {
    String key = modeLabelToKey(modeLabel);
    var panels = stack.getChildren();
    // Order: local=0, openai=1, anthropic=2
    int idx = switch (key) {
        case "openai" -> 1;
        case "anthropic" -> 2;
        default -> 0;
    };
    for (int i = 0; i < panels.size(); i++) {
        panels.get(i).setVisible(i == idx);
        panels.get(i).setManaged(i == idx);
    }
}

private static void initializeAiService(String mode) {
    AiServiceProvider.setCurrentMode(mode);
    switch (mode) {
        case "openai" -> {
            OpenAiService svc = new OpenAiService();
            svc.configure(getAiOpenAiEndpoint(), getAiOpenAiApiKey(), getAiOpenAiModel());
            AiServiceProvider.setService(svc);
            AiServiceProvider.notifyStateChanged();
        }
        case "anthropic" -> {
            AnthropicService svc = new AnthropicService();
            svc.configure(getAiAnthropicEndpoint(), getAiAnthropicApiKey(), getAiAnthropicModel());
            AiServiceProvider.setService(svc);
            AiServiceProvider.notifyStateChanged();
        }
        default -> {
            // Local mode: AiServiceImpl is created at startup; just notify
            AiServiceProvider.notifyStateChanged();
        }
    }
}
```

Add `buildLocalModelPanel()`, `buildOpenAiPanel()`, and `buildAnthropicPanel()`:

```java
private static VBox buildLocalModelPanel() {
    VBox panel = new VBox(12);

    Label modelStatusLabel = new Label(I18n.get("setting.ai.noModelLoaded"));
    modelStatusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 13px;");

    Label modelPathLabel = new Label("—");
    modelPathLabel.setWrapText(true);
    modelPathLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 12px;");

    TextField modelPathField = textField(null, I18n.get("setting.ai.selectModel"));
    loadAiSetting(AI_MODEL_PATH_KEY, val -> modelPathField.setText(val));

    Button browseBtn = glassBtn(I18n.get("setting.ai.browse"), false);
    Button loadBtn = glassBtn(I18n.get("setting.ai.loadModel"), true);
    Button unloadBtn = glassBtn(I18n.get("setting.ai.unload"), false);
    unloadBtn.setDisable(true);

    browseBtn.setOnAction(e -> {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("setting.ai.selectModel"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GGUF Model", "*.gguf"));
        File file = chooser.showOpenDialog(browseBtn.getScene().getWindow());
        if (file != null) {
            modelPathField.setText(file.getAbsolutePath());
            saveAiSetting(AI_MODEL_PATH_KEY, file.getAbsolutePath());
        }
    });

    loadBtn.setOnAction(e -> {
        String path = modelPathField.getText();
        if (path == null || path.isBlank()) {
            GlassNotification.notify(null, GlassNotification.Type.WARNING, I18n.get("setting.ai.selectModelFile"));
            return;
        }
        loadBtn.setDisable(true);
        modelStatusLabel.setText(I18n.get("setting.ai.loadingModel"));
        saveAiSetting(AI_MODEL_PATH_KEY, path.trim());

        Thread.ofVirtual().start(() -> {
            try {
                Optional<AiService> opt = AiServiceProvider.getService();
                if (opt.isEmpty()) {
                    Platform.runLater(() -> { modelStatusLabel.setText(I18n.get("setting.ai.aiServiceError")); loadBtn.setDisable(false); });
                    return;
                }
                AiService service = opt.get();
                service.loadModel(Path.of(path.trim()));
                Platform.runLater(() -> {
                    modelStatusLabel.setText(I18n.get("setting.ai.modelLoaded", service.getModelName().orElse("Unknown")));
                    modelPathLabel.setText(path.trim());
                    loadBtn.setDisable(false);
                    unloadBtn.setDisable(false);
                    AiServiceProvider.notifyStateChanged();
                });
            } catch (Exception ex) {
                log.error("Failed to load AI model", ex);
                Platform.runLater(() -> {
                    modelStatusLabel.setText(I18n.get("setting.ai.modelLoadFailed", ex.getMessage()));
                    loadBtn.setDisable(false);
                });
            }
        });
    });

    unloadBtn.setOnAction(e -> {
        Optional<AiService> opt = AiServiceProvider.getService();
        if (opt.isPresent()) opt.get().unloadModel();
        modelStatusLabel.setText(I18n.get("setting.ai.noModelLoaded"));
        modelPathLabel.setText("—");
        unloadBtn.setDisable(true);
        AiServiceProvider.notifyStateChanged();
    });

    HBox modelBtnRow = new HBox(8, browseBtn, loadBtn, unloadBtn);
    modelBtnRow.setAlignment(Pos.CENTER_LEFT);

    ProgressBar memBar = new ProgressBar(0);
    memBar.setPrefWidth(300);
    Label memText = new Label("—");
    memText.setStyle("-fx-text-fill: rgba(255,255,255,0.35); -fx-font-size: 11px;");
    HBox memRow = new HBox(10, memBar, memText);
    memRow.setAlignment(Pos.CENTER_LEFT);

    panel.getChildren().addAll(
        modelStatusLabel, modelPathLabel,
        labeled(I18n.get("setting.ai.modelPath"), modelPathField),
        modelBtnRow,
        labeled(I18n.get("setting.ai.memoryUsage"), memRow)
    );

    refreshAiModelState(modelStatusLabel, modelPathLabel, unloadBtn);
    return panel;
}

private static VBox buildOpenAiPanel() {
    VBox panel = new VBox(12);

    TextField endpointField = textField(null, "https://api.openai.com");
    loadAiSetting(AI_OPENAI_ENDPOINT_KEY, val -> endpointField.setText(val));

    PasswordField apiKeyField = new PasswordField();
    apiKeyField.getStyleClass().add(FIELD_STYLE_CLASS);
    loadAiSetting(AI_OPENAI_API_KEY_KEY, val -> apiKeyField.setText(val));

    TextField modelField = textField(null, "gpt-4o");
    loadAiSetting(AI_OPENAI_MODEL_KEY, val -> modelField.setText(val));

    // Auto-save on change
    endpointField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_ENDPOINT_KEY, n));
    apiKeyField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_API_KEY_KEY, n));
    modelField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_OPENAI_MODEL_KEY, n));

    Label statusLabel = new Label("");
    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

    Button testBtn = glassBtn(I18n.get("setting.ai.testConnection"), false);
    testBtn.setOnAction(e -> {
        testBtn.setDisable(true);
        Thread.ofVirtual().start(() -> {
            OpenAiService svc = new OpenAiService();
            svc.configure(endpointField.getText(), apiKeyField.getText(), modelField.getText());
            String err = svc.testConnection();
            Platform.runLater(() -> {
                if (err == null) {
                    statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                    statusLabel.setText(I18n.get("setting.ai.testSuccess"));
                } else {
                    statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
                    statusLabel.setText(I18n.get("setting.ai.testFailed", err));
                }
                testBtn.setDisable(false);
            });
        });
    });

    panel.getChildren().addAll(
        labeled(I18n.get("setting.ai.endpoint"), endpointField),
        labeled(I18n.get("setting.ai.apiKey"), apiKeyField),
        labeled(I18n.get("setting.ai.modelName"), modelField),
        testBtn, statusLabel
    );
    return panel;
}

private static VBox buildAnthropicPanel() {
    VBox panel = new VBox(12);

    TextField endpointField = textField(null, "https://api.anthropic.com");
    loadAiSetting(AI_ANTHROPIC_ENDPOINT_KEY, val -> endpointField.setText(val));

    PasswordField apiKeyField = new PasswordField();
    apiKeyField.getStyleClass().add(FIELD_STYLE_CLASS);
    loadAiSetting(AI_ANTHROPIC_API_KEY_KEY, val -> apiKeyField.setText(val));

    TextField modelField = textField(null, "claude-sonnet-4-20250514");
    loadAiSetting(AI_ANTHROPIC_MODEL_KEY, val -> modelField.setText(val));

    endpointField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_ENDPOINT_KEY, n));
    apiKeyField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_API_KEY_KEY, n));
    modelField.textProperty().addListener((obs, o, n) -> saveAiSetting(AI_ANTHROPIC_MODEL_KEY, n));

    Label statusLabel = new Label("");
    statusLabel.setWrapText(true);
    statusLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.55); -fx-font-size: 12px;");

    Button testBtn = glassBtn(I18n.get("setting.ai.testConnection"), false);
    testBtn.setOnAction(e -> {
        testBtn.setDisable(true);
        Thread.ofVirtual().start(() -> {
            AnthropicService svc = new AnthropicService();
            svc.configure(endpointField.getText(), apiKeyField.getText(), modelField.getText());
            String err = svc.testConnection();
            Platform.runLater(() -> {
                if (err == null) {
                    statusLabel.setStyle("-fx-text-fill: #4cd97b; -fx-font-size: 12px;");
                    statusLabel.setText(I18n.get("setting.ai.testSuccess"));
                } else {
                    statusLabel.setStyle("-fx-text-fill: #f25c5c; -fx-font-size: 12px;");
                    statusLabel.setText(I18n.get("setting.ai.testFailed", err));
                }
                testBtn.setDisable(false);
            });
        });
    });

    panel.getChildren().addAll(
        labeled(I18n.get("setting.ai.endpoint"), endpointField),
        labeled(I18n.get("setting.ai.apiKey"), apiKeyField),
        labeled(I18n.get("setting.ai.modelName"), modelField),
        testBtn, statusLabel
    );
    return panel;
}
```

- [ ] **Step 4: Build and fix compilation**

Run: `mvn compile -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java
git commit -m "✨ feat(settings): restructure AI tab with mode selector and remote config panels"
```

---

### Task 6: Auto-initialize AI backend on startup

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java`

- [ ] **Step 1: Add AI auto-initialization to `start()` method**

In `SwissKitJApp.start()`, after `BuiltinToolRegistrar.register(loader, registry)` (line 92) and before building the main window (line 96), add AI initialization:

```java
// ── Initialize AI backend based on saved mode ────────
initializeAiBackend();
```

Add the new method to `SwissKitJApp`:

```java
private void initializeAiBackend() {
    String mode = SwissKitJSettingUi.getAiMode();
    log.info("AI backend mode: {}", mode);
    AiServiceProvider.setCurrentMode(mode);

    switch (mode) {
        case "openai" -> {
            fan.summer.ai.service.OpenAiService svc = new fan.summer.ai.service.OpenAiService();
            svc.configure(
                SwissKitJSettingUi.getAiOpenAiEndpoint(),
                SwissKitJSettingUi.getAiOpenAiApiKey(),
                SwissKitJSettingUi.getAiOpenAiModel()
            );
            AiServiceProvider.setService(svc);
            log.info("OpenAI backend initialized: endpoint={}, model={}",
                SwissKitJSettingUi.getAiOpenAiEndpoint(), SwissKitJSettingUi.getAiOpenAiModel());
        }
        case "anthropic" -> {
            fan.summer.ai.service.AnthropicService svc = new fan.summer.ai.service.AnthropicService();
            svc.configure(
                SwissKitJSettingUi.getAiAnthropicEndpoint(),
                SwissKitJSettingUi.getAiAnthropicApiKey(),
                SwissKitJSettingUi.getAiAnthropicModel()
            );
            AiServiceProvider.setService(svc);
            log.info("Anthropic backend initialized: endpoint={}, model={}",
                SwissKitJSettingUi.getAiAnthropicEndpoint(), SwissKitJSettingUi.getAiAnthropicModel());
        }
        default -> {
            // Local mode: create AiServiceImpl and auto-load model if path is saved
            fan.summer.ai.service.AiServiceImpl aiService = new fan.summer.ai.service.AiServiceImpl();
            AiServiceProvider.setService(aiService);

            String modelPath = null;
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
                AppSettingEntity entity = mapper.selectByKey("ai.model.path");
                if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                    modelPath = entity.getSettingValue();
                }
            } catch (Exception e) {
                log.debug("Could not read AI model path", e);
            }

            if (modelPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(modelPath))) {
                log.info("Auto-loading local AI model: {}", modelPath);
                Thread.ofVirtual().start(() -> {
                    try {
                        aiService.loadModel(java.nio.file.Path.of(modelPath));
                        AiServiceProvider.notifyStateChanged();
                        log.info("Local AI model auto-loaded successfully");
                    } catch (Exception e) {
                        log.warn("Auto-load failed: {}", e.getMessage());
                    }
                });
            }
        }
    }
}
```

Add the needed imports at the top of `SwissKitJApp.java`:

```java
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.ui.setting.SwissKitJSettingUi;
```

- [ ] **Step 2: Build and verify**

Run: `mvn compile -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/app/SwissKitJApp.java
git commit -m "✨ feat(ai): auto-initialize AI backend on startup based on saved mode"
```

---

### Task 7: Build, smoke test, final commit

**Files:** None new

- [ ] **Step 1: Full build**

Run: `mvn install -f SwissKitJ-Api/pom.xml -DskipTests && mvn clean package -f SwissKit/pom.xml -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Verify no runtime errors on startup**

Run: `java -jar SwissKit/target/SwissKitJ-*.jar`
Expected: App starts, AI Chat appears in sidebar, Settings → AI Model shows mode selector. No exceptions in console.

- [ ] **Step 3: Verify mode switching works in Settings UI**

Manual check:
1. Open Settings → AI Model tab
2. Select "OpenAI Compatible" → endpoint/apiKey/model fields appear
3. Select "Anthropic Claude" → anthropic config fields appear
4. Select "Local Model" → GGUF model picker appears

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "🐛 fix(ai): address runtime issues from smoke test"
```
