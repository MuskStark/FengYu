package fan.summer.fengyu.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two round-outcome predicates behind empty-completion retry (Z1) and
 * output-token continuation (Z2): a turn must retry ONLY the nothing-at-all completion,
 * and continue ONLY the budget-truncated answer — never the reverse.
 */
class ToolLoopDriverResilienceTest {

    private static ChatResponse response(String text, String finishReason) {
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(finishReason).build());
        return new ChatResponse(List.of(generation));
    }

    @Test
    void emptyCompletionRequiresNoTextNoToolsNoBilledTokens() {
        assertTrue(ToolLoopDriver.isEmptyCompletion(response("", "stop"), new StringBuilder()));
        assertTrue(ToolLoopDriver.isEmptyCompletion(null, new StringBuilder()));
        // Streamed text (however partial) means the round is NOT empty.
        assertFalse(ToolLoopDriver.isEmptyCompletion(response("", "stop"),
                new StringBuilder("partial")));
        // Tool calls are a productive round by definition.
        AssistantMessage withToolCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "t", "{}")))
                .build();
        Generation toolRound = new Generation(withToolCall,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build());
        ChatResponse toolResponse = new ChatResponse(List.of(toolRound));
        assertFalse(ToolLoopDriver.isEmptyCompletion(toolResponse, new StringBuilder()));
    }

    @Test
    void outputTruncatedMatchesLengthFinishReasonsAcrossProtocols() {
        assertTrue(ToolLoopDriver.isOutputTruncated(response("cut mid-sent", "length")));
        assertTrue(ToolLoopDriver.isOutputTruncated(response("cut mid-sent", "LENGTH")));
        assertTrue(ToolLoopDriver.isOutputTruncated(response("cut mid-sent", "max_tokens")));
        assertFalse(ToolLoopDriver.isOutputTruncated(response("done", "stop")));
        assertFalse(ToolLoopDriver.isOutputTruncated(response("no", null)));
        assertFalse(ToolLoopDriver.isOutputTruncated(null));
        // Content-filter stops share neither spelling — a refusal must not loop continuations.
        assertFalse(ToolLoopDriver.isOutputTruncated(response("no", "content_filter")));
    }
}
