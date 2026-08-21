package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.AiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActivationStateTest {

    @Test
    void activationIsAdditiveEligibleOnlyAndCapped() {
        ToolActivationState state = new ToolActivationState(Set.of("a", "b"));

        assertTrue(state.activate("a"));
        assertTrue(state.activate("b"));
        // Re-activating and unknown names change nothing.
        assertFalse(state.activate("a"));
        assertFalse(state.activate("missing"));
        assertEquals(2, state.activatedCount());
        assertEquals(2, state.version()); // each real addition bumps the version once

        ToolActivationState full = new ToolActivationState(
                java.util.stream.IntStream.rangeClosed(1, 50)
                        .mapToObj(i -> "tool" + i).collect(java.util.stream.Collectors.toSet()));
        for (int i = 1; i <= ToolActivationState.MAX_ACTIVATED; i++) assertTrue(full.activate("tool" + i));
        assertTrue(full.isFull());
        assertFalse(full.activate("tool" + ToolActivationState.MAX_ACTIVATED + 1));
        assertEquals(ToolActivationState.MAX_ACTIVATED, full.activatedCount());
    }

    @Test
    void markerRoundTripsThroughParse() {
        String marker = ToolActivationState.markerFor(List.of("chrome__navigate", "chrome__shot"));
        assertTrue(marker.startsWith(ToolActivationState.MARKER_PREFIX));
        assertEquals(List.of("chrome__navigate", "chrome__shot"), ToolActivationState.parseMarker(marker));
        // Tolerates absent/malformed markers and extra prose around one.
        assertEquals(List.of(), ToolActivationState.parseMarker(null));
        assertEquals(List.of(), ToolActivationState.parseMarker("no marker here"));
        assertEquals(List.of("a", "b"),
                ToolActivationState.parseMarker("Activated 2 tools.\n[fengyu-activated: a, b]\nnext"));
        assertEquals(List.of(), ToolActivationState.parseMarker("[fengyu-activated: a, b"));
    }

    @Test
    void seedingFromHistoryFiltersEligibleAndKeepsNewestIntentFirst() {
        List<AiChatMessage> history = List.of(
                AiChatMessage.user("hello"),
                AiChatMessage.toolResult("t1", "search_tools",
                        "Activated 1 tool(s)\n[fengyu-activated: old_tool, removed_tool]"),
                AiChatMessage.assistant("ok"),
                AiChatMessage.toolResult("t2", "search_tools",
                        "Activated 1 tool(s)\n[fengyu-activated: newest_tool]"),
                // Non-loader tool results must be ignored even when they embed a marker.
                AiChatMessage.toolResult("t3", "web_fetch", "[fengyu-activated: fake_tool]"));

        ToolActivationState state = ToolActivationState.seedFrom(history,
                Set.of("old_tool", "newest_tool"));

        assertTrue(state.isActive("old_tool"));
        assertTrue(state.isActive("newest_tool"));
        assertFalse(state.isActive("removed_tool")); // no longer in the eligible catalog
        assertFalse(state.isActive("fake_tool"));    // marker outside a search_tools result
        assertEquals(2, state.activatedCount());
    }
}
