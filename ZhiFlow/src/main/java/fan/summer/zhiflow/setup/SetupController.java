package fan.summer.zhiflow.setup;

import fan.summer.zhiflow.ExitCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Setup wizard REST endpoints. Only active in SETUP mode (served by {@link SetupApplication}).
 * Token auth is bypassed for these paths (see {@code TokenAuthFilter}).
 *
 * <p>The {@code initialize} endpoint does NOT create schema or a virtual user — DDL is deferred
 * to APP-mode startup. It only: (1) re-tests the connection, (2) persists {@code datasource.properties},
 * (3) signals the process to exit so the parent supervisor restarts into APP mode. On APP start,
 * Hibernate {@code ddl-auto=update} (from {@code application.yml}) creates the schema from the
 * entities, and {@code VirtualUserInitializer} inserts the virtual user id=1.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

    private final DataSourceConfigService configService;
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
    @Autowired
    public SetupController(DataSourceConfigService configService) {
        this(configService, defaultExitAction());
    }

    /** Test constructor — injects a no-op/recording exit action. */
    SetupController(DataSourceConfigService configService, Runnable exitAction) {
        this.configService = configService;
        this.exitAction = exitAction;
    }

    /** Default exit: daemon thread sleeps 1s (let HTTP response flush) then exits SETUP_DONE. */
    private static Runnable defaultExitAction() {
        return () -> {
            Thread exitHook = new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                System.exit(ExitCodes.SETUP_DONE);
            }, "setup-exit");
            exitHook.setDaemon(true);
            exitHook.start();
        };
    }

    /** Frontend's first call on startup — determines whether to show the wizard or main shell. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        DataSourceConfig cfg = configService.load();
        boolean initialized = cfg != null;
        Map<String, Object> out = new HashMap<>();
        out.put("initialized", initialized);
        if (!initialized) {
            out.put("supportedTypes", Arrays.stream(DbType.values())
                    .map(e -> e.name().toLowerCase()).toList());
            out.put("embeddedTypes", Arrays.stream(DbType.values())
                    .filter(e -> e.embedded).map(e -> e.name().toLowerCase()).toList());
        }
        return out;
    }

    /** Returns per-type form metadata for the wizard UI. */
    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbType t : DbType.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", t.name().toLowerCase());
            entry.put("label", labelFor(t));
            entry.put("embedded", t.embedded);
            List<Map<String, Object>> fields = new ArrayList<>();
            if (t.embedded) {
                Map<String, Object> filePath = new LinkedHashMap<>();
                filePath.put("name", "filePath");
                filePath.put("label", "Data file location");
                filePath.put("required", true);
                filePath.put("secret", false);
                filePath.put("default", configService.defaultEmbeddedPath().toString());
                fields.add(filePath);
            } else {
                fields.add(Map.of("name", "host", "required", true, "secret", false));
                fields.add(Map.of("name", "port", "required", false, "secret", false,
                        "default", defaultPort(t)));
                fields.add(Map.of("name", "database", "required", true, "secret", false));
                fields.add(Map.of("name", "username", "required", true, "secret", false));
                fields.add(Map.of("name", "password", "required", true, "secret", true));
            }
            entry.put("fields", fields);
            out.add(entry);
        }
        return out;
    }

    /** Tests a connection WITHOUT persisting. Frontend "Test connection" button. */
    @PostMapping("/test-connection")
    public ConnectionTestResult testConnection(@RequestBody TestRequest req) {
        DbType type = DbType.fromName(req.type());
        DataSourceConfig cfg = configService.buildFromWizard(type, req.params());
        return configService.testConnection(cfg);
    }

    /**
     * Final initialization: re-test, persist config, signal restart. No schema/virtual-user
     * creation here — deferred to APP-mode startup (ddl-auto=update + VirtualUserInitializer).
     */
    @PostMapping("/initialize")
    public Map<String, Object> initialize(@RequestBody TestRequest req) {
        DbType type = DbType.fromName(req.type());
        DataSourceConfig cfg = configService.buildFromWizard(type, req.params());

        // 1. Re-verify connection
        ConnectionTestResult test = configService.testConnection(cfg);
        if (!test.success()) {
            return Map.of("success", false,
                    "error", test.error() != null ? test.error() : "connection failed",
                    "step", "connection");
        }

        // 2. Persist config (password encrypted on save)
        try {
            configService.save(cfg);
        } catch (Exception e) {
            log.error("Failed to persist datasource config", e);
            return Map.of("success", false, "error", e.getMessage(), "step", "save");
        }

        // 3. Signal restart into APP mode. On APP start, Hibernate ddl-auto=update
        //    (application.yml) creates the schema from entities, and VirtualUserInitializer
        //    inserts the virtual user id=1 — no DDL needed here.
        //    Delay 1s so the HTTP response is flushed to the frontend first.
        log.info("Setup complete; exiting in 1s for restart into APP mode (type={})", type);
        exitAction.run();

        return Map.of("success", true, "action", "restart");
    }

    /**
     * Backs up and clears {@code datasource.properties}, then signals a restart. Idempotent — if
     * no config file exists, no backup is created but {@code action:"restart"} is still returned.
     * On restart the process enters SETUP mode (config is gone), so the wizard reappears.
     */
    @DeleteMapping("/config")
    public Map<String, Object> clearConfig() {
        Path bak = configService.backupAndClear();
        log.info("Setup config cleared via DELETE /api/setup/config (bak={})", bak);
        exitAction.run();
        return Map.of("success", true, "action", "restart");
    }

    private String labelFor(DbType t) {
        return switch (t) {
            case H2 -> "H2 (local embedded)";
            case SQLITE -> "SQLite (local embedded)";
            case MYSQL -> "MySQL (remote)";
            case POSTGRESQL -> "PostgreSQL (remote)";
        };
    }

    private int defaultPort(DbType t) {
        return switch (t) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            default -> 0;
        };
    }

    /** Wizard request body — type + raw params. */
    public record TestRequest(String type, WizardParams params) {}
}
