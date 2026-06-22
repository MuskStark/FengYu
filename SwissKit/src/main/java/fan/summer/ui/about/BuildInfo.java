package fan.summer.ui.about;

import java.io.IOException;
import java.io.InputStream;
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
        return INSTANCE.value("build.time", DEV_BUILD_TIME);
    }

    String value(String key, String fallback) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank() || v.contains("${")) return fallback;
        return v;
    }
}
