package fan.summer.fengyu.ai.agent;

import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.ai.tools.AuditedToolCallback;
import fan.summer.fengyu.ai.tools.ToolEffect;
import fan.summer.fengyu.ai.tools.ToolGuardService;
import fan.summer.fengyu.ai.tools.ToolPermissionRules;
import fan.summer.fengyu.ai.util.JsonHelper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Creation-time screen for unattended triggers (workflow schedules, webhook triggers).
 *
 * <p>Under the default {@link AiPermissionMode#ASK_FOR_APPROVAL} mode a run with no attached
 * stream client can never release an approval gate: every fire with a non-{@code read} step
 * not covered by an allow rule would block until the workflow timeout, burning a background
 * task slot and eventually starving the queue. This policy rejects such definitions when the
 * trigger is <em>created</em> — after the workflow has compiled, so unpublished definitions
 * and invalid inputs still fail first — with a message that tells the owner to pick an
 * explicit permission mode or cover the tool with a permission rule.
 *
 * <p>Only allow rules are consulted (not the full guard pipeline): creation must not execute
 * PreToolUse hook scripts, and an {@code ask}/{@code deny} rule leaves the step at the gate
 * anyway.
 */
@Service
public class UnattendedTriggerPolicy {

    private final Supplier<ToolGuardService> guard;
    private final Supplier<List<ToolCallback>> tools;

    @org.springframework.beans.factory.annotation.Autowired
    public UnattendedTriggerPolicy(ObjectProvider<ToolGuardService> guards,
                                   ObjectProvider<fan.summer.fengyu.ai.config.AiToolRegistry> toolRegistries) {
        // Resolved LAZILY, never in this constructor: an eager getIfAvailable() here
        // constructs ToolGuardService — whose @PostConstruct reads app settings through a
        // JPA repository — during the earliest phase of context refresh, before the
        // EntityManagerFactory exists. Any context that happens to create this bean first
        // (repository slice tests, SETUP mode) then fails to boot entirely.
        this.guard = guards == null ? () -> null : guards::getIfAvailable;
        this.tools = toolRegistries == null ? List::of : () -> {
            fan.summer.fengyu.ai.config.AiToolRegistry registry = toolRegistries.getIfAvailable();
            return registry == null ? List.of() : registry.callbacks();
        };
    }

    /** Direct-injection constructor for tests: fixed guard and tool catalog. */
    public UnattendedTriggerPolicy(ToolGuardService guard, Supplier<List<ToolCallback>> tools) {
        this.guard = guard == null ? () -> null : () -> guard;
        this.tools = tools == null ? List::of : tools;
    }

    /**
     * Throws {@link IllegalArgumentException} when a workflow run under {@code mode} with no
     * watching client could not get past its first approval gate. No-op for every other mode
     * and for plans whose steps are all read-effect or allow-rule covered.
     */
    public void requireExecutable(AgentPlan plan, AiPermissionMode mode) {
        if (mode != AiPermissionMode.ASK_FOR_APPROVAL || plan == null || plan.steps() == null) {
            return;
        }
        for (AgentStep step : plan.steps()) {
            if (step == null) continue;
            ToolCallback tool = findTool(step.toolName());
            if (!(tool instanceof AuditedToolCallback audited)
                    || audited.effect() == ToolEffect.READ) {
                continue;
            }
            if (coveredByAllowRule(step, tool)) continue;
            throw new IllegalArgumentException(
                    "This unattended trigger would pause for approval on step " + step.index()
                            + " (tool '" + step.toolName() + "') with no one watching: the "
                            + "effective permission mode is ask-for-approval and no allow rule "
                            + "covers the tool. Create it with an explicit permission mode "
                            + "(approve-for-me or full-access) or add a permission rule that "
                            + "allows the tool.");
        }
    }

    /** True when a user-configured allow rule grants the step without an approval prompt. */
    private boolean coveredByAllowRule(AgentStep step, ToolCallback tool) {
        ToolGuardService resolved = guard.get();
        if (resolved == null) return false;
        ToolPermissionRules.Evaluation evaluation = ToolPermissionRules.evaluate(
                resolved.rules(), accessFor(step, tool));
        return evaluation != null
                && evaluation.decision() == ToolPermissionRules.Decision.ALLOW;
    }

    /**
     * Mirrors {@code ToolGuardService.accessFor} (private there) for the rule dimensions the
     * allow-rule grammar can match: name, effect, MCP qualification, command text, and URL.
     */
    private static ToolPermissionRules.ToolAccess accessFor(AgentStep step, ToolCallback tool) {
        String name = step.toolName();
        ToolEffect effect = tool instanceof AuditedToolCallback audited ? audited.effect() : null;
        boolean mcp = name != null && name.contains("__");
        String args = toJsonArgs(step.args());
        if ("execute_command".equals(name)) {
            return new ToolPermissionRules.ToolAccess(name, effect, mcp,
                    ToolPermissionRules.commandFromArguments(args), null);
        }
        if ("web_fetch".equals(name) || "web_search".equals(name)
                || "browser_navigate".equals(name) || "browser_new_tab".equals(name)) {
            return new ToolPermissionRules.ToolAccess(name, effect, mcp, null,
                    urlFromArguments(args));
        }
        return new ToolPermissionRules.ToolAccess(name, effect, mcp, null, null);
    }

    private static String urlFromArguments(String arguments) {
        try {
            Object parsed = JsonHelper.parse(arguments);
            if (parsed instanceof Map<?, ?> map) {
                Object url = map.get("url");
                if (url instanceof String value) return value;
                Object query = map.get("query");
                if (query instanceof String value) return value;
            }
        } catch (Exception ignored) {
            // Malformed args simply carry no URL dimension to rule matching.
        }
        return null;
    }

    private static String toJsonArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        try {
            return JsonHelper.toJson(args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ToolCallback findTool(String name) {
        for (ToolCallback tool : tools.get()) {
            if (tool.getToolDefinition().name().equals(name)) return tool;
        }
        return null;
    }
}
