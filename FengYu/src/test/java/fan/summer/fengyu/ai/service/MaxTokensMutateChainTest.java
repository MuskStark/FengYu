package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiConfigService;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the round-clamp options chain on the CONCRETE provider options types, built by the
 * REAL {@link ChatModelConfig} factories: the tool callbacks attached by roundOptions must
 * survive the clamp's second mutate(), and the clamped value must land in each provider's
 * real field (maxTokens / numPredict). The ordinary loop tests cannot reach this — their
 * fake models carry no base options, so the default no-op withMaxTokens runs instead.
 */
class MaxTokensMutateChainTest {

    @BeforeEach
    void seedConfig() {
        // The factories read live sampling settings; empty settings → defaults, and the
        // output budget comes from the catalog for these well-known ids.
        new AiConfigService(Mockito.mock(AppSettingRepository.class),
                new NoopSecurityContext()).init();
    }

    @AfterEach
    void cleanConfig() {
        new AiConfigService(Mockito.mock(AppSettingRepository.class),
                new NoopSecurityContext()).init();
    }

    private static final ChatModel inert = new ChatModel() {
        @Override public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
        @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.never();
        }
    };

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name).description("").inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }
            @Override public String call(String input) { return "{}"; }
        };
    }

    @Test
    void openAiChainKeepsToolsAndAppliesClamp() {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(inert);
        ToolCallingChatOptions withTools = OpenAiChatOptions.builder()
                .model("gpt-4o").maxTokens(16_384)
                .toolCallbacks(tool("read_file")).build();
        ToolCallingChatOptions clamped = backend.withMaxTokens(withTools, 4_096);
        assertInstanceOf(OpenAiChatOptions.class, clamped);
        assertEquals(4_096, ((OpenAiChatOptions) clamped).getMaxTokens());
        assertEquals(1, clamped.getToolCallbacks().size());
        assertEquals("read_file", clamped.getToolCallbacks().getFirst()
                .getToolDefinition().name());
        assertEquals("gpt-4o", ((OpenAiChatOptions) clamped).getModel());
    }

    @Test
    void anthropicChainKeepsToolsAndAppliesClamp() {
        SpringAiCloudBackend backend = new SpringAiCloudBackend(inert);
        ToolCallingChatOptions withTools = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-20250514").maxTokens(64_000)
                .toolCallbacks(tool("grep")).build();
        ToolCallingChatOptions clamped = backend.withMaxTokens(withTools, 2_000);
        assertInstanceOf(AnthropicChatOptions.class, clamped);
        assertEquals(2_000, ((AnthropicChatOptions) clamped).getMaxTokens());
        assertEquals(1, clamped.getToolCallbacks().size());
    }

    @Test
    void ollamaChainKeepsToolsAndAppliesNumPredict() {
        OllamaLocalBackend backend = new OllamaLocalBackend();
        ToolCallingChatOptions withTools = OllamaChatOptions.builder()
                .model("qwen3:4b").numPredict(32_768)
                .toolCallbacks(tool("glob")).build();
        ToolCallingChatOptions clamped = backend.withMaxTokens(withTools, 1_024);
        assertInstanceOf(OllamaChatOptions.class, clamped);
        assertEquals(1_024, ((OllamaChatOptions) clamped).getNumPredict());
        assertEquals(1, clamped.getToolCallbacks().size());
    }

    @Test
    void factoryBuiltSummaryPromptCarriesABoundedBudget() {
        // The summarizer fires at 60%/85% of the window — a cap-sized budget would 400 on
        // validating providers exactly then, silently degrading summaries to truncation.
        // Built through the real factory so baseOptions carries a real catalog cap
        // (gpt-4o → 16384), then bounded through the same mutate hook summarizePrompt uses.
        fan.summer.fengyu.ai.config.ChatModelConfig.ResolvedModel resolved =
                fan.summer.fengyu.ai.config.ChatModelConfig
                        .buildOpenAiCompatible("https://api.openai.com", "test-key", "gpt-4o");
        assertEquals(Integer.valueOf(16_384), resolved.options().getMaxTokens());
        SpringAiCloudBackend backend = new SpringAiCloudBackend(inert);
        Integer baked = resolved.options().getMaxTokens();
        int capped = Math.min(baked == null ? SpringAiCloudBackend.SUMMARY_MAX_OUTPUT_TOKENS : baked,
                SpringAiCloudBackend.SUMMARY_MAX_OUTPUT_TOKENS);
        Prompt bounded = new Prompt(List.of(new UserMessage("transcript")),
                backend.withMaxTokens(resolved.options(), capped));
        ToolCallingChatOptions boundedOptions = (ToolCallingChatOptions) bounded.getOptions();
        assertTrue(boundedOptions.getMaxTokens() <= SpringAiCloudBackend.SUMMARY_MAX_OUTPUT_TOKENS,
                "summary budget must be bounded, got: " + boundedOptions.getMaxTokens());
    }
}
