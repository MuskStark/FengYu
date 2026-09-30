package fan.summer.fengyu.ai.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Usage metrics: counters/tags per run status and step outcome; null registry degrades. */
class AiUsageMetricsTest {

    @SuppressWarnings("unchecked")
    private static AiUsageMetrics withRegistry(SimpleMeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AiUsageMetrics(provider);
    }

    @Test
    void countsRunsByStatusAndStepsByToolAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiUsageMetrics metrics = withRegistry(registry);

        metrics.runStarted("run-1");
        metrics.stepFinished("json_format", "completed");
        metrics.stepFinished("excel_execute", "failed");
        metrics.runFinished("run-1", "completed");

        assertEquals(1.0, registry.get("fengyu.agent.runs").tag("status", "completed").counter().count());
        assertEquals(1.0, registry.get("fengyu.agent.steps")
                .tag("tool", "json_format").tag("outcome", "completed").counter().count());
        assertEquals(1.0, registry.get("fengyu.agent.steps")
                .tag("tool", "excel_execute").tag("outcome", "failed").counter().count());
        assertEquals(1, registry.find("fengyu.agent.run.duration")
                .tag("status", "completed").timers().size());
        // Unknown run id still counts, just without a duration.
        metrics.runFinished("ghost", "failed");
        assertEquals(1.0, registry.get("fengyu.agent.runs").tag("status", "failed").counter().count());
    }

    @Test
    void missingRegistryIsANoOp() {
        AiUsageMetrics metrics = new AiUsageMetrics(null);
        assertDoesNotThrow(() -> {
            metrics.runStarted("r");
            metrics.stepFinished("t", "completed");
            metrics.runFinished("r", "completed");
        });
    }

    /**
     * The {@code tool} tag collapses to a bounded vocabulary (audit P3 "基数不受控"):
     * builtins keep their wire name; MCP wire names ({@code <server>__<tool>}) collapse to
     * {@code mcp}; workflow run-tools collapse to {@code workflow}; unknown blanks become
     * {@code unknown}. With no registry attached these structural rules are the whole policy.
     */
    @Test
    void toolTagsCollapseToABoundedVocabulary() {
        AiUsageMetrics metrics = withRegistry(new SimpleMeterRegistry());

        assertEquals("json_format", metrics.normalizedToolTag("json_format"));
        assertEquals("mcp", metrics.normalizedToolTag("myserver__echo"));
        assertEquals("mcp", metrics.normalizedToolTag("someone_configured_this__tool"));
        assertEquals("workflow", metrics.normalizedToolTag("run_workflow_0192ab3d"));
        assertEquals("unknown", metrics.normalizedToolTag(null));
        assertEquals("unknown", metrics.normalizedToolTag("  "));

        // The counter path uses the same normalization end to end.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiUsageMetrics counting = withRegistry(registry);
        counting.stepFinished("alpha__echo", "completed");
        counting.stepFinished("beta__echo", "failed");
        assertEquals(1.0, registry.get("fengyu.agent.steps")
                .tag("tool", "mcp").tag("outcome", "completed").counter().count());
        assertEquals(1.0, registry.get("fengyu.agent.steps")
                .tag("tool", "mcp").tag("outcome", "failed").counter().count());
    }
}
