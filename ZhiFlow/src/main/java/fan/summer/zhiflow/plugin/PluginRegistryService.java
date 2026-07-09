package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.api.plugin.PluginSource;
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
            // Every plugin MUST have a UI: a blank uiEntry means nothing to render.
            if (d.uiEntry() == null || d.uiEntry().isBlank()) {
                log.warn("Skipping plugin '{}': uiEntry is blank", d.id());
                continue;
            }
            // OFFICIAL is a trust claim reserved for plugins shipped under the
            // fan.summer.* reverse-domain. A plugin declaring OFFICIAL without that
            // id prefix is downgraded to THIRD_PARTY (warn-only; never blocks load).
            if (d.source() == PluginSource.OFFICIAL && !d.id().startsWith("fan.summer.")) {
                log.warn("Plugin '{}' declared OFFICIAL but id lacks 'fan.summer.' prefix; downgrading to THIRD_PARTY",
                    d.id());
                d = new PluginDescriptor(d.id(), d.name(), d.description(), d.category(),
                    d.icon(), d.iconStyle(), d.version(), d.uiEntry(), d.supportsAi(),
                    PluginSource.THIRD_PARTY);
            }
            ZhiFlowPlugin prev = byId.put(d.id(), new DescriptorOverridingPlugin(p, d));
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

    /**
     * Wrapper that returns the (possibly normalized) registry-side descriptor while
     * delegating {@link #invoke} to the underlying plugin. This is what makes the
     * OFFICIAL→THIRD_PARTY downgrade observable to {@code descriptor()} callers
     * without mutating the original plugin's own descriptor.
     */
    private record DescriptorOverridingPlugin(ZhiFlowPlugin delegate, PluginDescriptor descriptor)
        implements ZhiFlowPlugin {

        @Override
        public Object invoke(String action, Map<String, Object> args) {
            return delegate.invoke(action, args);
        }
    }
}
