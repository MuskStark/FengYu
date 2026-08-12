package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobsTest {

    @Test
    void asyncJobInheritsRequestLocaleAndClearsCallerState() throws Exception {
        Jobs jobs = new Jobs();
        AtomicReference<String> observed = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        WorkerLocale.set("zh-CN");
        try {
            jobs.start("I18N", handle -> {
                observed.set(WorkerLocale.current());
                finished.countDown();
            });
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals("zh", observed.get());
            assertEquals("zh", WorkerLocale.current(), "the job must not mutate the request thread");
        } finally {
            WorkerLocale.clear();
            jobs.close();
        }
    }

    @Test
    void closeCancelsRunningJobsAndRejectsNewWork() throws Exception {
        Jobs jobs = new Jobs();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        jobs.start("BLOCK", handle -> {
            started.countDown();
            try {
                while (!handle.isCancelled()) Thread.sleep(10);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
            handle.onCancel(cancellations::incrementAndGet);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        jobs.close();
        long deadline = System.currentTimeMillis() + 2_000;
        while (cancellations.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, cancellations.get(), "late cancel hooks must still run exactly once");
        assertThrows(IllegalStateException.class, () -> jobs.start("LATE", handle -> {}));
        jobs.close();
    }

    @Test
    void cursorRemainsAbsoluteAfterOldestLinesAreEvicted() {
        Jobs.Job job = new Jobs.Job("job_test", "TEST");
        for (int i = 0; i < Jobs.Job.MAX_LOG_LINES; i++) job.append("old-" + i);

        int cursor = ((Number) job.snapshot(0).get("cursor")).intValue();
        for (int i = 0; i < 10; i++) job.append("new-" + i);

        Map<String, Object> snapshot = job.snapshot(cursor);
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) snapshot.get("logs");
        assertEquals(10, logs.size(), "an old absolute cursor must map past evicted entries");
        assertEquals("new-0", logs.getFirst());
        assertEquals("new-9", logs.getLast());
        assertEquals(cursor + 10, ((Number) snapshot.get("cursor")).intValue());
    }

    @Test
    void byteCapCountsUtf8BytesNotUtf16Chars() {
        Jobs.Job job = new Jobs.Job("job_utf8", "TEST");
        // U+1F600 is one Unicode code point, two UTF-16 chars, and four UTF-8 wire bytes.
        String oversized = "😀".repeat((Jobs.Job.MAX_LOG_BYTES / 4) + 1);

        job.append(oversized);

        Map<String, Object> snapshot = job.snapshot(0);
        @SuppressWarnings("unchecked")
        List<String> logs = (List<String>) snapshot.get("logs");
        assertTrue(logs.isEmpty(), "one line larger than the byte budget must be evicted");
        assertEquals(1, ((Number) snapshot.get("droppedLogs")).intValue());
        assertEquals(1, ((Number) snapshot.get("cursor")).intValue());
    }

    /**
     * Cancelling a running job wins exactly once: the first {@code cancel(id)} against a still-running
     * job returns true and flips the snapshot to CANCELLED once the runner observes it. The job thread
     * removes its handle in a {@code finally} block, so once teardown completes a further cancel
     * reports not-running (false). This pins the deterministic "first cancel wins, second is a no-op"
     * invariant that the plugin-level cancel test can only assert non-deterministically (a fast job
     * may finish before the first cancel lands).
     */
    @Test
    void cancelRunningJobWinsOnceThenReportsNotRunning() throws Exception {
        Jobs jobs = new Jobs();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelObserved = new CountDownLatch(1);
        try {
            Jobs.Job job = jobs.start("CANC", handle -> {
                started.countDown();
                try {
                    while (!handle.isCancelled()) Thread.sleep(10);
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
                cancelObserved.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertTrue(jobs.cancel(job.id), "first cancel against a running job must succeed");
            assertTrue(cancelObserved.await(2, TimeUnit.SECONDS), "runner must observe cancellation");

            // The handle is torn down in the job thread's finally; spin until cancel reports false.
            long deadline = System.currentTimeMillis() + 2_000;
            while (jobs.cancel(job.id) && System.currentTimeMillis() < deadline) Thread.sleep(5);
            assertFalse(jobs.cancel(job.id), "cancel must report not-running after handle teardown");

            Map<String, Object> snap = jobs.snapshot(job.id, 0);
            assertEquals("CANCELLED", snap.get("status"), "cancelled job must snapshot CANCELLED");
            assertEquals(Boolean.TRUE, snap.get("done"));
        } finally {
            jobs.close();
        }
    }
}
