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
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return a == 0 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || a >= 224;
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
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
