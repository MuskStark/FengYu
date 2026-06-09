package fan.summer.ai.tools;

import fan.summer.api.ai.AiServiceProvider;
import fan.summer.api.ai.AiTool;
import fan.summer.api.ai.AiToolParam;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles slash commands in the AI chat input for direct tool invocation.
 *
 * <p>When the user types a message starting with {@code /}, this handler parses
 * the command and either executes the tool directly or delegates to the model
 * with a constrained tool set. This is especially useful for small local models
 * (e.g. FunctionGemma-270M) that struggle to select the correct tool from a
 * large declaration list.</p>
 *
 * <p>Supported patterns:</p>
 * <ul>
 *   <li>{@code /} or {@code /tools} — list all available tools</li>
 *   <li>{@code /tool_name} — show tool help (description + parameters)</li>
 *   <li>{@code /tool_name value} — direct execution for single-param tools</li>
 *   <li>{@code /tool_name key=value key=value} — direct execution with named args</li>
 *   <li>{@code /tool_name natural language} — guided model execution (constrained to that tool)</li>
 * </ul>
 *
 * @see AiTool
 * @see AiServiceProvider#setConstrainedTool(String)
 */
public class SlashCommandHandler {

    /** Matches key=value pairs where values can be quoted with " or '. */
    private static final Pattern KEY_VALUE = Pattern.compile("(\\w+)=(\"[^\"]*\"|'[^']*'|\\S+)");

    /** Result of parsing and resolving a slash command. */
    public enum Mode { LIST, HELP, DIRECT, GUIDED_MODEL }

    public record Result(Mode mode, AiTool tool, Map<String, Object> args, String message) {}

    /**
     * Parse and handle a slash command.
     *
     * @param input user input starting with "/"
     * @return handling result, never null
     */
    public static Result handle(String input) {
        String trimmed = input.substring(1).trim();

        // / or /tools or /help → list all tools
        if (trimmed.isEmpty() || trimmed.equals("tools") || trimmed.equals("help")) {
            return new Result(Mode.LIST, null, null, buildToolList());
        }

        // Parse tool name and remaining args
        int spaceIdx = trimmed.indexOf(' ');
        String toolName = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
        String rawArgs = spaceIdx > 0 ? trimmed.substring(spaceIdx + 1).trim() : "";

        // Resolve tool (exact match first, then prefix match)
        AiTool tool = resolveTool(toolName);
        if (tool == null) {
            return new Result(Mode.HELP, null, null,
                "⚠ Unknown tool: /" + toolName + "\n\n" + buildToolList());
        }

        // No args → show tool help
        if (rawArgs.isEmpty()) {
            return new Result(Mode.HELP, tool, null, buildToolHelp(tool));
        }

        // Try direct argument parsing
        Map<String, Object> args = tryParseArgs(tool, rawArgs);
        if (args != null && allRequiredFilled(tool, args)) {
            return new Result(Mode.DIRECT, tool, args, null);
        }

        // Fall back to guided model execution
        return new Result(Mode.GUIDED_MODEL, tool, args, rawArgs);
    }

    // ── Tool resolution ──────────────────────────────────────

    private static AiTool resolveTool(String name) {
        AiTool exact = AiServiceProvider.getTool(name);
        if (exact != null) return exact;

        // Prefix match (e.g., "base64" matches "base64_encode")
        List<AiTool> all = AiServiceProvider.getTools();
        for (AiTool t : all) {
            if (t.getName().startsWith(name)) return t;
        }
        return null;
    }

    // ── Argument parsing ─────────────────────────────────────

    private static Map<String, Object> tryParseArgs(AiTool tool, String rawArgs) {
        List<AiToolParam> params = tool.getParameters();
        if (params.isEmpty()) return new LinkedHashMap<>();

        // Check if args contain key=value patterns
        Matcher kvMatcher = KEY_VALUE.matcher(rawArgs);
        boolean hasKeyValue = kvMatcher.find();

        Map<String, Object> args = new LinkedHashMap<>();

        if (hasKeyValue) {
            // Parse key=value pairs
            args = parseKeyValueArgs(rawArgs, params);

            // Remaining text (not part of key=value) → fill first unmatched required string param
            String remaining = stripKeyValuePairs(rawArgs);
            if (!remaining.trim().isEmpty()) {
                for (AiToolParam p : params) {
                    if (p.required() && !args.containsKey(p.name())
                        && "string".equalsIgnoreCase(p.type())) {
                        args.put(p.name(), remaining.trim());
                        break;
                    }
                }
            }
            return args;
        }

        // No key=value patterns → positional argument
        List<AiToolParam> required = params.stream().filter(AiToolParam::required).toList();
        if (required.size() == 1) {
            // Single required param → use entire rawArgs as its value
            args.put(required.get(0).name(), convertValue(rawArgs.trim(), required.get(0)));
            return args;
        }

        if (required.isEmpty()) {
            // All params optional → use rawArgs for first string param
            for (AiToolParam p : params) {
                if ("string".equalsIgnoreCase(p.type())) {
                    args.put(p.name(), rawArgs.trim());
                    break;
                }
            }
            return args;
        }

        // Multiple required params with no key=value → can't parse reliably
        return null;
    }

    private static Map<String, Object> parseKeyValueArgs(String rawArgs, List<AiToolParam> params) {
        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, AiToolParam> paramByName = new LinkedHashMap<>();
        for (AiToolParam p : params) paramByName.put(p.name(), p);

        Matcher m = KEY_VALUE.matcher(rawArgs);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2);
            // Strip surrounding quotes
            if ((val.startsWith("\"") && val.endsWith("\"") && val.length() > 1)
                || (val.startsWith("'") && val.endsWith("'") && val.length() > 1)) {
                val = val.substring(1, val.length() - 1);
            }
            AiToolParam param = paramByName.get(key);
            args.put(key, param != null ? convertValue(val, param) : val);
        }
        return args;
    }

    private static String stripKeyValuePairs(String rawArgs) {
        return KEY_VALUE.matcher(rawArgs).replaceAll("").trim();
    }

    private static Object convertValue(String val, AiToolParam param) {
        if (param == null) return val;
        return switch (param.type().toLowerCase()) {
            case "integer", "number" -> {
                try { yield Integer.parseInt(val); }
                catch (NumberFormatException e) { yield val; }
            }
            case "float", "double" -> {
                try { yield Double.parseDouble(val); }
                catch (NumberFormatException e) { yield val; }
            }
            case "boolean" -> Boolean.parseBoolean(val);
            default -> val;
        };
    }

    private static boolean allRequiredFilled(AiTool tool, Map<String, Object> args) {
        for (AiToolParam p : tool.getParameters()) {
            if (p.required() && !args.containsKey(p.name())) return false;
        }
        return true;
    }

    // ── Display helpers ──────────────────────────────────────

    private static String buildToolList() {
        List<AiTool> tools = AiServiceProvider.getTools();
        if (tools.isEmpty()) return "No tools available.";

        var sb = new StringBuilder();
        sb.append("📋 Available tools (").append(tools.size()).append("):\n\n");
        for (AiTool t : tools) {
            sb.append("  /").append(t.getName());
            int pad = Math.max(2, 26 - t.getName().length());
            sb.append(" ".repeat(pad)).append("— ").append(shorten(t.getDescription(), 60)).append("\n");
        }
        sb.append("\nType /tool_name for details, or /tool_name <args> to execute directly.");
        return sb.toString();
    }

    private static String buildToolHelp(AiTool tool) {
        var sb = new StringBuilder();
        sb.append("🔧 /").append(tool.getName()).append("\n");
        sb.append(tool.getDescription()).append("\n");

        List<AiToolParam> params = tool.getParameters();
        if (!params.isEmpty()) {
            sb.append("\nParameters:\n");
            for (AiToolParam p : params) {
                sb.append("  • ").append(p.name());
                sb.append(" (").append(p.type()).append(")");
                if (p.required()) sb.append(" [required]");
                sb.append(" — ").append(p.description()).append("\n");
            }

            sb.append("\nUsage:\n");
            List<AiToolParam> required = params.stream().filter(AiToolParam::required).toList();
            if (required.size() <= 1) {
                sb.append("  /").append(tool.getName()).append(" <value>\n");
            } else {
                sb.append("  /").append(tool.getName());
                for (AiToolParam p : params) {
                    sb.append(" ").append(p.name()).append("=<value>");
                }
                sb.append("\n");
            }
        } else {
            sb.append("\nUsage: /").append(tool.getName()).append("  (no arguments needed)\n");
        }
        return sb.toString();
    }

    private static String shorten(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "…";
    }
}
