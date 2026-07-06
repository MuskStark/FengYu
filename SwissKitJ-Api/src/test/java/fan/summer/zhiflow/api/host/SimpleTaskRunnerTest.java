package fan.summer.zhiflow.api.host;

import fan.summer.zhiflow.api.SwissKitJPlugin;
import fan.summer.zhiflow.api.ToolCategory;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimpleTaskRunnerTest {

    /** 同步 executor:回调直接在提交线程执行,测试无需 FX Toolkit。 */
    private static final java.util.concurrent.Executor DIRECT = Runnable::run;

    private static SwissKitJPlugin stubPlugin() {
        return new SwissKitJPlugin() {
            public String getId() { return "test.plugin"; }
            public String getName() { return "Test"; }
            public String getDescription() { return ""; }
            public ToolCategory getCategory() { return ToolCategory.OTHER; }
            public String getVersion() { return "0"; }
            public String getMdiIcon() { return "star"; }
            public Node createView() { return null; }
        };
    }

    @Test
    void countsRunningTasksAndDecrementsWhenDone() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        runner.submit("t", () -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return null;
        }, r -> done.countDown(), e -> done.countDown());

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, runner.runningCount());
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // 实现契约:计数先结清、回调后派发 —— done 触发时必然已归零
        assertEquals(0, runner.runningCount());
    }

    @Test
    void successCallbackReceivesResultViaExecutor() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        runner.submit("t", () -> "hello", r -> { result.set(r); done.countDown(); }, e -> done.countDown());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("hello", result.get());
    }

    @Test
    void cancelInterruptsAndRoutesToOnError() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);

        TaskHandle handle = runner.submit("t", () -> {
            started.countDown();
            new CountDownLatch(1).await();   // 永久阻塞,等待中断
            return "never";
        }, r -> {}, e -> { error.set(e); failed.countDown(); });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        handle.cancel();
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertInstanceOf(InterruptedException.class, error.get());
        assertFalse(handle.isRunning());
    }

    @Test
    void cancelAllStopsEverything() throws Exception {
        SimpleTaskRunner runner = new SimpleTaskRunner(stubPlugin(), DIRECT);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch failed = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            runner.submit("t" + i, () -> {
                started.countDown();
                new CountDownLatch(1).await();
                return null;
            }, r -> {}, e -> failed.countDown());
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        runner.cancelAll();
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        assertEquals(0, runner.runningCount());
    }
}
