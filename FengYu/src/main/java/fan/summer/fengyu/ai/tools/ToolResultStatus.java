package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiToolResult;
import fan.summer.fengyu.ai.util.JsonHelper;

import java.util.Map;

/** Normalizes the JSON result convention shared by built-in and plugin tools. */
public final class ToolResultStatus {
    private ToolResultStatus() {}

    public static AiToolResult toAiResult(String value) {
        Map<String, Object> object = object(value);
        if (Boolean.FALSE.equals(object.get("success"))) {
            return AiToolResult.error(failureMessage(object, value));
        }
        return AiToolResult.success(value);
    }

    public static String requireSuccess(String value) {
        Map<String, Object> object = object(value);
        if (Boolean.FALSE.equals(object.get("success"))) {
            throw new IllegalStateException(failureMessage(object, value));
        }
        return value;
    }

    /**
     * Most official plugin methods report failure as {@code success:false} plus a localized
     * {@code summary} with no {@code error} field; without this fallback every such failure
     * surfaced as the useless "Tool reported failure" in agent runs and workflows.
     */
    private static String failureMessage(Map<String, Object> object, String raw) {
        Object error = object.get("error");
        if (error != null) return String.valueOf(error);
        Object summary = object.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) return String.valueOf(summary);
        return raw == null ? "Tool reported failure" : raw;
    }

    private static Map<String, Object> object(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '{') return Map.of();
        try { return JsonHelper.parseObject(value); }
        catch (Exception ignored) { return Map.of(); }
    }
}
