package fan.summer.fengyu.ai.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Queueing metrics for the background-task scheduler, published through Micrometer
 * (Actuator's {@code /actuator/metrics} locally; an OTLP collector in production via
 * {@code management.otlp.metrics.export.url}). The vocabulary follows the semantics used
 * by mature schedulers — Kubernetes API Priority and Fairness and Temporal's
 * schedule-to-start latency — so per-priority SLOs can be expressed without new labels:
 *
 * <ul>
 *   <li>{@code fengyu.bg.tasks.dispatched} — counter tagged {@code priority}
 *       (APF {@code apiserver_flowcontrol_dispatched_requests_total})</li>
 *   <li>{@code fengyu.bg.tasks.rejected} — counter tagged {@code priority} and
 *       {@code reason} (owner/global/owner-priority/global-priority capacity scope;
 *       APF {@code apiserver_flowcontrol_rejected_requests_total})</li>
 *   <li>{@code fengyu.bg.task.queue.wait} — schedule-to-start timer tagged
 *       {@code priority} and {@code outcome} (executed/cancelled-while-queued;
 *       APF {@code apiserver_flowcontrol_request_wait_duration_seconds} plus
 *       Temporal {@code task_latency_schedule})</li>
 *   <li>{@code fengyu.bg.queue.inqueue} — gauge tagged {@code priority}
 *       (APF {@code apiserver_flowcontrol_current_inqueue_requests})</li>
 *   <li>{@code fengyu.bg.queue.oldest_wait_ms} — gauge tagged {@code priority}; the age
 *       of the oldest queued task, the stuck-queue signal behind the 30s delay alert</li>
 * </ul>
 *
 * A missing registry (unit tests without Actuator) degrades to a no-op.
 */
@Service
public class BackgroundTaskMetrics {

    private final MeterRegistry registry;
    /** Gauges reference their suppliers weakly; hold them for the process lifetime. */
    private final List<Supplier<Number>> gaugeAnchors = new CopyOnWriteArrayList<>();

    @Autowired
    public BackgroundTaskMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider == null ? null : registryProvider.getIfAvailable());
    }

    public BackgroundTaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records an admitted task leaving the queue and starting its body. */
    public void dispatched(String priority, Duration queueWait) {
        if (registry == null) return;
        registry.counter("fengyu.bg.tasks.dispatched", "priority", priority).increment();
        queueWait(priority, "executed", queueWait);
    }

    /** Records a submission rejected by a capacity limit, with the limiting scope. */
    public void rejected(String priority, String reason) {
        if (registry == null) return;
        registry.counter("fengyu.bg.tasks.rejected",
                "priority", priority, "reason", reason).increment();
    }

    /** Records a task cancelled before its body started, crediting its time queued. */
    public void cancelledWhileQueued(String priority, Duration queueWait) {
        if (registry == null) return;
        queueWait(priority, "cancelled", queueWait);
    }

    private void queueWait(String priority, String outcome, Duration queueWait) {
        Timer.builder("fengyu.bg.task.queue.wait")
                .tag("priority", priority)
                .tag("outcome", outcome)
                .description("Background task schedule-to-start queue wait")
                .register(registry)
                .record(queueWait == null ? Duration.ZERO : queueWait);
    }

    /**
     * Binds the live queue-state gauges for one priority. The suppliers are polled on
     * scrape, so they observe the scheduler under its monitor instead of being updated
     * on every enqueue and dequeue.
     */
    public void bindQueueState(String priority, Supplier<Number> inqueue,
                               Supplier<Number> oldestWaitMs) {
        if (registry == null) return;
        gaugeAnchors.add(inqueue);
        gaugeAnchors.add(oldestWaitMs);
        Gauge.builder("fengyu.bg.queue.inqueue", inqueue, value -> value.get().doubleValue())
                .tag("priority", priority)
                .description("Background tasks currently queued")
                .register(registry);
        Gauge.builder("fengyu.bg.queue.oldest_wait_ms",
                        oldestWaitMs, value -> value.get().doubleValue())
                .tag("priority", priority)
                .description("Age of the oldest queued background task")
                .register(registry);
    }
}
