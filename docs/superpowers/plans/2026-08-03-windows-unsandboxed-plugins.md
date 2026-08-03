# Windows 无沙箱插件运行开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a platform-level opt-in toggle so plugin workers can run on Windows (no native sandbox) instead of hard-failing HTTP 500.

**Architecture:** A new persisted boolean setting `plugin.unsandboxed` (stored in the existing `app_setting` table) is consumed by `PluginProcessManager`, which OR-s it with the existing per-turn `FULL_ACCESS` flag to decide whether to launch the worker via `sandbox.unrestricted()` (no throw) instead of `sandbox.plugin()` (fail-closed throw on NONE). `ProcessSandbox` itself is unchanged — the safety primitive stays pure. The toggle is only visible/settable on platforms without a native sandbox (`ProcessSandbox.isNativeSandboxAvailable() == false`); on Linux/macOS the backend rejects enabling it with HTTP 400. Frontend shows it (compatibility-mode only) as a `.cx-segment` button group with a confirmation dialog when enabling.

**Tech Stack:** Java 21 + Spring Boot (`@Component`/`@RestController`), JPA `app_setting`, JUnit 5 + Mockito (tests), Vue 3.5 + Pinia + vue-i18n + Vuetify 3 (frontend).

## Global Constraints

- **Commit convention:** conventional commits with emojis — `✨` feat, `🐛` fix, `♻️` refactor, `📝` docs, `⬆️` deps, `🔥` removal. Commit, push, tag, or publish only when the user asks.
- **No DB migration:** `spring.jpa.hibernate.ddl-auto: update` auto-creates tables/columns; the toggle reuses the existing `app_setting` table as a new row — no entity change, no migration file.
- **`ProcessSandbox` must not change:** the fail-closed `IllegalStateException` in `plugin()` stays. The existing test `pluginWorkerFailsClosedWithoutNativeSandbox` must keep passing unmodified.
- **No new beans, no `@ConditionalOnXxx`:** the toggle reuses `AiConfigServiceHeadless` (existing `@Component` static facade).
- **Naming:** setting key is dotted-lowercase `plugin.unsandboxed`, mirroring the existing `sidebar.collapsed` key. Frontend camelCase field is `unsandboxedPlugins`.
- **Backend build/verify:** `./mvnw -f FengYu/pom.xml -DskipTests=false -pl . test` for the relevant test classes; full module build with `./mvnw clean package -f FengYu/pom.xml -DskipTests`.
- **Frontend build/verify:** `cd frontend && npm run build` (type-check + vite build).
- **i18n parity:** every key added to `frontend/src/i18n/en.json` MUST be added to `frontend/src/i18n/zh.json` with the same key path (structurally mirrored), per AGENTS.md.
- **Source is authoritative:** when prose conflicts with the repo, the repo wins. Read the actual file before trusting a summary.

**Reference spec:** `docs/superpowers/specs/2026-08-03-windows-unsandboxed-plugins-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|---------------|--------|
| `FengYu/src/main/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadless.java` | Persist + expose the toggle via static facade | Modify |
| `FengYu/src/main/java/fan/summer/fengyu/web/controller/SettingsController.java` | REST get/put the field + platform gate (400) + audit log | Modify |
| `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java` | Consume the toggle (OR with FULL_ACCESS) + audit log | Modify |
| `FengYu/src/test/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadlessTest.java` | Round-trip test for the new setting | Modify |
| `FengYu/src/test/java/fan/summer/fengyu/web/controller/SettingsControllerTest.java` | Platform-gate 400 test on sandboxed platforms | Modify |
| `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java` | Toggle unblocks worker under forced NONE backend | Modify |
| `frontend/src/api/types.ts` | Add `unsandboxedPlugins` to `AppSettings` | Modify |
| `frontend/src/stores/settings.ts` | Add `unsandboxedPlugins` ref + setter | Modify |
| `frontend/src/views/Settings.vue` | Render toggle row (compat-mode only) + confirm dialog | Modify |
| `frontend/src/i18n/en.json` | New i18n keys | Modify |
| `frontend/src/i18n/zh.json` | New i18n keys (mirrored) | Modify |

---

## Task 1: Persist the toggle in `AiConfigServiceHeadless`

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadless.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadlessTest.java`

**Interfaces:**
- Consumes: the existing `readSetting(key, default)` / `writeSetting(key, value)` private instance helpers and the `static volatile INSTANCE` field (lines 29, 127-146).
- Produces: two public static methods used by later tasks:
  - `public static boolean isUnsandboxedPluginsEnabled()`
  - `public static void setUnsandboxedPluginsEnabled(boolean enabled)`

- [ ] **Step 1: Write the failing test**

Add this test method to `AiConfigServiceHeadlessTest.java` (mirror the existing `setAiMode_roundTrips` pattern at lines 44-48; the `newHeadless()` helper at lines 35-42 publishes the static singleton via `init()`, so the static setter routes through the real repo):

```java
    @Test
    void unsandboxedPluginsDefaultsFalseAndRoundTrips() {
        AiConfigServiceHeadless h = newHeadless();
        // Default is false when no row exists.
        assertFalse(AiConfigServiceHeadless.isUnsandboxedPluginsEnabled());
        // Setting true persists and is read back via the static facade.
        h.setUnsandboxedPluginsEnabled(true);
        assertTrue(AiConfigServiceHeadless.isUnsandboxedPluginsEnabled());
        // Setting false again round-trips.
        h.setUnsandboxedPluginsEnabled(false);
        assertFalse(AiConfigServiceHeadless.isUnsandboxedPluginsEnabled());
    }
```

Also add `import static org.junit.jupiter.api.Assertions.assertFalse;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` to the imports (the file already imports `assertEquals`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiConfigServiceHeadlessTest#unsandboxedPluginsDefaultsFalseAndRoundTrips`
Expected: COMPILE ERROR — `isUnsandboxedPluginsEnabled()` / `setUnsandboxedPluginsEnabled(boolean)` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `AiConfigServiceHeadless.java`:

Add a new key constant next to `LOG_LEVEL_KEY` (after line 39):
```java
    private static final String PLUGIN_UNSANDBOXED_KEY = "plugin.unsandboxed";
```

Add two public static methods in the "Generic UI-shell settings" block, right after `setSidebarCollapsed` (after line 89, before the `// ── Reads` comment at line 91):
```java
    /**
     * Platform-level opt-in to run plugin workers without the native process sandbox. Only meaningful
     * on platforms where {@link fan.summer.fengyu.security.ProcessSandbox} detects no native isolator
     * (Windows); {@link fan.summer.fengyu.web.controller.SettingsController} gates writes to those
     * platforms. Default {@code false} (fail-closed).
     */
    public static boolean isUnsandboxedPluginsEnabled() {
        return Boolean.parseBoolean(INSTANCE.readSetting(PLUGIN_UNSANDBOXED_KEY, "false"));
    }

    public static void setUnsandboxedPluginsEnabled(boolean enabled) {
        INSTANCE.writeSetting(PLUGIN_UNSANDBOXED_KEY, String.valueOf(enabled));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=AiConfigServiceHeadlessTest#unsandboxedPluginsDefaultsFalseAndRoundTrips`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadless.java \
        FengYu/src/test/java/fan/summer/fengyu/ai/service/AiConfigServiceHeadlessTest.java
git commit -m "✨ feat(security): persist plugin.unsandboxed setting (defaults fail-closed)"
```

---

## Task 2: Surface the toggle in `SettingsController` with platform gate

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/web/controller/SettingsController.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/web/controller/SettingsControllerTest.java`

**Interfaces:**
- Consumes: `config.isUnsandboxedPluginsEnabled()` / `config.setUnsandboxedPluginsEnabled(boolean)` from Task 1 (called via the injected `AiConfigServiceHeadless config` bean). Also `fan.summer.fengyu.security.ProcessSandbox.isNativeSandboxAvailable()` (static).
- Produces: `GET /api/settings` returns a new `unsandboxedPlugins` boolean field; `PUT /api/settings` accepts an `unsandboxedPlugins` boolean (or string), rejects enabling on sandboxed platforms with `IllegalArgumentException` → mapped to HTTP 400 by the existing `GlobalExceptionHandler`.

- [ ] **Step 1: Write the failing test**

Add this test to `SettingsControllerTest.java`. It uses the existing 5-arg test constructor `new SettingsController(config, newService(), logging, pluginProcesses, exitAction)` (see the existing `putAppliesSameLogLevelToHostAndRunningPlugins` test at lines 73-91 for the exact wiring). On the CI platform (Linux/macOS) `ProcessSandbox.isNativeSandboxAvailable()` is `true`, so enabling must throw — proving the 400 gate. Disabling is always allowed.

```java
    @Test
    void putRejectsEnablingUnsandboxedPluginsOnSandboxedPlatform() {
        // On the CI host (Linux/macOS) a native sandbox is available, so enabling must be rejected.
        // This documents + guards the platform gate; IllegalArgumentException -> HTTP 400 via
        // GlobalExceptionHandler. (The NONE-platform accept path is covered by manual verification.)
        AiConfigServiceHeadless config = mock(AiConfigServiceHeadless.class);
        SettingsController controller = new SettingsController(
            config, newService(), mock(LoggingLevelService.class), mock(PluginProcessManager.class), () -> {});

        // Enabling on a sandboxed platform throws.
        assertThrows(IllegalArgumentException.class,
            () -> controller.put(Map.of("unsandboxedPlugins", true)));

        // Disabling is always allowed (closing a protection boundary is safe everywhere) and
        // must NOT throw, even on a sandboxed platform.
        controller.put(Map.of("unsandboxedPlugins", false));
        verify(config).setUnsandboxedPluginsEnabled(false);
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertThrows;` to imports (the file already imports `verify`, `when`, `mock` from Mockito and `Map`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=SettingsControllerTest#putRejectsEnablingUnsandboxedPluginsOnSandboxedPlatform`
Expected: FAIL or COMPILE ERROR — `unsandboxedPlugins` is ignored by `put()` today, so enabling does not throw.

- [ ] **Step 3: Write minimal implementation**

In `SettingsController.java`:

Add the import for `ProcessSandbox` (after the existing `import fan.summer.fengyu.plugin.runtime.PluginProcessManager;` at line 6):
```java
import fan.summer.fengyu.security.ProcessSandbox;
```

In `get()` (lines 81-89), add this line before `return out;` (after `out.put("logLevel", ...)`):
```java
        out.put("unsandboxedPlugins", config.isUnsandboxedPluginsEnabled());
```

In `put()` (lines 91-110), add this block right before `return get();` (after the `sidebarCollapsed` block ending at line 108):
```java
        Object unsandboxed = body.get("unsandboxedPlugins");
        if (unsandboxed instanceof Boolean b) {
            applyUnsandboxedPlugins(b);
        } else if (unsandboxed instanceof String s) {
            applyUnsandboxedPlugins(Boolean.parseBoolean(s));
        }
```

Add the private helper method + a SLF4J log call. Place this method right after `put()` (after line 110, before the `resetDatabase` method's javadoc at line 112):
```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=SettingsControllerTest#putRejectsEnablingUnsandboxedPluginsOnSandboxedPlatform`
Expected: PASS.

Also run the whole `SettingsControllerTest` class to ensure no regression in the existing `putAppliesSameLogLevelToHostAndRunningPlugins` test:
Run: `./mvnw -f FengYu/pom.xml test -Dtest=SettingsControllerTest`
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/controller/SettingsController.java \
        FengYu/src/test/java/fan/summer/fengyu/web/controller/SettingsControllerTest.java
git commit -m "✨ feat(security): expose unsandboxedPlugins setting with platform gate (400 on sandboxed)"
```

---

## Task 3: Consume the toggle in `PluginProcessManager`

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java`
- Test: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java`

**Interfaces:**
- Consumes: `AiConfigServiceHeadless.isUnsandboxedPluginsEnabled()` (static, from Task 1).
- Produces: when the toggle is ON, `start()` routes through `sandbox.unrestricted(command)` on NONE-platforms too (no `IllegalStateException`), so `POST /api/plugin-runtime/{id}/invoke` returns 200 instead of 500.

**Why the test can force NONE:** the `@Autowired` constructor at lines 73-82 accepts an injected `ProcessSandbox`. The test's `manager()` helper (lines 220-243) uses the 4-arg non-Spring constructor which internally builds `new ProcessSandbox()` (calls `detect()` → a real backend on CI). For this test we build a manager via the 5-arg constructor with `new ProcessSandbox(ProcessSandbox.Backend.NONE)` to force the Windows code path, then flip the static toggle.

- [ ] **Step 1: Write the failing test**

Add this test to `PluginProcessManagerTest.java`. It mirrors the existing `invokesIsolatedJsonRpcWorker` test (lines 39-45) but builds the manager with a forced NONE backend and stubs the static toggle read via `mockStatic`. Add `import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;` and `import fan.summer.fengyu.security.ProcessSandbox;` to the imports. (Using `mockStatic` keeps these unit tests free of Spring/DB wiring and matches the `mockStatic(AiConfigServiceHeadless.class)` usage already present in `SettingsControllerTest` line 84.)

```java
    @Test
    void unsandboxedToggleLetsPluginRunUnderForcedNoneBackend() throws Exception {
        // Force the Windows code path: NONE backend means sandbox.plugin() would throw.
        // With the toggle ON, the manager must route through sandbox.unrestricted() instead.
        PluginProcessManager manager = managerWithBackend(ProcessSandbox.Backend.NONE);
        try (var mocked = org.mockito.Mockito.mockStatic(AiConfigServiceHeadless.class)) {
            mocked.when(AiConfigServiceHeadless::isUnsandboxedPluginsEnabled).thenReturn(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", result.get("value"));
        } finally {
            manager.close();
        }
    }

    @Test
    void toggleOffFailsClosedUnderForcedNoneBackend() throws Exception {
        // Same forced NONE backend, but toggle OFF: the original fail-closed behavior must hold.
        PluginProcessManager manager = managerWithBackend(ProcessSandbox.Backend.NONE);
        try (var mocked = org.mockito.Mockito.mockStatic(AiConfigServiceHeadless.class)) {
            mocked.when(AiConfigServiceHeadless::isUnsandboxedPluginsEnabled).thenReturn(false);
            var error = assertThrows(IllegalStateException.class,
                () -> manager.invoke("com.example.worker", "echo", Map.of()));
            assertTrue(error.getMessage().contains("native process sandbox"));
        } finally {
            manager.close();
        }
    }
```

Add the helper that builds a manager with an explicit backend, right after the existing `manager(List<String> permissions)` helper (after line 243). It reuses the same fixture assembly as `manager()` but calls the 5-arg constructor (the `@Autowired` one at lines 73-82) with a forced `ProcessSandbox`:

```java
    private PluginProcessManager managerWithBackend(ProcessSandbox.Backend backend) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        String manifest = """
            {"schemaVersion":1,"id":"com.example.worker","name":"Worker","description":"test",
             "version":"1.0.0","author":"test","icon":"test","category":"test",
             "ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":[]}
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command));
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins-none").toString());
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip", archive(manifest)));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host-none").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:worker-none", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "", null));
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data-none").toString());
        return new PluginProcessManager(packages, new PluginFileGrantService(), runtimeEnvironment,
            new PluginLogStore(), new ProcessSandbox(backend));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=PluginProcessManagerTest#unsandboxedToggleLetsPluginRunUnderForcedNoneBackend+toggleOffFailsClosedUnderForcedNoneBackend`
Expected: `toggleOffFailsClosedUnderForcedNoneBackend` PASSES already (current fail-closed behavior), but `unsandboxedToggleLetsPluginRunUnderForcedNoneBackend` FAILS — the toggle is not yet consumed, so even with the stub returning `true`, `start()` still calls `sandbox.plugin(...)` and throws `IllegalStateException`.

- [ ] **Step 3: Write minimal implementation**

In `PluginProcessManager.java`:

Add the import for `AiConfigServiceHeadless` (after the existing imports near the top; find the import block and add):
```java
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
```

Change line 105 from:
```java
        boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS;
```
to:
```java
        boolean fullAccess = AiPermissionContext.current() == AiPermissionMode.FULL_ACCESS
                || AiConfigServiceHeadless.isUnsandboxedPluginsEnabled();
```

Optionally, to make the audit log explicit when the override is active, change the `isolation` string at lines 238-240 from:
```java
            String isolation = "sandbox=" + launch.backend().id()
                    + ", network=" + (fullAccess || allowNetwork ? "allowed" : "isolated")
                    + ", broadFileWrite=" + broadFileWrite;
```
to:
```java
            String isolation = "sandbox=" + launch.backend().id()
                    + ", network=" + (fullAccess || allowNetwork ? "allowed" : "isolated")
                    + ", broadFileWrite=" + broadFileWrite
                    + (AiConfigServiceHeadless.isUnsandboxedPluginsEnabled() ? ", unsandboxedOverride=true" : "");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=PluginProcessManagerTest#unsandboxedToggleLetsPluginRunUnderForcedNoneBackend+toggleOffFailsClosedUnderForcedNoneBackend`
Expected: both tests PASS.

Run the full `PluginProcessManagerTest` class to ensure no regression in the existing tests (which use the 4-arg constructor with a real backend):
Run: `./mvnw -f FengYu/pom.xml test -Dtest=PluginProcessManagerTest`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java
git commit -m "✨ feat(security): consume plugin.unsandboxed toggle in PluginProcessManager (OR with FULL_ACCESS)"
```

---

## Task 4: Verify `ProcessSandbox` fail-closed contract is unchanged

**Files:**
- Test: `FengYu/src/test/java/fan/summer/fengyu/security/ProcessSandboxTest.java` (no modification — just run it to prove the safety primitive was not weakened)

**Interfaces:**
- Consumes: nothing new. This is a regression guard.

- [ ] **Step 1: Run the existing fail-closed test**

Run: `./mvnw -f FengYu/pom.xml test -Dtest=ProcessSandboxTest`
Expected: all 5 tests PASS, including `pluginWorkerFailsClosedWithoutNativeSandbox` (lines 53-58). This proves `ProcessSandbox.plugin()` still throws on `Backend.NONE` and the safety primitive was not modified.

- [ ] **Step 2: Full backend module build (compile check across all changes)**

Run: `./mvnw clean package -f FengYu/pom.xml -DskipTests`
Expected: BUILD SUCCESS. (Confirms Tasks 1-3 compile together and no wiring is broken.)

- [ ] **Step 3: No commit (no code changed in this task)**

This task is a verification gate, not a code change. If it fails, STOP and investigate — the design requires `ProcessSandbox` to remain unchanged.

---

## Task 5: Frontend types + Pinia store

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/stores/settings.ts`

**Interfaces:**
- Consumes: existing `api.getSettings()` / `api.putSettings(partial)` (already generic, no change needed in `client.ts`).
- Produces: `AppSettings.unsandboxedPlugins: boolean` type, and `settings.unsandboxedPlugins` ref + `settings.setUnsandboxedPlugins(b)` action consumed by Task 7.

- [ ] **Step 1: Add the type field**

In `frontend/src/api/types.ts`, edit the `AppSettings` interface (lines 75-80) to add the new field:
```ts
export interface AppSettings {
  sidebarCollapsed: boolean
  theme: ThemeName
  language: LanguageName
  logLevel: LogLevel
  unsandboxedPlugins: boolean
}
```

- [ ] **Step 2: Add the store ref + setter**

In `frontend/src/stores/settings.ts`:

Add a ref next to `logLevel` (after line 13):
```ts
  const unsandboxedPlugins = ref(false)
```

In `apply(s)` (after line 29 `logLevel.value = s.logLevel ?? 'INFO'`), add:
```ts
    unsandboxedPlugins.value = s.unsandboxedPlugins ?? false
```

Add an action after `setLogLevel` (after line 72), mirroring `setSidebarCollapsed`:
```ts
  async function setUnsandboxedPlugins(enabled: boolean) {
    unsandboxedPlugins.value = enabled
    await update({ unsandboxedPlugins: enabled })
  }
```

Expose them in the returned store object (the `return { ... }` at lines 91-108). Add inside the return:
```ts
    unsandboxedPlugins,
    setUnsandboxedPlugins,
```

- [ ] **Step 3: Type-check**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: no errors. (Confirms the type flows through `apply`/`update`/the store return.)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api/types.ts frontend/src/stores/settings.ts
git commit -m "✨ feat(settings): add unsandboxedPlugins to AppSettings type + Pinia store"
```

---

## Task 6: i18n keys (EN + ZH, mirrored)

**Files:**
- Modify: `frontend/src/i18n/en.json`
- Modify: `frontend/src/i18n/zh.json`

**Interfaces:**
- Produces: these i18n keys, used by Task 7:
  - `settings.unsandboxedPluginsTitle`
  - `settings.unsandboxedOn`
  - `settings.unsandboxedOff`
  - `settings.unsandboxedPluginsWarn`
  - `settings.unsandboxedPluginsConfirm`

- [ ] **Step 1: Add EN keys**

In `frontend/src/i18n/en.json`, inside the `"settings": { ... }` object (lines 15-22), add these keys after the `"chinese": "中文" }` line (before the closing brace of the settings object). The settings object currently ends with `"english": "English", "chinese": "中文" }`. Replace that line with:
```json
                "english": "English", "chinese": "中文",
                "unsandboxedPluginsTitle": "Unsandboxed plugins",
                "unsandboxedOff": "Off",
                "unsandboxedOn": "Allow",
                "unsandboxedPluginsWarn": "Plugins will run without process isolation. Only enable if you trust all installed plugins.",
                "unsandboxedPluginsConfirm": "Disable plugin process isolation? Plugin workers will run with the same privileges as the app, with no sandbox boundary." },
```

- [ ] **Step 2: Add ZH keys (mirrored, same key paths)**

In `frontend/src/i18n/zh.json`, inside the `"settings": { ... }` object (lines 15-22), replace the closing `"english": "English", "chinese": "中文" }` line with:
```json
                "english": "English", "chinese": "中文",
                "unsandboxedPluginsTitle": "无沙箱运行插件",
                "unsandboxedOff": "关闭",
                "unsandboxedOn": "允许",
                "unsandboxedPluginsWarn": "插件将以无进程隔离方式运行。仅在你信任所有已安装插件时启用。",
                "unsandboxedPluginsConfirm": "禁用插件进程隔离？插件 Worker 将以与应用相同的权限运行，无沙箱边界保护。" },
```

- [ ] **Step 3: Verify both files are valid JSON**

Run: `cd frontend && node -e "JSON.parse(require('fs').readFileSync('src/i18n/en.json','utf8')); JSON.parse(require('fs').readFileSync('src/i18n/zh.json','utf8')); console.log('both valid')"`
Expected: prints `both valid`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "📝 i18n(settings): add unsandboxed-plugins keys (EN + ZH mirrored)"
```

---

## Task 7: Settings.vue — toggle row + confirmation dialog

**Files:**
- Modify: `frontend/src/views/Settings.vue`

**Interfaces:**
- Consumes: `settings.unsandboxedPlugins` (ref) + `settings.setUnsandboxedPlugins(b)` (action) from Task 5; `isolationStatus.compatibilityMode` (already loaded at line 26-28) as the visibility gate; i18n keys from Task 6.
- Produces: the UI control that, on a NONE platform (Windows), lets the user opt into running plugins unsandboxed. The `.cx-segment` button-group style mirrors the existing theme/language controls (lines 162-184).

- [ ] **Step 1: Add the reactive state for the confirm dialog**

In the `<script setup lang="ts">` block, near the other `ref` declarations (after line 20 `const isolationStatus = ref<ProcessIsolationStatus | null>(null)`), add:
```ts
const showUnsandboxedConfirm = ref(false)
```

Add the handler functions after the existing `onTest`/`onSave` functions (place them after `onSave`, before the end of script setup). The enable path opens the dialog; confirmation performs the write; disable writes directly (no confirm):
```ts
function requestEnableUnsandboxed() {
  if (settings.unsandboxedPlugins) return
  showUnsandboxedConfirm.value = true
}

async function confirmEnableUnsandboxed() {
  showUnsandboxedConfirm.value = false
  await settings.setUnsandboxedPlugins(true)
}
```

- [ ] **Step 2: Add the toggle row to the template**

In the template, inside the "Runtime & security" `.cx-card` (lines 188-216), insert this new row + warning AFTER the existing "Process isolation" row (after line 203, before the MCP row at line 204). The `v-if="isolationStatus?.compatibilityMode"` gate renders it only on NONE platforms:

```html
        <div v-if="isolationStatus?.compatibilityMode" class="cx-setting-row">
          <div class="cx-setting-row__label">
            <i class="mdi mdi-shield-alert-outline" />
            <span>{{ $t('settings.unsandboxedPluginsTitle') }}</span>
          </div>
          <div class="cx-segment">
            <button
              :class="{ active: !settings.unsandboxedPlugins }"
              @click="settings.setUnsandboxedPlugins(false)"
            >{{ $t('settings.unsandboxedOff') }}</button>
            <button
              :class="{ active: settings.unsandboxedPlugins }"
              @click="requestEnableUnsandboxed()"
            >{{ $t('settings.unsandboxedOn') }}</button>
          </div>
        </div>
        <div
          v-if="isolationStatus?.compatibilityMode"
          class="cx-muted"
          style="color: var(--md-sys-color-error); font-size: 12px; margin-top: -8px;"
        >
          {{ $t('settings.unsandboxedPluginsWarn') }}
        </div>
```

- [ ] **Step 3: Add the confirmation dialog**

Vuetify is already a project dependency. Add the dialog at the end of the template (just before the closing `</template>` tag, or adjacent to other dialogs if present). Use Vuetify's `v-dialog`:

```html
    <v-dialog v-model="showUnsandboxedConfirm" max-width="480">
      <v-card>
        <v-card-text>{{ $t('settings.unsandboxedPluginsConfirm') }}</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="showUnsandboxedConfirm = false">{{ $t('common.cancel') }}</v-btn>
          <v-btn color="error" variant="tonal" @click="confirmEnableUnsandboxed()">{{ $t('common.confirm') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
```

> **Check `common.cancel` / `common.confirm` exist:** run `grep -n '"cancel"\|"confirm"' frontend/src/i18n/en.json`. If they do not exist, add `"common": { "cancel": "Cancel", "confirm": "Confirm" }` to `en.json` and the mirrored `"common": { "cancel": "取消", "confirm": "确认" }` to `zh.json` (top-level keys, alongside `"settings"`). Re-run the JSON-validity check from Task 6 Step 3.

- [ ] **Step 4: Type-check + build**

Run: `cd frontend && npm run build`
Expected: build succeeds (vue-tsc + vite). If `common.cancel`/`common.confirm` are missing, the build still succeeds (vue-i18n resolves missing keys to the key string at runtime), but add them per the note above for correct display.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/Settings.vue frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "✨ feat(settings): Windows unsandboxed-plugins toggle row with confirmation dialog"
```

---

## Task 8: Final end-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full backend test suite for the touched modules**

Run:
```bash
./mvnw -f FengYu/pom.xml test -Dtest='AiConfigServiceHeadlessTest,SettingsControllerTest,PluginProcessManagerTest,ProcessSandboxTest'
```
Expected: ALL tests PASS. This is the combined proof for Tasks 1-4.

- [ ] **Step 2: Backend full build**

Run: `./mvnw clean package -f FengYu/pom.xml -DskipTests`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Frontend full build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Smoke the disclosure endpoint (optional, requires running backend)**

If a backend is running (loopback, port 24056, auth disabled for local dev):
```bash
curl -s http://127.0.0.1:24056/api/security/process-isolation | head
curl -s http://127.0.0.1:24056/api/settings | head
```
Expected: `process-isolation` returns `compatibilityMode` true/false per the host; `settings` now includes `unsandboxedPlugins`. On a sandboxed host, `curl -X PUT .../api/settings -d '{"unsandboxedPlugins":true}'` returns HTTP 400.

- [ ] **Step 5: Manual verification checklist (Windows / NONE-platform)**

Per the spec section 6.4, on a Windows (or otherwise no-bwrap/no-sandbox-exec) host:
- [ ] `GET /api/security/process-isolation` returns `compatibilityMode: true`.
- [ ] Settings "Runtime & security" section shows the new toggle row.
- [ ] Clicking "Allow" opens the confirmation dialog; confirming lets a plugin invoke succeed (`POST /api/plugin-runtime/{id}/invoke` returns 200, not 500).
- [ ] Backend log shows `Plugin unsandboxed mode ENABLED (platform: none)`.
- [ ] Restart the backend; the toggle stays "Allow" (persistence works).
- [ ] On Linux/macOS the row does not render; PUT `{unsandboxedPlugins:true}` returns 400.

- [ ] **Step 6: No commit unless something needed fixing**

If all green, the implementation is complete. Report results to the user.
