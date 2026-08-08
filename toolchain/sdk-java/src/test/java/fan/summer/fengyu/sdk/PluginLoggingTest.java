package fan.summer.fengyu.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLoggingTest {
    private final PrintStream originalError = System.err;

    @AfterEach
    void restore() {
        System.setErr(originalError);
        PluginLogging.setLevel("INFO");
    }

    @Test
    void emitsAllEnabledSlf4jLevelsAsStructuredStderrFrames() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Logger logger = LoggerFactory.getLogger("com.example.Worker");
        PluginLogging.setLevel("TRACE");

        logger.trace("trace {}", 1);
        logger.debug("debug");
        logger.info("info");
        logger.warn("warn");
        logger.error("error", new IllegalStateException("boom"));

        var lines = captured.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(5, lines.size());
        assertTrue(lines.stream().allMatch(line -> line.startsWith(PluginLogging.FRAME_PREFIX)));
        JsonObject error = JsonParser.parseString(
            lines.getLast().substring(PluginLogging.FRAME_PREFIX.length())).getAsJsonObject();
        assertEquals("ERROR", error.get("level").getAsString());
        assertEquals("com.example.Worker", error.get("logger").getAsString());
        assertEquals("error", error.get("message").getAsString());
        assertTrue(error.get("throwable").getAsString().contains("IllegalStateException: boom"));
    }

    @Test
    void updatesExistingLoggerThresholdAtRuntime() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        Logger logger = LoggerFactory.getLogger("threshold");

        PluginLogging.setLevel("ERROR");
        assertFalse(logger.isWarnEnabled());
        logger.warn("hidden");
        PluginLogging.setLevel("DEBUG");
        assertTrue(logger.isDebugEnabled());
        logger.debug("visible");

        String output = captured.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("hidden"));
        assertTrue(output.contains("visible"));
    }

    @Test
    void rejectsUnknownLevel() {
        assertThrows(IllegalArgumentException.class, () -> PluginLogging.setLevel("verbose"));
    }

    /**
     * Regression guard for PluginLogging.severity(Level). The SLF4J 2.x Level enum has exactly
     * TRACE..ERROR, and isEnabled short-circuits OFF before reaching severity — so severity's
     * new default branch (which throws) is unreachable today. We cannot synthesize a new enum
     * constant in a test, so instead assert the observable invariant: for every (level,
     * threshold) pair, isEnabled matches level.severity >= threshold.severity. This walks all
     * five constants through severity() and catches a reordering.
     */
    @Test
    void severityMapsEachSlf4jLevel() {
        for (SeverityLattice threshold : SeverityLattice.values()) {
            PluginLogging.setLevel(threshold.name());
            for (SeverityLattice level : SeverityLattice.values()) {
                boolean expected = level.severity >= threshold.severity;
                org.slf4j.event.Level slf4j = org.slf4j.event.Level.valueOf(level.name());
                assertEquals(expected, PluginLogging.isEnabled(slf4j),
                    "level=" + level + " threshold=" + threshold);
            }
        }
    }

    private enum SeverityLattice {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);
        final int severity;
        SeverityLattice(int severity) { this.severity = severity; }
    }
}
