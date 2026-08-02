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
            Object error = object.get("error");
            return AiToolResult.error(error == null ? value : String.valueOf(error));
        }
        return AiToolResult.success(value);
    }

    public static String requireSuccess(String value) {
        Map<String, Object> object = object(value);
        if (Boolean.FALSE.equals(object.get("success"))) {
            Object error = object.get("error");
            throw new IllegalStateException(error == null ? "Tool reported failure" : String.valueOf(error));
        }
        return value;
    }

    private static Map<String, Object> object(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '{') return Map.of();
        try { return JsonHelper.parseObject(value); }
        catch (Exception ignored) { return Map.of(); }
    }
}
