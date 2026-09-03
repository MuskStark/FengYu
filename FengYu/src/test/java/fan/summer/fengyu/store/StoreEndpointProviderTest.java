package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void plainHttpIntranetOverrideIsAllowedOnlyWithTheFlag() {
        // A remote self-hosted store without a certificate: the 升级渠道 can point
        // at it once the private-network escape hatch is on — this is exactly the
        // cross-site deployment the flag exists for.
        assertEquals("http://10.0.0.5:8080", provider("http://10.0.0.5:8080", true).base());
        assertThrows(IllegalStateException.class,
                () -> provider("http://10.0.0.5:8080", false).base());
    }

    @Test
    void settingsUiToggleFlipsThePolicyWithoutARestart() {
        // The Settings toggle is re-read on every resolution: the channel starts
        // rejected (launch property off, toggle off), the UI flips the toggle,
        // and the very next resolution passes — no restart.
        java.util.concurrent.atomic.AtomicBoolean toggle = new java.util.concurrent.atomic.AtomicBoolean();
        StoreEndpointProvider live = new StoreEndpointProvider(BOOTSTRAP,
                () -> "http://10.0.0.5:8080", false, toggle::get);
        assertThrows(IllegalStateException.class, live::base);
        toggle.set(true);
        assertEquals("http://10.0.0.5:8080", live.base());
        toggle.set(false);
        assertThrows(IllegalStateException.class, live::base);
    }

    @Test
    void launchPropertyOrToggleGrantsThePosture() {
        assertTrue(provider("http://10.0.0.5:8080", true).allowPrivateNetwork());
        assertTrue(new StoreEndpointProvider(BOOTSTRAP, () -> null, false,
                () -> true).allowPrivateNetwork());
        assertFalse(provider("http://10.0.0.5:8080", false).allowPrivateNetwork());
    }
}
