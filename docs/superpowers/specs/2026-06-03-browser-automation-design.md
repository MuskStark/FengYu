# Browser Automation AI Tool — Design Spec

**Date:** 2026-06-03
**Status:** Approved
**Module:** SwissKit (built-in tool)

## Overview

Add an AI-callable tool `browser_automate` that accepts a natural language instruction and uses Playwright for Java to control a headed Chromium browser. The tool implements an observe-think-act loop: it takes a snapshot of the current page, sends it to the existing AI service (already configured by the user), parses the LLM's structured action response, executes it via Playwright, and repeats until the task is done or max iterations are reached.

## Requirements

- Single `AiTool` named `browser_automate` with a `instruction` (string) parameter
- Uses Playwright for Java (`com.microsoft.playwright:playwright`) as the browser driver
- Headed Chromium mode — the user can watch the browser operating in real time
- Reuses the application's existing `AiService` instance for the internal planner LLM
- Max 50 iterations in the think-act loop
- The planner LLM controls the full browser lifecycle (including when to close)
- Supports: navigate, click, type, press key, scroll, extract text, screenshot (for LLM to see), wait, done

## Architecture

```
User: "帮我登录GitHub，用户名xxx，密码xxx"
  ↓
AI Model → calls browser_automate(instruction="登录GitHub...")
  ↓
BrowserAutomateTool
  ├── 1. Start Playwright Chromium (headed mode)
  ├── 2. Loop (max 50 iterations):
  │     ├── Get page snapshot (DOM accessibility tree as structured text)
  │     ├── Send snapshot + instruction + conversation history → existing AiService
  │     ├── LLM returns structured action: {"action": "navigate", "url": "..."}
  │     ├── Execute action via Playwright
  │     ├── Append action result to conversation history
  │     └── If LLM returns {"action": "done", "result": "..."} → break
  ├── 3. Close browser
  └── 4. Return AiToolResult with final result
```

## Components

### Package: `fan.summer.buildintool.browser`

| File | Responsibility |
|------|---------------|
| `ai/BrowserAutomateTool.java` | `AiTool` implementation — entry point, manages the think-act loop |
| `BrowserSession.java` | Wraps Playwright `Browser`/`Page`, exposes atomic operations (navigate, click, type, press, scroll, extract, screenshot, wait) |
| `BrowserAction.java` | Enum of supported actions with parameters; parses LLM JSON output into action instances |
| `PageSnapshot.java` | Extracts page state as structured text (accessibility tree + visible text + current URL + page title) for the LLM |
| `BrowserPromptBuilder.java` | Builds the system prompt that instructs the LLM how to read snapshots and output actions |

### BrowserAutomateTool

Implements `AiTool`. Its `execute()` method:

1. Creates a `BrowserSession` (launches Playwright Chromium in headed mode)
2. Initializes the planner conversation with a system prompt (from `BrowserPromptBuilder`) + the user instruction
3. Runs the observe-think-act loop:
   - Gets page snapshot via `PageSnapshot`
   - Appends snapshot as a user message to the planner conversation
   - Calls `AiServiceProvider.getService().chat(...)` to get the LLM's next action
   - Parses the response into a `BrowserAction`
   - Executes the action via `BrowserSession`
   - Appends the action result as an assistant message
   - If action is `done`, breaks the loop
4. Closes the `BrowserSession`
5. Returns `AiToolResult.success(result)` or `AiToolResult.error(message)`

### BrowserSession

Wraps Playwright's `Browser` and `Page` objects. Methods:

- `navigate(url)` — `page.navigate(url)`
- `click(selector)` — `page.click(selector)` with auto-wait
- `type(selector, text)` — `page.fill(selector, text)` (clears field first)
- `press(key)` — `page.keyboard().press(key)`
- `scroll(direction, amount)` — `page.mouse().wheel(deltaX, deltaY)`
- `extract(selector)` — Returns text content of matched element(s) via `page.querySelector(selector).textContent()`
- `screenshot()` — Takes a screenshot, encodes to base64, returns as image content for the LLM
- `wait(seconds)` — `page.waitForTimeout(seconds * 1000)`
- `getCurrentUrl()` — Returns the current page URL
- `getTitle()` — Returns the current page title
- `close()` — Closes browser and Playwright instance

### BrowserAction

Enum of supported actions. Each action has a `fromJson(Map<String, Object>)` factory method that parses the LLM's JSON response.

Supported actions and their parameters:

| Action | Parameters | Description |
|--------|-----------|-------------|
| `navigate` | `url` (string) | Navigate to URL |
| `click` | `selector` (string) | Click element matching CSS selector |
| `type` | `selector` (string), `text` (string) | Clear field and type text |
| `press` | `key` (string) | Press keyboard key (Enter, Tab, Escape, etc.) |
| `scroll` | `direction` (string), `amount` (integer, optional, default 3) | Scroll the page |
| `extract` | `selector` (string, optional) | Extract text content. If no selector, extract full page text |
| `screenshot` | none | Take screenshot for LLM analysis |
| `wait` | `seconds` (number, optional, default 2) | Wait for specified time |
| `done` | `result` (string) | Task complete, return result to caller |

### PageSnapshot

Extracts the current page state as structured text that the LLM can parse. The snapshot includes:

- Current URL
- Page title
- Accessibility tree or simplified DOM structure (using `page.accessibility().snapshot()` if available, otherwise `page.querySelectorAll("*")` to build a text representation)
- Visible text content of key interactive elements (links, buttons, inputs, text areas)
- Element selector hints (CSS selectors for interactive elements)

The snapshot format is designed to be compact but informative — enough for the LLM to understand the page structure and decide which element to interact with.

### BrowserPromptBuilder

Builds the system prompt for the planner LLM. The prompt:

- Explains the role: "You are a browser automation agent. Analyze the page snapshot and decide the next action."
- Lists all available actions with their JSON format
- Provides examples of correct action outputs
- Instructs the LLM to output ONLY a single JSON action per response
- Tells the LLM to emit `{"action": "done", "result": "..."}` when the task is complete
- Includes the user's original instruction as context

## Planner LLM Integration

The tool reuses the existing `AiService` from `AiServiceProvider.getService()`. For each iteration:

1. Build a conversation: `[system prompt, user instruction, (snapshot₁, action₁, result₁, snapshot₂, ...)]`
2. Send to the AI service for completion
3. Parse the response text as JSON → `BrowserAction`

The conversation history grows with each iteration, giving the LLM full context of the browser session.

**Fallback if AiService is unavailable**: Return `AiToolResult.error("AI service is not configured. Please configure an AI provider before using browser automation.")`

## Dependency

Add to `SwissKit/pom.xml`:

```xml
<playwright.version>1.49.0</playwright.version>
```

```xml
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>${playwright.version}</version>
</dependency>
```

Playwright's native browser binaries are auto-downloaded on first use via `Playwright.create()`. No manual installation required by the user.

## Registration

In `BuiltinAiToolRegistrar.register()`, add:

```java
AiServiceProvider.registerTool(new BrowserAutomateTool());
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Playwright binary not available | Catch `PlaywrightException`, return error with install instructions |
| Element not found (click/type/extract) | Capture Playwright error, feed back to LLM to retry with different selector |
| Navigation failure | Capture error, feed back to LLM |
| AiService not configured | Return error immediately: "AI service not configured" |
| Max iterations (50) reached | Return partial result: "Task did not complete within 50 steps. Last state: ..." |
| Unexpected LLM response format | Feed error back to LLM: "Invalid response, please output a valid action JSON" |
| Tool execution interrupted | Close browser, return error |

## Threading

Playwright operations are blocking and must run off the JavaFX Application Thread. The `execute()` method already runs in a background thread via `CompletableFuture` in `ToolExecutor`. No special threading changes needed — `BrowserAutomateTool.execute()` can be fully synchronous.

## Out of Scope (Future Enhancements)

- Cookie/session persistence across separate `browser_automate` calls
- File upload/download actions
- Multi-tab management
- Custom viewport/user-agent configuration
- Screenshot-based vision analysis (DOM-only in v1)
