package fan.summer.fengyu.ai.hooks;

import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import fan.summer.fengyu.ai.util.JsonHelper;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers lifecycle hooks contributed by installed plugins ({@code hooks/hooks.json}
 * inside a {@code .fyp} package) and activates them <b>only after the user explicitly
 * trusts the plugin</b> — installing (or enabling) a plugin is not consent to run its
 * hook scripts. This mirrors the enable≠trust split terminal agents converged on.
 *
 * <p>The hooks file accepts both shapes:
 * <ul>
 *   <li>grok-compatible: {@code {"hooks": {"PreToolUse": [{"matcher": "…",
 *       "hooks": [{"type": "command", "command": "bin/check.sh"}]}]}}} — event names
 *       map through the alias table (PreToolUse→pre_tool_use, …).</li>
 *   <li>FengYu's flat list: {@code [{"name","event","matcher","type","command","timeoutSeconds"}]}.</li>
 * </ul>
 *
 * <p>Every contributed hook runs with the plugin's install directory as working
 * directory and receives {@code FENGYU_PLUGIN_ROOT}/{@code FENGYU_PLUGIN_DATA} in its
 * environment — the plugin owns a writable data dir, never its install dir. Names are
 * namespaced {@code plugin/<pluginId>/<name>} so audit trails attribute cleanly.
 */
@Service
public class PluginHookContributions {

    private static final Logger log = LoggerFactory.getLogger(PluginHookContributions.class);
    private static final String TRUSTED_PLUGINS_KEY = "plugin.hooks.trusted";
    private static final int MAX_PLUGIN_HOOKS = 50;

    private final PluginPackageService packages;
    private final fan.summer.fengyu.plugin.runtime.PluginRuntimeEnvironmentService runtimeEnvironment;

    public PluginHookContributions(PluginPackageService packages,
            fan.summer.fengyu.plugin.runtime.PluginRuntimeEnvironmentService runtimeEnvironment) {
        this.packages = packages;
        this.runtimeEnvironment = runtimeEnvironment;
    }

    /** One plugin's parsed hook contribution, with its trust verdict. */
    public record PluginHooks(String pluginId, String pluginName, boolean trusted,
                              List<HookDispatcher.HookDefinition> hooks, List<String> warnings) {}

    /** Scans installed plugins for hook contributions; never throws on a bad package. */
    public List<PluginHooks> discover() {
        List<PluginHooks> out = new ArrayList<>();
        List<String> trustedIds = new ArrayList<>(trustedPluginIds());
        // 6.3: uninstall revokes trust — a trusted id with no installed package is a
        // stale grant that a DIFFERENT package reusing the id would silently inherit.
        // Prune it so reinstall starts untrusted, matching the enable≠trust model.
        java.util.Set<String> installedIds = new java.util.HashSet<>();
        for (var manifest : packages.installed()) installedIds.add(manifest.id());
        trustedIds.removeIf(id -> !installedIds.contains(id));
        if (!trustedIds.equals(trustedPluginIds())) {
            try {
                AiConfigServiceHeadless.setSetting(TRUSTED_PLUGINS_KEY, JsonHelper.toJson(trustedIds));
            } catch (Exception ignored) {
                // Persistence hiccup — the in-memory verdict below still holds for now.
            }
        }
        for (var manifest : packages.installed()) {
            try {
                Path hooksFile = packages.asset(manifest.id(), "hooks/hooks.json");
                if (hooksFile == null || !Files.isRegularFile(hooksFile)) continue;
                List<String> warnings = new ArrayList<>();
                List<HookDispatcher.HookDefinition> parsed = parse(
                        Files.readString(hooksFile), manifest.id(), manifest.name(), warnings);
                out.add(new PluginHooks(manifest.id(), manifest.name(),
                        trustedIds.contains(manifest.id()), parsed, warnings));
            } catch (Exception broken) {
                log.warn("plugin {}: hooks/hooks.json unreadable ({})",
                        manifest.id(), broken.getMessage());
            }
        }
        return out;
    }

    /** The subset the trust gate lets through, runtime-bound and ready to dispatch. */
    public List<HookDispatcher.HookDefinition> activeHooks() {
        List<HookDispatcher.HookDefinition> active = new ArrayList<>();
        int budget = MAX_PLUGIN_HOOKS;
        for (PluginHooks contribution : discover()) {
            if (!contribution.trusted()) continue;
            for (HookDispatcher.HookDefinition hook : contribution.hooks()) {
                if (budget-- <= 0) {
                    log.warn("plugin hook budget of {} reached; ignoring the rest", MAX_PLUGIN_HOOKS);
                    return List.copyOf(active);
                }
                active.add(bind(hook, contribution.pluginId()));
            }        }
        return active;
    }

    /** True when the plugin id is in the trust store (explicit user consent). */
    public boolean isTrusted(String pluginId) {
        return trustedPluginIds().contains(pluginId);
    }

    /** Adds/removes a plugin from the trust store; returns the new verdict. */
    public boolean setTrusted(String pluginId, boolean trusted) {
        List<String> ids = new ArrayList<>(trustedPluginIds());
        if (trusted && !ids.contains(pluginId)) ids.add(pluginId);
        if (!trusted) ids.remove(pluginId);
        try {
            AiConfigServiceHeadless.setSetting(TRUSTED_PLUGINS_KEY, JsonHelper.toJson(ids));
            log.info("plugin {} hooks {}", pluginId, trusted ? "TRUSTED (activated)" : "untrusted");
            return trusted;
        } catch (Exception error) {
            throw new IllegalStateException("Could not persist hook trust: " + error.getMessage());
        }
    }

    private List<String> trustedPluginIds() {
        try {
            Object parsed = JsonHelper.parse(AiConfigServiceHeadless
                    .getSetting(TRUSTED_PLUGINS_KEY, "[]"));
            List<String> ids = new ArrayList<>();
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String id && !id.isBlank()) ids.add(id);
                }
            }
            return ids;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private HookDispatcher.HookDefinition bind(HookDispatcher.HookDefinition hook, String pluginId) {
        Path pluginRoot = packages.directory(pluginId);
        String dataDir;
        try {
            dataDir = runtimeEnvironment.defaultOutputPath(pluginId).getParent().toString();
        } catch (Exception noDataDir) {
            dataDir = pluginRoot.toString();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FENGYU_PLUGIN_ROOT", pluginRoot.toString());
        env.put("FENGYU_PLUGIN_DATA", dataDir);
        return hook.withRuntime(pluginRoot.toString(), env);
    }

    /** Parses both file shapes; names are namespaced {@code plugin/<id>/<name>}. */
    static List<HookDispatcher.HookDefinition> parse(String json, String pluginId,
                                                     String pluginName, List<String> warnings) {
        List<HookDispatcher.HookDefinition> out = new ArrayList<>();
        Object parsed;
        try {
            parsed = JsonHelper.parse(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception malformed) {
            warnings.add("plugin " + pluginId + ": hooks/hooks.json is not valid JSON");
            return out;
        }
        if (parsed instanceof Map<?, ?> rootMap && rootMap.get("hooks") instanceof Map<?, ?> events) {
            // grok-shaped: {"hooks": {Event: [{"matcher":…, "hooks": […]}]}}
            for (Map.Entry<?, ?> eventEntry : events.entrySet()) {
                HookDispatcher.HookEvent event = HookDispatcher.HookEvent.fromWire(
                        String.valueOf(eventEntry.getKey()));
                if (event == null) {
                    warnings.add("plugin " + pluginId + ": skipped unsupported event '"
                            + eventEntry.getKey() + "'");
                    continue;
                }
                if (!(eventEntry.getValue() instanceof List<?> groups)) continue;
                for (Object group : groups) {
                    parseGroup(group, event, pluginId, pluginName, out, warnings);
                }
            }
            return out;
        }
        if (parsed instanceof List<?> list) {
            // FengYu flat list — reuse the settings parser, then namespace the names.
            for (HookDispatcher.HookDefinition hook : ToolGuardService.HookConfig.parse(json)) {
                out.add(namespace(hook, pluginId));
            }
            return out;
        }
        warnings.add("plugin " + pluginId + ": hooks file must be an object or array");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void parseGroup(Object group, HookDispatcher.HookEvent event, String pluginId,
                                   String pluginName, List<HookDispatcher.HookDefinition> out,
                                   List<String> warnings) {
        if (!(group instanceof Map<?, ?> rawGroup)) return;
        Map<String, Object> groupMap = (Map<String, Object>) rawGroup;
        String matcher = groupMap.get("matcher") instanceof String value ? value : null;
        if (!(groupMap.get("hooks") instanceof List<?> handlers)) return;
        int index = 1;
        for (Object handler : handlers) {
            if (!(handler instanceof Map<?, ?> rawHandler)) continue;
            Map<String, Object> handlerMap = (Map<String, Object>) rawHandler;
            String type = String.valueOf(handlerMap.getOrDefault("type", "command"));
            long timeout = handlerMap.get("timeout") instanceof Number number
                    ? number.longValue() : 5;
            String name = "plugin/" + pluginId + "/" + (handlerMap.get("name") instanceof String n && !n.isBlank()
                    ? n : event.wireName() + "-" + index);
            if ("http".equalsIgnoreCase(type)) {
                String url = handlerMap.get("url") instanceof String value ? value : null;
                if (url == null || url.isBlank()) {
                    warnings.add("plugin " + pluginId + ": http hook without url skipped");
                    continue;
                }
                out.add(new HookDispatcher.HookDefinition(name, event, matcher,
                        HookDispatcher.HookDefinition.Type.HTTP, null, url,
                        HookDispatcher.boundedHookTimeout(timeout), true,
                        null, Map.of()));
            } else {
                String command = handlerMap.get("command") instanceof String value ? value : null;
                if (command == null || command.isBlank()) {
                    warnings.add("plugin " + pluginId + ": command hook without command skipped");
                    continue;
                }
                out.add(new HookDispatcher.HookDefinition(name, event, matcher,
                        HookDispatcher.HookDefinition.Type.COMMAND, command, null,
                        HookDispatcher.boundedHookTimeout(timeout), true,
                        null, Map.of()));
            }
            index++;
        }
    }

    private static HookDispatcher.HookDefinition namespace(HookDispatcher.HookDefinition hook,
                                                           String pluginId) {
        return new HookDispatcher.HookDefinition(
                "plugin/" + pluginId + "/" + hook.name(), hook.event(), hook.matcher(),
                hook.type(), hook.command(), hook.url(), hook.timeout(), hook.enabled(),
                hook.workingDir(), hook.env());
    }
}
