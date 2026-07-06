package fan.summer.zhiflow.api.preview;

import fan.summer.zhiflow.api.host.PluginSettings;
import fan.summer.zhiflow.api.log.LoggerFactory;
import fan.summer.zhiflow.api.log.PluginLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Preview-mode {@link PluginSettings} backed by a per-plugin properties file
 * under {@code ~/.zhiflow/preview-settings/}. Write-through: every mutation
 * stores the file immediately (preview writes are low-frequency).
 *
 * @since 3.2.0
 */
class PropertiesPluginSettings implements PluginSettings {

    private static final PluginLogger log = LoggerFactory.getLogger(PropertiesPluginSettings.class);

    private final Path file;
    private final Properties props = new Properties();

    PropertiesPluginSettings(String pluginId) {
        this(Path.of(System.getProperty("user.home"), ".zhiflow", "preview-settings"), pluginId);
    }

    /** Test seam: explicit base directory. */
    PropertiesPluginSettings(Path baseDir, String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        this.file = baseDir.resolve(sanitize(pluginId) + ".properties");
        load();
    }

    /** File-name safety: anything outside [a-zA-Z0-9._-] becomes '_'. */
    static String sanitize(String pluginId) {
        return pluginId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to load preview settings {}: {}", file, e.getMessage());
        }
    }

    private synchronized void store() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "ZhiFlow preview settings");
            }
        } catch (IOException e) {
            log.warn("Failed to store preview settings {}: {}", file, e.getMessage());
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(props.getProperty(key));
    }

    @Override
    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    @Override
    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (value == null) {
            remove(key);
            return;
        }
        props.setProperty(key, value);
        store();
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key");
        props.remove(key);
        store();
    }
}
