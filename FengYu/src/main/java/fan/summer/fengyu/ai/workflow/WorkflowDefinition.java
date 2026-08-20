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
        Integer publishedRevision,
        boolean hasUnpublishedChanges,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Backward-compatible constructor for callers that predate published snapshots. */
    public WorkflowDefinition(String id, String name, String description,
                              Map<String, Object> inputSchema, AgentPlan plan,
                              Map<String, NodeLayout> layout, Map<String, Object> graph,
                              boolean published, int revision,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, name, description, inputSchema, plan, layout, graph, published, revision,
                published ? revision : null, false, createdAt, updatedAt);
    }

    /** Canvas position of one node, keyed by compiled step index in {@link #layout()}. */
    public record NodeLayout(double x, double y) {}
}
