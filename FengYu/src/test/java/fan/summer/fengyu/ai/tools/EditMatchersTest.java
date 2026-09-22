package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.tools.EditMatchers.Ambiguous;
import fan.summer.fengyu.ai.tools.EditMatchers.Matched;
import fan.summer.fengyu.ai.tools.EditMatchers.MatchResult;
import fan.summer.fengyu.ai.tools.EditMatchers.NotFound;
import fan.summer.fengyu.ai.tools.EditMatchers.Strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for the progressive match waterfall ported from ZCode's edit-matchers
 * (Apache-2.0): strategy order, ambiguity rejection, replace-all restrictions, and the
 * quote/escape normalizers' round trips.
 */
class EditMatchersTest {

    private static Matched matched(MatchResult result) {
        return assertInstanceOf(Matched.class, result);
    }

    @Test
    void exactMatchWinsBeforeAnyFallback() {
        Matched match = matched(EditMatchers.findEditMatch("abc def abc", "abc", false));
        assertEquals(Strategy.EXACT, match.strategy());
        assertEquals(2, match.candidateCount()); // both occurrences, one distinct value
        assertEquals("abc", match.actualString());
    }

    @Test
    void curlyQuotesNormalizeToStraightAndBack() {
        String content = "say “hello” now";
        Matched match = matched(EditMatchers.findEditMatch(content, "say \"hello\" now", false));
        assertEquals(Strategy.QUOTE_NORMALIZED, match.strategy());
        assertEquals("say “hello” now", match.actualString());
        String replacement = EditMatchers.preserveQuoteStyle(
                "say \"hello\" now", match.actualString(), "say \"hi\" there");
        assertEquals("say “hi” there", replacement);
    }

    @Test
    void readLineNumberPrefixesAreStripped() {
        String content = "alpha\nbeta\ngamma";
        String search = "12\tbeta\n13\tgamma";
        Matched match = matched(EditMatchers.findEditMatch(content, search, false));
        assertEquals(Strategy.LINE_NUMBER_PREFIX_STRIPPED, match.strategy());
        assertEquals("beta\ngamma", match.actualString());
    }

    @Test
    void lineNumberPrefixRequiresEveryLineNumbered() {
        // Only one of the two lines carries a prefix — the strategy must not fire.
        assertInstanceOf(NotFound.class,
                EditMatchers.findEditMatch("beta gamma", "beta\n13\tgamma", false));
    }

    @Test
    void visibleEscapesUnescapeBeforeMatching() {
        Matched match = matched(EditMatchers.findEditMatch("line1\nline2", "line1\\nline2", false));
        assertEquals(Strategy.ESCAPE_NORMALIZED, match.strategy());
        String replacement = EditMatchers.normalizeReplacementForMatch(
                Strategy.ESCAPE_NORMALIZED, "kept\\ttab");
        assertEquals("kept\ttab", replacement);
    }

    @Test
    void unicodeEscapesUnescapeToRealCharacters() {
        Matched match = matched(EditMatchers.findEditMatch("caf\u00e9 menu", "caf\\u00e9 menu", false));
        assertEquals(Strategy.UNICODE_ESCAPE_NORMALIZED, match.strategy());
        assertEquals("caf\u00e9 menu", match.actualString());
    }

    @Test
    void escapedBackslashIsNotAUnicodeEscape() {
        // \\\\u0041 in the search is a literal backslash before u — must not become 'A'.
        assertInstanceOf(NotFound.class,
                EditMatchers.findEditMatch("plain", "\\\\u0041plain", false));
    }

    @Test
    void lineTrimmedMatchesIndentationDrift() {
        String content = "    alpha\n        beta\n    gamma";
        Matched match = matched(EditMatchers.findEditMatch(content, "  alpha\n      beta\n  gamma", false));
        assertEquals(Strategy.LINE_TRIMMED, match.strategy());
        assertTrue(match.actualString().startsWith("    alpha"));
    }

    @Test
    void indentationDriftMatchesThroughLineTrimming() {
        String content = "one\n    inner1\n        deep\n    inner2\ntwo";
        // Per-line trim equality holds before the common-indent strategy is ever needed.
        Matched match = matched(EditMatchers.findEditMatch(content,
                "inner1\n      deep\ninner2", false));
        assertEquals(Strategy.LINE_TRIMMED, match.strategy());
        assertEquals("    inner1\n        deep\n    inner2", match.actualString());
    }

    @Test
    void blockAnchorToleratesMiddleEditsAboveSimilarityFloor() {
        String content = "def process(items):\n    total = 0\n    for item in items:\n        total += item\n    return total";
        // The middle line drifted; anchors (first/last) are intact.
        Matched match = matched(EditMatchers.findEditMatch(content,
                "def process(items):\n    total = 0.0\n    for item in items:\n        total += item\n    return total", false));
        assertEquals(Strategy.BLOCK_ANCHOR, match.strategy());
    }

    @Test
    void blockAnchorRejectsLowSimilarity() {
        String content = "start\naaaa\nbbbb\ncccc\nend";
        assertInstanceOf(NotFound.class, EditMatchers.findEditMatch(content,
                "start\nxxxx\nyyyy\nzzzz\nend", false));
    }

    @Test
    void blockAnchorNeedsThreeLines() {
        assertInstanceOf(NotFound.class,
                EditMatchers.findEditMatch("a\nb", "x\ny", false));
    }

    @Test
    void distinctMultipleCandidatesAreAmbiguous() {
        // The straight-quote search matches BOTH a left-curly and a right-curly quoted line
        // after normalization — two DIFFERENT file texts, so the matcher must refuse.
        String content = "msg(‘hi’)\nmsg(’hi’)";
        Ambiguous ambiguous = assertInstanceOf(Ambiguous.class,
                EditMatchers.findEditMatch(content, "msg('hi')", false));
        assertEquals(Strategy.QUOTE_NORMALIZED, ambiguous.strategy());
        assertEquals(2, ambiguous.candidateCount());
    }

    @Test
    void identicalDuplicatesStayAMatchForReplaceAll() {
        String content = "value = 1\nvalue = 1";
        Matched match = matched(EditMatchers.findEditMatch(content, "value = 1", true));
        assertEquals(2, match.candidateCount());
    }

    @Test
    void broadStrategiesAreSkippedForReplaceAll() {
        // Exact match absent; the only possible hit is the line-trimmed fallback, which
        // replace_all must not use.
        String content = "    alpha\n  beta";
        assertInstanceOf(NotFound.class,
                EditMatchers.findEditMatch(content, "alpha\n    beta", true));
        // Without replace_all the line-trimmed fallback applies.
        Matched match = matched(EditMatchers.findEditMatch(content, "alpha\n    beta", false));
        assertEquals(Strategy.LINE_TRIMMED, match.strategy());
    }

    @Test
    void crlfIsNormalizedForMatching() {
        assertEquals("a\nb", EditMatchers.normalizeLineEndings("a\r\nb"));
    }

    @Test
    void emptySearchFindsNothing() {
        assertInstanceOf(NotFound.class, EditMatchers.findEditMatch("content", "", false));
    }
}
