package fan.summer.api.ai;

import java.util.List;
import java.util.Map;

/**
 * A tool that the AI model can invoke during generation.
 * <p>
 * Plugins register tools via {@link AiServiceProvider#registerTool(AiTool)}.
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

    /**
     * Local-mode description (short, keyword-dense, tuned for small local models
     * like Qwen3-4B). Default falls back to {@link #getDescription()}.
     *
     * @return description shown to the model in local mode
     */
    default String getLocalDescription() { return getDescription(); }

    /**
     * Local-mode parameter list (may be a simplified subset of
     * {@link #getParameters()}). Default falls back to {@link #getParameters()}.
     *
     * @return parameters shown to the model in local mode
     */
    default java.util.List<AiToolParam> getLocalParameters() { return getParameters(); }

    /**
     * Whether this tool is visible when the active backend is local.
     * Default {@code true} — override to {@code false} for tools that require
     * strong-model reasoning (e.g. tools that drive their own think-act loop).
     *
     * @return {@code true} if the tool should be visible in local mode
     */
    default boolean supportsLocal() { return true; }

    /**
     * Whether this tool is visible when the active backend is cloud
     * (OpenAI / Anthropic). Default {@code true}.
     *
     * @return {@code true} if the tool should be visible in cloud mode
     */
    default boolean supportsCloud() { return true; }
}