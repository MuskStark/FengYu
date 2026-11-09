package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the sampling bridge's request shaping: hint routing, image passthrough,
 * tunable token budgets, and the plain text/system/temperature passthrough that predates them.
 */
class McpSamplingConfigTest {

    private String budgetProperty;

    static final class CapturingModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"ok\":true}"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    @AfterEach
    void restoreBudget() {
        if (budgetProperty != null) System.setProperty(McpSamplingConfig.MAX_TOKENS_PROPERTY, budgetProperty);
        else System.clearProperty(McpSamplingConfig.MAX_TOKENS_PROPERTY);
    }

    private static McpSchema.CreateMessageRequest textRequest(String text) {
        return McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent(text))))
                .maxTokens(256)
                .build();
    }

    private static McpSchema.ModelPreferences hints(String... names) {
        McpSchema.ModelPreferences.Builder builder = McpSchema.ModelPreferences.builder();
        for (String name : names) builder.addHint(name);
        return builder.build();
    }

    @Test
    void cloudModeHonorsHintNamingTheConfiguredModel() {
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("judge this loan"))))
                .modelPreferences(hints("gpt-4o", "DeepSeek-Chat"))
                .maxTokens(512)
                .build();
        McpSchema.CreateMessageResult result = McpSamplingConfig.sample(
                model, request, "deepseek-chat", "deepseek", "deepseek-chat", false);
        assertEquals("deepseek-chat", model.prompts.get(0).getOptions().getModel());
        assertEquals("deepseek-chat", result.model());
    }

    @Test
    void cloudModeIgnoresForeignHintAndServesTheConfiguredModel() {
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("judge"))))
                .modelPreferences(hints("claude-opus-4"))
                .maxTokens(512)
                .build();
        McpSchema.CreateMessageResult result = McpSamplingConfig.sample(
                model, request, "deepseek-chat", "deepseek", "deepseek-chat", false);
        assertNull(model.prompts.get(0).getOptions().getModel());
        assertEquals("deepseek-chat", result.model());
    }

    @Test
    void localModePassesFirstHintThrough() {
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("judge"))))
                .modelPreferences(hints("llama3.2-vision", "qwen3:8b"))
                .maxTokens(512)
                .build();
        McpSchema.CreateMessageResult result = McpSamplingConfig.sample(
                model, request, "qwen3:8b", "local", "qwen3:8b", true);
        assertEquals("llama3.2-vision", model.prompts.get(0).getOptions().getModel());
        assertEquals("llama3.2-vision", result.model());
    }

    @Test
    void imageContentRidesTheUserMessageAsMedia() {
        CapturingModel model = new CapturingModel();
        McpSchema.ImageContent image = new McpSchema.ImageContent(
                null, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}), "image/png");
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, image)))
                .maxTokens(128)
                .build();
        McpSamplingConfig.sample(model, request, "qwen3:8b", "local", "qwen3:8b", true);
        List<Message> sent = model.prompts.get(0).getInstructions();
        assertEquals(1, sent.size());
        UserMessage user = (UserMessage) sent.get(0);
        assertEquals(1, user.getMedia().size());
    }

    @Test
    void imageOnlyRequestFailsClosedOnTextOnlyModel() {
        CapturingModel model = new CapturingModel();
        McpSchema.ImageContent image = new McpSchema.ImageContent(
                null, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}), "image/png");
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, image)))
                .maxTokens(128)
                .build();
        assertThrows(McpRuntimeManager.McpRuntimeException.class,
                () -> McpSamplingConfig.sample(model, request, "deepseek-chat", "deepseek",
                        "deepseek-chat", false));
    }

    @Test
    void tokenCapClampsToTheDefaultBudget() {
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("long verdict please"))))
                .maxTokens(8192)
                .build();
        McpSamplingConfig.sample(model, request, "deepseek-chat", "deepseek", "deepseek-chat", false);
        assertEquals(McpSamplingConfig.DEFAULT_MAX_SAMPLING_TOKENS,
                model.prompts.get(0).getOptions().getMaxTokens());
    }

    @Test
    void raisedTokenBudgetLetsAgentGradeRequestsThrough() {
        budgetProperty = System.setProperty(McpSamplingConfig.MAX_TOKENS_PROPERTY, "16384");
        assertEquals(16384, McpSamplingConfig.maxSamplingTokens());
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("long verdict please"))))
                .maxTokens(8192)
                .build();
        McpSamplingConfig.sample(model, request, "deepseek-chat", "deepseek", "deepseek-chat", false);
        assertEquals(8192, model.prompts.get(0).getOptions().getMaxTokens());
    }

    @Test
    void systemPromptTemperatureAndTextPassThrough() {
        CapturingModel model = new CapturingModel();
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(
                        new McpSchema.SamplingMessage(McpSchema.Role.USER,
                                new McpSchema.TextContent("user turn")),
                        new McpSchema.SamplingMessage(McpSchema.Role.ASSISTANT,
                                new McpSchema.TextContent("assistant turn"))))
                .systemPrompt("be a green credit expert")
                .temperature(0.0)
                .maxTokens(512)
                .build();
        McpSchema.CreateMessageResult result = McpSamplingConfig.sample(
                model, request, "deepseek-chat", "deepseek", "deepseek-chat", false);
        List<Message> sent = model.prompts.get(0).getInstructions();
        assertEquals(3, sent.size());
        assertEquals("be a green credit expert", sent.get(0).getText());
        assertEquals("user turn", sent.get(1).getText());
        assertEquals("assistant turn", sent.get(2).getText());
        assertEquals(0.0, model.prompts.get(0).getOptions().getTemperature());
        assertEquals("{\"ok\":true}", ((McpSchema.TextContent) result.content()).text());
    }

    /**
     * 联调回归（4.1.0，green-credit-agent sampling 上行）：OpenAI 后端的 ChatModel 把
     * prompt options 硬强转为 OpenAiChatOptions——per-request 选项必须从 baseOptions
     * mutate 派生以保留具体类型，通用 DefaultChatOptions 会在调用时 ClassCastException。
     */
    @Test
    void samplingOptionsPreserveConcreteProviderTypeFromBaseOptions() {
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("judge"))))
                .maxTokens(512)
                .temperature(0.1)
                .build();
        ToolCallingChatOptions base = OpenAiChatOptions.builder()
                .model("deepseek-chat").maxTokens(8192).build();

        ChatOptions derived = McpSamplingConfig.samplingOptions(base, request, 4096, null);

        // 模拟 OpenAiChatModel.createRequest 的硬强转——降级为 DefaultChatOptions 即失败
        OpenAiChatOptions openai = (OpenAiChatOptions) derived;
        assertEquals(4096, openai.getMaxTokens());
        assertEquals(0.1, openai.getTemperature());
        assertEquals("deepseek-chat", openai.getModel());
    }

    @Test
    void samplingOptionsGenericFallbackWhenNoBaseOptions() {
        McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
                .messages(List.of(new McpSchema.SamplingMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent("judge"))))
                .maxTokens(256)
                .build();
        ChatOptions derived = McpSamplingConfig.samplingOptions(null, request, 256, null);
        assertEquals(256, derived.getMaxTokens());
    }

    // ── page sampling tool loop (`sampling.tools`) ─────────────────────────────

    /** Model stub whose single reply asks for a page tool instead of final text. */
    static final class ToolAskingModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            AssistantMessage output = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "pick_item", "{\"index\":2}")))
                    .build();
            return new ChatResponse(List.of(new Generation(output)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException();
        }
    }

    private static McpSamplingConfig.UiLoopRequest loopRequest(
            List<McpSamplingConfig.UiLoopMessage> messages, McpSamplingConfig.UiLoopTool... tools) {
        return new McpSamplingConfig.UiLoopRequest(messages, "pick wisely", 512, null,
                List.of(tools), null);
    }

    private static McpSamplingConfig.UiLoopTool tool(String name) {
        return new McpSamplingConfig.UiLoopTool(name, "picks an item",
                java.util.Map.of("type", "object", "properties", java.util.Map.of()));
    }

    @Test
    void toolLoopReturnsToolCallsForThePageToExecute() {
        ToolAskingModel model = new ToolAskingModel();
        McpSamplingConfig.UiLoopResult result = McpSamplingConfig.sampleToolLoop(
                model, loopRequest(List.of(new McpSamplingConfig.UiLoopMessage("user",
                        List.of(new McpSamplingConfig.UiLoopBlock("text", "pick one", null, null, null, null, null)))),
                        tool("pick_item")),
                "deepseek-chat", null, "deepseek", "deepseek-chat");
        assertEquals("toolUse", result.stopReason());
        assertEquals(1, result.toolCalls().size());
        assertEquals("call-1", result.toolCalls().get(0).id());
        assertEquals("pick_item", result.toolCalls().get(0).name());
        assertEquals(java.util.Map.of("index", 2), result.toolCalls().get(0).input());
    }

    @Test
    void toolLoopMapsToolUseAndToolResultHistoryIntoProviderMessages() {
        CapturingModel model = new CapturingModel();
        McpSamplingConfig.sampleToolLoop(model, new McpSamplingConfig.UiLoopRequest(List.of(
                new McpSamplingConfig.UiLoopMessage("user",
                        List.of(new McpSamplingConfig.UiLoopBlock("text", "pick one", null, null, null, null, null))),
                new McpSamplingConfig.UiLoopMessage("assistant", List.of(new McpSamplingConfig.UiLoopBlock(
                        "tool_use", null, "call-1", "pick_item", java.util.Map.of("index", 2), null, null))),
                new McpSamplingConfig.UiLoopMessage("user", List.of(new McpSamplingConfig.UiLoopBlock(
                        "tool_result", "{\"picked\":true}", null, null, null, "call-1", null)))),
                null, 512, null, List.of(tool("pick_item")), null),
                "deepseek-chat", null, "deepseek", "deepseek-chat");
        List<Message> sent = model.prompts.get(0).getInstructions();
        assertEquals(3, sent.size());
        AssistantMessage toolUse = (AssistantMessage) sent.get(1);
        assertEquals(1, toolUse.getToolCalls().size());
        assertEquals("call-1", toolUse.getToolCalls().get(0).id());
        assertEquals("{\"index\":2}", toolUse.getToolCalls().get(0).arguments());
        org.springframework.ai.chat.messages.ToolResponseMessage toolResult =
                (org.springframework.ai.chat.messages.ToolResponseMessage) sent.get(2);
        assertEquals(1, toolResult.getResponses().size());
        assertEquals("call-1", toolResult.getResponses().get(0).id());
        assertEquals("{\"picked\":true}", toolResult.getResponses().get(0).responseData());
    }

    @Test
    void toolLoopDeclaresToolsWithoutExecutingAndEndsTurnOnText() {
        CapturingModel model = new CapturingModel();
        McpSamplingConfig.UiLoopResult result = McpSamplingConfig.sampleToolLoop(
                model, loopRequest(List.of(new McpSamplingConfig.UiLoopMessage("user",
                        List.of(new McpSamplingConfig.UiLoopBlock("text", "just answer", null, null, null, null, null)))),
                        tool("pick_item")),
                "deepseek-chat", null, "deepseek", "deepseek-chat");
        assertEquals("endTurn", result.stopReason());
        assertTrue(result.toolCalls().isEmpty());
        assertEquals("{\"ok\":true}", result.text());
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts.get(0).getOptions();
        assertEquals(1, options.getToolCallbacks().size());
        assertEquals("pick_item", options.getToolCallbacks().get(0).getToolDefinition().name());
        assertTrue(options.getToolCallbacks().get(0).getToolDefinition().inputSchema()
                .contains("\"object\""));
    }

    @Test
    void toolLoopEnforcesToolCaps() {
        CapturingModel model = new CapturingModel();
        List<McpSamplingConfig.UiLoopTool> tooMany = new ArrayList<>();
        for (int i = 0; i <= McpSamplingConfig.MAX_LOOP_TOOLS; i++) tooMany.add(tool("t" + i));
        assertThrows(McpRuntimeManager.McpRuntimeException.class, () -> McpSamplingConfig.sampleToolLoop(
                model, new McpSamplingConfig.UiLoopRequest(List.of(new McpSamplingConfig.UiLoopMessage(
                        "user", List.of(new McpSamplingConfig.UiLoopBlock("text", "x", null, null, null, null, null)))),
                        null, 64, null, tooMany, null),
                "m", null, "deepseek", "deepseek-chat"));
        assertThrows(McpRuntimeManager.McpRuntimeException.class, () -> McpSamplingConfig.sampleToolLoop(
                model, new McpSamplingConfig.UiLoopRequest(List.of(new McpSamplingConfig.UiLoopMessage(
                        "user", List.of(new McpSamplingConfig.UiLoopBlock("text", "x", null, null, null, null, null)))),
                        null, 64, null, List.of(new McpSamplingConfig.UiLoopTool("bad name!", null, null)), null),
                "m", null, "deepseek", "deepseek-chat"));
    }

    @Test
    void loopOptionsPreserveConcreteProviderTypeAndToolChoice() {
        ToolCallingChatOptions base = OpenAiChatOptions.builder()
                .model("deepseek-chat").maxTokens(8192).build();
        ChatOptions derived = McpSamplingConfig.loopOptions(
                base, 4096, null, "required", List.of());
        OpenAiChatOptions openai = (OpenAiChatOptions) derived;
        assertEquals(4096, openai.getMaxTokens());
        assertEquals("required", openai.getToolChoice());
    }
}
