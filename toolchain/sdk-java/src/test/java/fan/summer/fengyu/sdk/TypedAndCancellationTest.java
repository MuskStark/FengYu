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
    /** Input that declares the plugin method's OWN `locale` field (distinct from the request locale). */
    record LocaleEchoInput(String locale) {}

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
        // The request locale rides in the reserved `_fengyu` envelope (not params), so it can never
        // collide with a plugin method's own `locale` input field.
        runCollect(worker,
            "{\"jsonrpc\":\"2.0\",\"id\":\"call-7\",\"method\":\"echo\",\"params\":{\"text\":\"x\"},\"_fengyu\":{\"locale\":\"zh\"}}\n");
        assertEquals("call-7", seenCallId[0]);
        assertEquals("zh", seenLocale[0]);
    }

    @Test void pluginLocaleParamIsNotOverwrittenByRequestLocale() throws Exception {
        // Core regression guard for the reserved-channel fix: a plugin method's OWN `locale` input
        // field (carried in params) and the host's request locale (carried in _fengyu) are two
        // independent channels. Pre-fix the host injected the request locale into params.locale,
        // clobbering the caller's value; now both survive verbatim.
        String[] seenCtxLocale = new String[1];
        String[] seenParamLocale = new String[1];
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("echo", LocaleEchoInput.class, EchoOutput.class,
                (LocaleEchoInput in, RpcContext ctx) -> {
                    seenCtxLocale[0] = ctx.locale();
                    seenParamLocale[0] = in.locale();
                    return new EchoOutput(in.locale());
                });
        runCollect(worker,
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"echo\",\"params\":{\"locale\":\"fr\"},\"_fengyu\":{\"locale\":\"zh\"}}\n");
        assertEquals("zh", seenCtxLocale[0], "request locale must bind from the _fengyu envelope");
        assertEquals("fr", seenParamLocale[0], "plugin's own locale param must NOT be overwritten");
    }

    @Test void legacyParamsLocaleStillBindsWhenFengyuEnvelopeAbsent() throws Exception {
        // Backward compat: a host that has not yet adopted the _fengyu channel still injects
        // params.locale; the worker falls back to it so locale keeps working across the rollout.
        String[] seenLocale = new String[1];
        JsonRpcWorker worker = new JsonRpcWorker()
            .method("echo", EchoInput.class, EchoOutput.class,
                (EchoInput in, RpcContext ctx) -> { seenLocale[0] = ctx.locale(); return new EchoOutput(in.text()); });
        runCollect(worker,
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"echo\",\"params\":{\"text\":\"x\",\"locale\":\"zh\"}}\n");
        assertEquals("zh", seenLocale[0], "legacy params.locale still binds when _fengyu is absent");
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

    @Test void duplicateIdNewerCallRemainsCancellable() throws Exception {
        // Regression guard: when two calls share an id, the older call's dispatchOne finally must
        // NOT remove the newer call's PendingCall from `pending`. Pre-fix it did (pending.remove(id)
        // dropped the newer entry), so the newer call was untracked: $/cancelRequest missed it, EOF
        // drain missed it, and serve() only returned after the 60s drain timeout. Post-fix each call
        // removes only its own entry (remove(id, call)), so the newer call stays cancellable and the
        // worker drains promptly.
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
        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} },
            "dup-cancel-runner");
        runner.setDaemon(true);
        runner.start();
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"dup\",\"method\":\"slow\",\"params\":{\"text\":\"a\"}}");
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS), "first slow handler must start");
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"dup\",\"method\":\"slow\",\"params\":{\"text\":\"b\"}}");
        // The newer call is now in flight under id="dup". Cancel it explicitly — this must reach the
        // newer call, not be swallowed by the older call having removed its slot.
        t.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"dup\"}}");
        t.eof();
        joinQuietly(runner, 5_000);
        assertFalse(runner.isAlive(),
            "newer dup call must stay cancellable so serve() drains in seconds, not the 60s drain timeout");
        List<String> frames = t.drainWrites();
        // Both the older and the newer call resolve CANCELLED (each writes one frame for id="dup").
        assertTrue(frames.size() >= 2, "expected both dup calls to produce a response, got " + frames.size());
        for (String frame : frames) {
            JsonObject err = JsonParser.parseString(frame).getAsJsonObject().getAsJsonObject("error");
            assertEquals(RpcError.Code.CANCELLED.jsonRpcCode(), err.get("code").getAsInt(),
                "every dup-id response must be CANCELLED");
        }
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
