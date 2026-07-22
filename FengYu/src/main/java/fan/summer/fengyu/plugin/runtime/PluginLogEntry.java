package fan.summer.fengyu.plugin.runtime;

import java.time.Instant;

/**
 * One captured plugin worker log line. {@code level} is best-effort — parsed from the worker's
 * log format when possible (see {@link PluginLogLineParser}), defaulting to {@code INFO}. This
 * record is serialized to JSON by the REST/SSE log endpoints, so its shape is part of the API.
 *
 * <p>{@code sequence} is a monotonic, store-wide ordering key assigned at append time. The SSE log
 * stream uses it to avoid delivering the same entry twice: a newly-connected subscriber replays
 * history up to a high-water mark, then the live path delivers only entries with a strictly greater
 * sequence.
 */
public record PluginLogEntry(Instant timestamp, String level, String message, long sequence) {
    /** The level marker we attach when a line carries no parseable level. */
    public static final String DEFAULT_LEVEL = "INFO";
}
