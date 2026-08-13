package fan.summer.fengyu.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP client for the Electron-side browser bridge. Talks to the loopback
 * {@code POST /invoke} endpoint opened by {@code desktop/electron/src/browser/bridge.ts}.
 *
 * <p>One instance per {@link BrowserTool}; constructed with the bridge port + token that
 * Electron injects as env ({@code FENGYU_BROWSER_BRIDGE_PORT/TOKEN}).
 */
final class BrowserBridgeClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutorService COMPLETIONS = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("browser-bridge-", 0).factory());

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(COMPLETIONS)
            .build();
    private final int port;
    private final String token;

    BrowserBridgeClient(int port, String token) {
        this.port = port;
        this.token = token;
    }

    /** Factory used by BrowserTool: reads the bridge address from process env. */
    static BrowserBridgeClient fromEnv() {
        String portStr = System.getenv("FENGYU_BROWSER_BRIDGE_PORT");
        int port = portStr == null || portStr.isBlank() ? 0 : Integer.parseInt(portStr);
        String token = System.getenv("FENGYU_BROWSER_BRIDGE_TOKEN");
        if (port <= 0 || token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "FENGYU_BROWSER_BRIDGE_PORT/TOKEN env not set; browser bridge unavailable");
        }
        return new BrowserBridgeClient(port, token);
    }

    /**
     * Invoke a browser operation. Returns the parsed envelope from Electron.
     * @throws BrowserBridgeUnavailableException on connect/timeout/non-200/parse failure
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> invoke(String method, Map<String, Object> params, int timeoutSeconds) {
        CompletableFuture<Map<String, Object>> pending = invokeAsync(method, params, timeoutSeconds);
        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BrowserBridgeUnavailableException("browser bridge request interrupted", interrupted);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BrowserBridgeUnavailableException unavailable) throw unavailable;
            throw new BrowserBridgeUnavailableException(
                    "browser bridge request failed: " + safeMessage(cause), cause);
        }
    }

    /**
     * Non-blocking transport used by browser calls and batches. Spring's tool callback contract is
     * synchronous, so {@link #invoke} joins only at that boundary; HTTP I/O and response decoding
     * run asynchronously, with JSON work dispatched onto virtual threads.
     */
    @SuppressWarnings("unchecked")
    CompletableFuture<Map<String, Object>> invokeAsync(
            String method, Map<String, Object> params, int timeoutSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new BrowserBridgeUnavailableException("failed to serialize request", e);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/invoke"))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("X-Browser-Token", token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(resp -> {
            if (resp.statusCode() != 200) {
                throw new BrowserBridgeUnavailableException("bridge returned HTTP " + resp.statusCode());
            }
                    try {
                        return (Map<String, Object>) JSON.readValue(resp.body(), Map.class);
                    } catch (Exception e) {
                        throw new BrowserBridgeUnavailableException("failed to parse bridge response", e);
                    }
                }, COMPLETIONS)
                .orTimeout(Math.max(2, timeoutSeconds + 1L), java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    if (cause instanceof BrowserBridgeUnavailableException unavailable) throw unavailable;
                    throw new BrowserBridgeUnavailableException(
                            "browser bridge request failed: " + safeMessage(cause), cause);
                });
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
