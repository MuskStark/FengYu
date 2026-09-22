package fan.summer.fengyu.store;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared outbound URL policy: HTTPS everywhere by default, loopback plain
 * HTTP for local development, and — only on the explicit
 * {@code allow-private-network} escape hatch — plain HTTP towards a
 * self-hosted (typically certificate-less) intranet or cross-site store.
 * Host cases use IP literals so the assertions never depend on live DNS.
 */
class UrlPolicyTest {

    @Test
    void loopbackPlainHttpIsAlwaysAllowed() throws Exception {
        UrlPolicy.requireTraversable(URI.create("http://127.0.0.1:8080/"), false);
        UrlPolicy.requireTraversable(URI.create("http://localhost:8080/"), false);
    }

    @Test
    void remotePlainHttpIsRejectedByDefault() {
        // 93.184.216.34 is a public IP literal: no DNS, just policy.
        IOException e = assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                URI.create("http://93.184.216.34:8080/"), false));
        assertTrue(e.getMessage().contains("loopback"), e.getMessage());
    }

    @Test
    void remotePlainHttpIsAllowedOnlyUnderTheExplicitEscapeHatch() throws Exception {
        UrlPolicy.requireTraversable(URI.create("http://93.184.216.34:8080/"), true);
    }

    @Test
    void privateNetworkIsRejectedByDefaultEvenOverHttps() {
        assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                URI.create("https://10.0.0.5:8080/"), false));
        assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                URI.create("http://192.168.1.10:8080/"), false));
    }

    @Test
    void privateNetworkHttpsIsAllowedWithTheFlag() throws Exception {
        UrlPolicy.requireTraversable(URI.create("https://10.0.0.5:8080/"), true);
    }

    @Test
    void ianaSpecialPurposeRangesAreRejectedByDefault() {
        for (String host : new String[] {
                "0.1.2.3",                                    // 0.0.0.0/8 "this network"
                "100.64.0.1", "100.127.255.254",              // CGNAT
                "192.0.0.1", "192.0.2.1",                      // IETF protocol assignments / TEST-NET-1
                "192.88.99.1",                                 // 6to4 relay anycast (deprecated)
                "198.18.0.1", "198.19.255.254",               // benchmarking
                "198.51.100.1", "203.0.113.1",                // TEST-NET-2 / TEST-NET-3
                "240.0.0.1", "255.255.255.255",               // reserved / limited broadcast
                "[2001:db8::1]", "[100::1]"}) {               // IPv6 documentation / discard-only
            IOException error = assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                    URI.create("https://" + host + "/"), false), host);
            assertTrue(error.getMessage().contains("SSRF policy"), host + ": " + error.getMessage());
        }
    }

    @Test
    void adjacentGlobalIpv4RangesRemainUsable() throws Exception {
        // These literals are deliberately outside the special-purpose ranges above; narrowing the
        // policy must not start blocking ordinary public address space — including the /16s that
        // contain a reserved /24 (198.51/16 and 203.0/16 are global outside their TEST-NETs).
        UrlPolicy.requireTraversable(URI.create("https://100.63.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://100.128.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://192.0.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://192.88.98.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://198.20.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://198.50.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://198.51.101.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://203.0.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://203.1.1.1/"), false);
        UrlPolicy.requireTraversable(URI.create("https://[2606:4700::1111]/"), false);
    }

    @Test
    void globalHttpsPolicyHasNoLoopbackOrPlainHttpEscapeHatch() {
        // Declared third-party URLs (imported MCP servers) get the strictest posture: even the
        // loopback/plain-HTTP exceptions that requireTraversable grants for local development
        // must not apply — a local target has to be created manually by the user.
        assertThrows(IOException.class, () -> UrlPolicy.requireGlobalHttps(
                URI.create("http://127.0.0.1:8080/")));
        assertThrows(IOException.class, () -> UrlPolicy.requireGlobalHttps(
                URI.create("https://127.0.0.1/")));
        assertThrows(IOException.class, () -> UrlPolicy.requireGlobalHttps(
                URI.create("http://localhost:8080/")));
        assertThrows(IOException.class, () -> UrlPolicy.requireGlobalHttps(
                URI.create("http://93.184.216.34/")));
        assertThrows(IOException.class, () -> UrlPolicy.requireGlobalHttps(
                URI.create("https://10.0.0.5/")));
        assertDoesNotThrow(() -> UrlPolicy.requireGlobalHttps(
                URI.create("https://93.184.216.34/")));
    }

    @Test
    void unresolvableHostIsRejected() {
        // RFC 2606 keeps .invalid unresolvable — no live DNS dependency.
        assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                URI.create("https://this-host-does-not-exist.invalid/"), true));
    }

    @Test
    void nonHttpSchemesAreRejected() {
        assertThrows(IOException.class, () -> UrlPolicy.requireTraversable(
                URI.create("ftp://93.184.216.34/"), true));
        assertDoesNotThrow(() -> UrlPolicy.requireTraversable(
                URI.create("https://93.184.216.34/"), true));
    }
}
