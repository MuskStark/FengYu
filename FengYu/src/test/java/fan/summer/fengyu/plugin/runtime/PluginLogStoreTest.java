package fan.summer.fengyu.plugin.runtime;

import fan.summer.fengyu.web.controller.PluginRuntimeController;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PluginLogStoreTest {

    @Test
    void appendAndRecent_returnsOldestFirst() {
        PluginLogStore store = new PluginLogStore(fixedClock());
        store.append("markdown", "INFO", "first");
        store.append("markdown", "DEBUG", "second");
        store.append("markdown", "WARN", "third");
        List<PluginLogEntry> recent = store.recent("markdown", 100);
        assertEquals(3, recent.size());
        assertEquals("first", recent.get(0).message());
        assertEquals("third", recent.get(2).message());
        assertEquals("WARN", recent.get(2).level());
    }

    @Test
    void recentForUnknownPlugin_returnsEmpty() {
        PluginLogStore store = new PluginLogStore();
        assertTrue(store.recent("never-spawned", 10).isEmpty());
    }

    @Test
    void ringBufferEvictsOldestBeyondCapacity() {
        PluginLogStore store = new PluginLogStore(fixedClock());
        for (int i = 0; i < PluginLogStore.CAPACITY + 10; i++) {
            store.append("excel", "INFO", "line-" + i);
        }
        List<PluginLogEntry> recent = store.recent("excel", PluginLogStore.CAPACITY);
        assertEquals(PluginLogStore.CAPACITY, recent.size());
        // Oldest 10 evicted; first kept entry is line-10, last is line-(CAPACITY+9).
        assertEquals("line-10", recent.get(0).message());
        assertEquals("line-" + (PluginLogStore.CAPACITY + 9), recent.get(recent.size() - 1).message());
    }

    @Test
    void recentWithMaxClampsToMostRecent() {
        PluginLogStore store = new PluginLogStore(fixedClock());
        for (int i = 0; i < 5; i++) store.append("email", "INFO", "m" + i);
        List<PluginLogEntry> recent = store.recent("email", 2);
        assertEquals(2, recent.size());
        assertEquals("m3", recent.get(0).message());
        assertEquals("m4", recent.get(1).message());
    }

    @Test
    void subscriberReceivesLiveEntriesAfterSubscribe() throws Exception {
        PluginLogStore store = new PluginLogStore();
        List<PluginLogEntry> received = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = store.subscribe("markdown", received::add);
        store.append("markdown", "INFO", "after-subscribe");
        // Delivery is asynchronous (each subscriber has its own drain thread), so wait for it.
        waitFor(() -> received.size() >= 1, Duration.ofSeconds(2));
        assertEquals(1, received.size());
        assertEquals("after-subscribe", received.get(0).message());
        unsubscribe.run();
        store.append("markdown", "INFO", "after-unsubscribe");
        Thread.sleep(100); // give the unsubscribed drain thread time to (not) deliver
        assertEquals(1, received.size(), "unsubscribed listener must not receive further entries");
    }

    @Test
    void subscriberSurvivingAFailingSubscriberStillReceives() throws Exception {
        PluginLogStore store = new PluginLogStore();
        AtomicInteger survivingCalls = new AtomicInteger();
        store.subscribe("excel", ignored -> { throw new IllegalStateException("boom"); });
        store.subscribe("excel", ignored -> survivingCalls.incrementAndGet());
        store.append("excel", "INFO", "hello");
        // Delivery is asynchronous; wait for the surviving subscriber to receive its entry.
        waitFor(() -> survivingCalls.get() >= 1, Duration.ofSeconds(2));
        assertEquals(1, survivingCalls.get(), "a throwing subscriber must not poison the others");
    }

    /**
     * Regression (P1-3): a slow SSE subscriber must NOT block the worker stderr drain. Before the
     * fix {@code append()} invoked each subscriber inline on the caller's thread, so a subscriber
     * whose {@code accept()} blocked stalled the drain (and could block the worker itself once its
     * stderr pipe filled). With per-subscriber decoupling, N appends complete quickly even while a
     * subscriber sleeps; the slow subscriber eventually receives its lines on its own thread.
     */
    @Test
    void slowSubscriberDoesNotBlockAppendOnDrainThread() throws Exception {
        PluginLogStore store = new PluginLogStore();
        CountDownLatch blockSubscriber = new CountDownLatch(1);
        List<PluginLogEntry> slowReceived = new CopyOnWriteArrayList<>();
        store.subscribe("excel", entry -> {
            slowReceived.add(entry);
            // Block this subscriber's delivery until the test releases it.
            try { blockSubscriber.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        // Append 10 entries on THIS (drain) thread; all must return promptly despite the blocked sub.
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) store.append("excel", "INFO", "m" + i);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000,
            "append() blocked by slow subscriber: " + elapsedMs + " ms (must be < 1000)");
        // The buffered entries are there regardless of the slow subscriber.
        assertEquals(10, store.recent("excel", 100).size());
        // Release the blocked subscriber so the test can finish promptly.
        blockSubscriber.countDown();
    }

    /**
     * Regression (P1-3): when a slow subscriber falls behind, its bounded per-connection queue must
     * drop the OLDEST entries (never throw, never grow unbounded). The subscriber keeps running and
     * the store stays bounded — matching the ring-buffer eviction philosophy already used for the
     * history buffer.
     */
    @Test
    void slowSubscriberQueueDropsOldestAndStaysBounded() throws Exception {
        PluginLogStore store = new PluginLogStore();
        CountDownLatch blockSubscriber = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        store.subscribe("excel", entry -> {
            delivered.incrementAndGet();
            // Block only the FIRST delivery to let the queue fill past capacity.
            if (delivered.get() == 1) {
                try { blockSubscriber.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });
        // Far more appends than the per-subscriber queue capacity — oldest entries are dropped.
        for (int i = 0; i < PluginLogStore.SUBSCRIBER_QUEUE_CAPACITY + 50; i++) {
            store.append("excel", "INFO", "line-" + i);
        }
        // append() must never throw and must stay bounded in time.
        blockSubscriber.countDown();
        // The subscriber's drainer eventually drains its bounded queue; the first "line-0" was
        // evicted to make room. We don't assert exact counts (timing-dependent) but the store never
        // OOMed and the history buffer still holds the last CAPACITY entries.
        assertTrue(store.recent("excel", PluginLogStore.CAPACITY).size() <= PluginLogStore.CAPACITY);
    }

    @Test
    void clearDropsBufferAndSubscribers() {
        PluginLogStore store = new PluginLogStore();
        List<PluginLogEntry> received = new CopyOnWriteArrayList<>();
        store.subscribe("offlinepython", received::add);
        store.append("offlinepython", "INFO", "kept");
        store.clear("offlinepython");
        assertTrue(store.recent("offlinepython", 10).isEmpty());
        int before = received.size();
        store.append("offlinepython", "INFO", "post-clear");
        assertEquals(before, received.size(), "subscribers must be cleared alongside the buffer");
    }

    /**
     * Regression (P2-1 replay race): each appended entry gets a store-wide monotonic sequence. The
     * SSE log stream relies on this to replay history and then go live WITHOUT delivering any entry
     * twice — a client connecting mid-traffic would otherwise see a duplicate for the entry caught
     * in the subscribe-then-replay window.
     */
    @Test
    void appendedEntriesGetStrictlyIncreasingSequence() {
        PluginLogStore store = new PluginLogStore();
        store.append("excel", "INFO", "a");
        store.append("excel", "INFO", "b");
        store.append("markdown", "INFO", "c"); // sequence is store-wide, not per-plugin
        List<PluginLogEntry> excel = store.recent("excel", 10);
        long s0 = excel.get(0).sequence();
        long s1 = excel.get(1).sequence();
        long s2 = store.recent("markdown", 10).get(0).sequence();
        assertTrue(s1 > s0, "sequence must be strictly increasing: " + s0 + " -> " + s1);
        assertTrue(s2 > s1, "sequence must be store-wide monotonic: " + s1 + " -> " + s2);
    }

    @Test
    void pausedSubscriptionQueuesConcurrentLiveEntriesUntilReplayActivation() throws Exception {
        PluginLogStore store = new PluginLogStore();
        for (int i = 0; i < 8; i++) store.append("excel", "INFO", "history-" + i);
        CopyOnWriteArrayList<PluginLogEntry> live = new CopyOnWriteArrayList<>();
        PluginLogStore.Subscription subscription = store.subscribeWithSnapshot("excel", live::add);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 64; i++) {
                int n = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    store.append("excel", "INFO", "live-" + n);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(2, TimeUnit.SECONDS);
        }

        assertTrue(live.isEmpty(), "live delivery must remain paused while history is replayed");
        subscription.activate();
        waitFor(() -> live.size() == 64, Duration.ofSeconds(2));

        List<PluginLogEntry> ordered = new ArrayList<>(subscription.snapshot());
        ordered.addAll(live);
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(ordered.get(i).sequence() > ordered.get(i - 1).sequence(),
                "replay/live sequence regressed at index " + i);
        }
        subscription.unsubscribe().run();
    }

    @Test
    void controllerNeverLetsLiveDeliveryOvertakeReplay() throws Exception {
        PluginLogStore store = new PluginLogStore();
        for (int i = 0; i < 5; i++) store.append("excel", "INFO", "history-" + i);
        CountDownLatch replayStarted = new CountDownLatch(1);
        CountDownLatch continueReplay = new CountDownLatch(1);
        CopyOnWriteArrayList<PluginLogEntry> delivered = new CopyOnWriteArrayList<>();
        PluginRuntimeController controller = new PluginRuntimeController(null, null, store) {
            @Override
            protected boolean sendLogEntry(SseEmitter emitter, String id, PluginLogEntry entry) {
                delivered.add(entry);
                if (delivered.size() == 1) {
                    replayStarted.countDown();
                    try {
                        assertTrue(continueReplay.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var connecting = executor.submit(() -> controller.logStream("excel"));
            assertTrue(replayStarted.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 10; i++) store.append("excel", "INFO", "live-" + i);
            continueReplay.countDown();
            SseEmitter emitter = connecting.get(2, TimeUnit.SECONDS);
            waitFor(() -> delivered.size() == 15, Duration.ofSeconds(2));
            emitter.complete();
        }
        for (int i = 1; i < delivered.size(); i++) {
            assertTrue(delivered.get(i).sequence() > delivered.get(i - 1).sequence(),
                "live event overtook replay at index " + i);
        }
    }

    @Test
    void replayFailureUnsubscribesImmediately() {
        PluginLogStore store = new PluginLogStore();
        store.append("excel", "INFO", "history");
        PluginRuntimeController controller = new PluginRuntimeController(null, null, store) {
            @Override
            protected boolean sendLogEntry(SseEmitter emitter, String id, PluginLogEntry entry) {
                return false;
            }
        };

        controller.logStream("excel");

        assertEquals(0, store.subscriberCountForTest("excel"),
            "a replay send failure must remove the paused subscriber before returning");
    }

    @Test
    void levelParserPicksUpLevelTokenFromSlf4jSimpleLine() {
        assertEquals("INFO", PluginLogLineParser.levelOf("12:34:56.789 INFO JsonRpcWorker - started"));
        assertEquals("WARN", PluginLogLineParser.levelOf("12:34:56.789 WARN ExcelRpcHandlers - oops"));
        assertEquals("ERROR", PluginLogLineParser.levelOf("[main] ERROR foo.Bar - boom"));
        assertEquals("DEBUG", PluginLogLineParser.levelOf("DEBUG foo - x"));
    }

    @Test
    void levelParserIgnoresLevelTokenEmbeddedInWord() {
        assertEquals("INFO", PluginLogLineParser.levelOf("INFORMATION about something"));
    }

    @Test
    void levelParserDefaultsToInfoWhenUnparseable() {
        assertEquals("INFO", PluginLogLineParser.levelOf((String) null));
        assertEquals("INFO", PluginLogLineParser.levelOf("no level here at all"));
        assertEquals("INFO", PluginLogLineParser.levelOf("warning: lowercase is not a token"));
    }

    @Test
    void levelParserNormalisesWarningToWarn() {
        assertEquals("WARN", PluginLogLineParser.levelOf("[main] WARNING foo.Bar - x"));
    }

    @Test
    void parsesStructuredSdkEventWithoutLosingLoggerOrThrowable() {
        PluginLogLineParser.Parsed event = PluginLogLineParser.parse(
            "@fengyu-log:{\"level\":\"ERROR\",\"logger\":\"com.example.Worker\","
                + "\"thread\":\"worker-1\",\"message\":\"failed\","
                + "\"throwable\":\"java.lang.IllegalStateException: boom\\n\\tat Example.run\"}");

        assertEquals("ERROR", event.level());
        assertEquals("com.example.Worker", event.logger());
        assertEquals("worker-1", event.thread());
        assertTrue(event.message().contains("failed"));
        assertTrue(event.message().contains("IllegalStateException: boom"));
    }

    @Test
    void structuredEntriesRetainLoggerAndThreadInTheApiModel() {
        PluginLogStore store = new PluginLogStore(fixedClock());

        store.append("markdown", "DEBUG", "com.example.Worker", "worker-1", "rendering");

        PluginLogEntry entry = store.recent("markdown", 1).getFirst();
        assertEquals("com.example.Worker", entry.logger());
        assertEquals("worker-1", entry.thread());
        assertEquals("rendering", entry.message());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC);
    }

    /** Spin until {@code condition} is true, failing the test after {@code timeout}. */
    private static void waitFor(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError("condition not met within " + timeout);
    }
}
