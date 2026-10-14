package fan.summer.fengyu.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The startup contract of the fengyu.chat-resources caps: positive, ordered, hard-ceilinged. */
class ChatResourceLimitsTest {

    @Test
    void defaultsSatisfyTheContract() {
        ChatResourceLimits limits = ChatResourceLimits.defaults();
        assertEquals(1024L * 1024 * 1024, limits.getMaxSingleFileBytes());
        assertEquals(2L * 1024 * 1024 * 1024, limits.getMaxTreeBytes());
        assertEquals(4L * 1024 * 1024 * 1024, limits.getMaxScopeBytes());
        assertEquals(1024L * 1024 * 1024, limits.getToolArtifactsBytes());
    }

    @Test
    void anInvertedLadderFailsFast() {
        ChatResourceLimits inverted = new ChatResourceLimits();
        inverted.setMaxSingleFileBytes(DataSize.ofMegabytes(512));
        inverted.setMaxTreeBytes(DataSize.ofMegabytes(256));
        assertThrows(IllegalStateException.class, inverted::validate,
                "single > tree must never boot");
    }

    @Test
    void aSingleFilePastTheHardCeilingFailsFast() {
        ChatResourceLimits past = new ChatResourceLimits();
        past.setMaxSingleFileBytes(DataSize.ofGigabytes(3));
        past.setMaxTreeBytes(DataSize.ofGigabytes(4));
        past.setMaxScopeBytes(DataSize.ofGigabytes(8));
        assertThrows(IllegalStateException.class, past::validate,
                "the 2 GB hard ceiling is not configurable away");
    }

    @Test
    void humanRoundsToWholeUnits() {
        assertEquals("2 GB", ChatResourceLimits.human(2L * 1024 * 1024 * 1024));
        assertEquals("500 MB", ChatResourceLimits.human(500L * 1024 * 1024));
        assertEquals("1536 bytes", ChatResourceLimits.human(1536));
    }
}
