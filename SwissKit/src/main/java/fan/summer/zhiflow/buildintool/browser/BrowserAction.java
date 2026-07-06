package fan.summer.zhiflow.buildintool.browser;

import fan.summer.zhiflow.ai.util.JsonHelper;

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
            try { return Double.parseDouble(s); } catch (NumberFormatException e) {
                // Not a valid number — fall through to default
            }
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
