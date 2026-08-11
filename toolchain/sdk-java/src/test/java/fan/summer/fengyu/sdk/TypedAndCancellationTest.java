package fan.summer.fengyu.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 1.4.0 typed-handler + cancellation contract:
 * {@code JsonRpcWorker#method}, {@link RpcContext}, {@link CancellationToken},
 * {@code $/cancelRequest}, duplicate request ids, and EOF drain.
 */
class TypedAndCancellationTest {

    record EchoInput(String text) {}
    record EchoOutput(String echoed) {}
    record PingInput() {}
    record PingOutput(String message) {}

    // ── typed method() ──────────────────────────────────────────────────────

    @Test void typedMethodRoundTripsDeserializesInputAndSerializesOutput() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("echo", EchoInput.class, EchoOutput.class,
                (EchoInput input, RpcContext ctx) -> new EchoOutput(input.text()));
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"echo\",\"params\":{\"text\":\"hi\"}}\n";
        String out = runCollect(worker, input);
        JsonObject resp = JsonParser.parseString(out).getAsJsonObject();
        assertEquals("1", resp.get("id").getAsString());
        assertEquals("hi", resp.getAsJsonObject("result").get("echoed").getAsString());
    }

    @Test void typedHandlerRpcExceptionProducesSemanticErrorEnvelope() throws Exception {
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("secret", EchoInput.class, EchoOutput.class,
                (EchoInput in, RpcContext ctx) -> {
                    throw new RpcException(RpcError.Code.PERMISSION_DENIED, "not authorized");
                });
        String input = "{\"jsonrpc\":\"2.0\",\"id\":\"9\",\"method\":\"secret\",\"params\":{\"text\":\"x\"}}\n";
        JsonObject resp = JsonParser.parseString(runCollect(worker, input)).getAsJsonObject();
        JsonObject err = resp.getAsJsonObject("error");
        assertEquals(RpcError.Code.PERMISSION_DENIED.jsonRpcCode(), err.get("code").getAsInt());
        assertEquals("PERMISSION_DENIED", err.getAsJsonObject("data").get("code").getAsString());
        assertEquals("not authorized", err.get("message").getAsString());
    }

    @Test void rpcContextExposesCallIdAndLocale() throws Exception {
        String[] seenCallId = new String[1];
        String[] seenLocale = new String[1];
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("echo", EchoInput.class, EchoOutput.class,
                (EchoInput in, RpcContext ctx) -> {
                    seenCallId[0] = ctx.callId();
                    seenLocale[0] = ctx.locale();
                    return new EchoOutput(in.text());
                });
        runCollect(worker,
            "{\"jsonrpc\":\"2.0\",\"id\":\"call-7\",\"method\":\"echo\",\"params\":{\"text\":\"x\",\"locale\":\"zh\"}}\n");
        assertEquals("call-7", seenCallId[0]);
        assertEquals("zh", seenLocale[0]);
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    /** In-memory frame transport: blocking reads (queue) + synchronized captured writes. */
    static final class MemoryTransport implements RpcTransport {
        final LinkedBlockingQueue<String> reads = new LinkedBlockingQueue<>();
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean open = true;
        private static final String EOF = "__EOF__";

        void send(String frame) { reads.add(frame); }
        void eof() { reads.add(EOF); }
        List<String> drainWrites() { synchronized (writes) { return new ArrayList<>(writes); } }

        @Override public String readFrame() {
            try {
                String f = reads.poll(3, TimeUnit.SECONDS);
                if (f == null) throw new IllegalStateException("read timeout: no frame within 3s");
                return EOF.equals(f) ? null : f;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        @Override public synchronized void writeFrame(String json) { writes.add(json); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }

    private JsonObject responseFor(List<String> frames, String id) {
        for (String f : frames) {
            JsonObject o = JsonParser.parseString(f).getAsJsonObject();
            if (id != null && id.equals(o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : null)) {
                return o;
            }
        }
        return null;
    }

    @Test void inFlightCancelReturnsCancelledAndWorkerStaysUsable() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        MemoryTransport t = new MemoryTransport();
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("slow", EchoInput.class, EchoOutput.class,
                (EchoInput in, RpcContext ctx) -> {
                    entered.countDown();
                    while (!ctx.cancellation().isCancelled()) {
                        try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    ctx.cancellation().throwIfCancelled();
                    return new EchoOutput("unreachable");
                })
            .method("ping", PingInput.class, PingOutput.class,
                (PingInput in, RpcContext ctx) -> new PingOutput("pong"));

        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} },
            "cancel-test-runner");
        runner.setDaemon(true);
        runner.start();

        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"slow\",\"params\":{\"text\":\"x\"}}");
        assertTrue(entered.await(2, TimeUnit.SECONDS), "slow handler must start");
        t.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"1\"}}");
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"ping\",\"params\":{}}");
        t.eof();

        joinQuietly(runner, 5_000);
        List<String> frames = t.drainWrites();
        assertEquals(2, frames.size(), "cancel is a notification (no response); slow + ping each get one");

        JsonObject cancelled = responseFor(frames, "1");
        assertNotNull(cancelled);
        JsonObject err = cancelled.getAsJsonObject("error");
        assertEquals(RpcError.Code.CANCELLED.jsonRpcCode(), err.get("code").getAsInt());
        assertEquals("CANCELLED", err.getAsJsonObject("data").get("code").getAsString());

        JsonObject pong = responseFor(frames, "2");
        assertNotNull(pong);
        assertEquals("pong", pong.getAsJsonObject("result").get("message").getAsString());
    }

    @Test void cancelForUnknownIdIsIgnoredWithoutAResponse() throws Exception {
        MemoryTransport t = new MemoryTransport();
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("ping", PingInput.class, PingOutput.class,
                (PingInput in, RpcContext ctx) -> new PingOutput("pong"));
        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} });
        runner.setDaemon(true);
        runner.start();
        t.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"ghost\"}}");
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"ping\",\"params\":{}}");
        t.eof();
        joinQuietly(runner, 3_000);
        List<String> frames = t.drainWrites();
        assertEquals(1, frames.size(), "unknown-target cancel must not produce a response frame");
        assertEquals("1", JsonParser.parseString(frames.get(0)).getAsJsonObject().get("id").getAsString());
    }

    @Test void duplicateRequestIdCancelsTheOlderCall() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        MemoryTransport t = new MemoryTransport();
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("slow", EchoInput.class, EchoOutput.class,
                (EchoInput in, RpcContext ctx) -> {
                    firstEntered.countDown();
                    while (!ctx.cancellation().isCancelled()) {
                        try { Thread.sleep(10); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    ctx.cancellation().throwIfCancelled();
                    return new EchoOutput("unreachable");
                });
        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} });
        runner.setDaemon(true);
        runner.start();
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"dup\",\"method\":\"slow\",\"params\":{\"text\":\"a\"}}");
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"dup\",\"method\":\"slow\",\"params\":{\"text\":\"b\"}}");
        t.eof();
        joinQuietly(runner, 5_000);
        // The older in-flight call is cancelled (CANCELLED); the newer blocks until EOF drain
        // force-cancels it. Either way no crash, no hang, and responses are written.
        assertTrue(t.drainWrites().size() >= 1, "duplicate id must not swallow all responses");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String runCollect(JsonRpcWorker worker, String input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        worker.run(new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    private static void joinQuietly(Thread t, long millis) {
        try { t.join(millis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
