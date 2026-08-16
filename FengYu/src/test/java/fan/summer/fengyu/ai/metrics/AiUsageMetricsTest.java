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
}
