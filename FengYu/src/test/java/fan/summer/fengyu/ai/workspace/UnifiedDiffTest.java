package fan.summer.fengyu.ai.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unified-diff formatting: headers, hunk ranges, context window, and identity short-circuit. */
class UnifiedDiffTest {

    @Test
    void identicalContentProducesNoDiff() {
        assertEquals("", UnifiedDiff.diff("a.txt", "same\n", "same\n"));
    }

    @Test
    void singleLineChangeEmitsHeadersAndHunk() {
        String diff = UnifiedDiff.diff("src/A.java", "one\ntwo\nthree\n", "one\nTWO\nthree\n");
        assertTrue(diff.startsWith("--- a/src/A.java\n+++ b/src/A.java\n"));
        assertTrue(diff.contains("@@ -1,3 +1,3 @@"));
        assertTrue(diff.contains("-two\n"));
        assertTrue(diff.contains("+TWO\n"));
        assertTrue(diff.contains(" one\n"));
    }

    @Test
    void distantChangesProduceSeparateHunks() {
        String[] before = new String[31];
        String[] after = new String[31];
        for (int i = 1; i <= 30; i++) {
            before[i] = after[i] = "line" + i;
        }
        after[2] = "CHANGED-early";
        after[27] = "CHANGED-late";
        String diff = UnifiedDiff.diff("big.txt", String.join("\n", before) + "\n",
                String.join("\n", after) + "\n");
        assertTrue(diff.indexOf("@@") != diff.lastIndexOf("@@"), "expected at least two hunks");
        assertTrue(diff.contains("+CHANGED-early"));
        assertTrue(diff.contains("+CHANGED-late"));
    }

    @Test
    void addedFileHasOnlyAdditions() {
        String diff = UnifiedDiff.diff("new.txt", "", "a\nb\n");
        assertTrue(diff.contains("+a"));
        assertTrue(diff.contains("+b"));
        assertFalse(diff.contains("-a"));
    }

    @Test
    void truncationKicksInForHugeChanges() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            before.append("before-").append(i).append('\n');
            after.append("after-").append(i).append('\n');
        }
        String diff = UnifiedDiff.diff("huge.txt", before.toString(), after.toString());
        assertTrue(diff.contains("…diff truncated…"), "large diffs must be capped");
    }
}
