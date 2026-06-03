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
