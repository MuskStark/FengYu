package fan.summer.fengyu.ai.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.service.ConnectionTester;
import fan.summer.fengyu.database.entity.AppSettingEntity;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.SecurityContext;
import fan.summer.fengyu.setup.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The provider registry: provider instances as DATA (any OpenAI/Anthropic-compatible
 * endpoint), persisted as one JSON setting ({@code ai.provider.instances}) per user —
 * the same storage pattern as the permission-rule table and hook list. API keys ride
 * inside the JSON in {@link CryptoUtil}'s machine-bound {@code ENC(...)} envelope and
 * never leave this service unmasked.
 *
 * <p><b>Read path never mutates.</b> Until the first registry write the effective
 * instance list is derived on the fly from the legacy flat keys
 * ({@code ai.openai.*} etc.), so an upgraded install keeps working with zero startup
 * migration risk. The first registry write (create/update/delete/activate) snapshots
 * the current legacy values into the registry and blanks the legacy API-key settings
 * (endpoint/model keep mirroring for the built-ins, keeping {@code GET /api/ai/config}
 * truthful and a 4.0.x rollback functional after re-entering one key).
 *
 * <p>The built-in four ({@code openai}/{@code anthropic}/{@code deepseek}/{@code ollama})
 * are updatable but not deletable; user ids are validated slugs.
 */
@Component
public class ProviderRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);

    static final String REGISTRY_KEY = "ai.provider.instances";
    static final String ACTIVE_KEY = "ai.provider.active";

    private static final Pattern USER_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{1,39}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppSettingRepository appSettingRepo;
    private final SecurityContext securityContext;

    public ProviderRegistryService(AppSettingRepository appSettingRepo, SecurityContext securityContext) {
        this.appSettingRepo = appSettingRepo;
        this.securityContext = securityContext;
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    /** All effective instances (persisted registry, or the legacy-derived view). */
    public List<ProviderDefinition> list() {
        return persisted().orElseGet(this::deriveFromLegacy);
    }

    public Optional<ProviderDefinition> get(String id) {
        return list().stream().filter(d -> d.id().equals(id)).findFirst();
    }

    /** The active provider id: registry key first, legacy {@code ai.mode} mapping as fallback. */
    public String activeProviderId() {
        String active = readRaw(ACTIVE_KEY);
        if (active != null && !active.isBlank()) return active;
        return legacyModeToId(AiConfigService.getAiMode());
    }

    public Optional<ProviderDefinition> activeProvider() {
        return get(activeProviderId());
    }

    /** The decrypted API key of one instance ("" when unset; a damaged cipher degrades to unset). */
    public String resolveApiKey(String id) {
        return get(id).map(this::decryptedKey).orElse("");
    }

    // ── writes (each persists the registry; the first one migrates) ───────────

    /** Creates a user instance. {@code id} is a validated slug; builtin ids are reserved. */
    public synchronized ProviderDefinition create(String id, String displayName, Protocol protocol,
            String baseUrl, String model, String apiKey, Map<String, String> headers) {
        String normalizedId = id == null ? "" : id.trim().toLowerCase();
        if (!USER_ID_PATTERN.matcher(normalizedId).matches()) {
            throw new IllegalArgumentException("provider id must be a lowercase slug (a-z, 0-9, -, 2-40 chars)");
        }
        List<ProviderDefinition> instances = new ArrayList<>(list());
        if (instances.stream().anyMatch(d -> d.id().equals(normalizedId))) {
            throw new IllegalArgumentException("provider id already exists: " + normalizedId);
        }
        ProviderDefinition created = new ProviderDefinition(normalizedId,
                displayName == null || displayName.isBlank() ? normalizedId : displayName.trim(),
                protocol, requireUrl(baseUrl), model == null ? "" : model.trim(),
                encryptKey(apiKey), headers, false, instances.size());
        instances.add(created);
        persist(instances);
        return created;
    }

    /**
     * Updates an instance. Blank {@code apiKey} keeps the stored credential; a
     * non-blank value replaces it. Built-ins mirror endpoint/model (and a changed
     * key) into the legacy flat settings so the deprecated flat API stays truthful.
     */
    public synchronized ProviderDefinition update(String id, String displayName, String baseUrl,
            String model, String apiKey, Map<String, String> headers) {
        List<ProviderDefinition> instances = new ArrayList<>(list());
        for (int i = 0; i < instances.size(); i++) {
            ProviderDefinition d = instances.get(i);
            if (!d.id().equals(id)) continue;
            // Masked placeholders round-trip from GET (\u2022\u2022\u2022\u2022 style) must never
            // become the stored credential — same skip rule as the legacy '*' placeholders.
            String trimmedKey = apiKey == null ? "" : apiKey.trim();
            boolean maskedPlaceholder = trimmedKey.contains("\u2022") || trimmedKey.contains("***");
            String nextKey = trimmedKey.isBlank() || maskedPlaceholder
                    ? d.apiKeyCipher() : encryptKey(trimmedKey);
            ProviderDefinition updated = new ProviderDefinition(d.id(),
                    displayName == null || displayName.isBlank() ? d.displayName() : displayName.trim(),
                    d.protocol(), baseUrl == null || baseUrl.isBlank() ? d.baseUrl() : requireUrl(baseUrl),
                    model == null || model.isBlank() ? d.model() : model.trim(),
                    nextKey, headers == null ? d.headers() : headers, d.builtin(), d.sort());
            instances.set(i, updated);
            persist(instances);
            mirrorLegacy(updated);
            return updated;
        }
        throw new IllegalArgumentException("unknown provider: " + id);
    }

    /** Deletes a user instance (built-ins are rejected). */
    public synchronized void delete(String id) {
        ProviderDefinition target = get(id).orElseThrow(() ->
                new IllegalArgumentException("unknown provider: " + id));
        if (target.builtin()) {
            throw new IllegalArgumentException("built-in providers cannot be deleted");
        }
        persist(list().stream().filter(d -> !d.id().equals(id)).toList());
    }

    /** Activates an instance and mirrors {@code ai.mode} for 4.0.x rollback compatibility. */
    public synchronized void activate(String id) {
        ProviderDefinition target = get(id).orElseThrow(() ->
                new IllegalArgumentException("unknown provider: " + id));
        List<ProviderDefinition> instances = list();
        persist(instances); // first-touch migration snapshot if not yet persisted
        writeSetting(ACTIVE_KEY, id);
        AiConfigServiceHeadless.setAiMode(protocolToLegacyMode(target));
    }

    /** Connection probe through the instance's protocol. Returns null error on success. */
    public ConnectionTester.TestResult test(String id) {
        ProviderDefinition d = get(id).orElseThrow(() ->
                new IllegalArgumentException("unknown provider: " + id));
        String key = decryptedKey(d);
        return switch (d.protocol()) {
            case OPENAI_CHAT -> ConnectionTester.testCloud("openai", d.baseUrl(), key, d.model());
            case ANTHROPIC_MESSAGES -> ConnectionTester.testCloud("anthropic", d.baseUrl(), key, d.model());
            case OLLAMA -> ConnectionTester.testOllama(d.baseUrl(), d.model());
        };
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private Optional<List<ProviderDefinition>> persisted() {
        String raw = readRaw(REGISTRY_KEY);
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            List<ProviderDefinition> parsed = JSON.readValue(raw, new TypeReference<>() {});
            return Optional.of(List.copyOf(parsed));
        } catch (Exception e) {
            log.warn("Provider registry JSON unreadable; using the legacy-derived view: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Persists the instance list. On the very first persistence the caller's list came
     * from {@link #list()} — i.e. the legacy-derived snapshot — so this IS the legacy
     * migration; the legacy API-key settings are blanked right after the snapshot lands.
     * Rollback note (audit R2-P2): once the registry is persisted the read path never
     * consults the legacy keys again — a 4.0.x rollback that rotates the key is NOT
     * re-imported automatically (only never-migrated installs derive from legacy).
     * Re-entering the key after upgrading happens through the registry UI/PUT.
     */
    private void persist(List<ProviderDefinition> instances) {
        boolean firstPersist = readRaw(REGISTRY_KEY) == null || readRaw(REGISTRY_KEY).isBlank();
        try {
            writeSetting(REGISTRY_KEY, JSON.writeValueAsString(instances));
        } catch (Exception e) {
            throw new IllegalStateException("provider registry could not be persisted", e);
        }
        // writeSetting swallows storage failures, so CONFIRM the registry row before
        // blanking the legacy keys — a failed snapshot must never discard them (R3-P2).
        String confirmed = readRaw(REGISTRY_KEY);
        if (firstPersist && confirmed != null && !confirmed.isBlank()) {
            // The snapshot above captured the legacy values; blank only the secrets.
            // Endpoint/model stay as read-only mirrors (mirrorLegacy keeps them fresh).
            writeSetting("ai.openai.api_key", "");
            writeSetting("ai.anthropic.api_key", "");
            writeSetting("ai.deepseek.api_key", "");
            log.info("Migrated legacy AI provider settings into the registry (legacy API keys blanked)");
        }
    }

    /** The pre-migration view: the four built-ins derived from the legacy flat keys. */
    private List<ProviderDefinition> deriveFromLegacy() {
        return List.of(
                new ProviderDefinition("openai", "OpenAI", Protocol.OPENAI_CHAT,
                        AiConfigService.getAiOpenAiEndpoint(), AiConfigService.getAiOpenAiModel(),
                        encryptKey(AiConfigService.getAiOpenAiApiKey()), Map.of(), true, 0),
                new ProviderDefinition("anthropic", "Anthropic", Protocol.ANTHROPIC_MESSAGES,
                        AiConfigService.getAiAnthropicEndpoint(), AiConfigService.getAiAnthropicModel(),
                        encryptKey(AiConfigService.getAiAnthropicApiKey()), Map.of(), true, 1),
                new ProviderDefinition("deepseek", "DeepSeek", Protocol.OPENAI_CHAT,
                        AiConfigService.getAiDeepSeekEndpoint(), AiConfigService.getAiDeepSeekModel(),
                        encryptKey(AiConfigService.getAiDeepSeekApiKey()), Map.of(), true, 2),
                new ProviderDefinition("ollama", "Ollama (local)", Protocol.OLLAMA,
                        AiConfigService.getAiOllamaBaseUrl(),
                        AiConfigService.getAiOllamaModel(), "", Map.of(), true, 3));
    }

    /** Keeps the legacy flat keys truthful for built-ins (deprecated flat API + rollback). */
    private void mirrorLegacy(ProviderDefinition d) {
        if (!d.builtin()) return;
        switch (d.id()) {
            case "openai" -> {
                AiConfigServiceHeadless.setAiOpenAiEndpoint(d.baseUrl());
                AiConfigServiceHeadless.setAiOpenAiModel(d.model());
                if (d.hasApiKey()) AiConfigServiceHeadless.setAiOpenAiApiKey(decryptedKey(d));
            }
            case "anthropic" -> {
                AiConfigServiceHeadless.setAiAnthropicEndpoint(d.baseUrl());
                AiConfigServiceHeadless.setAiAnthropicModel(d.model());
                if (d.hasApiKey()) AiConfigServiceHeadless.setAiAnthropicApiKey(decryptedKey(d));
            }
            case "deepseek" -> {
                AiConfigServiceHeadless.setAiDeepSeekEndpoint(d.baseUrl());
                AiConfigServiceHeadless.setAiDeepSeekModel(d.model());
                if (d.hasApiKey()) AiConfigServiceHeadless.setAiDeepSeekApiKey(decryptedKey(d));
            }
            case "ollama" -> {
                AiConfigServiceHeadless.setAiOllamaBaseUrl(d.baseUrl());
                AiConfigServiceHeadless.setAiOllamaModel(d.model());
            }
            default -> { }
        }
    }

    // ── settings plumbing ─────────────────────────────────────────────────────

    private String readRaw(String key) {
        try {
            Long uid = securityContext.currentUserId();
            return appSettingRepo.findByUserIdAndSettingKey(uid, key)
                    .map(AppSettingEntity::getSettingValue)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not read setting '{}': {}", key, e.toString());
            return null;
        }
    }

    private void writeSetting(String key, String value) {
        try {
            Long uid = securityContext.currentUserId();
            AppSettingEntity entity = appSettingRepo.findByUserIdAndSettingKey(uid, key)
                    .orElseGet(() -> {
                        AppSettingEntity fresh = new AppSettingEntity();
                        fresh.setUserId(uid);
                        fresh.setSettingKey(key);
                        return fresh;
                    });
            entity.setSettingValue(value == null ? "" : value);
            appSettingRepo.save(entity);
        } catch (Exception e) {
            log.warn("Could not write setting '{}': {}", key, e.toString());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String decryptedKey(ProviderDefinition d) {
        if (!d.hasApiKey()) return "";
        try {
            return CryptoUtil.decrypt(d.apiKeyCipher());
        } catch (Exception e) {
            log.warn("API key for provider '{}' could not be decrypted (machine change?); treating as unset",
                    d.id());
            return "";
        }
    }

    private String encryptKey(String plain) {
        if (plain == null || plain.isBlank()) return "";
        try {
            return CryptoUtil.encrypt(plain);
        } catch (Exception e) {
            log.warn("API key could not be encrypted; storing empty: {}", e.toString());
            return "";
        }
    }

    private static String requireUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String trimmed = baseUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl must start with http:// or https://");
        }
        return trimmed;
    }

    private static String legacyModeToId(String mode) {
        return switch (mode == null ? "local" : mode) {
            case "openai" -> "openai";
            case "anthropic" -> "anthropic";
            case "deepseek" -> "deepseek";
            default -> "ollama";
        };
    }

    private static String protocolToLegacyMode(ProviderDefinition d) {
        if (d.builtin()) {
            return switch (d.id()) {
                case "openai" -> "openai";
                case "anthropic" -> "anthropic";
                case "deepseek" -> "deepseek";
                default -> "local";
            };
        }
        return switch (d.protocol()) {
            case OPENAI_CHAT -> "openai";
            case ANTHROPIC_MESSAGES -> "anthropic";
            case OLLAMA -> "local";
        };
    }
}
