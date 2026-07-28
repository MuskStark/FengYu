package fan.summer.fengyu.runtime;

import java.nio.file.Path;

/**
 * Canonical locations for writable FengYu runtime state.
 *
 * <p>The root is deliberately based on {@code user.home}, not {@code user.dir}: packaged desktop
 * launches do not guarantee a stable working directory across restarts. Operators can override
 * the root with {@code -Dfengyu.runtime.dir=/path/to/fengyu}.
 */
public final class RuntimePaths {

    public static final String ROOT_PROPERTY = "fengyu.runtime.dir";

    private RuntimePaths() {}

    public static Path root() {
        return resolveRoot(System.getProperty(ROOT_PROPERTY), System.getProperty("user.home"));
    }

    static Path resolveRoot(String configured, String userHome) {
        String value = configured == null ? "" : configured.trim();
        Path root = value.isEmpty() ? Path.of(userHome, ".fengyu") : Path.of(value);
        return root.toAbsolutePath().normalize();
    }

    public static Path configDirectory(Path root) {
        return root.resolve("config");
    }

    public static Path databaseDirectory(Path root) {
        return root.resolve("database");
    }

    public static Path logDirectory(Path root) {
        return root.resolve("logs");
    }

    public static Path pluginDirectory(Path root) {
        return root.resolve("plugins");
    }

    public static Path pluginDataDirectory(Path root) {
        return root.resolve("plugin-data");
    }

    public static Path skillDirectory(Path root) {
        return root.resolve("skills");
    }

    public static Path runtimeFilesDirectory(Path root) {
        return root.resolve("runtime-files");
    }
}
