package fan.summer.zhiflow.plugin;

import fan.summer.zhiflow.api.ai.AiServiceProvider;
import fan.summer.zhiflow.api.ai.AiTool;
import fan.summer.zhiflow.api.plugin.PluginDescriptor;
import fan.summer.zhiflow.api.plugin.ZhiFlowPluginV2;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring service that collects every {@link ZhiFlowPluginV2} bean in the context and drives the
 * {@code /api/plugins} + {@code /api/plugins/{id}/invoke} endpoints.
 *
 * <p>On startup each plugin's {@link ZhiFlowPluginV2#aiTools()} are registered with
 * {@link AiServiceProvider}, mirroring the v1 auto-registration behaviour. Phase 1 uses
 * compile-time bundling only — plugins are Spring beans, not SPI-loaded JARs. The
 * runtime-override seam (a {@code ~/.zhiflow/plugin/<id>/} directory that would win when present)
 * is reserved for a later phase; here every id resolves to its bundled bean.
 */
@Service
public class PluginRegistryService {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistryService.class);

    private final Map<String, ZhiFlowPluginV2> byId = new ConcurrentHashMap<>();

    public PluginRegistryService(List<ZhiFlowPluginV2> plugins) {
        for (ZhiFlowPluginV2 p : plugins) {
            PluginDescriptor d = p.descriptor();
            if (d == null || d.id() == null || d.id().isBlank()) {
                log.warn("Skipping plugin with null/blank descriptor id: {}", p.getClass().getName());
                continue;
            }
            ZhiFlowPluginV2 prev = byId.put(d.id(), p);
            if (prev != null) {
                log.warn("Duplicate plugin id '{}' — {} overrides {}",
                    d.id(), p.getClass().getName(), prev.getClass().getName());
            }
        }
    }

    /** Registers every plugin's AI tools with {@link AiServiceProvider} after construction. */
    @PostConstruct
    void registerAiTools() {
        for (ZhiFlowPluginV2 p : byId.values()) {
            for (AiTool tool : p.aiTools()) {
                try {
                    AiServiceProvider.registerTool(tool);
                    log.info("Registered AI tool '{}' from plugin '{}'", tool.getName(), p.descriptor().id());
                } catch (Exception e) {
                    log.warn("Failed to register AI tool '{}' from plugin '{}': {}",
                        tool.getName(), p.descriptor().id(), e.getMessage());
                }
            }
        }
        log.info("PluginRegistryService ready: {} plugin(s) registered", byId.size());
    }

    /** @return descriptors for all registered plugins. */
    public List<PluginDescriptor> descriptors() {
        return byId.values().stream().map(ZhiFlowPluginV2::descriptor).toList();
    }

    /** @return the plugin with the given id, or empty if none. */
    public Optional<ZhiFlowPluginV2> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Invokes an action on the named plugin.
     *
     * @throws IllegalArgumentException if the plugin id is unknown
     */
    public Object invoke(String id, String action, Map<String, Object> args) {
        ZhiFlowPluginV2 plugin = byId.get(id);
        if (plugin == null) {
            throw new IllegalArgumentException("Unknown plugin id: " + id);
        }
        return plugin.invoke(action, args);
    }
}
