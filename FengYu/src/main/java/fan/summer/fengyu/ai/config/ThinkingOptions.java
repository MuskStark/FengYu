package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.config.ModelMetadataCatalog.ThinkingSpec;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Batch-B5 thinking injection: renders one {@link ThinkingSpec} style at one effective
 * level onto the provider options builders. The catalog is the single source of which
 * style a model speaks (data, not code — adding a vendor quirk is a JSON edit); this
 * class is the only place a style turns into request fields.
 *
 * <p>Level semantics: {@code "off"} disables thinking (or omits the fields where the
 * protocol has no disabled form); every other level passes through verbatim, so the
 * catalog decides whether a model offers {@code low/medium/high}, a boolean
 * {@code enabled}, or anything else. {@link #effectiveLevel} clamps the configured
 * level to the spec's list.
 */
public final class ThinkingOptions {

    private ThinkingOptions() {}

    /**
     * The level to actually send: configured when supported, else the spec default.
     * {@code null}/blank configured means UNSET — the spec default applies (legacy
     * behavior: capable models think); an explicit {@code "off"} stays "off".
     */
    public static String effectiveLevel(ThinkingSpec spec, String configured) {
        if (spec == null) return "off";
        String level = configured == null || configured.isBlank() ? spec.defaultLevel() : configured.trim();
        return spec.levels().contains(level) ? level : spec.defaultLevel();
    }

    /**
     * Renders the style onto an OpenAI-compatible options builder. An UNSET level
     * ({@code null}) renders NOTHING — matching the pre-B5 wire, where cloud
     * requests never carried thinking fields unless the user chose a level.
     */
    public static void applyOpenAi(OpenAiChatOptions.Builder builder, ThinkingSpec spec, String level) {
        if (spec == null || level == null || level.isBlank()) return;
        String effective = effectiveLevel(spec, level);
        boolean off = "off".equals(effective) || "none".equals(effective);
        Map<String, Object> extra = new HashMap<>();
        switch (spec.style()) {
            case "reasoning_effort" -> {
                if (!off) builder.reasoningEffort(effective);
            }
            case "thinking_type" -> extra.put("thinking",
                    Map.of("type", off ? "disabled" : "enabled".equals(effective) ? "enabled" : effective));
            case "enable_thinking" -> extra.put("enable_thinking", !off);
            case "shotgun" -> {
                // Fire every known OpenAI-compatible thinking shape at once; the
                // server honors the field it understands and ignores the rest.
                if (!off) builder.reasoningEffort(effective);
                extra.put("thinking", Map.of("type", off ? "disabled" : "enabled"));
                extra.put("enable_thinking", !off);
            }
            default -> { }
        }
        if (!extra.isEmpty()) builder.extraBody(extra);
    }

    /** Renders the style onto an Anthropic options builder; unset renders nothing. */
    public static void applyAnthropic(AnthropicChatOptions.Builder builder, ThinkingSpec spec,
            String level, int maxTokens) {
        if (spec == null || level == null || level.isBlank()) return;
        String effective = effectiveLevel(spec, level);
        boolean off = "off".equals(effective) || "none".equals(effective);
        switch (spec.style()) {
            // Anthropic requires max_tokens strictly above the thinking budget; a
            // quarter of the output cap (clamped to the protocol minimum) is safe.
            case "anthropic_enabled" -> {
                if (off) builder.thinkingDisabled();
                else builder.thinkingEnabled(Math.max(1024, Math.min(8192, maxTokens / 4)));
            }
            case "anthropic_adaptive" -> {
                if (!off) builder.thinkingAdaptive();
            }
            default -> { }
        }
    }

    /** Renders the style as Ollama's {@code think} option; null = omit the field. */
    public static ThinkOption ollamaThink(ThinkingSpec spec, String level) {
        if (spec == null) return null;
        boolean unset = level == null || level.isBlank();
        if (unset) {
            return "ollama_level".equals(spec.style())
                    ? new ThinkOption.ThinkLevel(spec.defaultLevel())
                    : ThinkOption.ThinkBoolean.ENABLED;
        }
        String effective = effectiveLevel(spec, level);
        if ("off".equals(effective)) return ThinkOption.ThinkBoolean.DISABLED;
        return switch (spec.style()) {
            case "ollama_level" -> new ThinkOption.ThinkLevel(effective);
            default -> ThinkOption.ThinkBoolean.ENABLED;
        };
    }
}
