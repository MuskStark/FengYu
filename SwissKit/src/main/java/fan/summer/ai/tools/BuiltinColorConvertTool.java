package fan.summer.ai.tools;

import fan.summer.zhiflow.api.ai.*;
import fan.summer.ai.util.JsonHelper;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.util.*;

/**
 * Built-in AI tool that converts colors between HEX, RGB, and HSL representations.
 *
 * <p>This tool wraps {@link java.awt.Color} to parse and re-format color values.
 * Supported transformations:</p>
 * <ul>
 *   <li>HEX (e.g. {@code #3574F0}) to RGB or HSL</li>
 *   <li>RGB (e.g. {@code 53, 116, 240}) to HEX or HSL</li>
 * </ul>
 *
 * <p>Tool name: {@code color_convert}</p>
 *
 * @see AiTool
 */
public class BuiltinColorConvertTool implements AiTool {
    private static final PluginLogger log = LoggerFactory.getLogger(BuiltinColorConvertTool.class);

    @Override public String getName() { return "color_convert"; }

    @Override public String getDescription() {
        return "Convert a color between HEX, RGB, and HSL formats.\n"
             + "Args: color (string, required) — the color value (e.g. \"#3574F0\" or \"53,116,240\");\n"
             + "      from (string, required, enum: HEX|RGB|HSL) — source format;\n"
             + "      to   (string, required, enum: HEX|RGB|HSL) — target format.\n"
             + "Example: color_convert{\"color\":\"#3574F0\",\"from\":\"HEX\",\"to\":\"RGB\"}.";
    }

    @Override public String getLocalDescription() {
        return "Convert color. Args: color (string), from (HEX|RGB|HSL), to (HEX|RGB|HSL).\n"
             + "Example: color_convert{\"color\":\"#fff\",\"from\":\"HEX\",\"to\":\"RGB\"}.";
    }

    /**
     * Returns the list of parameter definitions for this tool.
     *
     * @return a list containing three required parameters:
     *         {@code color} (string), {@code from} (string), and {@code to} (string)
     * @see AiToolParam
     */
    @Override public List<AiToolParam> getParameters() {
        return List.of(
            AiToolParam.of("color", "string", "Color value to convert", true),
            AiToolParam.of("from", "string", "Source format", true,
                List.of("HEX", "RGB", "HSL")),
            AiToolParam.of("to", "string", "Target format", true,
                List.of("HEX", "RGB", "HSL"))
        );
    }

    /**
     * Executes the color conversion based on the supplied arguments.
     *
     * @param args must contain:
     *             {@code color} — the color value to convert;
     *             {@code from} — source format ({@code HEX}, {@code RGB}, or {@code HSL});
     *             {@code to} — target format ({@code HEX}, {@code RGB}, or {@code HSL})
     * @return a result map containing {@code success}, {@code input}, {@code output},
     *         and {@code targetFormat}; or an error result if arguments are missing or invalid
     * @see AiToolResult
     */
    @Override public AiToolResult execute(Map<String, Object> args) {
        String color = (String) args.get("color");
        String from = (String) args.get("from");
        String to = (String) args.get("to");

        if (color == null || color.isBlank())
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "color is required")));
        if (from == null || to == null)
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "from and to formats are required")));

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
                    if (parts.length < 3)
                        return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "RGB format: \"R, G, B\" (e.g. \"53, 116, 240\")")));
                    r = Integer.parseInt(parts[0].trim());
                    g = Integer.parseInt(parts[1].trim());
                    b = Integer.parseInt(parts[2].trim());
                }
                default -> {
                    return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Unsupported source format: " + from + ". Use HEX, RGB, or HSL.")));
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
                    return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Unsupported target format: " + to + ". Use HEX, RGB, or HSL.")));
                }
            }
            result.put("summary", from + " → " + to + " conversion ok");

            log.debug("color_convert success: {} -> {}", from, to);
            return AiToolResult.success(JsonHelper.toJson(result));
        } catch (Exception e) {
            log.error("color_convert error: {}", e.getMessage());
            return AiToolResult.error(JsonHelper.toJson(Map.of("success", false, "error", "Color conversion error: " + e.getMessage())));
        }
    }
}
