package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store endpoint resolution behind the Settings 升级渠道: the channel
 * override wins when set AND the self-hosted posture is enabled, the bootstrap
 * property is the fallback (including while a saved override is dormant with
 * the posture off), and every resolution re-runs the SSRF policy against the
 * effective base.
 */
class StoreEndpointProviderTest {

    private static final String BOOTSTRAP = "http://127.0.0.1:8080";

    private StoreEndpointProvider provider(String override, boolean allowPrivateNetwork) {
        return new StoreEndpointProvider(BOOTSTRAP, () -> override, allowPrivateNetwork);
    }

    @Test
    void blankOrNullOverrideFallsBackToBootstrap() {
        assertEquals(BOOTSTRAP, provider("", true).base());
        assertEquals(BOOTSTRAP, provider(null, true).base());
        assertEquals(BOOTSTRAP, provider("   ", true).base());
    }

    @Test
    void overrideWinsAndIsNormalized() {
        assertEquals("http://127.0.0.2:9999",
                provider("http://127.0.0.2:9999///", true).base());
    }

    @Test
    void bootstrapTrailingSlashesAreStripped() {
        assertEquals(BOOTSTRAP,
                new StoreEndpointProvider("http://127.0.0.1:8080//", () -> null, false).base());
    }

    @Test
    void overrideIsDormantWithoutTheSelfHostedPosture() {
        // Declining the self-hosted channel (posture off) must revert every surface
        // to the bootstrap base instead of keeping the saved address in effect —
        // loopback included; the UrlPolicy loopback carve-out is not a claim that a
        // local store is in use.
        assertEquals(BOOTSTRAP, provider("https://10.0.0.5:8088", false).base());
        assertEquals(BOOTSTRAP, provider("http://10.0.0.5:8080", false).base());
        assertEquals(BOOTSTRAP, provider("http://localhost:8089", false).base());
    }

    @Test
    void privateNetworkOverrideIsActiveWithTheFlag() {
        assertEquals("https://10.0.0.5:8088", provider("https://10.0.0.5:8088", true).base());
    }

    @Test
    void plainHttpIntranetOverrideIsActiveOnlyWithTheFlag() {
        // A remote self-hosted store without a certificate: the 升级渠道 can point
        // at it once the private-network escape hatch is on — this is exactly the
        // cross-site deployment the flag exists for.
        assertEquals("http://10.0.0.5:8080", provider("http://10.0.0.5:8080", true).base());
        assertEquals(BOOTSTRAP, provider("http://10.0.0.5:8080", false).base());
    }

    @Test
    void secureTransportAcceptsHttpsAndLoopbackOnly() {
        // Refresh-token persistence gate: HTTPS anywhere, or a loopback dev
        // store (traffic never leaves the host, mirroring UrlPolicy's loopback
        // HTTPS exemption); plain HTTP anywhere else stays memory-only.
        // The off-loopback hosts are TEST-NET-1 literals: UrlPolicy resolves
        // every hostname for real, and a placeholder name with no DNS record
        // fails the test on runners (store.example.com broke CI exactly so).
        assertTrue(provider("https://192.0.2.10", true).secureTransport());
        assertTrue(provider("http://localhost:8080", true).secureTransport());
        assertTrue(provider("http://127.0.0.1:8080", true).secureTransport());
        assertTrue(provider("http://[::1]:8080", true).secureTransport());
        assertTrue(provider("http://dev.localhost:8080", true).secureTransport());
        assertFalse(provider("http://10.0.0.5:8080", true).secureTransport(),
                "plain HTTP to a LAN store is not a persistent-credential channel");
        assertFalse(provider("http://192.0.2.10", true).secureTransport(),
                "plain HTTP off loopback is not a persistent-credential channel");
    }

    @Test
    void settingsUiToggleFlipsTheChannelWithoutARestart() {
        // The Settings toggle is re-read on every resolution: the channel starts
        // dormant (launch property off, toggle off), the UI flips the toggle,
        // and the very next resolution uses the override — no restart. Flipping
        // it back off parks the channel on the bootstrap base again while the
        // saved address stays untouched (re-enabling restores it instantly).
        java.util.concurrent.atomic.AtomicBoolean toggle = new java.util.concurrent.atomic.AtomicBoolean();
        StoreEndpointProvider live = new StoreEndpointProvider(BOOTSTRAP,
                () -> "http://10.0.0.5:8080", false, toggle::get);
        assertEquals(BOOTSTRAP, live.base());
        toggle.set(true);
        assertEquals("http://10.0.0.5:8080", live.base());
        toggle.set(false);
        assertEquals(BOOTSTRAP, live.base());
    }

    @Test
    void launchPropertyOrToggleGrantsThePosture() {
        assertTrue(provider("http://10.0.0.5:8080", true).allowPrivateNetwork());
        assertTrue(new StoreEndpointProvider(BOOTSTRAP, () -> null, false,
                () -> true).allowPrivateNetwork());
        assertFalse(provider("http://10.0.0.5:8080", false).allowPrivateNetwork());
    }

    @Test
    void unresolvableActiveOverrideStillSurfacesThePolicyError() {
        // Defense in depth: an override that IS active (posture on) but violates the
        // URL policy fails loudly rather than silently falling back to the bootstrap.
        // A file: base can only arrive via the raw settings row (the Settings UI
        // validates http(s)) — and needs no DNS, so the test stays hermetic.
        assertThrows(IllegalStateException.class,
                () -> provider("file:/etc", true).base());
    }
}
