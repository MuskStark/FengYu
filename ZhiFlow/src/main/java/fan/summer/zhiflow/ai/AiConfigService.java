package fan.summer.zhiflow.ai;

import fan.summer.zhiflow.database.entity.AppSettingEntity;
import fan.summer.zhiflow.database.repository.AppSettingRepository;
import fan.summer.zhiflow.security.SecurityContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads AI configuration from the database via JPA.
 *
 * <p>Converted from a pure-static MyBatis utility to a Spring {@code @Component} so it can inject
 * {@link AppSettingRepository} and {@link SecurityContext} (user-scoped reads). The data path now
 * goes through JPA + SecurityContext; the public static call surface is <strong>retained</strong>
 * as thin delegates to the Spring-managed singleton so that non-bean callers (the AI backends built
 * by static factories, and {@code AiConfigProperties.snapshot()}) keep compiling without DI plumbing.
 *
 * <p><b>Why static delegates and not public instance getters:</b> Java forbids a static method and
 * an instance method with the same name and signature in one class. Since many callers depend on the
 * exact static signatures ({@code AiConfigService.getAiMode()}), the public methods stay static and
 * forward to {@link #INSTANCE}, whose private {@link #readSetting} uses the injected repository.
 * This is the plan's documented "static facade holder" fallback, adapted to Java's constraint.
 *
 * @since 3.0.0
 */
@Component
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    /**
     * Spring-managed singleton, populated in {@link #init()} after the bean is constructed.
     * Volatile: read by static delegates from arbitrary threads (virtual-thread chat loops).
     */
    private static volatile AiConfigService INSTANCE;

    private final AppSettingRepository appSettingRepo;
    private final SecurityContext securityContext;

    public AiConfigService(AppSettingRepository appSettingRepo, SecurityContext securityContext) {
        this.appSettingRepo = appSettingRepo;
        this.securityContext = securityContext;
    }

    @PostConstruct
    void init() {
        INSTANCE = this;
    }

    // ── Setting keys ─────────────────────────────────────────────
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    private static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    private static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
    private static final String AI_DEEPSEEK_ENDPOINT_KEY = "ai.deepseek.endpoint";
    private static final String AI_DEEPSEEK_API_KEY_KEY = "ai.deepseek.api_key";
    private static final String AI_DEEPSEEK_MODEL_KEY = "ai.deepseek.model";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String AI_LOCAL_BACKEND_KEY = "ai.local.backend";
    private static final String AI_MODEL_PATH_KEY = "ai.model.path";
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY = "ai.ollama.model";

    // ── Core read (instance; uses injected repo + security context) ──────────
    private String readSetting(String key, String defaultValue) {
        try {
            Long uid = securityContext.currentUserId();
            Optional<AppSettingEntity> entity = appSettingRepo.findByUserIdAndSettingKey(uid, key);
            if (entity.isPresent()) {
                String v = entity.get().getSettingValue();
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception e) {
            log.debug("Could not read AI setting: {}", key, e);
        }
        return defaultValue;
    }

    // ── Public static getters (signatures unchanged; forward to the bean) ────
    // Retained so non-bean callers (AI backends built via static factories, and
    // AiConfigProperties.snapshot()) keep compiling without DI plumbing.

    /** Returns the AI mode: {@code "local"}, {@code "openai"}, {@code "anthropic"}, or {@code "deepseek"}. */
    public static String getAiMode() { return INSTANCE.readSetting(AI_MODE_KEY, "local"); }

    /** Returns the OpenAI-compatible API endpoint URL. */
    public static String getAiOpenAiEndpoint() { return INSTANCE.readSetting(AI_OPENAI_ENDPOINT_KEY, "https://api.openai.com"); }

    /** Returns the OpenAI API key. */
    public static String getAiOpenAiApiKey() { return INSTANCE.readSetting(AI_OPENAI_API_KEY_KEY, ""); }

    /** Returns the OpenAI model identifier. */
    public static String getAiOpenAiModel() { return INSTANCE.readSetting(AI_OPENAI_MODEL_KEY, "gpt-4o"); }

    /** Returns the Anthropic API endpoint URL. */
    public static String getAiAnthropicEndpoint() { return INSTANCE.readSetting(AI_ANTHROPIC_ENDPOINT_KEY, "https://api.anthropic.com"); }

    /** Returns the Anthropic API key. */
    public static String getAiAnthropicApiKey() { return INSTANCE.readSetting(AI_ANTHROPIC_API_KEY_KEY, ""); }

    /** Returns the Anthropic model identifier. */
    public static String getAiAnthropicModel() { return INSTANCE.readSetting(AI_ANTHROPIC_MODEL_KEY, "claude-sonnet-4-20250514"); }

    /** Returns the DeepSeek API endpoint URL (OpenAI-compatible). */
    public static String getAiDeepSeekEndpoint() { return INSTANCE.readSetting(AI_DEEPSEEK_ENDPOINT_KEY, "https://api.deepseek.com"); }

    /** Returns the DeepSeek API key. */
    public static String getAiDeepSeekApiKey() { return INSTANCE.readSetting(AI_DEEPSEEK_API_KEY_KEY, ""); }

    /** Returns the DeepSeek model identifier; defaults to {@code deepseek-chat}. */
    public static String getAiDeepSeekModel() { return INSTANCE.readSetting(AI_DEEPSEEK_MODEL_KEY, "deepseek-chat"); }

    /** Returns the sampling temperature (0–2); defaults to 0.7. */
    public static float getAiTemperature() {
        String val = INSTANCE.readSetting(AI_TEMPERATURE_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.7f;
    }

    /** Returns the nucleus sampling threshold (0–1); defaults to 0.9. */
    public static float getAiTopP() {
        String val = INSTANCE.readSetting(AI_TOP_P_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.9f;
    }

    /** Returns the maximum number of tokens to generate; defaults to 2048. */
    public static int getAiMaxTokens() {
        String val = INSTANCE.readSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) { try { return Integer.parseInt(val); } catch (NumberFormatException ignored) {} }
        return 2048;
    }

    /** Returns the system prompt; defaults to "You are a helpful assistant." */
    public static String getAiSystemPrompt() {
        String val = INSTANCE.readSetting(AI_SYSTEM_PROMPT_KEY, null);
        return (val != null && !val.isBlank()) ? val : "You are a helpful assistant.";
    }

    /** Returns the local backend type: {@code "java"} or {@code "native"}. */
    public static String getAiLocalBackend() { return INSTANCE.readSetting(AI_LOCAL_BACKEND_KEY, "java"); }

    /** Returns the local GGUF model file path, or null if not set. */
    public static String getAiModelPath() { return INSTANCE.readSetting(AI_MODEL_PATH_KEY, null); }

    /** Ollama server base URL; defaults to the standard local daemon. */
    public static String getAiOllamaBaseUrl() { return INSTANCE.readSetting(AI_OLLAMA_BASE_URL_KEY, "http://localhost:11434"); }

    /** Ollama model tag (e.g. {@code "qwen3:4b"}); defaults to Qwen3 4B. */
    public static String getAiOllamaModel() { return INSTANCE.readSetting(AI_OLLAMA_MODEL_KEY, "qwen3:4b"); }
}
