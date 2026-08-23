package fan.summer.fengyu.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Output-binding materialization tests (implementation plan §7.3/§12.4): a step's
 * {@code outputBindings} must copy effective inputs / projected results into a COPY
 * of the worker result, never overwrite a real field, never pass through a
 * sensitive input, and apply identically to pinned and executed steps.
 */
class AgentRunnerOutputBindingsTest {

    private static AgentStep boundStep(int index, String toolName, Map<String, Object> args,
                                       AgentStep.OutputBinding... bindings) {
        return new AgentStep(index, toolName, args, "step " + index, false,
                List.of(), null, List.of(), null, List.of(bindings));
    }

    @Test
    void inputPassthroughMaterializesResolvedArgumentIntoResultCopy() {
        AgentStep step = boundStep(0, "register",
                Map.of("filePath", "/data/report.xlsx"),
                new AgentStep.OutputBinding("sourceFile", "input", "filePath"));
        String effective = AgentRunner.materializeOutputs(step,
                "{\"success\":true,\"summary\":\"registered\"}");
        assertTrue(effective.contains("\"sourceFile\":\"/data/report.xlsx\""));
        assertTrue(effective.contains("\"success\":true"));
        // The binding must not collide with the real fields.
        assertEquals(1, countOccurrences(effective, "\"sourceFile\""));
    }

    @Test
    void resultProjectionLiftsNestedFieldToTopLevel() {
        AgentStep step = boundStep(0, "split",
                Map.of(),
                new AgentStep.OutputBinding("outDir", "result", "output.dir"));
        String effective = AgentRunner.materializeOutputs(step,
                "{\"success\":true,\"output\":{\"dir\":\"/out/a1\"}}");
        assertTrue(effective.contains("\"outDir\":\"/out/a1\""));
    }

    @Test
    void collisionWithRealResultFieldFailsInsteadOfOverwriting() {
        AgentStep step = boundStep(0, "tool", Map.of("filePath", "x"),
                new AgentStep.OutputBinding("success", "input", "filePath"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}"));
        assertTrue(error.getMessage().contains("collides"));
    }

    @Test
    void sensitiveInputNameIsDefensivelyRejected() {
        AgentStep step = boundStep(0, "mail", Map.of("password", "hunter2"),
                new AgentStep.OutputBinding("leaked", "input", "password"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}"));
        assertTrue(error.getMessage().contains("sensitive"));
    }

    @Test
    void nestedSensitiveSegmentIsDefensivelyRejected() {
        // The lint floor must cover EVERY named segment: smtp.password leaks even
        // though the root object `smtp` is innocuous.
        java.util.Map<String, Object> smtp = new java.util.HashMap<>();
        smtp.put("password", "hunter2");
        AgentStep step = boundStep(0, "mail", Map.of("smtp", smtp),
                new AgentStep.OutputBinding("leaked", "input", "smtp.password"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}"));
        assertTrue(error.getMessage().contains("sensitive"));
    }

    @Test
    void missingPathFailsLoudly() {
        AgentStep step = boundStep(0, "tool", Map.of("filePath", "x"),
                new AgentStep.OutputBinding("missing", "input", "absent.field"));
        assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}"));
    }

    @Test
    void nonObjectResultIsAContractError() {
        AgentStep step = boundStep(0, "tool", Map.of("filePath", "x"),
                new AgentStep.OutputBinding("sourceFile", "input", "filePath"));
        assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "[1,2,3]"));
    }

    @Test
    void nonJsonTextResultCarriesTheContractErrorMessage() {
        AgentStep step = boundStep(0, "tool", Map.of("filePath", "x"),
                new AgentStep.OutputBinding("sourceFile", "input", "filePath"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "plain text, not JSON"));
        assertTrue(error.getMessage().contains("not a JSON object"), error.getMessage());
    }

    @Test
    void schemaAwareRuleHonorsTheExplicitSensitiveFalseOptOut() {
        // apiToken lint-matches but the tool schema explicitly exempts it — the CLI's
        // rule allows this binding, so the runtime must too (no build-pass/run-fail gap).
        String schema = "{\"type\":\"object\",\"properties\":{\"apiToken\":"
                + "{\"type\":\"string\",\"x-fengyu-sensitive\":false}}}";
        AgentStep step = boundStep(0, "tool", Map.of("apiToken", "tok"),
                new AgentStep.OutputBinding("api", "input", "apiToken"));
        String effective = AgentRunner.materializeOutputs(step, "{\"success\":true}", schema);
        assertTrue(effective.contains("\"api\":\"tok\""));
        // Without the schema the strict floor still rejects the same binding.
        assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}"));
    }

    @Test
    void schemaAwareRuleBlocksMarkedAndNestedSensitiveFields() {
        String schema = "{\"type\":\"object\",\"properties\":{"
                + "\"smtp\":{\"type\":\"object\",\"properties\":{"
                + "\"password\":{\"type\":\"string\",\"x-fengyu-sensitive\":true}}},"
                + "\"files\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"apiKey\":{\"type\":\"string\",\"x-fengyu-sensitive\":true}}}}}}";
        AgentStep nested = boundStep(0, "tool", java.util.Map.of("smtp", java.util.Map.of("password", "x")),
                new AgentStep.OutputBinding("leak", "input", "smtp.password"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(nested, "{\"success\":true}", schema));
        assertTrue(error.getMessage().contains("sensitive"));
        AgentStep arrayHop = boundStep(0, "tool",
                java.util.Map.of("files", java.util.List.of(java.util.Map.of("apiKey", "k"))),
                new AgentStep.OutputBinding("leak", "input", "files[0].apiKey"));
        assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(arrayHop, "{\"success\":true}", schema));
    }

    @Test
    void schemaAwareRuleRejectsUnresolvablePathsLikeTheBuildDoes() {
        String schema = "{\"type\":\"object\",\"properties\":{\"filePath\":{\"type\":\"string\"}}}";
        AgentStep step = boundStep(0, "tool", Map.of("filePath", "x"),
                new AgentStep.OutputBinding("ghost", "input", "absent.field"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AgentRunner.materializeOutputs(step, "{\"success\":true}", schema));
        assertTrue(error.getMessage().contains("does not resolve"), error.getMessage());
    }

    @Test
    void noBindingsLeavesResultByteIdentical() {
        AgentStep step = new AgentStep(0, "tool", Map.of("filePath", "x"), "s", false);
        assertEquals("{\"success\":true}", AgentRunner.materializeOutputs(step, "{\"success\":true}"));
    }

    @Test
    void pinnedResultMaterializesThroughTheSameFunction() throws Exception {
        // A two-step run: step 0 is PINNED and carries an input binding; step 1
        // references the materialized passthrough output. If materialization were
        // skipped for pinned steps, step 1's template would fail to resolve.
        AgentStep pinned = new AgentStep(0, "echo", Map.of("filePath", "/docs/a.md"), "pinned",
                false, List.of(), "{\"success\":true,\"summary\":\"pinned\"}", List.of(), null,
                List.of(new AgentStep.OutputBinding("sourceFile", "input", "filePath")));
        AgentStep consumer = new AgentStep(1, "echo",
                Map.of("text", "{{steps.0.result.sourceFile}}"), "consumer", false, List.of(0));

        AgentRunnerTest.RecordingSink sink = new AgentRunnerTest.RecordingSink();
        // The tool's inputSchema declares filePath so the schema-aware sensitive screen
        // resolves the passthrough binding exactly like a real built manifest would.
        org.springframework.ai.tool.ToolCallback fileEcho = new AgentRunnerTest.EchoToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name("echo").description("echo")
                        .inputSchema("{\"type\":\"object\",\"properties\":"
                                + "{\"filePath\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}}")
                        .build();
            }
        };
        AgentRunner runner = new AgentRunner(List.of(fileEcho),
                (goal, tools, tokenSink) -> new AgentPlan("g", List.of(pinned, consumer), ""),
                (step, tools) -> {
                    // Echo back the argument the runner resolved — proves the reference
                    // resolved to the MATERIALIZED passthrough value, not the raw template.
                    return "{\"echo\":" + fan.summer.fengyu.ai.util.JsonHelper.toJson(step.args()) + "}";
                });
        AgentRun run = new AgentRun("run-bindings", "g", new AgentRunConfig(false, false, false, 0));
        runner.run(run, sink);
        assertTrue(sink.awaitDone(), "run must terminate");
        assertEquals("onComplete", sink.events.get(sink.events.size() - 1));
        String consumerResult = run.getExecutions().stream()
                .filter(execution -> execution.index() == 1 && execution.result() != null)
                .map(StepExecution::result).findFirst().orElse("");
        assertTrue(consumerResult.contains("/docs/a.md"),
                "downstream must see the materialized passthrough: " + consumerResult);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
