package fan.summer.fengyu.ai.tasks;

import fan.summer.fengyu.ai.FengYuTool;
import fan.summer.fengyu.ai.agent.AgentRun;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolEffectProvider;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.ai.workflow.WorkflowExecutionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Background-task tool surface for the model: submit a published workflow as a
 * background task, poll or block for output, wait on many tasks at once, and kill a
 * runaway task. This frees the synchronous tool slot — the model no longer blocks for
 * up to fifteen minutes while a long workflow runs.
 *
 * <p>Task submission mirrors the synchronous {@code run_workflow_*} external effect (it
 * performs the same execution, just deferred); output/wait/list are read-only
 * observation; kill is a command-class effect.
 */
@Component
public class BackgroundTaskTools implements ToolEffectProvider {

    private final BackgroundTaskRegistry tasks;
    private final BackgroundTaskScheduler scheduler;
    /**
     * Lazy on purpose: the execution service transitively depends on the tool registry
     * (via AgentRunner → AiToolRegistry), which discovers this tool bean — a constructor
     * injection here closes a bean cycle.
     */
    private final org.springframework.beans.factory.ObjectProvider<WorkflowExecutionService> workflowsProvider;

    public BackgroundTaskTools(BackgroundTaskRegistry tasks,
            org.springframework.beans.factory.ObjectProvider<WorkflowExecutionService> workflowsProvider,
            BackgroundTaskScheduler scheduler) {
        this.tasks = tasks;
        this.workflowsProvider = workflowsProvider;
        this.scheduler = scheduler;
    }

    private WorkflowExecutionService workflows() {
        WorkflowExecutionService service = workflowsProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Workflow execution is not available");
        }
        return service;
    }

    @Override
    public ToolEffect effectFor(String toolName) {
        return switch (toolName) {
            case "task_output", "task_wait", "task_list",
                 "task_schedule_list" -> ToolEffect.READ;
            case "task_kill", "task_schedule_delete" -> ToolEffect.COMMAND;
            default -> ToolEffect.EXTERNAL;
        };
    }

    /**
     * Start a published workflow as a background task and return immediately.
     *
     * @param workflowId the published workflow id (run_workflow_&lt;id&gt; names it)
     * @param inputsJson JSON object of workflow inputs
     * @return {"taskId","status","description"} of the submitted task
     */
    @Tool(name = "task_submit_workflow",
          description = "Start a published workflow as a background task and return a taskId "
                  + "immediately; poll with task_output. Use this instead of run_workflow_* "
                  + "whenever the workflow may take longer than a minute.")
    public String submitWorkflow(String workflowId, String inputsJson) {
        try {
            Map<String, Object> inputs = parseInputs(inputsJson);
            // Capture the submitting chat's permission mode on the tool thread: the registry
            // worker thread has no context of its own, and startForAi must not fall back to
            // its ASK default (or inherit an unrelated pooled thread's mode) for this run.
            AiPermissionMode permissionMode = AiPermissionContext.current();
            BackgroundTaskRegistry.Task task = tasks.submit("workflow",
                    "workflow " + workflowId, running -> {
                        AiPermissionContext.set(permissionMode);
                        AgentRun run;
                        try {
                            run = workflows().startForAi(workflowId, inputs);
                        } finally {
                            AiPermissionContext.clear();
                        }
                        running.canceller = () -> {
                            run.markCancelled();
                            run.approve(null);
                        };
                        return workflows().waitForAiRun(run);
                    });
            return JsonHelper.toJson(tasks.summary(task));
        } catch (Exception error) {
            return error("{\"taskId\":null,\"error\":", error.getMessage());
        }
    }

    /**
     * Read a background task's status and output; optionally block until it finishes.
     *
     * @param taskId the task id from task_submit_workflow
     * @param timeoutMs milliseconds to wait for completion (0 = poll once)
     * @return the task summary {"taskId","status","output",...}
     */
    @Tool(name = "task_output",
          description = "Read a background task's status and output. timeoutMs > 0 blocks up to "
                  + "that many milliseconds for completion; 0 returns the current snapshot.")
    public String output(String taskId, Integer timeoutMs) {
        try {
            Map<String, Object> snapshot =
                    tasks.awaitOutput(taskId, timeoutMs == null ? 0 : timeoutMs);
            if (snapshot == null) return "{\"taskId\":\"" + taskId + "\",\"error\":\"unknown task\"}";
            return JsonHelper.toJson(snapshot);
        } catch (Exception error) {
            return error("{\"error\":", error.getMessage());
        }
    }

    /**
     * Wait for up to 20 background tasks at once.
     *
     * @param taskIdsJson JSON array of task ids
     * @param mode "any" (return when the first finishes) or "all"
     * @param timeoutMs milliseconds to wait (0..120000)
     * @return array of per-task summaries
     */
    @Tool(name = "task_wait",
          description = "Wait for up to 20 background tasks at once; mode is \"any\" or \"all\". "
                  + "Returns every task's status and output.")
    public String wait(String taskIdsJson, String mode, Integer timeoutMs) throws InterruptedException {
        try {
            List<String> ids = parseIds(taskIdsJson);
            BackgroundTaskRegistry.WaitMode waitMode = "all".equalsIgnoreCase(mode)
                    ? BackgroundTaskRegistry.WaitMode.ALL : BackgroundTaskRegistry.WaitMode.ANY;
            List<Map<String, Object>> snapshots =
                    tasks.waitMany(ids, waitMode, timeoutMs == null ? 30_000 : timeoutMs);
            return JsonHelper.toJson(snapshots);
        } catch (Exception error) {
            return error("{\"error\":", error.getMessage());
        }
    }

    /**
     * List background tasks (newest first).
     *
     * @return array of task summaries
     */
    @Tool(name = "task_list",
          description = "List background tasks, newest first, with status and output.")
    public String list() {
        try {
            return JsonHelper.toJson(tasks.list());
        } catch (Exception error) {
            return error("{\"error\":", error.getMessage());
        }
    }

    /**
     * Kill a running background task (cooperative first; SIGKILL escalation for processes).
     *
     * @param taskId the task id
     * @return {"ok":true|false,"taskId":...}
     */
    @Tool(name = "task_kill",
          description = "Kill a running background task. Cooperative cancellation first; "
                  + "process-backed tasks escalate SIGTERM then SIGKILL.")
    public String kill(String taskId) {
        boolean killed = tasks.kill(taskId);
        return "{\"ok\":" + killed + ",\"taskId\":\"" + taskId + "\"}";
    }

    /**
     * Schedule a published workflow to run periodically.
     *
     * @param workflowId the published workflow id
     * @param inputsJson JSON object of workflow inputs
     * @param intervalSeconds seconds between fires (minimum 60)
     * @param recurring true to repeat; false for a delayed one-shot
     * @param fireImmediately true to fire once right away before the interval
     * @return the schedule summary {"scheduleId","nextFireAt",...}
     */
    @Tool(name = "task_schedule",
          description = "Run a published workflow on a schedule (minimum 60-second interval, "
                  + "at most 50 active schedules, auto-expires after 7 days). recurring=false "
                  + "gives a delayed one-shot. Scheduled runs appear in task_output like manual ones.")
    public String schedule(String workflowId, String inputsJson, Integer intervalSeconds,
                           Boolean recurring, Boolean fireImmediately) {
        try {
            Map<String, Object> inputs = parseInputs(inputsJson);
            BackgroundTaskScheduler.Schedule created = scheduler.create(
                    workflowId, inputs,
                    intervalSeconds == null ? 3600 : intervalSeconds,
                    recurring == null || recurring,
                    Boolean.TRUE.equals(fireImmediately));
            return JsonHelper.toJson(scheduler.summary(created));
        } catch (Exception error) {
            return error("{\"scheduleId\":null,\"error\":", error.getMessage());
        }
    }

    /**
     * List active schedules.
     *
     * @return array of schedule summaries
     */
    @Tool(name = "task_schedule_list",
          description = "List active workflow schedules with their next fire times.")
    public String scheduleList() {
        try {
            return JsonHelper.toJson(scheduler.list());
        } catch (Exception error) {
            return error("{\"error\":", error.getMessage());
        }
    }

    /**
     * Delete a schedule by id.
     *
     * @param scheduleId the schedule id from task_schedule
     * @return {"ok":true|false}
     */
    @Tool(name = "task_schedule_delete",
          description = "Delete a workflow schedule; pending fires stop immediately.")
    public String scheduleDelete(String scheduleId) {
        return "{\"ok\":" + scheduler.delete(scheduleId) + ",\"scheduleId\":\"" + scheduleId + "\"}";
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String error(String prefix, String message) {
        String safe = message == null ? "unknown error" : message.replace("\"", "'");
        return prefix + "\"" + safe + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInputs(String inputsJson) throws Exception {
        if (inputsJson == null || inputsJson.isBlank()) return Map.of();
        Object parsed = JsonHelper.parse(inputsJson);
        if (parsed instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("inputs must be a JSON object");
    }

    private static List<String> parseIds(String taskIdsJson) throws Exception {
        Object parsed = JsonHelper.parse(taskIdsJson == null || taskIdsJson.isBlank()
                ? "[]" : taskIdsJson);
        List<String> ids = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String id && !id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }
}
