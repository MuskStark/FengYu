package fan.summer.fengyu.ai.service;

import fan.summer.fengyu.ai.AiStreamCallback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasoningForwarderTest {

    private static final class CollectingCallback implements AiStreamCallback {
        final List<String> thinking = new ArrayList<>();

        @Override public void onToken(String fragment) { }

        @Override public void onThinking(String fragment) {
            thinking.add(fragment);
        }
    }

    @Test
    void accumulatedModeEmitsOnlyTheUnseenSuffix() {
        ReasoningForwarder forwarder = ReasoningForwarder.accumulated();
        CollectingCallback callback = new CollectingCallback();

        forwarder.offer("Let me ", callback);
        forwarder.offer("Let me check ", callback);
        forwarder.offer("Let me check the file.", callback);

        assertEquals(List.of("Let me ", "check ", "the file."), callback.thinking);
    }

    @Test
    void accumulatedModeSkipsReplayedOrShorterValues() {
        ReasoningForwarder forwarder = ReasoningForwarder.accumulated();
        CollectingCallback callback = new CollectingCallback();

        forwarder.offer("abc", callback);
        forwarder.offer("abc", callback);   // replay of the same accumulation
        forwarder.offer("ab", callback);    // defensive: shorter than what was sent

        assertEquals(List.of("abc"), callback.thinking);
    }

    @Test
    void deltaModeForwardsEachFragmentVerbatim() {
        ReasoningForwarder forwarder = ReasoningForwarder.delta();
        CollectingCallback callback = new CollectingCallback();

        forwarder.offer("first ", callback);
        forwarder.offer("second", callback);

        assertEquals(List.of("first ", "second"), callback.thinking);
    }

    @Test
    void nonStringAndEmptyFragmentsAreIgnored() {
        ReasoningForwarder accumulated = ReasoningForwarder.accumulated();
        ReasoningForwarder delta = ReasoningForwarder.delta();
        CollectingCallback callback = new CollectingCallback();

        accumulated.offer(null, callback);
        accumulated.offer("", callback);
        accumulated.offer(List.of("not", "a", "string"), callback);
        delta.offer(null, callback);
        delta.offer("", callback);

        assertEquals(List.of(), callback.thinking);
    }

    @Test
    void freshInstanceResetsAccumulationBookkeeping() {
        CollectingCallback firstRound = new CollectingCallback();
        ReasoningForwarder.accumulated().offer("abc", firstRound);

        // A new round (new forwarder) must not inherit the previous round's sent length.
        CollectingCallback secondRound = new CollectingCallback();
        ReasoningForwarder.accumulated().offer("abcdef", secondRound);

        assertEquals(List.of("abc"), firstRound.thinking);
        assertEquals(List.of("abcdef"), secondRound.thinking);
    }
}
