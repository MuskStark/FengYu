package fan.summer.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Application version constants.
 * Version should match pom.xml <version> tag.
 */
public abstract class AppInfo {
    private static final Logger logger = LoggerFactory.getLogger(AppInfo.class);

    public static final String VERSION = resolveVersion();
    public static final String NAME    = loadProperty("app.name", "SwissKit");

    /**
     * Resolves the application version using the following strategy:
     * 1. Read Implementation-Version from JAR's MANIFEST.MF (works after packaging)
     * 2. Fallback to app.properties if MANIFEST not available (for IDE development, defaults to "dev")
     *
     * @return the resolved version string
     */
    private static String resolveVersion() {

        try {

            String className = AppInfo.class.getSimpleName() + ".class";
            String classPath = AppInfo.class.getResource(className).toString();
            if (classPath.startsWith("jar:")) {
                String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1)
                        + "/META-INF/MANIFEST.MF";
                try (InputStream is = new URL(manifestPath).openStream()) {
                    Manifest manifest = new Manifest(is);
                    Attributes attrs = manifest.getMainAttributes();
                    String version = attrs.getValue("Implementation-Version");
                    if (version != null && !version.isBlank()) {
                        logger.debug("Version from MANIFEST: {}", version);
                        return version;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("MANIFEST not available, falling back to app.properties");
        }


        return loadProperty("app.version", "dev");
    }

    /**
     * Loads a property value from app.properties file in classpath.
     *
     * @param key          the property key to look up
     * @param defaultValue the default value if property is not found
     * @return the property value or defaultValue if not found
     */
    private static String loadProperty(String key, String defaultValue) {
        try (InputStream is = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (is == null) return defaultValue;
            Properties props = new Properties();
            props.load(is);
            return props.getProperty(key, defaultValue);
        } catch (Exception e) {
            logger.warn("Failed to read {}", key, e);
            return defaultValue;
        }
    }

    /**
     * Returns the application version.
     *
     * @return the version string
     */
    public static String getVersion() { return VERSION; }

    /**
     * Returns the application name.
     *
     * @return the application name
     */
    public static String getName()    { return NAME; }

    /**
     * Returns the full application name with version (e.g., "SwissKit-1.0").
     *
     * @return the full name string
     */
    public static String getFullName() { return NAME + "-" + VERSION; }
}
