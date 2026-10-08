package fan.summer.fengyu.ai;

import fan.summer.fengyu.database.entity.AppSettingEntity;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.SecurityContext;
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
    public void init() {
        INSTANCE = this;
    }

    // ── Setting keys ─────────────────────────────────────────────
    private static final String AI_MODE_KEY = "ai.mode";
    private static final String AI_OPENAI_ENDPOINT_KEY = "ai.openai.endpoint";
    /**
     * The provider API-key setting keys are PUBLIC: the same legacy key strings are
     * referenced by {@code AiConfigServiceHeadless} (the writer) and
     * {@code ProviderRegistryService} (the first-write migration), so this class is the
     * single source for them — a rename cannot silently break the round-trip.
     */
    public static final String AI_OPENAI_API_KEY_KEY = "ai.openai.api_key";
    private static final String AI_OPENAI_MODEL_KEY = "ai.openai.model";
    private static final String AI_ANTHROPIC_ENDPOINT_KEY = "ai.anthropic.endpoint";
    public static final String AI_ANTHROPIC_API_KEY_KEY = "ai.anthropic.api_key";
    private static final String AI_ANTHROPIC_MODEL_KEY = "ai.anthropic.model";
    private static final String AI_DEEPSEEK_ENDPOINT_KEY = "ai.deepseek.endpoint";
    public static final String AI_DEEPSEEK_API_KEY_KEY = "ai.deepseek.api_key";
    private static final String AI_DEEPSEEK_MODEL_KEY = "ai.deepseek.model";
    private static final String AI_TEMPERATURE_KEY = "ai.temperature";
    private static final String AI_TOP_P_KEY = "ai.top_p";
    private static final String AI_MAX_TOKENS_KEY = "ai.max_tokens";
    private static final String AI_SYSTEM_PROMPT_KEY = "ai.system_prompt";
    private static final String AI_OLLAMA_BASE_URL_KEY = "ai.ollama.base_url";
    private static final String AI_OLLAMA_MODEL_KEY = "ai.ollama.model";
    /** Maximum rounds a chat backend's tool loop may run before aborting; {@code 0} = unlimited. */
    private static final String AI_MAX_TOOL_ROUNDS_KEY = "ai.max_tool_rounds";
    /** Provider context window used by automatic history compaction; {@code 0} disables it. */
    private static final String AI_CONTEXT_WINDOW_TOKENS_KEY = "ai.context_window_tokens";
    /** Default cap on tool-loop rounds when no setting is stored (protects against runaway loops). */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 50;
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768;
    /**
     * Default per-completion output cap when no setting is stored. Bounds tool-call
     * arguments too — see {@link #getAiMaxTokens()}.
     */
    public static final int DEFAULT_MAX_TOKENS = 8192;
    /** Dynamic tool loading: {@code auto} gates it on the tool count, {@code always}/{@code off} force it. */
    private static final String AI_TOOL_LOADING_MODE_KEY = "ai.tool_loading_mode";
    /** Visible-tool count above which {@code auto} mode switches to on-demand tool loading. */
    private static final String AI_TOOL_LOADING_THRESHOLD_KEY = "ai.tool_loading_threshold";
    /** Agent OS sandbox mode: {@code off} (default), {@code read-only}, {@code workspace-write}. */
    private static final String AI_SANDBOX_MODE_KEY = "ai.sandbox.mode";
    /** Comma-separated extra absolute writable roots for the {@code workspace-write} tier. */
    private static final String AI_SANDBOX_EXTRA_WRITABLE_ROOTS_KEY = "ai.sandbox.extra-writable-roots";
    /** Agent sandbox network policy: {@code denied} (default) or {@code open}. */
    private static final String AI_SANDBOX_NETWORK_KEY = "ai.sandbox.network";
    /** Code mode (the exec/wait JS-orchestration tools); off by default. */
    private static final String AI_CODE_MODE_ENABLED_KEY = "ai.code-mode.enabled";

    // ── Core read (instance; uses injected repo + security context) ──────────
    /** Provider API keys are written by AiConfigServiceHeadless in CryptoUtil's machine-bound
     *  ENC(...) envelope — decrypt on read here (plaintext rows from older builds pass through). */
    private static final java.util.Set<String> SECRET_SETTING_KEYS = java.util.Set.of(
            AI_OPENAI_API_KEY_KEY, AI_ANTHROPIC_API_KEY_KEY, AI_DEEPSEEK_API_KEY_KEY);

    private String readSetting(String key, String defaultValue) {
        try {
            Long uid = securityContext.currentUserId();
            Optional<AppSettingEntity> entity = appSettingRepo.findByUserIdAndSettingKey(uid, key);
            if (entity.isPresent()) {
                String v = entity.get().getSettingValue();
                if (v != null && !v.isBlank()) {
                    return SECRET_SETTING_KEYS.contains(key)
                            ? fan.summer.fengyu.setup.CryptoUtil.decrypt(v) : v;
                }
            }
        } catch (Exception e) {
            log.warn("Could not read AI setting '{}': {}", key, e.toString());
        }
        return defaultValue;
    }

    // ── Public static getters (signatures unchanged; forward to the bean) ────
    // Retained so non-bean callers (AI backends built via static factories, and
    // AiConfigProperties.snapshot()) keep compiling without DI plumbing.

    /** Returns the AI mode: {@code "local"}, {@code "openai"}, {@code "anthropic"}, or {@code "deepseek"}. */
    public static String getAiMode() {
        return INSTANCE == null ? "local" : INSTANCE.readSetting(AI_MODE_KEY, "local");
    }

    /** Returns the agent sandbox mode: {@code "off"} (default), {@code "read-only"}, {@code "workspace-write"}. */
    public static String getAiSandboxMode() {
        if (INSTANCE == null) return "off";
        String mode = INSTANCE.readSetting(AI_SANDBOX_MODE_KEY, "off");
        return switch (mode) {
            case "read-only", "workspace-write" -> mode;
            default -> "off";
        };
    }

    /** Returns the extra writable roots (comma-separated absolute paths) for workspace-write. */
    public static String getAiSandboxExtraWritableRoots() {
        if (INSTANCE == null) return "";
        return INSTANCE.readSetting(AI_SANDBOX_EXTRA_WRITABLE_ROOTS_KEY, "");
    }

    /** Returns the sandbox network policy: {@code "denied"} (default) or {@code "open"}. */
    public static String getAiSandboxNetwork() {
        if (INSTANCE == null) return "denied";
        return "open".equals(INSTANCE.readSetting(AI_SANDBOX_NETWORK_KEY, "denied")) ? "open" : "denied";
    }

    /** Code mode enabled? The exec/wait tools join the surface only when true. */
    public static boolean getAiCodeModeEnabled() {
        if (INSTANCE == null) return false;
        return "true".equals(INSTANCE.readSetting(AI_CODE_MODE_ENABLED_KEY, "false"));
    }

    /** Returns the OpenAI-compatible API endpoint URL. Null-safe (pre-init callers get the default). */
    public static String getAiOpenAiEndpoint() {
        return INSTANCE == null ? "https://api.openai.com"
                : INSTANCE.readSetting(AI_OPENAI_ENDPOINT_KEY, "https://api.openai.com");
    }

    /** Returns the OpenAI API key. Null-safe. */
    public static String getAiOpenAiApiKey() {
        return INSTANCE == null ? ""
                : INSTANCE.readSetting(AI_OPENAI_API_KEY_KEY, "");
    }

    /** Returns the OpenAI model identifier. Null-safe. */
    public static String getAiOpenAiModel() {
        return INSTANCE == null ? "gpt-4o"
                : INSTANCE.readSetting(AI_OPENAI_MODEL_KEY, "gpt-4o");
    }

    /** Returns the Anthropic API endpoint URL. Null-safe. */
    public static String getAiAnthropicEndpoint() {
        return INSTANCE == null ? "https://api.anthropic.com"
                : INSTANCE.readSetting(AI_ANTHROPIC_ENDPOINT_KEY, "https://api.anthropic.com");
    }

    /** Returns the Anthropic API key. Null-safe. */
    public static String getAiAnthropicApiKey() {
        return INSTANCE == null ? ""
                : INSTANCE.readSetting(AI_ANTHROPIC_API_KEY_KEY, "");
    }

    /** Returns the Anthropic model identifier. Null-safe. */
    public static String getAiAnthropicModel() {
        return INSTANCE == null ? "claude-sonnet-4-20250514"
                : INSTANCE.readSetting(AI_ANTHROPIC_MODEL_KEY, "claude-sonnet-4-20250514");
    }

    /** Returns the DeepSeek API endpoint URL (OpenAI-compatible). Null-safe. */
    public static String getAiDeepSeekEndpoint() {
        return INSTANCE == null ? "https://api.deepseek.com"
                : INSTANCE.readSetting(AI_DEEPSEEK_ENDPOINT_KEY, "https://api.deepseek.com");
    }

    /** Returns the DeepSeek API key. Null-safe. */
    public static String getAiDeepSeekApiKey() {
        return INSTANCE == null ? ""
                : INSTANCE.readSetting(AI_DEEPSEEK_API_KEY_KEY, "");
    }

    /** Returns the DeepSeek model identifier; defaults to {@code deepseek-chat}. Null-safe. */
    public static String getAiDeepSeekModel() {
        return INSTANCE == null ? "deepseek-chat"
                : INSTANCE.readSetting(AI_DEEPSEEK_MODEL_KEY, "deepseek-chat");
    }

    /** Returns the sampling temperature (0–2); defaults to 0.7. Null-safe like the other
     *  turn-shaping reads: a bare-unit-test (or pre-Spring-init) caller gets the default
     *  instead of an NPE that would kill the whole turn. */
    public static float getAiTemperature() {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_TEMPERATURE_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.7f;
    }

    /** Returns the nucleus sampling threshold (0–1); defaults to 0.9. Null-safe (see {@link #getAiTemperature()}). */
    public static float getAiTopP() {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_TOP_P_KEY, null);
        if (val != null) { try { return Float.parseFloat(val); } catch (NumberFormatException ignored) {} }
        return 0.9f;
    }

    /**
     * Returns the maximum number of tokens to generate; defaults to
     * {@value #DEFAULT_MAX_TOKENS}. Deliberately generous: this cap bounds EVERY completion,
     * including tool-call arguments — a 2048-class default truncates long write_file JSON
     * mid-argument and forces the model into piecemeal writes (terminal coding agents send
     * no such blanket cap; 8192 sits at or above every supported provider's own default
     * while staying within DeepSeek's 8192 request ceiling).
     */
    public static int getAiMaxTokens() {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val);
                // The pre-4.1 default of 2048 was persisted whole-form by the Settings UI,
                // so a stored 2048 is the app's own legacy default far more often than a
                // deliberate choice — upgrade it to the current default rather than keep
                // truncating tool-call JSON on old installs.
                return parsed == 2048 ? DEFAULT_MAX_TOKENS : parsed;
            } catch (NumberFormatException ignored) { }
        }
        return DEFAULT_MAX_TOKENS;
    }

    /** Returns the user-configured system prompt, or Infinia's default assistant prompt. */
    public static String getAiSystemPrompt() {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_SYSTEM_PROMPT_KEY, null);
        return (val != null && !val.isBlank()) ? val : SystemPrompts.DEFAULT_CHAT;
    }

    /** Ollama server base URL; defaults to the standard local daemon. Null-safe. */
    public static String getAiOllamaBaseUrl() {
        return INSTANCE == null ? "http://localhost:11434"
                : INSTANCE.readSetting(AI_OLLAMA_BASE_URL_KEY, "http://localhost:11434");
    }

    /** Ollama model tag (e.g. {@code "qwen3:4b"}); defaults to Qwen3 4B. Null-safe. */
    public static String getAiOllamaModel() {
        return INSTANCE == null ? "qwen3:4b"
                : INSTANCE.readSetting(AI_OLLAMA_MODEL_KEY, "qwen3:4b");
    }

    /**
     * Maximum number of tool-call rounds a chat backend's loop may execute before aborting the
     * turn. {@code 0} means unlimited (no safety net). Defaults to {@value #DEFAULT_MAX_TOOL_ROUNDS}
     * when unset or unparseable, guarding against models that loop on the same tool call.
     */
    public static int getAiMaxToolRounds() {
        if (INSTANCE == null) return DEFAULT_MAX_TOOL_ROUNDS;
        String val = INSTANCE.readSetting(AI_MAX_TOOL_ROUNDS_KEY, null);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) { }
        }
        return DEFAULT_MAX_TOOL_ROUNDS;
    }

    /**
     * Context-window estimate used to trigger conversation compaction at 60% utilisation.
     * {@code 0} explicitly disables automatic compaction.
     */
    public static int getAiContextWindowTokens() {
        if (INSTANCE == null) return DEFAULT_CONTEXT_WINDOW_TOKENS;
        String val = INSTANCE.readSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, null);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) { }
        }
        return DEFAULT_CONTEXT_WINDOW_TOKENS;
    }

    /**
     * The context window the compactor should actually use for one model. Precedence: an
     * explicit {@code ai.context_window_tokens} setting wins (the user sized their window
     * on purpose — including {@code 0} = compaction off); without a stored value the
     * bundled {@link fan.summer.fengyu.ai.config.ModelMetadataCatalog} supplies the model's
     * real window when it knows the id, and the legacy flat default remains the fallback.
     * This is what closes the quality gap where a 128k-class model was compacted at
     * 60% of a 32k default it never had.
     */
    public static int effectiveContextWindowTokens(String modelId) {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_CONTEXT_WINDOW_TOKENS_KEY, null);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException ignored) { }
        }
        return fan.summer.fengyu.ai.config.ModelMetadataCatalog.contextWindow(modelId)
                .orElse(DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    /**
     * Whether the model the chat loop is currently talking to accepts image input. Unknown
     * models resolve permissive (true): the strict-gateway media fallback already recovers
     * a text-only endpoint that rejects the attachment, while wrongly refusing would
     * silently degrade vision-capable models. Catalog-known text-only families
     * (deepseek, non-V GLM, non-VL Qwen, …) resolve false so the image read path can skip
     * the doomed request entirely.
     */
    public static boolean activeModelSupportsImages() {
        String modelId = activeModelId();
        return modelId == null
                || fan.summer.fengyu.ai.config.ModelMetadataCatalog.supportsImage(modelId)
                        .orElse(true);
    }

    /** The model id of the currently active mode (null when the mode is unknown). */
    public static String activeModelId() {
        return switch (getAiMode()) {
            case "openai" -> getAiOpenAiModel();
            case "anthropic" -> getAiAnthropicModel();
            case "deepseek" -> getAiDeepSeekModel();
            case "local" -> getAiOllamaModel();
            default -> null;
        };
    }

    /**
     * The output-token budget the request builders should send for one model. Precedence:
     * an explicit {@code ai.max_tokens} setting wins — EXCEPT the legacy 2048 default,
     * which the old settings form persisted whole-form far more often than any user chose
     * it deliberately, so it is treated as unset (this supersedes the earlier flat
     * 8192 upgrade: legacy installs now inherit the model's REAL output cap instead).
     * Without a usable stored value the bundled {@link fan.summer.fengyu.ai.config.ModelMetadataCatalog}
     * supplies the provider's published cap, and the flat default remains the fallback.
     * The loop additionally clamps this per round against the remaining context window
     * (see {@code ConversationCompactor.clampMaxOutputTokens}).
     */
    public static int effectiveMaxOutputTokens(String modelId) {
        String val = INSTANCE == null ? null : INSTANCE.readSetting(AI_MAX_TOKENS_KEY, null);
        if (val != null) {
            try {
                int parsed = Integer.parseInt(val);
                if (parsed > 0 && parsed != 2048) return parsed;
            } catch (NumberFormatException ignored) { }
        }
        return fan.summer.fengyu.ai.config.ModelMetadataCatalog.maxOutputTokens(modelId)
                .orElse(DEFAULT_MAX_TOKENS);
    }

    /** Dynamic tool loading mode: {@code auto} (default), {@code always}, or {@code off}. */
    public static String getAiToolLoadingMode() {
        if (INSTANCE == null) return fan.summer.fengyu.ai.tools.ToolLoadingPolicy.MODE_AUTO;
        return fan.summer.fengyu.ai.tools.ToolLoadingPolicy.normalizeMode(
                INSTANCE.readSetting(AI_TOOL_LOADING_MODE_KEY, null));
    }

    /** Visible-tool count above which {@code auto} mode enables dynamic tool loading. */
    public static int getAiToolLoadingThreshold() {
        if (INSTANCE == null) return fan.summer.fengyu.ai.tools.ToolLoadingPolicy.DEFAULT_THRESHOLD;
        String val = INSTANCE.readSetting(AI_TOOL_LOADING_THRESHOLD_KEY, null);
        if (val != null) {
            try { return fan.summer.fengyu.ai.tools.ToolLoadingPolicy.clampThreshold(Integer.parseInt(val)); }
            catch (NumberFormatException ignored) { }
        }
        return fan.summer.fengyu.ai.tools.ToolLoadingPolicy.DEFAULT_THRESHOLD;
    }
}
