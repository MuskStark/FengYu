package fan.summer.fengyu.plugin.runtime;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the PRODUCTION logback.xml into a fresh LoggerContext pointed at a temp fengyu.log.dir,
 * and asserts the unified plugin file contract: every {@code plugin.*} logger event lands in
 * the SINGLE {@code plugin.log} (the per-plugin {@code plugin-<id>.log} SiftingAppender fan-out
 * was retired with the consolidated log layout), and — additivity being false — the event
 * stays out of {@code fengyu.log}, where only host-side plugin lifecycle logging belongs.
 */
class PluginLogbackSiftingConfigTest {
    @TempDir Path temp;

    private LoggerContext context;

    @AfterEach
    void tearDown() {
        if (context != null) context.stop();
        System.clearProperty("fengyu.log.dir");
    }

    @Test
    void allPluginEventsLandInOnePluginFileAndStayOutOfTheHostLog() throws Exception {
        System.setProperty("fengyu.log.dir", temp.toString());
        context = new LoggerContext();
        // A fresh LoggerContext has no MDCAdapter until the SLF4J singleton binds one (which
        // only happens at host startup); appender subAppend unconditionally prepares the
        // event's MDC map, so without an adapter every append NPEs. In production the bound
        // context carries the adapter — wiring one here just mirrors that.
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(getClass().getResourceAsStream("/logback.xml"));
        } catch (JoranException e) {
            throw new IllegalStateException("logback.xml failed to parse", e);
        }

        context.getLogger("plugin.myplugin.stderr").info("[main] hello from worker");
        context.getLogger("plugin.another.worker").warn("[w1] another plugin speaking");

        Path unified = temp.resolve("plugin.log");
        assertTrue(Files.exists(unified), "plugin.log not created; dir=" + diagnose(temp));
        String content = Files.readString(unified);
        assertTrue(content.contains("plugin.myplugin.stderr") && content.contains("hello from worker"),
                "myplugin event missing; got: " + content);
        assertTrue(content.contains("plugin.another.worker") && content.contains("another plugin speaking"),
                "second plugin event missing; got: " + content);
        assertFalse(Files.exists(temp.resolve("plugin-myplugin.log")),
                "the per-plugin plugin-<id>.log fan-out must not exist; dir=" + diagnose(temp));

        Path hostLog = temp.resolve("fengyu.log");
        assertTrue(Files.exists(hostLog), "fengyu.log missing; dir=" + diagnose(temp));
        assertFalse(Files.readString(hostLog).contains("hello from worker"),
                "plugin worker output must not double into fengyu.log (additivity=false)");
        context.getLogger("fan.summer.fengyu.plugin.runtime").info("host-side lifecycle line");
        assertTrue(Files.readString(hostLog).contains("host-side lifecycle line"),
                "host plugin lifecycle logging still reaches fengyu.log");
    }

    private static String diagnose(Path dir) {
        try {
            return Files.list(dir).map(p -> p.getFileName().toString()).toList().toString();
        } catch (Exception e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }
}
