package plugin.swisskit.hpl.util;

import com.alibaba.fastjson2.JSON;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Utility class for HTTP requests and cookie parsing.
 *
 * @author phoebej
 * @version 1.00
 * @Date 2025/10/23
 */
public abstract class WebUtil {

    // -------------------------  Cookie Utils  -------------------------

    public static String getValueFromCookie(String cookie, String key) {
        return parseCookie(cookie).get(key);
    }

    public static Map<String, String> parseCookie(String cookie) {
        Map<String, String> result = new HashMap<>();
        if (cookie == null || cookie.trim().isEmpty()) {
            return result;
        }

        String[] cookies = cookie.split("[;,]");
        for (String ck : cookies) {
            if (ck.trim().isEmpty()) {
                continue;
            }
            String[] kv = ck.trim().split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    // -------------------------  HTTP Client  -------------------------

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Send a GET request.
     *
     * @param baseUrl the base URL
     * @param uri     the request path (relative or absolute)
     * @param headers the request headers
     * @param params  the query parameters (supports multiple values)
     * @param tClass  the target class for response body deserialization
     */
    public static <T> T sendGetRequest(String baseUrl,
                                       String uri,
                                       Map<String, String> headers,
                                       Map<String, List<String>> params,
                                       Class<T> tClass) {
        URI fullUri = buildUri(baseUrl, uri, params);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(fullUri)
                .GET();

        if (headers != null) {
            headers.forEach((key, value) -> {
                // Skip restricted headers that HttpClient manages automatically
                if (key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("Content-Length")
                        || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Keep-Alive")
                        || key.equalsIgnoreCase("TE") || key.equalsIgnoreCase("Trailer")
                        || key.equalsIgnoreCase("Transfer-Encoding") || key.equalsIgnoreCase("Upgrade")) {
                    return;
                }
                requestBuilder.header(key, value);
            });
        }

        return execute(requestBuilder.build(), tClass);
    }

    /**
     * Send a POST request with JSON body (Content-Type: application/json).
     *
     * @param baseUrl     the base URL
     * @param uri         the request path
     * @param headers     the request headers (supports multiple values)
     * @param requestBody the request body object, serialized to JSON; sends empty body if null
     * @param tClass      the target class for response body deserialization
     */
    public static <T> T sendPostRequest(String baseUrl,
                                        String uri,
                                        Map<String, List<String>> headers,
                                        Object requestBody,
                                        Class<T> tClass) {
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

        if (requestBody != null) {
            String json = JSON.toJSONString(requestBody);  // fastjson2 serialization
            bodyPublisher = HttpRequest.BodyPublishers.ofString(json);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + uri))
                .header("Content-Type", "application/json")
                .POST(bodyPublisher);

        if (headers != null) {
            headers.forEach((key, values) -> {
                // Skip restricted headers that HttpClient manages automatically
                if (key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("Content-Length")
                        || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Keep-Alive")
                        || key.equalsIgnoreCase("TE") || key.equalsIgnoreCase("Trailer")
                        || key.equalsIgnoreCase("Transfer-Encoding") || key.equalsIgnoreCase("Upgrade")) {
                    return;
                }
                values.forEach(value -> requestBuilder.header(key, value));
            });
        }

        return execute(requestBuilder.build(), tClass);
    }

    /**
     * Send a POST request with form-urlencoded body (Content-Type: application/x-www-form-urlencoded).
     *
     * @param baseUrl    the base URL
     * @param uri        the request path
     * @param headers    the request headers (supports multiple values)
     * @param formParams the form fields (supports multiple values per key)
     * @param tClass     the target class for response body deserialization
     */
    public static <T> T sendFormPostRequest(String baseUrl,
                                            String uri,
                                            Map<String, List<String>> headers,
                                            Map<String, List<String>> formParams,
                                            Class<T> tClass) {
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

        if (formParams != null && !formParams.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            formParams.forEach((key, values) -> {
                for (String value : values) {
                    if (sb.length() > 0) sb.append("&");
                    sb.append(encode(key)).append("=").append(encode(value));
                }
            });
            bodyPublisher = HttpRequest.BodyPublishers.ofString(sb.toString());
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + uri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(bodyPublisher);

        if (headers != null) {
            headers.forEach((key, values) -> {
                // Skip restricted headers that HttpClient manages automatically
                if (key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("Content-Length")
                        || key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Keep-Alive")
                        || key.equalsIgnoreCase("TE") || key.equalsIgnoreCase("Trailer")
                        || key.equalsIgnoreCase("Transfer-Encoding") || key.equalsIgnoreCase("Upgrade")) {
                    return;
                }
                values.forEach(value -> requestBuilder.header(key, value));
            });
        }

        return execute(requestBuilder.build(), tClass);
    }

    // -------------------------  Private Methods  -------------------------

    private static <T> T execute(HttpRequest request, Class<T> tClass) {
        try {
            CompletableFuture<HttpResponse<byte[]>> future = HTTP_CLIENT.sendAsync(
                    request, HttpResponse.BodyHandlers.ofByteArray());

            HttpResponse<byte[]> response = future.get(30, TimeUnit.SECONDS);

            int statusCode = response.statusCode();
            if (statusCode >= 400 && statusCode < 500) {
                throw new RuntimeException("Client error, status code: " + statusCode);
            }
            if (statusCode >= 500) {
                throw new RuntimeException("Server error, status code: " + statusCode);
            }

            byte[] rawBody = response.body();
            String body = decompressIfGzip(rawBody, response.headers());
            if (tClass == String.class) {
                return tClass.cast(body);
            }
            // Set context classloader so fastjson2 can load DTO classes from plugin JAR
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(WebUtil.class.getClassLoader());
            try {
                return JSON.parseObject(body, tClass);  // fastjson2 deserialization
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }

        } catch (TimeoutException e) {
            throw new RuntimeException("HTTP request timeout after 30 seconds", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("HTTP request execution failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted", e);
        }
    }

    /**
     * Decompress gzip-encoded response body if Content-Encoding is gzip.
     * Falls back to raw bytes if not gzip or if decompression fails.
     */
    private static String decompressIfGzip(byte[] rawBody, HttpHeaders headers) {
        // Check Content-Encoding header for gzip
        String contentEncoding = headers.firstValue("Content-Encoding").orElse("");
        if (contentEncoding.equalsIgnoreCase("gzip")) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(rawBody))) {
                byte[] decompressed = gis.readAllBytes();
                return new String(decompressed, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed to decompress gzip response", e);
            }
        }
        // Not gzip, return as plain string
        return new String(rawBody, StandardCharsets.UTF_8);
    }

    private static URI buildUri(String baseUrl, String uri, Map<String, List<String>> params) {
        String base = uri.startsWith("http") ? uri : baseUrl + uri;

        if (params == null || params.isEmpty()) {
            return URI.create(base);
        }

        StringBuilder sb = new StringBuilder(base).append("?");
        params.forEach((key, values) -> {
            for (String value : values) {
                if (sb.charAt(sb.length() - 1) != '?') sb.append("&");
                sb.append(encode(key)).append("=").append(encode(value));
            }
        });

        return URI.create(sb.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}