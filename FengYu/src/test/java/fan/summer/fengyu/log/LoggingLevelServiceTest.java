package fan.summer.fengyu.log;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoggingLevelServiceTest {
    @AfterEach
    void restoreLevel() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.INFO);
        context.getLogger("fan.summer").setLevel(ch.qos.logback.classic.Level.INFO);
        context.getLogger("plugin").setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    void persistsAndAppliesOneLevelToHostAndPluginNamespaces() {
        AtomicReference<String> persisted = new AtomicReference<>("INFO");
        LoggingLevelService service = new LoggingLevelService(new LoggingLevelService.Settings() {
            @Override public String read() { return persisted.get(); }
            @Override public void write(String level) { persisted.set(level); }
        });
        service.initialize();

        assertEquals("TRACE", service.setLevel("trace"));
        assertEquals("TRACE", persisted.get());
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertEquals(ch.qos.logback.classic.Level.TRACE,
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel());
        assertEquals(ch.qos.logback.classic.Level.TRACE, context.getLogger("fan.summer").getLevel());
        assertEquals(ch.qos.logback.classic.Level.TRACE, context.getLogger("plugin").getLevel());
    }

    @Test
    void rejectsUnknownLevel() {
        assertThrows(IllegalArgumentException.class, () -> LoggingLevelService.normalize("verbose"));
    }
}
