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
}
