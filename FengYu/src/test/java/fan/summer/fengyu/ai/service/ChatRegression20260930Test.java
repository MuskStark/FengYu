package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.AiStreamCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two live regressions from 2026-09-30, pinned:
 * (1) an IMMUTABLE caller history (the subagent runners passed {@code List.of(...)})
 *     must not crash the tool loop — the driver defensively copies;
 * (2) a thinking-model round's reasoning must ride the aggregated assistant message
 *     into the next request as {@code reasoning_content} metadata — DeepSeek 400s
 *     ("The reasoning_content in the thinking mode must be passed back to the API")
 *     the moment it is dropped.
 */
class ChatRegression20260930Test {

    /** Round 1: reasoning + a tool call; round 2: final text. Records every prompt. */
    static class ThinkingToolModel implements ChatModel {
        final AtomicInteger attempts = new AtomicInteger();
        final java.util.List<Prompt> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            if (attempts.incrementAndGet() == 1) {
                AssistantMessage reasoningChunk = AssistantMessage.builder()
                        .content("")
                        .properties(java.util.Map.of(
                                ToolLoopDriver.REASONING_METADATA_KEY, "step one, then step two"))
                        .build();
                AssistantMessage toolChunk = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call_1", "function", "echo", "{\"text\":\"hi\"}")))
                        .build();
                return Flux.just(
                        new ChatResponse(List.of(new Generation(reasoningChunk))),
                        new ChatResponse(List.of(new Generation(toolChunk))));
            }
            return Flux.just(new ChatResponse(
                    List.of(new Generation(new AssistantMessage("done")))));
        }
    }

    private static CountDownLatch[] run(SpringAiCloudBackend backend, List<AiChatMessage> history)
            throws fan.summer.fengyu.ai.AiServiceException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        backend.chat(history, 0.7f, 0.9f, 256, new AiStreamCallback() {
            @Override public void onToken(String fragment) { }
            @Override public void onComplete(String s, int t, double r) { done.countDown(); }
            @Override public void onError(Throwable t) {
                failure.set(t);
                done.countDown();
            }
        });
        return new CountDownLatch[]{done};
    }

    @Test
    void immutableCallerHistorySurvivesTheToolLoop() throws Exception {
        ThinkingToolModel model = new ThinkingToolModel();
        SpringAiCloudBackend backend = new SpringAiCloudBackend(model);
        backend.setToolCallbacks(List.of(new ChatClientToolLoopTest.EchoToolCallback()));

        // List.of is exactly what the subagent runners passed — immutable.
        List<AiChatMessage> history = List.of(AiChatMessage.user("hi"));
        CountDownLatch[] latch = run(backend, history);

        assertTrue(latch[0].await(15, TimeUnit.SECONDS), "the turn completes despite List.of");
        assertEquals(2, model.attempts.get(), "the tool round then the final answer");
    }

    @Test
    void reasoningRidesTheReplayedAssistantMessageIntoRoundTwo() throws Exception {
        ThinkingToolModel model = new ThinkingToolModel();
        SpringAiCloudBackend backend = new SpringAiCloudBackend(model);
        backend.setToolCallbacks(List.of(new ChatClientToolLoopTest.EchoToolCallback()));

        List<AiChatMessage> history = new java.util.ArrayList<>(List.of(AiChatMessage.user("hi")));
        CountDownLatch[] latch = run(backend, history);

        assertTrue(latch[0].await(15, TimeUnit.SECONDS));
        assertEquals(2, model.prompts.size(), "two rounds happened");
        Prompt roundTwo = model.prompts.get(1);
        AssistantMessage replayed = roundTwo.getInstructions().stream()
                .filter(msg -> msg instanceof AssistantMessage am && am.hasToolCalls())
                .map(msg -> (AssistantMessage) msg)
                .findFirst().orElse(null);
        assertNotNull(replayed, "round two replays the tool-carrying assistant message");
        Object reasoning = replayed.getMetadata().get(ToolLoopDriver.REASONING_METADATA_KEY);
        assertTrue(reasoning instanceof String text && text.contains("step two"),
                "the aggregated reasoning is re-attached: " + reasoning);
    }

    // The live 400 reproduced ONLY on the multi-call path: ToolBatchExecutor
    // synthesizes fresh per-call assistant messages, which dropped the reasoning
    // metadata. Two simultaneous tool calls = the real-world shape.
    @Test
    void multiCallRoundStillReplaysReasoningIntoRoundTwo() throws Exception {
        ThinkingToolModel model = new ThinkingToolModel() {
            @Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                if (attempts.incrementAndGet() == 1) {
                    AssistantMessage reasoningChunk = AssistantMessage.builder()
                            .content("")
                            .properties(java.util.Map.of(
                                    ToolLoopDriver.REASONING_METADATA_KEY,
                                    "multi-call reasoning"))
                            .build();
                    AssistantMessage toolChunk = AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(
                                    new AssistantMessage.ToolCall(
                                            "call_1", "function", "echo", "{\"text\":\"a\"}"),
                                    new AssistantMessage.ToolCall(
                                            "call_2", "function", "echo", "{\"text\":\"b\"}")))
                            .build();
                    return Flux.just(
                            new ChatResponse(List.of(new Generation(reasoningChunk))),
                            new ChatResponse(List.of(new Generation(toolChunk))));
                }
                return Flux.just(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("done")))));
            }
        };
        SpringAiCloudBackend backend = new SpringAiCloudBackend(model);
        backend.setToolCallbacks(List.of(new ChatClientToolLoopTest.EchoToolCallback()));

        List<AiChatMessage> history = new java.util.ArrayList<>(
                List.of(AiChatMessage.user("hi")));
        CountDownLatch[] latch = run(backend, history);

        assertTrue(latch[0].await(15, TimeUnit.SECONDS));
        assertEquals(2, model.prompts.size(), "two rounds");
        Prompt roundTwo = model.prompts.get(1);
        Object reasoning = roundTwo.getInstructions().stream()
                .filter(msg -> msg instanceof AssistantMessage am && am.hasToolCalls())
                .map(msg -> ((AssistantMessage) msg).getMetadata()
                        .get(ToolLoopDriver.REASONING_METADATA_KEY))
                .findFirst().orElse(null);
        assertTrue(reasoning instanceof String text && text.contains("multi-call reasoning"),
                "the batch-executor path preserved the reasoning: " + reasoning);
    }

    @Test
    void crossTurnRebuildCarriesReasoningThroughTheBridge() {
        AiChatMessage withReasoning = AiChatMessage.assistantWithReasoning(
                "answer", "why so");
        List<org.springframework.ai.chat.messages.Message> rebuilt =
                AiMessageBridge.toSpringAiMessages(withReasoning);
        AssistantMessage assistant = (AssistantMessage) rebuilt.getFirst();
        assertEquals("why so", assistant.getMetadata().get(
                ToolLoopDriver.REASONING_METADATA_KEY));
    }
}
