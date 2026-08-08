package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.util.BaseUrlNormalizer;
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
     * Probe a cloud provider (openai / anthropic / deepseek). The base URL is first
     * normalized to the provider's official SDK contract via {@link BaseUrlNormalizer}
     * (OpenAI-compatible: ensure {@code /v1}; Anthropic: strip {@code /v1}), so the probe
     * hits the same endpoint {@code ChatModelConfig} builds for live chat. DeepSeek uses
     * the OpenAI-compatible {@code chat/completions} path.
     */
    public static TestResult testCloud(String mode, String endpoint, String apiKey, String model) {
        if (isBlank(endpoint) || isBlank(apiKey) || isBlank(model)) {
            return TestResult.fail("Endpoint, API key, and model are all required");
        }
        BaseUrlNormalizer.Provider provider = switch (mode) {
            case "openai", "deepseek" -> BaseUrlNormalizer.Provider.OPENAI_COMPATIBLE;
            case "anthropic"           -> BaseUrlNormalizer.Provider.ANTHROPIC;
            default                    -> null;
        };
        if (provider == null) return TestResult.fail("Unknown cloud mode: " + mode);
        // Normalize the base URL to the provider's SDK contract so the probe hits the same
        // endpoint the live chat client (ChatModelConfig) will use. This makes the test and
        // the real path agree on /v1 handling regardless of how the user typed the URL.
        String normalized = BaseUrlNormalizer.normalizeForSdk(endpoint, provider);
        String fixNote = BaseUrlNormalizer.describeFix(endpoint, normalized);
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build()) {
            TestResult result = switch (mode) {
                case "openai", "deepseek" -> testOpenAiCompatible(client, normalized, apiKey, model);
                case "anthropic"           -> testAnthropic(client, normalized, apiKey, model);
                default -> throw new IllegalStateException();  // guarded above
            };
            // Surface the auto-fix as a non-blocking warning on a successful probe so the
            // user can correct the stored setting; a failure already carries its own error.
            if (result.success() && fixNote != null) {
                return TestResult.okWithWarning(fixNote);
            }
            return result;
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
                    .uri(URI.create(base + "/chat/completions"))
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
                    .uri(URI.create(base + "/messages"))
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
