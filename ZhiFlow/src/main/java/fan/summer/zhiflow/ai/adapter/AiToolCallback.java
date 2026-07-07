package fan.summer.zhiflow.ai.adapter;

import fan.summer.zhiflow.ai.tools.AiToolDescriptions;   // relocated to adapter/ in Task 11
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.ai.AiToolResult;
import fan.summer.zhiflow.ai.util.JsonHelper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Adapts a ZhiFlow plugin {@link AiTool} into a Spring AI {@link ToolCallback}.
 *
 * <p>This is the stable seam of the migration: plugins keep implementing
 * {@code AiTool.execute(Map) -> AiToolResult} unchanged; Spring AI invokes them
 * through this adapter. Replaces the LangChain4j {@code AiToolToToolSpecification}
 * (which was schema-only and not executable).
 *
 * <p>JSON-schema is built by {@link ToolSchemaJson}; argument parsing reuses
 * {@link JsonHelper} (Gson). Local/cloud description selection is delegated to
 * {@link AiToolDescriptions} so mode-aware tool descriptions keep working.
 */
public final class AiToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AiToolCallback.class);

    private final AiTool aiTool;

    public AiToolCallback(AiTool aiTool) {
        this.aiTool = aiTool;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schema = ToolSchemaJson.build(AiToolDescriptions.pickParameters(aiTool));
        return DefaultToolDefinition.builder()
                .name(aiTool.getName())
                .description(AiToolDescriptions.pickDescription(aiTool))
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> args = parseArgs(toolInput);
        try {
            log.debug("Executing AiTool via Spring AI: name={}, args={}", aiTool.getName(), args);
            AiToolResult result = aiTool.execute(args);
            return result.output();
        } catch (Exception e) {
            log.error("AiTool execution threw: name={}, error={}", aiTool.getName(), e.getMessage(), e);
            // Never propagate — Spring AI expects a String back. Return an error JSON
            // the model can reason about, mirroring ToolExecutor's old jsonError shape.
            return JsonHelper.toJson(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /** Expose the underlying AiTool so the backend can correlate by name. */
    public AiTool aiTool() {
        return aiTool;
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JsonHelper.parseObject(json);
        } catch (Exception e) {
            log.warn("Failed to parse tool-call arguments JSON, using empty map: '{}'", json);
            return Map.of();
        }
    }
}
