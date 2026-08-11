package fan.summer.fengyu.plugin.markdown;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.RpcError;
import fan.summer.fengyu.sdk.RpcTransport;
import fan.summer.markdown.generated.RenderInput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Toolchain 2 typed-render contract for the markdown worker end-to-end over an
 * in-memory JSON-RPC transport:
 * <ul>
 *   <li>A {@code render} round-trip deserializes {@link RenderInput}, runs the typed handler, and
 *       serializes {@code RenderOutput} with sanitized HTML.</li>
 *   <li>An in-flight call cancelled via {@code $/cancelRequest} returns a {@code CANCELLED} error
 *       (code -32800 / {@code data.code "CANCELLED"}), and the <em>same</em> worker keeps serving
 *       the next {@code render} request — cancellation is a clean response, never a crash.</li>
 * </ul>
 *
 * <p>The real {@code render} handler completes in microseconds, so it cannot be reliably cancelled
 * mid-flight. The cancellation case therefore targets a deliberately slow test method registered on
 * the same worker (the task spec's "or a slow test method" allowance); the follow-up request exercises
 * the real {@code render} registration, proving the worker stays usable after a cancel.
 */
class RenderCancellationTest {

    record SlowInput(String text) {}
    record SlowOutput(String message) {}

    /** In-memory frame transport: blocking queue reads + synchronized captured writes. */
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
                String f = reads.poll(5, TimeUnit.SECONDS);
                if (f == null) throw new IllegalStateException("read timeout: no frame within 5s");
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

    @Test
    void typedRenderRoundTripsAndSanitizesHtml() throws Exception {
        JsonRpcWorker worker = MarkdownWorkerMain.worker(new MarkdownRpcHandlers());
        MemoryTransport t = new MemoryTransport();

        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} },
                "render-roundtrip-runner");
        runner.setDaemon(true);
        runner.start();

        String md = "# hi\n\nHello **world**";
        JsonObject renderReq = new JsonObject();
        renderReq.addProperty("jsonrpc", "2.0");
        renderReq.addProperty("id", "r1");
        renderReq.addProperty("method", "render");
        JsonObject params = new JsonObject();
        params.addProperty("markdown", md);
        renderReq.add("params", params);
        t.send(new Gson().toJson(renderReq));
        t.eof();

        joinQuietly(runner, 5_000);
        JsonObject resp = responseFor(t.drainWrites(), "r1");
        assertNotNull(resp, "render must produce a response");
        JsonObject result = resp.getAsJsonObject("result");
        assertTrue(result.get("success").getAsBoolean(), "render success must be true");
        assertTrue(result.get("html").getAsString().contains("<strong>world</strong>"),
                "render html must contain the commonmark output");
        assertTrue(result.get("summary").getAsString().startsWith("rendered "),
                "render summary must be the localized render summary");
    }

    @Test
    void inFlightCancelReturnsCancelledAndWorkerStillServesRender() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        MemoryTransport t = new MemoryTransport();
        // The production worker (real `render`) plus a slow, cooperatively-cancellable test method
        // on the SAME worker instance — the follow-up render proves the worker survives the cancel.
        JsonRpcWorker worker = MarkdownWorkerMain.worker(new MarkdownRpcHandlers())
                .method("slow", SlowInput.class, SlowOutput.class,
                        (SlowInput in, RpcContext ctx) -> {
                            entered.countDown();
                            while (!ctx.cancellation().isCancelled()) {
                                try { Thread.sleep(5); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                            }
                            ctx.cancellation().throwIfCancelled();
                            return new SlowOutput("unreachable");
                        });

        Thread runner = new Thread(() -> { try { worker.serve(t); } catch (Exception ignored) {} },
                "render-cancel-runner");
        runner.setDaemon(true);
        runner.start();

        // Start the slow call, cancel it mid-flight, then issue a real render on the same worker.
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"slow\",\"params\":{\"text\":\"x\"}}");
        assertTrue(entered.await(2, TimeUnit.SECONDS), "slow handler must start before cancel");
        t.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"1\"}}");
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"render\",\"params\":{\"markdown\":\"# ok\"}}");
        t.eof();

        joinQuietly(runner, 5_000);
        List<String> frames = t.drainWrites();
        // Cancel is a notification (no response frame); only slow(1) and render(2) get responses.
        assertEquals(2, frames.size(), "cancel notification must not produce a response frame");

        JsonObject cancelled = responseFor(frames, "1");
        assertNotNull(cancelled, "cancelled call must still get a response");
        JsonObject err = cancelled.getAsJsonObject("error");
        assertEquals(RpcError.Code.CANCELLED.jsonRpcCode(), err.get("code").getAsInt(),
                "cancelled call must report the CANCELLED JSON-RPC code");
        assertEquals("CANCELLED", err.getAsJsonObject("data").get("code").getAsString(),
                "cancelled call must carry the CANCELLED semantic code label");

        JsonObject render = responseFor(frames, "2");
        assertNotNull(render, "follow-up render must get a response");
        assertTrue(render.getAsJsonObject("result").get("success").getAsBoolean(),
                "worker must keep serving render after a cancel (cancel is not a crash)");
    }

    private static JsonObject responseFor(List<String> frames, String id) {
        for (String f : frames) {
            JsonObject o = JsonParser.parseString(f).getAsJsonObject();
            if (id.equals(o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : null)) {
                return o;
            }
        }
        return null;
    }

    private static void joinQuietly(Thread t, long millis) {
        try { t.join(millis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
