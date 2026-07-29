package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.api.ai.AiChatMessage;
import fan.summer.fengyu.api.ai.AiStreamCallback;
import fan.summer.fengyu.api.ai.ChatBackend;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Produces an executable workflow with the currently active AI backend.
 *
 * <p>The model only plans here; tools are invoked later by {@link AgentRunner}. Keeping
 * planning and execution separate makes approval meaningful and prevents a model from
 * executing a tool while it is still deciding which workflow to propose.
 */
@Component
public class ChatBackendPlanGenerator implements AgentRunner.PlanGenerator {

    /** Default budget (seconds) for the model to finish a planning response. */
    static final int DEFAULT_PLANNING_TIMEOUT_SECONDS = 180;

    private static final String SYSTEM_PROMPT = """
            You are a workflow planner. Return exactly one JSON object and no markdown.
            The JSON shape is:
            {
              "goal": "the requested goal",
              "reasoning": "short explanation",
              "steps": [
                {
                  "index": 0,
                  "toolName": "an exact available tool name",
                  "args": {},
                  "description": "what this step does",
                  "requiresApproval": false,
                  "dependsOn": []
                }
              ]
            }
            Rules:
            - Use only tools from AVAILABLE_TOOLS.
            - Step indexes must be contiguous and start at 0.
            - Every args object must satisfy that tool's inputSchema.
            - Prefer the fewest steps that fully achieve the goal.
            - Mark destructive, externally visible, or irreversible actions as requiresApproval.
            - Put prerequisite step indexes in dependsOn. Independent steps may run concurrently.
            - A later argument may reference an earlier result with
              {{steps.<index>.result}} or the immediately previous result with {{last.result}}.
            - If no tool is needed, return an empty steps array.
            """;

    private final AiModeService aiModeService;
    private final int planningTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatBackendPlanGenerator(AiModeService aiModeService) {
        this(aiModeService, DEFAULT_PLANNING_TIMEOUT_SECONDS);
    }

    /** Test seam: inject a shorter timeout so the cancellation path can be exercised quickly. */
    ChatBackendPlanGenerator(AiModeService aiModeService, int planningTimeoutSeconds) {
        this.aiModeService = aiModeService;
        this.planningTimeoutSeconds = planningTimeoutSeconds;
    }

    @Override
    public AgentPlan generate(String goal, List<ToolCallback> tools,
                              AgentRunner.PlanTokenSink tokenSink) {
        ChatBackend backend = aiModeService.getService()
                .orElseThrow(() -> new IllegalStateException("No active AI backend"));
        if (!backend.isReady()) {
            throw new IllegalStateException("The active AI backend is not ready");
        }

        String prompt = "GOAL:\n" + safe(goal) + "\n\nAVAILABLE_TOOLS:\n" + toolCatalog(tools);
        CompletableFuture<String> completion = new CompletableFuture<>();
        StringBuilder streamed = new StringBuilder();

        try {
            backend.chatWithoutTools(new ArrayList<>(List.of(
                    AiChatMessage.system(SYSTEM_PROMPT),
                    AiChatMessage.user(prompt)
            )), new AiStreamCallback() {
            @Override
            public void onToken(String fragment) {
                if (fragment == null) return;
                streamed.append(fragment);
                if (tokenSink != null) tokenSink.onToken(fragment);
            }

            @Override
            public void onComplete(String fullResponse, int tokensGenerated, double tokensPerSecond) {
                completion.complete(fullResponse == null || fullResponse.isBlank()
                        ? streamed.toString() : fullResponse);
            }

            @Override
            public void onError(Throwable error) {
                completion.completeExceptionally(error == null
                        ? new IllegalStateException("Workflow planning failed") : error);
            }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not start workflow planning: " + e.getMessage(), e);
        }

        try {
            return parseAndValidate(completion.get(planningTimeoutSeconds, TimeUnit.SECONDS),
                    goal, tools);
        } catch (Exception e) {
            // The planning call gave up (timeout) or failed. If the backend is still streaming
            // in the background (e.g. a hung model that never called onComplete/onError), its
            // `generating` flag would stay set forever and wedge every subsequent request.
            // Cancel the in-flight stream so the backend's worker can exit and release the lock.
            backend.cancelGeneration();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Could not generate workflow: " + cause.getMessage(), cause);
        }
    }

    static AgentPlan parseAndValidate(String response, String requestedGoal, List<ToolCallback> tools) {
        String json = extractJson(response);
        Map<String, Object> root = JsonHelper.parseObject(json);
        Object rawSteps = root.get("steps");
        if (!(rawSteps instanceof List<?> stepList)) {
            throw new IllegalArgumentException("Planner response has no steps array");
        }

        Set<String> toolNames = tools == null ? Set.of() : tools.stream()
                .map(t -> t.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<AgentStep> steps = new ArrayList<>(stepList.size());

        for (int i = 0; i < stepList.size(); i++) {
            if (!(stepList.get(i) instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Workflow step " + i + " is not an object");
            }
            String toolName = string(raw.get("toolName"));
            if (toolName.isBlank() || !toolNames.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Workflow step " + i + " references unavailable tool '" + toolName + "'");
            }
            Map<String, Object> args = objectMap(raw.get("args"));
            steps.add(new AgentStep(
                    i,
                    toolName,
                    args,
                    string(raw.get("description")),
                    Boolean.TRUE.equals(raw.get("requiresApproval")),
                    integerList(raw.get("dependsOn"))
            ));
        }

        String planGoal = string(root.get("goal"));
        return new AgentPlan(planGoal.isBlank() ? safe(requestedGoal) : planGoal,
                List.copyOf(steps), string(root.get("reasoning")));
    }

    private static String toolCatalog(List<ToolCallback> tools) {
        List<Map<String, Object>> catalog = new ArrayList<>();
        if (tools != null) {
            for (ToolCallback tool : tools) {
                var definition = tool.getToolDefinition();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", definition.name());
                item.put("description", definition.description());
                item.put("inputSchema", schemaValue(definition.inputSchema()));
                catalog.add(item);
            }
        }
        return JsonHelper.toJson(catalog);
    }

    private static Object schemaValue(String inputSchema) {
        try {
            Object parsed = JsonHelper.parse(inputSchema);
            return parsed == null ? inputSchema : parsed;
        } catch (Exception ignored) {
            return inputSchema;
        }
    }

    private static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Planner returned an empty response");
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && closingFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closingFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Planner response does not contain a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Step args must be a JSON object");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) value));
    }

    private static String string(Object value) {
        return value instanceof String s ? s : "";
    }

    private static List<Integer> integerList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Step dependsOn must be an array");
        }
        List<Integer> indexes = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof Number number)) {
                throw new IllegalArgumentException("Step dependsOn must contain integer indexes");
            }
            indexes.add(number.intValue());
        }
        return List.copyOf(indexes);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
