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
    /**
     * Provider registry (nullable: focused tests build the controller without one).
     * Present in production, the deprecated flat provider writes are mirrored into
     * the registry so both surfaces stay consistent; null keeps the flat-only path.
     */
    private final fan.summer.fengyu.ai.provider.ProviderRegistryService providerRegistry;

    public AiConfigController(AiModeService aiMode, BackendReactivator reactivator) {
        this(aiMode, reactivator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiConfigController(AiModeService aiMode, BackendReactivator reactivator,
            fan.summer.fengyu.ai.provider.ProviderRegistryService providerRegistry) {
        this.aiMode = aiMode;
        this.reactivator = reactivator;
        this.providerRegistry = providerRegistry;
    }

    // ── GET: masked snapshot ──────────────────────────────────────────

    @GetMapping
    public Map<String, Object> get() {
        // First user-scoped read heals legacy frozen-default overrides (see
        // AiConfigServiceHeadless.healLegacyDefaultOverridesIfNeeded); a no-op
        // afterwards, guarded by a per-user marker setting.
        AiConfigServiceHeadless.healLegacyDefaultOverridesIfNeeded();
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
        // The form shows the EFFECTIVE budget (explicit override, else the active model's
        // catalog cap, else the flat default) — the same number the request builders send,
        // so what the user sees and saves is what the wire actually uses.
        out.put("maxTokens",
                AiConfigService.effectiveMaxOutputTokens(AiConfigService.activeModelId()));
        out.put("maxToolRounds", AiConfigService.getAiMaxToolRounds());
        // Same effective-value treatment as maxTokens above: the form shows what the loop
        // actually compacts against for the active model (0 stays 0 — compaction off).
        out.put("contextWindowTokens",
                AiConfigService.effectiveContextWindowTokens(AiConfigService.activeModelId()));
        out.put("toolLoadingMode", AiConfigService.getAiToolLoadingMode());
        out.put("toolLoadingThreshold", AiConfigService.getAiToolLoadingThreshold());
        out.put("sandboxMode", AiConfigService.getAiSandboxMode());
        out.put("sandboxExtraWritableRoots", AiConfigService.getAiSandboxExtraWritableRoots());
        out.put("sandboxNetwork", AiConfigService.getAiSandboxNetwork());
        out.put("codeModeEnabled", AiConfigService.getAiCodeModeEnabled());
        out.put("systemPrompt", AiConfigService.getAiSystemPrompt());
        // Thinking control (catalog-driven): the configured level plus the active
        // model's supported levels so the UI renders exactly the knobs that exist.
        out.put("thinkingLevel", AiConfigServiceHeadless.getAiThinkingLevel());
        String activeModel = aiMode.getService()
                .flatMap(fan.summer.fengyu.ai.ChatBackend::getModelName).orElse(null);
        fan.summer.fengyu.ai.config.ModelMetadataCatalog.thinkingFor(activeModel)
                .ifPresentOrElse(
                        spec -> out.put("thinkingLevels", spec.levels()),
                        () -> out.put("thinkingLevels", java.util.List.of("off")));
        out.put("activeMode", aiMode.getCurrentMode());
        out.put("ready", aiMode.getService().map(b -> b.isReady()).orElse(false));
        return out;
    }

    /** Mirrors one flat provider sub-map write into the registry builtin (best-effort). */
    private void mirrorProviderIntoRegistry(Map<String, Object> body, String id) {
        if (!(body.get(id) instanceof Map<?, ?> pm)) return;
        try {
            String baseUrl = pm.get("baseUrl") instanceof String b ? b : null;
            String model = pm.get("model") instanceof String m ? m : null;
            String apiKey = pm.get("apiKey") instanceof String k && !k.contains("*") ? k : null;
            if ("ollama".equals(id)) {
                providerRegistry.update(id, null, baseUrl, model, null, null);
            } else {
                providerRegistry.update(id, null, baseUrl, model, apiKey, null);
            }
        } catch (IllegalArgumentException ignored) { }
    }

    private Map<String, Object> providerMap(String endpoint, String apiKey, String model) {
        Map<String, Object> m = new HashMap<>();
        m.put("endpoint", endpoint);
        m.put("apiKey", maskKey(apiKey));
        m.put("apiKeySet", apiKey != null && !apiKey.isBlank());
        m.put("model", model);
        return m;
    }

    /**
     * Masks a key as {@code first4***last4}. Keys up to 12 characters return only {@code ***} —
     * revealing a 4-char prefix of a short key would hand out half (or more) of its entropy (D3).
     */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 12) return "***";
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
            if (providerRegistry != null) {
                try { providerRegistry.activate(switch (m) {
                    case "openai" -> "openai";
                    case "anthropic" -> "anthropic";
                    case "deepseek" -> "deepseek";
                    default -> "ollama";
                }); } catch (IllegalArgumentException ignored) { }
            }
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
        // Deprecated-surface sync: provider sub-maps that were just written flat are
        // mirrored into the registry (which mirrors endpoint/model back — idempotent)
        // so the two surfaces can never diverge for the built-ins.
        if (providerRegistry != null) {
            mirrorProviderIntoRegistry(body, "openai");
            mirrorProviderIntoRegistry(body, "anthropic");
            mirrorProviderIntoRegistry(body, "deepseek");
            mirrorProviderIntoRegistry(body, "ollama");
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
        Integer contextWindowTokens = parseIntQuietly(body.get("contextWindowTokens"));
        if (body.containsKey("contextWindowTokens")
                && (contextWindowTokens == null || (contextWindowTokens != 0
                && (contextWindowTokens < 4_096 || contextWindowTokens > 2_000_000)))) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be 0 or between 4096 and 2000000");
        }
        if (contextWindowTokens != null) {
            AiConfigServiceHeadless.setAiContextWindowTokens(contextWindowTokens);
        }
        if (body.get("toolLoadingMode") instanceof String tlm) {
            String normalizedMode = tlm.trim().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("auto", "always", "off").contains(normalizedMode)) {
                throw new IllegalArgumentException("toolLoadingMode must be auto, always, or off");
            }
            AiConfigServiceHeadless.setAiToolLoadingMode(normalizedMode);
        }
        Integer toolLoadingThreshold = parseIntQuietly(body.get("toolLoadingThreshold"));
        if (body.containsKey("toolLoadingThreshold")
                && (toolLoadingThreshold == null || toolLoadingThreshold < 5 || toolLoadingThreshold > 500)) {
            throw new IllegalArgumentException("toolLoadingThreshold must be between 5 and 500");
        }
        if (toolLoadingThreshold != null) {
            AiConfigServiceHeadless.setAiToolLoadingThreshold(toolLoadingThreshold);
        }
        if (body.get("thinkingLevel") instanceof String tl) {
            String activeModel = aiMode.getService()
                    .flatMap(fan.summer.fengyu.ai.ChatBackend::getModelName).orElse(null);
            var spec = fan.summer.fengyu.ai.config.ModelMetadataCatalog.thinkingFor(activeModel)
                    .orElse(new fan.summer.fengyu.ai.config.ModelMetadataCatalog.ThinkingSpec(
                            "none", java.util.List.of("off"), "off"));
            String normalized = tl.trim();
            if (!spec.levels().contains(normalized)) {
                throw new IllegalArgumentException(
                        "thinkingLevel must be one of " + spec.levels() + " for the active model");
            }
            AiConfigServiceHeadless.setAiThinkingLevel(normalized);
        }
        if (body.get("systemPrompt") instanceof String sp) {
            AiConfigServiceHeadless.setAiSystemPrompt(sp);
        }
        if (body.get("sandboxMode") instanceof String sm) {
            if (!List.of("off", "read-only", "workspace-write").contains(sm)) {
                throw new IllegalArgumentException("sandboxMode must be off, read-only, or workspace-write");
            }
            AiConfigServiceHeadless.setAiSandboxMode(sm);
        }
        if (body.get("sandboxExtraWritableRoots") instanceof String roots) {
            AiConfigServiceHeadless.setAiSandboxExtraWritableRoots(
                    normalizeSandboxWritableRoots(roots));
        }
        if (body.get("sandboxNetwork") instanceof String sn) {
            if (!List.of("denied", "open").contains(sn)) {
                throw new IllegalArgumentException("sandboxNetwork must be denied or open");
            }
            AiConfigServiceHeadless.setAiSandboxNetwork(sn);
        }
        if (body.get("codeModeEnabled") instanceof Boolean cm) {
            AiConfigServiceHeadless.setAiCodeModeEnabled(cm);
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

    /**
     * Normalize + validate the sandbox extra writable roots before persisting: these roots
     * WIDEN every fenced tier, so "/" or the home directory would silently void the
     * workspace-write fence, and relative entries would resolve against whatever cwd the
     * backend happens to run in (P3 fix). Blank entries are dropped.
     */
    private static String normalizeSandboxWritableRoots(String raw) {
        java.util.List<String> normalized = new java.util.ArrayList<>();
        java.nio.file.Path home = java.nio.file.Path.of(
                System.getProperty("user.home", ""));
        for (String entry : raw.split(",")) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) continue;
            java.nio.file.Path candidate;
            try {
                candidate = java.nio.file.Path.of(trimmed);
            } catch (java.nio.file.InvalidPathException e) {
                throw new IllegalArgumentException(
                        "sandboxExtraWritableRoots entry is not a valid path: " + trimmed);
            }
            if (!candidate.isAbsolute()) {
                throw new IllegalArgumentException(
                        "sandboxExtraWritableRoots entries must be absolute: " + trimmed);
            }
            java.nio.file.Path collapsed = candidate.normalize();
            if (collapsed.equals(java.nio.file.Path.of("/")) || collapsed.equals(home)) {
                throw new IllegalArgumentException(
                        "sandboxExtraWritableRoots may not widen the fence to " + trimmed
                                + " — add the specific directories the agent needs");
            }
            normalized.add(trimmed);
        }
        return String.join(",", normalized);
    }
}
