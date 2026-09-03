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
 * address — mitigating ticket/catalog-URL SSRF and DNS rebinding towards the
 * host's own loopback services. Used by both the store client and the skill
 * marketplace so the two surfaces can never drift apart.
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

    private static boolean isPrivateNetwork(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        // Unique-local IPv6 (fc00::/7) is not covered by isSiteLocalAddress.
        return address instanceof Inet6Address
                && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** Redacts the query (signed URLs carry tokens) for logs and error messages. */
    public static String describe(URI uri) {
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery().hashCode();
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                + uri.getPath() + query;
    }
}
