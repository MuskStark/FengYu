package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionRangeTest {
    @Test void evaluatesComparatorSetsAndAlternatives() {
        assertTrue(SemanticVersionRange.includes(">=4.0.0-beta.4 <5.0.0", "4.0.0-beta.4"));
        assertTrue(SemanticVersionRange.includes("<4.0.0 || >=5.0.0 <6.0.0", "5.2.1"));
        assertFalse(SemanticVersionRange.includes(">=4.0.0 <5.0.0", "4.0.0-beta.4"));
        assertFalse(SemanticVersionRange.includes(">=4.0.0 <5.0.0", "5.0.0"));
    }

    @Test void rejectsMalformedRanges() {
        assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes(">=4", "4.0.0"));
        assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes("=4.0.0 || malformed", "4.0.0"));
        assertFalse(SemanticVersionRange.isValid(">=4.0.0 ||"));
        assertFalse(SemanticVersionRange.isValid("=0.0.0 || malformed"));
    }

    // ---- npm-style shorthand (P3: ^ / ~ / wildcards) ----

    @Test void caretRangesBumpTheLeftmostNonZeroComponent() {
        assertTrue(SemanticVersionRange.includes("^4.1.2", "4.1.2"));
        assertTrue(SemanticVersionRange.includes("^4.1.2", "4.9.9"));
        assertFalse(SemanticVersionRange.includes("^4.1.2", "5.0.0"));
        assertFalse(SemanticVersionRange.includes("^4.1.2", "4.1.1"));
        // 0.x anchors: ^0.2.3 → [0.2.3, 0.3.0); ^0.0.3 → [0.0.3, 0.0.4)
        assertTrue(SemanticVersionRange.includes("^0.2.3", "0.2.9"));
        assertFalse(SemanticVersionRange.includes("^0.2.3", "0.3.0"));
        assertTrue(SemanticVersionRange.includes("^0.0.3", "0.0.3"));
        assertFalse(SemanticVersionRange.includes("^0.0.3", "0.0.4"));
        // Partial anchors: ^4.1 → [4.1.0, 5.0.0); ^4 → [4.0.0, 5.0.0); ^0 → [0.0.0, 1.0.0)
        assertTrue(SemanticVersionRange.includes("^4.1", "4.1.0"));
        assertFalse(SemanticVersionRange.includes("^4.1", "5.0.0"));
        assertTrue(SemanticVersionRange.includes("^4", "4.0.0"));
        assertFalse(SemanticVersionRange.includes("^4", "3.9.9"));
        assertTrue(SemanticVersionRange.includes("^0", "0.9.0"));
        assertFalse(SemanticVersionRange.includes("^0", "1.0.0"));
        // Prerelease anchors keep the same window (^4.1.2-beta.2 → >=4.1.2-beta.2 <5.0.0); this
        // engine's comparator semantics (not npm's prerelease-membership rule) apply.
        assertTrue(SemanticVersionRange.includes("^4.1.2-beta.2", "4.1.2-beta.3"));
        assertFalse(SemanticVersionRange.includes("^4.1.2-beta.2", "4.1.2-beta.1"));
    }

    @Test void tildeRangesBumpThePatchComponent() {
        assertTrue(SemanticVersionRange.includes("~4.1.2", "4.1.2"));
        assertTrue(SemanticVersionRange.includes("~4.1.2", "4.1.99"));
        assertFalse(SemanticVersionRange.includes("~4.1.2", "4.2.0"));
        assertFalse(SemanticVersionRange.includes("~4.1.2", "4.1.1"));
        assertTrue(SemanticVersionRange.includes("~4.1", "4.1.5"));
        assertFalse(SemanticVersionRange.includes("~4.1", "4.2.0"));
        assertTrue(SemanticVersionRange.includes("~4", "4.9.0"));
        assertFalse(SemanticVersionRange.includes("~4", "5.0.0"));
    }

    @Test void wildcardsMatchWholeMajorOrMinorWindows() {
        assertTrue(SemanticVersionRange.includes("*", "0.0.1"));
        assertTrue(SemanticVersionRange.includes("*", "99.99.99"));
        assertTrue(SemanticVersionRange.includes("*", "4.0.0-beta.1"));
        assertTrue(SemanticVersionRange.includes("x", "4.0.0"));
        assertTrue(SemanticVersionRange.includes("1.x", "1.9.9"));
        assertFalse(SemanticVersionRange.includes("1.x", "2.0.0"));
        assertFalse(SemanticVersionRange.includes("1.x", "0.9.9"));
        assertTrue(SemanticVersionRange.includes("1.2.x", "1.2.9"));
        assertFalse(SemanticVersionRange.includes("1.2.x", "1.3.0"));
        assertTrue(SemanticVersionRange.includes("1.*", "1.5.0"));
        // Bare numeric partials share the same windows: 1 → [1.0.0, 2.0.0), 1.2 → [1.2.0, 1.3.0).
        assertTrue(SemanticVersionRange.includes("1", "1.4.2"));
        assertFalse(SemanticVersionRange.includes("1", "2.0.0"));
        assertTrue(SemanticVersionRange.includes("1.2", "1.2.7"));
        assertFalse(SemanticVersionRange.includes("1.2", "1.3.0"));
    }

    @Test void shorthandComposesWithComparatorsAndAlternatives() {
        assertTrue(SemanticVersionRange.includes(">=3.0.0 ^4.1.0", "4.5.0"));
        assertFalse(SemanticVersionRange.includes(">=3.0.0 ^4.1.0", "3.5.0"));
        assertTrue(SemanticVersionRange.includes("~3.2.0 || ^4.0.0", "3.2.5"));
        assertTrue(SemanticVersionRange.includes("~3.2.0 || ^4.0.0", "4.7.0"));
        assertFalse(SemanticVersionRange.includes("~3.2.0 || ^4.0.0", "3.3.0"));
    }

    @Test void malformedTokensExplainTheSupportedSyntax() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes("^not-a-version", "4.0.0"));
        assertTrue(e.getMessage().contains("^4.1.2"),
            "the error must teach the supported shorthand syntax; got: " + e.getMessage());
        assertTrue(e.getMessage().contains("||"));
        assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes("~>", "4.0.0"));
        assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes("^1.2.3.4", "4.0.0"));
        assertFalse(SemanticVersionRange.isValid("^abc"));
        // Existing strictness is unchanged: operator tokens still need full versions.
        assertThrows(IllegalArgumentException.class,
            () -> SemanticVersionRange.includes(">=1.x", "4.0.0"));
    }
}
