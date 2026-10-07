package fan.summer.fengyu.ai.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tightened fence-denial heuristic: only the fence backends' REAL rejection
 * fingerprints pop the escape card — the macOS Seatbelt kernel denial record
 * ({@code Sandbox: … deny(…)}) and bubblewrap's first-person lines / fence errno texts.
 * Generic keywords ("permission denied" in an ordinary failure, the word "sandbox"
 * mid-line) are deliberately NOT fingerprints: false positives here pop an approval
 * card for plain command failures.
 */
class SandboxDenialDetectorTest {

    @Test
    void exitZeroAndQuickRejectCodesAreNeverDenials() {
        assertFalse(SandboxDenialDetector.likelyDenied(0, "operation not permitted"));
        assertFalse(SandboxDenialDetector.likelyDenied(2, "operation not permitted"));
        assertFalse(SandboxDenialDetector.likelyDenied(126, "permission denied"));
        assertFalse(SandboxDenialDetector.likelyDenied(127, "read-only file system"));
        assertFalse(SandboxDenialDetector.likelyDenied(1, null));
        assertFalse(SandboxDenialDetector.likelyDenied(1, "  "));
    }

    @Test
    void seatbeltDenyRecordsAreDenials() {
        // The kernel's own denial record — the only macOS shape that counts.
        assertTrue(SandboxDenialDetector.likelyDenied(1,
                "Sandbox: exec(4213) deny(1) file-write* /Users/x/.zsh_history"));
        assertTrue(SandboxDenialDetector.likelyDenied(1, "noise\n"
                + "Sandbox: network-outbound(Private) deny(remote 127.0.0.1:24056)\n"
                + "more noise"));
        // A Seatbelt line WITHOUT a deny is not a denial record.
        assertFalse(SandboxDenialDetector.likelyDenied(1, "Sandbox: initialized profile"));
        // The word "sandbox" mid-line is not the kernel's record.
        assertFalse(SandboxDenialDetector.likelyDenied(1, "epic sandbox violation"));
    }

    @Test
    void bubblewrapFingerprintsAreDenials() {
        for (String line : new String[]{
                "bwrap: Can't mount proc on /newroot: Read-only file system",
                "bwrap: Setting up uid map failed: Operation not permitted",
                "mkdir: /etc/test: Read-only file system",
                "sh: proof.txt: Operation not permitted"}) {
            assertTrue(SandboxDenialDetector.likelyDenied(1, line), line);
        }
    }

    /**
     * The tightening's other side: an ordinary command failure whose output merely
     * CONTAINS "permission denied" (an unreadable file, a refused connection) must not
     * trigger the escape card — the old keyword list did exactly that.
     */
    @Test
    void ordinaryPermissionDeniedFailuresAreNotFenceDenials() {
        assertFalse(SandboxDenialDetector.likelyDenied(1,
                "curl: (7) Couldn't connect — permission denied"));
        assertFalse(SandboxDenialDetector.likelyDenied(1,
                "cat: /Users/x/private: permission denied"));
        assertFalse(SandboxDenialDetector.likelyDenied(1,
                "landlock: rule denied"));
        assertFalse(SandboxDenialDetector.likelyDenied(1, "seccomp filter blocked syscall"));
        assertFalse(SandboxDenialDetector.likelyDenied(1,
                "syntax error near unexpected token"));
    }

    @Test
    void excerptQuotesTheFirstMatchingLineBounded() {
        String output = "building...\nSandbox: exec(9) deny(1) file-write* /tmp/x\nmore output";
        assertEquals("Sandbox: exec(9) deny(1) file-write* /tmp/x",
                SandboxDenialDetector.denialExcerpt(output, 200));
        String longLine = "x".repeat(300) + " Read-only file system";
        assertTrue(SandboxDenialDetector.denialExcerpt(longLine, 40).endsWith("…"));
        assertEquals("", SandboxDenialDetector.denialExcerpt("nothing here", 40));
        assertEquals("", SandboxDenialDetector.denialExcerpt(
                "curl: (7) permission denied", 40),
                "an ordinary failure has no denial excerpt");
    }
}
