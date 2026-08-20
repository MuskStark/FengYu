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
}
