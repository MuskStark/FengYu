package fan.summer.fengyu.plugin.offlinepython;

import fan.summer.fengyu.sdk.Jobs;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobsTest {

    @Test
    void statusUsesTheStandardRpcEnvelope() throws Exception {
        Jobs jobs = new Jobs();
        Jobs.Job job = jobs.start("BUILD", handle -> handle.setSummary(Map.of("files", 3)));
        awaitDone(job);

        Map<String, Object> snapshot = job.snapshot(0);

        assertEquals(true, snapshot.get("success"));
        assertEquals("job status", snapshot.get("summary"));
        assertEquals(Map.of("files", 3), snapshot.get("result"));
        assertEquals("DONE", snapshot.get("status"));
    }

    @Test
    void cancelInvokesTheRegisteredHookAndMarksTheJobCancelled() throws Exception {
        Jobs jobs = new Jobs();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Jobs.Job job = jobs.start("DEPLOY", handle -> {
            handle.onCancel(cancelled::countDown);
            running.countDown();
            while (!handle.isCancelled()) Thread.onSpinWait();
            throw new Jobs.CancellationException();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));

        assertTrue(jobs.cancel(job.id));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        awaitDone(job);
        assertEquals("CANCELLED", job.status);
    }

    /**
     * Regression (P1-2): a job's log buffer is dual-bounded (max lines + max bytes) and evicts the
     * OLDEST lines on overflow. A chatty job cannot grow memory without bound; the dropped count is
     * surfaced in the snapshot so a polling client knows lines were skipped.
     */
    @Test
    void jobLogsAreBoundedAndReportDroppedCount() throws Exception {
        Jobs jobs = new Jobs();
        // A job body that logs many more lines than the per-job line cap.
        int over = 5500;  // MAX_LOG_LINES is 5000
        CountDownLatch done = new CountDownLatch(1);
        Jobs.Job job = jobs.start("BUILD", handle -> {
            for (int i = 0; i < over; i++) handle.log("line-" + i);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        awaitDone(job);

        Map<String, Object> snapshot = jobs.snapshot(job.id, 0);
        @SuppressWarnings("unchecked")
        java.util.List<String> logs = (java.util.List<String>) snapshot.get("logs");
        int dropped = (int) snapshot.get("droppedLogs");

        assertTrue(logs.size() <= 5000, "job log buffer must be capped, got " + logs.size());
        assertTrue(dropped >= over - 5000, "droppedLogs must reflect evicted overflow, got " + dropped);
        // The tail keeps the most-recent lines.
        assertEquals("line-" + (over - 1), logs.get(logs.size() - 1), "the newest line must survive");
    }

    private static void awaitDone(Jobs.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ("RUNNING".equals(job.status) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(!"RUNNING".equals(job.status), "job did not finish in time");
    }
}
