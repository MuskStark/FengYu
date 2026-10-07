package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.provider.Protocol;
import fan.summer.fengyu.ai.provider.ProviderDefinition;
import fan.summer.fengyu.ai.provider.ProviderRegistryService;
import fan.summer.fengyu.ai.config.RemoteModelCatalogService;
import fan.summer.fengyu.ai.service.AiModeService;
import fan.summer.fengyu.ai.service.BackendReactivator;
import fan.summer.fengyu.ai.service.ConnectionTester;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider registry REST surface ({@code /api/ai/providers}): providers as DATA —
 * list/create/update/delete/test/activate any OpenAI/Anthropic-compatible endpoint
 * with zero Java changes. API keys never leave the backend: GET shows a masked
 * placeholder plus {@code apiKeySet}, PUT skips masked placeholders, and the stored
 * cipher is decrypted only inside {@link ProviderRegistryService}.
 *
 * <p>Deletes are rejected for the built-in four ({@code openai}/{@code anthropic}/
 * {@code deepseek}/{@code ollama}); the legacy flat {@code /api/ai/config} surface
 * keeps working in parallel (built-ins mirror their endpoint/model there).
 */
@RestController
@RequestMapping("/api/ai/providers")
public class AiProviderController {

    private final ProviderRegistryService registry;
    private final BackendReactivator reactivator;
    private final AiModeService aiMode;
    private final RemoteModelCatalogService remoteCatalog;

    public AiProviderController(ProviderRegistryService registry,
                                BackendReactivator reactivator,
                                AiModeService aiMode,
                                RemoteModelCatalogService remoteCatalog) {
        this.registry = registry;
        this.reactivator = reactivator;
        this.aiMode = aiMode;
        this.remoteCatalog = remoteCatalog;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> providers = new ArrayList<>();
        String active = registry.activeProviderId();
        for (ProviderDefinition d : registry.list()) {
            Map<String, Object> view = view(d);
            view.put("active", d.id().equals(active));
            providers.add(view);
        }
        return Map.of("providers", providers, "activeProvider", active);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            ProviderDefinition created = registry.create(
                    text(body, "id"),
                    text(body, "displayName"),
                    protocol(body.get("protocol")),
                    text(body, "baseUrl"),
                    text(body, "model"),
                    text(body, "apiKey"),
                    headers(body.get("headers")));
            return ResponseEntity.ok(view(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            ProviderDefinition updated = registry.update(id,
                    text(body, "displayName"),
                    text(body, "baseUrl"),
                    text(body, "model"),
                    text(body, "apiKey"), // blank keeps the stored credential
                    headers(body.get("headers")));
            return ResponseEntity.ok(view(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            registry.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        try {
            ConnectionTester.TestResult result = registry.test(id);
            Map<String, Object> out = new HashMap<>();
            out.put("success", result.success());
            if (result.error() != null) out.put("error", result.error());
            if (result.warning() != null) out.put("warning", result.warning());
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable String id) {
        try {
            registry.activate(id);
            reactivator.reactivate();
            return ResponseEntity.ok(Map.of(
                    "activeProvider", id,
                    "ready", aiMode.getService().map(b -> b.isReady()).orElse(false)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Live model ids the provider's own endpoint reports (vendor {@code /models}
     * listing; Ollama {@code /api/tags}) — what the chat picker offers. Best-effort:
     * an unreachable vendor yields an empty list plus the failure note, never a 5xx.
     */
    @GetMapping("/{id}/models")
    public ResponseEntity<?> models(@PathVariable String id) {
        return registry.get(id)
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(Map.of(
                        "models", fan.summer.fengyu.ai.provider.ProviderModelsFetcher.fetch(
                                d, registry.resolveApiKey(id)))))
                .orElse(ResponseEntity.badRequest().body(Map.of("error", "unknown provider: " + id)));
    }

    // ── remote model catalog (C1/C2) ────────────────────────────────────────

    /** Best-effort refresh of the remote model-catalog overlay; never throws. */
    @PostMapping("/model-catalog/refresh")
    public Map<String, Object> refreshModelCatalog() {
        String outcome = remoteCatalog.refresh();
        return Map.of("result", outcome, "cachedRevision", remoteCatalog.cachedRevision());
    }

    @GetMapping("/model-catalog")
    public Map<String, Object> modelCatalogStatus() {
        return Map.of("cachedRevision", remoteCatalog.cachedRevision());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The wire view of one definition: never the key itself, only set + masked tail. */
    private Map<String, Object> view(ProviderDefinition d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id());
        m.put("displayName", d.displayName());
        m.put("protocol", d.protocol().name());
        m.put("baseUrl", d.baseUrl());
        m.put("model", d.model());
        m.put("apiKeySet", d.hasApiKey());
        m.put("apiKey", d.hasApiKey() ? mask(d.id()) : "");
        m.put("builtin", d.builtin());
        m.put("sort", d.sort());
        if (!d.headers().isEmpty()) m.put("headers", d.headers());
        return m;
    }

    private static String mask(String id) {
        return "•••• (" + id + ")";
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }

    private static Protocol protocol(Object v) {
        if (v instanceof String s) {
            try {
                return Protocol.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("protocol must be one of OPENAI_CHAT / ANTHROPIC_MESSAGES / OLLAMA");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Object v) {
        if (!(v instanceof Map<?, ?> raw) || raw.isEmpty()) return null;
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<Object, Object>) raw).entrySet()) {
            if (e.getKey() instanceof String k && e.getValue() instanceof String val) out.put(k, val);
        }
        return out;
    }
}
