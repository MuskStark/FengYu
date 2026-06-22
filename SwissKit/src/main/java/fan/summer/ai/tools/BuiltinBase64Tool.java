package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Built-in AI tool that encodes text to Base64 or decodes Base64 back to plain text.
 *
 * <p>Performs standard Base64 encoding and decoding using UTF-8 character encoding.</p>
 *
 * <p>Required arguments:</p>
 * <ul>
 *   <li>{@code text} (string) — the input text to encode or the Base64 string to decode</li>
 *   <li>{@code mode} (string) — either {@code encode} or {@code decode}</li>
 * </ul>
 *
 * @see BuiltinHashTool
 */

public class BuiltinBase64Tool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinBase64Tool.class);

    @Override public String getName() { return "base64"; }

    @Override public String getDescription() {
        return "Encode text to Base64 or decode Base64 back to text. " +
               "Args: text (string, required) — the input text; " +
               "mode (string, required) — \"encode\" or \"decode\".";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to encode or decode", true),
            AiToolParam.of("mode", "string",
                "Direction of the conversion: encode or decode", true,
                List.of("encode", "decode"))
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String text = (String) args.get("text");
        String mode = (String) args.get("mode");

        if (text == null || text.isBlank()) return AiToolResult.error("text is required");
        if (mode == null || mode.isBlank()) return AiToolResult.error("mode is required (encode or decode)");

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String output;
            if ("encode".equalsIgnoreCase(mode)) {
                output = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
                result.put("mode", "encode");
            } else if ("decode".equalsIgnoreCase(mode)) {
                output = new String(Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8);
                result.put("mode", "decode");
            } else {
                return AiToolResult.error("Invalid mode: " + mode + ". Use \"encode\" or \"decode\".");
            }
            result.put("success", true);
            result.put("output", output);
            log.debug("base64 {} success, inputLength={}", mode, text.length());
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("base64 error: {}", e.getMessage());
            return AiToolResult.error("Base64 error: " + e.getMessage());
        }
    }
}
