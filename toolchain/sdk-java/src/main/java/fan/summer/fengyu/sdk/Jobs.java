package fan.summer.fengyu.sdk;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-worker registry for long-running operations that would otherwise exceed the host's per-RPC
 * timeout. A {@code start*} handler launches the work on a virtual thread, returns a {@code jobId}
 * immediately, and the UI polls {@code *.status} with a cursor to drain streamed logs.
 *
 * <p>The host ({@code PluginProcessManager}) kills any single JSON-RPC call after the declared
 * timeout (default 60s, max 600s), so the launching call must return fast. State lives in this
 * registry keyed by job id.
 *
 * <p><b>Bounded retention.</b> Completed jobs are retained for {@link #ttlMillis} (default 30 min)
 * and evicted opportunistically on {@link #snapshot(String, int)} / {@link #cancel(String)} access.
 * Additionally, the registry caps the number of retained jobs at {@link #maxRetained} (default 200);
 * when exceeded the oldest completed jobs are dropped first. This prevents unbounded memory growth
 * in workers that fan out many jobs (e.g. an Excel splitter processing many workbooks).
 *
 * <p><b>Generic {@code type}.</b> The caller picks the type label as a free-form string
 * (e.g. {@code "BUILD"}, {@code "SPLIT"}, {@code "DEPLOY"}); it is surfaced verbatim in the
 * status snapshot and used only for the virtual-thread name and diagnostics.
 */
public final class Jobs {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Jobs.class);

    /** Default completed-job retention: 30 minutes. */
    public static final long DEFAULT_TTL_MILLIS = 30L * 60 * 1000;
    /** Default cap on retained jobs (running + completed). */
    public static final int DEFAULT_MAX_RETAINED = 200;

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cancellable> handles = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxRetained;

    public Jobs() { this(DEFAULT_TTL_MILLIS, DEFAULT_MAX_RETAINED); }

    /**
     * Configure retention. Useful in tests that want deterministic eviction; production callers
     * should normally use the no-arg constructor.
     */
    public Jobs(long ttlMillis, int maxRetained) {
        this.ttlMillis = ttlMillis;
        this.maxRetained = maxRetained;
    }

    /** A job body that may throw checked exceptions (caught by the virtual-thread wrapper). */
    @FunctionalInterface
    public interface ThrowingRunner {
        void run(Cancellable handle) throws Exception;
    }

    /** Start a job. {@code runner} executes on a virtual thread; the returned handle drives cancellation. */
    public Job start(String type, ThrowingRunner runner) {
        evictExpired();
        String id = "job_" + UUID.randomUUID();
        Job job = new Job(id, type);
        jobs.put(id, job);
        Cancellable handle = new Cancellable(job);
        handles.put(id, handle);
        Thread.ofVirtual().name("fy-job-" + safeThreadToken(type) + "-" + id).start(() -> {
            try {
                runner.run(handle);
                if (handle.isCancelled()) job.markCancelled();
                else job.markDone();
            } catch (CancellationException e) {
                job.markCancelled();
            } catch (Throwable t) {
                if (handle.isCancelled()) {
                    job.markCancelled();
                } else {
                    // P1-2: preserve the full stack trace in the worker's log BEFORE flattening the
                    // message onto the job. markFailed stores only a one-line message; without this
                    // the stack (the only path to the root cause) is lost. Individual plugin bodies
                    // are still encouraged to catch+log+rethrow themselves (see ExcelRpcHandlers),
                    // but this guarantees diagnostics even when they forget.
                    log.warn("{} job {} failed: {}", type, id, t.getClass().getSimpleName(), t);
                    job.markFailed(safeMessage(t));
                }
            } finally {
                handles.remove(id, handle);
                evictExpired();
            }
        });
        return job;
    }

    public Job get(String id) {
        evictExpired();
        return jobs.get(id);
    }

    /** Snapshot the status of {@code id}; {@code null} when unknown (or evicted by TTL). */
    public Map<String, Object> snapshot(String id, int cursor) {
        Job job = jobs.get(id);
        if (job == null) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("success", false);
            missing.put("summary", "job not found (unknown id or evicted): " + id);
            missing.put("jobId", id);
            missing.put("done", true);
            return missing;
        }
        return job.snapshot(cursor);
    }

    public boolean cancel(String id) {
        evictExpired();
        Cancellable handle = handles.get(id);
        if (handle == null) return false;
        handle.cancel();
        return true;
    }

    /**
     * Drop completed jobs older than {@link #ttlMillis}. Also enforce {@link #maxRetained} by
     * removing the oldest completed jobs first. Running jobs are never evicted by the cap.
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        // 1. TTL sweep.
        for (Job job : jobs.values()) {
            if (job.isTerminal() && (now - job.startedAt) > ttlMillis) {
                jobs.remove(job.id, job);
            }
        }
        // 2. Cap sweep: if still over the limit, drop oldest completed jobs first.
        int completed = 0, running = 0;
        Job oldestCompleted = null;
        for (Job job : jobs.values()) {
            if (job.isTerminal()) {
                completed++;
                if (oldestCompleted == null || job.startedAt < oldestCompleted.startedAt) oldestCompleted = job;
            } else running++;
        }
        // Allow up to maxRetained total; only evict completed overshoot, never running jobs.
        while ((running + completed) > maxRetained && completed > 0 && oldestCompleted != null) {
            jobs.remove(oldestCompleted.id, oldestCompleted);
            completed--;
            oldestCompleted = null;
            for (Job job : jobs.values()) {
                if (job.isTerminal() && (oldestCompleted == null || job.startedAt < oldestCompleted.startedAt)) {
                    oldestCompleted = job;
                }
            }
        }
    }

    /** A running job and its streamed log lines. */
    public static final class Job {
        /** Per-job cap on retained log lines (P1-2): a chatty job cannot grow this without bound. */
        public static final int MAX_LOG_LINES = 5000;
        /** Per-job cap on retained log bytes (P1-2): the tail keeps the most recent ~2 MiB of lines. */
        public static final int MAX_LOG_BYTES = 2 * 1024 * 1024;

        public final String id;
        public final String type;
        public final long startedAt = System.currentTimeMillis();
        /** One of RUNNING / DONE / FAILED / CANCELLED. */
        public volatile String status = "RUNNING";
        private final Deque<String> logs = new ArrayDeque<>();
        /** UTF-8 bytes retained in {@link #logs}; guarded by {@code synchronized (logs)}. */
        private int logBytes;
        /** Absolute number of evicted lines; also the cursor of the first retained line. */
        private int droppedLogs;
        /** Summary produced on success (e.g. a BuildSummary serialised to a Map). */
        public volatile Object summary;
        /** Failure detail (status=FAILED only). */
        public volatile String error;

        Job(String id, String type) { this.id = id; this.type = type; }

        public void append(String line) {
            if (line == null) return;
            synchronized (logs) {
                logs.addLast(line);
                logBytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                // Dual-bound eviction (P1-2): drop the OLDEST lines until both the line count and the
                // byte budget are within their caps. This keeps the most recent diagnostics (the tail
                // is what a developer triaging a failure needs) and bounds memory for a chatty job.
                while ((logs.size() > MAX_LOG_LINES || logBytes > MAX_LOG_BYTES) && !logs.isEmpty()) {
                    String removed = logs.pollFirst();
                    if (removed != null) {
                        logBytes -= removed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        droppedLogs++;
                    }
                }
            }
        }
        void markDone() { status = "DONE"; }
        void markFailed(String message) { status = "FAILED"; this.error = message; }
        void markCancelled() { status = "CANCELLED"; }
        boolean isTerminal() { return !"RUNNING".equals(status); }

        /**
         * Snapshot the log tail starting at an absolute {@code cursor}. A cursor returned by an
         * earlier snapshot remains valid after oldest-line eviction: it is translated relative to
         * {@link #droppedLogs}, rather than being reused as an index into the shorter retained deque.
         */
        public Map<String, Object> snapshot(int cursor) {
            List<String> all;
            int dropped;
            synchronized (logs) {
                all = new ArrayList<>(logs);
                dropped = droppedLogs;
            }
            long relative = (long) Math.max(0, cursor) - dropped;
            int from = (int) Math.max(0L, Math.min(relative, all.size()));
            List<String> tail = new ArrayList<>(all.subList(from, all.size()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "job status");
            out.put("jobId", id);
            out.put("type", type);
            out.put("status", status);
            out.put("logs", tail);
            // Absolute line cursor: dropped count + current size, so a client that last polled at the
            // tail does not re-read the same lines after an overflow eviction.
            out.put("cursor", dropped + all.size());
            out.put("droppedLogs", dropped);
            out.put("done", isTerminal());
            if (summary != null) out.put("result", summary);
            if (error != null) out.put("error", error);
            out.put("elapsedMs", System.currentTimeMillis() - startedAt);
            return out;
        }
    }

    /** Handle handed to a running job: the job logs via {@code log()}, the host cancels via {@code cancel()}. */
    public static final class Cancellable {
        private final Job job;
        private volatile boolean cancelled;
        private volatile Runnable cancelHook;

        Cancellable(Job job) { this.job = job; }

        /** Append a streamed log line (subprocess stdout etc.). */
        public void log(String line) {
            if (!cancelled) job.append(line);
        }

        public boolean isCancelled() { return cancelled; }

        /** Register a cancel hook (e.g. {@code pool::shutdownNow}); invoked once on cancel. */
        public void onCancel(Runnable hook) { this.cancelHook = hook; }

        /** Publish the operation's summary onto the job (for status polling). */
        public void setSummary(Object summary) { job.summary = summary; }

        /** Flip the cancel flag and fire the registered hook (idempotent). */
        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            Runnable h = cancelHook;
            if (h != null) {
                try { h.run(); } catch (Exception ignored) { /* best-effort */ }
            }
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m.replace('\r', ' ').replace('\n', ' ');
    }

    /** Sanitise {@code type} for use as a virtual-thread name token (lowercase, alnum only). */
    private static String safeThreadToken(String type) {
        String t = type == null ? "job" : type.toLowerCase();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < t.length() && out.length() < 24; i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.isEmpty() ? "job" : out.toString();
    }

    /** Thrown by a job body to signal cooperative cancellation (not an error). */
    public static final class CancellationException extends RuntimeException {
        public CancellationException() { super("cancelled"); }
    }
}
