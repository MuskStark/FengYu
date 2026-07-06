# LangChain4j Cloud Mode Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace custom OpenAI/Anthropic HTTP+SSE+tool-loop code (~700 lines) with LangChain4j-backed adapters, keeping `AiService`/`AiTool` interfaces and local mode untouched.

**Architecture:** Hybrid adapter pattern. New `OpenAiService` / `AnthropicService` wrap LangChain4j `OpenAiStreamingChatModel` / `AnthropicStreamingChatModel` and manually drive the multi-round tool loop using the existing `ToolExecutor.executeAndFeed` (preserves `AiStreamCallback` events for UI feedback). New small mappers translate between SwissKitJ's `AiChatMessage`/`AiTool`/`AiStreamCallback` and LangChain4j's `ChatMessage`/`ToolSpecification`/`StreamingChatResponseHandler`. A new `CloudAiConfigProvider` interface lets `SynchronousChatHelper` extract endpoint/key/model without `instanceof OpenAiService`.

**Tech Stack:** Java 21, JavaFX 21, LangChain4j 1.0.1 (`langchain4j-open-ai`, `langchain4j-anthropic`), JUnit 5, Gson (existing).

---

## Scope Boundaries

**In scope:**
- `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java`
- `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java`
- `SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java` (small refactor)
- `SwissKit/pom.xml` (add deps)

**Out of scope (must not touch):**
- `SwissKitJ-Api/src/main/java/fan/summer/api/ai/*` — plugin contract
- `SwissKit/src/main/java/fan/summer/ai/service/AiServiceImpl.java` — local mode
- `SwissKit/src/main/java/fan/summer/ai/nativejni/*`, `inference/*`, `model/*`, `tensor/*` — local engine
- `SwissKit/src/main/java/fan/summer/ai/tools/FunctionGemmaAdapter.java` — FunctionGemma protocol
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolCallParser.java` — Qwen/Generic local parsers
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolSchemaBuilder.java` — still used by local generic protocol
- `SwissKit/src/main/java/fan/summer/ai/tools/ToolExecutor.java` — reused as-is
- `SwissKit/src/main/java/fan/summer/ui/setting/SwissKitJSettingUi.java` — call site signature unchanged

---

## File Structure

**New files (4):**

| File | Responsibility | LOC |
|---|---|---|
| `SwissKit/src/main/java/fan/summer/ai/adapter/ChatMessageMapper.java` | Bidirectional map: `AiChatMessage` ↔ LangChain4j `ChatMessage` (`UserMessage`, `AiMessage`, `SystemMessage`, `ToolExecutionResultMessage`) | ~80 |
| `SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java` | Convert `AiTool` + `AiToolParam` → LangChain4j `ToolSpecification` (handles `enumValues`, types) | ~60 |
| `SwissKit/src/main/java/fan/summer/ai/adapter/StreamingResponseHandlerBridge.java` | Implements LangChain4j `StreamingChatResponseHandler`; bridges `onPartialResponse`/`onCompleteResponse`/`onError` to `AiStreamCallback` and captures tool-execution requests for the loop | ~80 |
| `SwissKit/src/main/java/fan/summer/ai/adapter/CloudAiConfigProvider.java` | Interface: `getEndpoint()`, `getApiKey()`, `getModelNameInternal()` for direct-call consumers (browser planner) | ~15 |

**Modified files (4):**

| File | Change |
|---|---|
| `SwissKit/pom.xml` | Add LangChain4j deps |
| `SwissKit/.../service/OpenAiService.java` | Replace HTTP/SSE/loop body with LangChain4j-backed implementation; implement `CloudAiConfigProvider` |
| `SwissKit/.../service/AnthropicService.java` | Same as above |
| `SwissKit/.../buildintool/browser/SynchronousChatHelper.java` | Replace `instanceof OpenAiService` with `instanceof CloudAiConfigProvider` |

**Deleted files:** None.

**New test files (3):**

| File | Coverage |
|---|---|
| `SwissKit/src/test/java/fan/summer/ai/adapter/ChatMessageMapperTest.java` | Round-trip mapping for all 4 roles + tool calls + reasoning |
| `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationTest.java` | Param types, enum, required flags |
| `SwissKit/src/test/java/fan/summer/ai/adapter/StreamingResponseHandlerBridgeTest.java` | Token forwarding, completion with tool requests, error path |

---

## Task Breakdown

### Task 1: Add LangChain4j Dependencies

**Files:**
- Modify: `SwissKit/pom.xml` (around line 38, in `<properties>` and `<dependencies>`)

- [ ] **Step 1: Add version property**

In `SwissKit/pom.xml`, after the `<playwright.version>1.49.0</playwright.version>` line in the `<properties>` block, add:

```xml
        <langchain4j.version>1.0.1</langchain4j.version>
```

- [ ] **Step 2: Add the three LangChain4j artifacts**

After the existing Playwright `<dependency>` block (around line 208), add:

```xml
        <!-- LangChain4j: OpenAI + Anthropic cloud backends (replaces hand-rolled HTTP/SSE code) -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-anthropic</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
```

Note: `langchain4j-open-ai` and `langchain4j-anthropic` transitively bring in `langchain4j-core` and OkHttp 4.x. No need to declare `langchain4j-core` explicitly.

- [ ] **Step 3: Verify dependencies resolve**

Run via IDEA Maven tool window (right sidebar): `SwissKit → Dependencies → Reload`. No build errors expected.

Then run from IDEA MCP:
```
mcp__idea__build_project with projectPath=/Users/phoebej/Develop/Java/SwissKitJ, filesToRebuild=["SwissKit/pom.xml"]
```
Expected: success with no compile errors.

- [ ] **Step 4: Commit**

```bash
git add SwissKit/pom.xml
git commit -m "⬆️ deps: add LangChain4j 1.0.1 (openai + anthropic) for cloud AI migration"
```

---

### Task 2: ChatMessageMapper — TDD

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/adapter/ChatMessageMapper.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/adapter/ChatMessageMapperTest.java`

- [ ] **Step 1: Write the failing test (create test file first)**

Create `SwissKit/src/test/java/fan/summer/ai/adapter/ChatMessageMapperTest.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageMapperTest {

    @Test
    void mapsSystemMessage() {
        AiChatMessage src = AiChatMessage.system("You are helpful");
        SystemMessage out = (SystemMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("You are helpful", out.text());
    }

    @Test
    void mapsUserMessage() {
        AiChatMessage src = AiChatMessage.user("Hi");
        UserMessage out = (UserMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("Hi", out.singleText());
    }

    @Test
    void mapsAssistantMessagePlain() {
        AiChatMessage src = AiChatMessage.assistant("Hello!");
        AiMessage out = (AiMessage) ChatMessageMapper.toLc4j(src);
        assertEquals("Hello!", out.text());
        assertTrue(out.toolExecutionRequests() == null || out.toolExecutionRequests().isEmpty());
    }

    @Test
    void mapsAssistantMessageWithToolCalls() {
        AiToolCall call = AiToolCall.of("get_weather", "{\"city\":\"Paris\"}");
        AiChatMessage src = AiChatMessage.assistantWithTools("", List.of(call));

        AiMessage out = (AiMessage) ChatMessageMapper.toLc4j(src);

        assertEquals(1, out.toolExecutionRequests().size());
        ToolExecutionRequest req = out.toolExecutionRequests().get(0);
        assertEquals("get_weather", req.name());
        assertEquals("{\"city\":\"Paris\"}", req.arguments());
        assertNotNull(req.id());
    }

    @Test
    void mapsToolResultMessage() {
        AiChatMessage src = AiChatMessage.toolResult("call_1", "get_weather", "Sunny, 22°C");

        ToolExecutionResultMessage out = (ToolExecutionResultMessage) ChatMessageMapper.toLc4j(src);

        assertEquals("call_1", out.id());
        assertEquals("Sunny, 22°C", out.text());
    }

    @Test
    void roundTripPreservesRoles() {
        // Map AiChatMessage -> LangChain4j -> back to AiChatMessage; role must survive
        AiChatMessage user = AiChatMessage.user("hi");
        ChatMessage lc = ChatMessageMapper.toLc4j(user);
        AiChatMessage back = ChatMessageMapper.fromLc4j(lc);
        assertEquals(AiChatMessage.Role.USER, back.role());
        assertEquals("hi", back.content());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run via IDEA MCP:
```
mcp__idea__execute_run_configuration with filePath="SwissKit/src/test/java/fan/summer/ai/adapter/ChatMessageMapperTest.java", line=10
```
Expected: compile error — `ChatMessageMapper` class does not exist.

- [ ] **Step 3: Implement ChatMessageMapper**

Create `SwissKit/src/main/java/fan/summer/ai/adapter/ChatMessageMapper.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional mapper between SwissKitJ's {@link AiChatMessage} and LangChain4j's
 * {@link ChatMessage} hierarchy.
 *
 * <p>Role mapping:
 * <ul>
 *   <li>{@link AiChatMessage.Role#SYSTEM} ↔ {@link SystemMessage}</li>
 *   <li>{@link AiChatMessage.Role#USER} ↔ {@link UserMessage}</li>
 *   <li>{@link AiChatMessage.Role#ASSISTANT} ↔ {@link AiMessage} (with optional tool requests)</li>
 *   <li>{@link AiChatMessage.Role#TOOL} ↔ {@link ToolExecutionResultMessage}</li>
 * </ul>
 */
public final class ChatMessageMapper {

    private ChatMessageMapper() {}

    /** Converts a SwissKitJ message to its LangChain4j equivalent. */
    public static ChatMessage toLc4j(AiChatMessage src) {
        String text = src.content() == null ? "" : src.content();
        return switch (src.role()) {
            case SYSTEM -> SystemMessage.from(text);
            case USER   -> UserMessage.from(text);
            case ASSISTANT -> {
                if (src.toolCalls() == null || src.toolCalls().isEmpty()) {
                    yield AiMessage.from(text);
                }
                List<ToolExecutionRequest> reqs = new ArrayList<>();
                for (AiToolCall tc : src.toolCalls()) {
                    reqs.add(ToolExecutionRequest.builder()
                        .id(tc.id())
                        .name(tc.name())
                        .arguments(tc.arguments())
                        .build());
                }
                yield AiMessage.from(text, reqs);
            }
            case TOOL -> ToolExecutionResultMessage.from(
                src.toolCallId() == null ? "unknown" : src.toolCallId(),
                src.toolName() == null ? "unknown" : src.toolName(),
                text);
        };
    }

    /** Converts a LangChain4j message back to a SwissKitJ message. */
    public static AiChatMessage fromLc4j(ChatMessage src) {
        if (src instanceof SystemMessage sm) {
            return AiChatMessage.system(sm.text());
        }
        if (src instanceof UserMessage um) {
            return AiChatMessage.user(um.singleText());
        }
        if (src instanceof AiMessage am) {
            if (am.hasToolExecutionRequests()) {
                List<AiToolCall> calls = new ArrayList<>();
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    calls.add(AiToolCall.of(req.name(), req.arguments()));
                }
                return AiChatMessage.assistantWithTools(am.text(), calls);
            }
            return AiChatMessage.assistant(am.text());
        }
        if (src instanceof ToolExecutionResultMessage tm) {
            return AiChatMessage.toolResult(tm.id(), tm.toolName(), tm.text());
        }
        throw new IllegalArgumentException("Unsupported LangChain4j message type: " + src.getClass());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Re-run the test via IDEA MCP.
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/adapter/ChatMessageMapper.java \
        SwissKit/src/test/java/fan/summer/ai/adapter/ChatMessageMapperTest.java
git commit -m "✨ feat(ai): add ChatMessageMapper between AiChatMessage and LangChain4j"
```

---

### Task 3: AiToolToToolSpecification — TDD

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationTest.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolToToolSpecificationTest {

    private static AiTool tool(String name, List<AiToolParam> params) {
        return new AiTool() {
            public String getName() { return name; }
            public String getDescription() { return "desc-" + name; }
            public List<AiToolParam> getParameters() { return params; }
            public AiToolResult execute(Map<String, Object> args) { return AiToolResult.success("ok"); }
        };
    }

    @Test
    void convertsBasicStringParam() {
        AiTool t = tool("t1", List.of(AiToolParam.of("path", "string", "File path")));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);

        assertEquals("t1", spec.name());
        assertEquals("desc-t1", spec.description());
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema.properties().get("path"));
        assertTrue(schema.required().contains("path"));
    }

    @Test
    void convertsOptionalParam() {
        AiTool t = tool("t2", List.of(
            AiToolParam.of("path", "string", "File path"),
            AiToolParam.of("limit", "integer", "Max rows", false)
        ));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();

        assertTrue(schema.required().contains("path"));
        assertFalse(schema.required().contains("limit"));
    }

    @Test
    void convertsEnumParam() {
        AiTool t = tool("t3", List.of(
            AiToolParam.of("mode", "string", "Split mode", true, List.of("SSM", "SCM", "SCPM"))
        ));
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();

        JsonEnumSchema mode = (JsonEnumSchema) schema.properties().get("mode");
        assertEquals(List.of("SSM", "SCM", "SCPM"), mode.enumValues());
    }

    @Test
    void emptyParamsProducesEmptyObjectSchema() {
        AiTool t = tool("t4", List.of());
        ToolSpecification spec = AiToolToToolSpecification.convert(t);
        JsonObjectSchema schema = (JsonObjectSchema) spec.parameters();
        assertNotNull(schema);
        assertTrue(schema.properties().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run via IDEA MCP.
Expected: compile error — `AiToolToToolSpecification` does not exist.

- [ ] **Step 3: Implement AiToolToToolSpecification**

Create `SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a SwissKitJ {@link AiTool} into a LangChain4j {@link ToolSpecification}
 * for use with cloud chat models.
 */
public final class AiToolToToolSpecification {

    private AiToolToToolSpecification() {}

    public static ToolSpecification convert(AiTool tool) {
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();

        for (AiToolParam param : tool.getParameters()) {
            properties.put(param.name(), buildSchema(param));
            if (param.required()) {
                required.add(param.name());
            }
        }

        JsonObjectSchema params = JsonObjectSchema.builder()
            .properties(properties)
            .required(required)
            .build();

        return ToolSpecification.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .parameters(params)
            .build();
    }

    private static JsonSchemaElement buildSchema(AiToolParam param) {
        // Enum wins over type when present
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            return JsonEnumSchema.builder()
                .enumValues(param.enumValues())
                .description(param.description())
                .build();
        }
        JsonStringSchema.Builder builder = JsonStringSchema.builder()
            .description(param.description());
        // LangChain4j's JSON schema subset treats most primitives as "string";
        // integer/boolean are separate types
        return switch (param.type()) {
            case "integer", "number" -> JsonIntegerSchema.builder().description(param.description()).build();
            case "boolean" -> JsonBooleanSchema.builder().description(param.description()).build();
            default -> builder.build();
        };
    }
}
```

Note: `JsonIntegerSchema` is the correct LangChain4j 1.0.x class for both integer and number (no separate `JsonNumberSchema`). If the test reveals otherwise, check `dev.langchain4j.model.chat.request.json.*` for available schema builders and adjust.

- [ ] **Step 4: Run test to verify it passes**

Re-run the test.
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/adapter/AiToolToToolSpecification.java \
        SwissKit/src/test/java/fan/summer/ai/adapter/AiToolToToolSpecificationTest.java
git commit -m "✨ feat(ai): add AiToolToToolSpecification converter (handles enum, types)"
```

---

### Task 4: CloudAiConfigProvider Interface

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/adapter/CloudAiConfigProvider.java`

- [ ] **Step 1: Implement the interface**

Create `SwissKit/src/main/java/fan/summer/ai/adapter/CloudAiConfigProvider.java`:

```java
package fan.summer.zhiflow.ai.adapter;

/**
 * Marker interface for {@link fan.summer.zhiflow.api.ai.AiService} implementations that
 * expose raw cloud-API config (endpoint, API key, model name) for consumers that
 * must bypass the standard {@code chat()} flow.
 *
 * <p>Currently used by the browser-automation planner
 * (see {@code SynchronousChatHelper}), which makes its own direct HTTP call to
 * avoid recursive tool invocation.
 */
public interface CloudAiConfigProvider {

    /** Base URL of the cloud API (no trailing slash). May be empty if unconfigured. */
    String getEndpoint();

    /** API key for authentication. May be empty if unconfigured. */
    String getApiKey();

    /** Model identifier (e.g. {@code "gpt-4o"}). May be empty if unconfigured. */
    String getModelNameInternal();
}
```

- [ ] **Step 2: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/adapter/CloudAiConfigProvider.java
git commit -m "✨ feat(ai): add CloudAiConfigProvider interface for direct-call consumers"
```

---

### Task 5: StreamingResponseHandlerBridge — TDD

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/ai/adapter/StreamingResponseHandlerBridge.java`
- Test: `SwissKit/src/test/java/fan/summer/ai/adapter/StreamingResponseHandlerBridgeTest.java`

- [ ] **Step 1: Write the failing test**

Create `SwissKit/src/test/java/fan/summer/ai/adapter/StreamingResponseHandlerBridgeTest.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamingResponseHandlerBridgeTest {

    /** Captures every callback invocation for assertions. */
    static class CapturingCallback implements AiStreamCallback {
        final List<String> tokens = new ArrayList<>();
        String completeResponse;
        Throwable error;
        AiToolCall toolCall;
        int toolCallCount = 0;

        public void onToken(String fragment) { tokens.add(fragment); }
        public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
            this.completeResponse = fullResponse;
        }
        public void onError(Throwable error) { this.error = error; }
        public void onToolCall(AiToolCall toolCall) { this.toolCall = toolCall; toolCallCount++; }
    }

    @Test
    void forwardsPartialTokens() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onPartialResponse("Hello");
        bridge.onPartialResponse(", ");
        bridge.onPartialResponse("world");

        assertEquals(List.of("Hello", ", ", "world"), cb.tokens);
        assertNull(cb.completeResponse);
    }

    @Test
    void completesWithPlainText() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onPartialResponse("Hi");
        ChatResponse resp = ChatResponse.builder()
            .aiMessage(AiMessage.from("Hi"))
            .build();
        bridge.onCompleteResponse(resp);

        assertEquals("Hi", cb.completeResponse);
    }

    @Test
    void completesWithToolRequests() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        ChatResponse resp = ChatResponse.builder()
            .aiMessage(AiMessage.from("", List.of(
                ToolExecutionRequest.builder()
                    .id("call_1").name("get_weather").arguments("{\"city\":\"Paris\"}")
                    .build()
            )))
            .build();
        bridge.onCompleteResponse(resp);

        // Tool-call round: onComplete is NOT fired; the loop driver reads pendingToolCalls() instead
        assertNull(cb.completeResponse);
        assertEquals(1, bridge.pendingToolCalls().size());
        AiToolCall tc = bridge.pendingToolCalls().get(0);
        assertEquals("get_weather", tc.name());
        assertEquals("{\"city\":\"Paris\"}", tc.arguments());
    }

    @Test
    void forwardsError() {
        CapturingCallback cb = new CapturingCallback();
        StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(cb);

        bridge.onError(new RuntimeException("boom"));

        assertNotNull(cb.error);
        assertEquals("boom", cb.error.getMessage());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run via IDEA MCP.
Expected: compile error — `StreamingResponseHandlerBridge` does not exist.

- [ ] **Step 3: Implement StreamingResponseHandlerBridge**

Create `SwissKit/src/main/java/fan/summer/ai/adapter/StreamingResponseHandlerBridge.java`:

```java
package fan.summer.zhiflow.ai.adapter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiToolCall;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridges LangChain4j's {@link StreamingChatResponseHandler} events to SwissKitJ's
 * {@link AiStreamCallback}, and captures tool-execution requests so the host can
 * drive the multi-round tool loop manually (preserving UI tool-call/tool-result events).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code onPartialResponse} → {@code callback.onToken} (on FX thread)</li>
 *   <li>{@code onCompleteResponse} with no tool requests → {@code callback.onComplete}</li>
 *   <li>{@code onCompleteResponse} with tool requests → populate {@link #pendingToolCalls()};
 *       the host loop reads them, fires {@code callback.onToolCall/onToolResult} via
 *       {@link fan.summer.zhiflow.ai.tools.ToolExecutor}, then re-invokes the model</li>
 *   <li>{@code onError} → {@code callback.onError}</li>
 * </ul>
 */
public final class StreamingResponseHandlerBridge implements StreamingChatResponseHandler {

    private final AiStreamCallback callback;
    private final StringBuilder accumulated = new StringBuilder();
    private volatile List<AiToolCall> pendingToolCalls = List.of();

    public StreamingResponseHandlerBridge(AiStreamCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        if (partialResponse == null || partialResponse.isEmpty()) return;
        accumulated.append(partialResponse);
        String token = partialResponse;
        Platform.runLater(() -> callback.onToken(token));
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        AiMessage ai = completeResponse.aiMessage();
        if (ai.hasToolExecutionRequests()) {
            // Hand off to the host-driven loop — do NOT fire onComplete yet
            List<AiToolCall> calls = new ArrayList<>();
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                calls.add(AiToolCall.of(req.name(), req.arguments()));
            }
            this.pendingToolCalls = Collections.unmodifiableList(calls);
            return;
        }
        // Final response: fire onComplete on FX thread
        String full = ai.text() == null ? accumulated.toString() : ai.text();
        int tokens = estimateTokens(full);
        Platform.runLater(() -> callback.onComplete(full, tokens, 0));
    }

    @Override
    public void onError(Throwable error) {
        Platform.runLater(() -> callback.onError(error));
    }

    /** Tool calls captured by the most recent {@link #onCompleteResponse}; empty if it was a final response. */
    public List<AiToolCall> pendingToolCalls() {
        return pendingToolCalls;
    }

    /** Resets accumulator and pending tool calls for the next round. Called by the loop driver between rounds. */
    public void resetForNextRound() {
        accumulated.setLength(0);
        pendingToolCalls = List.of();
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Re-run the test.
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/adapter/StreamingResponseHandlerBridge.java \
        SwissKit/src/test/java/fan/summer/ai/adapter/StreamingResponseHandlerBridgeTest.java
git commit -m "✨ feat(ai): add StreamingResponseHandlerBridge (LangChain4j → AiStreamCallback)"
```

---

### Task 6: Re-implement OpenAiService with LangChain4j

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java` (full rewrite — currently 380 lines, target ~150 lines)

This task replaces the entire HTTP/SSE/tool-loop body. The new implementation must:
1. Implement `AiService` AND `CloudAiConfigProvider`
2. Build `OpenAiStreamingChatModel` lazily on first `chat()` call
3. Manually drive the multi-round tool loop using `StreamingResponseHandlerBridge` + `ToolExecutor.executeAndFeed`
4. Preserve `MAX_TOOL_ROUNDS = 5` cap
5. Preserve `configure(String, String, String)` API
6. Preserve `getEndpoint()/getApiKey()/getModelNameInternal()` accessors

- [ ] **Step 1: Replace the entire file contents**

Replace `SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java` with:

```java
package fan.summer.zhiflow.ai.service;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import fan.summer.zhiflow.ai.adapter.AiToolToToolSpecification;
import fan.summer.zhiflow.ai.adapter.ChatMessageMapper;
import fan.summer.zhiflow.ai.adapter.CloudAiConfigProvider;
import fan.summer.zhiflow.ai.adapter.StreamingResponseHandlerBridge;
import fan.summer.zhiflow.ai.tools.ToolExecutor;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AiService} implementation for OpenAI-compatible chat completion APIs,
 * backed by LangChain4j's {@link OpenAiStreamingChatModel}.
 *
 * <p>Supports streaming responses, tool calling, and multi-round conversations
 * (up to {@value #MAX_TOOL_ROUNDS} rounds). Does not support local model loading.
 *
 * @see AiService
 * @see CloudAiConfigProvider
 */
public class OpenAiService implements AiService, CloudAiConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private volatile OpenAiStreamingChatModel model;
    private final AtomicBoolean generating = new AtomicBoolean(false);

    private String endpoint;
    private String apiKey;
    private String modelName;

    public OpenAiService() {}

    /**
     * Configures the endpoint, API key, and model name for this service.
     * Causes any previously built model to be rebuilt on the next {@code chat()} call.
     */
    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = null; // force rebuild on next chat()
    }

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for OpenAI mode");
    }

    @Override public void unloadModel() {
        model = null;
    }

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating.get(); }

    // ── CloudAiConfigProvider ─────────────────────────────────
    @Override public String getEndpoint() { return endpoint; }
    @Override public String getApiKey() { return apiKey; }
    @Override public String getModelNameInternal() { return modelName; }

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
            throw new AiServiceException("OpenAI service not configured");
        }
        if (!generating.compareAndSet(false, true)) {
            throw new AiServiceException("Generation already in progress");
        }

        try {
            OpenAiStreamingChatModel m = getOrCreateModel(temperature, topP, maxTokens);
            List<AiTool> tools = AiServiceProvider.getTools();
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = tools.isEmpty()
                ? null
                : tools.stream().map(AiToolToToolSpecification::convert).toList();

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(callback);
                List<dev.langchain4j.data.message.ChatMessage> lcMessages = new ArrayList<>();
                for (AiChatMessage msg : history) {
                    lcMessages.add(ChatMessageMapper.toLc4j(msg));
                }

                if (toolSpecs != null) {
                    m.chat(lcMessages, toolSpecs, bridge);
                } else {
                    m.chat(lcMessages, bridge);
                }

                List<AiToolCall> calls = bridge.pendingToolCalls();
                if (calls.isEmpty()) {
                    return; // final response already fired onComplete via bridge
                }
                // Tool round: execute, append results to history, loop again
                ToolExecutor.executeAndFeed(calls, history, callback);
            }
            // Hit MAX_TOOL_ROUNDS: fire a final onComplete with whatever we have
            String msg = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
            log.warn(msg);
            Platform.runLater(() -> callback.onComplete(msg, 0, 0));
        } catch (Exception e) {
            log.error("OpenAI chat failed", e);
            Platform.runLater(() -> callback.onError(e));
            throw new AiServiceException("OpenAI chat failed: " + e.getMessage(), e);
        } finally {
            generating.set(false);
        }
    }

    @Override
    public void cancelGeneration() {
        // LangChain4j 1.0.x doesn't expose mid-stream cancellation on streaming models.
        // The stream terminates when the bridge goes out of scope; flag is cleared in finally.
        log.debug("cancelGeneration() requested; current LangChain4j streaming model does not support mid-stream abort");
    }

    @Override
    public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }

    @Override
    public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }

    @Override
    public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    // ── Internal ──────────────────────────────────────────────

    private OpenAiStreamingChatModel getOrCreateModel(float temperature, float topP, int maxTokens) {
        OpenAiStreamingChatModel m = model;
        if (m != null) return m;
        synchronized (this) {
            if (model == null) {
                model = OpenAiStreamingChatModel.builder()
                    .baseUrl(endpoint)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature((double) temperature)
                    .topP((double) topP)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(120))
                    .build();
            }
            return model;
        }
    }
}
```

Note: the exact `OpenAiStreamingChatModel.builder()` setter names (`topP` vs `topP()`, `maxTokens` vs `maxCompletionTokens()`) vary between LangChain4j patch versions. If the build fails, check the IDE's auto-completion for `OpenAiStreamingChatModel.Builder` and adjust. Specifically in 1.0.x:
- `maxTokens(int)` may be deprecated in favor of `maxCompletionTokens(int)` for newer OpenAI APIs — keep `maxTokens` for now since it works for OpenAI-compatible providers.

- [ ] **Step 2: Verify build compiles**

Run via IDEA MCP:
```
mcp__idea__build_project with projectPath=/Users/phoebej/Develop/Java/SwissKitJ
```
Expected: build succeeds with no errors. If specific LangChain4j method signatures differ from those used above, fix them now (do not proceed to commit with red code).

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/OpenAiService.java
git commit -m "♻️ refactor(ai): re-implement OpenAiService on LangChain4j (~380 LOC → ~150 LOC)"
```

---

### Task 7: Re-implement AnthropicService with LangChain4j

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java` (full rewrite — currently 350 lines, target ~150 lines)

- [ ] **Step 1: Replace the entire file contents**

Replace `SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java` with:

```java
package fan.summer.zhiflow.ai.service;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import fan.summer.zhiflow.ai.adapter.AiToolToToolSpecification;
import fan.summer.zhiflow.ai.adapter.ChatMessageMapper;
import fan.summer.zhiflow.ai.adapter.CloudAiConfigProvider;
import fan.summer.zhiflow.ai.adapter.StreamingResponseHandlerBridge;
import fan.summer.zhiflow.ai.tools.ToolExecutor;
import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.ui.setting.SwissKitJSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AiService} implementation for Anthropic's Messages API,
 * backed by LangChain4j's {@link AnthropicStreamingChatModel}.
 *
 * <p>Supports streaming responses, tool calling, and multi-round conversations
 * (up to {@value #MAX_TOOL_ROUNDS} rounds). Does not support local model loading.
 */
public class AnthropicService implements AiService, CloudAiConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicService.class);
    private static final int MAX_TOOL_ROUNDS = 5;

    private volatile AnthropicStreamingChatModel model;
    private final AtomicBoolean generating = new AtomicBoolean(false);

    private String endpoint;
    private String apiKey;
    private String modelName;

    public AnthropicService() {}

    public void configure(String endpoint, String apiKey, String modelName) {
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.model = null;
    }

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for Anthropic mode");
    }

    @Override public void unloadModel() { model = null; }

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating.get(); }

    @Override public String getEndpoint() { return endpoint; }
    @Override public String getApiKey() { return apiKey; }
    @Override public String getModelNameInternal() { return modelName; }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, SwissKitJSettingUi.getAiTemperature(), SwissKitJSettingUi.getAiTopP(),
             SwissKitJSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) {
            throw new AiServiceException("Anthropic service not configured");
        }
        if (!generating.compareAndSet(false, true)) {
            throw new AiServiceException("Generation already in progress");
        }

        try {
            AnthropicStreamingChatModel m = getOrCreateModel(temperature, topP, maxTokens);
            List<AiTool> tools = AiServiceProvider.getTools();
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = tools.isEmpty()
                ? null
                : tools.stream().map(AiToolToToolSpecification::convert).toList();

            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                StreamingResponseHandlerBridge bridge = new StreamingResponseHandlerBridge(callback);
                List<dev.langchain4j.data.message.ChatMessage> lcMessages = new ArrayList<>();
                for (AiChatMessage msg : history) {
                    lcMessages.add(ChatMessageMapper.toLc4j(msg));
                }

                if (toolSpecs != null) {
                    m.chat(lcMessages, toolSpecs, bridge);
                } else {
                    m.chat(lcMessages, bridge);
                }

                List<AiToolCall> calls = bridge.pendingToolCalls();
                if (calls.isEmpty()) {
                    return;
                }
                ToolExecutor.executeAndFeed(calls, history, callback);
            }
            String msg = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
            log.warn(msg);
            Platform.runLater(() -> callback.onComplete(msg, 0, 0));
        } catch (Exception e) {
            log.error("Anthropic chat failed", e);
            Platform.runLater(() -> callback.onError(e));
            throw new AiServiceException("Anthropic chat failed: " + e.getMessage(), e);
        } finally {
            generating.set(false);
        }
    }

    @Override
    public void cancelGeneration() {
        log.debug("cancelGeneration() requested; current LangChain4j streaming model does not support mid-stream abort");
    }

    @Override public void registerTool(AiTool tool) { AiServiceProvider.registerTool(tool); }
    @Override public void unregisterTool(String toolName) { AiServiceProvider.unregisterTool(toolName); }
    @Override public List<AiTool> getTools() { return AiServiceProvider.getTools(); }

    private AnthropicStreamingChatModel getOrCreateModel(float temperature, float topP, int maxTokens) {
        AnthropicStreamingChatModel m = model;
        if (m != null) return m;
        synchronized (this) {
            if (model == null) {
                AnthropicStreamingChatModel.Builder<?> b = AnthropicStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature((double) temperature)
                    .topP((double) topP)
                    .maxTokens(maxTokens)
                    .timeout(Duration.ofSeconds(120));
                if (!endpoint.isBlank() && !endpoint.equals("https://api.anthropic.com")) {
                    b.baseUrl(endpoint);
                }
                model = b.build();
            }
            return model;
        }
    }
}
```

Note: `AnthropicStreamingChatModel.builder()` is generic (`<T>`); check IDE auto-completion for the correct generic call chain. The above uses the raw builder pattern — if generics require a type witness, add `<Object>` or whatever the IDE suggests.

- [ ] **Step 2: Verify build compiles**

Run via IDEA MCP.
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/service/AnthropicService.java
git commit -m "♻️ refactor(ai): re-implement AnthropicService on LangChain4j (~350 LOC → ~150 LOC)"
```

---

### Task 8: Update SynchronousChatHelper to use CloudAiConfigProvider

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java:3,54-62`

- [ ] **Step 1: Replace the OpenAiService import and cast**

In `SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java`, change:

- Line 3: replace `import fan.summer.zhiflow.ai.service.OpenAiService;` with `import fan.summer.zhiflow.ai.adapter.CloudAiConfigProvider;`
- Lines 53-58: replace the `instanceof OpenAiService openAiService` block with `instanceof CloudAiConfigProvider config`:

```java
        // Cloud-only: planner needs a service that exposes raw API config for a direct HTTP call
        if (!(service instanceof CloudAiConfigProvider config)) {
            log.warn("Browser planner requires a cloud AI backend exposing raw API config, got: {}",
                     service.getClass().getSimpleName());
            return null;
        }

        String endpoint = config.getEndpoint();
        String apiKey = config.getApiKey();
        String model = config.getModelNameInternal();
```

- [ ] **Step 2: Verify build compiles**

Run via IDEA MCP.
Expected: build succeeds. `SynchronousChatHelper` no longer imports or references `OpenAiService`.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java
git commit -m "♻️ refactor(ai): SynchronousChatHelper uses CloudAiConfigProvider (decoupled from OpenAiService)"
```

---

### Task 9: Manual Regression Test

Cloud services cannot be unit-tested without real API keys and network calls. This task is a manual test checklist — execute it before tagging the release.

**Prerequisites:**
- Working OpenAI API key (or OpenAI-compatible endpoint)
- Working Anthropic API key

- [ ] **Step 1: Configure OpenAI mode in Settings**

1. Launch the app: `java -jar SwissKit/target/SwissKitJ-3.0.1.jar`
2. Settings → AI → Mode: `openai`
3. Fill in endpoint (e.g. `https://api.openai.com`), API key, model (e.g. `gpt-4o-mini`)
4. Save settings

- [ ] **Step 2: Verify plain chat**

1. Open AI tool
2. Send: "Say hello in 5 words"
3. Expected: streamed response appears token-by-token, completes within 5s
4. ✅ If failed: check logs at `.zhiflow/logs/zhiflow.log` for LangChain4j errors

- [ ] **Step 3: Verify tool calling (single round)**

1. Send: "What's the SHA-256 hash of 'hello'?"
2. Expected: model calls `hash_compute` (or similar) tool once, returns final answer
3. UI should show "Calling tool: hash_compute" and "Tool result: success" indicators
4. ✅ If no tool called: verify built-in AI tools are registered (check log for `BuiltinAiToolRegistrar`)

- [ ] **Step 4: Verify multi-round tool loop**

1. Send a query that requires analyze → configure → execute (e.g. Excel workflow)
2. Expected: multiple tool calls in sequence, each with UI feedback, final response synthesizes results
3. ✅ If only first tool executes: check `MAX_TOOL_ROUNDS` cap or bridge reset logic

- [ ] **Step 5: Switch to Anthropic mode and repeat Steps 2-4**

1. Settings → AI → Mode: `anthropic`
2. Endpoint: `https://api.anthropic.com`, API key, model: `claude-3-5-sonnet-20241022`
3. Repeat plain chat, single tool, multi-tool tests

- [ ] **Step 6: Verify browser automation planner still works**

1. Open browser automation tool
2. Trigger a planner-driven action (e.g. "search for cats on Google")
3. Expected: planner makes a direct API call (no tools injected), returns JSON action
4. Check logs for any "Browser planner only supports..." warnings — there should be NONE
5. ✅ If "only supports" warning appears: `instanceof CloudAiConfigProvider` check is failing — verify both new services implement the interface

- [ ] **Step 7: Verify connection test buttons**

1. Settings → AI → OpenAI tab → "Test connection" button
2. Settings → AI → Anthropic tab → "Test connection" button
3. Expected: both succeed (these go through `configure(...)` then `isReady()`)
4. ✅ If failed: verify the `testConnection()` calls in `SwissKitJSettingUi.java:749,799` still work with the new classes

- [ ] **Step 8: Record results**

Append to `docs/superpowers/plans/2026-06-21-langchain4j-cloud-migration.md` under a new `## Regression Test Results` section:
- Date tested
- OpenAI: pass/fail per step
- Anthropic: pass/fail per step
- Browser planner: pass/fail

- [ ] **Step 9: Commit**

```bash
git add docs/superpowers/plans/2026-06-21-langchain4j-cloud-migration.md
git commit -m "📝 docs: record LangChain4j migration regression test results"
```

---

### Task 10: CHANGELOG and Release Prep

**Files:**
- Modify: `CHANGELOG.md` (root)
- Modify: `docs/changelog.md`
- Modify: `docs/zh/changelog.md`
- Modify: `SwissKit/pom.xml` + `SwissKitJ-Api/pom.xml` + `<zhiflow.api.version>` (3.0.1 → 3.1.0)
- Modify: jar-path references in `README.md`, `docs/`, `docs/zh/` (use `/docs-updater` skill)

- [ ] **Step 1: Invoke docs-updater skill for version bump**

Invoke `docs-updater` with args: `bump 3.0.1 -> 3.1.0, sync docs/README/CHANGELOG with the LangChain4j cloud-mode migration`. The skill handles all pom.xml bumps and version refs.

- [ ] **Step 2: Add CHANGELOG entry for 3.1.0**

Insert at top of `CHANGELOG.md`, `docs/changelog.md`, `docs/zh/changelog.md`:

```markdown
## [3.1.0] — LangChain4j Cloud Mode Migration

**v3.1.0** — 2026-XX-XX

### ♻️ Refactor

- **Cloud AI Backends Migrated to LangChain4j**: `OpenAiService` and `AnthropicService` now wrap `OpenAiStreamingChatModel` and `AnthropicStreamingChatModel`; eliminates ~700 lines of hand-rolled HTTP/SSE/tool-loop code
- Tool schema generation, message mapping, and stream bridging extracted into reusable adapters under `fan.summer.zhiflow.ai.adapter`
- `SynchronousChatHelper` (browser planner) decoupled from concrete `OpenAiService` via new `CloudAiConfigProvider` interface — Anthropic now also usable as browser planner backend

### ✨ New

- New adapter package: `fan.summer.zhiflow.ai.adapter` (`ChatMessageMapper`, `AiToolToToolSpecification`, `StreamingResponseHandlerBridge`, `CloudAiConfigProvider`)
- New unit tests for all adapters (3 test files, ~13 test cases)

### ⬆️ Dependencies

- `dev.langchain4j:langchain4j-open-ai:1.0.1`
- `dev.langchain4j:langchain4j-anthropic:1.0.1`

### ⚠️ Known Behavior Changes

- `cancelGeneration()` on cloud backends is now best-effort (LangChain4j 1.0.x does not expose mid-stream cancellation); the in-progress flag is still cleared
- Browser planner (SynchronousChatHelper) now supports both OpenAI-compatible and Anthropic backends (previously OpenAI-only)
```

For the Chinese version (`docs/zh/changelog.md`), translate the same content.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md docs/changelog.md docs/zh/changelog.md SwissKit/pom.xml SwissKitJ-Api/pom.xml \
        README.md docs/README.md docs/_coverpage.md docs/getting-started.md docs/architecture.md \
        docs/zh/README.md docs/zh/_coverpage.md docs/zh/getting-started.md docs/zh/architecture.md
git commit -m "📝 docs: bump 3.0.1 → 3.1.0 for LangChain4j cloud migration release"
```

---

## Self-Review Notes

**Spec coverage check:**
- ✅ Replace OpenAI HTTP/SSE/tool-loop code → Task 6
- ✅ Replace Anthropic HTTP/SSE/tool-loop code → Task 7
- ✅ Preserve `AiService`/`AiTool` plugin API → out of scope by design (no API module changes)
- ✅ Preserve local mode (llama.cpp + GGUF + FunctionGemma) → out of scope by design
- ✅ Handle `SynchronousChatHelper` instanceof → Task 8
- ✅ Update CHANGELOG for 3.1.0 → Task 10

**Type consistency check:**
- `StreamingResponseHandlerBridge.pendingToolCalls()` returns `List<AiToolCall>` — matches Task 6/7 loop driver
- `ChatMessageMapper.toLc4j` returns `ChatMessage` (LangChain4j root type) — consumed correctly by `m.chat(lcMessages, ...)`
- `AiToolToToolSpecification.convert(AiTool)` returns `ToolSpecification` — consumed correctly by both cloud services
- `CloudAiConfigProvider` method names match existing `OpenAiService` accessor names — `SynchronousChatHelper` refactor in Task 8 uses identical call signatures

**Placeholder scan:** None — every step contains concrete code or commands.

---

## Risk Notes for Implementation

1. **LangChain4j API drift**: The exact builder method names (`maxTokens` vs `maxCompletionTokens`, generic signatures on `AnthropicStreamingChatModel.Builder`) vary across patch versions. Task 6/7 code is written for 1.0.1 stable; if IDEA shows red, use IDE auto-completion rather than guessing.

2. **OkHttp transitive dep**: LangChain4j's OpenAI/Anthropic modules require OkHttp 4.x. This adds ~800KB to the fat JAR. Should not conflict with existing deps but verify via IDEA dependency analyzer.

3. **No mid-stream cancellation**: LangChain4j 1.0.x does not expose abort on streaming models. UI cancel button on cloud mode will be a no-op (only the flag clears). If this is unacceptable, upgrade to LangChain4j 1.1.x which may have added cancellation (verify before upgrading — 1.1.x may require Java 21+, which is already met).

4. **`OpenAiStreamingChatModel` constructor for OpenAI-compatible providers (e.g. DeepSeek, Moonshot)**: Set `.baseUrl(...)` to the provider's API root. The existing `configure()` strips trailing slashes, which matches LangChain4j's expectation.
