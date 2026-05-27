package fan.summer.ai.tools;

import fan.summer.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.api.log.LoggerFactory;
import fan.summer.api.log.PluginLogger;

import java.util.*;

public class BuiltinColorConvertTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinColorConvertTool.class);

    @Override public String getName() { return "color_convert"; }

    @Override public String getDescription() {
        return "Convert a color between HEX, RGB, and HSL formats. " +
               "Args: color (string, required) — color value (e.g. \"#5b8cf7\" or \"91,140,247\"); " +
               "from (string, required) — source format: HEX, RGB, or HSL; " +
               "to (string, required) — target format: HEX, RGB, or HSL.";
    }

    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("color", "string", "Color value to convert", true),
            AiToolParam.of("from", "string", "Source format: HEX, RGB, or HSL", true),
            AiToolParam.of("to", "string", "Target format: HEX, RGB, or HSL", true)
        );
    }

    @Override public AiToolResult execute(Map<String, Object> args) {
        String color = (String) args.get("color");
        String from = (String) args.get("from");
        String to = (String) args.get("to");

        if (color == null || color.isBlank()) return AiToolResult.error("color is required");
        if (from == null || to == null) return AiToolResult.error("from and to formats are required");

        try {
            int r, g, b;

            from = from.toUpperCase().trim();
            switch (from) {
                case "HEX" -> {
                    String hex = color.trim();
                    if (!hex.startsWith("#")) hex = "#" + hex;
                    java.awt.Color c = java.awt.Color.decode(hex);
                    r = c.getRed(); g = c.getGreen(); b = c.getBlue();
                }
                case "RGB" -> {
                    String[] parts = color.split("[,\\s]+");
                    if (parts.length < 3) return AiToolResult.error("RGB format: \"R, G, B\" (e.g. \"91, 140, 247\")");
                    r = Integer.parseInt(parts[0].trim());
                    g = Integer.parseInt(parts[1].trim());
                    b = Integer.parseInt(parts[2].trim());
                }
                default -> {
                    return AiToolResult.error("Unsupported source format: " + from + ". Use HEX, RGB, or HSL.");
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("input", Map.of("color", color, "format", from));

            to = to.toUpperCase().trim();
            switch (to) {
                case "HEX" -> {
                    result.put("output", String.format("#%02x%02x%02x", r, g, b));
                    result.put("targetFormat", "HEX");
                }
                case "RGB" -> {
                    result.put("output", String.format("%d, %d, %d", r, g, b));
                    result.put("targetFormat", "RGB");
                }
                case "HSL" -> {
                    float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
                    result.put("output", String.format("%.0f°, %.0f%%, %.0f%%",
                        hsb[0] * 360, hsb[1] * 100, hsb[2] * 100));
                    result.put("targetFormat", "HSL");
                }
                default -> {
                    return AiToolResult.error("Unsupported target format: " + to + ". Use HEX, RGB, or HSL.");
                }
            }

            log.debug("color_convert success: {} -> {}", from, to);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("color_convert error: {}", e.getMessage());
            return AiToolResult.error("Color conversion error: " + e.getMessage());
        }
    }
}
