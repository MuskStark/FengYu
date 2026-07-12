package fan.summer.fengyu.ai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A placeholder {@link AgentRunner.PlanGenerator} that produces an empty plan for any goal.
 *
 * <p><b>Why this exists (and not a ChatClient-backed planner):</b> Task 16 wires the
 * Plan-and-Execute agent to HTTP/SSE, and the {@link AgentRunner} requires a
 * {@link AgentRunner.PlanGenerator} seam. A production-quality planner would build a
 * planning prompt from {@code goal} + the tools' {@code name/description/inputSchema},
 * stream it through Spring AI's {@code ChatClient}, and parse the returned JSON into an
 * {@link AgentPlan}. The complication: FengYu's four {@code ChatModel} beans
 * ({@code openAiChatModel} / {@code deepSeekChatModel} / {@code anthropicChatModel} /
 * {@code ollamaChatModel}) are all {@code @Lazy} and selected by name at mode-switch time
 * inside {@code SpringAiCloudBackend}; the <em>active</em> model lives inside the opaque
 * {@code ChatBackend} held by {@code AiModeService} and is not directly exposed as a bean.
 * Wiring a real planner cleanly therefore needs a small refactor of the backend layer to
 * surface the active {@code ChatModel} / {@code ChatClient} — out of scope for this task.
 *
 * <p>This stub keeps the controller, registry, and SSE plumbing fully functional: a run
 * started with {@code POST /api/agent/run} will plan instantly (empty plan) and complete
 * successfully, so the full SSE event stream ({@code onPlanReady → onComplete}) is
 * observable end-to-end. The planner quality is explicitly called out as secondary in the
 * task brief; the next iteration replaces {@link #generate(String, List, AgentRunner.PlanTokenSink)}
 * with the ChatClient-backed implementation.
 *
 * <p>TODO(Task 17+): replace with a {@code ChatClient}-backed production planner.
 */
@Component
public class StubPlanGenerator implements AgentRunner.PlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(StubPlanGenerator.class);

    @Override
    public AgentPlan generate(String goal, List<ToolCallback> tools, AgentRunner.PlanTokenSink tokenSink) {
        log.debug("StubPlanGenerator: returning empty plan for goal '{}' ({} tool(s) available)",
                goal, tools == null ? 0 : tools.size());
        // Emit a single planning token so the SSE onPlanToken path is exercised even with no LLM.
        if (tokenSink != null) {
            tokenSink.onToken("(stub planner: no steps)");
        }
        return new AgentPlan(goal, List.of(),
                "Stub planner produced an empty plan. A ChatClient-backed planner is not yet wired.");
    }
}
