package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.database.entity.AppSettingEntity;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Headless AI/UI configuration service. Wraps read-only {@link AiConfigService} reads and provides
 * write methods that persist via {@link AppSettingRepository}. Converted from a pure-static MyBatis
 * utility to a {@code @Component} for DI; all operations are user-scoped via {@link SecurityContext}.
 *
 * <p><b>Static facade:</b> the public static call surface is retained as thin delegates to the
 * Spring-managed singleton (set in {@link #init()}), so non-bean callers (the AI backends built via
 * static factories, and {@code AiController}) keep compiling unchanged. The data path now goes
 * through JPA + SecurityContext via the instance. This is the plan's documented static-facade-holder
 * fallback. Java forbids static + instance methods with the same signature in one class, so the
 * instance helpers that use the injected repo are private.
 *
 * <p>Setting keys are kept identical to {@link AiConfigService}'s read keys so that a value
 * written here round-trips through the read path (e.g. {@code ai.top_p}, not {@code ai.topP}).
 */
@Component
public class AiConfigServiceHeadless {

    /** Spring-managed singleton, populated in {@link #init()}. Volatile for cross-thread reads. */
    private static volatile AiConfigServiceHeadless INSTANCE;

    // Keys — MUST match AiConfigService read keys exactly.
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY       = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY  = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String THEME_KEY    = "theme";
    private static final String LANGUAGE_KEY = "language";
    private static final String SIDEBAR_COLLAPSED_KEY = "sidebar.collapsed";
    private static final String LOG_LEVEL_KEY = "logging.level";
    private static final String PLUGIN_UNSANDBOXED_KEY = "plugin.unsandboxed";
    /** Update-channel proxy base (e.g. {@code http://10.0.0.5:8088}). Empty → default GitHub feed. */
    private static final String UPDATE_API_BASE_KEY = "update.api_base";
    /**
     * Store SSRF escape hatch ({@code fengyu.store.allow-private-network} mirrored into the
     * persisted settings so the Settings UI can flip it live): permits private-network store
     * targets and plain HTTP towards them — the self-hosted intranet/cross-site store
     * deployment that rarely carries a CA-signed certificate.
     */
    private static final String STORE_ALLOW_PRIVATE_NETWORK_KEY = "store.allow_private_network";
    /** User permission-rule table: {@code {"allow":[…],"ask":[…],"deny":[…]}} rule strings. */
    private static final String AI_PERMISSION_RULES_KEY = "ai.permission_rules";
    /** User hook list: {@code [{"name","event","matcher","type","command"/"url","timeoutSeconds","enabled"}]}. */
    private static final String AI_HOOKS_KEY = "ai.hooks";
    /** When true, plugin uploads must carry a matching .sha256 sidecar (supply-chain hardening). */
    private static final String MARKETPLACE_REQUIRE_CHECKSUM_KEY = "marketplace.require_checksum";
    /**
     * Master switch for the {@code computer_*} screen-control tools (ChatGPT-desktop-style
     * computer use). Default {@code true}: the desktop build ships the capability on, and every
     * input-injecting call still passes the tool approval gate. Null-safe against an
     * uninitialized {@link #INSTANCE} (pure unit tests) like the unsandboxed toggle.
     */
    private static final String COMPUTER_USE_KEY = "computer.use.enabled";

    // ── AI provider keys (duplicate AiConfigService read keys so writes round-trip) ──
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_MODEL_KEY    = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_MODEL_KEY  = "ai.anthropic.model";
    private static final String AI_DEEPSEEK_ENDPOINT_KEY = "ai.deepseek.endpoint";
    private static final String AI_DEEPSEEK_MODEL_KEY   = "ai.deepseek.model";
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY   = "ai.ollama.model";
    private static final String AI_MAX_TOOL_ROUNDS_KEY = "ai.max_tool_rounds";
    private static final String AI_TOOL_LOADING_MODE_KEY = "ai.tool_loading_mode";
    private static final String AI_TOOL_LOADING_THRESHOLD_KEY = "ai.tool_loading_threshold";
    private static final String AI_SANDBOX_MODE_KEY = "ai.sandbox.mode";
    private static final String AI_SANDBOX_EXTRA_ROOTS_KEY = "ai.sandbox.extra-writable-roots";
    private static final String AI_SANDBOX_NETWORK_KEY = "ai.sandbox.network";
    private static final String AI_CODE_MODE_ENABLED_KEY = "ai.code-mode.enabled";
    private static final String AI_CONTEXT_WINDOW_TOKENS_KEY = "ai.context_window_tokens";

    private final AppSettingRepository appSettingRepo;
    private final SecurityContext securityContext;
    private final AiConfigService aiConfigService;

    public AiConfigServiceHeadless(AppSettingRepository appSettingRepo,
                                   SecurityContext securityContext,
                                   AiConfigService aiConfigService) {
        this.appSettingRepo = appSettingRepo;
        this.securityContext = securityContext;
        this.aiConfigService = aiConfigService;
    }

    @PostConstruct
    void init() {
        INSTANCE = this;
    }

    // ── Generic UI-shell settings (theme / language / sidebar) ─────────────────────────

    /** Reads any setting by key, returning {@code defaultValue} when absent/blank. */
    public static String getSetting(String key, String defaultValue) {
        return INSTANCE.readSetting(key, defaultValue);
    }

    /** Writes any setting by key (null-safe against an uninitialized instance). */
    public static void setSetting(String key, String value) {
        INSTANCE.writeSetting(key, value == null ? "" : value);
    }

    public static String getTheme()    { return INSTANCE.readSetting(THEME_KEY, "dark"); }
    public static String getLanguage() { return INSTANCE.readSetting(LANGUAGE_KEY, "en"); }
    public static boolean getSidebarCollapsed() {
        return Boolean.parseBoolean(INSTANCE.readSetting(SIDEBAR_COLLAPSED_KEY, "false"));
    }
    public static String getLogLevel() { return INSTANCE.readSetting(LOG_LEVEL_KEY, "INFO"); }

    public static void setSidebarCollapsed(boolean collapsed) {
        INSTANCE.writeSetting(SIDEBAR_COLLAPSED_KEY, String.valueOf(collapsed));
    }

    /**
     * Platform-level opt-in to run plugin workers without the native process sandbox. Only meaningful
     * on platforms where {@link fan.summer.fengyu.security.ProcessSandbox} detects no native isolator
     * (Windows); {@link fan.summer.fengyu.web.controller.SettingsController} gates writes to those
     * platforms. Default {@code false} (fail-closed).
     *
     * <p>Null-safe against an uninitialized {@link #INSTANCE}: in pure unit tests (e.g.
     * {@code PluginProcessManagerTest}) the Spring singleton is never published, so this method
     * returns {@code false} instead of NPE-ing. This mirrors the null-safe default of
     * {@code AiPermissionContext.current()} and preserves the documented fail-closed default.
     */
    public static boolean isUnsandboxedPluginsEnabled() {
        if (INSTANCE == null) return false;
        return Boolean.parseBoolean(INSTANCE.readSetting(PLUGIN_UNSANDBOXED_KEY, "false"));
    }

    public static void setUnsandboxedPluginsEnabled(boolean enabled) {
        INSTANCE.writeSetting(PLUGIN_UNSANDBOXED_KEY, String.valueOf(enabled));
    }

    /** The stored permission-rule table JSON ({@code {"allow":[…],"ask":[…],"deny":[…]}}); null-safe. */
    public static String getPermissionRulesJson() {
        if (INSTANCE == null) return "{}";
        return INSTANCE.readSetting(AI_PERMISSION_RULES_KEY, "{}");
    }

    public static void setPermissionRulesJson(String json) {
        INSTANCE.writeSetting(AI_PERMISSION_RULES_KEY, json == null || json.isBlank() ? "{}" : json);
    }

    /** The stored hook-list JSON; null-safe (pure unit tests see {@code "[]"}). */
    public static String getHooksJson() {
        if (INSTANCE == null) return "[]";
        return INSTANCE.readSetting(AI_HOOKS_KEY, "[]");
    }

    public static void setHooksJson(String json) {
        INSTANCE.writeSetting(AI_HOOKS_KEY, json == null || json.isBlank() ? "[]" : json);
    }

    /** True when plugin installs must present a matching checksum sidecar. Null-safe, default off. */
    public static boolean isMarketplaceChecksumRequired() {
        if (INSTANCE == null) return false;
        return Boolean.parseBoolean(INSTANCE.readSetting(MARKETPLACE_REQUIRE_CHECKSUM_KEY, "false"));
    }

    public static void setMarketplaceChecksumRequired(boolean required) {
        INSTANCE.writeSetting(MARKETPLACE_REQUIRE_CHECKSUM_KEY, String.valueOf(required));
    }

    /**
     * The configured update-channel proxy base URL (e.g. an intranet FY-Proxy at
     * {@code http://10.0.0.5:8088}), or {@code bootstrapDefault} when the setting is absent/blank.
     * The bootstrap default (typically the {@code fengyu.updates.api-base} {@code @Value} captured
     * at construction) is returned as-is when the Spring singleton is uninitialized (pure unit
     * tests), mirroring {@link #isUnsandboxedPluginsEnabled()}'s null-safe fallback.
     *
     * <p>Read from within {@code UpdateCheckService.fetchLatest()} so the channel is live-reconfigured
     * by a Settings-UI change without a JVM restart.
     */
    public static String getUpdateApiBase(String bootstrapDefault) {
        if (INSTANCE == null) return bootstrapDefault;
        return INSTANCE.readSetting(UPDATE_API_BASE_KEY, bootstrapDefault);
    }

    public static void setUpdateApiBase(String value) {
        // Normalize: null → empty, trim, strip trailing slashes. Keeps a single canonical form so
        // every consumer (backend UpdateCheckService, desktop update-feed.ts) reads a clean base.
        String normalized = value == null ? "" : value.trim().replaceAll("/+$", "");
        INSTANCE.writeSetting(UPDATE_API_BASE_KEY, normalized);
    }

    /**
     * Whether the store channel may point into a private network / plain HTTP. Read per request
     * by {@code StoreEndpointProvider} so the Settings-UI toggle re-runs the SSRF policy with
     * the new posture on the very next store call — no restart.
     */
    public static boolean isStoreAllowPrivateNetwork() {
        if (INSTANCE == null) return false;
        return Boolean.parseBoolean(INSTANCE.readSetting(STORE_ALLOW_PRIVATE_NETWORK_KEY, "false"));
    }

    public static void setStoreAllowPrivateNetwork(boolean value) {
        INSTANCE.writeSetting(STORE_ALLOW_PRIVATE_NETWORK_KEY, String.valueOf(value));
    }

    /**
     * Whether the {@code computer_*} screen-control tools are exposed to the AI. Read per
     * registry snapshot by {@code AiToolRegistry} so toggling in Settings takes effect on the
     * next turn without a restart.
     */
    public static boolean isComputerUseEnabled() {
        if (INSTANCE == null) return true;
        return Boolean.parseBoolean(INSTANCE.readSetting(COMPUTER_USE_KEY, "true"));
    }

    public static void setComputerUseEnabled(boolean enabled) {
        INSTANCE.writeSetting(COMPUTER_USE_KEY, String.valueOf(enabled));
    }

    // ── Reads (delegate to AiConfigService) ───────────────────────────────────

    public static float getAiTemperature() { return AiConfigService.getAiTemperature(); }
    public static float getAiTopP()        { return AiConfigService.getAiTopP(); }
    public static int   getAiMaxTokens()   { return AiConfigService.getAiMaxTokens(); }
    public static String getAiSystemPrompt() { return AiConfigService.getAiSystemPrompt(); }
    public static int   getAiMaxToolRounds() { return AiConfigService.getAiMaxToolRounds(); }
    public static int   getAiContextWindowTokens() { return AiConfigService.getAiContextWindowTokens(); }
    public static String getAiToolLoadingMode() { return AiConfigService.getAiToolLoadingMode(); }
    public static int   getAiToolLoadingThreshold() { return AiConfigService.getAiToolLoadingThreshold(); }

    // ── Writes (persist via JPA) ──────────────────────────────────────────────

    public static void setAiTemperature(float value) { INSTANCE.writeSetting(AI_TEMPERATURE_KEY, String.valueOf(value)); }
    public static void setAiTopP(float value)        { INSTANCE.writeSetting(AI_TOP_P_KEY, String.valueOf(value)); }

    /** Heal marker: the one-time legacy-default cleanup below already ran for this user. */
    private static final String AI_LEGACY_DEFAULT_HEAL_MARKER_KEY = "ai.legacy_default_override_healed";

    public static void setAiMaxTokens(int value) {
        // Round-trip guard: the generation form shows the EFFECTIVE cap (explicit
        // override, else the active model's catalog value) and saves what it shows,
        // so an untouched save would freeze a catalog-derived number into a
        // permanent override that outlives model switches. A value equal to the
        // active model's catalog number is that untouched round-trip — blank the
        // override (blank reads as unset) instead of storing it, keeping the
        // setting "auto" so it follows the catalog across model changes.
        if (value == fan.summer.fengyu.ai.config.ModelMetadataCatalog
                .maxOutputTokens(fan.summer.fengyu.ai.AiConfigService.activeModelId())
                .orElse(Integer.MIN_VALUE)) {
            INSTANCE.writeSetting(AI_MAX_TOKENS_KEY, "");
            return;
        }
        INSTANCE.writeSetting(AI_MAX_TOKENS_KEY, String.valueOf(value));
    }
    public static void setAiMaxToolRounds(int value) { INSTANCE.writeSetting(AI_MAX_TOOL_ROUNDS_KEY, String.valueOf(value)); }
    public static void setAiContextWindowTokens(int value) {
        // Same round-trip guard as setAiMaxTokens; 0 (compaction off) is always a
        // deliberate choice and always persists.
        if (value != 0 && value == fan.summer.fengyu.ai.config.ModelMetadataCatalog
                .contextWindow(fan.summer.fengyu.ai.AiConfigService.activeModelId())
                .orElse(Integer.MIN_VALUE)) {
            INSTANCE.writeSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, "");
            return;
        }
        INSTANCE.writeSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, String.valueOf(value));
    }

    /**
     * One-time heal of a pre-4.1 form artifact, run on the first user-scoped
     * config read: the generation form used to persist the then-effective
     * context window / output cap on EVERY save, which froze the legacy flat
     * defaults (32,768 / 8,192) as explicit overrides that later model switches
     * could never update — a 1M-window model kept compacting against 32k. A
     * stored value still equal to a legacy default is almost certainly that
     * artifact; blank it so the catalog drives again. The marker key makes this
     * run exactly once per user, so a deliberate 32768/8192 choice made after
     * the heal survives restarts. Best-effort: never throws.
     */
    public static void healLegacyDefaultOverridesIfNeeded() {
        AiConfigServiceHeadless h = INSTANCE;
        if (h == null) return;
        try {
            if (!h.readSetting(AI_LEGACY_DEFAULT_HEAL_MARKER_KEY, "").isBlank()) return;
            if ("32768".equals(h.readSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, ""))) {
                h.writeSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, "");
            }
            if ("8192".equals(h.readSetting(AI_MAX_TOKENS_KEY, ""))) {
                h.writeSetting(AI_MAX_TOKENS_KEY, "");
            }
            h.writeSetting(AI_LEGACY_DEFAULT_HEAL_MARKER_KEY, "1");
        } catch (Exception ignored) {
            // No user context yet or a repository hiccup — the next read retries.
        }
    }
    public static void setAiToolLoadingMode(String value) {
        INSTANCE.writeSetting(AI_TOOL_LOADING_MODE_KEY,
                fan.summer.fengyu.ai.tools.ToolLoadingPolicy.normalizeMode(value));
    }

    /** Sandbox mode: off / read-only / workspace-write (anything else falls back to off). */
    public static void setAiSandboxMode(String value) {
        String normalized = switch (value == null ? "" : value.trim()) {
            case "read-only", "workspace-write" -> value.trim();
            default -> "off";
        };
        INSTANCE.writeSetting(AI_SANDBOX_MODE_KEY, normalized);
    }

    public static void setAiSandboxExtraWritableRoots(String value) {
        INSTANCE.writeSetting(AI_SANDBOX_EXTRA_ROOTS_KEY,
                value == null ? "" : value.trim());
    }

    /** Sandbox network: denied / open (anything else falls back to denied). */
    public static void setAiSandboxNetwork(String value) {
        INSTANCE.writeSetting(AI_SANDBOX_NETWORK_KEY,
                "open".equals(value) ? "open" : "denied");
    }

    public static void setAiCodeModeEnabled(boolean value) {
        INSTANCE.writeSetting(AI_CODE_MODE_ENABLED_KEY, Boolean.toString(value));
    }
    public static void setAiToolLoadingThreshold(int value) {
        INSTANCE.writeSetting(AI_TOOL_LOADING_THRESHOLD_KEY,
                String.valueOf(fan.summer.fengyu.ai.tools.ToolLoadingPolicy.clampThreshold(value)));
    }
    public static void setAiSystemPrompt(String value) { INSTANCE.writeSetting(AI_SYSTEM_PROMPT_KEY, value); }
    public static void setTheme(String theme)        { INSTANCE.writeSetting(THEME_KEY, theme); }
    public static void setLanguage(String language)  { INSTANCE.writeSetting(LANGUAGE_KEY, language); }
    public static void setLogLevel(String level)     { INSTANCE.writeSetting(LOG_LEVEL_KEY, level); }

    // ── AI provider writes (persist via JPA) ───────────────────────────────────

    public static void setAiMode(String mode)              { INSTANCE.writeSetting(AI_MODE_KEY, mode); }
    public static void setAiOpenAiEndpoint(String v)       { INSTANCE.writeSetting(AI_OPENAI_ENDPOINT_KEY, v); }
    public static void setAiOpenAiApiKey(String v)         { INSTANCE.writeSetting(AiConfigService.AI_OPENAI_API_KEY_KEY, v); }
    public static void setAiOpenAiModel(String v)          { INSTANCE.writeSetting(AI_OPENAI_MODEL_KEY, v); }
    public static void setAiAnthropicEndpoint(String v)    { INSTANCE.writeSetting(AI_ANTHROPIC_ENDPOINT_KEY, v); }
    public static void setAiAnthropicApiKey(String v)      { INSTANCE.writeSetting(AiConfigService.AI_ANTHROPIC_API_KEY_KEY, v); }
    public static void setAiAnthropicModel(String v)       { INSTANCE.writeSetting(AI_ANTHROPIC_MODEL_KEY, v); }
    public static void setAiDeepSeekEndpoint(String v)     { INSTANCE.writeSetting(AI_DEEPSEEK_ENDPOINT_KEY, v); }
    public static void setAiDeepSeekApiKey(String v)       { INSTANCE.writeSetting(AiConfigService.AI_DEEPSEEK_API_KEY_KEY, v); }
    public static void setAiDeepSeekModel(String v)        { INSTANCE.writeSetting(AI_DEEPSEEK_MODEL_KEY, v); }
    public static void setAiOllamaBaseUrl(String v)        { INSTANCE.writeSetting(AI_OLLAMA_BASE_URL_KEY, v); }
    public static void setAiThinkingLevel(String v)        { INSTANCE.writeSetting(AI_THINKING_LEVEL_KEY, v == null ? "off" : v.trim()); }
    public static String getAiThinkingLevel() {
        if (INSTANCE == null) return "off";
        return INSTANCE.readSetting(AI_THINKING_LEVEL_KEY, "off");
    }

    /** The configured level, or {@code null} when unset — unset means "provider
     * default" (legacy Ollama behavior: capable models think), never "off". */
    public static String getAiThinkingLevelOrNull() {
        if (INSTANCE == null) return null;
        return INSTANCE.readSetting(AI_THINKING_LEVEL_KEY, null);
    }
    private static final String AI_THINKING_LEVEL_KEY = "ai.thinking.level";
    public static void setAiOllamaModel(String v)          { INSTANCE.writeSetting(AI_OLLAMA_MODEL_KEY, v); }

    // ── Instance implementation (uses injected repo + security context) ───────

    /** Settings whose values are provider credentials — encrypted at rest with the
     *  machine-bound key (CryptoUtil's ENC(...) envelope; historical plaintext rows still
     *  decrypt transparently, and a stolen database does not yield usable keys off-machine).
     *  The key strings live once, on {@link AiConfigService} (the canonical read path). */
    private static final java.util.Set<String> SECRET_SETTING_KEYS = java.util.Set.of(
            AiConfigService.AI_OPENAI_API_KEY_KEY,
            AiConfigService.AI_ANTHROPIC_API_KEY_KEY,
            AiConfigService.AI_DEEPSEEK_API_KEY_KEY);

    /** Raw (never decrypted) user-scoped read for non-secret structured settings. */
    public static String readRawSetting(String key) {
        if (INSTANCE == null) return null;
        try {
            Long uid = INSTANCE.securityContext.currentUserId();
            return INSTANCE.appSettingRepo.findByUserIdAndSettingKey(uid, key)
                    .map(AppSettingEntity::getSettingValue).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Raw (never encrypted) user-scoped write for non-secret structured settings. */
    public static void persistRawSetting(String key, String value) {
        INSTANCE.writeSetting(key, value);
    }

    private String readSetting(String key, String defaultValue) {
        Long uid = securityContext.currentUserId();
        return appSettingRepo.findByUserIdAndSettingKey(uid, key)
                .filter(e -> e.getSettingValue() != null && !e.getSettingValue().isBlank())
                .map(AppSettingEntity::getSettingValue)
                .map(value -> SECRET_SETTING_KEYS.contains(key)
                        ? fan.summer.fengyu.setup.CryptoUtil.decrypt(value) : value)
                .orElse(defaultValue);
    }

    private void writeSetting(String key, String value) {
        Long uid = securityContext.currentUserId();
        AppSettingEntity entity = appSettingRepo.findByUserIdAndSettingKey(uid, key)
                .orElseGet(() -> {
                    AppSettingEntity e = new AppSettingEntity();
                    e.setSettingKey(key);
                    e.setUserId(uid);
                    return e;
                });
        if (SECRET_SETTING_KEYS.contains(key) && value != null && !value.isBlank()) {
            value = fan.summer.fengyu.setup.CryptoUtil.encrypt(value);
        }
        entity.setSettingValue(value);
        appSettingRepo.save(entity);
    }
}
