package fan.summer.ai.tools;

import fan.summer.api.ai.AiChatMessage;
import fan.summer.api.ai.AiService;
import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolCall;
import fan.summer.api.ai.AiToolParam;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates FunctionGemma's native tool calling protocol.
 * <p>
 * FunctionGemma uses a structured format with control tokens
 * ({@code <start_function_call>}, {@code <end_function_call>}, etc.)
 * instead of JSON. This adapter handles:
 * <ul>
 *   <li>Formatting tool declarations for the developer prompt</li>
 *   <li>Parsing tool calls from model output</li>
 *   <li>Formatting tool results for the response turn</li>
 *   <li>Building the complete prompt with {@code developer} role</li>
 * </ul>
 * <p>
 * Only active when a FunctionGemma model is detected in local mode.
 *
 * @see <a href="https://ai.google.dev/gemma/docs/functiongemma/formatting-and-best-practices">FunctionGemma formatting guide</a>
 */
public class FunctionGemmaAdapter {

    private static final PluginLogger log = LoggerFactory.getLogger(FunctionGemmaAdapter.class);

    /** FunctionGemma string delimiter token — wraps all string values. */
    private static final String STRING_DELIM = "🪙"; // 🪙

    /** Mandatory trigger phrase to activate FunctionGemma's tool calling mode. */
    private static final String SYSTEM_TRIGGER =
        "You are a model that can do function calling with the following functions\n";

    // ── Tool call parsing ──────────────────────────────────────

    private static final Pattern FG_CALL = Pattern.compile(
        "<start_function_call>call:(\\w+)\\{([^}]*)}<end_function_call>"
    );

    /**
     * Parse all FunctionGemma tool calls from model output.
     *
     * @param text raw model output
     * @return list of parsed tool calls; empty if none found
     */
    public List<AiToolCall> parseToolCalls(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<AiToolCall> calls = new ArrayList<>();
        Matcher m = FG_CALL.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String argsRaw = m.group(2);
            Map<String, Object> args = parseArgs(argsRaw, name);
            calls.add(AiToolCall.of(name, args));
            log.info("Parsed FunctionGemma tool call: name={}, args={}", name, args);
        }

        log.debug("FunctionGemma parseToolCalls: found {} calls", calls.size());
        return calls;
    }

    /**
     * Quick check whether the text contains a FunctionGemma tool call pattern.
     */
    public boolean containsToolCall(String text) {
        return text != null && text.contains("<start_function_call>");
    }

    /**
     * Parse FunctionGemma argument string: {@code key:value,key:value}.
     * String values are wrapped in 🪙 delimiters.
     */
    private Map<String, Object> parseArgs(String argsRaw, String toolName) {
        if (argsRaw == null || argsRaw.isBlank()) return Map.of();

        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, AiToolParam> paramTypes = getParamTypes(toolName);

        List<String> pairs = splitArgPairs(argsRaw);
        for (String pair : pairs) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx <= 0) continue;

            String key = pair.substring(0, colonIdx).trim();
            String valStr = pair.substring(colonIdx + 1).trim();

            // Remove 🪙 delimiters from string values
            valStr = valStr.replace(STRING_DELIM, "");

            // Type conversion based on tool parameter schema
            AiToolParam param = paramTypes.get(key);
            Object value = convertValue(valStr, param);
            args.put(key, value);
        }
        return args;
    }

    /**
     * Split argument string on commas, respecting 🪙-delimited strings
     * that may contain commas.
     */
    private List<String> splitArgPairs(String argsRaw) {
        List<String> pairs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < argsRaw.length(); i++) {
            char c = argsRaw.charAt(i);

            // Check for string delimiter (🪙 is a surrogate pair — 2 chars)
            if (i + 1 < argsRaw.length()) {
                String twoChars = argsRaw.substring(i, Math.min(i + 2, argsRaw.length()));
                if (twoChars.equals(STRING_DELIM)) {
                    inString = !inString;
                    current.append(twoChars);
                    i++; // skip second char of surrogate pair
                    continue;
                }
            }

            if (c == ',' && !inString) {
                pairs.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            pairs.add(current.toString().trim());
        }
        return pairs;
    }

    /**
     * Get parameter type map for a specific tool from the registered tools.
     */
    private Map<String, AiToolParam> getParamTypes(String toolName) {
        AiTool tool = AiServiceProvider.getTool(toolName);
        if (tool == null) return Map.of();
        Map<String, AiToolParam> types = new LinkedHashMap<>();
        for (AiToolParam p : tool.getParameters()) {
            types.put(p.name(), p);
        }
        return types;
    }

    /**
     * Convert a string value to the appropriate Java type based on parameter schema.
     */
    private Object convertValue(String valStr, AiToolParam param) {
        if (param == null) return valStr;
        String type = param.type().toLowerCase();
        return switch (type) {
            case "integer", "number" -> {
                try { yield Integer.parseInt(valStr); }
                catch (NumberFormatException e) { yield valStr; }
            }
            case "float", "double" -> {
                try { yield Double.parseDouble(valStr); }
                catch (NumberFormatException e) { yield valStr; }
            }
            case "boolean" -> Boolean.parseBoolean(valStr);
            default -> valStr;
        };
    }

    // ── Tool declaration formatting ────────────────────────────

    /**
     * Build FunctionGemma-format tool declarations for the developer prompt.
     *
     * <p>Format: {@code <start_function_declaration>declaration:name{description:...,parameters:{...}}<end_function_declaration>}</p>
     *
     * @param tools registered AI tools
     * @return formatted declaration string with mandatory trigger phrase
     */
    public String buildToolDeclarations(List<AiTool> tools) {
        if (tools.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append(SYSTEM_TRIGGER);

        for (AiTool tool : tools) {
            sb.append("<start_function_declaration>declaration:")
              .append(tool.getName())
              .append("{description:").append(tool.getDescription())
              .append(",parameters:");

            List<AiToolParam> params = tool.getParameters();
            if (!params.isEmpty()) {
                sb.append("{properties:{");
                StringJoiner propJoiner = new StringJoiner(",");
                List<String> required = new ArrayList<>();
                for (AiToolParam p : params) {
                    String fgType = toFgType(p.type());
                    propJoiner.add(p.name() + ":{description:" + p.description() + ",type:" + fgType + "}");
                    if (p.required()) required.add(p.name());
                }
                sb.append(propJoiner);
                sb.append("}");
                if (!required.isEmpty()) {
                    sb.append(",required:[").append(String.join(",", required)).append("]");
                }
                sb.append(",type:OBJECT");
            } else {
                sb.append("{type:OBJECT,properties:{}}");
            }
            sb.append("}}<end_function_declaration>");
        }

        log.debug("FunctionGemma tool declarations built: {} tools, {} chars", tools.size(), sb.length());
        return sb.toString();
    }

    /**
     * Convert Java type string to FunctionGemma type enum.
     */
    private String toFgType(String javaType) {
        if (javaType == null) return "STRING";
        return switch (javaType.toLowerCase()) {
            case "integer" -> "INTEGER";
            case "number", "float", "double" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "string" -> "STRING";
            default -> {
                if (javaType.endsWith("[]")) yield "ARRAY";
                yield "STRING";
            }
        };
    }

    // ── Tool result formatting ─────────────────────────────────

    /**
     * Format a tool execution result as a FunctionGemma function response.
     *
     * <p>Format: {@code <start_function_response>response:name{result:value}<end_function_response>}</p>
     */
    public String formatToolResponse(String toolName, String result) {
        return "<start_function_response>response:" + toolName +
               "{result:" + escapeForFG(result) + "}<end_function_response>";
    }

    /**
     * Escape text for inclusion in FunctionGemma structured blocks.
     * Wraps string values in 🪙 delimiters.
     */
    private String escapeForFG(String text) {
        if (text == null) return STRING_DELIM + STRING_DELIM;
        return STRING_DELIM + text + STRING_DELIM;
    }

    // ── Prompt building ────────────────────────────────────────

    /**
     * Build a complete FunctionGemma prompt with developer (system/tool defs),
     * user, model, and tool response turns.
     *
     * @param history chat messages
     * @param toolDeclarations pre-built tool declaration string
     * @return formatted prompt ready for model inference
     */
    public String buildPrompt(List<AiChatMessage> history, String toolDeclarations) {
        var sb = new StringBuilder();

        // Developer turn: system trigger + tool declarations
        if (toolDeclarations != null && !toolDeclarations.isEmpty()) {
            sb.append("<start_of_turn>developer\n")
              .append(toolDeclarations)
              .append("<end_of_turn>\n");
        }

        // Chat history
        for (var msg : history) {
            String role = switch (msg.role()) {
                case ASSISTANT -> "model";
                case SYSTEM -> "developer";
                case TOOL -> "developer";  // tool results use developer role in FunctionGemma
                default -> "user";
            };

            sb.append("<start_of_turn>").append(role).append("\n");

            // For tool results, use FunctionGemma response format
            if (msg.role() == AiChatMessage.Role.TOOL) {
                String toolName = msg.toolName() != null ? msg.toolName() : "unknown";
                sb.append(formatToolResponse(toolName, msg.content()));
            } else if (msg.role() == AiChatMessage.Role.ASSISTANT && msg.hasToolCalls()) {
                // Model turn with tool calls — emit the native format
                for (var tc : msg.toolCalls()) {
                    sb.append("<start_function_call>call:")
                      .append(tc.name()).append("{");
                    StringJoiner j = new StringJoiner(",");
                    for (var entry : tc.arguments().entrySet()) {
                        j.add(entry.getKey() + ":" + formatArgValue(entry.getValue()));
                    }
                    sb.append(j);
                    sb.append("}<end_function_call>");
                }
                // Also append any text content
                if (msg.content() != null && !msg.content().isEmpty()) {
                    sb.append(msg.content());
                }
            } else {
                sb.append(msg.content() == null ? "" : msg.content());
            }

            sb.append("<end_of_turn>\n");
        }

        // Start model turn for generation
        sb.append("<start_of_turn>model\n");
        return sb.toString();
    }

    /**
     * Format a single argument value for FunctionGemma call syntax.
     * Strings get 🪙 delimiters; others are left as-is.
     */
    private String formatArgValue(Object value) {
        if (value instanceof String s) {
            return STRING_DELIM + s + STRING_DELIM;
        }
        return String.valueOf(value);
    }

    /**
     * Strip FunctionGemma control tokens from model output to get clean text.
     */
    public String stripToolCalls(String text) {
        if (text == null) return "";
        // Remove function call blocks
        String result = text.replaceAll("<start_function_call>.*?<end_function_call>", "");
        // Remove any stray control tokens
        result = result.replace("<start_function_declaration>", "")
                       .replace("<end_function_declaration>", "")
                       .replace("<start_function_response>", "")
                       .replace("<end_function_response>", "");
        return result.trim();
    }
}
