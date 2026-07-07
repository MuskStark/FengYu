# Phase 1: AI Strangler — LangChain4j + Local Stack → Spring AI 2.0 + Ollama

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the entire `fan.summer.zhiflow.ai` inference stack (LangChain4j cloud + custom GGUF/JNI/worker local) with Spring AI 2.0 (OpenAI/Anthropic cloud) + Ollama (local), embedded in the existing JavaFX process via Spring Boot 4 — without touching the plugin-facing `ChatBackend` / `AiStreamCallback` / `AiTool` contract or any JavaFX UI code.

**Architecture:** Strangler-fig, "keep the shell, swap the core". The `ChatBackend` interface (`ZhiFlow-Api`) stays as the shell that JavaFX consumers (`AiChatPlugin`) call. New `SpringAiCloudBackend` and `OllamaLocalBackend` implement `ChatBackend` but delegate inference to Spring AI `ChatModel` beans. A minimal embedded Spring Boot context (`WebApplicationType.NONE`, `headless(false)`) provides DI + the `ChatModel` beans; the context is bootstrapped from `ZhiFlowApp` and beans are looked up imperatively via `ctx.getBean(...)`. Plugin tools (`AiTool`) are adapted to Spring AI's `ToolCallback` SPI so the existing `AiTool.execute(Map) → AiToolResult` contract is unchanged. After the new backends are green, the entire local inference/GGUF/JNI/worker/parsers subtree and the LangChain4j adapters are deleted wholesale.

**Tech Stack:** Java 21 (compile) / 17+ (runtime), JavaFX 21.0.2, Spring Boot 4.1.0, Spring Framework 7, Spring AI 2.0.0 GA (BOM), `spring-ai-openai` + `spring-ai-anthropic` + `spring-ai-ollama` (non-starter, manual `@Bean`), Ollama (external local runtime), JUnit 5.10.2, Gson (existing).

---

## Global Constraints

These apply to every task. Copy verbatim; do not deviate without an explicit spec change.

> **⚠️ Codebase drift note (added 2026-07-07 at execution time).** This plan was authored 2026-07-06, before the `SwissKit → ZhiFlow` module + `fan.summer → fan.summer.zhiflow` package rename landed. All paths/names below have been mechanically updated to the current tree:
> - Module dir: `ZhiFlow/` (main app), `ZhiFlow-Api/` (contract). Stray untracked `SwissKit/` + `SwissKitJ-Api/` dirs are pre-rename leftovers — **ignore them; edit only the git-tracked `ZhiFlow*` modules.**
> - App class: `fan.summer.zhiflow.app.ZhiFlowApp`; settings: `fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi`; launcher: `fan.summer.zhiflow.Launcher`. Contract package: `fan.summer.zhiflow.api.ai.*` (the older `fan.summer.api.ai.*` tree in `ZhiFlow-Api` is a dead leftover with 0 app references — leave it).
> - **Maven:** there is NO system Maven and modules use standalone POMs (root `pom.xml` is an aggregator). Do **not** run `mvn -pl ... -o` in a shell — it will fail. Run every build/compile/test via **IntelliJ IDEA's Maven** (Maven tool window) or the IDEA MCP tools (`mcp__idea__build_project`, `mcp__idea__get_file_problems`, `mcp__idea__execute_terminal_command` with the bundled-maven path). The `mvn -pl ZhiFlow ...` command lines in each task are the *intent* (which module, compile vs test, which test) — translate them to the IDEA equivalent.
> - **Plan gap:** Tasks 9 & 10 call `AiSpringContext.getBean("ollamaChatModel", ChatModel.class)` (two-arg by-name lookup), but Task 2's `AiSpringContext` only defines `getBean(Class)`. Task 2 must also add a `public static <T> T getBean(String name, Class<T> type)` overload delegating to `getContext().getBean(name, type)`.

- **Spring Boot**: `4.1.0` (GA). Spring Framework 7. Java baseline 17 min / 21 recommended — current project compiles on 21, runs on 17+. **Compatible.**
- **Spring AI**: `2.0.0` GA (released 2026-06-12). Targets Spring Boot 4.0/4.1 + Spring Framework 7. Use the `spring-ai-bom` for version alignment. **Do NOT use Spring AI 1.x** — it targets Spring Boot 3.5 and will not align.
- **Embedded context**: `WebApplicationType.NONE` + `headless(false)` + base `spring-boot-starter` (NO `spring-boot-starter-web`). JavaFX needs `headless=false` (AWT/Java2D). The context lives in the same process as JavaFX.
- **Non-starter Spring AI artifacts**: use `spring-ai-openai`, `spring-ai-anthropic`, `spring-ai-ollama` (manual `@Bean` configuration), NOT the `*-starter-model-*` artifacts. Rationale: starter auto-configuration under `WebApplicationType.NONE` has historical uncertainty (spring-ai#1066, M1-era); manual beans are fully doc-verified and read config from H2 at runtime, not `application.properties`.
- **Ollama is an external runtime**: the user must install/run `ollama serve` and `ollama pull qwen3:4b`. ZhiFlow no longer ships a model runtime. A bundled `libllama_jni-*.dylib`, the C++ JNI bridge, and the worker process are **deleted**.
- **Untouched (do NOT modify)**: `ZhiFlow-Api/**` (the entire plugin contract — `ChatBackend`, `AiStreamCallback`, `AiTool`, `AiToolParam`, `AiToolResult`, `AiToolCall`, `AiChatMessage`, `AiServiceProvider`, `AiServiceException`), and all JavaFX UI (`fan.summer.zhiflow.ui.*`, `fan.summer.zhiflow.buildintool.*` view code). The `AiChatPlugin` consumer must compile and behave identically before/after.
- **API signatures verified against `v2.0.0` git tag** (see "Verified API Cheat-Sheet" below). Where an exact signature could not be locked from source, the task is marked **SPIKE** and gives a fallback path that does not depend on the uncertain API.
- **Commit cadence**: one commit per step where code changes. Conventional-commit prefixes (`feat`, `refactor`, `chore`, `test`, `docs`).
- **Branch**: this plan is executed on `v3.2.0` (current) or a dedicated migration branch off it. Do NOT work on `main`.

---

## Verified API Cheat-Sheet (Spring AI 2.0.0, tag `v2.0.0`)

These signatures were read from `raw.githubusercontent.com/spring-projects/spring-ai/v2.0.0/...`. Code in this plan uses ONLY these. Anything not in this list is marked **SPIKE** in its task.

```java
// ── ChatModel / streaming ───────────────────────────────────────────
package org.springframework.ai.chat.model;
interface ChatModel           { ChatResponse call(Prompt p); /* + convenience overloads */ }
interface StreamingChatModel  { Flux<ChatResponse> stream(Prompt p); }
// OpenAiChatModel / AnthropicChatModel / OllamaChatModel implement BOTH.
// Manual bean shape (verified, docs.spring.io reference "Manual Configuration"):
//   OpenAiChatModel.builder().openAiApi(api).defaultOptions(opts).build();
//   OllamaChatModel.builder().ollamaApi(api).options(opts).build();
//   OpenAiApi.builder().baseUrl(...).apiKey(...).build();
//   OllamaApi.builder().baseUrl(...).build();

// ── Prompt / messages ───────────────────────────────────────────────
package org.springframework.ai.chat.prompt;
class Prompt { Prompt(List<Message> messages); Prompt(List<Message>, ChatOptions opts); }

package org.springframework.ai.chat.messages;
interface Message { String getText(); MessageType getMessageType(); Map<String,Object> getMetadata(); }
final class SystemMessage   implements Message { SystemMessage(String text); }
final class UserMessage     implements Message { UserMessage(String text); }
final class AssistantMessage implements Message {
    AssistantMessage(String text);
    AssistantMessage(String text, Map<String,Object> properties, List<ToolCall> toolCalls);
    // builder() also available
    List<ToolCall> getToolCalls();
    boolean hasToolCalls();
    record ToolCall(String id, String type, String name, String arguments) { }
}
final class ToolResponseMessage implements Message {
    ToolResponseMessage(List<ToolResponse> responses);
    record ToolResponse(String id, String name, String responseMessage) { }
}
enum MessageType { SYSTEM, USER, ASSISTANT, TOOL }

// ── ChatResponse structure ──────────────────────────────────────────
package org.springframework.ai.chat.model;
class ChatResponse {
    List<Generation> getResults();
    Generation getResult();          // primary
    ChatResponseMetadata getMetadata(); // token usage etc. — appears in FINAL chunk on streams
}
class Generation { AssistantMessage getOutput(); }

// ── Tool SPI ────────────────────────────────────────────────────────
package org.springframework.ai.tool;
interface ToolCallback {
    ToolDefinition getToolDefinition();
    String call(String toolInput);                              // no context
    String call(String toolInput, ToolContext toolContext);     // with context
}
package org.springframework.ai.chat.model;
final class ToolContext { ToolContext(Map<String,Object> context); } // standalone, NOT nested

package org.springframework.ai.tool.definition;
interface ToolDefinition {
    String name();
    String description();
    String inputSchema();   // JSON-schema STRING (not an object)
}
final class DefaultToolDefinition {
    static Builder builder();
    interface Builder {
        Builder name(String); Builder description(String); Builder inputSchema(String);
        ToolDefinition build();
    }
}
```

**Ollama thinking**: `OllamaChatOptions` exposes a `think` field (auto-on for reasoning models). The metadata key carrying thinking content per-chunk in streaming mode **was not locked from source** → Task 8 is a **SPIKE** with a documented fallback (drop thinking surfacing in Phase 1; surface it in a follow-up once the key is confirmed).

**Tool loop**: Spring AI 2.0 has `ToolCallingManager` / `ToolCallingChatOptions` (`internalToolExecutionEnabled`), but the exact FQN + `executeToolCalls` method name **was not locked from source**. This plan therefore drives the tool loop **manually** (read `getToolCalls()` off the response, call `ToolCallback.call(json)` directly, build a `ToolResponseMessage`, re-stream). This is the same shape as the existing `CloudChatBackend.runToolLoop` and depends ONLY on verified signatures.

---

## Scope Boundaries

**In scope (Phase 1):**
- Build config: introduce SB4 BOM + Spring AI BOM + the three non-starter artifacts; remove LangChain4j deps.
- Embedded Spring context bootstrap (`AiSpringContext`).
- New `ChatBackend` implementations: `SpringAiCloudBackend`, `OllamaLocalBackend`.
- Adapters: `AiToolCallback` (`AiTool` → `ToolCallback`), `MessageMapper` (`AiChatMessage` ↔ Spring AI `Message`).
- Cloud config bean (`OpenAiChatModel` / `AnthropicChatModel`).
- Ollama config bean (`OllamaChatModel`) + connection test.
- Rewire `ZhiFlowApp.initializeAiBackend()` + `ZhiFlowSettingUi` mode-switch to build the new backends.
- Delete: `fan.summer.zhiflow.ai.{service.CloudChatBackend,service.LocalChatBackend,adapter.*,tools.*,inference.*,model.*,tensor.*,nativejni.*,util.JsonHelper(if unused elsewhere)}`, `src/main/cpp/`, `src/main/resources/native/`, the `langchain4j-*` deps.
- Delete tests that exercised deleted code; add tests for the new adapters/backends.

**Out of scope (deferred to later phases):**
- Vue / Electron / REST boundary (Phase 2–4).
- Plugin SPI changes (Phase 4). `ZhiFlowPlugin.createView()` stays JavaFX.
- `ZhiFlowSettingUi` UI layout changes. Only its AI-backend instantiation callsites change; the controls themselves stay.
- Migration of the `ai.local.backend` setting semantics (java/native) — the setting key is repurposed to an Ollama URL but the UI control is left for a later polish pass.

---

## File Structure

**New files (in `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/`):**

| File | Responsibility | ~LOC |
|---|---|---|
| `spring/AiSpringContext.java` | Bootstraps the embedded SB4 context (`WebApplicationType.NONE`, `headless(false)`), exposes `getContext()` / `getBean(Class)` / `close()`. Static holder. | 70 |
| `spring/AiConfigProperties.java` | Reads AI config from H2 via existing `AiConfigService` into a plain record, used as a `@Bean` source. | 50 |
| `spring/ChatModelConfig.java` | `@Configuration` defining `openAiChatModel`, `anthropicChatModel`, `ollamaChatModel` `@Bean`s (manual builder shape). | 110 |
| `adapter/MessageMapper.java` | Bidirectional `AiChatMessage` ↔ Spring AI `Message` (System/User/Assistant/Tool). Replaces LC4j `ChatMessageMapper`. | 90 |
| `adapter/AiToolCallback.java` | `implements ToolCallback`; wraps a plugin `AiTool`, builds `ToolDefinition` (JSON-schema from `AiToolParam`), `call()` delegates to `aiTool.execute(map).output()`. | 90 |
| `adapter/ToolSchemaJson.java` | Builds a JSON-schema **string** for `DefaultToolDefinition.inputSchema()` from `List<AiToolParam>`. | 60 |
| `service/SpringAiCloudBackend.java` | `ChatBackend` for OpenAI/Anthropic. Streams via `ChatModel.stream(Prompt)`; manual tool loop firing `AiStreamCallback` events on the FX thread. | 220 |
| `service/OllamaLocalBackend.java` | `ChatBackend` for local Ollama. Same shape as cloud; "load model" = pick Ollama model name; thinking surfaced best-effort. | 200 |

**New test files:**

| File | Coverage |
|---|---|
| `adapter/MessageMapperTest.java` | Round-trip all 4 roles + tool calls + tool results. |
| `adapter/AiToolCallbackTest.java` | Schema generation, arg parsing, execute dispatch, error path. |
| `adapter/ToolSchemaJsonTest.java` | Primitive/array/enum/required param shapes. |
| `service/SpringAiCloudBackendToolLoopTest.java` | Tool-loop round semantics, FX-thread callback events, MAX_TOOL_ROUNDS. Uses a stub `ChatModel`. |
| `service/OllamaLocalBackendConnectionTest.java` | Connection-probe logic against a fake Ollama HTTP endpoint (MockWebServer/ServerSocket). |

**Modified files:**

| File | Change |
|---|---|
| `ZhiFlow/pom.xml` | Add SB4 + Spring AI BOM + 3 artifacts + `spring-boot-starter`; remove `langchain4j-open-ai`, `langchain4j-anthropic`. |
| `ZhiFlow/.../app/ZhiFlowApp.java` | `start()`: bootstrap `AiSpringContext` after DB init; `stop()`: `AiSpringContext.close()`. `initializeAiBackend()`: build new backends. |
| `ZhiFlow/.../ui/setting/ZhiFlowSettingUi.java` | Mode-switch call sites: replace `CloudChatBackend.openAi(...)`/`anthropic(...)`/`new LocalChatBackend(...)` with `SpringAiCloudBackend`/`OllamaLocalBackend` construction. **No UI layout change.** |
| `ZhiFlow-Api` | **NO SOURCE CHANGES.** (Only doc-comment mention of impl class names may be updated; behaviorally identical.) |

**Deleted files (entire subtrees):**
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/CloudChatBackend.java` (old LC4j impl)
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/LocalChatBackend.java`
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/` (entire dir: `AiToolToToolSpecification`, `ChatMessageMapper` — replaced)
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/` (entire dir: `ThinkingStreamSegmenter`, `ToolCallParser`, `Qwen3Adapter`, `ToolSchemaBuilder`, `ToolExecutor`, `AiToolDescriptions`, `ToolRegistry`, `SlashCommandHandler`, `Builtin*Tool` — see Task 11 for `ToolExecutor`/`SlashCommandHandler` relocation decision)
- `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/inference/`, `model/`, `tensor/`, `nativejni/` (entire dirs — the GGUF/JNI/worker engine)
- `ZhiFlow/src/main/cpp/` (the JNI C++ bridge)
- `ZhiFlow/src/main/resources/native/` (the bundled `.dylib` + `.gitkeep`)
- Corresponding test files under `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/{adapter,tools,inference,model,tensor,nativejni,service.*Local*,service.CloudChatBackend*,service.TokenBatcher*}`

**Kept (unchanged) in `fan.summer.zhiflow.ai`:**
- `AiConfigService.java` (H2 setting reader — reused by `AiConfigProperties`).
- `session/ChatSession.java` (history holder, no backend dependency).
- `util/MarkdownRenderer.java`, `util/JsonHelper.java` (UI/JSON helpers — verify no LC4j imports remain; keep if still used).
- `buildintool/ai/AiChatPlugin.java` (consumer — must compile unchanged).

---

## Task Breakdown

Tasks are ordered so that each one leaves the build green and the app runnable. Strangler discipline: **the old code keeps running until the new code is proven**, then we flip the wiring, then we delete the old code. Do not delete-then-rebuild.

---

### Task 1: Add Spring Boot 4 + Spring AI 2.0 dependencies, remove LangChain4j

**Files:**
- Modify: `ZhiFlow/pom.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: the dependency set that all later tasks compile against. SB4 BOM property `spring-boot.version`, Spring AI BOM property `spring-ai.version`.

- [ ] **Step 1: Add version properties**

In `ZhiFlow/pom.xml`, inside `<properties>`, after the `<langchain4j.version>1.2.0</langchain4j.version>` line, add:

```xml
        <spring-boot.version>4.1.0</spring-boot.version>
        <spring-ai.version>2.0.0</spring-ai.version>
```

- [ ] **Step 2: Add the two BOMs in a new `<dependencyManagement>` block**

SpringKit's POM currently has no `<dependencyManagement>`. Add it as a top-level child of `<project>` (after `</properties>` and before `<dependencies>`):

```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

- [ ] **Step 3: Add Spring Boot starter + the three Spring AI non-starter artifacts**

Inside `<dependencies>`, after the existing `ZhiFlow-Api` dependency block, add:

```xml
        <!-- Spring Boot (embedded, non-web): DI + auto-config backbone for the AI context -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI 2.0 — manual @Bean configuration (no starter-model auto-config).
             Non-starter artifacts so WebApplicationType.NONE has no auto-config surprises. -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-anthropic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-ollama</artifactId>
        </dependency>
```

- [ ] **Step 4: Remove LangChain4j dependencies**

Delete these two blocks from `<dependencies>`:

```xml
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

Also delete the now-unused `<langchain4j.version>1.2.0</langchain4j.version>` property from Step 1's neighbourhood.

- [ ] **Step 5: Verify the build compiles (it will FAIL — that's expected and informative)**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | tail -40`

Expected: compilation errors in `CloudChatBackend.java`, `LocalChatBackend.java`, `adapter/AiToolToToolSpecification.java`, `adapter/ChatMessageMapper.java`, and the `buildintool/.../*Tool.java` files that import `dev.langchain4j.*`. **This is correct** — these are the files Tasks 2–9 replace or delete. Note the exact list of failing files; it confirms the blast radius.

- [ ] **Step 6: Commit**

```bash
git add ZhiFlow/pom.xml
git commit -m "chore(ai): swap LangChain4j deps for Spring Boot 4.1 + Spring AI 2.0 BOMs"
```

> ⚠️ The build is intentionally broken at this commit (old code references removed LC4j classes). Tasks 2–10 restore green. If your CI gates on green builds, consider squashing Tasks 1–10 into one PR rather than landing Task 1 alone on a shared branch.

---

### Task 2: Embedded Spring Boot context bootstrap

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java`
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java`

**Interfaces:**
- Consumes: Spring Boot 4.1 API (`SpringApplicationBuilder`, `WebApplicationType`).
- Produces: `AiSpringContext.getContext()` → `ConfigurableApplicationContext`; `AiSpringContext.getBean(Class)` → `<T> T`; `AiSpringContext.close()`.

- [ ] **Step 1: Write `AiApplication` — the minimal `@SpringBootApplication`**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java`:

```java
package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring Boot application for the embedded (non-web) AI context.
 *
 * <p>Scans only the {@code fan.summer.zhiflow.ai.spring} package so the context stays
 * small: just the {@code ChatModel} {@code @Bean}s and their config. It does
 * <strong>not</strong> scan the legacy {@code fan.summer.zhiflow.ai.service} / {@code tools}
 * code (those classes are not Spring-managed; they are constructed imperatively
 * by {@code ZhiFlowApp} and the settings UI, exactly as before).
 *
 * <p>{@code WebApplicationType.NONE} is forced by {@link AiSpringContext} — this
 * class never starts an HTTP server.
 */
@SpringBootApplication
@ComponentScan(basePackages = "fan.summer.zhiflow.ai.spring")
public class AiApplication {
}
```

- [ ] **Step 2: Write `AiSpringContext` — the static holder/bootstrapper**

Create `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java`:

```java
package fan.summer.zhiflow.ai.spring;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstraps and holds the embedded Spring Boot context used for AI inference.
 *
 * <p>Lifecycle: {@link #start()} is called from {@code ZhiFlowApp.start()} after
 * the H2 database is initialised (so {@code AiConfigService} can read settings);
 * {@link #close()} is called from {@code ZhiFlowApp.stop()}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code WebApplicationType.NONE} — no HTTP server, no web context. This is
 *       a desktop app; Spring is here purely for DI + the {@code ChatModel} beans.</li>
 *   <li>{@code headless(false)} — required by JavaFX (AWT/Java2D insists on
 *       {@code java.awt.headless=false} when a {@code Stage} is shown).</li>
 *   <li>No {@code spring-boot-starter-web} on the classpath, so the context is
 *       guaranteed non-web regardless of auto-detection.</li>
 * </ul>
 *
 * <p>Non-Spring code (JavaFX controllers, the {@code ChatBackend} impls constructed
 * by the settings UI) resolves beans imperatively via {@link #getBean(Class)}.
 * This keeps the strangler migration honest: legacy code does not have to become
 * Spring-managed to use the new {@code ChatModel}s.
 */
public final class AiSpringContext {

    private static final Logger log = LoggerFactory.getLogger(AiSpringContext.class);

    private static volatile ConfigurableApplicationContext context;

    private AiSpringContext() {}

    /** Bootstraps the context. Idempotent — a second call is a no-op. */
    public static synchronized void start() {
        if (context != null) {
            log.debug("AI Spring context already started");
            return;
        }
        log.info("Starting embedded AI Spring context (non-web, headless=false)");
        context = new SpringApplicationBuilder(AiApplication.class)
            .web(WebApplicationType.NONE)
            .headless(false)
            .registerShutdownHook(false)   // we close() manually in ZhiFlowApp.stop()
            .logStartupInfo(false)         // keep the FX launch console clean
            .run();
        log.info("AI Spring context ready");
    }

    /** @return the live context, or throws if {@link #start()} was not called. */
    public static ConfigurableApplicationContext getContext() {
        ConfigurableApplicationContext ctx = context;
        if (ctx == null) {
            throw new IllegalStateException("AI Spring context not started; call AiSpringContext.start() first");
        }
        return ctx;
    }

    /** Look up a bean by type. Convenience for non-Spring callers. */
    public static <T> T getBean(Class<T> type) {
        return getContext().getBean(type);
    }

    /** Close the context, releasing beans. Safe to call from {@code ZhiFlowApp.stop()}. */
    public static synchronized void close() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                log.warn("Error closing AI Spring context: {}", e.getMessage());
            } finally {
                context = null;
            }
        }
    }
}
```

- [ ] **Step 3: Compile the two new files in isolation**

Run: `cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow compile -o 2>&1 | grep -E "(AiSpringContext|AiApplication|BUILD)" | head`

Expected: the two new files compile (they only touch Spring API). Other files still fail from Task 1 — that's fine; we only need THESE two clean.

- [ ] **Step 4: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiSpringContext.java ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiApplication.java
git commit -m "feat(ai): add embedded Spring Boot context bootstrap (WebApplicationType.NONE)"
```

---

### Task 3: H2-backed config properties record

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiConfigProperties.java`

**Interfaces:**
- Consumes: `fan.summer.zhiflow.ai.AiConfigService` (existing H2 reader, unchanged).
- Produces: `AiConfigProperties.record()` → `AiConfigProperties` (immutable snapshot of all AI settings); used as a `@Bean` method-arg by `ChatModelConfig`.

- [ ] **Step 1: Write the record + provider bean**

```java
package fan.summer.zhiflow.ai.spring;

import fan.summer.zhiflow.ai.AiConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Immutable snapshot of the AI settings read from H2 via {@link AiConfigService}.
 *
 * <p>Snapshotted once at context start (a {@code @Bean} method), so the
 * {@code ChatModel} beans see a consistent view. Mode switching (local/openai/
 * anthropic) re-reads via {@link #snapshot()} at switch time rather than caching
 * here, because the user can change settings while the app runs.
 *
 * <p>The underlying keys are unchanged ({@code ai.openai.*}, {@code ai.anthropic.*},
 * {@code ai.local.*}, {@code ai.temperature}, …). Only the reading path is reused.
 */
public record AiConfigProperties(
        String mode,
        String openAiEndpoint,
        String openAiApiKey,
        String openAiModel,
        String anthropicEndpoint,
        String anthropicApiKey,
        String anthropicModel,
        float  temperature,
        float  topP,
        int    maxTokens,
        String systemPrompt,
        String ollamaBaseUrl,   // new: defaults to http://localhost:11434
        String ollamaModel      // new: e.g. "qwen3:4b"; repurposes ai.model.path semantics
) {

    /** Read a fresh snapshot from H2. Called at context start and on mode switch. */
    public static AiConfigProperties snapshot() {
        return new AiConfigProperties(
                AiConfigService.getAiMode(),
                AiConfigService.getAiOpenAiEndpoint(),
                AiConfigService.getAiOpenAiApiKey(),
                AiConfigService.getAiOpenAiModel(),
                AiConfigService.getAiAnthropicEndpoint(),
                AiConfigService.getAiAnthropicApiKey(),
                AiConfigService.getAiAnthropicModel(),
                AiConfigService.getAiTemperature(),
                AiConfigService.getAiTopP(),
                AiConfigService.getAiMaxTokens(),
                AiConfigService.getAiSystemPrompt(),
                AiConfigService.getAiOllamaBaseUrl(),
                AiConfigService.getAiOllamaModel()
        );
    }

    /** Spring bean: snapshot once at context start for default model construction. */
    @Configuration
    public static class Config {
        @Bean
        public AiConfigProperties aiConfigProperties() {
            return AiConfigProperties.snapshot();
        }
    }
}
```

- [ ] **Step 2: Add the two new H2 getters to `AiConfigService`**

In `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/AiConfigService.java`, after the existing `getAiModelPath()` method, add:

```java
    // ── Ollama settings (Phase 1: local runtime is now Ollama) ────────────

    /** Ollama server base URL; defaults to the standard local daemon. */
    public static String getAiOllamaBaseUrl() {
        return readSetting("ai.ollama.base_url", "http://localhost:11434");
    }

    /** Ollama model tag (e.g. {@code "qwen3:4b"}); defaults to Qwen3 4B. */
    public static String getAiOllamaModel() {
        return readSetting("ai.ollama.model", "qwen3:4b");
    }
```

Also add the two key constants near the top of the class with the other `private static final String AI_*_KEY` declarations:

```java
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY = "ai.ollama.model";
```

- [ ] **Step 3: Compile**

Run: `mvn -pl ZhiFlow compile -o 2>&1 | grep -E "(AiConfigProperties|AiConfigService|BUILD)" | head`

Expected: both files clean.

- [ ] **Step 4: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/AiConfigProperties.java ZhiFlow/src/main/java/fan/summer/zhiflow/ai/AiConfigService.java
git commit -m "feat(ai): add H2-backed AiConfigProperties snapshot + Ollama settings"
```

---

### Task 4: `ChatModel` `@Bean` configuration (OpenAI / Anthropic / Ollama)

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/ChatModelConfig.java`

**Interfaces:**
- Consumes: `AiConfigProperties` (Task 3); Spring AI 2.0 `OpenAiApi`/`OpenAiChatModel`/`AnthropicApi`/`AnthropicChatModel`/`OllamaApi`/`OllamaChatModel`.
- Produces: three `ChatModel` beans named `openAiChatModel`, `anthropicChatModel`, `ollamaChatModel`. Looked up by `SpringAiCloudBackend` / `OllamaLocalBackend` via `AiSpringContext.getBean(...)`.

- [ ] **Step 1: Write the configuration**

```java
package fan.summer.zhiflow.ai.spring;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Defines the three Spring AI {@code ChatModel} beans manually (no starter
 * auto-configuration). Each is constructed from {@link AiConfigProperties}.
 *
 * <p>All three beans always exist; the active one is chosen at mode-switch time
 * by name (the {@code ChatBackend} impl asks for "openAiChatModel" etc.). This
 * keeps the wiring explicit and side-steps the historical uncertainty around
 * Spring AI auto-configuration under {@code WebApplicationType.NONE}.
 *
 * <p>Verified builder shapes (Spring AI 2.0 reference, "Manual Configuration"):
 * <ul>
 *   <li>{@code OpenAiChatModel.builder().openAiApi(api).defaultOptions(opts).build()}</li>
 *   <li>{@code OllamaChatModel.builder().ollamaApi(api).options(opts).build()}</li>
 * </ul>
 */
@Configuration
public class ChatModelConfig {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);

    @Bean(name = "openAiChatModel")
    public ChatModel openAiChatModel(AiConfigProperties cfg) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(cfg.openAiEndpoint())
                .apiKey(cfg.openAiApiKey())
                // .restClientBuilder(...) omitted — default HTTP client is fine for a desktop app
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(cfg.openAiModel())
                        .temperature((double) cfg.temperature())
                        .topP((double) cfg.topP())
                        .maxTokens(cfg.maxTokens())
                        .build())
                .build();
    }

    @Bean(name = "anthropicChatModel")
    public ChatModel anthropicChatModel(AiConfigProperties cfg) {
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl(cfg.anthropicEndpoint())
                .apiKey(cfg.anthropicApiKey())
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                        .model(cfg.anthropicModel())
                        .temperature((double) cfg.temperature())
                        .topP((double) cfg.topP())
                        .maxTokens(cfg.maxTokens())
                        .build())
                .build();
    }

    @Bean(name = "ollamaChatModel")
    public ChatModel ollamaChatModel(AiConfigProperties cfg) {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(cfg.ollamaBaseUrl())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(cfg.ollamaModel())
                        .temperature((double) cfg.temperature())
                        .topP((double) cfg.topP())
                        .numPredict(cfg.maxTokens())   // Ollama's max-tokens knob
                        // .think(true)  // SPIKE Task 8: enable once thinking-metadata key is confirmed
                        .build())
                .build();
    }
}
```

> ⚠️ **SPIKE markers** (verify before running):
> 1. `OpenAiChatOptions`/`AnthropicChatOptions`/`OllamaChatOptions` are the 2.0 names; `OllamaOptions` is deprecated (confirmed in the Ollama reference). If `.temperature()` takes `Double` vs `double` causes overload ambiguity, drop the cast.
> 2. `AnthropicChatModel.builder().anthropicApi(...)` — confirm the builder method name is `anthropicApi` (not `api`). If the compiler rejects it, the method is `api(api)` — both have appeared across versions; the compiler will tell you.
> 3. `OpenAiChatOptions.builder().maxTokens(int)` — confirm it exists (some versions expose it only via `maxCompletionTokens`). If rejected, use `.maxCompletionTokens(cfg.maxTokens())`.

- [ ] **Step 2: Compile (accepting that downstream backends still fail)**

Run: `mvn -pl ZhiFlow compile -o 2>&1 | grep -E "(ChatModelConfig|BUILD)" | head`

Expected: `ChatModelConfig` compiles clean. If a SPIKE marker trips, fix the exact method name per the compiler error and update this plan.

- [ ] **Step 3: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/spring/ChatModelConfig.java
git commit -m "feat(ai): add ChatModel @Bean config for OpenAI/Anthropic/Ollama"
```

---

### Task 5: `MessageMapper` — `AiChatMessage` ↔ Spring AI `Message`

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/MessageMapper.java`
- Create: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/MessageMapperTest.java`

**Interfaces:**
- Consumes: `AiChatMessage`, `AiToolCall` (contract, unchanged).
- Produces: `MessageMapper.toSpringAi(AiChatMessage)` → `org.springframework.ai.chat.messages.Message`; `MessageMapper.fromAssistant(AssistantMessage)` → `AiChatMessage` (for tool-call extraction).

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageMapperTest {

    @Test
    void systemMessageRoundTrip() {
        Message m = MessageMapper.toSpringAi(AiChatMessage.system("You are helpful."));
        assertEquals(MessageType.SYSTEM, m.getMessageType());
        assertEquals("You are helpful.", m.getText());
    }

    @Test
    void userMessageRoundTrip() {
        Message m = MessageMapper.toSpringAi(AiChatMessage.user("Hi"));
        assertEquals(MessageType.USER, m.getMessageType());
        assertEquals("Hi", m.getText());
    }

    @Test
    void assistantWithToolCallsMapsToToolCallList() {
        AiToolCall call = AiToolCall.of("call_1", "get_weather", Map.of("city", "Zurich"));
        Message m = MessageMapper.toSpringAi(AiChatMessage.assistantWithTools("", List.of(call)));

        assertInstanceOf(AssistantMessage.class, m);
        AssistantMessage am = (AssistantMessage) m;
        assertTrue(am.hasToolCalls());
        assertEquals(1, am.getToolCalls().size());
        AssistantMessage.ToolCall tc = am.getToolCalls().get(0);
        assertEquals("call_1", tc.id());
        assertEquals("get_weather", tc.name());
    }

    @Test
    void toolResultMapsToToolResponseMessage() {
        AiChatMessage tr = AiChatMessage.toolResult("call_1", "get_weather", "sunny");
        Message m = MessageMapper.toSpringAi(tr);
        assertInstanceOf(ToolResponseMessage.class, m);
        ToolResponseMessage trm = (ToolResponseMessage) m;
        assertEquals("sunny", trm.getResponses().get(0).responseMessage());
        assertEquals("call_1", trm.getResponses().get(0).id());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl ZhiFlow test -o -Dtest=MessageMapperTest 2>&1 | tail -20`

Expected: compilation failure — `MessageMapper` does not exist yet.

- [ ] **Step 3: Write `MessageMapper`**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bidirectional mapper between ZhiFlow's {@link AiChatMessage} and Spring AI's
 * {@link Message} hierarchy. Replaces the LangChain4j {@code ChatMessageMapper}.
 *
 * <p>Role mapping:
 * <ul>
 *   <li>{@code SYSTEM}    ↔ {@link SystemMessage}</li>
 *   <li>{@code USER}      ↔ {@link UserMessage}</li>
 *   <li>{@code ASSISTANT} ↔ {@link AssistantMessage} (with optional {@code ToolCall}s)</li>
 *   <li>{@code TOOL}      ↔ {@link ToolResponseMessage}</li>
 * </ul>
 *
 * <p>Tool-call arguments cross the boundary as JSON strings (Spring AI's
 * {@code ToolCall.arguments()} is a JSON string), serialised via {@link JsonHelper}.
 */
public final class MessageMapper {

    private MessageMapper() {}

    /** ZhiFlow message → Spring AI message. */
    public static Message toSpringAi(AiChatMessage src) {
        String text = src.content() == null ? "" : src.content();
        return switch (src.role()) {
            case SYSTEM -> new SystemMessage(text);
            case USER   -> new UserMessage(text);
            case ASSISTANT -> {
                if (src.toolCalls() == null || src.toolCalls().isEmpty()) {
                    yield new AssistantMessage(text);
                }
                List<AssistantMessage.ToolCall> tcs = new ArrayList<>();
                for (AiToolCall tc : src.toolCalls()) {
                    tcs.add(new AssistantMessage.ToolCall(
                            tc.id() != null ? tc.id() : "",
                            "function",
                            tc.name(),
                            JsonHelper.toJson(tc.arguments())));
                }
                yield new AssistantMessage(text, Map.of(), tcs);
            }
            case TOOL -> new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(
                            src.toolCallId() != null ? src.toolCallId() : "",
                            src.toolName() != null ? src.toolName() : "",
                            text)));
        };
    }

    /**
     * Extract tool-call requests from a Spring AI {@link AssistantMessage} into
     * ZhiFlow {@link AiToolCall}s. Used by the manual tool loop after a streamed
     * response completes with pending tool calls.
     */
    public static List<AiToolCall> extractToolCalls(AssistantMessage am) {
        if (!am.hasToolCalls()) return List.of();
        List<AiToolCall> out = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            String id = tc.id() != null && !tc.id().isEmpty()
                    ? tc.id()
                    : "tc_" + System.currentTimeMillis();
            Map<String, Object> args = parseArgs(tc.arguments());
            out.add(AiToolCall.of(id, tc.name(), args));
        }
        return out;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonHelper.parseObject(json);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl ZhiFlow test -o -Dtest=MessageMapperTest 2>&1 | tail -20`

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/MessageMapper.java ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/MessageMapperTest.java
git commit -m "feat(ai): add MessageMapper (AiChatMessage <-> Spring AI Message)"
```

---

### Task 6: `ToolSchemaJson` — build JSON-schema string from `AiToolParam`

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJson.java`
- Create: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJsonTest.java`

**Interfaces:**
- Consumes: `List<AiToolParam>` (contract).
- Produces: `ToolSchemaJson.build(List<AiToolParam>)` → `String` (a JSON-schema object string, suitable for `DefaultToolDefinition.inputSchema()`).

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaJsonTest {

    @Test
    void emptyParamsYieldsEmptyObjectSchema() {
        String s = ToolSchemaJson.build(List.of());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}", s);
    }

    @Test
    void primitiveStringParam() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("city", "string", "City name", true)));
        assertTrue(s.contains("\"city\""));
        assertTrue(s.contains("\"type\":\"string\""));
        assertTrue(s.contains("\"required\":[\"city\"]"));
    }

    @Test
    void enumParamEmitsEnumArray() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("unit", "string", "Temperature unit", true,
                        List.of("celsius", "fahrenheit"))));
        assertTrue(s.contains("\"enum\":[\"celsius\",\"fahrenheit\"]"));
    }

    @Test
    void integerAndBooleanTypes() {
        String s = ToolSchemaJson.build(List.of(
                AiToolParam.of("count", "integer", "How many", false),
                AiToolParam.of("verbose", "boolean", "Verbose output", false)));
        assertTrue(s.contains("\"type\":\"integer\""));
        assertTrue(s.contains("\"type\":\"boolean\""));
        // nothing required
        assertTrue(s.contains("\"required\":[]"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ZhiFlow test -o -Dtest=ToolSchemaJsonTest 2>&1 | tail -10`

Expected: compile failure (class missing).

- [ ] **Step 3: Write `ToolSchemaJson`**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiToolParam;

import java.util.List;

/**
 * Builds a JSON-schema <strong>string</strong> describing an {@link AiToolParam} list,
 * for use as {@code DefaultToolDefinition.inputSchema()}.
 *
 * <p>Spring AI's {@code ToolDefinition.inputSchema()} returns a JSON-schema
 * <em>string</em> (not an object), so this builder hand-rolls the JSON. It is the
 * moral equivalent of the old LangChain4j {@code AiToolToToolSpecification}, but
 * emits schema JSON rather than LC4j {@code JsonSchemaElement} objects.
 *
 * <p>Supported types: {@code string} (incl. enums), {@code integer}, {@code number},
 * {@code boolean}. Array suffix {@code "type[]"} is mapped to {@code {"type":"array",...}}.
 * Unknown types default to {@code string}.
 */
public final class ToolSchemaJson {

    private ToolSchemaJson() {}

    public static String build(List<AiToolParam> params) {
        StringBuilder props = new StringBuilder("{");
        StringBuilder required = new StringBuilder("[");

        boolean firstProp = true;
        boolean firstReq = true;
        for (AiToolParam p : params) {
            if (!firstProp) props.append(",");
            firstProp = false;
            props.append('"').append(escape(p.name())).append("\":")
                 .append(schemaForParam(p));

            if (p.required()) {
                if (!firstReq) required.append(",");
                firstReq = false;
                required.append('"').append(escape(p.name())).append('"');
            }
        }
        props.append("}");
        required.append("]");

        return "{\"type\":\"object\",\"properties\":" + props
             + ",\"required\":" + required + "}";
    }

    private static String schemaForParam(AiToolParam p) {
        String desc = escape(p.description() == null ? "" : p.description());
        // Enum overrides any type — emit {"type":"string","enum":[...],"description":"..."}
        if (p.enumValues() != null && !p.enumValues().isEmpty()) {
            StringBuilder enumArr = new StringBuilder("[");
            boolean first = true;
            for (String e : p.enumValues()) {
                if (!first) enumArr.append(",");
                first = false;
                enumArr.append('"').append(escape(e)).append('"');
            }
            enumArr.append("]");
            return "{\"type\":\"string\",\"enum\":" + enumArr + ",\"description\":\"" + desc + "\"}";
        }
        String type = p.type() == null ? "string" : p.type();
        if (type.endsWith("[]")) {
            String elem = type.substring(0, type.length() - 2);
            return "{\"type\":\"array\",\"items\":{\"type\":\"" + mapType(elem)
                 + "\"},\"description\":\"" + desc + "\"}";
        }
        return "{\"type\":\"" + mapType(type) + "\",\"description\":\"" + desc + "\"}";
    }

    private static String mapType(String t) {
        return switch (t) {
            case "integer", "number", "boolean" -> t;
            default -> "string";
        };
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl ZhiFlow test -o -Dtest=ToolSchemaJsonTest 2>&1 | tail -10`

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJson.java ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/ToolSchemaJsonTest.java
git commit -m "feat(ai): add ToolSchemaJson (AiToolParam -> JSON-schema string)"
```

---

### Task 7: `AiToolCallback` — adapt plugin `AiTool` to Spring AI `ToolCallback`

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolCallback.java`
- Create: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolCallbackTest.java`

**Interfaces:**
- Consumes: `AiTool`, `AiToolParam`, `AiToolResult` (contract); Spring AI `ToolCallback`, `ToolDefinition`, `DefaultToolDefinition`, `ToolContext`.
- Produces: `new AiToolCallback(aiTool)` → a Spring AI `ToolCallback` whose `call(json)` delegates to `aiTool.execute(map).output()`.

- [ ] **Step 1: Write the failing test**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiToolCallbackTest {

    private static AiTool echoTool() {
        return new AiTool() {
            @Override public String getName()        { return "echo"; }
            @Override public String getDescription() { return "Echoes text"; }
            @Override public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("text", "string", "Text to echo", true));
            }
            @Override public AiToolResult execute(Map<String, Object> args) {
                return AiToolResult.success("echo:" + args.get("text"));
            }
        };
    }

    @Test
    void toolDefinitionHasNameDescriptionSchema() {
        ToolCallback cb = new AiToolCallback(echoTool());
        ToolDefinition def = cb.getToolDefinition();
        assertEquals("echo", def.name());
        assertEquals("Echoes text", def.description());
        assertTrue(def.inputSchema().contains("\"text\""));
        assertTrue(def.inputSchema().contains("\"type\":\"string\""));
    }

    @Test
    void callParsesJsonArgsAndDelegates() {
        ToolCallback cb = new AiToolCallback(echoTool());
        String result = cb.call("{\"text\":\"hi\"}");
        assertEquals("echo:hi", result);
    }

    @Test
    void callReturnsErrorMessageOnException() {
        AiTool broken = new AiTool() {
            @Override public String getName()        { return "broken"; }
            @Override public String getDescription() { return "Always throws"; }
            @Override public List<AiToolParam> getParameters() { return List.of(); }
            @Override public AiToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };
        ToolCallback cb = new AiToolCallback(broken);
        String result = cb.call("{}");
        // AiToolCallback wraps exceptions into an error JSON, never throws
        assertNotNull(result);
        assertTrue(result.contains("boom") || result.contains("error"), "result=" + result);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ZhiFlow test -o -Dtest=AiToolCallbackTest 2>&1 | tail -10`

Expected: compile failure.

- [ ] **Step 3: Write `AiToolCallback`**

```java
package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.ai.tools.AiToolDescriptions;   // KEEP for local/cloud description picking
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.ai.util.JsonHelper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Adapts a ZhiFlow plugin {@link AiTool} into a Spring AI {@link ToolCallback}.
 *
 * <p>This is the stable seam of the migration: plugins keep implementing
 * {@code AiTool.execute(Map) -> AiToolResult} unchanged; Spring AI invokes them
 * through this adapter. Replaces the LangChain4j {@code AiToolToToolSpecification}
 * (which was schema-only and not executable).
 *
 * <p>JSON-schema is built by {@link ToolSchemaJson}; argument parsing reuses
 * {@link JsonHelper} (Gson). Local/cloud description selection is delegated to
 * {@link AiToolDescriptions} so mode-aware tool descriptions keep working.
 */
public final class AiToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallback.class);

    private final AiTool aiTool;

    public AiToolCallback(AiTool aiTool) {
        this.aiTool = aiTool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = ToolSchemaJson.build(AiToolDescriptions.pickParameters(aiTool));
        return DefaultToolDefinition.builder()
                .name(aiTool.getName())
                .description(AiToolDescriptions.pickDescription(aiTool))
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> args = parseArgs(toolInput);
        try {
            log.debug("Executing AiTool via Spring AI: name={}, args={}", aiTool.getName(), args);
            AiToolResult result = aiTool.execute(args);
            return result.output();
        } catch (Exception e) {
            log.error("AiTool execution threw: name={}, error={}", aiTool.getName(), e.getMessage(), e);
            // Never propagate — Spring AI expects a String back. Return an error JSON
            // the model can reason about, mirroring ToolExecutor's old jsonError shape.
            return JsonHelper.toJson(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** Expose the underlying AiTool so the backend can correlate by name. */
    public AiTool aiTool() {
        return aiTool;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonHelper.parseObject(json);
        } catch (Exception e) {
            log.warn("Failed to parse tool-call arguments JSON, using empty map: '{}'", json);
            return Map.of();
        }
    }
}
```

> ⚠️ This task depends on `AiToolDescriptions` (currently in `fan.summer.zhiflow.ai.tools`). That class is scheduled for deletion in Task 11. Before Task 11, we relocate the two static methods `pickParameters`/`pickDescription` (they are tiny) into `AiToolCallback` or a small helper in `adapter`. **Decision: relocate in Task 11, not now** — for Task 7, keep the import as-is so the adapter compiles against the existing helper.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl ZhiFlow test -o -Dtest=AiToolCallbackTest 2>&1 | tail -10`

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolCallback.java ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolCallbackTest.java
git commit -m "feat(ai): add AiToolCallback (AiTool -> Spring AI ToolCallback)"
```

---

### Task 8 (SPIKE): Ollama thinking-mode probe

**Why this is a spike:** Spring AI's `OllamaChatOptions` exposes a `think` field (auto-on for reasoning models), but the **exact metadata key** carrying thinking content per-streaming-chunk **could not be locked from source** in the research pass. Writing the `OllamaLocalBackend` (Task 9) without knowing this produces a non-functional thinking-surfacing path or, worse, one that silently swallows thinking.

**Files:**
- Create (throwaway, not committed): `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/spike/OllamaThinkingSpike.java`

**Prerequisite:** `ollama serve` running locally; `ollama pull qwen3:4b` done.

- [ ] **Step 1: Write the spike**

```java
package fan.summer.zhiflow.ai.spike;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Map;

/**
 * SPIKE — not committed. Run manually to discover:
 *  1. The exact metadata key that carries <think> content in streaming chunks.
 *  2. Whether thinking appears per-chunk or only in the final chunk.
 *  3. The OllamaChatOptions builder method name for enabling think.
 *
 * Run: mvn -pl ZhiFlow test-compile exec:java -Dexec.mainClass=fan.summer.zhiflow.ai.spike.OllamaThinkingSpike
 */
public class OllamaThinkingSpike {
    public static void main(String[] args) {
        OllamaApi api = OllamaApi.builder().baseUrl("http://localhost:11434").build();
        OllamaChatOptions opts = OllamaChatOptions.builder()
                .model("qwen3:4b")
                // try BOTH spellings; keep whichever compiles
                // .think(true)        // ← expected 2.0 name
                // .thinking(true)     // ← fallback if think() absent
                .numPredict(512)
                .build();
        ChatModel model = OllamaChatModel.builder().ollamaApi(api).options(opts).build();

        Prompt p = new Prompt(List.of(new UserMessage("What is 17 * 23? Think step by step.")));
        model.stream(p).doOnNext(chunk -> {
            ChatResponse r = chunk;
            var out = r.getResult().getOutput();
            Map<String, Object> md = out.getMetadata();
            System.out.println("=== CHUNK ===");
            System.out.println("text   = " + out.getText());
            System.out.println("md keys= " + (md == null ? "null" : md.keySet()));
            if (md != null) md.forEach((k, v) -> System.out.println("  " + k + " = " + truncate(v)));
        }).blockLast();
    }

    private static String truncate(Object v) {
        String s = String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
```

- [ ] **Step 2: Run it and record findings**

Run against the local Ollama. Note down:
- Which `.think*()` builder method compiled.
- The metadata key(s) on each chunk that carry thinking content.
- Whether thinking chunks are emitted as they happen (good — can be streamed to `AiStreamCallback.onThinking`) or only at the end.

- [ ] **Step 3: Record the findings in this plan**

Edit Task 9's `routeThinking(...)` step (below) with the discovered key. Delete the spike file. **Do not commit the spike.**

> **Fallback if the spike cannot be run** (e.g. no GPU / Ollama unavailable): Task 9's `OllamaLocalBackend` ships **without** thinking surfacing — `AiStreamCallback.onThinking` is simply never invoked by the local backend in Phase 1. Thinking still happens inside the model; it's just not surfaced to the UI. This is a regression vs the old `ThinkingStreamSegmenter` behaviour, explicitly accepted for Phase 1, and logged as a TODO for the next iteration. The cloud backends never surfaced thinking either, so this is parity for cloud, regression-only-for-local.

---

### Task 9: `OllamaLocalBackend` — local `ChatBackend` over Ollama

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java`
- Create: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/OllamaLocalBackendConnectionTest.java`

**Interfaces:**
- Consumes: `ChatBackend` (contract), `AiSpringContext`, `MessageMapper`, `AiToolCallback`, `ToolExecutor` (relocated — see Task 11; for now keep the `tools.ToolExecutor` import).
- Produces: `new OllamaLocalBackend()` — implements `ChatBackend`; the local-mode replacement for `LocalChatBackend`. `loadModel(Path)` is repurposed: the path is ignored if a model tag is configured in H2, otherwise the filename is treated as an Ollama tag.

- [ ] **Step 1: Write the connection-test unit test (uses a fake HTTP server)**

```java
package fan.summer.zhiflow.ai.service;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only the connection-probe helper on OllamaLocalBackend, which pings
 * {base}/api/tags. The full chat path needs a live Ollama and is covered by
 * manual smoke-testing.
 */
class OllamaLocalBackendConnectionTest {

    @Test
    void probeReturnsTrueWhenServerResponds200() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread accepter = new Thread(() -> {
                try (java.net.Socket s = server.accept();
                     var os = s.getOutputStream()) {
                    // minimal HTTP 200 response
                    String body = "{\"models\":[]}";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
                    os.write(resp.getBytes());
                } catch (Exception ignored) {}
            });
            accepter.setDaemon(true);
            accepter.start();

            assertTrue(OllamaLocalBackend.probeReachable("http://localhost:" + port));
        }
    }

    @Test
    void probeReturnsFalseWhenConnectionRefused() {
        // pick a port that's almost certainly closed
        assertFalse(OllamaLocalBackend.probeReachable("http://localhost:65500"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ZhiFlow test -o -Dtest=OllamaLocalBackendConnectionTest 2>&1 | tail -10`

Expected: compile failure.

- [ ] **Step 3: Write `OllamaLocalBackend`**

```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.adapter.AiToolCallback;
import fan.summer.zhiflow.ai.adapter.MessageMapper;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.ai.tools.ToolExecutor;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local-mode {@link ChatBackend} backed by Ollama via Spring AI's
 * {@code OllamaChatModel}. Replaces the entire custom GGUF/JNI/worker stack.
 *
 * <p>The model is served by an external {@code ollama serve} process; this class
 * only talks to its HTTP API (through Spring AI). "Loading a model" is now
 * selecting an Ollama tag ({@code qwen3:4b}); there is no in-process weight file.
 *
 * <p>Tool loop: driven manually (same shape as {@link SpringAiCloudBackend}) so
 * {@code AiStreamCallback.onToolCall/onToolResult} events still fire on the FX
 * thread for UI feedback. Tools are pulled from {@link AiServiceProvider} and
 * wrapped as {@link AiToolCallback}.
 */
public final class OllamaLocalBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(OllamaLocalBackend.class);
    private static final int MAX_TOOL_ROUNDS = 8;

    private final AtomicBoolean generating = new AtomicBoolean(false);
    private volatile String ollamaModelTag;
    private volatile ChatModel chatModel;

    public OllamaLocalBackend() {
        this.ollamaModelTag = fan.summer.zhiflow.ai.AiConfigService.getAiOllamaModel();
        // The ChatModel bean is built from H2 config at context start; look it up lazily.
    }

    // ── ChatBackend lifecycle ────────────────────────────────────────

    @Override
    public void loadModel(Path modelPath) throws AiServiceException {
        // In the Ollama world, "load model" = "select the tag". The path argument
        // is honoured only if the user dropped a model file (we read its name as
        // a tag); otherwise the H2-configured tag wins.
        String configured = fan.summer.zhiflow.ai.AiConfigService.getAiOllamaModel();
        if (configured != null && !configured.isBlank()) {
            this.ollamaModelTag = configured;
        } else if (modelPath != null) {
            this.ollamaModelTag = modelPath.getFileName().toString();
        }
        log.info("Ollama local backend: model tag = {}", ollamaModelTag);

        // Resolve the ChatModel bean (built by ChatModelConfig from the Ollama base URL).
        try {
            this.chatModel = AiSpringContext.getBean("ollamaChatModel", ChatModel.class);
        } catch (Exception e) {
            throw new AiServiceException("Ollama ChatModel bean unavailable; is the AI Spring context started? " + e.getMessage(), e);
        }
        if (!probeReachable(fan.summer.zhiflow.ai.AiConfigService.getAiOllamaBaseUrl())) {
            log.warn("Ollama server not reachable at {} — chat will fail at call time. "
                     + "Run `ollama serve` and `ollama pull {}`.",
                     fan.summer.zhiflow.ai.AiConfigService.getAiOllamaBaseUrl(), ollamaModelTag);
        }
    }

    @Override public void unloadModel() {
        // Nothing to release — the model lives in the Ollama server.
        chatModel = null;
    }

    @Override public boolean isReady() {
        return chatModel != null && ollamaModelTag != null && !ollamaModelTag.isBlank();
    }

    @Override
    public Optional<String> getModelName() {
        return Optional.ofNullable(ollamaModelTag);
    }

    @Override public long getMemoryUsage() {
        // Ollama owns the weights; the JVM's heap usage is not meaningful here.
        return -1;
    }

    @Override public boolean isNativeAvailable() {
        // There is no JNI surface anymore. Return true if the Ollama server is up —
        // this drives the "degraded banner" the AiChatPlugin shows when false.
        return probeReachable(fan.summer.zhiflow.ai.AiConfigService.getAiOllamaBaseUrl());
    }

    // ── Chat ──────────────────────────────────────────────────────────

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, ZhiFlowSettingUi.getAiTemperature(), ZhiFlowSettingUi.getAiTopP(),
             ZhiFlowSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException("Ollama backend not ready (model=" + ollamaModelTag + ")");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, callback);
            } catch (Exception e) {
                log.error("Ollama chat failed", e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        // Best-effort: Spring AI 2.0 streaming Flux can be cancelled via downstream dispose,
        // but the manual loop here doesn't hold the Disposable. Logged for parity.
        log.debug("cancelGeneration() requested; mid-stream abort not wired in Phase 1");
    }

    @Override public boolean isGenerating() { return generating.get(); }

    // ── Tool loop ─────────────────────────────────────────────────────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback) {
        List<ToolCallback> toolCallbacks = buildToolCallbacks();
        String systemPrompt = currentSystemPrompt();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> msgs = new ArrayList<>(history.size() + 1);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                msgs.add(new SystemMessage(systemPrompt));
            }
            for (AiChatMessage m : history) msgs.add(MessageMapper.toSpringAi(m));

            Prompt prompt = new Prompt(msgs);

            // Stream this round; accumulate the assistant text + capture the last chunk
            // (tool calls arrive in the terminal chunk once the round completes).
            StringBuilder accumulated = new StringBuilder();
            AtomicReference<List<AiToolCall>> pendingCalls = new AtomicReference<>(List.of());
            AtomicReference<AssistantMessage> lastMsg = new AtomicReference<>();

            chatModel.stream(prompt).doOnNext(chunk -> {
                ChatResponse resp = chunk;
                if (resp == null || resp.getResult() == null) return;
                AssistantMessage am = resp.getResult().getOutput();
                if (am == null) return;
                lastMsg.set(am);

                String delta = am.getText();
                if (delta != null && !delta.isEmpty()) {
                    accumulated.append(delta);
                    final String token = delta;
                    Platform.runLater(() -> callback.onToken(token));
                }
                // SPIKE Task 8: route thinking content here once the metadata key is known.
                // routeThinking(am, callback);
            }).blockLast();   // virtual thread, blocking is fine

            AssistantMessage finalAm = lastMsg.get();
            List<AiToolCall> calls = finalAm != null ? MessageMapper.extractToolCalls(finalAm) : List.of();

            if (calls.isEmpty()) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                Platform.runLater(() -> callback.onComplete(finalText, tokens, 0));
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                Platform.runLater(() -> callback.onComplete(warn, 0, 0));
                return;
            }
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
        }
    }

    private List<ToolCallback> buildToolCallbacks() {
        List<AiTool> tools = AiServiceProvider.getTools();
        List<ToolCallback> cbs = new ArrayList<>(tools.size());
        for (AiTool t : tools) cbs.add(new AiToolCallback(t));
        return cbs;
    }

    private static String currentSystemPrompt() {
        try { return ZhiFlowSettingUi.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    // ── Connection probe (also used by the connection test) ───────────

    /**
     * Pings {@code {base}/api/tags} to check whether an Ollama server is listening.
     * Public so the unit test can drive a fake server.
     */
    public static boolean probeReachable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
```

> ⚠️ **Note on `getBean("ollamaChatModel", ChatModel.class)`:** the two-arg `getBean(String, Class)` is standard Spring `ApplicationContext` API and verified.

- [ ] **Step 4: Run the connection test**

Run: `mvn -pl ZhiFlow test -o -Dtest=OllamaLocalBackendConnectionTest 2>&1 | tail -10`

Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/OllamaLocalBackend.java ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/OllamaLocalBackendConnectionTest.java
git commit -m "feat(ai): add OllamaLocalBackend (local ChatBackend over Ollama)"
```

---

### Task 10: `SpringAiCloudBackend` — cloud `ChatBackend` over Spring AI

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java`
- Create: `ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackendToolLoopTest.java`

**Interfaces:**
- Consumes: `ChatBackend`, `AiSpringContext`, `MessageMapper`, `AiToolCallback`, `ToolExecutor`.
- Produces: `SpringAiCloudBackend.openAi()` / `SpringAiCloudBackend.anthropic()` — drop-in replacement for the old `CloudChatBackend` factories; the cloud-mode `ChatBackend`. `testConnection()` preserved for the Settings UI.

- [ ] **Step 1: Write the failing tool-loop test (stub `ChatModel`)**

```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.AiToolParam;
import fan.summer.zhiflow.api.ai.AiToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the manual tool loop drives exactly one round-trip per tool batch
 * and fires onToolCall/onToolResult on each call. Uses a scripted ChatModel
 * that returns a tool-call on the first stream and a final answer on the second.
 */
class SpringAiCloudBackendToolLoopTest {

    private static final class ScriptedChatModel implements ChatModel {
        final AtomicInteger callCount = new AtomicInteger(0);
        // First stream -> tool call; second stream -> final text.
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                AssistantMessage am = new AssistantMessage("",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"text\":\"hi\"}")));
                return Flux.just(new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(am))));
            }
            AssistantMessage finalAm = new AssistantMessage("echo:hi");
            return Flux.just(new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(finalAm))));
        }
        // unused ChatModel methods — minimal stubs
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
    }

    private AiTool echoTool;

    @BeforeEach void setupTool() {
        echoTool = new AiTool() {
            @Override public String getName()        { return "echo"; }
            @Override public String getDescription() { return "echo"; }
            @Override public List<AiToolParam> getParameters() {
                return List.of(AiToolParam.of("text", "string", "text", true));
            }
            @Override public AiToolResult execute(Map<String, Object> args) {
                return AiToolResult.success("echo:" + args.get("text"));
            }
        };
        AiServiceProvider.registerTool(echoTool);
    }

    @AfterEach void clearTool() { AiServiceProvider.clearTools(); }

    @Test
    void toolLoopFiresCallbackEvents() throws Exception {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(new ScriptedChatModel());
        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("ping")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger toolCalls = new AtomicInteger(0);
        AtomicInteger toolResults = new AtomicInteger(0);
        AiStreamCallback cb = new AiStreamCallback() {
            @Override public void onToolCall(AiToolCall tc)        { toolCalls.incrementAndGet(); }
            @Override public void onToolResult(String id, AiToolResult r) { toolResults.incrementAndGet(); }
            @Override public void onComplete(String s, int t, double r)   { done.countDown(); }
        };

        backend.chat(history, 0.7f, 0.9f, 256, cb);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, toolCalls.get());
        assertEquals(1, toolResults.get());
        // history should now contain: [user, assistantWithTools, toolResult, assistant-final]
        assertEquals(4, history.size());
    }
}
```

> ⚠️ This test constructs `SpringAiCloudBackend` with a `ChatModel` directly (package-private or public constructor accepting a `ChatModel`), bypassing the Spring context — so it runs without booting Spring. The production constructor path is `SpringAiCloudBackend.openAi(...)`/`anthropic(...)`, which look the bean up via `AiSpringContext`. Make the `ChatModel`-accepting constructor package-private for tests.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl ZhiFlow test -o -Dtest=SpringAiCloudBackendToolLoopTest 2>&1 | tail -15`

Expected: compile failure (`SpringAiCloudBackend` missing).

- [ ] **Step 3: Write `SpringAiCloudBackend`**

```java
package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.adapter.AiToolCallback;
import fan.summer.zhiflow.ai.adapter.MessageMapper;
import fan.summer.zhiflow.ai.spring.AiSpringContext;
import fan.summer.zhiflow.ai.tools.ToolExecutor;
import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiChatMessage;
import fan.summer.zhiflow.api.ai.AiServiceException;
import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiStreamCallback;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolCall;
import fan.summer.zhiflow.api.ai.ChatBackend;
import fan.summer.zhiflow.ui.setting.ZhiFlowSettingUi;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cloud-mode {@link ChatBackend} backed by Spring AI's {@code OpenAiChatModel} /
 * {@code AnthropicChatModel}. Replaces the LangChain4j {@code CloudChatBackend}.
 *
 * <p>The bean is looked up from {@link AiSpringContext} by name
 * ({@code openAiChatModel} / {@code anthropicChatModel}); the provider is fixed at
 * construction time. Each {@code chat()} call streams via
 * {@link ChatModel#stream(Prompt)} and drives the multi-round tool loop manually,
 * preserving {@link AiStreamCallback#onToolCall} / {@link #onToolResult} events
 * on the FX thread for UI feedback (same contract as the old backend).
 */
public final class SpringAiCloudBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCloudBackend.class);
    private static final int MAX_TOOL_ROUNDS = 5;
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    public enum Provider { OPENAI, ANTHROPIC }

    private final Provider provider;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final ChatModel chatModel;          // resolved at construction
    private final AtomicBoolean generating = new AtomicBoolean(false);

    // ── Production constructors (look up the ChatModel bean) ──────────

    public static SpringAiCloudBackend openAi(String endpoint, String apiKey, String modelName) {
        ChatModel model = AiSpringContext.getBean("openAiChatModel", ChatModel.class);
        return new SpringAiCloudBackend(Provider.OPENAI, endpoint, apiKey, modelName, model);
    }

    public static SpringAiCloudBackend anthropic(String endpoint, String apiKey, String modelName) {
        ChatModel model = AiSpringContext.getBean("anthropicChatModel", ChatModel.class);
        return new SpringAiCloudBackend(Provider.ANTHROPIC, endpoint, apiKey, modelName, model);
    }

    // ── Test constructor (inject ChatModel directly, bypass Spring) ───

    SpringAiCloudBackend(ChatModel chatModel) {
        this(Provider.OPENAI, "test", "test-key", "test-model", chatModel);
    }

    private SpringAiCloudBackend(Provider provider, String endpoint, String apiKey, String modelName, ChatModel chatModel) {
        this.provider = provider;
        this.endpoint = endpoint == null ? "" : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.chatModel = chatModel;
    }

    // ── Public accessors (preserved for SynchronousChatHelper + Settings UI) ──

    public Provider provider()         { return provider; }
    public String getEndpoint()        { return endpoint; }
    public String getApiKey()          { return apiKey; }
    public String getModelNameInternal() { return modelName; }

    // ── ChatBackend ───────────────────────────────────────────────────

    @Override public void loadModel(Path modelPath) throws AiServiceException {
        throw new AiServiceException("Local model loading not supported for cloud backend");
    }
    @Override public void unloadModel() { /* model bean is reused; nothing to release */ }

    @Override public boolean isReady() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && modelName != null && !modelName.isBlank();
    }

    @Override public Optional<String> getModelName() { return Optional.ofNullable(modelName); }
    @Override public long getMemoryUsage() { return -1; }
    @Override public boolean isGenerating() { return generating.get(); }

    @Override
    public void chat(List<AiChatMessage> history, AiStreamCallback callback) throws AiServiceException {
        chat(history, ZhiFlowSettingUi.getAiTemperature(), ZhiFlowSettingUi.getAiTopP(),
             ZhiFlowSettingUi.getAiMaxTokens(), callback);
    }

    @Override
    public void chat(List<AiChatMessage> history, float temperature, float topP, int maxTokens,
                     AiStreamCallback callback) throws AiServiceException {
        if (!isReady()) throw new AiServiceException(provider + " cloud backend not configured");
        if (!generating.compareAndSet(false, true)) throw new AiServiceException("Generation already in progress");

        Thread.ofVirtual().start(() -> {
            try {
                runToolLoop(history, callback);
            } catch (Exception e) {
                log.error("{} chat failed", provider, e);
                Platform.runLater(() -> callback.onError(e));
            } finally {
                generating.set(false);
            }
        });
    }

    @Override public void cancelGeneration() {
        log.debug("cancelGeneration() requested; mid-stream abort not wired in Phase 1");
    }

    // ── Tool loop ─────────────────────────────────────────────────────

    private void runToolLoop(List<AiChatMessage> history, AiStreamCallback callback) {
        String systemPrompt = currentSystemPrompt();

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<Message> msgs = new ArrayList<>(history.size() + 1);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                msgs.add(new SystemMessage(systemPrompt));
            }
            for (AiChatMessage m : history) msgs.add(MessageMapper.toSpringAi(m));
            Prompt prompt = new Prompt(msgs);

            StringBuilder accumulated = new StringBuilder();
            AtomicReference<AssistantMessage> lastMsg = new AtomicReference<>();

            chatModel.stream(prompt).doOnNext(chunk -> {
                ChatResponse resp = chunk;
                if (resp == null || resp.getResult() == null) return;
                AssistantMessage am = resp.getResult().getOutput();
                if (am == null) return;
                lastMsg.set(am);
                String delta = am.getText();
                if (delta != null && !delta.isEmpty()) {
                    accumulated.append(delta);
                    final String token = delta;
                    Platform.runLater(() -> callback.onToken(token));
                }
            }).blockLast();

            AssistantMessage finalAm = lastMsg.get();
            List<AiToolCall> calls = finalAm != null ? MessageMapper.extractToolCalls(finalAm) : List.of();

            if (calls.isEmpty()) {
                String finalText = accumulated.toString();
                if (!finalText.isBlank()) history.add(AiChatMessage.assistant(finalText));
                int tokens = Math.max(1, finalText.length() / 4);
                Platform.runLater(() -> callback.onComplete(finalText, tokens, 0));
                return;
            }
            if (round == MAX_TOOL_ROUNDS) {
                String warn = "Reached MAX_TOOL_ROUNDS (" + MAX_TOOL_ROUNDS + ")";
                log.warn(warn);
                Platform.runLater(() -> callback.onComplete(warn, 0, 0));
                return;
            }
            history.add(AiChatMessage.assistantWithTools(accumulated.toString(), calls));
            ToolExecutor.executeAndFeed(calls, history, callback);
        }
    }

    private static String currentSystemPrompt() {
        try { return ZhiFlowSettingUi.getAiSystemPrompt(); }
        catch (Throwable t) { return null; }
    }

    // ── testConnection (used by Settings UI) ──────────────────────────
    // Preserved verbatim from the old CloudChatBackend — raw HTTP probe,
    // independent of the AI library, so connection issues surface as actionable
    // strings rather than wrapped exceptions.

    public String testConnection() {
        return switch (provider) {
            case OPENAI -> testOpenAi();
            case ANTHROPIC -> testAnthropic();
        };
    }

    private String testOpenAi() {
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build()) {
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
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
        }
    }

    private String testAnthropic() {
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build()) {
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
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "HTTP " + resp.statusCode() + ": " + resp.body();
        } catch (Exception e) {
            String msg = e.getMessage();
            return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
        }
    }
}
```

- [ ] **Step 4: Run the tool-loop test**

Run: `mvn -pl ZhiFlow test -o -Dtest=SpringAiCloudBackendToolLoopTest 2>&1 | tail -15`

Expected: `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackend.java ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/SpringAiCloudBackendToolLoopTest.java
git commit -m "feat(ai): add SpringAiCloudBackend (cloud ChatBackend over Spring AI)"
```

---

### Task 11: Relocate `ToolExecutor` + `AiToolDescriptions` (kept helpers) and rewire call sites

**Why:** The wholesale deletion in Task 12 removes `fan.summer.zhiflow.ai.tools.*`. But two of those classes are still referenced by the new backends and by `AiChatPlugin`: `ToolExecutor` (the `executeAndFeed` loop that fires FX-thread UI events) and `AiToolDescriptions` (local/cloud description picker used by `AiToolCallback`), plus `SlashCommandHandler` (used by `AiChatPlugin`). These must be moved OUT of the doomed package before Task 12 deletes it.

**Files:**
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java` (relocated from `tools/`)
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolDescriptions.java` (relocated from `tools/`)
- Create: `ZhiFlow/src/main/java/fan/summer/zhiflow/ai/SlashCommandHandler.java` (relocated from `tools/`)
- Modify: `ZhiFlow/.../buildintool/ai/AiChatPlugin.java` (import change only — `fan.summer.zhiflow.ai.tools.SlashCommandHandler` → `fan.summer.zhiflow.ai.SlashCommandHandler`)
- Modify: `ZhiFlow/.../buildintool/browser/SynchronousChatHelper.java` (if it imports `tools.ToolExecutor`)

**Interfaces:**
- Consumes: the source classes as-is.
- Produces: same API at new packages.

- [ ] **Step 1: Inspect what `AiChatPlugin` and other consumers import from `tools/`**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && grep -rn "fan.summer.zhiflow.ai.tools\." ZhiFlow/src/main/java --include=*.java | grep -v "/ai/tools/"
```

Record the full set of importer→imported pairs. Expect at least: `AiChatPlugin` → `SlashCommandHandler`, `ToolExecutor`; `SynchronousChatHelper` → something. **Anything imported from `tools/` by code outside `ai/tools/` and outside the deletion list must be relocated** — or, if unused, deleted with its consumer updated.

- [ ] **Step 2: Relocate the kept classes (copy file contents verbatim, fix only the `package` line)**

For each class to keep (`ToolExecutor`, `AiToolDescriptions`, `SlashCommandHandler`):
1. Read the source file.
2. Create the new file at the target path with the same content but `package fan.summer.zhiflow.ai;` (or `fan.summer.zhiflow.ai.adapter;` for `AiToolDescriptions`).
3. Update internal references (e.g. `ToolExecutor`'s `import fan.summer.zhiflow.ai.tools.AiToolDescriptions` → `fan.summer.zhiflow.ai.adapter.AiToolDescriptions`; or to its new sibling).
4. Update imports in the new backends and `AiChatPlugin` to point at the new packages.

> The relocation is mechanical (move + repackage, no behaviour change). Do NOT rewrite logic in this task — that risks introducing bugs the tests won't catch. If a class is large, consider leaving it in place and excluding it from the Task 12 delete list instead.

- [ ] **Step 3: Compile**

Run: `mvn -pl ZhiFlow compile -o 2>&1 | tail -30`

Expected: clean compile (the new backends + relocated helpers + consumers all resolve).

- [ ] **Step 4: Run all existing tests that don't depend on deleted code**

Run: `mvn -pl ZhiFlow test -o 2>&1 | tail -30`

Expected: the `MessageMapper`, `ToolSchemaJson`, `AiToolCallback`, `SpringAiCloudBackendToolLoop`, `OllamaLocalBackendConnection` tests pass; tests for `LocalChatBackend`, `CloudChatBackend` (old), `TokenBatcher`, `ThinkingStreamSegmenter`, `ToolCallParser`, `Qwen3Adapter`, `ToolSchemaBuilder`, `AiToolToToolSpecification` still fail (they reference soon-to-be-deleted code) — leave those for Task 13.

- [ ] **Step 5: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/ai/ToolExecutor.java ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolDescriptions.java ZhiFlow/src/main/java/fan/summer/zhiflow/ai/SlashCommandHandler.java
git add ZhiFlow/src/main/java/fan/summer/zhiflow/buildintool/ai/AiChatPlugin.java
# plus any other importer files updated in Step 1
git commit -m "refactor(ai): relocate ToolExecutor/AiToolDescriptions/SlashCommandHandler out of tools/"
```

---

### Task 12: Rewire `ZhiFlowApp` + `ZhiFlowSettingUi` to use the new backends

**This is the cutover.** After this task, the old `CloudChatBackend` / `LocalChatBackend` are no longer referenced by production code; they're dead weight pending deletion in Task 13.

**Files:**
- Modify: `ZhiFlow/.../app/ZhiFlowApp.java`
- Modify: `ZhiFlow/.../ui/setting/ZhiFlowSettingUi.java`

**Interfaces:**
- Consumes: `AiSpringContext`, `SpringAiCloudBackend`, `OllamaLocalBackend`.
- Produces: a running app that uses Spring AI for cloud and Ollama for local.

- [ ] **Step 1: Bootstrap/close the Spring context in `ZhiFlowApp`**

In `ZhiFlowApp.start()`, immediately AFTER `DatabaseInit.init();` (line ~78) and BEFORE `initializeAiBackend()` (line ~108), add:

```java
        // ── Embedded Spring context (DI + Spring AI ChatModel beans) ────
        // Must start after DB init (AiConfigService reads H2) and before
        // initializeAiBackend() (which looks up ChatModel beans).
        fan.summer.zhiflow.ai.spring.AiSpringContext.start();
```

In `ZhiFlowApp.stop()`, BEFORE the existing `mainWindow.shutdown()` call (or after it — pick "after" so UI teardown isn't blocked by bean destruction), add:

```java
        try { fan.summer.zhiflow.ai.spring.AiSpringContext.close(); }
        catch (Exception e) { log.warn("AI Spring context close failed: {}", e.getMessage()); }
```

- [ ] **Step 2: Rewire `initializeAiBackend()` to construct the new backends**

Replace the body of `ZhiFlowApp.initializeAiBackend()` with:

```java
    private void initializeAiBackend() {
        String mode = fan.summer.zhiflow.ai.AiConfigService.getAiMode();
        log.info("AI backend mode: {}", mode);

        switch (mode) {
            case "openai" -> {
                SpringAiCloudBackend svc = SpringAiCloudBackend.openAi(
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiEndpoint(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiApiKey(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiModel()
                );
                AiServiceProvider.switchMode(mode, svc);
                log.info("OpenAI backend initialized (Spring AI): model={}",
                         fan.summer.zhiflow.ai.AiConfigService.getAiOpenAiModel());
            }
            case "anthropic" -> {
                SpringAiCloudBackend svc = SpringAiCloudBackend.anthropic(
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicEndpoint(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicApiKey(),
                    fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicModel()
                );
                AiServiceProvider.switchMode(mode, svc);
                log.info("Anthropic backend initialized (Spring AI): model={}",
                         fan.summer.zhiflow.ai.AiConfigService.getAiAnthropicModel());
            }
            default -> {
                log.info("AI backend: local Ollama (deferred, will initialize when AI tool opens)");
            }
        }
    }
```

Update the `import` for `CloudChatBackend` to `SpringAiCloudBackend`:

```java
import fan.summer.zhiflow.ai.service.SpringAiCloudBackend;
```

- [ ] **Step 3: Find and rewire every `ZhiFlowSettingUi` call site that constructs a backend**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && grep -n "CloudChatBackend\|LocalChatBackend" ZhiFlow/src/main/java/fan/summer/zhiflow/ui/setting/ZhiFlowSettingUi.java
```

For each hit:
- `CloudChatBackend.openAi(...)` → `SpringAiCloudBackend.openAi(...)`
- `CloudChatBackend.anthropic(...)` → `SpringAiCloudBackend.anthropic(...)`
- `new LocalChatBackend(...)` → `new OllamaLocalBackend()`

Update imports accordingly. The `ensureLocalBackend()` lazy-init path should construct `new OllamaLocalBackend()` and call `loadModel(null)` (it will read the tag from H2).

- [ ] **Step 4: Full compile**

Run: `mvn -pl ZhiFlow compile -o 2>&1 | tail -30`

Expected: clean. (The old `CloudChatBackend`/`LocalChatBackend` still exist but are now unreferenced; that's fine — Task 13 deletes them.)

- [ ] **Step 5: Smoke-test the app manually (cloud)**

Run the app (via the IDE run config or `mvn -pl ZhiFlow exec:java -Dexec.mainClass=fan.summer.zhiflow.Launcher`), open Settings → AI, switch to OpenAI/Anthropic, send a chat message. Confirm:
- A streamed response appears token-by-token.
- Tool calls (e.g. Excel analyze) still trigger `onToolCall`/`onToolResult` UI events.
- The conversation history retains the assistant reply across turns.

- [ ] **Step 6: Smoke-test the app manually (local/Ollama)**

Prerequisite: `ollama serve` + `ollama pull qwen3:4b`. Switch AI mode to local, send a message. Confirm a response streams. (Thinking surfacing depends on Task 8's spike outcome.)

- [ ] **Step 7: Commit**

```bash
git add ZhiFlow/src/main/java/fan/summer/zhiflow/app/ZhiFlowApp.java ZhiFlow/src/main/java/fan/summer/zhiflow/ui/setting/ZhiFlowSettingUi.java
git commit -m "feat(ai): rewire app + settings UI to Spring AI cloud / Ollama local backends"
```

---

### Task 13: Delete the legacy AI implementation + native subtree

**This is the payoff.** Everything below is now unreferenced. Deletion is safe because Task 12 verified the app runs on the new path.

**Files:**
- Delete: see lists below.

- [ ] **Step 1: Delete the legacy cloud + local backend implementations**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/CloudChatBackend.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/service/LocalChatBackend.java
```

- [ ] **Step 2: Delete the LangChain4j adapters (replaced by `MessageMapper`/`AiToolCallback`)**

```bash
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/AiToolToToolSpecification.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/adapter/ChatMessageMapper.java
# (MessageMapper.java, AiToolCallback.java, ToolSchemaJson.java, AiToolDescriptions.java STAY)
```

- [ ] **Step 3: Delete the local tool-calling helpers (replaced by Spring AI tool-calling)**

```bash
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/ThinkingStreamSegmenter.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/ToolCallParser.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/Qwen3Adapter.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/ToolSchemaBuilder.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinBase64Tool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinColorConvertTool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinHashTool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/BuiltinJsonFormatTool.java
rm ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools/ToolRegistry.java
# (ToolExecutor.java, AiToolDescriptions.java, SlashCommandHandler.java were relocated in Task 11 — not here)
```

If the `tools/` directory is now empty, delete it:

```bash
rmdir ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tools 2>/dev/null || true
```

- [ ] **Step 4: Delete the local inference engine (GGUF/JNI/worker)**

```bash
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/ai/inference
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/ai/model
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/ai/tensor
rm -r ZhiFlow/src/main/java/fan/summer/zhiflow/ai/nativejni
```

- [ ] **Step 5: Delete the C++ JNI bridge + bundled native library**

```bash
rm -r ZhiFlow/src/main/cpp
rm -r ZhiFlow/src/main/resources/native
```

- [ ] **Step 6: Delete the tests that exercised deleted code**

```bash
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolToToolSpecificationTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/AiToolToToolSpecificationLocalTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/adapter/ChatMessageMapperTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ThinkingStreamSegmenterTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ToolCallParserHermesTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/Qwen3AdapterTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ToolSchemaBuilderLocalTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/AiToolDescriptionsTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/tools/ToolExecutorErrorJsonTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/model/GGUFReaderTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/model/GGUFModelTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/CloudChatBackendTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/LocalChatBackendMaxTokensTest.java
rm ZhiFlow/src/test/java/fan/summer/zhiflow/ai/service/TokenBatcherTest.java
```

> Verify each path exists before `rm`; some may already be gone. Keep `ToolExecutorErrorJsonTest` if `ToolExecutor`'s behaviour is unchanged by relocation — but it likely imports the old package, so update or delete. Prefer delete (coverage of the unchanged `ToolExecutor` is low-value vs the risk of a stale-import compile failure).

- [ ] **Step 7: Full build + test**

Run: `mvn -pl ZhiFlow test -o 2>&1 | tail -30`

Expected: BUILD SUCCESS, all remaining tests pass. If any test fails to compile due to a stale import of a deleted class, fix the import (point at the relocated class from Task 11) or delete that test too if it's exclusively covering deleted code.

- [ ] **Step 8: Confirm zero references to deleted packages remain**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && grep -rn "fan.summer.zhiflow.ai.tools\.\|fan.summer.zhiflow.ai.inference\.\|fan.summer.zhiflow.ai.model\.\|fan.summer.zhiflow.ai.tensor\.\|fan.summer.zhiflow.ai.nativejni\.\|dev.langchain4j" ZhiFlow/src 2>/dev/null
```

Expected: empty output (or only matches in `backup/`, `docs/`, or comments). Any real source hit must be fixed before commit.

- [ ] **Step 9: Commit**

```bash
git add -A ZhiFlow
git commit -m "refactor(ai): delete legacy LC4j cloud + GGUF/JNI/worker local stack (Phase 1 strangler)"
```

---

### Task 14: Update docs + release notes

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/migration-3.2.md` or create `docs/migration-4.0-phase1.md`
- Modify: `README.md` (system requirements: note Ollama as optional local runtime)

- [ ] **Step 1: Add a CHANGELOG entry**

Under the next-version section, add:

```
### AI subsystem — Spring AI 2.0 migration (Phase 1 of 4.0)
- Cloud (OpenAI/Anthropic) now powered by Spring AI 2.0 (was LangChain4j 1.2).
- Local mode now runs on Ollama instead of the bundled GGUF/JNI engine.
  Install Ollama and run `ollama pull qwen3:4b` to use local mode.
- Removed: the in-process GGUF reader, quantised tensor types, llama.cpp JNI
  bridge, native worker process, and Hermes/<think> text parsers. This
  eliminates the hybrid-SIGABRT, empty-answer-truncation, and stdout-IPC bug
  class entirely.
- Plugin `AiTool` contract is unchanged; plugins recompile without changes.
- Desktop UI (JavaFX) is unchanged in this phase.
```

- [ ] **Step 2: Add the Ollama runtime requirement to README**

In the system-requirements section, add a row noting Ollama (optional, for local AI mode) with install instructions.

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md README.md docs/
git commit -m "docs(ai): document Phase 1 Spring AI + Ollama migration"
```

---

## Self-Review (run before declaring the plan done)

**Spec coverage:**
- ✅ Spring Boot 4.1.0 introduced (Task 1) and embedded non-web (Task 2) — matches the "embedded" runtime decision.
- ✅ Spring AI 2.0 takes over cloud (Tasks 4, 10) and local (Tasks 4, 9) — matches "Spring AI 接管 AI 模块".
- ✅ Ollama replaces the local runtime (Tasks 3, 4, 9) — matches "转 Ollama".
- ✅ LangChain4j removed (Task 1) and legacy stack deleted (Task 13) — matches "绞杀式".
- ✅ Plugin contract (`ZhiFlow-Api`) untouched — verified in every task's "Out of scope".
- ✅ JavaFX UI untouched — only `ZhiFlowApp`/`ZhiFlowSettingUi` wiring changes.

**Placeholder scan:** No "TBD"/"implement later" steps. SPIKE markers (Tasks 4, 8) carry explicit fallbacks that don't depend on the uncertain API.

**Type consistency:**
- `MessageMapper.toSpringAi(AiChatMessage)` returns `Message`; consumed in Tasks 9 & 10 as `msgs.add(MessageMapper.toSpringAi(m))`. ✅
- `MessageMapper.extractToolCalls(AssistantMessage)` returns `List<AiToolCall>`; consumed in Tasks 9 & 10. ✅
- `AiToolCallback` implements `ToolCallback`; `getToolDefinition()` returns `ToolDefinition` built via `DefaultToolDefinition.builder()`. ✅
- `AiSpringContext.getBean(String, Class)` — standard `ApplicationContext` API; consumed in Tasks 9 & 10. ✅

**Known risks carried into implementation:**
1. Three SPIKE markers (Task 4 builder method names, Task 8 thinking metadata key) — each has a compiler-checked or fallback path.
2. The `TokenBatcher` (50ms FX-thread batching) is gone; Tasks 9/10 emit tokens directly via `Platform.runLater`. If FX-thread flooding becomes visible, re-introduce a batcher in a follow-up — not in Phase 1.
3. Mid-stream cancellation (`cancelGeneration`) is a no-op in both new backends, matching the old `CloudChatBackend`'s behaviour (LC4j 1.x also couldn't abort). The old `LocalChatBackend` could cancel the Java runner; this is a minor regression for local mode, accepted for Phase 1.
4. Tests for the streaming chat path rely on stub/fake `ChatModel`s; end-to-end validation is the manual smoke-test in Task 12 Steps 5–6.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-06-phase1-ai-strangler-spring-ai.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
2. **Inline Execution** — Execute tasks in this session using superpowers:executing-plans, batch execution with checkpoints.

Which approach?
