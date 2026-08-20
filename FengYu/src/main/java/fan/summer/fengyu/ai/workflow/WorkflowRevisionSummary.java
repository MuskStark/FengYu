package fan.summer.fengyu.ai.workflow;

import java.time.LocalDateTime;

/** Lightweight publication-history row for the flow settings UI. */
public record WorkflowRevisionSummary(
        int revision,
        String name,
        String description,
        LocalDateTime publishedAt,
        boolean active) {
}
