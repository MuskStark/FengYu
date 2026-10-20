package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the health payload: the readiness consumers (Electron sidecar, boot gate) only read
 * {@code status}, while the SPA renders {@code version} from this probe instead of the
 * network-bound update check — so the field must always resolve, never null.
 */
class HealthControllerTest {

    @Test
    void reportsOkAndANonBlankVersion() {
        Map<String, Object> body = new HealthController().health();
        assertEquals("ok", body.get("status"));
        assertFalse(String.valueOf(body.get("version")).isBlank(),
                "version must resolve even without a JAR manifest (IDE runs fall back to "
                        + "build-info.properties, or 'Dev' as a last resort)");
    }
}
