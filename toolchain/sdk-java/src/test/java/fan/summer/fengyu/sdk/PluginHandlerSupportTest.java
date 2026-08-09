package fan.summer.fengyu.sdk;

import org.junit.jupiter.api.Test;

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
}
