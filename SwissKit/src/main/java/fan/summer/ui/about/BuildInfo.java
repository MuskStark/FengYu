package fan.summer.ui.about;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * Reads build metadata injected by Maven resource filtering from
 * {@code /build-info.properties} on the classpath. Exposes the app version and
 * the build timestamp. When the file is missing or still contains unfiltered
 * {@code ${...}} placeholders (i.e. launched from the IDE without Maven), the
 * accessors return {@code (dev)} / {@code (dev build)} so the UI never shows
 * raw placeholders.
 */
public final class BuildInfo {

    static final String DEV_VERSION = "(dev)";
    static final String DEV_BUILD_TIME = "(dev build)";
    private static final String RESOURCE = "/build-info.properties";
    private static final DateTimeFormatter BUILD_TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    static final BuildInfo INSTANCE = new BuildInfo(load(RESOURCE));

    private final Properties props;

    /** Test seam: build an info view over an explicit property set. */
    BuildInfo(Properties props) {
        this.props = props;
    }

    static Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // unreadable metadata is equivalent to a dev build
        }
        return p;
    }

    public static String getVersion() {
        return INSTANCE.value("app.version", DEV_VERSION);
    }

    public static String getBuildTime() {
        return INSTANCE.formatBuildTime(INSTANCE.value("build.time", DEV_BUILD_TIME));
    }

    /**
     * Reformats an ISO-8601 instant (Maven's {@code ${maven.build.timestamp}}) as
     * {@code yyyy-MM-dd HH:mm z} in the system zone. Any other value (the test
     * fixture's human-readable string, the {@code (dev build)} fallback, or
     * anything unparseable) is returned unchanged.
     */
    String formatBuildTime(String raw) {
        if (raw == null || raw.isBlank() || raw.charAt(0) == '(') return raw;
        try {
            return BUILD_TIME_FMT.format(Instant.parse(raw));
        } catch (DateTimeParseException e) {
            return raw;
        }
    }

    String value(String key, String fallback) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank() || v.contains("${")) return fallback;
        return v;
    }
}
