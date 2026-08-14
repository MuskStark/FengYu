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
import java.util.HashMap;
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
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
    @Autowired
    public SettingsController(AiConfigServiceHeadless config,
                              DataSourceConfigService dataSourceConfigService,
                              LoggingLevelService logging,
                              PluginProcessManager pluginProcesses,
                              ObjectProvider<ComputerTool> computerTool) {
        this(config, dataSourceConfigService, logging, pluginProcesses, computerTool, defaultExitAction());
    }

    /** Test constructor — injects a no-op/recording exit action. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       Runnable exitAction) {
        this(config, dataSourceConfigService, null, null, null, exitAction);
    }

    /** Test constructor — pre-computer-use shape retained for existing tests. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       LoggingLevelService logging,
                       PluginProcessManager pluginProcesses,
                       Runnable exitAction) {
        this(config, dataSourceConfigService, logging, pluginProcesses, null, exitAction);
    }

    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       LoggingLevelService logging,
                       PluginProcessManager pluginProcesses,
                       ObjectProvider<ComputerTool> computerTool,
                       Runnable exitAction) {
        this.config = config;
        this.dataSourceConfigService = dataSourceConfigService;
        this.logging = logging;
        this.pluginProcesses = pluginProcesses;
        this.computerTool = computerTool;
        this.exitAction = exitAction;
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
        out.put("computerUseEnabled", AiConfigServiceHeadless.isComputerUseEnabled());
        // Capability probe (null when the desktop-mode bean is absent, e.g. plain web mode):
        // the Settings UI shows the computer-use card only when this is present.
        ComputerTool tool = computerTool == null ? null : computerTool.getIfAvailable();
        out.put("computerUse", tool == null ? null : tool.availability());
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
        Object computerUse = body.get("computerUseEnabled");
        if (computerUse instanceof Boolean b) {
            applyComputerUseEnabled(b);
        } else if (computerUse instanceof String s) {
            applyComputerUseEnabled(Boolean.parseBoolean(s));
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
