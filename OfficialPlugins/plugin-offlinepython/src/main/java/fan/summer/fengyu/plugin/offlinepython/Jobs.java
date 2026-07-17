package fan.summer.fengyu.plugin.offlinepython;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-worker registry for long-running operations (build / deploy) that would otherwise exceed the
 * host's per-RPC timeout. A {@code start*} handler launches the work on a virtual thread, returns a
 * {@code jobId} immediately, and the UI polls {@code *.status} with a cursor to drain streamed logs.
 *
 * <p>The host ({@code PluginProcessManager}) kills any single JSON-RPC call after ~60s, so the
 * launching call must return fast. State lives in this registry keyed by job id.
 */
public final class Jobs {

    public enum Type { BUILD, DEPLOY }

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cancellable> handles = new ConcurrentHashMap<>();

    /** A job body that may throw checked exceptions (caught by the virtual-thread wrapper). */
    @FunctionalInterface
    public interface ThrowingRunner {
        void run(Cancellable handle) throws Exception;
    }

    /** Start a job. {@code runner} executes on a virtual thread; {@code cancel} cancels it. */
    public Job start(Type type, ThrowingRunner runner) {
        String id = "job_" + UUID.randomUUID();
        Job job = new Job(id, type);
        jobs.put(id, job);
        Cancellable handle = new Cancellable(job);
        handles.put(id, handle);
        Thread.ofVirtual().name("opb-" + type.name().toLowerCase() + "-" + id).start(() -> {
            try {
                runner.run(handle);
                if (handle.isCancelled()) job.markCancelled();
                else job.markDone();
            } catch (CancellationException e) {
                job.markCancelled();
            } catch (Throwable t) {
                if (handle.isCancelled()) job.markCancelled();
                else job.markFailed(safeMessage(t));
            } finally {
                handles.remove(id, handle);
            }
        });
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public boolean cancel(String id) {
        Cancellable handle = handles.get(id);
        if (handle == null) return false;
        handle.cancel();
        return true;
    }

    /** A running job and its streamed log lines. */
    public static final class Job {
        public final String id;
        public final Type type;
        public final long startedAt = System.currentTimeMillis();
        /** One of RUNNING / DONE / FAILED / CANCELLED. */
        public volatile String status = "RUNNING";
        private final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        /** Summary produced on success (e.g. a BuildSummary serialised to a Map). */
        public volatile Object summary;
        /** Failure detail (status=FAILED only). */
        public volatile String error;

        Job(String id, Type type) { this.id = id; this.type = type; }

        public void append(String line) { logs.add(line); }
        void markDone() { status = "DONE"; }
        void markFailed(String message) { status = "FAILED"; this.error = message; }
        void markCancelled() { status = "CANCELLED"; }

        /** Snapshot the log tail starting at {@code cursor} (0-based line index). */
        public Map<String, Object> snapshot(int cursor) {
            List<String> all = new ArrayList<>(logs);
            int from = Math.max(0, Math.min(cursor, all.size()));
            List<String> tail = new ArrayList<>(all.subList(from, all.size()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", true);
            out.put("summary", "job status");
            out.put("jobId", id);
            out.put("type", type.name());
            out.put("status", status);
            out.put("logs", tail);
            out.put("cursor", all.size());     // next poll's starting cursor
            out.put("done", !"RUNNING".equals(status));
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

        /** Register a cancel hook (e.g. {@code ProcessRunner::cancel}); invoked once on cancel. */
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

    /** Thrown by a job body to signal cooperative cancellation (not an error). */
    public static final class CancellationException extends RuntimeException {
        public CancellationException() { super("cancelled"); }
    }
}
