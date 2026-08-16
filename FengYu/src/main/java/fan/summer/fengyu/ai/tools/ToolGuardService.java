package fan.summer.fengyu.ai.tools;

import fan.summer.fengyu.ai.hooks.HookDispatcher;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.util.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single approval pipeline shared by ordinary chat and the Plan-and-Execute agent,
 * layering two user-configurable mechanisms under the coarse permission mode:
 *
 * <pre>catastrophic-command hard floor → PreToolUse hooks → deny rules → ask rules → allow rules → permission-mode default</pre>
 *
 * The order is deliberate (it is the order terminal-agent practice converged on):
 * the hard floor categorically denies filesystem/device destruction and nothing can
 * override it; a hook can veto even what an allow rule grants; a deny rule always wins
 * regardless of declaration order; an explicit allow only suppresses the approval prompt,
 * never the dangerous-command floor; and when nothing matches, the legacy
 * {@link ToolApprovalPolicy} mode decision applies.
 */
@Service
public class ToolGuardService {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardService.class);

    /** Verdict for one tool call. */
    public enum Verdict { ALLOW, ASK, DENY }

    public record GuardDecision(Verdict verdict, String reason, List<String> executedHooks) {
        static GuardDecision allow() {
            return new GuardDecision(Verdict.ALLOW, null, List.of());
        }
    }

    private final HookDispatcher hooks;
    /** Plugin-contributed hooks (enable ≠ trust; only trusted plugins activate). */
    private final org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.hooks.PluginHookContributions> pluginHooks;
    private volatile List<ToolPermissionRules.PermissionRule> rules = List.of();
    private volatile List<String> invalidRules = List.of();

    @org.springframework.beans.factory.annotation.Autowired
    public ToolGuardService(HookDispatcher hooks,
            org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.hooks.PluginHookContributions> pluginHooks) {
        this.hooks = hooks;
        this.pluginHooks = pluginHooks;
    }

    /** Loads rule + hook configuration from app settings (after the bean is constructed). */
    @PostConstruct
    void load() {
        applyRules(AiConfigServiceHeadless.getPermissionRulesJson());
        refreshHooks(HookConfig.parse(AiConfigServiceHeadless.getHooksJson()));
    }

    /** Test/direct-injection constructor: installs rules + hooks from raw JSON. */
    public ToolGuardService(HookDispatcher hooks, String rulesJson, String hooksJson) {
        this(hooks, null, rulesJson, hooksJson);
    }

    ToolGuardService(HookDispatcher hooks,
            org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.hooks.PluginHookContributions> pluginHooks,
            String rulesJson, String hooksJson) {
        this.hooks = hooks;
        this.pluginHooks = pluginHooks;
        applyRules(rulesJson);
        refreshHooks(HookConfig.parse(hooksJson));
    }

    /** Re-reads configuration after a Settings or plugin-trust change. */
    public synchronized void reload() {
        applyRules(AiConfigServiceHeadless.getPermissionRulesJson());
        refreshHooks(HookConfig.parse(AiConfigServiceHeadless.getHooksJson()));
    }

    /** Merges user-configured hooks with trusted plugin contributions into the dispatcher. */
    private void refreshHooks(List<HookDispatcher.HookDefinition> userHooks) {
        List<HookDispatcher.HookDefinition> merged = new ArrayList<>(userHooks);
        if (pluginHooks != null) {
            fan.summer.fengyu.ai.hooks.PluginHookContributions contributions =
                    pluginHooks.getIfAvailable();
            if (contributions != null) {
                merged.addAll(contributions.activeHooks());
            }
        }
        hooks.update(merged);
    }

    private void applyRules(String rulesJson) {
        List<String> invalid = new ArrayList<>();
        List<ToolPermissionRules.PermissionRule> parsed = new ArrayList<>();
        Map<String, Object> raw = parseObject(rulesJson);
        parsed.addAll(ToolPermissionRules.parseAll(
                strings(raw.get("allow")), strings(raw.get("ask")), strings(raw.get("deny")), invalid));
        if (!invalid.isEmpty()) {
            log.warn("ignoring invalid permission rules: {}", invalid);
        }
        this.rules = List.copyOf(parsed);
        this.invalidRules = List.copyOf(invalid);
    }

    public List<String> invalidRules() {
        return invalidRules;
    }

    public List<ToolPermissionRules.PermissionRule> rules() {
        return rules;
    }

    /**
     * Decides whether a tool call may proceed, must ask, or is denied — running the
     * full pipeline. {@code tool} may be null (unknown tool): the rule evaluation then
     * only matches name-based rules and the mode default decides.
     */
    public GuardDecision decide(String toolName, ToolCallback tool, String arguments,
                                AiPermissionMode mode, String runId) {
        // 0. Catastrophic-command hard floor — runs BEFORE hooks and rules, and is not
        //    overridable: no allow rule, no PreToolUse hook allow, and no FULL_ACCESS mode
        //    may ever green-light filesystem/device destruction.
        if ("execute_command".equals(toolName)) {
            String command = ToolPermissionRules.commandFromArguments(arguments);
            if (ToolPermissionRules.isCatastrophicCommand(command)) {
                return new GuardDecision(Verdict.DENY,
                        "Blocked by the hard safety floor: this command is categorically denied "
                                + "(filesystem or raw-device destruction) and cannot be allowed by "
                                + "rules, hooks, or permission mode",
                        List.of());
            }
        }
        // 1. PreToolUse hooks — a hook veto beats everything, including allow rules.
        HookDispatcher.PreToolDecision hookDecision =
                hooks.preToolUse(toolName, parseArgs(arguments), runId);
        if (!hookDecision.allowed()) {
            return new GuardDecision(Verdict.DENY, hookDecision.denyReason(),
                    hookDecision.executedHooks());
        }
        // 2. Permission rules.
        ToolPermissionRules.Evaluation evaluation =
                ToolPermissionRules.evaluate(rules, accessFor(toolName, tool, arguments));
        if (evaluation != null) {
            return switch (evaluation.decision()) {
                case DENY -> new GuardDecision(Verdict.DENY, evaluation.reason(),
                        hookDecision.executedHooks());
                case ASK -> new GuardDecision(Verdict.ASK, null, hookDecision.executedHooks());
                case ALLOW -> new GuardDecision(Verdict.ALLOW, null, hookDecision.executedHooks());
            };
        }
        // 3. Permission-mode default.
        return ToolApprovalPolicy.requiresApproval(tool, mode, arguments)
                ? new GuardDecision(Verdict.ASK, null, hookDecision.executedHooks())
                : GuardDecision.allow();
    }

    /** Fires observe-only hooks for a finished tool call (result present) or failure. */
    public void observeToolResult(String toolName, String arguments, String result,
                                  boolean failed, String runId) {
        HookDispatcher.HookEvent event = failed
                ? HookDispatcher.HookEvent.POST_TOOL_USE_FAILURE
                : HookDispatcher.HookEvent.POST_TOOL_USE;
        hooks.observe(event, toolName, parseArgs(arguments), runId, result);
    }

    public void observeRunComplete(String runId, String goal, String summary, boolean error) {
        HookDispatcher.HookEvent event = error
                ? HookDispatcher.HookEvent.RUN_ERROR
                : HookDispatcher.HookEvent.RUN_COMPLETE;
        hooks.observe(event, null, Map.of("goal", goal == null ? "" : goal), runId, summary);
    }

    private static ToolPermissionRules.ToolAccess accessFor(String toolName, ToolCallback tool,
                                                            String arguments) {
        ToolEffect effect = tool instanceof AuditedToolCallback audited
                ? audited.effect() : null;
        boolean mcp = toolName != null && toolName.contains("__");
        if ("execute_command".equals(toolName)) {
            return new ToolPermissionRules.ToolAccess(toolName, effect, mcp,
                    ToolPermissionRules.commandFromArguments(arguments), null);
        }
        if (toolName != null && (toolName.equals("web_fetch") || toolName.equals("web_search"))) {
            return new ToolPermissionRules.ToolAccess(toolName, effect, mcp, null,
                    urlFromArguments(arguments));
        }
        return new ToolPermissionRules.ToolAccess(toolName, effect, mcp, null, null);
    }

    private static String urlFromArguments(String arguments) {
        Map<String, Object> args = parseArgs(arguments);
        Object url = args.get("url");
        if (url instanceof String value) return value;
        Object query = args.get("query");
        return query instanceof String value ? value : null;
    }

    private static Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JsonHelper.parseObjectStrict(arguments);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception malformed) {
            // Not JSON — hooks see it verbatim as a single value.
        }
        return Map.of();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) out.add(text.trim());
        }
        return out;
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            Map<String, Object> parsed = JsonHelper.parseObjectStrict(
                    json == null || json.isBlank() ? "{}" : json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            // Corrupt stored config → treat as empty (fail-open to the mode default).
        }
        return Map.of();
    }

    /** Hook-configuration JSON (de)serialization shared by the service and Settings. */
    public static final class HookConfig {

        private HookConfig() {}

        @SuppressWarnings("unchecked")
        public static List<HookDispatcher.HookDefinition> parse(String json) {
            List<HookDispatcher.HookDefinition> out = new ArrayList<>();
            Object parsed;
            try {
                parsed = JsonHelper.parse(json == null || json.isBlank() ? "[]" : json);
            } catch (Exception malformed) {
                return out;
            }
            if (!(parsed instanceof List<?> list)) return out;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rawMap)) continue;
                Map<String, Object> map = (Map<String, Object>) rawMap;
                String name = String.valueOf(map.getOrDefault("name", "hook"));
                HookDispatcher.HookEvent event = HookDispatcher.HookEvent.fromWire(
                        String.valueOf(map.getOrDefault("event", "")));
                if (event == null) continue;
                String matcher = map.get("matcher") instanceof String value ? value : null;
                String type = String.valueOf(map.getOrDefault("type", "command"));
                long timeout = map.get("timeoutSeconds") instanceof Number number
                        ? number.longValue() : 5;
                boolean enabled = !(map.get("enabled") instanceof Boolean b) || b;
                if ("http".equalsIgnoreCase(type)) {
                    String url = map.get("url") instanceof String value ? value : null;
                    if (url == null || url.isBlank()) continue;
                    out.add(new HookDispatcher.HookDefinition(name, event, matcher,
                            HookDispatcher.HookDefinition.Type.HTTP, null, url,
                            HookDispatcher.boundedHookTimeout(timeout), enabled,
                            null, Map.of()));
                } else {
                    String command = map.get("command") instanceof String value ? value : null;
                    if (command == null || command.isBlank()) continue;
                    out.add(new HookDispatcher.HookDefinition(name, event, matcher,
                            HookDispatcher.HookDefinition.Type.COMMAND, command, null,
                            HookDispatcher.boundedHookTimeout(timeout), enabled,
                            null, Map.of()));
                }
            }
            return out;
        }
    }
}
