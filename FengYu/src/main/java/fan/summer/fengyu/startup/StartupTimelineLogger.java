package fan.summer.fengyu.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline.TimelineEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Startup phase timing (P2): dumps the slowest recorded Spring startup steps once the
 * application is ready, so "which bean delays boot" is answered from data instead of
 * guesswork — the input for targeted {@code @Lazy} decisions.
 *
 * <p>Registered programmatically by {@code HeadlessLauncher.runSpring} next to the
 * {@link BufferingApplicationStartup} it reads (both SETUP and APP contexts). Bean-init
 * steps, auto-configuration, and environment preparation all appear; the names are the
 * Spring core-metrics step names ({@code spring.beans.instantiate}, {@code spring.context.beans.smart-initialize}, …)
 * with the bean class in the step tags.</p>
 */
public final class StartupTimelineLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupTimelineLogger.class);
    /** Boot's own actuator default buffer; a full APP context fits comfortably. */
    public static final int DEFAULT_CAPACITY = 4096;
    /** How many slow steps make the report — enough to spot offenders, short enough to read in a log. */
    private static final int TOP_SLOWEST = 12;

    private final BufferingApplicationStartup buffering;
    private final long bootStartedMillis;

    public StartupTimelineLogger(BufferingApplicationStartup buffering, long bootStartedMillis) {
        this.buffering = buffering;
        this.bootStartedMillis = bootStartedMillis;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            List<TimelineEvent> events = new ArrayList<>(buffering.getBufferedTimeline().getEvents());
            events.sort(Comparator.comparing(TimelineEvent::getDuration).reversed());
            StringBuilder report = new StringBuilder();
            for (int i = 0; i < Math.min(events.size(), TOP_SLOWEST); i++) {
                TimelineEvent step = events.get(i);
                report.append(String.format(Locale.ROOT, "%n  %8.1f ms  %s%s",
                        step.getDuration().toNanos() / 1_000_000.0,
                        step.getStartupStep().getName(),
                        describeTags(step.getStartupStep())));
            }
            log.info("Startup timing: {} recorded steps, launcher-to-ready {} ms. Slowest steps:{}",
                    events.size(), System.currentTimeMillis() - bootStartedMillis, report);
        } catch (Exception failure) {
            // Diagnostics must never be able to fail the boot that just succeeded.
            log.debug("Cannot report startup timeline", failure);
        }
    }

    /**
     * Compact tag summary — {@code spring.beans.instantiate} steps carry the bean name as a
     * {@code beanName} tag, which is exactly what turns "something took 1 s" into an
     * actionable lazy-init target. Empty tags stay invisible.
     */
    private static String describeTags(org.springframework.core.metrics.StartupStep step) {
        StringBuilder tags = new StringBuilder();
        for (org.springframework.core.metrics.StartupStep.Tag tag : step.getTags()) {
            if (tags.length() > 0) tags.append(' ');
            tags.append(tag.getKey()).append('=').append(tag.getValue());
        }
        return tags.isEmpty() ? "" : " (" + tags + ")";
    }
}
