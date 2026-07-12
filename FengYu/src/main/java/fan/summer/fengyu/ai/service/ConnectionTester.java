package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.util.JsonHelper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Pure (no Spring) connection-test utility for AI backends. Extracted from
 * {@code SpringAiCloudBackend.testOpenAi/testAnthropic} and
 * {@code OllamaLocalBackend.probeReachable} so the controller's {@code /test}
 * endpoint can probe with request-supplied values without building a full
 * {@code ChatModel} or activating a backend.
 *
 * <p>All methods return a {@link TestResult}; {@code success} reflects reachability.
 * For Ollama, a reachable server with a missing model returns {@code success=true}
 * + a {@code warning} (the user should {@code ollama pull}).
 */
public final class ConnectionTester {

    private ConnectionTester() {}

    /** Result of a connection probe. {@code error}/{@code warning} are null when not applicable. */
    public record TestResult(boolean success, String error, String warning) {
        public static TestResult ok() { return new TestResult(true, null, null); }
        public static TestResult okWithWarning(String w) { return new TestResult(true, null, w); }
        public static TestResult fail(String err) { return new TestResult(false, err, null); }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Probe a cloud provider (openai / anthropic / deepseek). DeepSeek uses the
     * OpenAI-compatible {@code /v1/chat/completions} path.
     */
    public static TestResult testCloud(String mode, String endpoint, String apiKey, String model) {
        if (isBlank(endpoint) || isBlank(apiKey) || isBlank(model)) {
            return TestResult.fail("Endpoint, API key, and model are all required");
        }
        String base = stripTrailingSlash(endpoint);
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {
            return switch (mode) {
                case "openai", "deepseek" -> testOpenAiCompatible(client, base, apiKey, model);
                case "anthropic"           -> testAnthropic(client, base, apiKey, model);
                default -> TestResult.fail("Unknown cloud mode: " + mode);
            };
        } catch (Exception e) {
            return TestResult.fail(errMsg(e));
        }
    }

    /** Probe an Ollama server via {@code GET {base}/api/tags}. */
    public static TestResult testOllama(String baseUrl, String model) {
        if (isBlank(baseUrl)) return TestResult.fail("Ollama base URL is required");
        String base = stripTrailingSlash(baseUrl);
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/tags"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return TestResult.fail("HTTP " + resp.statusCode() + ": " + resp.body());
            }
            // Check whether the requested model is already pulled.
            if (!isBlank(model)) {
                String warning = checkModelPulled(resp.body(), model);
                if (warning != null) return TestResult.okWithWarning(warning);
            }
            return TestResult.ok();
        } catch (Exception e) {
            return TestResult.fail(errMsg(e));
        }
    }

    // ── Cloud probes ──────────────────────────────────────────────────

    private static TestResult testOpenAiCompatible(HttpClient client, String base,
                                                    String apiKey, String model) {
        String body = JsonHelper.toJson(Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", "Hi")),
            "max_tokens", 5
        ));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return TestResult.ok();
            return TestResult.fail("HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return TestResult.fail(errMsg(e));
        }
    }

    private static TestResult testAnthropic(HttpClient client, String base,
                                             String apiKey, String model) {
        String body = JsonHelper.toJson(Map.of(
            "model", model,
            "max_tokens", 5,
            "messages", List.of(Map.of("role", "user", "content", "Hi"))
        ));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return TestResult.ok();
            return TestResult.fail("HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            return TestResult.fail(errMsg(e));
        }
    }

    // ── Ollama model-presence check ───────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String checkModelPulled(String tagsJson, String model) {
        Map<String, Object> parsed = JsonHelper.parseObject(tagsJson);
        Object models = parsed.get("models");
        if (models instanceof List<?> list) {
            for (Object m : list) {
                if (m instanceof Map<?, ?> mm) {
                    Object name = mm.get("name");
                    if (name instanceof String s && (s.equals(model) || s.startsWith(model + ":"))) {
                        return null;  // found
                    }
                }
            }
        }
        return "model '" + model + "' not found locally; run: ollama pull " + model;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String errMsg(Exception e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName() + ": " + e;
    }
}
