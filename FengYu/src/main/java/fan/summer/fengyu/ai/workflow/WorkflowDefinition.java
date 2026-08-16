package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentPlan;

import java.time.LocalDateTime;
import java.util.Map;

/** API/domain representation of one reusable workflow definition. */
public record WorkflowDefinition(
        String id,
        String name,
        String description,
        Map<String, Object> inputSchema,
        AgentPlan plan,
        Map<String, NodeLayout> layout,
        Map<String, Object> graph,
        boolean published,
        int revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Canvas position of one node, keyed by compiled step index in {@link #layout()}. */
    public record NodeLayout(double x, double y) {}
}
