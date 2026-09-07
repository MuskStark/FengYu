package fan.summer.fengyu.ai.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

interface WebTextClient {
    WebResponse get(String url, int maxBytes) throws Exception;

    record WebResponse(String url, int status, String contentType, String body) {}
}

/** Bounded public-http client shared by the read-only web tools. */
final class SafeWebTextClient implements WebTextClient {

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public WebResponse get(String url, int maxBytes) throws Exception {
        URI current = checkedUri(url);
        int limit = Math.max(1024, Math.min(MAX_RESPONSE_BYTES, maxBytes));
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            assertPublicHost(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(25))
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.1")
                    .header("User-Agent", "FengYu/4 web-fetch")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("redirect missing Location header"));
                current = checkedUri(current.resolve(location).toString());
                continue;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            try (InputStream body = response.body()) {
                return new WebResponse(current.toString(), status, contentType, readAtMost(body, limit));
            }
        }
        throw new IOException("too many redirects");
    }

    static URI checkedUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("url must not be blank");
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url must contain a public host and no credentials");
        }
        return uri;
    }

    /**
     * Resolves the host and rejects private/loopback/link-local targets. KNOWN RESIDUAL GAP
     * (accepted, tracked): this validates the DNS answer, then {@code HttpClient} re-resolves
     * the hostname at connect time — a rebinding DNS answer can race between the two lookups
     * (TOCTOU). Closing it fully needs a pinned-IP transport (custom socket layer or a
     * resolver-pinning HTTP client), which is a tracked follow-up. Today's mitigations: the
     * private-range check on every resolution, a redirect cap, and a response byte cap. Blast
     * radius is bounded by the loopback-only, single-user deployment.
     */
    static void assertPublicHost(URI uri) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        if (addresses.length == 0) throw new IOException("host did not resolve");
        for (InetAddress address : addresses) {
            if (isPrivate(address)) throw new IOException("private or local network targets are not allowed");
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPrivateIpv4(bytes, 0);
        }
        if (bytes.length == 16) {
            // IPv6 unique-local addresses fc00::/7.
            if ((bytes[0] & 0xfe) == 0xfc) return true;
            // IPv4-mapped ::ffff:a.b.c.d — decide on the EMBEDDED IPv4 address (the JVM
            // normally unmasks these to Inet4Address, but a literal AAAA record or a
            // rebinding answer can still arrive in the 16-byte form).
            if (isIpv4Mapped(bytes)) return isPrivateIpv4(bytes, 12);
            // NAT64 well-known prefix 64:ff9b::/96: the address is an IPv4 target in
            // disguise, so the embedded-address rules alone are not a policy — block the
            // whole translation prefix.
            if (isNat64(bytes)) return true;
        }
        return false;
    }

    private static boolean isPrivateIpv4(byte[] bytes, int offset) {
        int a = bytes[offset] & 0xff;
        int b = bytes[offset + 1] & 0xff;
        return a == 0 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || a >= 224;
    }

    /** {@code ::ffff:0:0/96} — twelve zero bytes then the embedded IPv4 address. */
    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    /** NAT64 well-known translation prefix {@code 64:ff9b::/96} (RFC 6052). */
    private static boolean isNat64(byte[] bytes) {
        return bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == (byte) 0xff
                && bytes[3] == (byte) 0x9b && bytes[4] == 0 && bytes[5] == 0
                && bytes[6] == 0 && bytes[7] == 0;
    }

    private static String readAtMost(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxBytes) throw new IOException("response exceeds " + maxBytes + " bytes");
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
