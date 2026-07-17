package fan.summer.fengyu.plugin.offlinepython;

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
        Jobs.Job job = jobs.start(Jobs.Type.BUILD, handle -> handle.setSummary(Map.of("files", 3)));
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
        Jobs.Job job = jobs.start(Jobs.Type.DEPLOY, handle -> {
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

    private static void awaitDone(Jobs.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ("RUNNING".equals(job.status) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(!"RUNNING".equals(job.status), "job did not finish in time");
    }
}
