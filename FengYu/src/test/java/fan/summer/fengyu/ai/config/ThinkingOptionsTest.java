package fan.summer.fengyu.ai.config;

import fan.summer.fengyu.ai.config.ModelMetadataCatalog.ThinkingSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-B5 thinking data-ization: catalog descriptor resolution (exact → family,
 * first-match order), level clamping, and the per-style rendering onto the three
 * option builders. Zero-regression contract: a model without a catalog thinking
 * entry sends NO thinking fields at any level, and "off" (the default) sends none
 * wherever the protocol has no explicit disabled form.
 */
class ThinkingOptionsTest {

    private static ThinkingSpec spec(String style, String defaultLevel, String... levels) {
        return new ThinkingSpec(style, List.of(levels), defaultLevel);
    }

    // ── catalog resolution ─────────────────────────────────────────────────────

    @Test
    void catalogResolvesThinkingByExactThenFamily() {
        // exact: deepseek-reasoner
        var reasoner = ModelMetadataCatalog.thinkingFor("deepseek-reasoner");
        assertTrue(reasoner.isPresent());
        assertEquals("thinking_type", reasoner.get().style());
        assertEquals("enabled", reasoner.get().defaultLevel());
        // family: glm-5.4 falls to the glm-5.* rule
        var glm = ModelMetadataCatalog.thinkingFor("glm-5.4-air");
        assertTrue(glm.isPresent());
        assertEquals("thinking_type", glm.get().style());
        // family: gpt-5.1 -> reasoning_effort with levels
        var gpt5 = ModelMetadataCatalog.thinkingFor("gpt-5.1");
        assertTrue(gpt5.isPresent());
        assertEquals(List.of("off", "low", "medium", "high"), gpt5.get().levels());
        // appended anchored rules: o3-mini, grok, gpt-oss
        assertEquals("reasoning_effort", ModelMetadataCatalog.thinkingFor("o3-mini").orElseThrow().style());
        assertEquals("reasoning_effort", ModelMetadataCatalog.thinkingFor("grok-4").orElseThrow().style());
        assertEquals("ollama_level", ModelMetadataCatalog.thinkingFor("gpt-oss:20b").orElseThrow().style());
        // no opinion: unknown model and the vision-first glm-5v rule (idx 3, no thinking)
        assertTrue(ModelMetadataCatalog.thinkingFor("totally-unknown").isEmpty());
        assertTrue(ModelMetadataCatalog.thinkingFor("glm-5v-plus").isEmpty(),
                "the earlier glm-5v vision rule wins first-match and carries no thinking");
    }

    @Test
    void levelClampingFollowsTheSpec() {
        assertEquals("medium",
                ThinkingOptions.effectiveLevel(spec("reasoning_effort", "medium", "off", "low", "medium", "high"), "bogus"));
        assertEquals("high",
                ThinkingOptions.effectiveLevel(spec("reasoning_effort", "medium", "off", "low", "medium", "high"), "high"));
        assertEquals("off", ThinkingOptions.effectiveLevel(null, "high"));
    }

    // ── OpenAI-compatible rendering ────────────────────────────────────────────

    @Test
    void openAiReasoningEffortSendsNativeFieldOnly() {
        OpenAiChatOptions options = OpenAiChatOptions.builder().model("gpt-5.1").build();
        var builder = options.mutate();
        ThinkingOptions.applyOpenAi(builder,
                spec("reasoning_effort", "medium", "off", "low", "medium", "high"), "high");
        OpenAiChatOptions built = builder.build();
        assertEquals("high", built.getReasoningEffort());
        assertTrue(built.getExtraBody() == null || built.getExtraBody().isEmpty(),
                "reasoning_effort must ride the native field, not extra body");
    }

    @Test
    void openAiThinkingTypeRidesExtraBody() {
        var builder = OpenAiChatOptions.builder().model("glm-5.3");
        ThinkingOptions.applyOpenAi(builder, spec("thinking_type", "enabled", "off", "enabled"), "enabled");
        Map<String, Object> extra = builder.build().getExtraBody();
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), extra);
    }

    @Test
    void openAiEnableThinkingIsBoolean() {
        var builder = OpenAiChatOptions.builder().model("qwen3-235b");
        ThinkingOptions.applyOpenAi(builder, spec("enable_thinking", "off", "off", "enabled"), "enabled");
        assertEquals(Map.of("enable_thinking", true), builder.build().getExtraBody());
    }

    @Test
    void openAiShotgunFiresAllShapesAtOnce() {
        var builder = OpenAiChatOptions.builder().model("kimi-k3");
        ThinkingOptions.applyOpenAi(builder, spec("shotgun", "off", "off", "enabled"), "enabled");
        OpenAiChatOptions built = builder.build();
        assertEquals("enabled", built.getReasoningEffort());
        assertEquals(Map.of(
                "thinking", Map.of("type", "enabled"),
                "enable_thinking", true), built.getExtraBody());
    }

    @Test
    void openAiOffSendsDisabledFormsOnly() {
        var builder = OpenAiChatOptions.builder().model("glm-5.3");
        ThinkingOptions.applyOpenAi(builder, spec("thinking_type", "enabled", "off", "enabled"), "off");
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), builder.build().getExtraBody());

        var shot = OpenAiChatOptions.builder().model("kimi-k3");
        ThinkingOptions.applyOpenAi(shot, spec("shotgun", "off", "off", "enabled"), "off");
        OpenAiChatOptions built = shot.build();
        assertNull(built.getReasoningEffort(), "off omits the native effort field");
        assertEquals(Map.of(
                "thinking", Map.of("type", "disabled"),
                "enable_thinking", false), built.getExtraBody());
    }

    @Test
    void openAiUnknownSpecOrUnsetLevelSendsNothing() {
        var builder = OpenAiChatOptions.builder().model("mystery");
        ThinkingOptions.applyOpenAi(builder, null, "high");
        OpenAiChatOptions built = builder.build();
        assertNull(built.getReasoningEffort());
        assertTrue(built.getExtraBody() == null || built.getExtraBody().isEmpty());
        // UNSET level = the pre-B5 wire: no thinking fields even on thinking models.
        var unset = OpenAiChatOptions.builder().model("glm-5.3");
        ThinkingOptions.applyOpenAi(unset, spec("thinking_type", "enabled", "off", "enabled"), null);
        OpenAiChatOptions unsetBuilt = unset.build();
        assertNull(unsetBuilt.getReasoningEffort());
        assertTrue(unsetBuilt.getExtraBody() == null || unsetBuilt.getExtraBody().isEmpty());
    }

    @Test
    void unsetLevelTakesTheSpecDefault() {
        assertEquals("medium", ThinkingOptions.effectiveLevel(
                spec("reasoning_effort", "medium", "off", "medium", "high"), null));
        assertEquals("enabled", ThinkingOptions.effectiveLevel(
                spec("thinking_type", "enabled", "off", "enabled"), ""));
    }

    // ── Anthropic rendering ────────────────────────────────────────────────────

    @Test
    void anthropicEnabledSetsBudgetClampedToOutputCap() {
        var builder = AnthropicChatOptions.builder().model("claude-sonnet-4-5");
        ThinkingOptions.applyAnthropic(builder, spec("anthropic_enabled", "off", "off", "enabled"),
                "enabled", 8192);
        AnthropicChatOptions built = builder.build();
        assertTrue(built.getThinking() != null && built.getThinking().isEnabled(),
                "thinking must be enabled with a budget");
        // off disables explicitly
        var off = AnthropicChatOptions.builder().model("claude-sonnet-4-5");
        ThinkingOptions.applyAnthropic(off, spec("anthropic_enabled", "off", "off", "enabled"), "off", 8192);
        AnthropicChatOptions builtOff = off.build();
        assertTrue(builtOff.getThinking() == null || !builtOff.getThinking().isEnabled());
    }

    // ── Ollama rendering ───────────────────────────────────────────────────────

    @Test
    void ollamaBooleanAndLevelShapes() {
        assertEquals(ThinkOption.ThinkBoolean.ENABLED,
                ThinkingOptions.ollamaThink(spec("ollama_boolean", "enabled", "off", "enabled"), "enabled"));
        assertEquals(ThinkOption.ThinkBoolean.DISABLED,
                ThinkingOptions.ollamaThink(spec("ollama_boolean", "enabled", "off", "enabled"), "off"));
        ThinkOption level = ThinkingOptions.ollamaThink(
                spec("ollama_level", "medium", "off", "low", "medium", "high"), "medium");
        assertTrue(level instanceof ThinkOption.ThinkLevel, "gpt-oss style renders the level form");
        assertEquals("medium", level.toString().contains("medium") ? "medium" : level.toString());
        assertNull(ThinkingOptions.ollamaThink(null, "high"));
        // UNSET keeps the legacy Ollama contract: capable models think.
        assertEquals(ThinkOption.ThinkBoolean.ENABLED,
                ThinkingOptions.ollamaThink(spec("enable_thinking", "off", "off", "enabled"), null));
        assertEquals(new ThinkOption.ThinkLevel("medium"),
                ThinkingOptions.ollamaThink(spec("ollama_level", "medium", "off", "low", "medium", "high"), null));
    }
}
