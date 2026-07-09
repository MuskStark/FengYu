package fan.summer.zhiflow.ai.agent;

import java.util.List;

/**
 * A complete plan produced by the planning phase of a Plan-and-Execute agent run.
 *
 * <p>An {@code AgentPlan} is an immutable, ordered list of {@link AgentStep}s that the
 * AgentRunner (Task 15) will execute sequentially. The {@code reasoning} field carries the
 * planner's justification for the plan and is surfaced to the user when plan approval is
 * required (see {@link AgentRunConfig#requirePlanApproval()}).
 *
 * @param goal      the user goal this plan addresses (mirrors {@link AgentRun#getGoal()})
 * @param steps     the ordered steps to execute; never null, may be empty for a no-op plan
 * @param reasoning the planner's explanation of why these steps achieve the goal
 */
public record AgentPlan(String goal,
                        List<AgentStep> steps,
                        String reasoning) {
}
