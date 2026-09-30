package fan.summer.fengyu.plugin.market;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Resolves the running host version without coupling plugin services to the update subsystem. */
public final class PluginHostVersion {
    private PluginHostVersion() {}

    public static String current() {
        String override = System.getProperty("fengyu.version");
        if (override != null && !override.isBlank()) return override.trim();
        String implementation = PluginHostVersion.class.getPackage().getImplementationVersion();
        if (implementation != null && !implementation.isBlank()) return implementation.trim();
        try (InputStream input = PluginHostVersion.class.getResourceAsStream("/build-info.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String version = properties.getProperty("app.version");
                if (version != null && SemanticVersion.isValid(version.trim())) return version.trim();
            }
        } catch (IOException ignored) {
            // IDE runs can legitimately lack filtered build metadata.
        }
        return "Dev";
    }

    public static void requireCompatible(String range) {
        String current = current();
        if ("Dev".equals(current)) return;
        if (!SemanticVersionRange.includes(range, current)) {
            throw new IllegalArgumentException(
                "Plugin requires FengYu " + range + " but the running host is " + current);
        }
    }
}
