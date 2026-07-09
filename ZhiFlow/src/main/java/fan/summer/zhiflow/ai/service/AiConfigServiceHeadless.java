package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.SecurityContext;
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

    public static void setSidebarCollapsed(boolean collapsed) {
        INSTANCE.writeSetting(SIDEBAR_COLLAPSED_KEY, String.valueOf(collapsed));
    }

    // ── Reads (delegate to AiConfigService) ───────────────────────────────────

    public static float getAiTemperature() { return AiConfigService.getAiTemperature(); }
    public static float getAiTopP()        { return AiConfigService.getAiTopP(); }
    public static int   getAiMaxTokens()   { return AiConfigService.getAiMaxTokens(); }
    public static String getAiSystemPrompt() { return AiConfigService.getAiSystemPrompt(); }

    // ── Writes (persist via JPA) ──────────────────────────────────────────────

    public static void setAiTemperature(float value) { INSTANCE.writeSetting(AI_TEMPERATURE_KEY, String.valueOf(value)); }
    public static void setAiTopP(float value)        { INSTANCE.writeSetting(AI_TOP_P_KEY, String.valueOf(value)); }
    public static void setAiMaxTokens(int value)     { INSTANCE.writeSetting(AI_MAX_TOKENS_KEY, String.valueOf(value)); }
    public static void setAiSystemPrompt(String value) { INSTANCE.writeSetting(AI_SYSTEM_PROMPT_KEY, value); }
    public static void setTheme(String theme)        { INSTANCE.writeSetting(THEME_KEY, theme); }
    public static void setLanguage(String language)  { INSTANCE.writeSetting(LANGUAGE_KEY, language); }

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
