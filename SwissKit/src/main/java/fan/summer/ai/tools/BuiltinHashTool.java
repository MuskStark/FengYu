package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Built-in AI tool that calculates a cryptographic hash digest of input text.
 *
 * <p>Supports MD5, SHA-1, SHA-256, and SHA-512 algorithms. The computed hash is
 * returned as a lowercase hexadecimal string.</p>
 *
 * <p>Required arguments:</p>
 * <ul>
 *   <li>{@code text} (string) — the input text to hash</li>
 *   <li>{@code algorithm} (string) — one of {@code MD5}, {@code SHA-1}, {@code SHA-256}, {@code SHA-512}</li>
 * </ul>
 *
 * @see BuiltinBase64Tool
 */

public class BuiltinHashTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinHashTool.class);

    @Override public String getName() { return "hash_calculate"; }

    @Override public String getDescription() {
        return "Calculate cryptographic hash digest of input text.\n"
             + "Args: text (string, required) — input text;\n"
             + "      algorithm (string, required, enum: MD5|SHA-1|SHA-256|SHA-512).\n"
             + "Example: hash_calculate{\"text\":\"abc\",\"algorithm\":\"SHA-256\"}.";
    }

    @Override public String getLocalDescription() {
        return "Hash digest. Args: text (string), algorithm (MD5|SHA-1|SHA-256|SHA-512).\n"
             + "Example: hash_calculate{\"text\":\"abc\",\"algorithm\":\"MD5\"}.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("text", "string", "Input text to hash", true),
            AiToolParam.of("algorithm", "string",
                "Hash algorithm", true,
                List.of("MD5", "SHA-1", "SHA-256", "SHA-512"))
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String text = (String) args.get("text");
        String algorithm = (String) args.get("algorithm");

        if (text == null)
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "text is required")));
        if (algorithm == null || algorithm.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "algorithm is required")));

        Set<String> allowed = Set.of("MD5", "SHA-1", "SHA-256", "SHA-512");
        String algoUpper = algorithm.toUpperCase().trim();
        if (!allowed.contains(algoUpper)) {
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Unsupported algorithm: " + algorithm + ". Allowed: MD5, SHA-1, SHA-256, SHA-512")));
        }

        try {
            MessageDigest md = MessageDigest.getInstance(algoUpper);
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", algoUpper + " digest computed");
            result.put("algorithm", algoUpper);
            result.put("hash", hex.toString());
            log.debug("hash_calculate success: algo={}", algoUpper);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("hash_calculate error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Hash error: " + e.getMessage())));
        }
    }
}
