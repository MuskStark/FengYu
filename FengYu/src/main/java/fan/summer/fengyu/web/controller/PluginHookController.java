package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.hooks.PluginHookContributions;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin-hook discovery and the trust gate. Installing (or enabling) a plugin never
 * activates its hook scripts — the user must trust the plugin explicitly, mirroring
 * the enable≠trust split terminal agents standardized on. Untrusting takes effect
 * immediately (the merged hook set is rebuilt).
 */
@RestController
@RequestMapping("/api/plugin-hooks")
public class PluginHookController {

    /** ObjectProvider: the minimal SETUP-mode context excludes the AI service graph. */
    private final ObjectProvider<PluginHookContributions> contributionsProvider;
    private final ObjectProvider<ToolGuardService> guardProvider;

    public PluginHookController(ObjectProvider<PluginHookContributions> contributionsProvider,
            ObjectProvider<ToolGuardService> guardProvider) {
        this.contributionsProvider = contributionsProvider;
        this.guardProvider = guardProvider;
    }

    private PluginHookContributions contributions() {
        PluginHookContributions available = contributionsProvider.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("Plugin hooks are unavailable in this mode");
        }
        return available;
    }

    /** Every plugin that ships hooks/hooks.json, its trust verdict, and parsed summaries. */
    @GetMapping
    public List<Map<String, Object>> list() {
        PluginHookContributions contributions = contributions();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PluginHookContributions.PluginHooks plugin : contributions.discover()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pluginId", plugin.pluginId());
            entry.put("pluginName", plugin.pluginName());
            entry.put("trusted", plugin.trusted());
            entry.put("active", plugin.trusted() ? plugin.hooks().size() : 0);
            entry.put("hookCount", plugin.hooks().size());
            entry.put("warnings", plugin.warnings());
            entry.put("hooks", plugin.hooks().stream().map(hook -> Map.of(
                    "name", hook.name(),
                    "event", hook.event().wireName(),
                    "type", hook.type() == fan.summer.fengyu.ai.hooks.HookDispatcher.HookDefinition.Type.HTTP
                            ? "http" : "command",
                    "matcher", hook.matcher() == null ? "" : hook.matcher())).toList());
            out.add(entry);
        }
        return out;
    }

    /** Grants or revokes a plugin's hook trust; the live hook set is rebuilt at once. */
    @PostMapping("/{pluginId}/trust")
    public ResponseEntity<Map<String, Object>> trust(@PathVariable String pluginId,
                                                     @RequestBody TrustRequest request) {
        boolean trusted = request != null && request.trusted();
        contributions().setTrusted(pluginId, trusted);
        ToolGuardService guard = guardProvider.getIfAvailable();
        if (guard != null) guard.reload();
        return ResponseEntity.ok(Map.of(
                "pluginId", pluginId,
                "trusted", contributions().isTrusted(pluginId)));
    }

    public record TrustRequest(boolean trusted) {}
}
