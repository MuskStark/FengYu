# 数据库被移除后可重新配置 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the configured database is removed/unreachable at startup, automatically back up `datasource.properties` and fall back to SETUP mode so the user can reconfigure; also add manual "reset database config" endpoints in both APP and SETUP modes.

**Architecture:** Add a DB-reachability pre-flight probe in `HeadlessLauncher` (before Spring starts) that reuses `DataSourceConfigService.testConnection`. On failure, back up the stale config (`.bak`) and boot SETUP mode. Add a shared `backupAndClear()` method on `DataSourceConfigService`, plus two reset endpoints (`DELETE /api/setup/config` on `SetupController`, `POST /api/settings/database/reset` on `SettingsController`), both backed by an injectable `Runnable exitAction` seam so the `System.exit` restart path is unit-testable.

**Tech Stack:** Java 21, Spring Boot 4.1.0, JUnit 5, HikariCP, H2/SQLite/MySQL/PostgreSQL JDBC, Lombok-free records.

## Global Constraints

- **Branch:** `4.0.0-ZhiFlow`. Commit only when a task's steps say to.
- **Test style:** JUnit 5 + `@TempDir`, direct instantiation (no Spring) for service/unit tests — match `DataSourceConfigServiceTest.java` exactly. Controller tests instantiate the controller directly with a no-op `Runnable` exitAction (match `PluginControllerCategoriesTest.java` style).
- **Commit message prefix:** `🐛 fix(setup):` for bug-fix commits, `✨ feat(setup):` for the new endpoints. One commit per task unless noted.
- **Do NOT** add new dependencies — `spring-boot-starter-test` (MockMvc, JUnit) and H2 driver are already on the classpath.
- **Do NOT** modify `DataSourceConfigService.load`/`save`/`testConnection`/`buildFromWizard` signatures — only add new methods.
- **Exit code:** reuse `ExitCodes.SETUP_DONE` (0). Do not add new exit codes.
- **Password handling:** `CryptoUtil.decrypt("")` returns `""` (safe); `backupAndClear` only moves/deletes the properties file, never touches encryption.
- **File paths** below are relative to repo root `/Users/phoebej/Develop/Java/SwissKitJ`.

**Spec:** `docs/superpowers/specs/2026-07-10-db-recovery-after-removal-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java` | Modify | Add `backupAndClear()` — move config to `.bak` (timestamped if exists), fallback to delete |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java` | Modify | Replace `isDatasourceConfigured()` with probe-and-recover logic before Spring starts |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java` | Modify | Add `exitAction` seam + `DELETE /api/setup/config` endpoint; refactor `initialize` to use seam |
| `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java` | Modify | Inject `DataSourceConfigService` + `exitAction` seam; add `POST /api/settings/database/reset` |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java` | Modify | Add 3 `backupAndClear` tests |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/HeadlessLauncherProbeTest.java` | Create | Unit-test the probe-and-recover decision logic |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java` | Create | Unit-test `clearConfig` + `initialize` with no-op exitAction |
| `ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/SettingsControllerTest.java` | Create | Unit-test `resetDatabase` with no-op exitAction |

---

## Task 1: `DataSourceConfigService.backupAndClear()`

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java`

**Interfaces:**
- Produces: `public Path backupAndClear()` — moves `datasource.properties` to `datasource.properties.bak` (or `.bak.<timestamp>` if `.bak` exists); on move failure attempts direct delete; returns the backup path or `null` (no file / total failure). Reads `configFile()` which returns `Path.of(baseDir, "config", "datasource.properties")`.

- [ ] **Step 1: Write the failing tests**

Add these three tests to `DataSourceConfigServiceTest.java` (after the existing `buildFromWizard_embedded_createsParentDirectoryForCustomPath` test, before the closing brace). Add imports `java.nio.file.StandardCopyOption` is NOT needed — use `Files.move`. Add import for `Path` (already imported).

```java
    @Test
    void backupAndClear_movesConfigToBak() {
        DataSourceConfigService svc = newService();
        // Seed a real config file.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));

        java.nio.file.Path bak = svc.backupAndClear();

        assertNotNull(bak, "should return the backup path");
        assertFalse(Files.exists(svc.configFileForTest()),
                "original config should be gone");
        assertTrue(Files.exists(bak), "backup file should exist");
        assertTrue(bak.getFileName().toString().endsWith(".bak"),
                "backup name should end with .bak, got: " + bak);
    }

    @Test
    void backupAndClear_whenBakExists_appendsTimestamp() throws Exception {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);

        // First backup.
        svc.save(svc.buildFromWizard(DbType.H2, params));
        java.nio.file.Path firstBak = svc.backupAndClear();
        assertNotNull(firstBak);

        // Second backup of a re-saved config.
        svc.save(svc.buildFromWizard(DbType.SQLITE, params));
        java.nio.file.Path secondBak = svc.backupAndClear();
        assertNotNull(secondBak);

        assertNotEquals(firstBak, secondBak,
                "second backup must not overwrite the first");
        assertTrue(Files.exists(firstBak), "first backup must still exist");
        assertTrue(Files.exists(secondBak), "second backup must exist");
        assertTrue(secondBak.getFileName().toString().matches(".*\\.bak\\.\\d+"),
                "second backup name should be .bak.<timestamp>, got: " + secondBak);
    }

    @Test
    void backupAndClear_whenFileMissing_returnsNullNoThrow() {
        DataSourceConfigService svc = newService();
        java.nio.file.Path bak = svc.backupAndClear();
        assertNull(bak, "no file to back up → null");
    }
```

Also add a test-only accessor next to the existing `readRawForTest()` method so the test can reference the config path without hardcoding it. Add this method to `DataSourceConfigService`:

```java
    /** Test-only: the config file path (for existence assertions). */
    Path configFileForTest() {
        return configFile();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='DataSourceConfigServiceTest#backupAndClear_movesConfigToBak+backupAndClear_whenBakExists_appendsTimestamp+backupAndClear_whenFileMissing_returnsNullNoThrow'
```
Expected: COMPILATION FAILURE — `backupAndClear()` and `configFileForTest()` do not exist yet.

- [ ] **Step 3: Implement `backupAndClear()` and `configFileForTest()`**

Add to `DataSourceConfigService.java`. First add imports at the top (the file already imports `java.nio.file.Files`, `java.nio.file.Path`, `java.io.IOException`; add `java.time.LocalDateTime` is NOT needed — use `System.currentTimeMillis()` for a stable timestamp suffix).

Add this method after the existing `save(...)` method (before `buildFromWizard`):

```java
    /**
     * Backs up and clears {@code datasource.properties}: moves it to
     * {@code datasource.properties.bak} (or {@code .bak.<millis>} if a {@code .bak} already
     * exists, to avoid clobbering a prior backup). If the move fails, falls back to a direct
     * delete. Returns the backup path, or {@code null} if there was no file or backup/delete
     * failed entirely. Never throws — callers (startup probe, reset endpoints) rely on this to
     * degrade gracefully so the app can still boot into SETUP mode.
     */
    public Path backupAndClear() {
        Path file = configFile();
        if (!Files.exists(file)) return null;
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        if (Files.exists(bak)) {
            bak = file.resolveSibling(file.getFileName() + ".bak." + System.currentTimeMillis());
        }
        try {
            Files.move(file, bak);
            log.warn("Backed up stale datasource.properties to {}", bak);
            return bak;
        } catch (IOException moveErr) {
            log.warn("Move to .bak failed ({}); attempting direct delete", moveErr.getMessage());
            try {
                Files.deleteIfExists(file);
                log.warn("Deleted datasource.properties directly (backup unavailable)");
            } catch (IOException delErr) {
                log.error("Could not backup or delete datasource.properties: {}", delErr.getMessage());
            }
            return null;
        }
    }
```

And add `configFileForTest()` after `readRawForTest()`:

```java
    /** Test-only: the config file path (for existence assertions). */
    Path configFileForTest() {
        return configFile();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='DataSourceConfigServiceTest'
```
Expected: PASS — all tests including the 3 new ones. (The timestamp-suffix test must allow the two backups to differ; if it flakes due to same-millisecond, the regex still matches because `System.currentTimeMillis()` returns the same value only within the same ms — to be safe the test re-saves between backups which takes >1ms in practice. If flaky, switch to `Thread.sleep(2)` — but try first.)

- [ ] **Step 5: Commit**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/DataSourceConfigService.java ZhiFlow/src/test/java/fan/summer/zhiflow/setup/DataSourceConfigServiceTest.java && git commit -m "✨ feat(setup): DataSourceConfigService.backupAndClear() — move config to .bak"
```

---

## Task 2: HeadlessLauncher startup probe + fallback

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/HeadlessLauncherProbeTest.java` (Create)

**Interfaces:**
- Consumes: `DataSourceConfigService.load()`, `DataSourceConfigService.testConnection(DataSourceConfig)`, `DataSourceConfigService.backupAndClear()` (all from Task 1 + existing).
- Produces: package-private static method `HeadlessLauncher.probeAndDecide(DataSourceConfigService svc)` returning `boolean` (`true` = APP mode / configured & reachable, `false` = SETUP mode). Called by `main`.

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/HeadlessLauncherProbeTest.java`:

```java
package fan.summer.zhiflow;

import fan.summer.zhiflow.setup.DataSourceConfig;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.DbType;
import fan.summer.zhiflow.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests {@link HeadlessLauncher#probeAndDecide(DataSourceConfigService)} — the startup
 * decision: probe the configured DB; on failure back up the config and fall back to SETUP mode.
 */
class HeadlessLauncherProbeTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void noConfig_returnsFalse_noBackup() {
        boolean configured = HeadlessLauncher.probeAndDecide(newService());
        assertFalse(configured, "no config file → SETUP mode");
        assertFalse(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "nothing to back up");
    }

    @Test
    void unreachableSqlite_backsUpAndReturnsFalse() {
        DataSourceConfigService svc = newService();
        // Save a config pointing at a SQLite file whose PARENT DIRECTORY does not exist.
        // buildFromWizard creates the parent dir, so first save a normal config, then delete
        // the database dir to simulate the "database removed" scenario.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.SQLITE, params);
        svc.save(cfg);
        // Remove the database directory to simulate the user deleting it.
        deleteRecursively(tempDir.resolve("database"));

        boolean configured = HeadlessLauncher.probeAndDecide(svc);

        assertFalse(configured, "unreachable DB → SETUP mode");
        assertFalse(Files.exists(svc.configFileForTest()),
                "stale config should have been backed up (removed)");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup .bak should exist");
    }

    @Test
    void reachableH2_returnsTrue_configUntouched() {
        DataSourceConfigService svc = newService();
        // H2 creates the file on connect; point at a temp file path whose parent exists.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        DataSourceConfig cfg = svc.buildFromWizard(DbType.H2, params);
        svc.save(cfg);

        boolean configured = HeadlessLauncher.probeAndDecide(svc);

        assertTrue(configured, "reachable H2 → APP mode");
        assertTrue(Files.exists(svc.configFileForTest()),
                "reachable config must NOT be backed up / deleted");
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.delete(path); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='HeadlessLauncherProbeTest'
```
Expected: COMPILATION FAILURE — `probeAndDecide` does not exist.

- [ ] **Step 3: Implement `probeAndDecide` and wire it into `main`**

Modify `ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java`.

First add imports (the file currently imports `DataSourceConfigService`, `Files`, `Path`, `ArrayList`, `List`). Add:
```java
import fan.summer.zhiflow.setup.DataSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.DriverManager;
```

Add a logger field and the `probeAndDecide` method. Replace the existing `isDatasourceConfigured` method (lines 65-72) with:

```java
    private static final Logger log = LoggerFactory.getLogger(HeadlessLauncher.class);

    /**
     * Startup decision: load the datasource config and probe the DB. Returns {@code true} (APP
     * mode) only when a config is loaded AND a JDBC {@code SELECT 1} succeeds. Returns
     * {@code false} (SETUP mode) when there is no config, or when the config exists but the DB is
     * unreachable — in the latter case the stale config is backed up to {@code .bak} so the wizard
     * can reappear. Non-connection exceptions (e.g. driver classpath issues) are logged and treated
     * conservatively as {@code true} to avoid deleting a possibly-good config.
     */
    static boolean probeAndDecide(DataSourceConfigService configService) {
        DataSourceConfig cfg = configService.load();
        if (cfg == null) {
            return false;
        }
        // Short JDBC login timeout so a down remote host fails fast (doesn't block startup).
        int prevTimeout = DriverManager.getLoginTimeout();
        DriverManager.setLoginTimeout(5);
        boolean reachable;
        try {
            reachable = configService.testConnection(cfg).success();
        } catch (RuntimeException e) {
            // Non-connection failure (driver missing, config corruption) — don't delete config.
            log.warn("DB probe threw (non-connection); booting APP mode conservatively: {}", e.getMessage());
            DriverManager.setLoginTimeout(prevTimeout);
            return true;
        } finally {
            DriverManager.setLoginTimeout(prevTimeout);
        }
        if (reachable) {
            return true;
        }
        log.warn("Configured DB is unreachable at startup; backing up config and falling back to SETUP mode.");
        configService.backupAndClear();
        return false;
    }
```

Then update `main` to call `probeAndDecide` instead of `isDatasourceConfigured`. In `main`, replace:
```java
        boolean configured = isDatasourceConfigured();
        startWithFallback(port, configured);
```
with:
```java
        boolean configured = probeAndDecide(new DataSourceConfigService());
        startWithFallback(port, configured);
```

Remove the old `isDatasourceConfigured` method entirely (its logic is now inside `probeAndDecide`).

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='HeadlessLauncherProbeTest'
```
Expected: PASS — all 3 tests.

- [ ] **Step 5: Run the full existing test suite to ensure no regression**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test
```
Expected: PASS — all tests green (HeadlessLauncher changes are pre-Spring; HeadlessIntegrationTest uses the test profile with its own H2 datasource and never calls `probeAndDecide`).

- [ ] **Step 6: Commit**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && git add ZhiFlow/src/main/java/fan/summer/zhiflow/HeadlessLauncher.java ZhiFlow/src/test/java/fan/summer/zhiflow/HeadlessLauncherProbeTest.java && git commit -m "🐛 fix(setup): probe DB at startup, fall back to SETUP mode when unreachable"
```

---

## Task 3: `SetupController` exit-action seam + `DELETE /api/setup/config`

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java` (Create)

**Interfaces:**
- Consumes: `DataSourceConfigService.backupAndClear()` (Task 1), `ExitCodes.SETUP_DONE`.
- Produces: constructor `SetupController(DataSourceConfigService, Runnable exitAction)` (package-private for tests); endpoint `DELETE /api/setup/config` → `{success:true, action:"restart"}`. The existing single-arg public constructor is retained for Spring auto-wiring and delegates to the default exit action.

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java`:

```java
package fan.summer.zhiflow.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests {@link SetupController} reset + initialize paths using a no-op, recording
 * {@code exitAction} so {@code System.exit} never fires during tests.
 */
class SetupControllerTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    private SetupController newController(DataSourceConfigService svc, AtomicBoolean exitFired) {
        return new SetupController(svc, () -> exitFired.set(true));
    }

    @Test
    void clearConfig_backsUpConfigAndSignalsRestart() {
        DataSourceConfigService svc = newService();
        // Seed a config.
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));
        assertTrue(Files.exists(svc.configFileForTest()), "precondition: config exists");

        AtomicBoolean exitFired = new AtomicBoolean(false);
        SetupController controller = newController(svc, exitFired);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.clearConfig();

        assertEquals(true, result.get("success"));
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "exit action should have been invoked");
        assertFalse(Files.exists(svc.configFileForTest()), "config should be backed up / gone");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup should exist");
    }

    @Test
    void clearConfig_whenNoConfig_isIdempotent() {
        DataSourceConfigService svc = newService();
        AtomicBoolean exitFired = new AtomicBoolean(false);
        SetupController controller = newController(svc, exitFired);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.clearConfig();

        assertEquals(true, result.get("success"), "idempotent: no-op still success");
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "restart still signalled even with no config");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='SetupControllerTest'
```
Expected: COMPILATION FAILURE — `clearConfig()` method and the 2-arg constructor don't exist.

- [ ] **Step 3: Implement the seam + endpoint**

Modify `ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java`.

Add imports:
```java
import org.springframework.web.bind.annotation.DeleteMapping;
import java.nio.file.Path;
```

Replace the existing field + constructor block (lines 33-39):
```java
    private final DataSourceConfigService configService;

    public SetupController(DataSourceConfigService configService) {
        this.configService = configService;
    }
```
with:
```java
    private final DataSourceConfigService configService;
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
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
```

Refactor the existing `initialize` method's inline exit-hook (lines 121-127) to use `exitAction.run()`. Replace:
```java
        log.info("Setup complete; exiting in 1s for restart into APP mode (type={})", type);
        Thread exitHook = new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            System.exit(ExitCodes.SETUP_DONE);
        }, "setup-exit");
        exitHook.setDaemon(true);
        exitHook.start();

        return Map.of("success", true, "action", "restart");
```
with:
```java
        log.info("Setup complete; exiting in 1s for restart into APP mode (type={})", type);
        exitAction.run();

        return Map.of("success", true, "action", "restart");
```

Add the new `clearConfig` endpoint after `initialize` (before `labelFor`):
```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='SetupControllerTest'
```
Expected: PASS — both tests.

- [ ] **Step 5: Run full test suite to ensure no regression**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test
```
Expected: PASS — the `initialize` refactor preserves production behavior (same daemon-thread exit); `HeadlessIntegrationTest` does not call `/api/setup/*` so it's unaffected.

- [ ] **Step 6: Commit**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && git add ZhiFlow/src/main/java/fan/summer/zhiflow/setup/SetupController.java ZhiFlow/src/test/java/fan/summer/zhiflow/setup/SetupControllerTest.java && git commit -m "✨ feat(setup): DELETE /api/setup/config reset endpoint + exit-action seam"
```

---

## Task 4: `SettingsController` reset endpoint (APP mode)

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java`
- Test: `ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/SettingsControllerTest.java` (Create)

**Interfaces:**
- Consumes: `DataSourceConfigService.backupAndClear()` (Task 1), `ExitCodes.SETUP_DONE`, `AiConfigServiceHeadless` (existing).
- Produces: constructor `SettingsController(AiConfigServiceHeadless, DataSourceConfigService, Runnable exitAction)` (package-private for tests); endpoint `POST /api/settings/database/reset` → `{success:true, action:"restart"}`.

- [ ] **Step 1: Write the failing test**

Create `ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/SettingsControllerTest.java`:

```java
package fan.summer.zhiflow.web.controller;

import fan.summer.zhiflow.ai.service.AiConfigServiceHeadless;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import fan.summer.zhiflow.setup.DbType;
import fan.summer.zhiflow.setup.WizardParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit-tests {@link SettingsController#resetDatabase()} using a no-op, recording exit action and
 * a mock {@link AiConfigServiceHeadless} (its methods are not exercised by the reset path).
 */
class SettingsControllerTest {

    @TempDir
    Path tempDir;

    private DataSourceConfigService newService() {
        return new DataSourceConfigService(tempDir.toString());
    }

    @Test
    void resetDatabase_backsUpConfigAndSignalsRestart() {
        DataSourceConfigService svc = newService();
        WizardParams params = new WizardParams(
                tempDir.resolve("database/zhiflow").toString(), null, null, null, null, null);
        svc.save(svc.buildFromWizard(DbType.H2, params));
        assertTrue(Files.exists(svc.configFileForTest()), "precondition: config exists");

        AtomicBoolean exitFired = new AtomicBoolean(false);
        SettingsController controller = new SettingsController(
                mock(AiConfigServiceHeadless.class), svc, () -> exitFired.set(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.resetDatabase();

        assertEquals(true, result.get("success"));
        assertEquals("restart", result.get("action"));
        assertTrue(exitFired.get(), "exit action should have been invoked");
        assertFalse(Files.exists(svc.configFileForTest()), "config should be backed up / gone");
        assertTrue(Files.exists(tempDir.resolve("config/datasource.properties.bak")),
                "backup should exist");
    }

    @Test
    void resetDatabase_whenNoConfig_isIdempotent() {
        DataSourceConfigService svc = newService();
        AtomicBoolean exitFired = new AtomicBoolean(false);
        SettingsController controller = new SettingsController(
                mock(AiConfigServiceHeadless.class), svc, () -> exitFired.set(true));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) controller.resetDatabase();

        assertEquals(true, result.get("success"));
        assertTrue(exitFired.get(), "restart still signalled");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='SettingsControllerTest'
```
Expected: COMPILATION FAILURE — 3-arg constructor and `resetDatabase()` don't exist.

- [ ] **Step 3: Implement the seam + endpoint**

Modify `ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java`.

Add imports:
```java
import fan.summer.zhiflow.ExitCodes;
import fan.summer.zhiflow.setup.DataSourceConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import java.nio.file.Path;
```

Replace the existing fields + constructor (lines 22-28):
```java
    private final AiConfigServiceHeadless config;

    public SettingsController(AiConfigServiceHeadless config) {
        this.config = config;
    }
```
with:
```java
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final AiConfigServiceHeadless config;
    private final DataSourceConfigService dataSourceConfigService;
    private final Runnable exitAction;

    /** Production constructor — Spring auto-wires this. Exit action delays 1s then exits. */
    public SettingsController(AiConfigServiceHeadless config,
                              DataSourceConfigService dataSourceConfigService) {
        this(config, dataSourceConfigService, defaultExitAction());
    }

    /** Test constructor — injects a no-op/recording exit action. */
    SettingsController(AiConfigServiceHeadless config,
                       DataSourceConfigService dataSourceConfigService,
                       Runnable exitAction) {
        this.config = config;
        this.dataSourceConfigService = dataSourceConfigService;
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
```

Add the reset endpoint after the `put` method (before the closing brace):
```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test -Dtest='SettingsControllerTest'
```
Expected: PASS — both tests. (Mockito is available via `spring-boot-starter-test`.)

- [ ] **Step 5: Run full test suite + build**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test
```
Expected: PASS — all tests green. The `SettingsController` constructor change is backward-compatible because Spring auto-wires by type; `AiApplication` scans `fan.summer.zhiflow` so `DataSourceConfigService` (`@Service` in the `setup` package) is available as a bean in APP mode.

- [ ] **Step 6: Commit**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && git add ZhiFlow/src/main/java/fan/summer/zhiflow/web/controller/SettingsController.java ZhiFlow/src/test/java/fan/summer/zhiflow/web/controller/SettingsControllerTest.java && git commit -m "✨ feat(settings): POST /api/settings/database/reset (APP-mode DB reconfigure)"
```

---

## Task 5: Verify TokenAuthFilter routing for the new endpoints

**Files:**
- Modify: `ZhiFlow/src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java` (only if needed)

**Interfaces:**
- Consumes: existing filter bypass logic.

This is a verification task — the spec notes `/api/setup/*` is already bypassed and `/api/settings/*` requires a token (APP mode). Confirm no change is needed; if the filter uses exact path matching rather than prefix, adjust.

- [ ] **Step 1: Read the filter and check path matching**

Read `ZhiFlow/src/main/java/fan/summer/zhiflow/web/filter/TokenAuthFilter.java`. Check how `/api/setup/` is bypassed (prefix `startsWith` vs exact match) and whether `/api/settings/database/reset` falls under the same token-required rule as other `/api/settings` paths.

- [ ] **Step 2: Confirm or fix**

- `/api/setup/config` (DELETE): must be token-bypassed in SETUP mode. If the filter uses `path.startsWith("/api/setup/")` → already covered, no change. If it uses exact matches → add `/api/setup/config`.
- `/api/settings/database/reset` (POST): must require token (APP mode). If `/api/settings` paths are token-required by default (not in the bypass list) → no change. Only add to bypass list if you intentionally want it unprotected (you do NOT).

If no change is needed (expected): skip to Step 3. If a change is needed, edit the filter and add a test mirroring the existing filter test pattern.

- [ ] **Step 3: Run full suite + commit (only if a change was made)**

If no change: no commit. If changed:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test && git add -A && git commit -m "🐛 fix(web): TokenAuthFilter covers /api/setup/config reset path"
```

---

## Task 6: Manual end-to-end verification + final build

**Files:** None (verification only)

- [ ] **Step 1: Full build + all tests**

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -q -pl ZhiFlow test
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Manual reproduction of the original crash scenario**

This verifies the actual user-reported bug is fixed. In a terminal:

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ
# 1. Build the classpath (compile)
mvn -q -pl ZhiFlow compile
# 2. Run HeadlessLauncher once to go through setup (or pre-create a config).
#    Easiest: hand-write a sqlite datasource.properties pointing at .zhiflow/database/zhiflow,
#    then run the launcher so it creates the DB, then delete the DB dir and restart.
mkdir -p .zhiflow/config
cat > .zhiflow/config/datasource.properties <<'EOF'
db.type=sqlite
db.url=jdbc:sqlite:.zhiflow/database/zhiflow
db.driver=org.sqlite.JDBC
db.dialect=org.hibernate.community.dialect.SQLiteDialect
db.username=
db.password=
db.file.path=.zhiflow/database/zhiflow
EOF
```

Run the launcher (APP mode should start, creating the DB):
```bash
mvn -q -pl ZhiFlow exec:java -Dexec.mainClass=fan.summer.zhiflow.HeadlessLauncher 2>&1 | head -40
```
(If `exec:java` isn't configured, run via IDE: `HeadlessLauncher.main` with no args. Confirm it boots APP mode — `ZHIFLOW_PORT=` printed, no crash.)

Then simulate the bug:
```bash
rm -rf .zhiflow/database
```
Run the launcher again. **Expected (fixed behavior):** logs show `Configured DB is unreachable at startup; backing up config and falling back to SETUP mode.`, then SETUP mode boots (no crash). Confirm `.zhiflow/config/datasource.properties.bak` exists and `datasource.properties` is gone.

- [ ] **Step 3: Clean up test artifacts**

```bash
cd /Users/phoebej/Develop/Java/SwissKitJ
rm -rf .zhiflow/database .zhiflow/config/datasource.properties .zhiflow/config/datasource.properties.bak
```
Do NOT commit these artifacts (they're gitignored under `.zhiflow/` — verify with `git status`).

- [ ] **Step 4: Final commit (docs update if any)**

If the spec doc or any docs need a status bump to "Implemented", update and commit:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ && git add -A && git commit -m "📝 docs(setup): mark db-recovery spec implemented" || echo "nothing to commit"
```

---

## Notes for the implementer

- **Mockito availability:** `spring-boot-starter-test` (test scope, in `ZhiFlow/pom.xml`) bundles Mockito. The `SettingsControllerTest` uses `mock(AiConfigServiceHeadless.class)` — if that class cannot be mocked (final class), use `Mockito.mock` with the `mockito-inline` agent (already enabled by default in Mockito 5.x bundled with Spring Boot 4.1). If it's a concrete class with no final modifier, plain `mock()` works.
- **`System.currentTimeMillis()` timestamp uniqueness in tests:** the `backupAndClear_whenBakExists_appendsTimestamp` test re-saves a config between two backups; `save()` does file I/O which takes >1ms, so the two `currentTimeMillis()` calls differ. If the test flakes on a very fast machine, add `Thread.sleep(2)` between the two `backupAndClear()` calls — but try without first.
- **`SetupController` is scanned in BOTH modes:** `AiApplication` scans `fan.summer.zhiflow` and does NOT exclude `SetupController` (only `SetupApplication` is excluded). So `DELETE /api/setup/config` is reachable in APP mode too. This is fine — it backs up config and restarts into SETUP either way. The `POST /api/settings/database/reset` endpoint on `SettingsController` provides the APP-mode-flavored entry point under the settings namespace; both are intentionally available.
- **Do NOT** add `@ConditionalOnProperty` to `SetupController` — it's already active in both modes by design and the existing `/api/setup/status` endpoint relies on this.
