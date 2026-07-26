package fan.summer.fengyu.ai.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds every live {@link AgentRun} keyed by id so the HTTP layer can look one up for
 * approve / cancel / stream after the run has been started by {@code POST /api/agent/run}.
 *
 * <p>This is a process-local, in-memory registry: runs do not survive a restart, and there is
 * no eviction yet (a long-running server would accumulate completed runs). The controller
 * (Task 16) is the sole consumer; it {@link #create(String, AgentRunConfig) creates} a run on
 * a {@code /run} request and {@link #get(String) resolves} it by id on every subsequent call.
 * Eviction / TTL is a Phase 2 concern once the run lifecycle is observable end-to-end.
 *
 * <p>Backed by a {@link ConcurrentHashMap} so concurrent lookups from the runner's virtual
 * thread and the controller's request threads are safe.
 */
@Component
public class AgentRunRegistry {

    private final ConcurrentMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    /**
     * Creates and registers a fresh {@link AgentRun} with a generated id.
     *
     * @param goal   the user goal; never re-encoded, stored verbatim
     * @param config the approval/recovery config; if {@code null} a no-approval / no-replan
     *               default is used (the simplest path that completes without human input)
     * @return the newly registered run (also retrievable thereafter via {@link #get(String)})
     */
    public AgentRun create(String goal, AgentRunConfig config) {
        return create(goal, config, null);
    }

    /**
     * Creates a run with an optional caller-supplied workflow. When {@code workflow} is
     * present the runner executes it directly instead of asking the model to create a plan.
     */
    public AgentRun create(String goal, AgentRunConfig config, AgentPlan workflow) {
        AgentRunConfig effective = config != null ? config
                : new AgentRunConfig(false, false, false, 0);
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), goal, effective);
        run.setPlan(workflow);
        runs.put(run.getRunId(), run);
        return run;
    }

    /**
     * @param runId the id returned by {@link #create}
     * @return the registered {@link AgentRun}, or {@code null} if unknown / evicted
     */
    public AgentRun get(String runId) {
        if (runId == null) return null;
        return runs.get(runId);
    }

    /**
     * Removes a run from the registry (optional cleanup). Returns silently if the id is unknown.
     *
     * @param runId the id returned by {@link #create}
     */
    public void remove(String runId) {
        if (runId != null) runs.remove(runId);
    }
}
