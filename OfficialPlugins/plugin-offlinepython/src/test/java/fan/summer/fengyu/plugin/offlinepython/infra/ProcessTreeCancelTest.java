package fan.summer.fengyu.plugin.offlinepython.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves a domain {@code build.cancel}/{@code deploy.cancel} reaps the whole pip subprocess tree,
 * not just the immediate pip process. A build/deploy spawns pip, which spawns its own children
 * (download workers, installer subprocesses); {@link ProcessRunner#cancel()} destroys the tracked
 * process plus every live descendant so no orphan python/pip/temp-download processes survive a
 * cancel.
 *
 * <p>The tree is materialized with a portable shell recipe — {@code sh -c "sleep 60 & wait"} —
 * where {@code sh} is the tracked ProcessRunner process and {@code sleep} is its backgrounded
 * child (a descendant). After {@code cancel()} BOTH must be dead. The {@code sh}/{@code sleep}
 * pair exists on macOS/Linux (the supported worker hosts); the test is gated to those OSes.
 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class ProcessTreeCancelTest {

    @Test
    void cancelReapsTheProcessAndAllDescendants() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        AtomicInteger exitCode = new AtomicInteger(-999);
        CountDownLatch finished = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                exitCode.set(runner.run(List.of("sh", "-c", "sleep 60 & wait"), line -> {}));
            } catch (Exception ignored) {
                // cancel interrupts the read loop; an exception here is expected and benign.
            } finally {
                finished.countDown();
            }
        }, "opb-tree-kill-worker");
        worker.setDaemon(true);
        worker.start();

        // Wait for the tracked sh process to start, then for its sleep descendant to appear.
        ProcessHandle sh = waitForHandle(runner);
        assertTrue(sh.isAlive(), "sh must be running before cancel");
        ProcessHandle sleep = waitForDescendant(sh);
        assertTrue(sleep.isAlive(), "sleep child must be running before cancel");

        // Domain cancel: reaps the whole tree (sh + sleep, and in production pip + its children).
        runner.cancel();
        assertTrue(finished.await(5, TimeUnit.SECONDS), "run() must return after cancel");

        // Give the OS a brief moment to finalize process teardown, then assert nothing survived.
        Thread.sleep(200);
        assertEquals(-1, exitCode.get(), "a cancelled run must report the destroyed exit code");
        assertFalse(sh.isAlive(), "the tracked process must be reaped");
        assertFalse(sleep.isAlive(), "the descendant must be reaped — no orphan processes survive");
        // Belt-and-braces: sh must have NO live descendants left at all.
        assertEquals(0, sh.descendants().filter(ProcessHandle::isAlive).count(),
                "no live descendants may remain after a tree cancel");
    }

    private static ProcessHandle waitForHandle(ProcessRunner runner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ProcessHandle h = runner.handle();
            if (h != null) return h;
            Thread.sleep(10);
        }
        throw new IllegalStateException("ProcessRunner never started its process within 5s");
    }

    private static ProcessHandle waitForDescendant(ProcessHandle parent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            ProcessHandle[] live = parent.descendants().filter(ProcessHandle::isAlive).toArray(ProcessHandle[]::new);
            if (live.length > 0) return live[0];
            Thread.sleep(10);
        }
        throw new IllegalStateException("sh never spawned its sleep child within 5s");
    }
}
