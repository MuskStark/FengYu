package fan.summer.fengyu.ai.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The codex denial heuristic: keyword matrix, quick-reject exit codes, excerpt shape. */
class SandboxDenialDetectorTest {

    @Test
    void exitZeroAndQuickRejectCodesAreNeverDenials() {
        assertFalse(SandboxDenialDetector.likelyDenied(0, "operation not permitted"));
        assertFalse(SandboxDenialDetector.likelyDenied(2, "operation not permitted"));
        assertFalse(SandboxDenialDetector.likelyDenied(126, "permission denied"));
        assertFalse(SandboxDenialDetector.likelyDenied(127, "read-only file system"));
    }

    @Test
    void keywordCarryingFailuresAreLikelyDenials() {
        for (String keyword : new String[]{
                "mkdir: /etc/x: Read-only file system",
                "sh: proof.txt: Operation not permitted",
                "curl: (7) Couldn't connect — permission denied",
                "epic sandbox violation",
                "landlock: rule denied"}) {
            assertTrue(SandboxDenialDetector.likelyDenied(1, keyword), keyword);
        }
        assertFalse(SandboxDenialDetector.likelyDenied(1, "syntax error near unexpected token"));
        assertFalse(SandboxDenialDetector.likelyDenied(1, ""));
    }

    @Test
    void excerptQuotesTheFirstMatchingLineBounded() {
        String output = "building...\nmkdir: /etc/x: Read-only file system\nmore output";
        assertEquals("mkdir: /etc/x: Read-only file system",
                SandboxDenialDetector.denialExcerpt(output, 200));
        String longLine = "x".repeat(300) + " operation not permitted";
        assertTrue(SandboxDenialDetector.denialExcerpt(longLine, 40).endsWith("…"));
        assertEquals("", SandboxDenialDetector.denialExcerpt("nothing here", 40));
    }
}
