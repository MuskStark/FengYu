package fan.summer.ai.tools;

import com.google.gson.GsonBuilder;
import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

/**
 * Built-in AI tool that formats or minifies JSON strings.
 *
 * <p>This tool wraps {@link fan.summer.ai.util.JsonHelper} and {@code Gson} to
 * provide pretty-printed or compact JSON output. It accepts a JSON string and
 * an optional flag to minify rather than prettify.</p>
 *
 * <p>Tool name: {@code json_format}</p>
 *
 * @see AiTool
 */
public class BuiltinJsonFormatTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinJsonFormatTool.class);

    @Override public String getName() { return "json_format"; }

    @Override public String getDescription() {
        return "Format or minify a JSON string.\n"
             + "Args: json (string, required) — the JSON string;\n"
             + "      minify (boolean, optional, default false) — true for compact, false for pretty.\n"
             + "Example: json_format{\"json\":\"{\\\"a\\\":1}\",\"minify\":false}.";
    }

    @Override public String getLocalDescription() {
        return "Format or minify JSON. Args: json (string), minify (boolean, default false).\n"
             + "Example: json_format{\"json\":\"{\\\"a\\\":1}\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("json", "string", "JSON string to format or minify", true),
            AiToolParam.of("minify", "boolean", "If true, produce compact JSON; if false, pretty-print", false)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String json = (String) args.get("json");
        if (json == null || json.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "json is required")));

        boolean minify = Boolean.TRUE.equals(args.get("minify"));

        try {
            Object parsed = JsonHelper.parse(json);
            if (parsed == null)
                return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Invalid JSON: null result")));

            String output;
            if (minify) {
                output = JsonHelper.toJson(parsed);
            } else {
                output = new GsonBuilder().setPrettyPrinting().create().toJson(parsed);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", (minify ? "Minified" : "Pretty-printed") + " JSON");
            result.put("output", output);
            result.put("mode", minify ? "minify" : "pretty-print");

            log.debug("json_format success: minify={}", minify);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("json_format error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Invalid JSON: " + e.getMessage())));
        }
    }
}
