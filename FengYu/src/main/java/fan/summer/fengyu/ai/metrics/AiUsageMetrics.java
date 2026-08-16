package fan.summer.fengyu.ai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Usage metrics for the AI surfaces, published through Micrometer (Actuator's
 * {@code /actuator/metrics} locally; an OTLP collector in production via
 * {@code management.otlp.metrics.export.url}). The metric vocabulary is deliberately
 * small and stable:
 *
 * <ul>
 *   <li>{@code fengyu.agent.runs} — counter tagged {@code status}
 *       (completed/failed/cancelled)</li>
 *   <li>{@code fengyu.agent.steps} — counter tagged {@code tool} and {@code outcome}
 *       (completed/failed/denied)</li>
 *   <li>{@code fengyu.agent.run.duration} — run wall-clock timer tagged {@code status}</li>
 * </ul>
 *
 * A missing registry (unit tests without Actuator) degrades to a no-op.
 */
@Service
public class AiUsageMetrics {

    private final MeterRegistry registry;
    private final Map<String, Instant> runStarts = new ConcurrentHashMap<>();

    public AiUsageMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
    }

    public void runStarted(String runId) {
        if (registry == null) return;
        runStarts.put(runId, Instant.now());
    }

    public void runFinished(String runId, String status) {
        if (registry == null) return;
        registry.counter("fengyu.agent.runs", "status", status).increment();
        Instant started = runStarts.remove(runId);
        if (started != null) {
            Timer.builder("fengyu.agent.run.duration")
                    .tag("status", status)
                    .description("Agent run wall-clock duration")
                    .register(registry)
                    .record(Duration.between(started, Instant.now()));
        }
    }

    public void stepFinished(String toolName, String outcome) {
        if (registry == null) return;
        String tool = toolName == null ? "unknown" : toolName;
        registry.counter("fengyu.agent.steps", "tool", tool, "outcome", outcome).increment();
    }
}
