package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import fan.summer.fengyu.plugin.offlinepython.infra.PythonDetector;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PythonDetectorTest {

    @Test
    void parsesPythonVersionLine() {
        assertEquals("3.12.10", PythonDetector.parsePythonVersion("Python 3.12.10").orElseThrow());
    }

    @Test
    void parsesPythonVersionLineWithExtra() {
        assertEquals("3.11.4",
            PythonDetector.parsePythonVersion("Python 3.11.4 (main, Aug 1 2023)").orElseThrow());
    }

    @Test
    void returnsEmptyForGarbage() {
        assertTrue(PythonDetector.parsePythonVersion("not a version").isEmpty());
    }

    @Test
    void parsesPipVersionLine() {
        // "pip 25.0 from /usr/.../pip (python 3.12)"
        assertEquals(Optional.of("25.0"), PythonDetector.parsePipVersion("pip 25.0 from /x (python 3.12)"));
    }

    @Test
    void versionIsAtLeast() {
        assertTrue(PythonDetector.isAtLeast("3.12.10", "3.10"));
        assertFalse(PythonDetector.isAtLeast("3.9.1", "3.10"));
    }
}
