package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The store endpoint resolution behind the Settings 升级渠道: the channel
 * override wins when set (normalized), the bootstrap property is the fallback,
 * and every resolution re-runs the SSRF policy against the effective base.
 */
class StoreEndpointProviderTest {

    private static final String BOOTSTRAP = "http://127.0.0.1:8080";

    private StoreEndpointProvider provider(String override, boolean allowPrivateNetwork) {
        return new StoreEndpointProvider(BOOTSTRAP, () -> override, allowPrivateNetwork);
    }

    @Test
    void blankOrNullOverrideFallsBackToBootstrap() {
        assertEquals(BOOTSTRAP, provider("", false).base());
        assertEquals(BOOTSTRAP, provider(null, false).base());
        assertEquals(BOOTSTRAP, provider("   ", false).base());
    }

    @Test
    void overrideWinsAndIsNormalized() {
        assertEquals("http://127.0.0.2:9999",
                provider("http://127.0.0.2:9999///", false).base());
    }

    @Test
    void bootstrapTrailingSlashesAreStripped() {
        assertEquals(BOOTSTRAP,
                new StoreEndpointProvider("http://127.0.0.1:8080//", () -> null, false).base());
    }

    @Test
    void privateNetworkOverrideIsRejectedWithoutTheFlag() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> provider("https://10.0.0.5:8088", false).base());
        assertEquals("java.io.IOException", e.getCause().getClass().getName());
    }

    @Test
    void privateNetworkOverrideIsAllowedWithTheFlag() {
        assertEquals("https://10.0.0.5:8088", provider("https://10.0.0.5:8088", true).base());
    }
}
