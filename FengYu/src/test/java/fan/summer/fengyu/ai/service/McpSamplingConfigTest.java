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
import org.springframework.ai.chat.prompt.Prompt;
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
}
