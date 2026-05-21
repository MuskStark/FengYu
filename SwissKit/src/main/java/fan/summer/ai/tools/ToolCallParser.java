package fan.summer.ai.tools;

import fan.summer.api.ai.AiToolCall;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured tool calls from model output text.
 * Supports two formats:
 * <ol>
 *   <li>Qwen-style: {@code <|tool_call_begin|>}<br>
 *       {@code {"name": "...", "arguments": {...}}}<br>
 *       {@code <|tool_call_end|>}</li>
 *   <li>Generic JSON: a JSON object with "name" and "arguments" fields,
 *       optionally wrapped in markdown code blocks.</li>
 * </ol>
 */
public class ToolCallParser {

    private static final Pattern QWEN_TOOL_CALL = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?\"name\"\\s*:\\s*\"(.*?)\".*?\"arguments\"\\s*:\\s*(\\{.*?}).*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );

    private static final Pattern GENERIC_TOOL_CALL = Pattern.compile(
        "(?:```(?:json)?\\s*)?\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}(?:\\s*```)?",
        Pattern.DOTALL
    );

    /**
     * Attempt to extract tool calls from model output.
     * Returns a list of parsed calls, or empty list if none found.
     */
    public static List<AiToolCall> parse(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AiToolCall> calls = new ArrayList<>();

        // Try Qwen-style first
        Matcher m = QWEN_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        if (!calls.isEmpty()) return calls;

        // Try generic JSON
        m = GENERIC_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        return calls;
    }

    /**
     * Check if text starts with or contains a partial tool call pattern
     * (used for early detection during streaming).
     */
    public static boolean containsToolCallPattern(String text) {
        if (text == null) return false;
        return text.contains("<|tool_call_begin|>")
            || text.contains("\"name\"") && text.contains("\"arguments\"");
    }

    /**
     * Strip tool call markup from text, returning only the plain-text portion.
     */
    public static String stripToolCalls(String text) {
        if (text == null) return "";
        String result = QWEN_TOOL_CALL.matcher(text).replaceAll("");
        return result.trim();
    }

    @SuppressWarnings("unchecked")
    private static AiToolCall buildCall(String name, String argsJson) {
        Map<String, Object> args;
        try {
            // Simple JSON parsing — no dependency needed for flat objects
            args = parseSimpleJson(argsJson);
        } catch (Exception e) {
            args = Map.of("_raw", argsJson);
        }
        return AiToolCall.of(name.trim(), args);
    }

    /**
     * Minimal JSON object parser for flat string/number/boolean values.
     * Avoids adding a JSON library dependency.
     */
    static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return map;

        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        if (trimmed.isEmpty()) return map;

        // Split by commas, respecting quoted strings
        List<String> pairs = splitKeyValuePairs(trimmed);
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            map.put(key, parseValue(value));
        }
        return map;
    }

    private static List<String> splitKeyValuePairs(String s) {
        List<String> parts = new ArrayList<>();
        boolean inString = false;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') depth--;
                if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (start < s.length()) parts.add(s.substring(start).trim());
        return parts;
    }

    private static Object parseValue(String v) {
        v = v.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        if ("true".equalsIgnoreCase(v)) return true;
        if ("false".equalsIgnoreCase(v)) return false;
        if ("null".equalsIgnoreCase(v)) return null;
        try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        return v;
    }
}
