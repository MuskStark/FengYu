package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ExitCodes;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.log.LoggingLevelService;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import fan.summer.fengyu.setup.DataSourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
    @Autowired
    public SettingsController(AiConfigServiceHeadless config,
                              DataSourceConfigService dataSourceConfigService,
                              LoggingLevelService logging,
                              PluginProcessManager pluginProcesses) {
        this(config, dataSourceConfigService, logging, pluginProcesses, defaultExitAction());
    }

    /** Test constructor — injects a no-op/recording exit action. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       Runnable exitAction) {
        this(config, dataSourceConfigService, null, null, exitAction);
    }

    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       LoggingLevelService logging,
                       PluginProcessManager pluginProcesses,
                       Runnable exitAction) {
        this.config = config;
        this.dataSourceConfigService = dataSourceConfigService;
        this.logging = logging;
        this.pluginProcesses = pluginProcesses;
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
        return get();
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
