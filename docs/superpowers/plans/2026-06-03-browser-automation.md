# Browser Automation AI Tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `browser_automate` AI tool that accepts natural language instructions and controls a Playwright Chromium browser via the existing AI service as planner.

**Architecture:** A single `BrowserAutomateTool` implements `AiTool`. On execution it launches a headed Chromium browser via Playwright, then runs an observe-think-act loop: snapshot the page DOM → send to the configured `AiService` as a planner prompt → parse the LLM's JSON action response → execute via Playwright → repeat until `done` or 50 iterations. A `SynchronousChatHelper` wraps the streaming `AiService.chat()` into a blocking call for the loop.

**Tech Stack:** Java 21, Playwright for Java 1.49.0, existing `AiService` / `AiChatMessage` / `JsonHelper`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `SwissKit/pom.xml` | Add Playwright dependency |
| Create | `fan/summer/buildintool/browser/BrowserAction.java` | Enum of actions + JSON parsing |
| Create | `fan/summer/buildintool/browser/BrowserPromptBuilder.java` | System prompt for the planner LLM |
| Create | `fan/summer/buildintool/browser/BrowserSession.java` | Playwright Browser/Page wrapper |
| Create | `fan/summer/buildintool/browser/PageSnapshot.java` | Extract page state as structured text |
| Create | `fan/summer/buildintool/browser/SynchronousChatHelper.java` | Sync wrapper for streaming AiService |
| Create | `fan/summer/buildintool/browser/ai/BrowserAutomateTool.java` | Main AiTool entry point + think-act loop |
| Modify | `fan/summer/ai/tools/BuiltinAiToolRegistrar.java` | Register browser_automate tool |

---

### Task 1: Add Playwright dependency to pom.xml

**Files:**
- Modify: `SwissKit/pom.xml`

- [ ] **Step 1: Add Playwright version property and dependency**

In `SwissKit/pom.xml`, add inside `<properties>`:

```xml
<playwright.version>1.49.0</playwright.version>
```

Add inside `<dependencies>`:

```xml
<!-- Playwright (Browser Automation) -->
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>${playwright.version}</version>
</dependency>
```

- [ ] **Step 2: Build the project to verify dependency resolves**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/pom.xml
git commit -m "⬆️ build: add Playwright dependency for browser automation"
```

---

### Task 2: Create BrowserAction enum

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAction.java`

This is a pure data class with no external dependencies. It defines all supported browser actions and parses them from the LLM's JSON response.

- [ ] **Step 1: Create `BrowserAction.java`**

```java
package fan.summer.buildintool.browser;

import fan.summer.ai.util.JsonHelper;

import java.util.Map;

/**
 * Represents a single browser automation action parsed from the planner LLM's JSON output.
 *
 * <p>Each action has a type and a map of parameters. The static {@link #fromJson(String)}
 * method parses the LLM response into an action instance.</p>
 */
public record BrowserAction(
    Type type,
    Map<String, Object> params
) {

    /** Supported browser action types. */
    public enum Type {
        NAVIGATE,
        CLICK,
        TYPE,
        PRESS,
        SCROLL,
        EXTRACT,
        SCREENSHOT,
        WAIT,
        DONE
    }

    /**
     * Parses a JSON string from the planner LLM into a BrowserAction.
     * Expected format: {"action": "navigate", "url": "https://..."}
     *
     * @param json the raw JSON string from the LLM
     * @return parsed action, or a DONE action with error message if parsing fails
     */
    public static BrowserAction fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new BrowserAction(Type.DONE, Map.of("result", "Empty response from planner"));
        }

        // Strip markdown code fences if present
        String cleaned = json.trim();
        if (cleaned.startsWith("```")) {
            int start = cleaned.indexOf('\n');
            int end = cleaned.lastIndexOf("```");
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start + 1, end).trim();
            }
        }

        Map<String, Object> map;
        try {
            map = JsonHelper.parseObject(cleaned);
        } catch (Exception e) {
            return new BrowserAction(Type.DONE, Map.of("result", "Failed to parse planner response: " + e.getMessage()));
        }

        String actionName = (String) map.get("action");
        if (actionName == null) {
            return new BrowserAction(Type.DONE, Map.of("result", "No 'action' field in planner response"));
        }

        Type type = parseType(actionName);
        return new BrowserAction(type, map);
    }

    /** Shorthand to get a string parameter. */
    public String getString(String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    /** Shorthand to get a numeric parameter with default. */
    public double getDouble(String key, double defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static Type parseType(String name) {
        return switch (name.toLowerCase().trim()) {
            case "navigate"  -> Type.NAVIGATE;
            case "click"     -> Type.CLICK;
            case "type"      -> Type.TYPE;
            case "press"     -> Type.PRESS;
            case "scroll"    -> Type.SCROLL;
            case "extract"   -> Type.EXTRACT;
            case "screenshot"-> Type.SCREENSHOT;
            case "wait"      -> Type.WAIT;
            case "done"      -> Type.DONE;
            default          -> Type.DONE;
        };
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserAction.java
git commit -m "✨ feat(browser): add BrowserAction enum for parsing planner actions"
```

---

### Task 3: Create BrowserPromptBuilder

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserPromptBuilder.java`

This builds the system prompt that instructs the planner LLM how to read page snapshots and output actions. No external dependencies.

- [ ] **Step 1: Create `BrowserPromptBuilder.java`**

```java
package fan.summer.buildintool.browser;

/**
 * Builds the system prompt for the browser automation planner LLM.
 *
 * <p>The prompt instructs the LLM to analyze a page snapshot and output
 * exactly one JSON action per turn. It lists all supported actions with
 * their parameters and provides examples.</p>
 */
public final class BrowserPromptBuilder {

    private BrowserPromptBuilder() {}

    /**
     * Builds the system prompt for the browser automation planner.
     *
     * @return the complete system prompt string
     */
    public static String buildSystemPrompt() {
        return """
            You are a browser automation agent. Your task is to analyze the current page state \
            and decide the next action to accomplish the user's goal.

            ## Available Actions

            You must output EXACTLY ONE JSON object per response. Do not add any text before or after the JSON.

            ### navigate
            Navigate to a URL.
            {"action": "navigate", "url": "https://example.com"}

            ### click
            Click an element matching a CSS selector.
            {"action": "click", "selector": "button.login"}

            ### type
            Clear a field and type text into it.
            {"action": "type", "selector": "input#username", "text": "myuser"}

            ### press
            Press a keyboard key (Enter, Tab, Escape, ArrowDown, etc.).
            {"action": "press", "key": "Enter"}

            ### scroll
            Scroll the page. direction: "up" or "down". amount is number of scrolls (default 3).
            {"action": "scroll", "direction": "down", "amount": 3}

            ### extract
            Extract text content from the page. If selector is provided, extract only that element's text. \
            If no selector, extract the full page text.
            {"action": "extract", "selector": "div.result"}
            {"action": "extract"}

            ### screenshot
            Take a screenshot of the current page for analysis. Use this when the DOM snapshot is insufficient.
            {"action": "screenshot"}

            ### wait
            Wait for a number of seconds (default 2). Use after navigation or when content needs time to load.
            {"action": "wait", "seconds": 3}

            ### done
            Task is complete. Return the result to the user.
            {"action": "done", "result": "Successfully logged in as user X"}

            ## Rules

            1. Output ONLY a single JSON object. No markdown, no explanation, no code fences.
            2. Analyze the page snapshot carefully before choosing an action.
            3. Use CSS selectors to target elements. Prefer specific selectors (id, class+tag) over generic ones.
            4. If an action fails, try an alternative approach (different selector, wait first, etc.).
            5. When the task is complete, output the done action with a summary of what was accomplished.
            6. If you cannot complete the task after several attempts, output done with an explanation of why it failed.
            7. For login tasks, use the credentials provided in the user instruction.
            """;
    }

    /**
     * Formats the initial user instruction message.
     *
     * @param instruction the natural language instruction from the user
     * @return formatted instruction message
     */
    public static String buildUserInstruction(String instruction) {
        return "User instruction: " + instruction + "\n\nPlease analyze the current page state and decide the next action.";
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserPromptBuilder.java
git commit -m "✨ feat(browser): add BrowserPromptBuilder for planner LLM system prompt"
```

---

### Task 4: Create BrowserSession

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserSession.java`

Wraps Playwright's `Browser` and `Page` objects with the atomic operations.

- [ ] **Step 1: Create `BrowserSession.java`**

```java
package fan.summer.buildintool.browser;

import com.microsoft.playwright.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.file.Path;
import java.util.Base64;

/**
 * Manages a Playwright browser session — one headed Chromium instance with a single page.
 *
 * <p>All methods are synchronous and blocking. The session must be {@link #close()}'d
 * when done to release the browser process.</p>
 */
public class BrowserSession implements AutoCloseable {

    private static final PluginLogger log = LoggerFactory.getLogger(BrowserSession.class);

    private Playwright playwright;
    private Browser browser;
    private Page page;

    /**
     * Launches a headed Chromium browser and opens a new page.
     */
    public BrowserSession() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false)
            .setArgs(java.util.List.of("--start-maximized")));
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(null));
        page = context.newPage();
        log.info("Browser session started (headed Chromium)");
    }

    /**
     * Returns the Playwright Page for direct access.
     */
    public Page page() {
        return page;
    }

    /**
     * Navigates to the given URL.
     *
     * @return the response, or null if navigation failed
     */
    public String navigate(String url) {
        log.debug("navigate: {}", url);
        Response response = page.navigate(url);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return response != null ? "Navigated to " + url + " (status: " + response.status() + ")" : "Navigation completed";
    }

    /**
     * Clicks an element matching the CSS selector.
     */
    public String click(String selector) {
        log.debug("click: {}", selector);
        try {
            page.click(selector, new Page.ClickOptions().setTimeout(5000));
            return "Clicked element: " + selector;
        } catch (PlaywrightException e) {
            return "Click failed for '" + selector + "': " + e.getMessage();
        }
    }

    /**
     * Clears the field matching the selector and types text into it.
     */
    public String type(String selector, String text) {
        log.debug("type: {} => [{} chars]", selector, text.length());
        try {
            page.fill(selector, "", new Page.FillOptions().setTimeout(5000));
            page.fill(selector, text);
            return "Typed text into: " + selector;
        } catch (PlaywrightException e) {
            return "Type failed for '" + selector + "': " + e.getMessage();
        }
    }

    /**
     * Presses a keyboard key.
     */
    public String press(String key) {
        log.debug("press: {}", key);
        page.keyboard().press(key);
        return "Pressed key: " + key;
    }

    /**
     * Scrolls the page.
     *
     * @param direction "up" or "down"
     * @param amount    number of scroll increments (each ~300px)
     */
    public String scroll(String direction, int amount) {
        log.debug("scroll: {} x{}", direction, amount);
        int delta = "up".equalsIgnoreCase(direction) ? -300 : 300;
        for (int i = 0; i < amount; i++) {
            page.mouse().wheel(0, delta);
        }
        return "Scrolled " + direction + " x" + amount;
    }

    /**
     * Extracts text content from the page.
     *
     * @param selector CSS selector, or null to extract full page text
     * @return the extracted text content
     */
    public String extract(String selector) {
        if (selector != null && !selector.isBlank()) {
            log.debug("extract: {}", selector);
            try {
                ElementHandle element = page.querySelector(selector);
                if (element == null) return "Element not found: " + selector;
                String text = element.textContent();
                return text != null ? text : "(empty text content)";
            } catch (PlaywrightException e) {
                return "Extract failed for '" + selector + "': " + e.getMessage();
            }
        } else {
            log.debug("extract: full page");
            String text = page.textContent("body");
            if (text == null) return "(page body has no text)";
            // Truncate if too large for LLM context
            if (text.length() > 8000) {
                return text.substring(0, 8000) + "\n... [truncated, total " + text.length() + " chars]";
            }
            return text;
        }
    }

    /**
     * Takes a screenshot and returns it as a base64-encoded PNG string.
     */
    public String screenshot() {
        log.debug("screenshot");
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Waits for the specified number of seconds.
     */
    public String wait(double seconds) {
        log.debug("wait: {}s", seconds);
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "Waited " + seconds + " seconds";
    }

    /**
     * Returns the current page URL.
     */
    public String getCurrentUrl() {
        return page.url();
    }

    /**
     * Returns the current page title.
     */
    public String getTitle() {
        return page.title();
    }

    /**
     * Closes the browser and Playwright instance.
     */
    @Override
    public void close() {
        try {
            if (browser != null) browser.close();
        } catch (Exception e) {
            log.warn("Error closing browser: {}", e.getMessage());
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception e) {
            log.warn("Error closing Playwright: {}", e.getMessage());
        }
        browser = null;
        playwright = null;
        page = null;
        log.info("Browser session closed");
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`
Note: Playwright dependency was added in Task 1.

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/BrowserSession.java
git commit -m "✨ feat(browser): add BrowserSession wrapping Playwright browser operations"
```

---

### Task 5: Create PageSnapshot

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/PageSnapshot.java`

Extracts the current page state as structured text for the planner LLM. Uses Playwright's `page.querySelectorAll` to enumerate interactive elements.

- [ ] **Step 1: Create `PageSnapshot.java`**

```java
package fan.summer.buildintool.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts the current page state as a structured text snapshot for the planner LLM.
 *
 * <p>The snapshot includes the page URL, title, and a list of interactive elements
 * (links, buttons, inputs, selects, textareas) with their CSS selectors and text content.
 * The format is compact but informative enough for the LLM to decide which element
 * to interact with.</p>
 */
public final class PageSnapshot {

    private PageSnapshot() {}

    /**
     * Captures a text snapshot of the current page state.
     *
     * @param session the active browser session
     * @return a structured text representation of the page
     */
    public static String capture(BrowserSession session) {
        Page page = session.page();
        StringBuilder sb = new StringBuilder();

        sb.append("=== Page State ===\n");
        sb.append("URL: ").append(session.getCurrentUrl()).append("\n");
        sb.append("Title: ").append(session.getTitle()).append("\n");

        // Visible page text (truncated)
        String bodyText = getVisibleText(page);
        sb.append("\n--- Visible Text ---\n");
        sb.append(bodyText).append("\n");

        // Interactive elements
        sb.append("\n--- Interactive Elements ---\n");
        sb.append(buildInteractiveElementsList(page));

        sb.append("\n=== End Page State ===\n");
        return sb.toString();
    }

    /**
     * Extracts visible text content from the page body, truncated to fit LLM context.
     */
    private static String getVisibleText(Page page) {
        try {
            String text = page.textContent("body");
            if (text == null) return "(no visible text)";
            String cleaned = text.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 6000) {
                return cleaned.substring(0, 6000) + " ... [truncated]";
            }
            return cleaned;
        } catch (Exception e) {
            return "(failed to extract text: " + e.getMessage() + ")";
        }
    }

    /**
     * Builds a list of interactive elements with their selectors and text content.
     * Targets: a, button, input, select, textarea, [role="button"], [role="link"]
     */
    private static String buildInteractiveElementsList(Page page) {
        StringBuilder sb = new StringBuilder();
        String[] selectors = {
            "a[href]",
            "button",
            "input",
            "select",
            "textarea",
            "[role='button']",
            "[role='link']"
        };

        int index = 0;
        for (String selector : selectors) {
            List<ElementHandle> elements;
            try {
                elements = page.querySelectorAll(selector);
            } catch (Exception e) {
                continue;
            }

            for (ElementHandle el : elements) {
                try {
                    if (!el.isVisible()) continue;

                    String tag = el.evaluate("el => el.tagName.toLowerCase()").toString();
                    String text = el.textContent();
                    if (text != null) text = text.replaceAll("\\s+", " ").trim();
                    if (text != null && text.length() > 100) text = text.substring(0, 100) + "...";

                    String type = el.getAttribute("type");
                    String placeholder = el.getAttribute("placeholder");
                    String name = el.getAttribute("name");
                    String id = el.getAttribute("id");
                    String href = el.getAttribute("href");
                    String ariaLabel = el.getAttribute("aria-label");

                    // Build a best-effort CSS selector for this element
                    String cssSelector = buildSelector(tag, id, name, type, selector);

                    sb.append("[").append(index++).append("] ");
                    sb.append("<").append(tag);
                    if (type != null) sb.append(" type=\"").append(type).append("\"");
                    if (id != null) sb.append(" id=\"").append(id).append("\"");
                    if (name != null) sb.append(" name=\"").append(name).append("\"");
                    if (placeholder != null) sb.append(" placeholder=\"").append(placeholder).append("\"");
                    if (ariaLabel != null) sb.append(" aria-label=\"").append(ariaLabel).append("\"");
                    if (href != null) sb.append(" href=\"").append(truncate(href, 80)).append("\"");
                    sb.append(">");

                    if (text != null && !text.isEmpty()) {
                        sb.append(" \"").append(truncate(text, 80)).append("\"");
                    }
                    sb.append("  → selector: ").append(cssSelector);
                    sb.append("\n");

                    // Limit to 50 interactive elements to keep snapshot compact
                    if (index >= 50) {
                        sb.append("... (truncated, more elements on page)\n");
                        return sb.toString();
                    }
                } catch (Exception ignored) {
                    // Skip elements that throw during property access
                }
            }
        }

        if (index == 0) {
            sb.append("(no interactive elements found)\n");
        }

        return sb.toString();
    }

    /**
     * Builds a CSS selector string for targeting the element.
     * Prefers #id, then [name='x'], then tag[type='x'], then generic tag.
     */
    private static String buildSelector(String tag, String id, String name, String type, String originalSelector) {
        if (id != null && !id.isEmpty()) return "#" + id;
        if (name != null && !name.isEmpty()) return tag + "[name='" + name + "']";
        if (type != null && !type.isEmpty()) return tag + "[type='" + type + "']";
        return tag;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/PageSnapshot.java
git commit -m "✨ feat(browser): add PageSnapshot for extracting page state as text"
```

---

### Task 6: Create SynchronousChatHelper

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java`

The existing `AiService.chat()` is streaming/async with `AiStreamCallback`. This helper wraps it into a synchronous call that blocks until the full response is collected, returning the complete text. This is needed for the think-act loop.

- [ ] **Step 1: Create `SynchronousChatHelper.java`**

```java
package fan.summer.buildintool.browser;

import fan.summer.api.ai.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the streaming {@link AiService#chat(List, AiStreamCallback)} into a
 * synchronous blocking call. Used by {@link BrowserAutomateTool} to call the
 * planner LLM inside the think-act loop.
 *
 * <p>Timeout: 120 seconds per call (sufficient for complex planner reasoning).</p>
 */
public class SynchronousChatHelper {

    private static final PluginLogger log = LoggerFactory.getLogger(SynchronousChatHelper.class);
    private static final long TIMEOUT_SECONDS = 120;

    private SynchronousChatHelper() {}

    /**
     * Calls the AI service synchronously, blocking until the full response is available.
     *
     * @param history the conversation history (system + user + assistant messages)
     * @return the complete response text, or null if the service is unavailable or times out
     */
    public static String call(List<AiChatMessage> history) {
        AiService service = AiServiceProvider.getService().orElse(null);
        if (service == null || !service.isReady()) {
            log.warn("AI service not available for browser planner");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();

        AiStreamCallback callback = new AiStreamCallback() {
            final StringBuilder buffer = new StringBuilder();

            @Override
            public void onToken(String fragment) {
                buffer.append(fragment);
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                resultHolder.set(fullResponse != null ? fullResponse : buffer.toString());
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorHolder.set(error);
                latch.countDown();
            }
        };

        try {
            service.chat(history, callback);
        } catch (AiServiceException e) {
            log.error("AI service exception during browser planner call: {}", e.getMessage());
            return null;
        }

        try {
            boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Browser planner call timed out after {}s", TIMEOUT_SECONDS);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Throwable error = errorHolder.get();
        if (error != null) {
            log.error("Browser planner call errored: {}", error.getMessage());
            return null;
        }

        return resultHolder.get();
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/SynchronousChatHelper.java
git commit -m "✨ feat(browser): add SynchronousChatHelper for blocking AiService calls"
```

---

### Task 7: Create BrowserAutomateTool

**Files:**
- Create: `SwissKit/src/main/java/fan/summer/buildintool/browser/ai/BrowserAutomateTool.java`

The main `AiTool` implementation. Ties together all the components into the observe-think-act loop.

- [ ] **Step 1: Create `BrowserAutomateTool.java`**

```java
package fan.summer.buildintool.browser.ai;

import fan.summer.api.ai.*;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;
import fan.summer.buildintool.browser.*;

import java.util.*;

/**
 * AI-callable tool that accepts a natural language instruction and automates a
 * Chromium browser to accomplish the task.
 *
 * <p>The tool launches a headed Chromium browser via Playwright and runs an
 * observe-think-act loop: it snapshots the page DOM, sends it to the configured
 * AI service as a planner, parses the returned action JSON, executes it via
 * Playwright, and repeats until the task is done or max iterations are reached.</p>
 *
 * @see BrowserSession
 * @see BrowserAction
 * @see PageSnapshot
 * @see BrowserPromptBuilder
 */
public class BrowserAutomateTool implements AiTool {

    private static final PluginLogger log = LoggerFactory.getLogger(BrowserAutomateTool.class);
    private static final int MAX_ITERATIONS = 50;

    @Override
    public String getName() {
        return "browser_automate";
    }

    @Override
    public String getDescription() {
        return "Automate a web browser using natural language instructions. " +
               "Opens a visible Chromium browser and performs actions such as navigation, " +
               "clicking, typing, form filling, data extraction, and more. " +
               "Args: instruction (string, required) — a natural language description of " +
               "what to do, e.g. \"Open github.com and search for 'playwright java'\"";
    }

    @Override
    public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("instruction", "string",
                "Natural language instruction for the browser task, e.g. \"Log in to example.com with username X and password Y\"", true)
        );
    }

    @Override
    public AiToolResult execute(Map<String, Object> arguments) {
        String instruction = (String) arguments.get("instruction");
        if (instruction == null || instruction.isBlank()) {
            return AiToolResult.error("instruction is required");
        }

        // Verify AI service is available before launching browser
        if (AiServiceProvider.getService().isEmpty() || !AiServiceProvider.getService().get().isReady()) {
            return AiToolResult.error("AI service is not configured or not ready. Please configure an AI provider before using browser automation.");
        }

        log.info("Starting browser automation: {}", instruction);

        BrowserSession session = null;
        try {
            session = new BrowserSession();
            String result = runThinkActLoop(session, instruction);
            log.info("Browser automation completed: {}", result);
            return AiToolResult.success(result);
        } catch (Exception e) {
            log.error("Browser automation failed: {}", e.getMessage());
            return AiToolResult.error("Browser automation failed: " + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    log.warn("Error closing browser session: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Runs the observe-think-act loop.
     *
     * @param session     the active browser session
     * @param instruction the user's natural language instruction
     * @return the final result string
     */
    private String runThinkActLoop(BrowserSession session, String instruction) {
        String systemPrompt = BrowserPromptBuilder.buildSystemPrompt();

        // Planner conversation history
        List<AiChatMessage> history = new ArrayList<>();
        history.add(AiChatMessage.system(systemPrompt));
        history.add(AiChatMessage.user(BrowserPromptBuilder.buildUserInstruction(instruction)));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("Think-act iteration {}/{}", i + 1, MAX_ITERATIONS);

            // 1. Capture page snapshot
            String snapshot = PageSnapshot.capture(session);

            // 2. Append snapshot as user message
            history.add(AiChatMessage.user("Current page state:\n" + snapshot));

            // 3. Call planner LLM
            String plannerResponse = SynchronousChatHelper.call(history);
            if (plannerResponse == null) {
                return "AI planner became unavailable during execution. Last page: " + session.getCurrentUrl();
            }

            // Add assistant response to history
            history.add(AiChatMessage.assistant(plannerResponse));

            // 4. Parse action
            BrowserAction action = BrowserAction.fromJson(plannerResponse);
            log.info("Iteration {}: action={} params={}", i + 1, action.type(), action.params);

            // 5. Execute action
            if (action.type() == BrowserAction.Type.DONE) {
                String result = action.getString("result");
                return result != null ? result : "Task completed (no result specified).";
            }

            String actionResult = executeAction(session, action);
            log.debug("Action result: {}", actionResult);

            // 6. Append action result to history
            history.add(AiChatMessage.user("Action result: " + actionResult));

            // Keep history from growing unbounded — keep system prompt + last N exchanges
            if (history.size() > 40) {
                trimHistory(history);
            }
        }

        return "Task did not complete within " + MAX_ITERATIONS + " steps. " +
               "Last page: " + session.getCurrentUrl() + " (" + session.getTitle() + ")";
    }

    /**
     * Executes a single browser action and returns a human-readable result.
     */
    private String executeAction(BrowserSession session, BrowserAction action) {
        try {
            return switch (action.type()) {
                case NAVIGATE -> {
                    String url = action.getString("url");
                    if (url == null || url.isBlank()) yield "Error: 'url' is required for navigate action";
                    yield session.navigate(url);
                }
                case CLICK -> {
                    String selector = action.getString("selector");
                    if (selector == null || selector.isBlank()) yield "Error: 'selector' is required for click action";
                    yield session.click(selector);
                }
                case TYPE -> {
                    String selector = action.getString("selector");
                    String text = action.getString("text");
                    if (selector == null || selector.isBlank()) yield "Error: 'selector' is required for type action";
                    if (text == null) yield "Error: 'text' is required for type action";
                    yield session.type(selector, text);
                }
                case PRESS -> {
                    String key = action.getString("key");
                    if (key == null || key.isBlank()) yield "Error: 'key' is required for press action";
                    yield session.press(key);
                }
                case SCROLL -> {
                    String direction = action.getString("direction");
                    if (direction == null) direction = "down";
                    int amount = (int) action.getDouble("amount", 3);
                    yield session.scroll(direction, amount);
                }
                case EXTRACT -> {
                    String selector = action.getString("selector");
                    String extracted = session.extract(selector);
                    yield "Extracted text: " + extracted;
                }
                case SCREENSHOT -> {
                    // Screenshot taken but we can't feed images through the text-only planner
                    // For v1, just acknowledge and let the planner rely on DOM snapshots
                    yield "Screenshot captured. (Visual analysis not yet supported — rely on DOM snapshot.)";
                }
                case WAIT -> {
                    double seconds = action.getDouble("seconds", 2);
                    yield session.wait(seconds);
                }
                case DONE -> action.getString("result");
            };
        } catch (Exception e) {
            return "Action execution error: " + e.getMessage();
        }
    }

    /**
     * Trims history to prevent unbounded growth while preserving the system prompt
     * and recent context. Keeps the system message (index 0) and the last 20 messages.
     */
    private void trimHistory(List<AiChatMessage> history) {
        if (history.size() <= 22) return; // system + 20 messages + headroom
        AiChatMessage system = history.get(0);
        List<AiChatMessage> recent = history.subList(Math.max(1, history.size() - 20), history.size());
        List<AiChatMessage> trimmed = new ArrayList<>();
        trimmed.add(system);
        trimmed.addAll(recent);
        history.clear();
        history.addAll(trimmed);
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/buildintool/browser/ai/BrowserAutomateTool.java
git commit -m "✨ feat(browser): add BrowserAutomateTool with observe-think-act loop"
```

---

### Task 8: Register browser_automate in BuiltinAiToolRegistrar

**Files:**
- Modify: `SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java`

- [ ] **Step 1: Add import and registration call**

Add import at the top of `BuiltinAiToolRegistrar.java`:

```java
import fan.summer.buildintool.browser.ai.BrowserAutomateTool;
```

In the `register()` method, add this line after the PDF tool registrations:

```java
AiServiceProvider.registerTool(new BrowserAutomateTool());
```

Update the log message at the end of `register()` to include `browser_automate`:

```java
log.info("Built-in AI tools registered: base64, hash_calculate, json_format, color_convert, excel_*, email_archive_*, pdf_split, pdf_merge, pdf_to_docx, browser_automate");
```

- [ ] **Step 2: Build to verify compilation**

Build via IntelliJ Maven: `SwissKit → Lifecycle → compile`

- [ ] **Step 3: Commit**

```bash
git add SwissKit/src/main/java/fan/summer/ai/tools/BuiltinAiToolRegistrar.java
git commit -m "✨ feat(browser): register browser_automate tool in BuiltinAiToolRegistrar"
```

---

### Task 9: Install Playwright browsers and verify

**Files:** None (runtime verification)

- [ ] **Step 1: Package the application**

Build via IntelliJ Maven: `SwissKit → Lifecycle → package` (skip tests)

- [ ] **Step 2: Run the application**

```bash
java -jar SwissKit/target/SwissKitJ-3.0.0-beta.1.jar
```

- [ ] **Step 3: Install Playwright browsers (first time only)**

On first use, Playwright needs to download Chromium. If it doesn't auto-download, run via the application's terminal or manually:

```bash
java -cp SwissKit/target/SwissKitJ-3.0.0-beta.1.jar com.microsoft.playwright.CLI install chromium
```

- [ ] **Step 4: Manual test — trigger browser_automate via AI chat**

In the running app, open the AI chat and send a message like:
"帮我打开百度搜索天气预报"

Expected behavior:
1. The AI model should call `browser_automate` with the instruction
2. A Chromium window opens (headed mode)
3. The planner LLM navigates to baidu.com and searches
4. The tool returns the result

- [ ] **Step 5: Final commit if any fixes needed**

If any issues are found during manual testing, fix and commit with appropriate message.
