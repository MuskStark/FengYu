package fan.summer.fengyu.sdk;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Database connection details supplied to a plugin worker by the FengYu host. */
public record PluginDatabaseConfig(String type, String driver, String url,
        String username, String password, Path dataDirectory) {
    public static Optional<PluginDatabaseConfig> fromEnvironment(Map<String, String> env) {
        boolean any = PluginEnvironment.databaseKeys().stream().anyMatch(env::containsKey);
        if (!any) {
            return Optional.empty();
        }

        String type = required(env, PluginEnvironment.DB_TYPE);
        String driver = required(env, PluginEnvironment.DB_DRIVER);
        String url = required(env, PluginEnvironment.DB_URL);
        String data = required(env, PluginEnvironment.PLUGIN_DATA_DIR);
        return Optional.of(new PluginDatabaseConfig(type, driver, url,
            env.getOrDefault(PluginEnvironment.DB_USERNAME, ""),
            env.getOrDefault(PluginEnvironment.DB_PASSWORD, ""), Path.of(data)));
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required plugin environment variable: " + key);
        }
        return value;
    }

    @Override public String toString() {
        return "PluginDatabaseConfig[type=" + type + ",driver=" + driver
            + ",url=" + url + ",username=" + username + ",password=<redacted>,dataDirectory="
            + dataDirectory + "]";
    }
}
