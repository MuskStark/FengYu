package fan.summer.api.ai;

import java.util.List;
import java.util.Map;

/**
 * A tool that the AI model can invoke during generation.
 * <p>
 * Plugins register tools via {@link AiService#registerTool(AiTool)}.
 * When the model decides to call a tool, the inference engine invokes
 * {@link #execute(Map)} and feeds the result back to the model.
 *
 * <pre>
 * AiTool tool = new AiTool() {
 *     public String getName()        { return "get_weather"; }
 *     public String getDescription() { return "Get current weather for a city"; }
 *     public List&lt;AiToolParam&gt; getParameters() {
 *         return List.of(AiToolParam.of("city", "string", "City name"));
 *     }
 *     public AiToolResult execute(Map&lt;String, Object&gt; args) {
 *         String city = (String) args.get("city");
 *         return AiToolResult.success("Sunny, 22°C in " + city);
 *     }
 * };
 * aiService.registerTool(tool);
 * </pre>
 */
public interface AiTool {

    /** Unique tool name (e.g. "get_weather"). */
    String getName();

    /** Human-readable description shown to the model. */
    String getDescription();

    /** Parameter specifications. */
    List<AiToolParam> getParameters();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments map of parameter name → value
     * @return execution result
     */
    AiToolResult execute(Map<String, Object> arguments);
}