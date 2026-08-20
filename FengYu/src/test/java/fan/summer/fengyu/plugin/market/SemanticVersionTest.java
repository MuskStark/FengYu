package fan.summer.fengyu.plugin.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {
    @Test
    void followsSemverPrecedenceIncludingPrereleaseIdentifiers() {
        String[] ordered = {
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0"
        };
        for (int i = 1; i < ordered.length; i++) {
            assertTrue(SemanticVersion.compare(ordered[i], ordered[i - 1]) > 0,
                ordered[i] + " must be newer than " + ordered[i - 1]);
        }
    }

    @Test
    void ignoresBuildMetadataForPrecedence() {
        assertEquals(0, SemanticVersion.compare("1.2.3+linux", "1.2.3+mac"));
    }

    @Test
    void rejectsNonSemverAndLeadingZeroes() {
        assertFalse(SemanticVersion.isValid("1.0"));
        assertFalse(SemanticVersion.isValid("1.0.0-beta.01"));
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("v1.0.0"));
    }
}
