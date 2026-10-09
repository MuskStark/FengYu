package fan.summer.fengyu.runtime;

import java.nio.file.Path;

/**
 * Canonical locations for writable FengYu runtime state.
 *
 * <p>The root defaults to {@code .fengyu} under the program working directory
 * ({@code user.dir}). Operators and the desktop shell can pin a different runtime root with
 * {@code -Dfengyu.runtime.dir=/path/to/fengyu}.
 */
public final class RuntimePaths {

    public static final String ROOT_PROPERTY = "fengyu.runtime.dir";

    /**
     * Marker set once at JVM startup recording whether {@link #ROOT_PROPERTY} was provided by
     * the operator (vs normalized programmatically by {@code HeadlessLauncher}'s static init,
     * which sets it unconditionally). An explicitly pinned runtime root must stay isolated —
     * {@code DataSourceConfigService} suppresses legacy-config adoption for pinned roots.
     */
    public static final String PINNED_MARKER_PROPERTY = "fengyu.runtime.pinned";

    private RuntimePaths() {}

    public static Path root() {
        return resolveRoot(System.getProperty(ROOT_PROPERTY), System.getProperty("user.dir"));
    }

    /**
     * Default working directory for tool-spawned subprocesses (AI {@code command} tool,
     * hook scripts) and the approval gate's workspace anchor.
     *
     * <p>NOT plain {@code user.dir}: the desktop's cached AOT launch spawns the backend
     * with the process cwd set to the jar's directory (the training classpath form must
     * be repeated byte for byte), so {@code user.dir} is the read-only install dir
     * there. Desktop-mode boots instead resolve to the pinned runtime root — exactly
     * the process cwd every desktop launch had before the startup cache — while bare
     * CLI runs keep the shell's working directory unchanged.
     */
    public static Path subprocessDefaultWorkingDirectory() {
        String pinned = System.getProperty(ROOT_PROPERTY);
        return Boolean.getBoolean("fengyu.desktop") && pinned != null && !pinned.isBlank()
                ? Path.of(pinned.trim())
                : Path.of(System.getProperty("user.dir"));
    }

    static Path resolveRoot(String configured, String workingDirectory) {
        String value = configured == null ? "" : configured.trim();
        Path root = value.isEmpty()
                ? Path.of(workingDirectory).resolve(".fengyu")
                : Path.of(value);
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

    /** Persistent MCP connection definitions and their protected credentials. */
    public static Path mcpDirectory(Path root) {
        return root.resolve("mcp-servers");
    }

    /** Full-screen captures taken by the computer-use tools (mirrors browser-screenshots). */
    public static Path computerScreenshotsDirectory(Path root) {
        return root.resolve("computer-screenshots");
    }

    public static Path runtimeFilesDirectory(Path root) {
        return root.resolve("runtime-files");
    }
}
