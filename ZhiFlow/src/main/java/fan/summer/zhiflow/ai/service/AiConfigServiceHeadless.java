package fan.summer.zhiflow.ai.service;

import fan.summer.zhiflow.ai.AiConfigService;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.mapper.AppSettingMapper;

/**
 * Headless AI configuration service — wraps read-only {@link AiConfigService} and provides
 * write methods that persist to H2 via MyBatis, replacing the UI-layer
 * {@code ZhiFlowSettingUi} cache for headless mode.
 *
 * <p>Setting keys are kept identical to {@link AiConfigService}'s read keys so that a value
 * written here round-trips through the read path (e.g. {@code ai.top_p}, not {@code ai.topP}).
 */
public final class AiConfigServiceHeadless {

    // Keys — MUST match AiConfigService read keys exactly.
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY       = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY  = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String THEME_KEY    = "theme";
    private static final String LANGUAGE_KEY = "language";
    private static final String SIDEBAR_COLLAPSED_KEY = "sidebar.collapsed";

    private AiConfigServiceHeadless() {}

    // ── Generic UI-shell settings (theme / language / sidebar) ─────────────────────────

    /** Reads any setting by key, returning {@code defaultValue} when absent/blank. */
    public static String getSetting(String key, String defaultValue) {
        return DatabaseInit.withMapper(AppSettingMapper.class, mapper -> {
            AppSettingEntity e = mapper.selectByKey(key);
            return (e != null && e.getSettingValue() != null && !e.getSettingValue().isBlank())
                ? e.getSettingValue() : defaultValue;
        });
    }

    public static String getTheme()    { return getSetting(THEME_KEY, "dark"); }
    public static String getLanguage() { return getSetting(LANGUAGE_KEY, "en"); }
    public static boolean getSidebarCollapsed() {
        return Boolean.parseBoolean(getSetting(SIDEBAR_COLLAPSED_KEY, "false"));
    }

    public static void setSidebarCollapsed(boolean collapsed) {
        writeSetting(SIDEBAR_COLLAPSED_KEY, String.valueOf(collapsed));
    }

    // ── Reads (delegate to existing AiConfigService) ───────────────────────────────────

    public static float getAiTemperature() {
        return AiConfigService.getAiTemperature();
    }

    public static float getAiTopP() {
        return AiConfigService.getAiTopP();
    }

    public static int getAiMaxTokens() {
        return AiConfigService.getAiMaxTokens();
    }

    public static String getAiSystemPrompt() {
        return AiConfigService.getAiSystemPrompt();
    }

    // ── Writes (persist to H2) ─────────────────────────────────────────────────────────

    public static void setAiTemperature(float value) {
        writeSetting(AI_TEMPERATURE_KEY, String.valueOf(value));
    }

    public static void setAiTopP(float value) {
        writeSetting(AI_TOP_P_KEY, String.valueOf(value));
    }

    public static void setAiMaxTokens(int value) {
        writeSetting(AI_MAX_TOKENS_KEY, String.valueOf(value));
    }

    public static void setAiSystemPrompt(String value) {
        writeSetting(AI_SYSTEM_PROMPT_KEY, value);
    }

    public static void setTheme(String theme) {
        writeSetting(THEME_KEY, theme);
    }

    public static void setLanguage(String language) {
        writeSetting(LANGUAGE_KEY, language);
    }

    private static void writeSetting(String key, String value) {
        DatabaseInit.withMapper(AppSettingMapper.class, mapper -> {
            AppSettingEntity existing = mapper.selectByKey(key);
            if (existing == null) {
                AppSettingEntity e = new AppSettingEntity();
                e.setSettingKey(key);
                e.setSettingValue(value);
                mapper.insert(e);
            } else {
                existing.setSettingValue(value);
                mapper.update(existing);
            }
        });
    }
}
