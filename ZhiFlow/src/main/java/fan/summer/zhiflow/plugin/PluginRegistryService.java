package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.api.plugin.ZhiFlowPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring service that collects every {@link ZhiFlowPlugin} bean in the context and drives the
 * {@code /api/plugins} + {@code /api/plugins/{id}/invoke} endpoints.
 *
 * <p>Phase 1 uses compile-time bundling only — plugins are Spring beans, not SPI-loaded JARs.
 * The runtime-override seam (a {@code ~/.zhiflow/plugin/<id>/} directory that would win when
 * present) is reserved for a later phase; here every id resolves to its bundled bean.
 */
@Service
public class PluginRegistryService {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistryService.class);

    private final Map<String, ZhiFlowPlugin> byId = new ConcurrentHashMap<>();

    public PluginRegistryService(List<ZhiFlowPlugin> plugins) {
        for (ZhiFlowPlugin p : plugins) {
            PluginDescriptor d = p.descriptor();
            if (d == null || d.id() == null || d.id().isBlank()) {
                log.warn("Skipping plugin with null/blank descriptor id: {}", p.getClass().getName());
                continue;
            }
            ZhiFlowPlugin prev = byId.put(d.id(), p);
            if (prev != null) {
                log.warn("Duplicate plugin id '{}' — {} overrides {}",
                    d.id(), p.getClass().getName(), prev.getClass().getName());
            }
        }
    }

    /** @return descriptors for all registered plugins. */
    public List<PluginDescriptor> descriptors() {
        return byId.values().stream().map(ZhiFlowPlugin::descriptor).toList();
    }

    /** @return the plugin with the given id, or empty if none. */
    public Optional<ZhiFlowPlugin> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Invokes an action on the named plugin.
     *
     * @throws IllegalArgumentException if the plugin id is unknown
     */
    public Object invoke(String id, String action, Map<String, Object> args) {
        ZhiFlowPlugin plugin = byId.get(id);
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown plugin id: " + id);
        }
        return plugin.invoke(action, args);
    }
}
