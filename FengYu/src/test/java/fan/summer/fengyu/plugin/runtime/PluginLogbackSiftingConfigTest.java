package fan.summer.fengyu.plugin.runtime;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.spi.MDCAdapter;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the PRODUCTION logback.xml into a fresh LoggerContext pointed at a temp fengyu.log.dir,
 * and asserts that a log event carrying MDC["pluginId"]=myplugin lands in plugin-myplugin.log via
 * the SiftingAppender — and that an event without the MDC key routes to the defaultValue bucket.
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
    void perPluginLogFileCreatedByMdcKey() throws Exception {
        System.setProperty("fengyu.log.dir", temp.toString());
        context = new LoggerContext();
        // The fresh LoggerContext has no MDCAdapter until the SLF4J singleton binds one to it
        // (which only happens at host startup). Give it its own so LoggingEvent.getMDCPropertyMap()
        // — read by MDCBasedDiscriminator — sees the values we put below. (In production the global
        // PluginProcessManager uses org.slf4j.MDC against the single bound context, which does the
        // same thing.) This is the only deviation from the brief: it exercises the REAL logback.xml.
        MDCAdapter mdc = new LogbackMDCAdapter();
        context.setMDCAdapter(mdc);
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(getClass().getResourceAsStream("/logback.xml"));
        } catch (JoranException e) {
            throw new IllegalStateException("logback.xml failed to parse", e);
        }

        mdc.put("pluginId", "myplugin");
        context.getLogger("plugin.myplugin.stderr").info("[main] hello from worker");
        mdc.remove("pluginId");

        Path pluginFile = temp.resolve("plugin-myplugin.log");
        assertTrue(Files.exists(pluginFile), "plugin-myplugin.log not created; dir=" + diagnose(temp));
        String content = Files.readString(pluginFile);
        assertTrue(content.contains("hello from worker"), "content missing; got: " + content);

        context.getLogger("plugin.orphan.stderr").info("no mdc here");
        assertTrue(Files.exists(temp.resolve("plugin-unknown.log")),
            "plugin-unknown.log (defaultValue bucket) not created; dir=" + diagnose(temp));
    }

    private static String diagnose(Path dir) {
        try {
            return Files.list(dir).map(p -> p.getFileName().toString()).toList().toString();
        } catch (Exception e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }
}
