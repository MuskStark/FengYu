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

    // ── AI provider keys (duplicate AiConfigService read keys so writes round-trip) ──
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_API_KEY_KEY  = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY    = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_API_KEY_KEY  = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY    = "ai.anthropic.model";
    private static final String AI_DEEPSEEK_ENDPOINT_KEY = "ai.deepseek.endpoint";
    private static final String AI_DEEPSEEK_API_KEY_KEY  = "ai.deepseek.api_key";
    private static final String AI_DEEPSEEK_MODEL_KEY    = "ai.deepseek.model";
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY   = "ai.ollama.model";
    private static final String AI_MAX_TOOL_ROUNDS_KEY = "ai.max_tool_rounds";
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

    // ── Reads (delegate to AiConfigService) ───────────────────────────────────

    public static float getAiTemperature() { return AiConfigService.getAiTemperature(); }
    public static float getAiTopP()        { return AiConfigService.getAiTopP(); }
    public static int   getAiMaxTokens()   { return AiConfigService.getAiMaxTokens(); }
    public static String getAiSystemPrompt() { return AiConfigService.getAiSystemPrompt(); }
    public static int   getAiMaxToolRounds() { return AiConfigService.getAiMaxToolRounds(); }
    public static int   getAiContextWindowTokens() { return AiConfigService.getAiContextWindowTokens(); }

    // ── Writes (persist via JPA) ──────────────────────────────────────────────

    public static void setAiTemperature(float value) { INSTANCE.writeSetting(AI_TEMPERATURE_KEY, String.valueOf(value)); }
    public static void setAiTopP(float value)        { INSTANCE.writeSetting(AI_TOP_P_KEY, String.valueOf(value)); }
    public static void setAiMaxTokens(int value)     { INSTANCE.writeSetting(AI_MAX_TOKENS_KEY, String.valueOf(value)); }
    public static void setAiMaxToolRounds(int value) { INSTANCE.writeSetting(AI_MAX_TOOL_ROUNDS_KEY, String.valueOf(value)); }
    public static void setAiContextWindowTokens(int value) {
        INSTANCE.writeSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, String.valueOf(value));
    }
    public static void setAiSystemPrompt(String value) { INSTANCE.writeSetting(AI_SYSTEM_PROMPT_KEY, value); }
    public static void setTheme(String theme)        { INSTANCE.writeSetting(THEME_KEY, theme); }
    public static void setLanguage(String language)  { INSTANCE.writeSetting(LANGUAGE_KEY, language); }
    public static void setLogLevel(String level)     { INSTANCE.writeSetting(LOG_LEVEL_KEY, level); }

    // ── AI provider writes (persist via JPA) ───────────────────────────────────

    public static void setAiMode(String mode)              { INSTANCE.writeSetting(AI_MODE_KEY, mode); }
    public static void setAiOpenAiEndpoint(String v)       { INSTANCE.writeSetting(AI_OPENAI_ENDPOINT_KEY, v); }
    public static void setAiOpenAiApiKey(String v)         { INSTANCE.writeSetting(AI_OPENAI_API_KEY_KEY, v); }
    public static void setAiOpenAiModel(String v)          { INSTANCE.writeSetting(AI_OPENAI_MODEL_KEY, v); }
    public static void setAiAnthropicEndpoint(String v)    { INSTANCE.writeSetting(AI_ANTHROPIC_ENDPOINT_KEY, v); }
    public static void setAiAnthropicApiKey(String v)      { INSTANCE.writeSetting(AI_ANTHROPIC_API_KEY_KEY, v); }
    public static void setAiAnthropicModel(String v)       { INSTANCE.writeSetting(AI_ANTHROPIC_MODEL_KEY, v); }
    public static void setAiDeepSeekEndpoint(String v)     { INSTANCE.writeSetting(AI_DEEPSEEK_ENDPOINT_KEY, v); }
    public static void setAiDeepSeekApiKey(String v)       { INSTANCE.writeSetting(AI_DEEPSEEK_API_KEY_KEY, v); }
    public static void setAiDeepSeekModel(String v)        { INSTANCE.writeSetting(AI_DEEPSEEK_MODEL_KEY, v); }
    public static void setAiOllamaBaseUrl(String v)        { INSTANCE.writeSetting(AI_OLLAMA_BASE_URL_KEY, v); }
    public static void setAiOllamaModel(String v)          { INSTANCE.writeSetting(AI_OLLAMA_MODEL_KEY, v); }

    // ── Instance implementation (uses injected repo + security context) ───────

    private String readSetting(String key, String defaultValue) {
        Long uid = securityContext.currentUserId();
        return appSettingRepo.findByUserIdAndSettingKey(uid, key)
                .filter(e -> e.getSettingValue() != null && !e.getSettingValue().isBlank())
                .map(AppSettingEntity::getSettingValue)
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
        entity.setSettingValue(value);
        appSettingRepo.save(entity);
    }
}
