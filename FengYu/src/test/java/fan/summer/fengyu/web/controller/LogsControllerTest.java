package fan.summer.fengyu.web.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log panel's read surface: listing, whole-line tails, size caps, and the name jail. */
class LogsControllerTest {

    @TempDir
    Path logsDir;

    private LogsController controller() {
        // Overrides the directory resolution instead of mutating the fengyu.log.dir
        // system property, which other tests in the same JVM may read concurrently.
        return new LogsController() {
            @Override Path logDirectory() { return logsDir; }
        };
    }

    @Test
    void listsActiveLogFilesNewestFirstAndSkipsArchives() throws IOException {
        Files.writeString(logsDir.resolve("fengyu.log"), "one\n");
        Files.writeString(logsDir.resolve("plugin.log"), "two\n");
        Files.writeString(logsDir.resolve("fengyu.2026-09-01.log.gz"), "archived");
        Files.writeString(logsDir.resolve("notes.txt"), "not a log");

        List<LogsController.LogFileView> files = controller().list().files();

        assertEquals(List.of("plugin.log", "fengyu.log"),
                files.stream().map(LogsController.LogFileView::name).toList(),
                "active .log files only, newest first");
    }

    @Test
    void listOnAMissingDirectoryIsEmpty() throws IOException {
        LogsController.LogsOverviewView overview = controller().list();
        assertTrue(overview.files().isEmpty());
        assertTrue(overview.plugins().isEmpty());
    }

    @Test
    void pluginIdsComeFromTheLoggerColumnOfTheUnifiedPluginLog() throws IOException {
        Files.writeString(logsDir.resolve("plugin.log"), String.join("\n",
            "2026-09-27 08:10:01.100 INFO  [vthread-1] plugin.fan.summer.markdown.worker - started",
            "2026-09-27 08:10:02.312 WARN  [vthread-2] plugin.fan.summer.excel.worker - slow",
            "2026-09-27 08:10:05.777 INFO  [vthread-3] plugin.fan.summer.markdown.worker - invoke ok",
            "2026-09-27 08:10:09.001 ERROR [vthread-4] plugin.fan.summer.email.worker - smtp failed",
            "a bare line without a plugin logger column",
            "message mentioning plugin.something in prose only — leading space still logs it, accepted"));

        assertEquals(
            List.of("fan.summer.email", "fan.summer.excel", "fan.summer.markdown"),
            controller().list().plugins(),
            "ids are deduplicated, sorted, and the trailing source segment is stripped");
    }

    @Test
    void pluginIdsAreCachedUntilThePluginLogChanges() throws IOException {
        Path pluginLog = logsDir.resolve("plugin.log");
        Files.writeString(pluginLog,
            "2026-09-27 08:10:01.100 INFO  [t] plugin.alpha.worker - started\n");
        LogsController first = controller();
        assertEquals(List.of("alpha"), first.list().plugins());

        Files.writeString(pluginLog, String.join("\n",
            "2026-09-27 08:10:01.100 INFO  [t] plugin.alpha.worker - started",
            "2026-09-27 08:11:00.000 INFO  [t] plugin.beta.worker - started"));
        assertEquals(List.of("alpha", "beta"), first.list().plugins(),
            "a changed file (size/mtime) invalidates the cache");
    }

    @Test
    void tailReadsTheWholeFileWhenUnderTheCap() throws IOException {
        Files.writeString(logsDir.resolve("fengyu.log"), "line 1\nline 2\n");

        LogsController.LogTailView tail = controller().tail("fengyu.log", null);

        assertEquals("line 1\nline 2\n", tail.content());
        assertEquals(14, tail.size());
    }

    @Test
    void tailDropsTheLeadingPartialLineWhenCapped() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) body.append("row-").append(i).append("-padding\n");
        Files.writeString(logsDir.resolve("fengyu.log"), body);

        int cap = 128; // lands mid-row
        LogsController.LogTailView tail = controller().tail("fengyu.log", cap);

        assertTrue(tail.content().startsWith("row-"), "the leading partial row is dropped");
        assertFalse(tail.content().contains("padding\nrow-") && tail.content().endsWith("padding"),
                "content stays whole-line aligned");
        assertTrue(tail.content().length() <= cap + 64, "bounded near the requested cap");
    }

    @Test
    void tailCapIsClampedToTheHardMaximum() throws IOException {
        byte[] filler = new byte[300 * 1024];
        Files.write(logsDir.resolve("desktop.log"), filler);

        LogsController.LogTailView tail = controller().tail("desktop.log", 10 * 1024 * 1024);

        assertEquals(256 * 1024, tail.content().getBytes(StandardCharsets.UTF_8).length,
                "an oversized request is clamped, not honored");
    }

    @Test
    void namesAreJailedToTheLogDirectory() {
        LogsController controller = controller();
        assertThrows(IllegalArgumentException.class, () -> controller.tail("../fengyu.log", null));
        assertThrows(IllegalArgumentException.class, () -> controller.tail("sub/fengyu.log", null));
        assertThrows(IllegalArgumentException.class, () -> controller.tail("..\\fengyu.log", null));
        assertThrows(IllegalArgumentException.class, () -> controller.tail("fengyu.log.gz", null));
        assertThrows(IllegalArgumentException.class, () -> controller.tail(".", null));
        assertThrows(IllegalArgumentException.class, () -> controller.tail("", null));
    }

    @Test
    void tailOfAnUnknownFileIsAUserError() {
        assertThrows(IllegalArgumentException.class, () -> controller().tail("missing.log", null));
    }
}
