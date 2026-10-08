package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (P1-1): the SDK's per-call entry log must record the param KEYS only — never the
 * values. A request can carry secrets in any value (an SMTP password, a mail body, a token, a
 * parsed path), so stringifying a value (even truncated) leaks it to the host's plugin log
 * surface. The env redactor only knows env-borne secrets, so it cannot redact request-carried
 * values. {@link PluginHandlerSupport#abbreviateParams} is the single place the entry log renders
 * params; this test pins that it is value-free.
 */
class PluginHandlerSupportTest {

    /** Concrete subclass purely to reach the protected static helper under test. */
    private static final class Harness extends PluginHandlerSupport {
        Harness() { super("test"); }
        static String preview(Map<String, Object> params) { return abbreviateParams(params); }
    }

    @Test
    void abbreviateParamsLogsKeysOnlyNeverValues() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("accountId", 42L);
        params.put("password", "hunter2-secret");
        params.put("body", "a very long mail body that used to be truncated but still leaked");

        String preview = Harness.preview(params);

        // Keys are recorded (call shape is useful for diagnostics).
        assertTrue(preview.contains("accountId"), "param keys must be logged: " + preview);
        assertTrue(preview.contains("password"));
        assertTrue(preview.contains("body"));
        // Values must NEVER appear — not the secret, not the long body, not even the id.
        assertFalse(preview.contains("hunter2-secret"), "param value leaked into entry log: " + preview);
        assertFalse(preview.contains("a very long mail body"), "param value leaked into entry log: " + preview);
        assertFalse(preview.contains("42"), "param value leaked into entry log: " + preview);
    }

    @Test
    void abbreviateParamsHandlesEmptyAndNull() {
        assertEquals("{}", Harness.preview(null));
        assertEquals("{}", Harness.preview(Map.of()));
    }

    /**
     * Regression (redaction invariant): a plain throwable's message must reach NEITHER the response
     * envelope (it may embed request-carried secrets) NOR the shared stderr channel (the raw
     * throwable is no longer handed to SLF4J). Only a handler-authored RpcException message —
     * controlled by the plugin author — may surface in the summary.
     */
    @Test
    void plainExceptionMessagesNeverReachWireOrStderr() throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Harness support = new Harness();
        try {
            System.setErr(new PrintStream(diagnostics, true, java.nio.charset.StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked")
            Map<String, Object> flattened = (Map<String, Object>) support.handle("save",
                p -> { throw new IllegalStateException("password=hunter2-secret"); })
                .handle(Map.of());
            assertFalse((Boolean) flattened.get("success"), "failure envelope");
            assertFalse(String.valueOf(flattened.get("summary")).contains("hunter2-secret"),
                "raw message leaked into the response envelope: " + flattened);
            assertEquals("test operation failed", flattened.get("summary"),
                "generic localized failure summary instead");
        } finally {
            System.setErr(originalErr);
        }
        String stderr = diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(stderr.contains("IllegalStateException"), "the class name still diagnoses: " + stderr);
        assertFalse(stderr.contains("hunter2-secret"),
            "raw exception message leaked to stderr:\n" + stderr);
    }

    /** A handler-authored RpcException IS the controlled channel: its message reaches the caller. */
    @Test
    void rpcExceptionMessageSurfacesInSummary() throws Exception {
        Harness support = new Harness();
        @SuppressWarnings("unchecked")
        Map<String, Object> flattened = (Map<String, Object>) support.handle("save",
            p -> { throw new RpcException(RpcError.Code.INVALID_ARGUMENT, "sheet 'Q3' not found"); })
            .handle(Map.of());
        assertFalse((Boolean) flattened.get("success"));
        assertEquals("sheet 'Q3' not found", flattened.get("summary"),
            "RpcException messages are caller-safe by contract and must survive flattening");
    }
}
