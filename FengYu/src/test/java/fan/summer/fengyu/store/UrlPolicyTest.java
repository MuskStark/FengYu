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
