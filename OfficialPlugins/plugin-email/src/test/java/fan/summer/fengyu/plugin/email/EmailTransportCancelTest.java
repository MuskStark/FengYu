package fan.summer.fengyu.plugin.email;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fan.summer.fengyu.plugin.email.crypto.CredentialCipher;
import fan.summer.fengyu.plugin.email.database.EmailDatabase;
import fan.summer.fengyu.plugin.email.rpc.EmailRpcHandlers;
import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.PluginDatabaseConfig;
import fan.summer.fengyu.sdk.RpcContext;
import fan.summer.fengyu.sdk.RpcError;
import fan.summer.fengyu.sdk.RpcTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.KeyGenerator;
import java.nio.file.Path;
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
 * Proves the Email worker honours the standard JSON-RPC transport cancellation over an in-memory
 * transport: an in-flight call cancelled via {@code $/cancelRequest} returns a {@code CANCELLED}
 * error (code -32800 / {@code data.code "CANCELLED"}), and the SAME worker keeps serving the next
 * call — cancellation is a clean response, never a crash. This is the Email peer of the markdown
 * {@code RenderCancellationTest}; it also guards that the Email {@code result()} wrapper re-throws
 * {@code RpcException(CANCELLED)} instead of swallowing it into a {@code success=false} envelope.
 */
class EmailTransportCancelTest {

    @TempDir Path temp;

    record SlowInput(String text) {}
    record SlowOutput(String message) {}

    /** In-memory frame transport: blocking-queue reads + synchronized captured writes. */
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
    void inFlightCancelReturnsCancelledAndWorkerStillServes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        MemoryTransport t = new MemoryTransport();
        // The production Email worker (all 36 typed methods) plus a deliberately slow, cooperatively
        // cancellable test method on the same instance. The follow-up email_accounts_list on an empty
        // DB proves the worker survives the cancel (it is not a crash / restart).
        JsonRpcWorker worker = EmailWorkerMain.worker(new EmailRpcHandlers(database(), cipher()))
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
                "email-cancel-runner");
        runner.setDaemon(true);
        runner.start();

        // Start the slow call, cancel it mid-flight, then issue a real Email call on the same worker.
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"slow\",\"params\":{\"text\":\"x\"}}");
        assertTrue(entered.await(2, TimeUnit.SECONDS), "slow handler must start before cancel");
        t.send("{\"jsonrpc\":\"2.0\",\"method\":\"$/cancelRequest\",\"params\":{\"id\":\"1\"}}");
        t.send("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"email_accounts_list\",\"params\":{}}");
        t.eof();

        joinQuietly(runner, 5_000);
        List<String> frames = t.drainWrites();
        // $/cancelRequest is a notification (no response frame); only slow(1) and accounts_list(2) answer.
        assertEquals(2, frames.size(), "cancel notification must not produce a response frame");

        JsonObject cancelled = responseFor(frames, "1");
        assertNotNull(cancelled, "cancelled call must still get a response");
        JsonObject err = cancelled.getAsJsonObject("error");
        assertEquals(RpcError.Code.CANCELLED.jsonRpcCode(), err.get("code").getAsInt(),
                "cancelled call must report the CANCELLED JSON-RPC code, not be swallowed into success=false");
        assertEquals("CANCELLED", err.getAsJsonObject("data").get("code").getAsString(),
                "cancelled call must carry the CANCELLED semantic code label");

        JsonObject next = responseFor(frames, "2");
        assertNotNull(next, "follow-up email_accounts_list must get a response");
        assertTrue(next.getAsJsonObject("result").get("success").getAsBoolean(),
                "worker must keep serving Email calls after a cancel (cancel is not a crash)");
    }

    private EmailDatabase database() {
        return new EmailDatabase(new PluginDatabaseConfig("h2", "org.h2.Driver",
                "jdbc:h2:mem:email-cancel-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "", temp));
    }

    private static CredentialCipher cipher() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return new CredentialCipher(generator.generateKey());
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
