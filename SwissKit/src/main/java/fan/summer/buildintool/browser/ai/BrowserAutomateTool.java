package fan.summer.buildintool.browser.ai;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;
import fan.summer.buildintool.browser.*;
import fan.summer.ai.util.JsonHelper;

import java.util.*;

/**
 * AI-callable tool that accepts a natural language instruction and automates a
 * Chromium browser to accomplish the task.
 *
 * <p>The tool launches the system's already-installed browser (Chrome/Edge/Chromium)
 * via Playwright — no separate browser download required. It then runs an
 * observe-think-act loop: it snapshots the page DOM, sends it to the configured
 * AI service as a planner, parses the returned action JSON, executes it via
 * Playwright, and repeats until the task is done or max iterations are reached.</p>
 *
 * @see fan.summer.buildintool.browser.BrowserSession
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
        return "Automate a web browser using natural language.\n"
             + "Opens the system Chrome/Edge/Chromium and performs navigation, clicking, typing, "
             + "form filling, data extraction. No driver install needed.\n"
             + "Args: instruction (string, required) — natural language task description.\n"
             + "Example: browser_automate{\"instruction\":\"Open github.com and search for 'playwright java'\"}.";
    }

    @Override
    public boolean supportsLocal() { return false; }

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
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "instruction is required")));
        }

        // Verify AI service is available before launching browser
        if (AiServiceProvider.getService().isEmpty() || !AiServiceProvider.getService().get().isReady()) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "AI service is not configured or not ready. Please configure an AI provider before using browser automation.")));
        }

        log.info("Starting browser automation: {}", instruction);

        try (BrowserSession session = new BrowserSession()) {
            String result = runThinkActLoop(session, instruction);
            log.info("Browser automation completed: {}", result);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", result.length() > 100 ? result.substring(0, 100) + "..." : result);
            out.put("result", result);
            return AiToolResult.success(JsonHelper.toJson(out));
        } catch (RuntimeException e) {
            log.error("Browser automation failed: {}", e.getMessage());
            String msg = e.getMessage();
            if (msg != null && msg.contains("No supported browser found")) {
                return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error",
                    "No supported browser found on this system. " +
                    "Please install Google Chrome, Microsoft Edge, or Chromium.")));
            }
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Browser automation failed: " + msg)));
        } catch (Exception e) {
            log.error("Browser automation failed: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Browser automation failed: " + e.getMessage())));
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
            log.info("Iteration {}: action={} params={}", i + 1, action.type(), action.params());

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
        List<AiChatMessage> recent = new ArrayList<>(history.subList(Math.max(1, history.size() - 20), history.size()));
        history.clear();
        history.add(system);
        history.addAll(recent);
    }
}
