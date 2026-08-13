package fan.summer.fengyu.ai.workflow;

import fan.summer.fengyu.ai.agent.AgentEventSink;
import fan.summer.fengyu.ai.agent.AgentPlan;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.agent.AgentRunConfig;
import fan.summer.fengyu.ai.agent.AgentRunPersistenceService;
import fan.summer.fengyu.ai.agent.AgentRunRegistry;
import fan.summer.fengyu.ai.agent.AgentRunner;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One execution entry point shared by manual REST runs and AI workflow tools. */
@Service
public class WorkflowExecutionService {
    private static final long AI_RUN_TIMEOUT_MINUTES = 15;
    private final WorkflowService workflows;
    private final AgentRunRegistry registry;
    private final AgentRunPersistenceService persistence;
    private final AgentRunner runner;

    public WorkflowExecutionService(WorkflowService workflows, AgentRunRegistry registry,
                                    AgentRunPersistenceService persistence, AgentRunner runner) {
        this.workflows = workflows;
        this.registry = registry;
        this.persistence = persistence;
        this.runner = runner;
    }

    public AgentRun createManual(String workflowId, Map<String, Object> inputs,
                                 AgentRunConfig config) {
        WorkflowDefinition definition = workflows.get(workflowId);
        AgentPlan plan = workflows.compile(workflowId, inputs, false);
        AgentRunConfig effective = config == null
                ? new AgentRunConfig(false, true, false, 0, AiPermissionMode.ASK_FOR_APPROVAL)
                : config;
        return registry.create(definition.name(), effective, plan);
    }

    /** Execute a published workflow as one synchronous Spring AI tool call. */
    public String executeForAi(String workflowId, Map<String, Object> inputs) {
        try {
            WorkflowDefinition definition = workflows.get(workflowId);
            AgentPlan plan = workflows.compile(workflowId, inputs, true);
            AgentRun run = registry.create(definition.name(),
                    new AgentRunConfig(false, false, false, 0, AiPermissionMode.FULL_ACCESS), plan);
            persistence.create(run, null);
            CompletableFuture<String> result = new CompletableFuture<>();
            Map<Integer, String> stepResults = new ConcurrentHashMap<>();
            AgentEventSink sink = new AgentEventSink() {
                @Override public void onPlanToken(String delta) { }
                @Override public void onPlanReady(AgentPlan ready) { }
                @Override public void onPlanApprovalRequested() { }
                @Override public void onStepStart(int index) { }
                @Override public void onStepComplete(int index, String value) {
                    stepResults.put(index, value == null ? "" : value);
                }
                @Override public void onStepApprovalRequested(int index) { }
                @Override public void onComplete(String summary) {
                    String finalStep = stepResults.get(plan.steps().size() - 1);
                    result.complete(finalStep == null || finalStep.isEmpty() ? summary : finalStep);
                }
                @Override public void onError(String message) {
                    result.completeExceptionally(new IllegalStateException(message));
                }
            };
            result.whenComplete((ignored, error) -> registry.remove(run.getRunId()));
            runner.run(run, persistence.persisting(run, sink));
            try {
                return result.get(AI_RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException timeout) {
                run.markCancelled();
                run.approve(null);
                registry.remove(run.getRunId());
                throw timeout;
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IllegalStateException("Workflow execution failed: " + cause.getMessage(), cause);
        }
    }
}
