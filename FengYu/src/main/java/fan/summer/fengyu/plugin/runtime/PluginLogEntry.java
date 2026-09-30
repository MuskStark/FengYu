package fan.summer.fengyu.plugin.runtime;

import java.time.Instant;

/**
 * One captured plugin worker log event. {@code level} is best-effort — parsed from the worker's
 * log format when possible (see {@link PluginLogLineParser}), defaulting to {@code INFO}.
 * Structured SDK events also retain their logger and thread; legacy stderr leaves those fields
 * {@code null}. This record is serialized to JSON by the REST/SSE log endpoints.
 *
 * <p>{@code sequence} is a monotonic, store-wide ordering key assigned at append time. The SSE log
 * stream uses it to avoid delivering the same entry twice: a newly-connected subscriber replays
 * history up to a high-water mark, then the live path delivers only entries with a strictly greater
 * sequence.
 */
public record PluginLogEntry(Instant timestamp, String level, String logger, String thread,
        String message, long sequence) {
    /** The level marker we attach when a line carries no parseable level. */
    public static final String DEFAULT_LEVEL = "INFO";
}
