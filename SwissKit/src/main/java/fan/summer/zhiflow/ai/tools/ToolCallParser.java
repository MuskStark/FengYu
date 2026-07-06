package fan.summer.zhiflow.ai.tools;

import fan.summer.zhiflow.ai.util.JsonHelper;
import fan.summer.zhiflow.api.ai.AiToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw model output text and extracts structured AI tool-call objects.
 *
 * <p>This class recognises three patterns, tried in order:</p>
 * <ul>
 *   <li><b>Qwen2.5 pattern</b> — delimited by {@code <|tool_call_begin|>} and
 *       {@code <|tool_call_end|>} tokens with JSON fields {@code name} and
 *       {@code arguments} inside.</li>
 *   <li><b>Hermes pattern (Qwen3)</b> — a JSON object with {@code name} and
 *       {@code arguments} fields wrapped in {@code <tool_call>} … {@code </tool_call>}.</li>
 *   <li><b>Generic pattern</b> — a bare JSON object with {@code name} and
 *       {@code arguments} fields, optionally wrapped in a markdown code fence.</li>
 * </ul>
 *
 * <p>The first pattern that yields matches wins. Extracted calls are returned as
 * {@link AiToolCall} instances.</p>
 *
 * @see AiToolCall
 */
public class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);

    private static final Pattern QWEN_TOOL_CALL = Pattern.compile(
        "<\\|tool_call_begin\\|>.*?\"name\"\\s*:\\s*\"(.*?)\".*?\"arguments\"\\s*:\\s*(\\{.*?}).*?<\\|tool_call_end\\|>",
        Pattern.DOTALL
    );

    private static final Pattern HERMES_TOOL_CALL = Pattern.compile(
        "<tool_call>\\s*\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}\\s*</tool_call>",
        Pattern.DOTALL
    );

    private static final Pattern GENERIC_TOOL_CALL = Pattern.compile(
        "(?:```(?:json)?\\s*)?\\{\\s*\"name\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?})\\s*}(?:\\s*```)?",
        Pattern.DOTALL
    );

    /**
     * Parses all tool calls from the given model output text.
     *
     * @param text the raw text emitted by the AI model; may contain zero or more tool calls
     * @return an unmodifiable list of {@link AiToolCall}; empty if no calls were found or
     *         {@code text} is null/blank
     * @see #containsToolCallPattern(String)
     */
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

        m = HERMES_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        if (!calls.isEmpty()) {
            log.debug("Parsed {} tool call(s) via Hermes pattern", calls.size());
            return calls;
        }

        m = GENERIC_TOOL_CALL.matcher(text);
        while (m.find()) {
            calls.add(buildCall(m.group(1), m.group(2)));
        }
        log.debug("Parsed {} tool call(s) via generic pattern", calls.size());
        return calls;
    }

    /**
     * Quick-check whether the given text <i>looks like</i> it contains a tool-call
     * pattern without doing full parsing.
     *
     * @param text the text to inspect; may be null
     * @return true if {@code text} contains the Qwen delimiter, a Hermes wrapper, or both
     *         {@code "name"} and {@code "arguments"} substrings
     */
    public static boolean containsToolCallPattern(String text) {
        if (text == null) return false;
        return text.contains("<|tool_call_begin|>")
            || text.contains("<tool_call>")
            || text.contains("\"name\"") && text.contains("\"arguments\"");
    }

    /**
     * Removes all tool-call artefacts from the text, leaving only the conversational
     * content intended for the user.
     *
     * <p>Both the Qwen2.5-delimited form and the Hermes-wrapped form are stripped.
     * Surrounding whitespace is trimmed from the result.</p>
     *
     * @param text the original model output; may be null
     * @return the text with all detected tool-call blocks removed; empty string if
     *         {@code text} is null
     */
    public static String stripToolCalls(String text) {
        if (text == null) return "";
        String result = QWEN_TOOL_CALL.matcher(text).replaceAll("");
        result = HERMES_TOOL_CALL.matcher(result).replaceAll("");
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
