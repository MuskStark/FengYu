package fan.summer.zhiflow.plugin.host;

import fan.summer.zhiflow.api.host.PluginSettings;
import fan.summer.zhiflow.database.DatabaseInit;
import fan.summer.zhiflow.database.entity.PluginSettingEntity;
import fan.summer.zhiflow.database.mapper.PluginSettingMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H2-backed {@link PluginSettings}: cache-first reads (read-your-writes),
 * asynchronous persistence on a virtual thread — the same pattern as the host's
 * own settings cache in ZhiFlowSettingUi.
 *
 * @since 3.2.0
 */
public class H2PluginSettings implements PluginSettings {

    private static final Logger log = LoggerFactory.getLogger(H2PluginSettings.class);

    private final String pluginId;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    /**
     * @param pluginId the namespace for every key in this store; must not be null
     */
    public H2PluginSettings(String pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    /** Lazily loads all rows of this plugin into the cache on first access. */
    private void ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return;
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
            for (PluginSettingEntity e : mapper.selectByPluginId(pluginId)) {
                if (e.getSettingValue() != null) cache.put(e.getSettingKey(), e.getSettingValue());
            }
        } catch (Exception e) {
            log.error("Failed to load settings for plugin {}", pluginId, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        return Optional.ofNullable(cache.get(key));
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
        ensureLoaded();
        cache.put(key, value);
        Thread.ofVirtual().name("plugin-settings-save").start(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
                PluginSettingEntity entity = new PluginSettingEntity();
                entity.setPluginId(pluginId);
                entity.setSettingKey(key);
                entity.setSettingValue(value);
                mapper.upsert(entity);
                session.commit();
            } catch (Exception e) {
                log.error("Failed to persist setting '{}' for plugin {}", key, pluginId, e);
            }
        });
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key");
        ensureLoaded();
        cache.remove(key);
        Thread.ofVirtual().name("plugin-settings-save").start(() -> {
            try (SqlSession session = DatabaseInit.getSqlSession()) {
                PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
                mapper.deleteByPluginIdAndKey(pluginId, key);
                session.commit();
            } catch (Exception e) {
                log.error("Failed to delete setting '{}' for plugin {}", key, pluginId, e);
            }
        });
    }

    /**
     * Purges every stored setting of the plugin. Called by PluginLoader on
     * EXPLICIT uninstall only — hot-reload keeps settings.
     *
     * @param pluginId the plugin to purge
     */
    public static void purge(String pluginId) {
        try (SqlSession session = DatabaseInit.getSqlSession()) {
            PluginSettingMapper mapper = session.getMapper(PluginSettingMapper.class);
            mapper.deleteByPluginId(pluginId);
            session.commit();
        } catch (Exception e) {
            log.error("Failed to purge settings for plugin {}", pluginId, e);
        }
    }
}
