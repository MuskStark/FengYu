package fan.summer.fengyu.ai.metrics;

import fan.summer.fengyu.ai.config.AiToolRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>The {@code tool} tag is normalized to a bounded vocabulary so its cardinality cannot
 * grow without bound: builtin tools keep their wire name, plugin tools collapse to
 * {@code plugin:<id>}, MCP tools (whose names embed user-configured server prefixes) to
 * {@code mcp}, and workflow tools to {@code workflow}. The owner map is resolved from
 * {@link AiToolRegistry} and cached briefly.</p>
 *
 * A missing registry (unit tests without Actuator) degrades to a no-op.
 */
@Service
public class AiUsageMetrics {

    /** How often the wire-name → owner-tag map is refreshed from the live registry. */
    private static final Duration OWNER_TAG_REFRESH = Duration.ofSeconds(60);

    private final MeterRegistry registry;
    private final ObjectProvider<AiToolRegistry> toolRegistry;
    private final Map<String, Instant> runStarts = new ConcurrentHashMap<>();
    /** Wire-name → bounded owner tag; refreshed at most every OWNER_TAG_REFRESH. */
    private volatile Map<String, String> ownerTags = Map.of();
    private volatile boolean ownerTagsLoaded = false;
    private volatile long ownerTagsLoadedAtNanos;

    public AiUsageMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider, null);
    }

    @Autowired
    public AiUsageMetrics(ObjectProvider<MeterRegistry> registryProvider,
                          ObjectProvider<AiToolRegistry> toolRegistry) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        this.toolRegistry = toolRegistry;
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
        registry.counter("fengyu.agent.steps", "tool", normalizedToolTag(toolName), "outcome", outcome).increment();
    }

    /**
     * Collapses a tool wire name to the bounded tag vocabulary. The registry map is
     * authoritative when available; structural fallbacks (MCP's {@code <server>__<tool>}
     * naming, the {@code run_workflow_<id>} prefix) keep the tag bounded without it.
     */
    String normalizedToolTag(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        String name = toolName.trim();
        String owner = ownerTags().get(name);
        if (owner != null) return owner;
        if (name.contains("__")) return "mcp";
        if (name.startsWith("run_workflow_")) return "workflow";
        return name;
    }

    private Map<String, String> ownerTags() {
        if (toolRegistry == null) return Map.of();
        long now = System.nanoTime();
        Map<String, String> cached = ownerTags;
        if (ownerTagsLoaded && now - ownerTagsLoadedAtNanos < OWNER_TAG_REFRESH.toNanos()) {
            return cached;
        }
        AiToolRegistry live = toolRegistry.getIfAvailable();
        if (live == null) {
            ownerTagsLoaded = true; // retry no sooner than the refresh interval
            ownerTagsLoadedAtNanos = now;
            return cached;
        }
        try {
            Map<String, String> refreshed = live.toolOwnerTags();
            ownerTags = refreshed;
            ownerTagsLoadedAtNanos = now;
            ownerTagsLoaded = true;
            return refreshed;
        } catch (Exception unavailable) {
            // Registry not resolvable right now (mid-refresh, plugin scan failure):
            // fall back to the structural rules rather than failing the metric.
            ownerTagsLoadedAtNanos = now;
            ownerTagsLoaded = true;
            return cached;
        }
    }
}
