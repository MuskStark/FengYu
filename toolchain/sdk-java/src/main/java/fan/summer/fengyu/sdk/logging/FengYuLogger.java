package fan.summer.fengyu.sdk.logging;

import com.google.gson.Gson;
import fan.summer.fengyu.sdk.PluginLogging;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/** SLF4J logger that emits one structured FengYu event per stderr line. */
final class FengYuLogger extends AbstractLogger {
    private static final int MAX_TEXT_LENGTH = 32_768;
    private static final Gson JSON = new Gson();

    FengYuLogger(String name) {
        this.name = name;
    }

    @Override public boolean isTraceEnabled() { return PluginLogging.isEnabled(Level.TRACE); }
    @Override public boolean isDebugEnabled() { return PluginLogging.isEnabled(Level.DEBUG); }
    @Override public boolean isInfoEnabled() { return PluginLogging.isEnabled(Level.INFO); }
    @Override public boolean isWarnEnabled() { return PluginLogging.isEnabled(Level.WARN); }
    @Override public boolean isErrorEnabled() { return PluginLogging.isEnabled(Level.ERROR); }

    @Override public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    @Override public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    @Override public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    @Override public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }
    @Override public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }

    @Override
    protected String getFullyQualifiedCallerName() {
        return FengYuLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern,
            Object[] arguments, Throwable throwable) {
        if (!PluginLogging.isEnabled(level)) return;
        var formatted = MessageFormatter.arrayFormat(pattern, arguments, throwable);
        Map<String, String> event = new LinkedHashMap<>();
        event.put("level", level.toString());
        event.put("logger", abbreviate(name));
        event.put("thread", abbreviate(Thread.currentThread().getName()));
        event.put("message", abbreviate(formatted.getMessage()));
        Throwable effectiveThrowable = throwable != null ? throwable : formatted.getThrowable();
        if (effectiveThrowable != null) {
            StringWriter stack = new StringWriter();
            effectiveThrowable.printStackTrace(new PrintWriter(stack));
            event.put("throwable", abbreviate(stack.toString()));
        }
        System.err.println(PluginLogging.FRAME_PREFIX + JSON.toJson(event));
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH - 3) + "...";
    }
}
