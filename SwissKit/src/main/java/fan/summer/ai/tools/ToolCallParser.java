package fan.summer.ai.tools;

import fan.summer.ai.util.JsonHelper;
import fan.summer.api.ai.AiToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);

    private static final Pattern QWEN_TOOL_CALL = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?\"name\"\\s*:\\s*\"(.*?)\".*?\"arguments\"\\s*:\\s*(\\{.*?}).*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );

    private static final Pattern GENERIC_TOOL_CALL = Pattern.compile(
        "(?:```(?:json)?\\s*)?\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}(?:\\s*```)?",
        Pattern.DOTALL
    );

    public static List<AiToolCall> parse(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AiToolCall> calls = new ArrayList<>();

        Matcher m = QWEN_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        if (!calls.isEmpty()) {
            log.debug("Parsed {} tool call(s) via Qwen pattern", calls.size());
            return calls;
        }

        m = GENERIC_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        log.debug("Parsed {} tool call(s) via generic pattern", calls.size());
        return calls;
    }

    public static boolean containsToolCallPattern(String text) {
        if (text == null) return false;
        return text.contains("<|tool_call_begin|>")
            || text.contains("\"name\"") && text.contains("\"arguments\"");
    }

    public static String stripToolCalls(String text) {
        if (text == null) return "";
        String result = QWEN_TOOL_CALL.matcher(text).replaceAll("");
        log.debug("stripToolCalls: originalLength={}, resultLength={}", text.length(), result.length());
        return result.trim();
    }

    @SuppressWarnings("unchecked")
    private static AiToolCall buildCall(String name, String argsJson) {
        Map<String, Object> args;
        try {
            Map<String, Object> parsed = JsonHelper.parseObject(argsJson);
            args = parsed != null ? parsed : Map.of("_raw", argsJson);
        } catch (Exception e) {
            args = Map.of("_raw", argsJson);
        }
        return AiToolCall.of(name.trim(), args);
    }
}
