package fan.summer.zhiflow.ai.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LocalChatBackend.TokenBatcher} (v3.0.1 stability work).
 *
 * <p>The batcher must coalesce high-frequency token callbacks into fewer emits
 * and must drive its periodic flush with a plain JVM scheduler (not a JavaFX
 * {@code Animation}, which may only be created/started on the FX Application
 * Thread — but {@code add()} is invoked from the AI worker thread). The emitter
 * is injectable so these tests run without the JavaFX toolkit.
 */
class TokenBatcherTest {

    @Test
    void flush_drainsConcatenatedBuffer() {
        List<String> received = new ArrayList<>();
        LocalChatBackend.TokenBatcher b = new LocalChatBackend.TokenBatcher(emitter(received));
        b.add("Hel");
        b.add("lo");
        b.flush();
        assertEquals(List.of("Hello"), received);
        // buffer is cleared after a flush
        b.add("!");
        b.flush();
        assertEquals(List.of("Hello", "!"), received);
        b.close();
    }

    @Test
    void flush_onEmptyBuffer_isNoOp() {
        List<String> received = new ArrayList<>();
        LocalChatBackend.TokenBatcher b = new LocalChatBackend.TokenBatcher(emitter(received));
        b.flush();
        assertTrue(received.isEmpty());
        b.close();
    }

    @Test
    void close_flushesRemainderAndIgnoresFurtherAdds() {
        List<String> received = new ArrayList<>();
        LocalChatBackend.TokenBatcher b = new LocalChatBackend.TokenBatcher(emitter(received));
        b.add("partial");
        b.close();
        assertEquals(List.of("partial"), received);
        b.add("ignored");   // after close — must be dropped
        b.flush();
        assertEquals(List.of("partial"), received);
    }

    @Test
    void scheduledFlush_firesAutomaticallyWithoutManualFlush() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        LocalChatBackend.TokenBatcher b = new LocalChatBackend.TokenBatcher(text -> {
            received.add(text);
            latch.countDown();
        });
        b.add("auto");
        assertTrue(latch.await(2, TimeUnit.SECONDS),
                   "scheduled flush did not fire within 2s — timer wiring broken");
        assertEquals(List.of("auto"), received);
        b.close();
    }

    @Test
    void rapidAdds_coalesceIntoSingleFlush() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        LocalChatBackend.TokenBatcher b = new LocalChatBackend.TokenBatcher(text -> {
            received.add(text);
            latch.countDown();
        });
        b.add("a");
        b.add("b");
        b.add("c");   // all within the flush window
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("abc"), received);   // one coalesced emit
        b.close();
    }

    private static Consumer<String> emitter(List<String> sink) {
        return (Consumer<String>) sink::add;
    }
}
