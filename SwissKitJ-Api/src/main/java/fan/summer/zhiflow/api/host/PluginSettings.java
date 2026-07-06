package fan.summer.zhiflow.api.host;

import java.util.Optional;

/**
 * Key-value settings store for a single plugin, namespaced by plugin ID.
 * Reads are cache-first (read-your-writes guaranteed). Keys must be non-null;
 * a null key throws {@link NullPointerException}.
 *
 * @since 3.2.0
 */
public interface PluginSettings {

    /**
     * @param key the setting key; must not be null
     * @return the stored value, or empty if absent
     */
    Optional<String> get(String key);

    /**
     * @param key          the setting key; must not be null
     * @param defaultValue returned when the key is absent
     * @return the stored value, or {@code defaultValue}
     */
    String get(String key, String defaultValue);

    /**
     * Stores a value. {@code value == null} is equivalent to {@link #remove(String)}.
     *
     * @param key   the setting key; must not be null
     * @param value the value to store, or null to remove
     */
    void put(String key, String value);

    /**
     * Removes the key; no-op if absent.
     *
     * @param key the setting key; must not be null
     */
    void remove(String key);
}
