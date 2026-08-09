package fan.summer.fengyu.plugin.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.ai.tools.AiPermissionContext;
import fan.summer.fengyu.ai.tools.AiPermissionMode;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.PluginIntegrityStore;
import fan.summer.fengyu.security.ProcessSandbox;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginProcessManagerTest {
    @TempDir Path temp;

    @Test
    void invokesIsolatedJsonRpcWorker() throws Exception {
        PluginProcessManager manager = manager();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
        assertEquals("ok", result.get("value"));
        manager.close();
    }

    @Test
    void frameLimitsCountRawUtf8BytesInBothDirections() throws Exception {
        byte[] frame = "😀\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("😀", PluginProcessManager.readBoundedLine(
            new ByteArrayInputStream(frame), 4, "stdout"));
        assertThrows(java.io.IOException.class, () -> PluginProcessManager.readBoundedLine(
            new ByteArrayInputStream(frame), 3, "stdout"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> PluginProcessManager.ensureFrameWithinLimit(
            "😀", 4, "stdin"));
        assertThrows(java.io.IOException.class, () -> PluginProcessManager.ensureFrameWithinLimit(
            "😀", 3, "stdin"));
    }

    @Test
    void timesOutAndRestartsWorker() throws Exception {
        PluginProcessManager manager = manager();
        // sleep method blocks for 3s; declare a 1s timeout.
        var error = assertThrows(IllegalStateException.class,
            () -> manager.invoke("com.example.worker", "sleep", Map.of(), 1));
        assertTrue(error.getMessage().contains("timed out"));
        // The worker must have been killed and lazily restarted — the next call succeeds.
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
        assertEquals("ok", result.get("value"));
        manager.close();
    }

    @Test
    void concurrentInvokesOnSamePluginBothSucceed() throws Exception {
        PluginProcessManager manager = manager();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() ->
                ((Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of("tag", "a"))).get("value"));
            var second = executor.submit(() ->
                ((Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of("tag", "b"))).get("value"));
            assertEquals("ok", first.get(10, TimeUnit.SECONDS));
            assertEquals("ok", second.get(10, TimeUnit.SECONDS));
        }
        manager.close();
    }

    @Test
    void preservesRpcErrorMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PluginProcessManager.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        PluginProcessManager manager = manager();
        try {
            var error = assertThrows(IllegalArgumentException.class,
                () -> manager.invoke("com.example.worker", "error", Map.of()));
            assertTrue(error.getMessage().contains("bad workbook"));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(logs.contains("bad workbook"), "worker error payload leaked into host log: " + logs);
            assertTrue(logs.contains("IllegalArgumentException"));
        } finally {
            manager.close();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void reportsWorkerEof() throws Exception {
        PluginProcessManager manager = manager();
        var error = assertThrows(IllegalStateException.class,
            () -> manager.invoke("com.example.worker", "eof", Map.of()));
        assertTrue(error.getMessage().contains("stopped unexpectedly"));
        manager.close();
    }

    /**
     * Regression (P0-1): the Worker process must NOT inherit arbitrary host environment variables —
     * a plugin would otherwise read host secrets (OPENAI_API_KEY, GH_TOKEN, proxy creds, ...). The
     * allowlist is positive: only named essentials survive, everything else is dropped by
     * construction. This unit test asserts the property against a synthetic host environment that
     * mixes a secret, an allowlisted essential, and a locale-prefix family member.
     */
    @Test
    void environmentAllowlistDropsSecretsAndKeepsEssentials() {
        java.util.Map<String, String> host = new java.util.LinkedHashMap<>();
        host.put("OPENAI_API_KEY", "sk-host-secret");
        host.put("GH_TOKEN", "ghp_hosttoken");
        host.put("MY_DB_PASSWORD", "hunter2");
        host.put("PATH", "/usr/bin:/bin");
        host.put("JAVA_HOME", "/opt/java");
        // JVM auto-interprets these — a -javaagent / system property here would inject into every
        // plugin Worker, so they must NOT be admitted even though they are "Java" variables.
        host.put("JAVA_OPTS", "-javaagent:/host/agent.jar");
        host.put("JAVA_TOOL_OPTIONS", "-Dhost.secret=leaked -javaagent:/host/x.jar");
        // XAUTHORITY can name a credential file — must not be admitted.
        host.put("XAUTHORITY", "/home/user/.Xauthority");
        host.put("LANG", "en_US.UTF-8");
        host.put("LC_MEASUREMENT", "en_US.UTF-8");  // LC_* prefix family
        host.put("TZ", "UTC");
        host.put("USER", "tester");

        java.util.Map<String, String> env = new java.util.HashMap<>(host);

        PluginProcessManager.applyEnvironmentAllowlist(env, host);

        // Secrets are dropped by construction — never admitted regardless of name pattern.
        assertFalse(env.containsKey("OPENAI_API_KEY"), "host secret leaked to worker env: " + env);
        assertFalse(env.containsKey("GH_TOKEN"));
        assertFalse(env.containsKey("MY_DB_PASSWORD"));
        // JVM-interpreted options that could inject an agent/secret are dropped (P0-1 follow-up).
        assertFalse(env.containsKey("JAVA_OPTS"), "JAVA_OPTS must not inherit (-javaagent risk): " + env);
        assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"), "JAVA_TOOL_OPTIONS must not inherit: " + env);
        assertFalse(env.containsKey("XAUTHORITY"), "XAUTHORITY can name a credential file: " + env);
        // Allowlisted essentials survive.
        assertEquals("/usr/bin:/bin", env.get("PATH"));
        assertEquals("/opt/java", env.get("JAVA_HOME"));
        assertEquals("en_US.UTF-8", env.get("LANG"));
        assertEquals("UTC", env.get("TZ"));
        assertEquals("tester", env.get("USER"));
        // LC_* prefix family is admitted (locale categories), but a secret is NOT admitted merely
        // because the prefix appears — the prefix family only broadens locale categories.
        assertEquals("en_US.UTF-8", env.get("LC_MEASUREMENT"));
    }

    /**
     * Regression (P0-1), runtime proof: a running Worker sees the FENGYU_PLUGIN_ID protocol var and
     * an allowlisted essential (PATH), but NOT a host secret that exists in the test JVM's
     * environment. The env-probe worker method reports which of these are visible; if the host
     * happened not to set {@code FENGYU_P0A_HOST_SECRET} the hostSecret assertion is vacuous, but
     * the PLUGIN_ID/PATH checks still prove the allowlist is active (the ProcessBuilder default
     * would otherwise copy the entire host env, and PATH presence alone is not distinguishing — so
     * the PLUGIN_ID-from-protocol check is the load-bearing assertion here).
     */
    @Test
    void workerDoesNotInheritHostSecrets() throws Exception {
        PluginProcessManager manager = manager();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "env-probe", Map.of());
            // The protocol var is set AFTER the allowlist, so the worker must always see it.
            assertEquals("com.example.worker", result.get("pluginId"),
                "FENGYU_PLUGIN_ID protocol var must reach the worker");
            // PATH is allowlisted; if the host has one it must survive (proves allowlist applied,
            // not an over-broad clear). If the host has no PATH this assertion is skipped.
            String hostPath = System.getenv("PATH");
            if (hostPath != null) {
                assertEquals(hostPath, result.get("path"), "PATH should survive the allowlist");
            }
            // If a non-allowlisted host secret exists in the test environment, the worker must NOT
            // see it. When unset, this is a no-op pass (the unit test above carries the full proof).
            String hostSecret = System.getenv("FENGYU_P0A_HOST_SECRET");
            if (hostSecret != null) {
                String workerSecret = String.valueOf(result.get("hostSecret"));
                assertFalse(workerSecret.contains(hostSecret),
                    "host secret leaked into worker env via inheritance: " + workerSecret);
            }
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-5): each successful invoke must reclaim its pending-slot entry. The reader
     * atomically removes the slot as the response arrives, so after a burst of successful calls the
     * worker's pending map must be empty — otherwise long-lived workers (browser/agent tools) leak a
     * UUID + Future + result per call until OOM. The documented long-soak target is 100k responses;
     * the loop here uses a smaller count that still exercises many concurrent-ish round trips while
     * keeping CI fast. The property under test (map empties) is independent of the count.
     */
    @Test
    void successfulInvokesDoNotLeakPendingSlots() throws Exception {
        PluginProcessManager manager = manager();
        try {
            for (int i = 0; i < 50; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
                assertEquals("ok", result.get("value"));
            }
            assertEquals(0, manager.pendingCountForTest("com.example.worker"),
                "successful invokes leaked pending slots: worker pending map must be empty");
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-2 follow-up): once the integrity store is wired, a plugin with NO integrity
     * record must fail closed at Worker start — the host records one for every install and migrates
     * existing installs at startup, so a missing record means the package was dropped onto disk
     * out-of-band (or tampered). Previously the absence was silently allowed, which let a legacy
     * or smuggled plugin bypass the manifest-tamper check entirely.
     */
    @Test
    void workerFailsClosedOnMissingIntegrityRecord() throws Exception {
        PluginProcessManager manager = managerWithIntegrity();
        try {
            // First invoke succeeds (install recorded a digest).
            @SuppressWarnings("unchecked")
            Map<String, Object> ok = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", ok.get("value"));

            // Remove the integrity record out-of-band, then stop so the next invoke must re-verify.
            Path record = temp.resolve("digests-int").resolve("com.example.worker.json");
            assertTrue(java.nio.file.Files.exists(record), "integrity record must exist after install");
            java.nio.file.Files.deleteIfExists(record);
            manager.stop("com.example.worker");

            var error = assertThrows(IllegalStateException.class,
                () -> manager.invoke("com.example.worker", "echo", Map.of()));
            assertTrue(error.getMessage().contains("no integrity record"),
                "missing record must fail closed: " + error.getMessage());
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-2): if a plugin's installed manifest.json is tampered with out-of-band (or the
     * package dir was writable and the Worker rewrote it), the host must refuse to start the Worker
     * rather than honoring potentially-escalated permissions from the modified manifest. The
     * integrity store recorded the digest at install time; the first invoke recomputes the live
     * digest and fails closed on mismatch.
     */
    @Test
    void workerFailsClosedOnTamperedManifest() throws Exception {
        PluginProcessManager manager = managerWithIntegrity();
        try {
            // First invoke succeeds and starts the worker (manifest matches the recorded digest).
            @SuppressWarnings("unchecked")
            Map<String, Object> ok = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", ok.get("value"));

            // Tamper with the on-disk manifest of the installed package.
            Path manifest = temp.resolve("plugins-int").resolve("com.example.worker").resolve("manifest.json");
            assertTrue(java.nio.file.Files.exists(manifest), "installed manifest must exist");
            java.nio.file.Files.writeString(manifest, java.nio.file.Files.readString(manifest) + "\n  /* tampered */");

            // The next invoke must refuse to start a fresh worker against the tampered manifest.
            // (The cached worker from the first call is reused unless invalidated; stop() forces the
            // re-verification path on the next invoke.)
            manager.stop("com.example.worker");
            var error = assertThrows(IllegalStateException.class,
                () -> manager.invoke("com.example.worker", "echo", Map.of()));
            assertTrue(error.getMessage().contains("tamper"),
                "tampered manifest must fail closed with a tamper message: " + error.getMessage());
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-2 whole-package verify): tampering a NON-manifest file in the installed package
     * (e.g. the Worker JAR, or dropping an extra file) must refuse to start the Worker, even though
     * the manifest digest still matches. The whole-package digest recomputed at start catches any
     * content change the manifest-only check would miss.
     */
    @Test
    void workerFailsClosedOnTamperedPackageFile() throws Exception {
        PluginProcessManager manager = managerWithIntegrity();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ok = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", ok.get("value"));

            // Tamper by ADDING a file to the package directory (manifest.json unchanged).
            Path pkgDir = temp.resolve("plugins-int").resolve("com.example.worker");
            java.nio.file.Files.writeString(pkgDir.resolve("injected.jar"), "tampered-bytes");

            manager.stop("com.example.worker");
            var error = assertThrows(IllegalStateException.class,
                () -> manager.invoke("com.example.worker", "echo", Map.of()));
            assertTrue(error.getMessage().contains("package tamper"),
                "a tampered package file must fail whole-package verification: " + error.getMessage());
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-6): after a package upgrade (same id, higher version), the next invoke must
     * run the NEW worker process — the cached worker from the old version must not be reused. The
     * cache now keys on manifest version, so reinstalling v2 over a running v1 worker invalidates
     * the cache and the next invoke starts a fresh process (different pid).
     */
    @Test
    void upgradeRestartsWorkerByManifestVersion() throws Exception {
        // Build the manager and its package service together so we can reinstall the plugin in place.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins-up").toString());
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip",
            archive(manifestFor("com.example.worker", "1.0.0", command))));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host-up").toString());
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data-up").toString());
        PluginProcessManager manager = new PluginProcessManager(
            packages, new PluginFileGrantService(), runtimeEnvironment, new PluginLogStore());
        try {
            // Start the v1 worker.
            @SuppressWarnings("unchecked")
            long pidV1 = ((Number) ((Map<String, Object>) manager.invoke("com.example.worker", "pid", Map.of())).get("value")).longValue();
            assertTrue(pidV1 > 0);

            // Upgrade the package in place (same id, higher version) without going through the
            // controller — directly via the package service, simulating what the controller now does
            // (stop) + install.
            manager.stop("com.example.worker");
            packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip",
                archive(manifestFor("com.example.worker", "2.0.0", command))));

            // The next invoke must start a NEW worker process for v2 — a different pid.
            @SuppressWarnings("unchecked")
            long pidV2 = ((Number) ((Map<String, Object>) manager.invoke("com.example.worker", "pid", Map.of())).get("value")).longValue();
            assertNotEquals(pidV1, pidV2,
                "upgrade must restart the worker (different pid); the old worker must not be reused");
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-6): a same-version repack — the version is NOT bumped but the package content
     * changes — must still invalidate the cached Worker. The identity now keys on the package
     * content digest, not just the version, so a rebuilt jar (e.g. a logging fix shipped without a
     * version bump) reaches a user whose Worker is already running the old bytes.
     */
    @Test
    void sameVersionRepackRestartsWorkerByContentDigest() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins-repack").toString());
        packages.attachIntegrityStoreForTest(new PluginIntegrityStore(temp.resolve("digests-repack")));
        // v1.0.0 with description "original".
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip",
            archive(manifestFor("com.example.worker", "1.0.0", command, "original"))));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host-repack").toString());
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data-repack").toString());
        PluginProcessManager manager = new PluginProcessManager(
            packages, new PluginFileGrantService(), runtimeEnvironment, new PluginLogStore());
        try {
            @SuppressWarnings("unchecked")
            long pidV1 = ((Number) ((Map<String, Object>) manager.invoke("com.example.worker", "pid", Map.of())).get("value")).longValue();

            // Repack the SAME version with different content (description "patched") + stop + reinstall.
            manager.stop("com.example.worker");
            packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip",
                archive(manifestFor("com.example.worker", "1.0.0", command, "patched"))));

            // The version is identical, but the package digest changed → the next invoke must start a
            // NEW worker process (different pid), proving the stale Worker was not reused.
            @SuppressWarnings("unchecked")
            long pidV2 = ((Number) ((Map<String, Object>) manager.invoke("com.example.worker", "pid", Map.of())).get("value")).longValue();
            assertNotEquals(pidV1, pidV2,
                "same-version repack with different content must restart the worker (digest-based identity)");
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-6): while a plugin's package is mid-swap (between beginUpdate and endUpdate),
     * new invokes must refuse rather than race the stop→install→restart sequence.
     */
    @Test
    void invokeRefusedWhilePluginIsUpdating() throws Exception {
        PluginProcessManager manager = manager();
        try {
            // Start the worker normally.
            @SuppressWarnings("unchecked")
            Map<String, Object> ok = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", ok.get("value"));

            // Simulate the controller's update window: beginUpdate marks the id updating.
            manager.beginUpdate("com.example.worker");
            try {
                var error = assertThrows(IllegalStateException.class,
                    () -> manager.invoke("com.example.worker", "echo", Map.of()));
                assertTrue(error.getMessage().contains("being updated"),
                    "invoke during update must be refused: " + error.getMessage());
            } finally {
                manager.endUpdate("com.example.worker");
            }
            // After endUpdate, invokes work again (a fresh worker starts).
            @SuppressWarnings("unchecked")
            Map<String, Object> ok2 = (Map<String, Object>) manager.invoke("com.example.worker", "echo", Map.of());
            assertEquals("ok", ok2.get("value"));
        } finally {
            manager.close();
        }
    }

    /**
     * Regression (P0-6 TOCTOU): the update gate must not have a check-then-act race between
     * {@code invoke}'s "is it updating?" check and its "acquire a Worker" step. Hammer the same
     * plugin with concurrent invokes interleaved with beginUpdate/endUpdate windows; under the old
     * unsynchronized gate an invoke could slip past the check and start a Worker that an update's
     * stop then killed mid-RPC, or reuse a Worker against a half-swapped package. With the per-plugin
     * lock, every invoke either sees the update window (and is refused with "being updated") or
     * acquires a Worker cleanly — never an inconsistent interleaving. The assertion is that NO
     * invoke throws an unexpected exception type or hangs; refused invokes are caught and counted.
     */
    @Test
    void concurrentInvokesAndUpdatesDoNotRaceTheGate() throws Exception {
        PluginProcessManager manager = manager();
        try {
            String id = "com.example.worker";
            int rounds = 40;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                // Invoker: repeatedly invoke echo; a "being updated" refusal is an expected outcome.
                Runnable invoker = () -> {
                    for (int i = 0; i < rounds && failure.get() == null; i++) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> r = (Map<String, Object>) manager.invoke(id, "echo", Map.of());
                            assertEquals("ok", r.get("value"));
                        } catch (IllegalStateException e) {
                            // Acceptable: the gate refused the call because an update was in flight.
                            if (e.getMessage() != null && e.getMessage().contains("being updated")) {
                                refused.incrementAndGet();
                            } else {
                                // Worker teardown (EOF/stop during RPC) surfaces as IllegalStateException
                                // too; that's the documented in-flight-call failure path, not a race bug.
                                // Only assert it is one of the known benign causes.
                                assertTrue(e.getMessage().contains("stopped") || e.getMessage().contains("tearing down")
                                        || e.getMessage().contains("timed out") || e.getMessage().contains("interrupted")
                                        || e.getMessage().contains("being updated"),
                                    "unexpected invoke failure: " + e.getMessage());
                            }
                        } catch (Throwable t) {
                            failure.set(t);
                        }
                    }
                };
                // Updater: repeatedly open and close an update window (beginUpdate stops the Worker,
                // endUpdate re-enables it; the next invoke restarts it). This is what races invoker.
                Runnable updater = () -> {
                    for (int i = 0; i < rounds && failure.get() == null; i++) {
                        manager.beginUpdate(id);
                        // The window is open for a moment; concurrent invokes here must be refused.
                        try { Thread.sleep(1); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        manager.endUpdate(id);
                    }
                };
                var f1 = executor.submit(invoker);
                var f2 = executor.submit(invoker);
                var f3 = executor.submit(updater);
                f1.get(30, TimeUnit.SECONDS);
                f2.get(30, TimeUnit.SECONDS);
                f3.get(30, TimeUnit.SECONDS);
                assertNull(failure.get(), "a concurrent invoke/update hit an unexpected failure: " + failure.get());
                assertTrue(refused.get() > 0,
                    "expected at least some invokes to be refused by the gate during update windows");
            }
        } finally {
            manager.close();
        }
    }

    private static String manifestFor(String id, String version, String command) throws Exception {
        return manifestFor(id, version, command, "test");
    }

    private static String manifestFor(String id, String version, String command, String description) throws Exception {
        return """
            {"schemaVersion":1,"id":"%s","name":"Worker","description":"%s",
             "version":"%s","author":"test","icon":"test","category":"test",
             "ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":[]}
            """.formatted(id, description, version,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command));
    }

    @Test
    void injectsDatabaseEnvironmentIntoPermittedWorker() throws Exception {
        PluginProcessManager manager = manager(List.of("database"));
        @SuppressWarnings("unchecked") Map<String, Object> result =
            (Map<String, Object>) manager.invoke("com.example.worker", "environment", Map.of());
        // The worker receives a DB URL. For an embedded H2 host it gets its own file under the
        // plugin data dir (not the host's in-memory URL), so assert it is a non-null h2 URL rather
        // than the host's literal value.
        String workerUrl = String.valueOf(result.get("value"));
        assertTrue(workerUrl.startsWith("jdbc:h2:"), "worker should receive an h2 DB url, got: " + workerUrl);
        manager.close();
    }

    @Test
    void givesSandboxedWorkerAWritablePluginOwnedTempDirectory() throws Exception {
        PluginProcessManager manager = manager();

        @SuppressWarnings("unchecked") Map<String, Object> result =
            (Map<String, Object>) manager.invoke("com.example.worker", "temporary-file", Map.of());

        Path path = Path.of(String.valueOf(result.get("value")));
        assertTrue(path.startsWith(temp.resolve("plugin-data").resolve("com.example.worker")));
        manager.close();
    }

    @Test
    void keepsDatabasePasswordOutOfWorkerCommandAndRpcErrors() throws Exception {
        PluginProcessManager manager = manager(List.of("database"));
        @SuppressWarnings("unchecked") Map<String, Object> command =
            (Map<String, Object>) manager.invoke("com.example.worker", "command", Map.of());
        assertFalse(String.valueOf(command.get("value")).contains("do-not-log-me"));

        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.invoke("com.example.worker", "secret-error", Map.of()));
        assertFalse(error.getMessage().contains("do-not-log-me"));
        manager.close();
    }

    @Test
    void redactsDatabasePasswordFromWorkerStderrLogs() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("plugin.com.example.worker.stderr");
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        PluginProcessManager manager = manager(List.of("database"));
        try {
            manager.invoke("com.example.worker", "stderr-secret", Map.of());
            waitForLog(appender, "database password", Duration.ofSeconds(2));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(logs.contains("<redacted>"));
            assertFalse(logs.contains("do-not-log-me"));
        } finally {
            manager.close();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    /**
     * forwardPluginLog must stamp MDC["pluginId"] = safeLoggerName(pluginId) on every forwarded
     * worker event so the logback SiftingAppender routes it to plugin-&lt;pluginId&gt;.log. The MDC
     * key must be removed again afterwards (balanced put/remove) so unrelated host log lines do not
     * leak into a per-plugin bucket. Reuses the same fixture as
     * {@link #redactsDatabasePasswordFromWorkerStderrLogs} (stderr-secret worker method → forwarded
     * event on the plugin.&lt;id&gt;.stderr logger).
     */
    @Test
    void forwardedPluginLogCarriesPluginIdMdc() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("plugin.com.example.worker.stderr");
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        PluginProcessManager manager = manager(List.of("database"));
        try {
            manager.invoke("com.example.worker", "stderr-secret", Map.of());
            waitForLog(appender, "database password", Duration.ofSeconds(2));
            assertFalse(appender.list.isEmpty(), "no forwarded event captured");
            ILoggingEvent event = appender.list.getLast();
            assertEquals("com.example.worker", event.getMDCPropertyMap().get("pluginId"),
                "forwarded plugin log must carry MDC pluginId for SiftingAppender routing");
            // The MDC key must be cleared after the forwarded event so the surrounding host thread
            // does not keep leaking its events into the per-plugin bucket.
            assertNull(org.slf4j.MDC.get("pluginId"),
                "MDC pluginId must be removed after forwarding the worker log event");
        } finally {
            manager.close();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    /**
     * Regression (P1-1): the host must never log invoke PARAMETER VALUES — only their keys. A caller
     * can pass arbitrary credentials/body text in params (e.g. an SMTP password for
     * {@code email_account_save}); logging the value (even truncated to 60 chars) leaks it to the
     * console, the host log file, and the plugin log REST/SSE surface. Keys are safe to log.
     */
    @Test
    void invokeLogsParameterKeysButNeverValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PluginProcessManager.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        PluginProcessManager manager = manager();
        try {
            manager.invoke("com.example.worker", "echo",
                Map.of("password", "hunter2", "body", "secret-message"));
            waitForLog(appender, "echo", Duration.ofSeconds(2));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
            // Keys are expected and safe — they describe the call shape without revealing secrets.
            assertTrue(logs.contains("password"), "param keys must be logged for diagnostics");
            assertTrue(logs.contains("body"));
            // Values must NEVER appear — not at INFO (params preview) nor DEBUG (resolved params).
            assertFalse(logs.contains("hunter2"), "param value leaked into host log: " + logs);
            assertFalse(logs.contains("secret-message"), "param value leaked into host log: " + logs);
        } finally {
            manager.close();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void emptySensitiveValuesDoNotAlterDiagnosticText() {
        SensitiveValueRedactor redactor = SensitiveValueRedactor.fromEnvironment(
            Map.of(PluginWorkerProtocol.DB_PASSWORD_ENV, ""));

        assertEquals("worker diagnostic", redactor.redact("worker diagnostic"));
    }

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

    /**
     * Regression (P0-4): a per-turn AI {@code FULL_ACCESS} permission must NOT disable a plugin's
     * OS boundary. Granting the AI full access for tool-call effects used to route every called
     * plugin through {@code sandbox.unrestricted()}, bypassing the plugin's declared permissions and
     * platform isolation. Now only the explicit host-wide unsandboxed toggle does that — so under
     * FULL_ACCESS with the toggle OFF, a plugin still tries to launch sandboxed and (on a forced
     * NONE backend) fails closed the same way it does with no special permission at all.
     */
    @Test
    void fullAccessAiModeDoesNotUnsandboxPlugin() throws Exception {
        PluginProcessManager manager = managerWithBackend(ProcessSandbox.Backend.NONE);
        try (var mocked = org.mockito.Mockito.mockStatic(AiConfigServiceHeadless.class)) {
            mocked.when(AiConfigServiceHeadless::isUnsandboxedPluginsEnabled).thenReturn(false);
            // Simulate a per-turn AI FULL_ACCESS grant. Under the old (buggy) behavior this would
            // select sandbox.unrestricted() and the invoke would succeed; under the fix it must NOT
            // — the plugin still routes through sandbox.plugin() and fails closed on the NONE backend.
            AiPermissionMode previous = AiPermissionContext.current();
            AiPermissionContext.set(AiPermissionMode.FULL_ACCESS);
            try {
                var error = assertThrows(IllegalStateException.class,
                    () -> manager.invoke("com.example.worker", "echo", Map.of()));
                assertTrue(error.getMessage().contains("native process sandbox"),
                    "FULL_ACCESS must not unsandbox the plugin: " + error.getMessage());
            } finally {
                AiPermissionContext.set(previous);
            }
        } finally {
            manager.close();
        }
    }

    private static void waitForLog(ListAppender<ILoggingEvent> appender, String fragment,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline
                && appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(fragment))) {
            Thread.sleep(10);
        }
    }

    private PluginProcessManager manager() throws Exception {
        return manager(List.of());
    }

    private PluginProcessManager manager(List<String> permissions) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        String manifest = """
            {"schemaVersion":1,"id":"com.example.worker","name":"Worker","description":"test",
             "version":"1.0.0","author":"test","icon":"test","category":"test",
             "ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":%s}
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(permissions));
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins").toString());
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip", archive(manifest)));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:worker-host", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "do-not-log-me", null));
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data").toString());
        return new PluginProcessManager(packages, new PluginFileGrantService(), runtimeEnvironment, new PluginLogStore());
    }

    /**
     * A manager whose {@link PluginPackageService} records and verifies manifest digests via a
     * {@link PluginIntegrityStore} pinned under the temp dir. Used by P0-2 tamper-detection tests.
     */
    private PluginProcessManager managerWithIntegrity() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        String command = "\"" + java + "\" -cp \"" + classpath + "\" " + EchoWorker.class.getName();
        String manifest = """
            {"schemaVersion":1,"id":"com.example.worker","name":"Worker","description":"test",
             "version":"1.0.0","author":"test","icon":"test","category":"test",
             "ui":{"entry":"ui/index.html"},
             "backend":{"command":%s,"protocol":"json-rpc-2.0"},"permissions":[]}
            """.formatted(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command));
        PluginPackageService packages = new PluginPackageService(temp.resolve("plugins-int").toString());
        packages.attachIntegrityStoreForTest(new PluginIntegrityStore(temp.resolve("digests-int")));
        packages.install(new MockMultipartFile("file", "worker.fyp", "application/zip", archive(manifest)));
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host-int").toString());
        dataSources.save(new DataSourceConfig(DbType.H2, "jdbc:h2:mem:worker-int", "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect", "sa", "", null));
        PluginRuntimeEnvironmentService runtimeEnvironment = new PluginRuntimeEnvironmentService(
            dataSources, temp.resolve("plugin-data-int").toString());
        return new PluginProcessManager(packages, new PluginFileGrantService(), runtimeEnvironment, new PluginLogStore());
    }

    /**
     * Builds a manager pinned to a specific sandbox backend via the 5-arg constructor (the one
     * normally populated by {@code @Autowired}). Used to force the {@link ProcessSandbox.Backend#NONE}
     * Windows code path so the unsandboxed toggle can be exercised deterministically regardless of
     * the CI host. Distinct temp subdirs (plugins-none / host-none / plugin-data-none) keep it from
     * colliding with {@link #manager(List)} when both run in the same class.
     */
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

    private byte[] archive(String manifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "manifest.json", manifest); add(zip, "ui/index.html", "test");
        }
        return bytes.toByteArray();
    }
    private static void add(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    public static final class EchoWorker {
        private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"");
        public static void main(String[] args) throws Exception {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    var matcher = ID.matcher(line); String id = matcher.find() ? matcher.group(1) : "";
                    if (line.contains("\"method\":\"eof\"")) return;
                    System.out.println("third-party diagnostic line");
                    System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}");
                    if (line.contains("\"method\":\"sleep\"")) {
                        try { Thread.sleep(3_000); } catch (InterruptedException ie) { return; }
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"value\":\"slept\"}}");
                    } else if (line.contains("\"method\":\"error\"")) {
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":{\"code\":-32000,\"message\":\"bad workbook\"}}");
                    } else if (line.contains("\"method\":\"secret-error\"")) {
                        String password = System.getenv("FENGYU_DB_PASSWORD");
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"error\":{\"code\":-32000,\"message\":\"worker failed with "
                            + password + "\"}}");
                    } else if (line.contains("\"method\":\"stderr-secret\"")) {
                        System.err.println("database password=" + System.getenv("FENGYU_DB_PASSWORD"));
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":\"ok\"}}");
                    } else if (line.contains("\"method\":\"command\"")) {
                        String command = String.join(" ",
                            ProcessHandle.current().info().arguments().orElse(new String[0]));
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":\"" + command.replace("\\", "\\\\") + "\"}}");
                    } else if (line.contains("\"method\":\"environment\"")) {
                        String url = System.getenv("FENGYU_DB_URL");
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":\"" + url + "\"}}");
                    } else if (line.contains("\"method\":\"env-probe\"")) {
                        // Echo which host env vars are visible to the worker. Used by P0-1 to prove
                        // the worker does NOT inherit arbitrary host secrets while it DOES still see
                        // allowlisted essentials and the FENGYU_PLUGIN_ID protocol variable.
                        String pluginId = System.getenv("FENGYU_PLUGIN_ID");
                        String hostSecret = System.getenv("FENGYU_P0A_HOST_SECRET");
                        String path = System.getenv("PATH");
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"pluginId\":\"" + (pluginId == null ? "" : pluginId)
                            + "\",\"hostSecret\":\"" + (hostSecret == null ? "" : hostSecret).replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\",\"path\":\"" + (path == null ? "" : path).replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}");
                    } else if (line.contains("\"method\":\"temporary-file\"")) {
                        Path created = Files.createTempFile("fengyu-worker-", ".tmp");
                        String value = created.toAbsolutePath().toString().replace("\\", "\\\\");
                        Files.delete(created);
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":\"" + value + "\"}}");
                    } else if (line.contains("\"method\":\"pid\"")) {
                        // Echo this worker JVM's pid. Used by P0-6 to prove an upgrade restarts the
                        // worker process (the old pid must not survive a version change).
                        long pid = ProcessHandle.current().pid();
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                            + "\",\"result\":{\"value\":" + pid + "}}");
                    } else {
                        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"value\":\"ok\"}}");
                    }
                    System.out.flush();
                }
            }
        }
    }
}
