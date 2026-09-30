package fan.summer.fengyu.store;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Shared outbound URL policy for remote artifact sources (design §13.1 SSRF
 * row): HTTPS everywhere except a loopback host (local development), and no
 * host may resolve into a private, link-local or otherwise non-routable
 * address. Used by the store client, the skill marketplace, the plugin
 * package downloader, and the imported-MCP gate so the surfaces can never
 * drift apart.
 *
 * <p><b>DNS rebinding — honest residual risk (P2-11).</b> This check resolves
 * the host itself and validates <em>every</em> address returned by
 * {@code getAllByName}, but {@code java.net.http.HttpClient} performs its own
 * independent resolution at connect time, and the standard library offers no
 * supported way to pin the pre-validated IP while preserving SNI/Host (no
 * custom-connect/SocketChannel hook exists on {@code HttpClient.Builder} in
 * current JDKs; the only workarounds — a local pinning SOCKS relay or speaking
 * HTTP by hand over a pre-connected socket — would re-implement TLS). A
 * zero-TTL rebinding domain can therefore pass validation and connect to a
 * different address milliseconds later. Mitigations that ARE in place:
 * <ul>
 *   <li>all resolved addresses are validated, so the attack requires the second
 *       answer itself to be rebindable mid-flight;</li>
 *   <li>callers invoke {@link #requireTraversable} immediately before sending
 *       (per request, not per host session), shrinking the window;</li>
 *   <li>every client using this policy keeps the {@code HttpClient} default of
 *       NEVER following redirects, so a redirect cannot re-enter resolution
 *       around the check.</li>
 * </ul>
 */
public final class UrlPolicy {

    private UrlPolicy() {}

    /** Enforces the policy for one request hop; throws {@link IOException} on violation. */
    public static void requireTraversable(URI uri, boolean allowPrivateNetwork)
            throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IOException("Remote URL must use HTTP(S): " + describe(uri));
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Remote URL has no host: " + describe(uri));
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve remote host " + host, e);
        }
        boolean https = scheme.equalsIgnoreCase("https");
        boolean loopbackOnly = !Arrays.stream(addresses)
                .filter(a -> !a.isLoopbackAddress()).findFirst().isPresent();
        // Plain HTTP off loopback needs the explicit escape hatch too: a
        // self-hosted LAN/cross-site store rarely has a CA-signed certificate,
        // and allow-private-network already means "I trust this network path".
        // The default posture is unchanged — HTTPS everywhere except loopback.
        if (!https && !loopbackOnly && !allowPrivateNetwork) {
            throw new IOException("Plain-HTTP remote URLs are only allowed on the "
                    + "loopback interface, or on an explicitly trusted network "
                    + "(fengyu.store.allow-private-network=true): " + describe(uri));
        }
        if (allowPrivateNetwork || loopbackOnly) {
            return;
        }
        for (InetAddress address : addresses) {
            if (isPrivateNetwork(address)) {
                throw new IOException("Remote URL resolves into a private or "
                        + "link-local network (SSRF policy): " + describe(uri));
            }
        }
    }

    /**
     * Strictest posture, for URLs a third party declared (imported MCP servers): HTTPS only,
     * and every resolved address must be global — the loopback escape hatch of
     * {@link #requireTraversable} does not apply. A local or private target must be created
     * manually by the user after inspection, never adopted from a remote declaration.
     */
    public static void requireGlobalHttps(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Declared remote URL must use HTTPS: " + describe(uri));
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("Declared remote URL has no host: " + describe(uri));
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve declared remote host " + host, e);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || isPrivateNetwork(address)) {
                throw new IOException("Declared remote URL must resolve to a global address "
                        + "(loopback/private targets must be entered manually): " + describe(uri));
            }
        }
    }

    private static boolean isPrivateNetwork(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (isSpecialPurposeIpv4(address)) {
            return true;
        }
        // Non-global IPv6 ranges not covered by the predicates above.
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            // Unique-local fc00::/7 is not covered by isSiteLocalAddress.
            if ((b[0] & 0xfe) == 0xfc) return true;
            // Documentation 2001:db8::/32 (RFC 3849).
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8) return true;
            // Discard-only 100::/64 (RFC 6666).
            return b[0] == 0x01 && b[1] == 0x00
                    && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0;
        }
        return false;
    }

    /**
     * IANA special-purpose IPv4 ranges not covered by InetAddress's site-local predicate.
     * Match the registry boundaries exactly — over-broad masks would also block ordinary
     * public space (198.51/16 and 203.0/16 contain global unicast outside their reserved
     * /24s): a public DNS name must not be able to aim store/plugin traffic at the
     * non-routable ranges, but legitimate adjacent space must stay usable.
     */
    private static boolean isSpecialPurposeIpv4(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != 4) return false;
        int first = a[0] & 0xff;
        int second = a[1] & 0xff;
        int third = a[2] & 0xff;
        return first == 0                                        // 0.0.0.0/8 "this network"
                || first == 100 && (second & 0xc0) == 0x40       // 100.64.0.0/10 CGNAT
                || first == 192 && second == 0
                        && (third == 0 || third == 2)              // 192.0.0.0/24, 192.0.2.0/24
                || first == 192 && second == 88 && third == 99   // 192.88.99.0/24 6to4 relay
                || first == 198 && second >= 18 && second <= 19  // 198.18.0.0/15 benchmarking
                || first == 198 && second == 51 && third == 100  // 198.51.100.0/24 TEST-NET-2
                || first == 203 && second == 0 && third == 113   // 203.0.113.0/24 TEST-NET-3
                || (first & 0xf0) == 0xf0;                       // 240.0.0.0/4 reserved + broadcast
    }

    /** Redacts the query (signed URLs carry tokens) for logs and error messages. */
    public static String describe(URI uri) {
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery().hashCode();
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                + uri.getPath() + query;
    }
}
