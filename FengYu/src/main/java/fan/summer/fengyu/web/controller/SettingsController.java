package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ExitCodes;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.tools.ComputerTool;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.security.ProcessSandbox;
import fan.summer.fengyu.setup.DataSourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URI;

/**
 * UI-shell settings — theme, language, sidebar-collapsed. Backed by
 * {@link AiConfigServiceHeadless} (a bean, JPA-persisted, user-scoped). {@code GET} returns the
 * current values; {@code PUT} accepts a partial JSON object and persists only the keys present.
 *
 * <p>Injects the bean to make the wiring explicit; reads/writes go through the bean's facade.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final AiConfigServiceHeadless config;
    private final DataSourceConfigService dataSourceConfigService;
    private final LoggingLevelService logging;
    private final PluginProcessManager pluginProcesses;
    private final ObjectProvider<ComputerTool> computerTool;
    private final ObjectProvider<fan.summer.fengyu.ai.tools.ToolGuardService> toolGuardProvider;
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
    @Autowired
    public SettingsController(AiConfigServiceHeadless config,
                              DataSourceConfigService dataSourceConfigService,
                              LoggingLevelService logging,
                              PluginProcessManager pluginProcesses,
                              ObjectProvider<ComputerTool> computerTool,
                              ObjectProvider<fan.summer.fengyu.ai.tools.ToolGuardService> toolGuardProvider) {
        this(config, dataSourceConfigService, logging, pluginProcesses, computerTool,
                toolGuardProvider, defaultExitAction());
    }

    /** Test constructor — injects a no-op/recording exit action. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       Runnable exitAction) {
        this(config, dataSourceConfigService, null, null, null, null, exitAction);
    }

    /** Test constructor — pre-computer-use shape retained for existing tests. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       LoggingLevelService logging,
                       PluginProcessManager pluginProcesses,
                       Runnable exitAction) {
        this(config, dataSourceConfigService, logging, pluginProcesses, null, null, exitAction);
    }

    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       LoggingLevelService logging,
                       PluginProcessManager pluginProcesses,
                       ObjectProvider<ComputerTool> computerTool,
                       ObjectProvider<fan.summer.fengyu.ai.tools.ToolGuardService> toolGuardProvider,
                       Runnable exitAction) {
        this.config = config;
        this.dataSourceConfigService = dataSourceConfigService;
        this.logging = logging;
        this.pluginProcesses = pluginProcesses;
        this.computerTool = computerTool;
        this.toolGuardProvider = toolGuardProvider;
        this.exitAction = exitAction;
    }

    /** Reloads the cached rules/hooks in the guard after any Settings write. */
    private void reloadGuard() {
        fan.summer.fengyu.ai.tools.ToolGuardService guard =
                toolGuardProvider == null ? null : toolGuardProvider.getIfAvailable();
        if (guard != null) guard.reload();
    }

    /** Default exit: daemon thread sleeps 1s (let HTTP response flush) then exits SETUP_DONE. */
    private static Runnable defaultExitAction() {
        return () -> {
            Thread exitHook = new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                System.exit(ExitCodes.SETUP_DONE);
            }, "settings-exit");
            exitHook.setDaemon(true);
            exitHook.start();
        };
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("theme", config.getTheme());
        out.put("language", config.getLanguage());
        out.put("sidebarCollapsed", config.getSidebarCollapsed());
        out.put("logLevel", logging.currentLevel());
        out.put("unsandboxedPlugins", config.isUnsandboxedPluginsEnabled());
        out.put("updateApiBase", config.getUpdateApiBase(""));
        out.put("storeAllowPrivateNetwork", config.isStoreAllowPrivateNetwork());
        out.put("computerUseEnabled", AiConfigServiceHeadless.isComputerUseEnabled());
        // Capability probe (null when the desktop-mode bean is absent, e.g. plain web mode):
        // the Settings UI shows the computer-use card only when this is present.
        ComputerTool tool = computerTool == null ? null : computerTool.getIfAvailable();
        out.put("computerUse", tool == null ? null : tool.availability());
        out.put("permissionRules", parseRulesJson(AiConfigServiceHeadless.getPermissionRulesJson()));
        out.put("hooks", AiConfigServiceHeadless.getHooksJson());
        out.put("memoryEnabled", Boolean.parseBoolean(
                AiConfigServiceHeadless.getSetting("ai.memory.enabled", "false")));
        out.put("marketplaceRequireChecksum", AiConfigServiceHeadless.isMarketplaceChecksumRequired());
        fan.summer.fengyu.ai.tools.ToolGuardService guard =
                toolGuardProvider == null ? null : toolGuardProvider.getIfAvailable();
        out.put("invalidPermissionRules", guard == null ? List.of() : guard.invalidRules());
        return out;
    }

    private static Map<String, Object> parseRulesJson(String json) {
        try {
            Object parsed = fan.summer.fengyu.ai.util.JsonHelper.parse(json);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        } catch (Exception ignored) {
            // Corrupt stored config → report the raw text so the UI can fix it.
        }
        return json == null ? Map.of() : Map.of("raw", json);
    }

    /**
     * Validates a permission-rule table ({@code {"allow":[…],"ask":[…],"deny":[…]}}) and stores it.
     * Invalid rules are rejected with HTTP 400 naming the problem — a typo'd deny must never
     * silently vanish (that would weaken a user's security intent).
     */
    @PutMapping("/permission-rules")
    public Map<String, Object> putPermissionRules(@RequestBody Map<String, Object> body) {
        int total = strings(body.get("allow")).size() + strings(body.get("ask")).size()
                + strings(body.get("deny")).size();
        if (total > 200) {
            throw new IllegalArgumentException("Permission rules are capped at 200 entries total");
        }
        List<String> invalid = new ArrayList<>();
        List<fan.summer.fengyu.ai.tools.ToolPermissionRules.PermissionRule> parsed =
                fan.summer.fengyu.ai.tools.ToolPermissionRules.parseAll(
                        strings(body.get("allow")), strings(body.get("ask")),
                        strings(body.get("deny")), invalid);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Invalid permission rules: "
                    + String.join("; ", invalid));
        }
        AiConfigServiceHeadless.setPermissionRulesJson(fan.summer.fengyu.ai.util.JsonHelper.toJson(
                Map.of("allow", strings(body.get("allow")),
                        "ask", strings(body.get("ask")),
                        "deny", strings(body.get("deny")))));
        reloadGuard();
        log.info("Permission rules updated: {} allow / {} ask / {} deny",
                strings(body.get("allow")).size(), strings(body.get("ask")).size(),
                strings(body.get("deny")).size());
        return Map.of("ok", true, "rules", parsed.size());
    }

    /** Validates and stores the hook list; bad hook events are rejected with HTTP 400. */
    @PutMapping("/hooks")
    public Map<String, Object> putHooks(@RequestBody String json) {
        List<fan.summer.fengyu.ai.hooks.HookDispatcher.HookDefinition> parsed =
                fan.summer.fengyu.ai.tools.ToolGuardService.HookConfig.parse(json);
        if (parsed.size() > 50) {
            throw new IllegalArgumentException("Hook definitions are capped at 50");
        }
        // Reject entries whose event could not be resolved (they were silently dropped).
        Object raw;
        try {
            raw = fan.summer.fengyu.ai.util.JsonHelper.parse(json);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Hooks body must be a JSON array");
        }
        int submitted = raw instanceof List<?> list ? list.size() : 0;
        if (parsed.size() != submitted) {
            throw new IllegalArgumentException("Invalid hook definitions: "
                    + parsed.size() + " of " + submitted + " entries parsed "
                    + "(check event names: pre_tool_use, post_tool_use, post_tool_use_failure, "
                    + "run_complete, run_error; and that command/url is present)");
        }
        // A matcher that cannot compile would break every matching tool call at
        // dispatch time — reject it at save time with a precise message.
        for (var hook : parsed) {
            if (hook.matcher() != null && !hook.matcher().isBlank()) {
                try {
                    java.util.regex.Pattern.compile(hook.matcher());
                } catch (java.util.regex.PatternSyntaxException invalid) {
                    throw new IllegalArgumentException("Hook '" + hook.name()
                            + "' has an invalid matcher: " + invalid.getMessage());
                }
            }
        }
        AiConfigServiceHeadless.setHooksJson(json);
        reloadGuard();
        return Map.of("ok", true, "hooks", parsed.size());
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) out.add(text.trim());
        }
        return out;
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        if (body.get("theme") instanceof String t) {
            config.setTheme(t);
        }
        if (body.get("language") instanceof String l) {
            config.setLanguage(l);
        }
        if (body.get("logLevel") instanceof String level) {
            String effective = logging.setLevel(level);
            pluginProcesses.updateLogLevel(effective);
        }
        Object collapsed = body.get("sidebarCollapsed");
        if (collapsed instanceof Boolean b) {
            config.setSidebarCollapsed(b);
        } else if (collapsed instanceof String s) {
            config.setSidebarCollapsed(Boolean.parseBoolean(s));
        }
        Object unsandboxed = body.get("unsandboxedPlugins");
        if (unsandboxed instanceof Boolean b) {
            applyUnsandboxedPlugins(b);
        } else if (unsandboxed instanceof String s) {
            applyUnsandboxedPlugins(Boolean.parseBoolean(s));
        }
        if (body.get("updateApiBase") instanceof String u) {
            applyUpdateApiBase(u);
        }
        Object storeAllowPrivate = body.get("storeAllowPrivateNetwork");
        if (storeAllowPrivate instanceof Boolean b) {
            config.setStoreAllowPrivateNetwork(b);
        } else if (storeAllowPrivate instanceof String s) {
            config.setStoreAllowPrivateNetwork(Boolean.parseBoolean(s));
        }
        Object computerUse = body.get("computerUseEnabled");
        if (computerUse instanceof Boolean b) {
            applyComputerUseEnabled(b);
        } else if (computerUse instanceof String s) {
            applyComputerUseEnabled(Boolean.parseBoolean(s));
        }
        Object memory = body.get("memoryEnabled");
        if (memory instanceof Boolean b) {
            AiConfigServiceHeadless.setSetting("ai.memory.enabled", String.valueOf(b));
        } else if (memory instanceof String s) {
            AiConfigServiceHeadless.setSetting("ai.memory.enabled", String.valueOf(Boolean.parseBoolean(s)));
        }
        Object requireChecksum = body.get("marketplaceRequireChecksum");
        if (requireChecksum instanceof Boolean b) {
            AiConfigServiceHeadless.setMarketplaceChecksumRequired(b);
        } else if (requireChecksum instanceof String s) {
            AiConfigServiceHeadless.setMarketplaceChecksumRequired(Boolean.parseBoolean(s));
        }
        return get();
    }

    /**
     * Master switch for the {@code computer_*} screen-control tools. Hides (or restores) the
     * tool family on the next registry snapshot — no restart. Input-injecting calls keep
     * passing the per-turn tool approval gate independently of this switch.
     */
    private void applyComputerUseEnabled(boolean enabled) {
        AiConfigServiceHeadless.setComputerUseEnabled(enabled);
        log.info("Computer use tools {} via settings", enabled ? "ENABLED" : "disabled");
    }

    /**
     * Apply the plugin-unsandboxed toggle with a platform gate: enabling is rejected on platforms
     * that DO have a native process sandbox (there is no reason to disable protection there).
     * Throwing {@link IllegalArgumentException} lets {@link GlobalExceptionHandler} map it to 400.
     * Disabling is always allowed. Audited via SLF4J.
     */
    private void applyUnsandboxedPlugins(boolean enabled) {
        if (enabled && ProcessSandbox.isNativeSandboxAvailable()) {
            throw new IllegalArgumentException(
                "Unsandboxed plugin mode is only available on platforms without a native process sandbox");
        }
        config.setUnsandboxedPluginsEnabled(enabled);
        log.info("Plugin unsandboxed mode {} (platform: {})",
            enabled ? "ENABLED" : "disabled",
            ProcessSandbox.isNativeSandboxAvailable() ? "native" : "none");
    }

    /**
     * Persist the update-channel proxy base URL. An empty/blank value clears the override (the
     * client falls back to the bootstrap default / GitHub feed). A non-empty value must be an
     * absolute {@code http(s)} URL without credentials, query parameters, or a fragment — mirroring
     * the Electron {@code update-feed.ts} validation so both channels accept the same value.
     * Throwing {@link IllegalArgumentException} lets {@link GlobalExceptionHandler} map it to 400.
     * Audited via SLF4J.
     */
    private void applyUpdateApiBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty()) {
            // Validate BEFORE the setter strips the trailing slash — a path-suffix like "/" is legal,
            // but query/fragment/credentials are not. Mirrors update-feed.ts validation.
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Update API base must be an absolute HTTP(S) URL");
            }
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Update API base must use HTTP or HTTPS");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException(
                    "Update API base must not contain credentials, query parameters, or a fragment");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Update API base must contain a host");
            }
        }
        // Normalize here (trailing-slash strip) so every consumer reads a canonical base; the
        // setter re-normalizes defensively, so double-strip is a harmless no-op.
        String normalized = value.replaceAll("/+$", "");
        config.setUpdateApiBase(normalized);
        log.info("Update API base {} (source: settings UI)",
            normalized.isEmpty() ? "cleared → default GitHub feed" : normalized);
    }

    /**
     * Resets the database configuration: backs up {@code datasource.properties} to {@code .bak}
     * and signals a restart. On restart the process enters SETUP mode (config is gone), so the
     * setup wizard reappears and the user can reconfigure. Idempotent.
     */
    @PostMapping("/database/reset")
    public Map<String, Object> resetDatabase() {
        Path bak = dataSourceConfigService.backupAndClear();
        log.info("Database config reset via APP settings (bak={})", bak);
        exitAction.run();
        return Map.of("success", true, "action", "restart");
    }
}
