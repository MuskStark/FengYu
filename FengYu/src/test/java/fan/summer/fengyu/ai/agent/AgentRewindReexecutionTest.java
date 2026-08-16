package fan.summer.fengyu.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewind must actually RE-RUN: given a completed three-step run, rewinding to step 1
 * produces a run that skips step 0 (its completed execution is inherited) and executes
 * steps 1 and 2 again. The rewound run pauses for plan review first — the test
 * approves through the gate.
 */
class AgentRewindReexecutionTest {

    static final class CountingTool implements ToolCallback,
            fan.summer.fengyu.ai.tools.AuditedToolCallback {
        final Map<String, Integer> callsByCommand = new ConcurrentHashMap<>();
        final String name;

        CountingTool(String name) {
            this.name = name;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}").build();
        }
        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
        @Override public String call(String input) {
            callsByCommand.merge(input, 1, Integer::sum);
            return "ok:" + input;
        }
        @Override public fan.summer.fengyu.ai.tools.ToolEffect effect() {
            return fan.summer.fengyu.ai.tools.ToolEffect.READ;
        }
    }

    private static final class LatchSink implements AgentEventSink {
        final CountDownLatch done = new CountDownLatch(1);
        @Override public void onPlanToken(String delta) {}
        @Override public void onPlanReady(AgentPlan plan) {}
        @Override public void onPlanApprovalRequested() {}
        @Override public void onStepStart(int index) {}
        @Override public void onStepComplete(int index, String result) {}
        @Override public void onStepApprovalRequested(int index) {}
        @Override public void onComplete(String summary) { done.countDown(); }
        @Override public void onError(String message) { done.countDown(); }
    }

    @Test
    void rewindRerunsFromBoundaryAndSkipsInheritedCompletions() throws Exception {
        CountingTool toolA = new CountingTool("tool_a");
        CountingTool toolB = new CountingTool("tool_b");
        CountingTool toolC = new CountingTool("tool_c");
        AgentPlan plan = new AgentPlan("goal", List.of(
                new AgentStep(0, "tool_a", Map.of("command", "a"), "a", false),
                new AgentStep(1, "tool_b", Map.of("command", "b"), "b", false, List.of(0)),
                new AgentStep(2, "tool_c", Map.of("command", "c"), "c", false, List.of(1))),
                "");
        AgentRunConfig config = new AgentRunConfig(false, false, false, 0,
                fan.summer.fengyu.ai.tools.AiPermissionMode.FULL_ACCESS);

        // Original run: all three steps execute once.
        AgentRun first = new AgentRun("rewind-source", "goal", config);
        LatchSink firstSink = new LatchSink();
        AgentRunner runner = new AgentRunner(() -> List.of(toolA, toolB, toolC),
                (goal, tools, sink) -> plan, AgentRunner.toolResolvingExecutor());
        runner.run(first, firstSink);
        assertTrue(firstSink.done.await(10, TimeUnit.SECONDS));
        assertEquals(AgentRunStatus.COMPLETED, first.getStatus());
        assertEquals(1, toolA.callsByCommand.getOrDefault("{\"command\":\"a\"}", 0));
        assertEquals(1, toolB.callsByCommand.getOrDefault("{\"command\":\"b\"}", 0));
        assertEquals(1, toolC.callsByCommand.getOrDefault("{\"command\":\"c\"}", 0));

        // Rewind to step 1: inherit only step 0's completion; re-run 1 and 2.
        List<StepExecution> inherited = List.of(
                new StepExecution(0, StepStatus.COMPLETED, "ok:a"));
        AgentRun rewound = new AgentRun("rewind-target", "goal",
                config.withCapabilityMode(null) == null ? config : config); // plan review on
        rewound.setPlan(plan);
        rewound.restoreExecutions(inherited);
        // The persistence layer marks rewound runs for plan review; the runner's
        // approval gate needs a releaser.
        Thread approver = new Thread(() -> {
            while (rewound.getStatus() != AgentRunStatus.COMPLETED
                    && rewound.getStatus() != AgentRunStatus.FAILED
                    && rewound.getStatus() != AgentRunStatus.CANCELLED) {
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                rewound.approve(null);
            }
        });
        approver.start();
        LatchSink rewindSink = new LatchSink();
        runner.run(rewound, rewindSink);
        assertTrue(rewindSink.done.await(10, TimeUnit.SECONDS));
        approver.join(1000);
        assertEquals(AgentRunStatus.COMPLETED, rewound.getStatus());
        // Step 0 NOT re-run; steps 1 and 2 re-run exactly once more.
        assertEquals(1, toolA.callsByCommand.getOrDefault("{\"command\":\"a\"}", 0),
                "step 0 must not re-run");
        assertEquals(2, toolB.callsByCommand.getOrDefault("{\"command\":\"b\"}", 0),
                "step 1 must re-run");
        assertEquals(2, toolC.callsByCommand.getOrDefault("{\"command\":\"c\"}", 0),
                "step 2 must re-run");
    }
}
