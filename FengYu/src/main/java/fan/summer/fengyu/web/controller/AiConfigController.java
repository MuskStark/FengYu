package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.service.ConnectionTester;
import fan.summer.fengyu.ai.service.BackendReactivator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI provider configuration: mode, per-provider endpoint/apiKey/model, Ollama
 * settings, sampling params, system prompt. Backed by {@link AiConfigServiceHeadless}
 * (JPA-persisted, user-scoped) — mirrors {@link SettingsController}'s pattern.
 *
 * <ul>
 *   <li>{@code GET} returns a masked snapshot (API keys show {@code 前4***后4});
 *       also includes {@code activeMode} + {@code ready} from {@link AiModeService}.</li>
 *   <li>{@code PUT} accepts a partial JSON object, persists only present keys, then
 *       hot-swaps the backend via {@link BackendReactivator#reactivate()}.
 *       API-key values containing {@code ***} are treated as "unchanged" (skipped)
 *       so the masked placeholder round-trips safely.</li>
 *   <li>{@code POST /test} probes a provider with request-supplied (or DB-fallback)
 *       values via {@link ConnectionTester}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/config")
public class AiConfigController {

    private final AiModeService aiMode;
    private final BackendReactivator reactivator;

    public AiConfigController(AiModeService aiMode, BackendReactivator reactivator) {
        this.aiMode = aiMode;
        this.reactivator = reactivator;
    }

    // ── GET: masked snapshot ──────────────────────────────────────────

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("mode", AiConfigService.getAiMode());
        out.put("openai", providerMap(
                AiConfigService.getAiOpenAiEndpoint(),
                AiConfigService.getAiOpenAiApiKey(),
                AiConfigService.getAiOpenAiModel()));
        out.put("anthropic", providerMap(
                AiConfigService.getAiAnthropicEndpoint(),
                AiConfigService.getAiAnthropicApiKey(),
                AiConfigService.getAiAnthropicModel()));
        out.put("deepseek", providerMap(
                AiConfigService.getAiDeepSeekEndpoint(),
                AiConfigService.getAiDeepSeekApiKey(),
                AiConfigService.getAiDeepSeekModel()));
        out.put("ollama", Map.of(
                "baseUrl", AiConfigService.getAiOllamaBaseUrl(),
                "model", AiConfigService.getAiOllamaModel()));
        out.put("temperature", AiConfigService.getAiTemperature());
        out.put("topP", AiConfigService.getAiTopP());
        out.put("maxTokens", AiConfigService.getAiMaxTokens());
        out.put("maxToolRounds", AiConfigService.getAiMaxToolRounds());
        out.put("systemPrompt", AiConfigService.getAiSystemPrompt());
        out.put("activeMode", aiMode.getCurrentMode());
        out.put("ready", aiMode.getService().map(b -> b.isReady()).orElse(false));
        return out;
    }

    private Map<String, Object> providerMap(String endpoint, String apiKey, String model) {
        Map<String, Object> m = new HashMap<>();
        m.put("endpoint", endpoint);
        m.put("apiKey", maskKey(apiKey));
        m.put("apiKeySet", apiKey != null && !apiKey.isBlank());
        m.put("model", model);
        return m;
    }

    /** Masks a key as {@code 前4***后4}; empty/short keys return "". */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return key.substring(0, Math.min(4, key.length())) + "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    // ── PUT: partial write + hot-swap ─────────────────────────────────

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        if (aiMode.getService().map(fan.summer.fengyu.ai.ChatBackend::isGenerating).orElse(false)) {
            throw new IllegalStateException("Cannot change AI configuration while a generation is active");
        }
        if (body.get("mode") instanceof String m) {
            if (!List.of("local", "openai", "anthropic", "deepseek").contains(m)) {
                throw new IllegalArgumentException("Unsupported AI mode: " + m);
            }
            AiConfigServiceHeadless.setAiMode(m);
        }
        applyProvider(body, "openai",
                AiConfigServiceHeadless::setAiOpenAiEndpoint,
                AiConfigServiceHeadless::setAiOpenAiApiKey,
                AiConfigServiceHeadless::setAiOpenAiModel);
        applyProvider(body, "anthropic",
                AiConfigServiceHeadless::setAiAnthropicEndpoint,
                AiConfigServiceHeadless::setAiAnthropicApiKey,
                AiConfigServiceHeadless::setAiAnthropicModel);
        applyProvider(body, "deepseek",
                AiConfigServiceHeadless::setAiDeepSeekEndpoint,
                AiConfigServiceHeadless::setAiDeepSeekApiKey,
                AiConfigServiceHeadless::setAiDeepSeekModel);
        // Ollama
        Object ollama = body.get("ollama");
        if (ollama instanceof Map<?, ?> om) {
            if (om.get("baseUrl") instanceof String b) AiConfigServiceHeadless.setAiOllamaBaseUrl(b);
            if (om.get("model") instanceof String mo) AiConfigServiceHeadless.setAiOllamaModel(mo);
        }
        // Sampling params (parse quietly: a malformed string is ignored rather
        // than throwing NumberFormatException → 500; PUT always returns 200).
        Float temperature = parseFloatQuietly(body.get("temperature"));
        if (body.containsKey("temperature") && (temperature == null || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (temperature != null) AiConfigServiceHeadless.setAiTemperature(temperature);
        Float topP = parseFloatQuietly(body.get("topP"));
        if (body.containsKey("topP") && (topP == null || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        if (topP != null) AiConfigServiceHeadless.setAiTopP(topP);
        Integer maxTokens = parseIntQuietly(body.get("maxTokens"));
        if (body.containsKey("maxTokens") && (maxTokens == null || maxTokens < 1 || maxTokens > 1_000_000)) {
            throw new IllegalArgumentException("maxTokens must be between 1 and 1000000");
        }
        if (maxTokens != null) AiConfigServiceHeadless.setAiMaxTokens(maxTokens);
        Integer maxToolRounds = parseIntQuietly(body.get("maxToolRounds"));
        if (body.containsKey("maxToolRounds") && (maxToolRounds == null || maxToolRounds < 0 || maxToolRounds > 10_000)) {
            throw new IllegalArgumentException("maxToolRounds must be between 0 and 10000 (0 = unlimited)");
        }
        if (maxToolRounds != null) AiConfigServiceHeadless.setAiMaxToolRounds(maxToolRounds);
        if (body.get("systemPrompt") instanceof String sp) {
            AiConfigServiceHeadless.setAiSystemPrompt(sp);
        }

        // Hot-swap: rebuild backend from the just-persisted config.
        reactivator.reactivate();

        return get();
    }

    /**
     * Applies a provider sub-map ({@code {endpoint, apiKey, model}}). The
     * {@code apiKey} is skipped when it contains {@code ***} (masked placeholder
     * = "unchanged"); only a freshly-typed key is persisted.
     */
    private void applyProvider(Map<String, Object> body, String name,
                               Consumer<String> setEndpoint,
                               Consumer<String> setApiKey,
                               Consumer<String> setModel) {
        Object p = body.get(name);
        if (!(p instanceof Map<?, ?> pm)) return;
        if (pm.get("endpoint") instanceof String e) setEndpoint.accept(e);
        Object key = pm.get("apiKey");
        if (key instanceof String k && !k.isBlank() && !k.contains("***")) {
            setApiKey.accept(k);
        }
        if (pm.get("model") instanceof String mo) setModel.accept(mo);
    }

    /**
     * Parses a Number-or-String value as a Float, returning {@code null} if the
     * input is missing, null, or a non-numeric string. Never throws — a malformed
     * value is silently ignored so PUT can still return 200.
     */
    private static Float parseFloatQuietly(Object v) {
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * Parses a Number-or-String value as an Integer, returning {@code null} if the
     * input is missing, null, or a non-numeric string. Never throws — see
     * {@link #parseFloatQuietly(Object)}.
     */
    private static Integer parseIntQuietly(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    // ── POST /test: connection probe ──────────────────────────────────

    public record TestRequest(String mode, String endpoint, String apiKey,
                              String model, String baseUrl) {}

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody TestRequest req) {
        String mode = req.mode() != null ? req.mode() : AiConfigService.getAiMode();
        ConnectionTester.TestResult result;
        if ("local".equals(mode)) {
            String baseUrl = orDefault(req.baseUrl(), AiConfigService.getAiOllamaBaseUrl());
            String model = orDefault(req.model(), AiConfigService.getAiOllamaModel());
            result = ConnectionTester.testOllama(baseUrl, model);
        } else {
            String endpoint = orDefault(req.endpoint(), endpointFor(mode));
            String requestedKey = req.apiKey();
            String apiKey = requestedKey != null && requestedKey.contains("***")
                    ? apiKeyFor(mode) : orDefault(requestedKey, apiKeyFor(mode));
            String model = orDefault(req.model(), modelFor(mode));
            result = ConnectionTester.testCloud(mode, endpoint, apiKey, model);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("success", result.success());
        if (result.error() != null) out.put("error", result.error());
        if (result.warning() != null) out.put("warning", result.warning());
        return out;
    }

    private static String orDefault(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static String endpointFor(String mode) {
        return switch (mode) {
            case "openai" -> AiConfigService.getAiOpenAiEndpoint();
            case "anthropic" -> AiConfigService.getAiAnthropicEndpoint();
            case "deepseek" -> AiConfigService.getAiDeepSeekEndpoint();
            default -> "";
        };
    }

    private static String apiKeyFor(String mode) {
        return switch (mode) {
            case "openai" -> AiConfigService.getAiOpenAiApiKey();
            case "anthropic" -> AiConfigService.getAiAnthropicApiKey();
            case "deepseek" -> AiConfigService.getAiDeepSeekApiKey();
            default -> "";
        };
    }

    private static String modelFor(String mode) {
        return switch (mode) {
            case "openai" -> AiConfigService.getAiOpenAiModel();
            case "anthropic" -> AiConfigService.getAiAnthropicModel();
            case "deepseek" -> AiConfigService.getAiDeepSeekModel();
            default -> "";
        };
    }
}
