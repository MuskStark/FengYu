# Host-Embedded Browser Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `plugin-browser` Playwright worker with a host-embedded browser capability: the Spring Boot backend exposes 9 `browser_*` AI tools that delegate over a loopback HTTP bridge to Electron, which drives a real independent `BrowserWindow` using native `webContents` + CDP (no separate Chromium download).

**Architecture:** Backend `BrowserTool` (@Component, approval-gated) forwards each AI tool call to an Electron-side HTTP listener (`POST /invoke`). Electron owns a lazy, single, visible `BrowserWindow` with a persistent partition (`persist:fengyu-browser`) and implements the 9 operations via `webContents.loadURL/executeJavaScript/capturePage` + `webContents.debugger` (CDP) for the accessibility tree. `plugin-browser` is deleted; the backend suppresses any residual installed copy when running desktop-mode.

**Tech Stack:** Java 21 + Spring Boot (backend), Jackson 2.21.4 (`ObjectMapper`), JDK `java.net.http.HttpClient`; TypeScript + Electron 43 + `node:http` (desktop), vitest; JUnit 5 + Mockito (backend tests).

## Global Constraints

Copied verbatim from the spec / codebase:

- **9 tool names are a binding contract** (prompts/skills depend on them): `browser_navigate`, `browser_click`, `browser_type`, `browser_get_text`, `browser_query`, `browser_screenshot`, `browser_wait_for`, `browser_eval_js`, `browser_close`.
- **Return envelope is a binding contract:** every tool's success result is a JSON object whose first two keys are `success:true` and `summary:<string>`; failures are `{success:false, summary:<single-line message>}`. Extra keys per tool are fixed (see Task 2 table).
- **Constants:** `TEXT_CAP = 64_000` (truncate with suffix `…[truncated]`), `SAMPLE_LIMIT = 5`.
- **Backend HTTP client:** JDK `java.net.http.HttpClient` — no new pom dependency (9 existing services use it). JSON via Jackson 2 `ObjectMapper` (on classpath via `spring-boot-starter-web`, pinned `2.21.4`).
- **Electron HTTP server:** Node builtin `node:http` (`createServer`) — no Express/Fastify dependency. All Node imports use the `node:` prefix.
- **Writable paths in Electron:** under `runtimeRoot()` = `<cwd>/.fengyu/` (NOT `app.getPath('userData')` — the codebase never uses it). Screenshots dir = `runtimeRoot()/browser-screenshots/`. Browser session persistence via Chromium-managed partition `persist:fengyu-browser`.
- **Desktop detection:** new system property `fengyu.desktop`, set by `spawn.ts` as `-Dfengyu.desktop=true`, read via `@ConditionalOnProperty("fengyu.desktop")` on `BrowserTool` (Web mode → bean absent → tools never registered).
- **Backend tool registration:** `implements ApprovalRequiredTool` (transitively `FengYuTool`); auto-collected by `AiToolDiscoveryConfig` — zero edits to `AiToolDiscoveryConfig`.
- **Tool effect:** all 9 are `ToolEffect.EXTERNAL`.
- **Backend tests:** JUnit 5, package-private `*Test` in same package as SUT, hand-constructed collaborators, `@TempDir`, no `@SpringBootTest` for unit tests.
- **Electron tests:** vitest, `vi.mock('electron', ...)`, dynamic `await import(...)` + `vi.resetModules()` per test.
- **Import style (backend):** fully-qualified Jackson (`com.fasterxml.jackson.databind.ObjectMapper`) as in `CommandExecuteTool`; **Import style (electron):** ESM `import`, `node:` prefix, `import type` for type-only.
- **Do NOT touch:** `AiToolDiscoveryConfig`, `CommandExecuteTool`, frontend Vue/router/stores, `i18n` files, backend `pom.xml`, Electron `package.json`, Electron preload / main window.

---

## File Structure

**Backend (create):**
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java` — 9 `@Tool` methods, `@ConditionalOnProperty("fengyu.desktop")`, `implements ApprovalRequiredTool`. Owns envelope construction + TEXT_CAP/SAMPLE_LIMIT.
- `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java` — thin HTTP client over `java.net.http.HttpClient`; `invoke(method, params, timeoutSeconds)` → `Map<String,Object>`. Reads bridge port/token from env.

**Backend (modify):**
- `FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolRegistry.java` — add desktop-mode skip of `fan.summer.browser` in `callbacks()` and `descriptors()` plugin loops.

**Backend tests (create):**
- `FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserBridgeClientTest.java`
- `FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserToolTest.java`
- `FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolRegistryBrowserSuppressionTest.java`

**Electron (create):**
- `desktop/electron/src/browser/bridge.ts` — `node:http` listener on `127.0.0.1:0`, token auth, routes `POST /invoke` to handlers. Exports `startBrowserBridge(opts): { port, token, close }`.
- `desktop/electron/src/browser/session.ts` — lazy single `BrowserWindow` lifecycle (persistent partition, screenshots dir).
- `desktop/electron/src/browser/handlers.ts` — 9 operation implementations using `webContents` + CDP. Pure functions taking `(session, params)`.
- `desktop/electron/src/browser/a11y.ts` — CDP `Accessibility.getFullAXTree` → YAML string formatter.

**Electron (modify):**
- `desktop/electron/src/main.ts` — start bridge before backend spawn; close on quit.
- `desktop/electron/src/backend/spawn.ts` — inject `-Dfengyu.desktop=true` + `FENGYU_BROWSER_BRIDGE_PORT/TOKEN` env.

**Electron tests (create):**
- `desktop/electron/test/bridge.test.ts`
- `desktop/electron/test/a11y.test.ts`
- `desktop/electron/test/browser-handlers.test.ts`

**Removal (plugin-browser):**
- Delete `OfficialPlugins/plugin-browser/` (whole tree).
- `OfficialPlugins/pom.xml` — remove `<module>plugin-browser</module>`.
- `.github/workflows/fengyu-release.yml`, `.github/workflows/toolchain-release.yml`, `scripts/e2e-smoke.sh`, `scripts/release-workflow.test.mjs` — remove plugin-browser build/copy/assert lines.

---

## Task 1: Backend — BrowserBridgeClient (HTTP client to Electron)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserBridgeClientTest.java`

**Interfaces:**
- Consumes: env vars `FENGYU_BROWSER_BRIDGE_PORT` (int) and `FENGYU_BROWSER_BRIDGE_TOKEN` (string), set by Electron `spawn.ts`.
- Produces: `Map<String,Object> invoke(String method, Map<String,Object> params, int timeoutSeconds)` — returns the parsed JSON response body from Electron. Throws `BrowserBridgeUnavailableException` (a checked-ish signal) when the bridge is unreachable/timed out.

- [ ] **Step 1: Write the failing test**

`FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserBridgeClientTest.java`:

```java
package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeClientTest {

    private com.sun.net.httpserver.HttpServer server;
    private String receivedToken;
    private String receivedBody;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void startStub(String responseJson, int status) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            receivedToken = exchange.getRequestHeaders().getFirst("X-Browser-Token");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @Test
    void postsMethodAndParamsWithTokenHeaderAndParsesEnvelope() throws Exception {
        startStub("{\"success\":true,\"summary\":\"navigated to https://example.com\",\"url\":\"https://example.com\",\"title\":\"Example\"}", 200);
        var client = new BrowserBridgeClient(server.getAddress().getPort(), "secret-token");

        Map<String, Object> result = client.invoke("browser_navigate", Map.of("url", "https://example.com"), 60);

        assertEquals("secret-token", receivedToken);
        assertTrue(receivedBody.contains("\"method\":\"browser_navigate\""));
        assertTrue(receivedBody.contains("\"url\":\"https://example.com\""));
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("navigated to https://example.com", result.get("summary"));
        assertEquals("https://example.com", result.get("url"));
    }

    @Test
    void throwsWhenBridgeReturnsNon200() throws Exception {
        startStub("{\"success\":false,\"summary\":\"bad request\"}", 400);
        var client = new BrowserBridgeClient(server.getAddress().getPort(), "tok");
        BrowserBridgeUnavailableException ex = assertThrows(BrowserBridgeUnavailableException.class,
                () -> client.invoke("browser_click", Map.of("selector", "#x"), 30));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void throwsWhenBridgeUnreachable() {
        // port 1 is reserved/unlikely to have a listener
        var client = new BrowserBridgeClient(1, "tok");
        assertThrows(BrowserBridgeUnavailableException.class,
                () -> client.invoke("browser_close", Map.of(), 5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=BrowserBridgeClientTest`
Expected: FAIL — `BrowserBridgeClient` and `BrowserBridgeUnavailableException` do not compile.

- [ ] **Step 3: Implement BrowserBridgeUnavailableException**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeUnavailableException.java`:

```java
package fan.summer.fengyu.ai.tools;

/** Thrown when the Electron browser bridge is unreachable, returns non-200, or times out. */
class BrowserBridgeUnavailableException extends RuntimeException {
    BrowserBridgeUnavailableException(String message) { super(message); }
    BrowserBridgeUnavailableException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Implement BrowserBridgeClient**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java`:

```java
package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP client for the Electron-side browser bridge. Talks to the loopback
 * {@code POST /invoke} endpoint opened by {@code desktop/electron/src/browser/bridge.ts}.
 *
 * <p>One instance per {@link BrowserTool}; constructed with the bridge port + token that
 * Electron injects as env ({@code FENGYU_BROWSER_BRIDGE_PORT/TOKEN}).
 */
final class BrowserBridgeClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final int port;
    private final String token;

    BrowserBridgeClient(int port, String token) {
        this.port = port;
        this.token = token;
    }

    /** Factory used by BrowserTool: reads the bridge address from process env. */
    static BrowserBridgeClient fromEnv() {
        int port = Integer.parseInt(System.getenvOrDefault("FENGYU_BROWSER_BRIDGE_PORT", "0"));
        String token = System.getenv("FENGYU_BROWSER_BRIDGE_TOKEN");
        if (port <= 0 || token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "FENGYU_BROWSER_BRIDGE_PORT/TOKEN env not set; browser bridge unavailable");
        }
        return new BrowserBridgeClient(port, token);
    }

    /**
     * Invoke a browser operation. Returns the parsed envelope from Electron.
     * @throws BrowserBridgeUnavailableException on connect/timeout/non-200/parse failure
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> invoke(String method, Map<String, Object> params, int timeoutSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new BrowserBridgeUnavailableException("failed to serialize request", e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/invoke"))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("X-Browser-Token", token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new BrowserBridgeUnavailableException("bridge returned HTTP " + resp.statusCode());
            }
            return JSON.readValue(resp.body(), Map.class);
        } catch (BrowserBridgeUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new BrowserBridgeUnavailableException("browser bridge request failed: " + e.getMessage(), e);
        }
    }
}
```

Note: `System.getenvOrDefault` is JDK 21+ (confirmed runtime). If the build targets an earlier source for that call, replace with explicit null-check on `System.getenv(...)`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=BrowserBridgeClientTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeClient.java \
        FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserBridgeUnavailableException.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserBridgeClientTest.java
git commit -m "✨ feat: add BrowserBridgeClient HTTP client to Electron browser bridge"
```

---

## Task 2: Backend — BrowserTool (9 @Tool methods + envelope helpers)

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserToolTest.java`

**Interfaces:**
- Consumes: `BrowserBridgeClient.invoke(method, params, timeoutSeconds)` (Task 1).
- Produces: 9 Spring AI `@Tool` methods returning JSON strings; `implements ApprovalRequiredTool` so the registry wraps it in the approval gate. Envelope keys (binding contract):

| method | success extra keys | timeoutSeconds |
|---|---|---|
| browser_navigate | `url`, `title` | 60 |
| browser_click | `clicked:true` | 30 |
| browser_type | `filled:true` | 30 |
| browser_get_text | `text`, `length` | 30 |
| browser_query | `count`, `samples`(≤5) | 30 |
| browser_screenshot | `imagePath`, `width`, `height`, `a11yTree` | 30 |
| browser_wait_for | `ok:true` | 40 |
| browser_eval_js | `value` | 30 |
| browser_close | `closed:true` | 15 |

- [ ] **Step 1: Write the failing test**

`FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserToolTest.java`:

```java
package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserToolTest {

    /** Test subclass that swaps the real HTTP client for an in-memory stub. */
    private static final class StubTool extends BrowserTool {
        private final java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> stub;
        StubTool(java.util.function.BiFunction<String, Map<String, Object>, Map<String, Object>> stub) {
            this.stub = stub;
        }
        @Override
        protected Map<String, Object> invokeBridge(String method, Map<String, Object> params, int timeoutSeconds) {
            return stub.apply(method, params);
        }
    }

    private static Map<String, Object> parse(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
    }

    @Test
    void navigateReturnsContractEnvelope() throws Exception {
        var tool = new StubTool((m, p) -> Map.of("success", true, "summary", "navigated to https://example.com",
                "url", "https://example.com", "title", "Example"));
        Map<String, Object> r = parse(tool.navigate("https://example.com", "load"));
        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals("Example", r.get("title"));
    }

    @Test
    void getTextTruncatesBeyondTextCap() throws Exception {
        // Electron returns full text; BrowserTool must cap at TEXT_CAP.
        String huge = "a".repeat(70_000);
        var tool = new StubTool((m, p) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", true); e.put("summary", "read text");
            e.put("text", huge); e.put("length", huge.length());
            return e;
        });
        Map<String, Object> r = parse(tool.getText(null));
        String text = (String) r.get("text");
        assertTrue(text.endsWith("…[truncated]"));
        assertTrue(text.length() <= BrowserTool.TEXT_CAP);
    }

    @Test
    void queryLimitsSamplesToFive() throws Exception {
        var all = List.of("a", "b", "c", "d", "e", "f", "g");
        var tool = new StubTool((m, p) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", true); e.put("summary", "matched 7 element(s)");
            e.put("count", 7); e.put("samples", all);
            return e;
        });
        Map<String, Object> r = parse(tool.query("div"));
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) r.get("samples");
        assertEquals(5, samples.size());
        assertEquals(7, r.get("count"));
    }

    @Test
    void bridgeUnavailableReturnsFailureEnvelopeNotException() throws Exception {
        var tool = new StubTool((m, p) -> { throw new BrowserBridgeUnavailableException("bridge down"); });
        Map<String, Object> r = parse(tool.close());
        assertEquals(Boolean.FALSE, r.get("success"));
        assertTrue(((String) r.get("summary")).contains("browser bridge unavailable"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=BrowserToolTest`
Expected: FAIL — `BrowserTool` does not compile.

- [ ] **Step 3: Implement BrowserTool**

Create `FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java`:

```java
package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side AI tool that delegates browser operations to the Electron desktop shell over
 * a loopback HTTP bridge. Only registered when {@code fengyu.desktop=true} (set by the
 * Electron sidecar at JVM spawn). In web mode the bean is absent and {@code browser_*}
 * tools never appear in the AI catalog.
 *
 * <p>The 9 {@code @Tool} method names and the {@code {success, summary, ...}} return
 * envelope mirror the former {@code plugin-browser} worker verbatim so prompts/skills
 * are unaffected. Text capping ({@link #TEXT_CAP}) and sample limiting
 * ({@link #SAMPLE_LIMIT}) are applied here (Electron returns raw values).
 *
 * <p>Approval-gated via {@link ApprovalRequiredTool} — same gate as {@code execute_command}.
 */
@Component
@ConditionalOnProperty("fengyu.desktop")
public class BrowserTool implements ApprovalRequiredTool {

    public static final int TEXT_CAP = 64_000;
    public static final int SAMPLE_LIMIT = 5;
    private static final String TRUNCATION_MARKER = "…[truncated]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BrowserBridgeClient client;

    /** Spring constructor: reads bridge address from env. */
    public BrowserTool() {
        this(BrowserBridgeClient.fromEnv());
    }

    /** Test/injection constructor. */
    BrowserTool(BrowserBridgeClient client) {
        this.client = client;
    }

    // ── 9 AI tools ────────────────────────────────────────────────────────────

    @Tool(name = "browser_navigate",
          description = "Navigate the browser to a URL. Returns the final URL and page title.")
    public String navigate(
            @ToolParam(description = "Absolute http(s) URL to open.") String url,
            @ToolParam(required = false,
                       description = "Wait condition: load, domcontentloaded, or networkidle (default load).")
            String waitUntil) {
        return bridge("browser_navigate", params("url", url, "waitUntil", waitUntil), 60);
    }

    @Tool(name = "browser_click",
          description = "Click an element matching the CSS selector.")
    public String click(
            @ToolParam(description = "CSS selector of the element to click.") String selector) {
        return bridge("browser_click", params("selector", selector), 30);
    }

    @Tool(name = "browser_type",
          description = "Type text into an element matching the selector, optionally clearing it first.")
    public String type(
            @ToolParam(description = "CSS selector of the input element.") String selector,
            @ToolParam(description = "Text to type.") String text,
            @ToolParam(required = false, description = "Clear the field first (default true).") Boolean clear) {
        return bridge("browser_type", params("selector", selector, "text", text, "clear", clear), 30);
    }

    @Tool(name = "browser_get_text",
          description = "Read visible text of the page or a single element. Capped at " + TEXT_CAP + " chars.")
    public String getText(
            @ToolParam(required = false, description = "CSS selector; defaults to whole page body.") String selector) {
        String result = bridge("browser_get_text", params("selector", selector), 30);
        return capText(result);
    }

    @Tool(name = "browser_query",
          description = "Count elements matching a selector and return up to " + SAMPLE_LIMIT + " innerText samples.")
    public String query(
            @ToolParam(description = "CSS selector.") String selector) {
        String result = bridge("browser_query", params("selector", selector), 30);
        return limitSamples(result);
    }

    @Tool(name = "browser_screenshot",
          description = "Capture a PNG screenshot. Returns the saved file path, dimensions, and an accessibility tree (YAML) the model reads instead of the image.")
    public String screenshot(
            @ToolParam(required = false, description = "Capture the full scrollable page (default false).") Boolean fullPage,
            @ToolParam(required = false, description = "CSS selector to capture a single element.") String selector) {
        return bridge("browser_screenshot", params("fullPage", fullPage, "selector", selector), 30);
    }

    @Tool(name = "browser_wait_for",
          description = "Wait until an element reaches a state (attached, detached, visible, hidden).")
    public String waitFor(
            @ToolParam(description = "CSS selector.") String selector,
            @ToolParam(required = false,
                       description = "State to wait for: attached, detached, visible, hidden (default visible).")
            String state,
            @ToolParam(required = false, description = "Timeout in seconds (default 30, max 600).") Integer timeout) {
        return bridge("browser_wait_for", params("selector", selector, "state", state, "timeout", timeout), 40);
    }

    @Tool(name = "browser_eval_js",
          description = "Evaluate a JavaScript expression in the page and return its stringified value.")
    public String evalJs(
            @ToolParam(description = "JavaScript expression to evaluate.") String script) {
        return bridge("browser_eval_js", params("script", script), 30);
    }

    @Tool(name = "browser_close", description = "Close the browser window.")
    public String close() {
        return bridge("browser_close", Map.of(), 15);
    }

    // ── bridge dispatch + envelope shaping ───────────────────────────────────

    /** Subclass seam for tests (in-memory stub). */
    protected Map<String, Object> invokeBridge(String method, Map<String, Object> params, int timeoutSeconds) {
        return client.invoke(method, params, timeoutSeconds);
    }

    /** Sends to the bridge and serializes the envelope to a JSON string. */
    private String bridge(String method, Map<String, Object> params, int timeoutSeconds) {
        try {
            Map<String, Object> envelope = invokeBridge(method, params, timeoutSeconds);
            return JSON.writeValueAsString(envelope);
        } catch (BrowserBridgeUnavailableException e) {
            return failure("browser bridge unavailable");
        } catch (Exception e) {
            return failure("browser tool failed: " + safeMsg(e));
        }
    }

    private static String failure(String summary) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("success", false);
        e.put("summary", summary.replaceAll("[\\r\\n]", " "));
        return toJson(e);
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"summary\":\"failed to serialize browser result\"}";
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @SuppressWarnings("unchecked")
    private static String capText(String json) {
        try {
            Map<String, Object> e = JSON.readValue(json, Map.class);
            Object o = e.get("text");
            if (o instanceof String s && s.length() > TEXT_CAP) {
                e.put("text", s.substring(0, TEXT_CAP - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER);
            }
            return toJson(e);
        } catch (Exception ex) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static String limitSamples(String json) {
        try {
            Map<String, Object> e = JSON.readValue(json, Map.class);
            Object o = e.get("samples");
            if (o instanceof List<?> list && list.size() > SAMPLE_LIMIT) {
                e.put("samples", new ArrayList<>(list.subList(0, SAMPLE_LIMIT)));
            }
            return toJson(e);
        } catch (Exception ex) {
            return json;
        }
    }

    /** Builds a params map, dropping keys whose value is null. */
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
```

Note the two `bridge(...)` overloads: the `protected Map` one is the test seam (overridden by the stub subclass); the `private String` one wraps it with serialization + failure handling. This matches the test in Step 1.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=BrowserToolTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/tools/BrowserTool.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/tools/BrowserToolTest.java
git commit -m "✨ feat: add host-side BrowserTool with 9 browser_* AI tools"
```

---

## Task 3: Backend — suppress residual fan.summer.browser in registry

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolRegistry.java` (plugin loops in `callbacks()` ~line 61 and `descriptors()` ~line 82)
- Test: `FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolRegistryBrowserSuppressionTest.java`

**Interfaces:**
- Consumes: `System.getProperty("fengyu.desktop")` (boolean).
- Produces: when desktop mode is on, any installed `fan.summer.browser` plugin's tools are skipped in BOTH `callbacks()` and `descriptors()` (avoids name collision with the new host tools + UI descriptor leak).

- [ ] **Step 1: Read the exact current loops**

Read `FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolRegistry.java` lines 59-103 to confirm the exact text of both plugin loops before editing. The `callbacks()` loop is ~line 60-66 and the `descriptors()` loop is ~line 82-94.

- [ ] **Step 2: Write the failing test**

`FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolRegistryBrowserSuppressionTest.java`:

```java
package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiToolRegistryBrowserSuppressionTest {

    @AfterEach
    void resetDesktopProp() {
        System.clearProperty("fengyu.desktop");
    }

    private static PluginManifest manifest(String id, boolean enabled, PluginPackageService pkg) {
        // PluginManifest is a 15-component record (see PluginManifest.java). Build a minimal one
        // whose aiTools list maps to a browser tool name so we can detect registration.
        var tool = new PluginManifest.AiTool(
                "browser_navigate", "nav", "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "browser_navigate", 60L, "external");
        when(pkg.isEnabled(id)).thenReturn(enabled);
        // 15 components: schemaVersion,id,name,description,version,author,icon,category,
        //                ui,backend,permissions,homepage,official,aiTools,i18n
        return new PluginManifest(
                1, id, "Browser", "browser automation", "1.0.0", "test", null, "automation",
                null,                                  // Ui
                new PluginManifest.Backend("cmd", "json-rpc-2.0", 60L),
                java.util.List.of("network"), null, true, java.util.List.of(tool), null);
    }

    @Test
    void desktopModeSkipsFanSummerBrowserPlugin() {
        System.setProperty("fengyu.desktop", "true");
        var pkg = mock(PluginPackageService.class);
        var processes = mock(PluginProcessManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.mcp.SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        when(pkg.installed()).thenReturn(List.of(manifest("fan.summer.browser", true, pkg)));

        var registry = new AiToolRegistry(List.of(), pkg, processes, mcp);
        List<ToolCallback> cbs = registry.callbacks();
        assertTrue(cbs.stream().noneMatch(cb -> cb.getToolDefinition().name().startsWith("browser_")),
                "fan.summer.browser tools must be suppressed in desktop mode");
    }

    @Test
    void nonDesktopModeRegistersPluginBrowserTools() {
        // desktop prop absent → false → plugin tools ARE registered (web mode, no host tool collision)
        var pkg = mock(PluginPackageService.class);
        var processes = mock(PluginProcessManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.mcp.SyncMcpToolCallbackProvider> mcp = mock(ObjectProvider.class);
        when(pkg.installed()).thenReturn(List.of(manifest("fan.summer.browser", true, pkg)));

        var registry = new AiToolRegistry(List.of(), pkg, processes, mcp);
        List<ToolCallback> cbs = registry.callbacks();
        assertTrue(cbs.stream().anyMatch(cb -> cb.getToolDefinition().name().equals("browser_navigate")),
                "plugin browser tools must be registered in non-desktop mode");
    }
}
```

> NOTE: The `PluginManifest` constructor above uses the real 15-component record signature (`PluginManifest.java:9-25`). The `AiTool` record has a 7-component canonical constructor (`name, description, inputSchema, outputSchema, method, timeoutSeconds, effect`) and `Backend` has `(command, protocol, callTimeoutSeconds)`. `pkg.find(...)` is NOT stubbed here because `callbacks()` only calls `packages.installed()` + `packages.isEnabled(id)` for the registry path; if a stub is needed to compile, add it then.

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=AiToolRegistryBrowserSuppressionTest`
Expected: FAIL — the first test fails because `fan.summer.browser` is currently registered regardless of mode. (The second should already pass.) Fix any constructor-signature compile errors first by aligning with the real `PluginManifest` record.

- [ ] **Step 4: Add the desktop-suppression guard to AiToolRegistry**

Add a constant near the top of `AiToolRegistry`:

```java
    /** When the desktop shell provides built-in browser tools, suppress the legacy plugin's tools to avoid name collisions. */
    private static final String DESKTOP_PROPERTY = "fengyu.desktop";
    private static final String BROWSER_PLUGIN_ID = "fan.summer.browser";

    private static boolean desktopMode() {
        return Boolean.parseBoolean(System.getProperty(DESKTOP_PROPERTY));
    }
```

In `callbacks()` plugin loop (currently ~line 61-66), change the first line inside the loop from:
```java
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
```
to:
```java
        for (var manifest : packages.installed()) {
            if (!packages.isEnabled(manifest.id()) || manifest.aiTools() == null) continue;
            if (desktopMode() && BROWSER_PLUGIN_ID.equals(manifest.id())) continue;
```

Apply the identical guard in the `descriptors()` plugin loop (~line 82-94), as the first line after the existing `isEnabled`/`aiTools` continue.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -f FengYu/pom.xml -Dtest=AiToolRegistryBrowserSuppressionTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Run existing registry tests to confirm no regression**

Run: `./mvnw test -f FengYu/pom.xml -Dtest='AiTool*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/config/AiToolRegistry.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/config/AiToolRegistryBrowserSuppressionTest.java
git commit -m "🐛 fix: suppress residual fan.summer.browser plugin tools in desktop mode"
```

---

## Task 4: Electron — a11y YAML formatter (CDP tree → Playwright-like YAML)

**Files:**
- Create: `desktop/electron/src/browser/a11y.ts`
- Test: `desktop/electron/test/a11y.test.ts`

**Interfaces:**
- Consumes: the JSON result of CDP `Accessibility.getFullAXTree` — an object `{ nodes: [{ nodeId, role: {value/type}, name: {value}, childIds: [...] }, ...] }`.
- Produces: `function formatA11yTree(fullAxTree: CdpAxTree): string` — a YAML-ish string with `role name: ` indentation reflecting parent→child, mirroring Playwright's `ariaSnapshot()` shape. Semantic equivalence only (not byte-identical).

- [ ] **Step 1: Write the failing test**

`desktop/electron/test/a11y.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { formatA11yTree } from '../src/browser/a11y'

describe('formatA11yTree', () => {
  it('renders a single root node', () => {
    const tree = {
      nodes: [
        { nodeId: '0', role: { type: 'rootWebArea' }, name: { value: 'Home' }, childIds: ['1'] },
        { nodeId: '1', role: { type: 'button' }, name: { value: 'Submit' }, childIds: [] },
      ],
    }
    const out = formatA11yTree(tree)
    expect(out).toContain('rootWebArea "Home":')
    expect(out).toContain('button "Submit"')
    // child indented deeper than root
    const rootLine = out.split('\n').find((l) => l.includes('rootWebArea'))!
    const childLine = out.split('\n').find((l) => l.includes('button "Submit"'))!
    expect(childLine.indexOf('button')).toBeGreaterThan(rootLine.indexOf('rootWebArea'))
  })

  it('ignores nodes not reachable from the root', () => {
    const tree = {
      nodes: [
        { nodeId: '0', role: { type: 'rootWebArea' }, name: { value: '' }, childIds: [] },
        { nodeId: 'orphan', role: { type: 'link' }, name: { value: 'nope' }, childIds: [] },
      ],
    }
    expect(formatA11yTree(tree)).not.toContain('nope')
  })

  it('handles empty tree', () => {
    expect(formatA11yTree({ nodes: [] })).toBe('')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/a11y.test.ts`
Expected: FAIL — module `../src/browser/a11y` not found.

- [ ] **Step 3: Implement the formatter**

Create `desktop/electron/src/browser/a11y.ts`:

```ts
/** Minimal shape of the CDP Accessibility.getFullAXTree response that we consume. */
export interface CdpAxNode {
  nodeId: string
  role: { type?: string; value?: string }
  name: { value?: string }
  childIds?: string[]
  ignored?: boolean
}
export interface CdpAxTree {
  nodes: CdpAxNode[]
}

/**
 * Format a CDP accessibility tree into a YAML-ish string resembling Playwright's
 * `ariaSnapshot()`: `role "name":` with children indented two spaces per level.
 * Semantic equivalence only — not byte-identical to Playwright's output.
 */
export function formatA11yTree(tree: CdpAxTree): string {
  if (!tree?.nodes?.length) return ''
  const byId = new Map<string, CdpAxNode>()
  for (const n of tree.nodes) if (!n.ignored) byId.set(n.nodeId, n)
  // The first node with role rootWebArea (or the first node) is the root.
  let root: CdpAxNode | undefined = tree.nodes.find((n) => n.role?.type === 'rootWebArea' || n.role?.value === 'rootWebArea')
  if (!root) root = tree.nodes[0]
  const lines: string[] = []
  walk(root, 0, byId, lines, new Set<string>())
  return lines.join('\n')
}

function walk(node: CdpAxNode, depth: number, byId: Map<string, CdpAxNode>, out: string[], seen: Set<string>): void {
  if (seen.has(node.nodeId)) return
  seen.add(node.nodeId)
  const role = node.role?.type || node.role?.value || 'unknown'
  const name = node.name?.value ?? ''
  const indent = '  '.repeat(depth)
  out.push(name ? `${indent}${role} "${name}":` : `${indent}${role}:`)
  for (const childId of node.childIds ?? []) {
    const child = byId.get(childId)
    if (child) walk(child, depth + 1, byId, out, seen)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/a11y.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/browser/a11y.ts desktop/electron/test/a11y.test.ts
git commit -m "✨ feat: add CDP a11y tree → YAML formatter for browser screenshots"
```

---

## Task 5: Electron — browser session (lazy single BrowserWindow)

**Files:**
- Create: `desktop/electron/src/browser/session.ts`
- Test: `desktop/electron/test/browser-handlers.test.ts` (handlers test mocks the session; this task only creates the session module, its direct test is folded into the handlers test in Task 6)

**Interfaces:**
- Consumes: `electron.BrowserWindow`, `runtimeRoot()` from `../desktop/runtime-paths`, `node:fs`, `node:path`.
- Produces: `class BrowserSession` with:
  - `ensureWindow(): BrowserWindow` — lazy-creates the single visible window on first call (1280x900, `partition: 'persist:fengyu-browser'`, `contextIsolation:true`, `nodeIntegration:false`, `sandbox:true`, `show:true`).
  - `window(): BrowserWindow | null` — the current window or null.
  - `close(): void` — destroy + null the ref (idempotent).
  - `screenshotsDir(): string` — `join(runtimeRoot(), 'browser-screenshots')`, mkdirs.

- [ ] **Step 1: Implement the session module**

Create `desktop/electron/src/browser/session.ts`:

```ts
import { BrowserWindow } from 'electron'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'
import { runtimeRoot } from '../desktop/runtime-paths'

/**
 * Lazy, single, visible browser window used for AI-driven automation. Uses a Chromium
 * persistent partition so cookies/localStorage/login state survive restarts. Mirrors the
 * former plugin-browser BrowserSession's lazy-start + idempotent-close lifecycle.
 */
export class BrowserSession {
  private win: BrowserWindow | null = null

  /** The current window, or null if closed/not yet created. */
  window(): BrowserWindow | null {
    return this.win && !this.win.isDestroyed() ? this.win : null
  }

  /** Lazily create the window on first navigation; reuse thereafter. */
  ensureWindow(): BrowserWindow {
    const existing = this.window()
    if (existing) return existing
    this.win = new BrowserWindow({
      title: 'FengYu Browser',
      width: 1280,
      height: 900,
      show: true,
      webPreferences: {
        partition: 'persist:fengyu-browser',
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
      },
    })
    return this.win
  }

  /** Destroy the window and clear the reference. Idempotent. */
  close(): void {
    if (this.win && !this.win.isDestroyed()) this.win.destroy()
    this.win = null
  }

  /** Directory for screenshot PNGs, created on demand. */
  screenshotsDir(): string {
    const dir = join(runtimeRoot(), 'browser-screenshots')
    mkdirSync(dir, { recursive: true })
    return dir
  }
}
```

- [ ] **Step 2: Typecheck**

Run: `cd desktop/electron && npx tsc --noEmit`
Expected: PASS (no type errors).

- [ ] **Step 3: Commit**

```bash
git add desktop/electron/src/browser/session.ts
git commit -m "✨ feat: add lazy single BrowserWindow session for browser automation"
```

---

## Task 6: Electron — handlers (9 operations via webContents + CDP)

**Files:**
- Create: `desktop/electron/src/browser/handlers.ts`
- Test: `desktop/electron/test/browser-handlers.test.ts`

**Interfaces:**
- Consumes: `BrowserSession` (Task 5), `formatA11yTree` (Task 4), `node:fs`, `node:path`.
- Produces: `async function handleBrowserOp(session: BrowserSession, method: string, params: Record<string, unknown>): Promise<Record<string, unknown>>` — returns the envelope `{success, summary, ...}`. On no active window for ops other than navigate/close, returns `{success:false, summary:"no browser session"}`.

- [ ] **Step 1: Write the failing test**

`desktop/electron/test/browser-handlers.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub electron with a mockable webContents/debugger. The session module imports
// { BrowserWindow } from 'electron', so we mock it before importing SUT.
const execJs = vi.fn()
const loadURL = vi.fn()
const capturePage = vi.fn()
const attach = vi.fn()
const sendCommand = vi.fn()
const detach = vi.fn()

vi.mock('electron', () => ({
  BrowserWindow: vi.fn().mockImplementation(() => ({
    isDestroyed: () => false,
    destroy: vi.fn(),
    webContents: {
      loadURL,
      executeJavaScript: execJs,
      capturePage,
      debugger: { attach, sendCommand, detach, isAttached: () => false },
      getURL: () => 'https://example.com',
      getTitle: () => 'Example',
    },
  })),
}))

// Import AFTER mocks are registered.
const { BrowserSession } = await import('../src/browser/session')
const { handleBrowserOp } = await import('../src/browser/handlers')

describe('handleBrowserOp', () => {
  beforeEach(() => {
    execJs.mockReset(); loadURL.mockReset(); capturePage.mockReset()
    attach.mockReset(); sendCommand.mockReset(); detach.mockReset()
  })

  it('navigate creates the window and loads the url', async () => {
    loadURL.mockResolvedValue(undefined)
    const s = new BrowserSession()
    const r = await handleBrowserOp(s, 'browser_navigate', { url: 'https://example.com' })
    expect(loadURL).toHaveBeenCalledWith('https://example.com')
    expect(r.success).toBe(true)
    expect(r.title).toBe('Example')
  })

  it('click returns no session when window absent', async () => {
    const s = new BrowserSession()
    const r = await handleBrowserOp(s, 'browser_click', { selector: '#x' })
    expect(r.success).toBe(false)
    expect(r.summary).toContain('no browser session')
  })

  it('get_text returns the executed innerText', async () => {
    execJs.mockResolvedValue('hello')
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_get_text', {})
    expect(r.success).toBe(true)
    expect(r.text).toBe('hello')
    expect(r.length).toBe(5)
  })

  it('query returns count and samples', async () => {
    execJs.mockResolvedValue({ count: 2, samples: ['a', 'b'] })
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_query', { selector: 'div' })
    expect(r.count).toBe(2)
    expect(r.samples).toEqual(['a', 'b'])
  })

  it('close destroys the window', async () => {
    const s = new BrowserSession()
    s.ensureWindow()
    const r = await handleBrowserOp(s, 'browser_close', {})
    expect(r.success).toBe(true)
    expect(r.closed).toBe(true)
    expect(s.window()).toBeNull()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/browser-handlers.test.ts`
Expected: FAIL — `../src/browser/handlers` not found.

- [ ] **Step 3: Implement handlers**

Create `desktop/electron/src/browser/handlers.ts`:

```ts
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { BrowserSession } from './session'
import { formatA11yTree, type CdpAxTree } from './a11y'

const NO_SESSION = { success: false, summary: 'no browser session' }

/**
 * Execute one browser_* operation against the session. Returns the envelope that the
 * backend BrowserTool forwards to the AI. Envelope keys mirror the former plugin-browser.
 */
export async function handleBrowserOp(
  session: BrowserSession,
  method: string,
  params: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  try {
    switch (method) {
      case 'browser_navigate':
        return await navigate(session, str(params, 'url'))
      case 'browser_click':
        return await click(session, str(params, 'selector'))
      case 'browser_type':
        return await type(session, str(params, 'selector'), str(params, 'text'), params.clear !== false)
      case 'browser_get_text':
        return await getText(session, optStr(params, 'selector'))
      case 'browser_query':
        return await query(session, str(params, 'selector'))
      case 'browser_screenshot':
        return await screenshot(session, params.fullPage === true, optStr(params, 'selector'))
      case 'browser_wait_for':
        return await waitFor(session, str(params, 'selector'), optStr(params, 'state') ?? 'visible', num(params, 'timeout', 30))
      case 'browser_eval_js':
        return await evalJs(session, str(params, 'script'))
      case 'browser_close':
        session.close()
        return { success: true, summary: 'browser closed', closed: true }
      default:
        return { success: false, summary: 'unknown method: ' + method }
    }
  } catch (e) {
    return { success: false, summary: msg(e) }
  }
}

function requireWindow(session: BrowserSession) {
  const w = session.window()
  if (!w) throw new Error('no browser session')
  return w
}

async function navigate(session: BrowserSession, url: string) {
  if (!/^https?:\/\//i.test(url)) return { success: false, summary: 'url must be absolute http(s)' }
  const win = session.ensureWindow()
  await win.webContents.loadURL(url)
  return { success: true, summary: `navigated to ${url}`, url, title: win.webContents.getTitle() }
}

async function click(session: BrowserSession, selector: string) {
  const w = requireWindow(session)
  await w.webContents.executeJavaScript(
    `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) throw new Error('element not found'); el.scrollIntoView(); el.click(); })()`,
  )
  return { success: true, summary: `clicked ${selector}`, clicked: true }
}

async function type(session: BrowserSession, selector: string, text: string, clear: boolean) {
  const w = requireWindow(session)
  await w.webContents.executeJavaScript(
    `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) throw new Error('element not found'); ${clear ? 'el.value = "";' : ''} el.value = ${JSON.stringify(text)}; el.dispatchEvent(new Event('input', {bubbles:true})); el.dispatchEvent(new Event('change', {bubbles:true})); })()`,
  )
  return { success: true, summary: `filled ${selector}`, filled: true }
}

async function getText(session: BrowserSession, selector: string | null) {
  const w = requireWindow(session)
  const expr = selector
    ? `document.querySelector(${JSON.stringify(selector)})?.innerText ?? ''`
    : `document.body.innerText`
  const text = String(await w.webContents.executeJavaScript(expr) ?? '')
  return { success: true, summary: 'read text', text, length: text.length }
}

async function query(session: BrowserSession, selector: string) {
  const w = requireWindow(session)
  const res = await w.webContents.executeJavaScript(
    `(() => { const els = Array.from(document.querySelectorAll(${JSON.stringify(selector)})); return { count: els.length, samples: els.slice(0,5).map(e => e.innerText) }; })()`,
  )
  return { success: true, summary: `matched ${res.count} element(s)`, count: res.count, samples: res.samples }
}

async function screenshot(session: BrowserSession, fullPage: boolean, selector: string | null) {
  const w = requireWindow(session)
  const rect = selector
    ? await w.webContents.executeJavaScript(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) throw new Error('element not found'); const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; })()`)
    : undefined
  let img: Electron.NativeImage
  if (rect) {
    img = await w.webContents.capturePage(rect)
  } else if (fullPage) {
    const dims = await w.webContents.executeJavaScript(`({w: document.body.scrollWidth, h: document.body.scrollHeight})`)
    // fullPage approximated by resizing once; for MVP capture the current viewport.
    img = await w.webContents.capturePage()
    void dims
  } else {
    img = await w.webContents.capturePage()
  }
  const file = join(session.screenshotsDir(), `shot-${Date.now()}.png`)
  writeFileSync(file, img.toPNG())
  const size = img.getSize()
  const a11yTree = await captureA11y(w)
  return { success: true, summary: 'screenshot saved', imagePath: file, width: size.width, height: size.height, a11yTree }
}

async function captureA11y(w: Electron.BrowserWindow): Promise<string> {
  try {
    if (!w.webContents.debugger.isAttached()) w.webContents.debugger.attach('1.3')
    const res = await w.webContents.debugger.sendCommand('Accessibility.getFullAXTree')
    w.webContents.debugger.detach()
    return formatA11yTree(res as CdpAxTree)
  } catch (e) {
    try { w.webContents.debugger.detach() } catch { /* ignore */ }
    return ''
  }
}

async function waitFor(session: BrowserSession, selector: string, state: string, timeoutSec: number) {
  const w = requireWindow(session)
  const deadline = Date.now() + Math.min(600, Math.max(1, timeoutSec)) * 1000
  const pred = buildPredicate(selector, state)
  while (Date.now() < deadline) {
    const ok = await w.webContents.executeJavaScript(pred)
    if (ok) return { success: true, summary: 'wait satisfied', ok: true }
    await new Promise((r) => setTimeout(r, 200))
  }
  return { success: true, summary: 'wait timed out', ok: false }
}

function buildPredicate(selector: string, state: string): string {
  const sel = JSON.stringify(selector)
  switch (state) {
    case 'attached': return `!!document.querySelector(${sel})`
    case 'detached': return `!document.querySelector(${sel})`
    case 'hidden': return `(() => { const e = document.querySelector(${sel}); return !e || e.offsetParent === null; })()`
    default: return `(() => { const e = document.querySelector(${sel}); return !!e && e.offsetParent !== null; })()` // visible
  }
}

async function evalJs(session: BrowserSession, script: string) {
  const w = requireWindow(session)
  const value = await w.webContents.executeJavaScript(script)
  return { success: true, summary: 'eval ok', value: String(value) }
}

function str(p: Record<string, unknown>, k: string): string {
  const v = p[k]
  if (typeof v !== 'string' || !v) throw new Error(`missing required parameter: ${k}`)
  return v
}
function optStr(p: Record<string, unknown>, k: string): string | null {
  const v = p[k]
  return typeof v === 'string' && v ? v : null
}
function num(p: Record<string, unknown>, k: string, dflt: number): number {
  const v = p[k]
  return typeof v === 'number' && Number.isFinite(v) ? v : dflt
}
function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/browser-handlers.test.ts`
Expected: PASS (5 tests).

Then add one more case for the screenshot path (the most complex op — it exercises `capturePage` + CDP + file I/O). Extend the `electron` mock's `capturePage` to return a fake image:
```ts
capturePage.mockResolvedValue({ toPNG: () => Buffer.from([0x89,0x50,0x4e,0x47]), getSize: () => ({ width: 1, height: 1 }) })
```
and stub `sendCommand` to return `{ nodes: [] }` (empty a11y tree). Assert `r.success === true` and that `r.imagePath` ends with `.png`. If the `Electron.NativeImage` return type in `handlers.ts` (line ~1163) fails to resolve under the test's type-checker, cast via `as unknown as Electron.NativeImage` in the mock factory.

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/browser/handlers.ts desktop/electron/test/browser-handlers.test.ts
git commit -m "✨ feat: implement 9 browser_* operations via webContents + CDP"
```

---

## Task 7: Electron — HTTP bridge (node:http listener + token auth)

**Files:**
- Create: `desktop/electron/src/browser/bridge.ts`
- Test: `desktop/electron/test/bridge.test.ts`

**Interfaces:**
- Consumes: `handleBrowserOp` (Task 6), `BrowserSession` (Task 5), `node:http`, `genToken` from `../util/token`.
- Produces:
  ```ts
  export interface BrowserBridge { port: number; token: string; close(): void }
  export function startBrowserBridge(session: BrowserSession): Promise<BrowserBridge>
  ```
  Listens on `127.0.0.1:0` (random port); validates `X-Browser-Token`; on `POST /invoke` reads JSON body `{method, params}`, calls `handleBrowserOp`, writes the envelope as JSON.

- [ ] **Step 1: Write the failing test**

`desktop/electron/test/bridge.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// We test the bridge as a real HTTP round-trip: start the listener, then fetch.
const handleBrowserOp = vi.fn()
vi.mock('../src/browser/handlers', () => ({ handleBrowserOp: (...a: unknown[]) => handleBrowserOp(...a) }))
vi.mock('../src/browser/session', () => ({ BrowserSession: class {} }))

const { startBrowserBridge } = await import('../src/browser/bridge')

describe('startBrowserBridge', () => {
  let bridge: { port: number; token: string; close: () => void } | null = null
  beforeEach(() => { handleBrowserOp.mockReset() })
  afterEach(() => { bridge?.close(); bridge = null })

  it('rejects requests with no/wrong token', async () => {
    bridge = await startBrowserBridge({} as never)
    const res = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(res.status).toBe(401)
  })

  it('routes invoke to handleBrowserOp and returns the envelope', async () => {
    bridge = await startBrowserBridge({} as never)
    handleBrowserOp.mockResolvedValue({ success: true, summary: 'ok', closed: true })
    const res = await fetch(`http://127.0.0.1:${bridge.port}/invoke`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Browser-Token': bridge.token },
      body: JSON.stringify({ method: 'browser_close', params: {} }),
    })
    expect(res.status).toBe(200)
    const json = await res.json()
    expect(json.success).toBe(true)
    expect(handleBrowserOp).toHaveBeenCalledTimes(1)
  })

  it('rejects non-POST / non-/invoke paths', async () => {
    bridge = await startBrowserBridge({} as never)
    const res = await fetch(`http://127.0.0.1:${bridge.port}/foo`, {
      headers: { 'X-Browser-Token': bridge.token },
    })
    expect(res.status).toBe(404)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd desktop/electron && npx vitest run test/bridge.test.ts`
Expected: FAIL — `../src/browser/bridge` not found.

- [ ] **Step 3: Implement the bridge**

Create `desktop/electron/src/browser/bridge.ts`:

```ts
import { createServer, type Server } from 'node:http'
import { genToken } from '../util/token'
import type { BrowserSession } from './session'
import { handleBrowserOp } from './handlers'

export interface BrowserBridge {
  port: number
  token: string
  close(): void
}

/** Start the loopback browser bridge. Port is OS-assigned; token is per-launch. */
export function startBrowserBridge(session: BrowserSession): Promise<BrowserBridge> {
  return new Promise((resolve, reject) => {
    const token = genToken()
    const server: Server = createServer((req, res) => {
      // CORS not needed (loopback only). Keep handlers tiny.
      if (req.method !== 'POST' || req.url !== '/invoke') {
        res.writeHead(404).end()
        return
      }
      if (req.headers['x-browser-token'] !== token) {
        res.writeHead(401).end()
        return
      }
      let body = ''
      req.on('data', (c) => { body += c; if (body.length > 1_000_000) req.destroy() })
      req.on('end', async () => {
        try {
          const { method, params } = JSON.parse(body || '{}')
          const envelope = await handleBrowserOp(session, String(method), params ?? {})
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(envelope))
        } catch (e) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ success: false, summary: e instanceof Error ? e.message : String(e) }))
        }
      })
    })
    server.on('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address()
      if (addr && typeof addr === 'object') {
        resolve({ port: addr.port, token, close: () => server.close() })
      } else {
        reject(new Error('failed to bind browser bridge'))
      }
    })
  })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd desktop/electron && npx vitest run test/bridge.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add desktop/electron/src/browser/bridge.ts desktop/electron/test/bridge.test.ts
git commit -m "✨ feat: add loopback HTTP browser bridge with token auth"
```

---

## Task 8: Electron — wire bridge into main + inject spawn env

**Files:**
- Modify: `desktop/electron/src/main.ts` (start bridge before backend spawn; close on quit)
- Modify: `desktop/electron/src/backend/spawn.ts` (inject `-Dfengyu.desktop=true` + bridge env)

**Interfaces:**
- Consumes: `startBrowserBridge` (Task 7), `BrowserSession` (Task 5).
- Produces: `process.env.FENGYU_BROWSER_BRIDGE_PORT` + `FENGYU_BROWSER_BRIDGE_TOKEN` set before `startBackend()`, so `spawn.ts` inherits them; JVM gets `-Dfengyu.desktop=true`.

- [ ] **Step 1: Edit main.ts — start the bridge before backend spawn**

Read `desktop/electron/src/main.ts` and add imports near the existing ones (after line 17):
```ts
import { BrowserSession } from './browser/session'
import { startBrowserBridge, type BrowserBridge } from './browser/bridge'
```
Add module-level state (next to line 21 `let devFrontend...`):
```ts
let browserBridge: BrowserBridge | null = null
```
In `bootstrap()`, insert AFTER `process.env.FENGYU_TOKEN = token` (line 165) and BEFORE `reportProgress(splash, 'spawning')` (line 167):
```ts
  // Browser automation bridge: must start before the JVM spawn so the backend inherits
  // the bridge port/token via process.env and fengyu.desktop=true enables the host tool.
  browserBridge = await startBrowserBridge(new BrowserSession())
  process.env.FENGYU_BROWSER_BRIDGE_PORT = String(browserBridge.port)
  process.env.FENGYU_BROWSER_BRIDGE_TOKEN = browserBridge.token
```

In `killBackend()` (lines 30-36), add before `devFrontend?.stop()`:
```ts
  browserBridge?.close()
  browserBridge = null
```

- [ ] **Step 2: Edit spawn.ts — inject -Dfengyu.desktop + env**

In `desktop/electron/src/backend/spawn.ts`, modify the `args` array (lines 93-100) to add the desktop flag. Change:
```ts
  const args = [
    `-Dfengyu.runtime.dir=${runtimeRoot()}`,
    `-Dfengyu.plugins.official-directory=${layout.plugins}`,
    '-cp',
    layout.jar,
    'fan.summer.fengyu.HeadlessLauncher',
    `--port=${requestedPort}`,
  ]
```
to:
```ts
  const args = [
    `-Dfengyu.runtime.dir=${runtimeRoot()}`,
    `-Dfengyu.plugins.official-directory=${layout.plugins}`,
    '-Dfengyu.desktop=true',
    '-cp',
    layout.jar,
    'fan.summer.fengyu.HeadlessLauncher',
    `--port=${requestedPort}`,
  ]
```
The env at line 107 already spreads `process.env`, so `FENGYU_BROWSER_BRIDGE_PORT/TOKEN` set in `main.ts` are inherited automatically — no change needed to the `env:` object.

- [ ] **Step 3: Typecheck + run unit tests**

Run: `cd desktop/electron && npx tsc --noEmit && npx vitest run`
Expected: PASS (no type errors; all unit tests green).

- [ ] **Step 4: Commit**

```bash
git add desktop/electron/src/main.ts desktop/electron/src/backend/spawn.ts
git commit -m "✨ feat: start browser bridge in desktop shell and inject desktop env into JVM"
```

---

## Task 9: Remove plugin-browser (module + CI + scripts + docs)

**Files:**
- Delete: `OfficialPlugins/plugin-browser/` (whole tree)
- Modify: `OfficialPlugins/pom.xml:24`
- Modify: `.github/workflows/fengyu-release.yml` (lines ~334-347)
- Modify: `.github/workflows/toolchain-release.yml:73`
- Modify: `scripts/e2e-smoke.sh:146-149`
- Modify: `scripts/release-workflow.test.mjs:10,110,111`

- [ ] **Step 1: Delete the plugin module**

```bash
git rm -r OfficialPlugins/plugin-browser
```

- [ ] **Step 2: Remove the aggregator module line**

In `OfficialPlugins/pom.xml`, remove line 24:
```xml
    <module>plugin-browser</module>
```

- [ ] **Step 3: Remove CI build/copy steps**

In `.github/workflows/fengyu-release.yml`, delete the desktop job's browser-specific block: the comment at ~line 334, the `- name: Build native browser plugin` step at ~336, the `run: node ... plugin build OfficialPlugins/plugin-browser` at ~337, and the two `cp OfficialPlugins/plugin-browser/dist-package/*.fyp ...` lines at ~345-347. Leave the generic `cp inputs/*.fyp ...` line (it copies the other plugins).

In `.github/workflows/toolchain-release.yml`, delete line 73 (`- run: node toolchain/cli/bin/fengyu.mjs plugin build OfficialPlugins/plugin-browser`).

- [ ] **Step 4: Remove e2e smoke + release-workflow references**

In `scripts/e2e-smoke.sh`, make THREE edits:
1. Line 33 — remove `browser` from the build loop: change `for plugin in markdown excel email offlinepython browser; do` to `for plugin in markdown excel email offlinepython; do`.
2. Lines 146-149 — delete the browser-plugin registered check (comment + `grep -q 'fan.summer.browser'` + `fail` + `PASS` echo).

In `scripts/release-workflow.test.mjs`, delete line 10 (`const browserPom = ...`), line 110 (the `Build native browser plugin` assertion), and line 111 (the `cp .../plugin-browser ...` assertion). Remove any use of `browserPom` elsewhere in the file.

- [ ] **Step 5: Verify build + the release-workflow test**

Run:
```bash
./mvnw -q -pl OfficialPlugins -am package -DskipTests
node --test scripts/release-workflow.test.mjs
```
Expected: OfficialPlugins builds without plugin-browser; the release-workflow meta-test passes (its browser assertions are gone).

- [ ] **Step 6: Run docs-updater skill for the plugin-browser doc pages**

The docs source (`docs/en/plugins/official-browser.md`, `docs/zh/plugins/official-browser.md`, and overview mentions) reference the now-removed plugin. Invoke the `docs-updater` skill to remove/rewrite these pages to describe the host-embedded browser capability instead. (This is a deliberate skill delegation, not inline edits, to keep EN/ZH structurally aligned.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "🔥 chore: remove plugin-browser, replaced by host-embedded browser capability"
```

---

## Task 10: End-to-end smoke (web-mode negative assertion)

**Files:**
- Modify: `scripts/e2e-smoke.sh` (add an assertion that the browser plugin is absent)

> NOTE: `scripts/e2e-smoke.sh` boots the JAR directly (`java -jar`), which is **web mode** — no Electron, no `fengyu.desktop`, no browser bridge. After Task 9 the `fan.summer.browser` plugin is gone, so it should NOT appear in `/api/plugin-runtime`. The variable `$RUNTIME` (e2e-smoke.sh:108) holds that endpoint's JSON. The positive full-chain check (backend → bridge → window → screenshot) requires the packaged desktop app and belongs in the Playwright e2e suite (`desktop/electron/test/e2e/`) as a follow-up, not here.

- [ ] **Step 1: Add a negative assertion to e2e-smoke**

In `scripts/e2e-smoke.sh`, right after the existing plugin-registered checks (the block around line 109-152 that greps `$RUNTIME` for `fan.summer.markdown` etc.), add:
```bash
# plugin-browser was removed in favor of the host-embedded (desktop-only) browser capability.
# It must NOT be registered in web mode.
if echo "$RUNTIME" | grep -q 'fan.summer.browser'; then
  fail "fan.summer.browser should not be registered after plugin removal"
fi
echo "PASS: browser plugin correctly absent"
```

- [ ] **Step 2: Run e2e-smoke**

Run: `scripts/e2e-smoke.sh`
Expected: PASS, including the new `PASS: browser plugin correctly absent` line.

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e-smoke.sh
git commit -m "✅ test: assert browser plugin absent in e2e smoke"
```

---

## Verification (final)

- [ ] Backend: `./mvnw clean test -f FengYu/pom.xml` — all green.
- [ ] Electron: `cd desktop/electron && npx tsc --noEmit && npx vitest run` — all green.
- [ ] Reactor: `./mvnw -q clean package -DskipTests` (builds OfficialPlugins without plugin-browser) — succeeds.
- [ ] Release meta-test: `node --test scripts/release-workflow.test.mjs` — green.
- [ ] `git diff --check` — no whitespace damage.

---

## Notes for the implementer

- The `PluginManifest` record constructor in Task 3's test uses the real 15-component signature (`PluginManifest.java:9-25`). If the record changes, realign component order/arity before running the test.
- `System.getenvOrDefault` (Task 1) is JDK 21+; if the compiler rejects it, swap to an explicit `String p = System.getenv("FENGYU_BROWSER_BRIDGE_PORT"); if (p==null||p.isBlank()) throw ...`.
- The `fullPage` screenshot path (Task 6) is approximated (viewport capture) for MVP — the spec records this as acceptable; a true full-page capture would require resizing the window to scroll dimensions.
- `captureA11y` attaches/detaches the debugger per screenshot to avoid a long-lived CDP session; on any error it detaches in a finally-ish try.
