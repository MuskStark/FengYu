package fan.summer.fengyu.ai.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TurnSteering contract: bounded parking, drain-once semantics, byte cap, disconnect
 * clear. The lost-update shape (offer racing drain) is pinned by the never-remove-empty
 * rule — see the javadoc there.
 */
class TurnSteeringTest {

    @Test
    void offerRejectsBlankAndNullConversation() {
        assertFalse(TurnSteering.offer(1L, null));
        assertFalse(TurnSteering.offer(1L, "  "));
        assertFalse(TurnSteering.offer(null, "hello"));
    }

    @Test
    void drainsInSendOrderAndClears() {
        TurnSteering.offer(2L, "first");
        TurnSteering.offer(2L, "second");
        assertEquals(List.of("first", "second"), TurnSteering.drain(2L));
        assertEquals(List.of(), TurnSteering.drain(2L));
    }

    @Test
    void conversationsAreIsolated() {
        TurnSteering.offer(3L, "mine");
        assertEquals(List.of(), TurnSteering.drain(4L));
        assertEquals(List.of("mine"), TurnSteering.drain(3L));
    }

    @Test
    void pendingCapRejectsBeyondThree() {
        try {
            assertTrue(TurnSteering.offer(5L, "1"));
            assertTrue(TurnSteering.offer(5L, "2"));
            assertTrue(TurnSteering.offer(5L, "3"));
            assertFalse(TurnSteering.offer(5L, "4"));
            assertEquals(3, TurnSteering.drain(5L).size());
        } finally {
            TurnSteering.clear(5L);
        }
    }

    @Test
    void byteCapRejectsOversizedMessages() {
        assertTrue(TurnSteering.withinByteCap("short"));
        assertFalse(TurnSteering.withinByteCap("x".repeat(TurnSteering.MAX_BYTES + 1)));
        // CJK counts BYTES not chars: 3 bytes per char must trip the cap with fewer
        // than MAX_BYTES characters — the exact trap a chars-based check would miss.
        int cjkChars = TurnSteering.MAX_BYTES / 3 + 2;
        assertTrue(cjkChars * 3 > TurnSteering.MAX_BYTES);
        assertFalse(TurnSteering.withinByteCap("一".repeat(cjkChars)));
    }

    @Test
    void clearDropsParkedInput() {
        TurnSteering.offer(6L, "gone");
        TurnSteering.clear(6L);
        assertEquals(List.of(), TurnSteering.drain(6L));
    }
}
