package fan.summer.zhiflow.ai;

import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.mapper.AppSettingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads AI configuration from the database without any UI dependency.
 *
 * <p>This class centralizes all AI-related setting lookups so that
 * the startup code ({@code SwissKitJApp}) does not depend on the
 * settings UI class ({@code SwissKitJSettingUi}).</p>
 *
 * <p>The settings cache from {@code SwissKitJSettingUi} is NOT used
 * here — this service reads directly from the database on each call.
 * The settings UI's cache is updated when the user changes values,
 * but the startup path should not need to go through the UI layer.</p>
 *
 * @since 3.0.0
 */
public final class AiConfigService {

    private AiConfigService() {}

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    // ── Setting keys (same as in SwissKitJSettingUi) ──────────
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String AI_LOCAL_BACKEND_KEY = "ai.local.backend";
    private static final String AI_MODEL_PATH_KEY = "ai.model.path";

    // ── Core read ─────────────────────────────────────────────

    private static String readSetting(String key, String defaultValue) {
        try (var session = DatabaseInit.getSqlSession()) {
            AppSettingMapper mapper = session.getMapper(AppSettingMapper.class);
            AppSettingEntity entity = mapper.selectByKey(key);
            if (entity != null && entity.getSettingValue() != null && !entity.getSettingValue().isBlank()) {
                return entity.getSettingValue();
            }
        } catch (Exception e) {
            log.debug("Could not read AI setting: {}", key, e);
        }
        return defaultValue;
    }

    // ── Public getters ────────────────────────────────────────

    /** Returns the AI mode: {@code "local"}, {@code "openai"}, or {@code "anthropic"}. */
    public static String getAiMode() {
        return readSetting(AI_MODE_KEY, "local");
    }

    /** Returns the OpenAI-compatible API endpoint URL. */
    public static String getAiOpenAiEndpoint() {
        return readSetting(AI_OPENAI_ENDPOINT_KEY, "https://api.openai.com");
    }

    /** Returns the OpenAI API key. */
    public static String getAiOpenAiApiKey() {
        return readSetting(AI_OPENAI_API_KEY_KEY, "");
    }

    /** Returns the OpenAI model identifier. */
    public static String getAiOpenAiModel() {
        return readSetting(AI_OPENAI_MODEL_KEY, "gpt-4o");
    }

    /** Returns the Anthropic API endpoint URL. */
    public static String getAiAnthropicEndpoint() {
        return readSetting(AI_ANTHROPIC_ENDPOINT_KEY, "https://api.anthropic.com");
    }

    /** Returns the Anthropic API key. */
    public static String getAiAnthropicApiKey() {
        return readSetting(AI_ANTHROPIC_API_KEY_KEY, "");
    }

    /** Returns the Anthropic model identifier. */
    public static String getAiAnthropicModel() {
        return readSetting(AI_ANTHROPIC_MODEL_KEY, "claude-sonnet-4-20250514");
    }

    /** Returns the sampling temperature (0–2); defaults to 0.7. */
    public static float getAiTemperature() {
        String val = readSetting(AI_TEMPERATURE_KEY, null);
        if (val != null) {
            try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {}
        }
        return 0.7f;
    }

    /** Returns the nucleus sampling threshold (0–1); defaults to 0.9. */
    public static float getAiTopP() {
        String val = readSetting(AI_TOP_P_KEY, null);
        if (val != null) {
            try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {}
        }
        return 0.9f;
    }

    /** Returns the maximum number of tokens to generate; defaults to 2048. */
    public static int getAiMaxTokens() {
        String val = readSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {}
        }
        return 2048;
    }

    /** Returns the system prompt; defaults to "You are a helpful assistant." */
    public static String getAiSystemPrompt() {
        String val = readSetting(AI_SYSTEM_PROMPT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "You are a helpful assistant.";
    }

    /** Returns the local backend type: {@code "java"} or {@code "native"}. */
    public static String getAiLocalBackend() {
        return readSetting(AI_LOCAL_BACKEND_KEY, "java");
    }

    /** Returns the local GGUF model file path, or null if not set. */
    public static String getAiModelPath() {
        return readSetting(AI_MODEL_PATH_KEY, null);
    }
}
