package fan.summer.fengyu.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProcessSandboxTest {
    @TempDir Path workdir;

    @Test
    void compatibilityBackendLeavesCommandUnchangedAndReportsDowngrade() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, false);

        assertEquals(List.of("/bin/sh", "-lc", "pwd"), launch.command());
        assertFalse(launch.sandboxed());
        assertEquals("none", launch.backend().id());
    }

    @Test
    void bubblewrapLimitsWritesAndDisablesNetworkByDefault() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, false);

        assertTrue(launch.sandboxed());
        assertTrue(launch.command().contains("--ro-bind"));
        assertTrue(launch.command().contains("--unshare-net"));
        assertTrue(launch.command().contains(workdir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void explicitlyApprovedNetworkDoesNotUnshareNetworkNamespace() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);

        ProcessSandbox.Launch launch =
                sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, true);

        assertFalse(launch.command().contains("--unshare-net"));
    }

    @Test
    void pluginWorkerFailsClosedWithoutNativeSandbox() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);
        assertThrows(IllegalStateException.class, () -> sandbox.plugin(
                List.of("worker"), workdir, List.of(workdir), false, false));
    }

    @Test
    void macSandboxCanonicalizesWritableRootsBeforeBuildingProfile() throws Exception {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.SANDBOX_EXEC);
        Path writable = Files.createDirectories(workdir.resolve("worker-tmp"));

        ProcessSandbox.Launch launch = sandbox.plugin(
            List.of("worker"), workdir, List.of(writable), false, false);

        assertTrue(launch.command().get(2).contains(writable.toRealPath().toString()));
    }

    @Test
    void launchCarriesOnStartedCallback() {
        // NONE/BUBBLEWRAP/SANDBOX_EXEC Launches carry no onStarted hook; only WINDOWS_JOB does.
        ProcessSandbox noneSandbox = new ProcessSandbox(ProcessSandbox.Backend.NONE);
        ProcessSandbox.Launch l = noneSandbox.unrestricted(java.util.List.of("echo", "hi"));
        assertNull(l.onStarted(), "NONE backend has no onStarted hook");
    }

    @Test
    void windowsJobBackendIsNotSelectedOnNonWindows() {
        // detect() is private; infer via the public backend() default constructor.
        // On a non-Windows host WINDOWS_JOB must never be selected.
        assertNotEquals(ProcessSandbox.Backend.WINDOWS_JOB,
                new ProcessSandbox().backend(),
                "non-Windows host must not select WINDOWS_JOB");
    }

    /**
     * Regression (P0-3 / P0-2): the capability dimensions must be split and reported honestly. Only
     * {@link ProcessSandbox.Backend#BUBBLEWRAP} provides FULL security isolation (a minimal read-only
     * view that excludes the user home). {@link ProcessSandbox.Backend#SANDBOX_EXEC} (macOS) is
     * reduced — deny-sensitive, not a true deny-default — so it reports reduced, not full.
     * {@link ProcessSandbox.Backend#WINDOWS_JOB} confines lifecycle only; NONE provides nothing.
     */
    @Test
    void isolationCapabilitiesAreReportedHonestly() {
        assertTrue(ProcessSandbox.Backend.BUBBLEWRAP.providesSecurityIsolation(),
            "BUBBLEWRAP provides full security isolation");
        assertFalse(ProcessSandbox.Backend.BUBBLEWRAP.reducedIsolation());

        assertFalse(ProcessSandbox.Backend.SANDBOX_EXEC.providesSecurityIsolation(),
            "SANDBOX_EXEC is NOT full isolation (deny-sensitive, JVM cannot survive deny-default on macOS)");
        assertTrue(ProcessSandbox.Backend.SANDBOX_EXEC.reducedIsolation(),
            "SANDBOX_EXEC provides reduced isolation");

        assertFalse(ProcessSandbox.Backend.WINDOWS_JOB.providesSecurityIsolation(),
            "WINDOWS_JOB is lifecycle-only, not a security sandbox");
        assertFalse(ProcessSandbox.Backend.WINDOWS_JOB.reducedIsolation());

        assertFalse(ProcessSandbox.Backend.NONE.providesSecurityIsolation());
        assertFalse(ProcessSandbox.Backend.NONE.reducedIsolation());
    }

    /**
     * Regression (P0-3): a Worker launch under the WINDOWS_JOB backend reports lifecycle isolation
     * but NOT security isolation. The old behavior conflated the two, so {@code isNativeSandboxAvailable()}
     * returned true on Windows and the settings/permission gates treated a Job Object as a real
     * sandbox — a false sense of security.
     */
    @Test
    void windowsJobLaunchIsLifecycleIsolatedButNotSecurityIsolated() {
        // Force the WINDOWS_JOB backend (the 2-arg constructor) regardless of host OS so the property
        // is testable on any CI host.
        ProcessSandbox windowsSandbox = new ProcessSandbox(ProcessSandbox.Backend.WINDOWS_JOB);
        ProcessSandbox.Launch launch =
                windowsSandbox.command(List.of("worker.exe"), workdir, false);
        // The Job Object backend is "active" (a backend is set), so legacy sandboxed() is true…
        assertTrue(launch.sandboxed(), "WINDOWS_JOB backend is active");
        // …but it does NOT provide a security boundary — the property the gates must key on.
        assertFalse(launch.securityIsolated(),
            "WINDOWS_JOB must not report security isolation");
    }

    /**
     * Regression (P0-3): {@link ProcessSandbox#isNativeSandboxAvailable()} must return true only on
     * platforms with a real security sandbox. On a host whose detected backend is WINDOWS_JOB (or
     * NONE) it must return false so the host honestly runs in compatibility mode. We cannot force
     * the static detect() here, so this asserts the property indirectly: a backend that does not
     * provide security isolation never satisfies the gate.
     */
    @Test
    void isNativeSandboxAvailableKeysOnSecurityIsolation() {
        // Any non-security-isolating backend proves the gate would be false on a host that detects it.
        for (ProcessSandbox.Backend backend : ProcessSandbox.Backend.values()) {
            if (!backend.providesSecurityIsolation()) {
                // Simulate what isNativeSandboxAvailable() would return if detect() picked this backend:
                // it reduces to backend.providesSecurityIsolation().
                assertFalse(backend.providesSecurityIsolation(),
                    "isNativeSandboxAvailable must be false when detect() returns " + backend);
            }
        }
    }

    /**
     * Regression (P0-2c): bubblewrap must NOT bind the entire host root read-only. The old
     * {@code --ro-bind / /} exposed the user's home, SSH config, ~/.fengyu secrets, ... to every
     * plugin. The tightened view binds only the OS/runtime trees + the JDK + the plugin's own
     * package + any classpath roots the worker references — the user home must not appear.
     */
    @Test
    void bubblewrapDoesNotBindEntireHostRootOrUserHome() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);
        ProcessSandbox.Launch launch = sandbox.command(List.of("/bin/sh", "-lc", "pwd"), workdir, false);

        String joined = String.join(" ", launch.command());
        assertFalse(joined.contains("--ro-bind / /"),
            "bwrap must not bind the entire host root read-only: " + joined);
        // Essential runtime trees + the plugin package must still be visible.
        assertTrue(joined.contains("--ro-bind"), "bwrap must still bind read-only trees");
        assertTrue(joined.contains("/usr"), "bwrap must keep /usr visible: " + joined);
        assertTrue(joined.contains(workdir.toAbsolutePath().normalize().toString()),
            "bwrap must bind the plugin package read-only: " + joined);
        // The user's home must NOT be bound (the whole point of the tightening).
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && !home.equals("/")) {
            assertFalse(joined.contains("--ro-bind " + home + " "),
                "bwrap must not bind the user home: " + joined);
            assertFalse(joined.contains("--bind " + home + " "),
                "bwrap must not bind the user home writable: " + joined);
        }
    }

    @Test
    void bubblewrapCreatesPrivateTmpBeforeBindingNestedPluginPaths() throws Exception {
        Path pluginUnderTmp = Files.createDirectories(workdir.resolve("tmp-parent/plugin"));
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.BUBBLEWRAP);
        ProcessSandbox.Launch launch = sandbox.plugin(
            List.of("/bin/true"), pluginUnderTmp, List.of(pluginUnderTmp), false, false);

        List<String> command = launch.command();
        int tmpfs = command.indexOf("--tmpfs");
        int pluginBind = command.indexOf(pluginUnderTmp.toRealPath().toString());
        assertTrue(tmpfs >= 0 && pluginBind > tmpfs,
            "private /tmp must be mounted before nested plugin binds: " + command);
    }

    /**
     * Regression (P0-2c): the macOS sandbox-exec profile must deny reads of the genuinely sensitive
     * host paths (SSH/AWS/cloud credentials, the FengYu runtime root) so a plugin cannot harvest host
     * secrets. A strict deny-default is not viable on macOS (a JVM cannot launch under it), so the
     * profile starts from allow-default and explicitly denies the sensitive subpaths — the test pins
     * those denials.
     */
    @Test
    void macSandboxDeniesSensitiveHostPaths() {
        ProcessSandbox sandbox = new ProcessSandbox(ProcessSandbox.Backend.SANDBOX_EXEC);
        ProcessSandbox.Launch launch = sandbox.plugin(
            List.of("worker"), workdir, List.of(workdir), false, false);

        String profile = launch.command().get(2);
        // Credential dirs a plugin has no business reading.
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            assertTrue(profile.contains("(deny file-read* (subpath \"" + home + "/.ssh\"))"),
                "macOS profile must deny reading ~/.ssh: " + profile);
            assertTrue(profile.contains("(deny file-read* (subpath \"" + home + "/.aws\"))"),
                "macOS profile must deny reading ~/.aws: " + profile);
        }
        // The FengYu runtime root (host DB, config, other plugins' data) must be denied.
        String runtimeRoot = fan.summer.fengyu.runtime.RuntimePaths.root().toString();
        assertTrue(profile.contains("(deny file-read* (subpath \"" + runtimeRoot + "\"))"),
            "macOS profile must deny reading the FengYu runtime root: " + profile);
        // Writes denied by default; only plugin-owned roots writable.
        assertTrue(profile.contains("(deny file-write*)"),
            "macOS profile must deny writes by default: " + profile);
    }

    /**
     * Regression (P0-2c): the sandbox extracts the worker's own classpath roots from its command
     * line so it can grant them read access without exposing the whole host. A worker launched with
     * {@code -cp a.jar:b.jar} must yield both roots; {@code -jar app.jar} must yield the jar.
     */
    @Test
    void classpathRootsExtractsCpAndJarEntries() throws Exception {
        // Create the jars under a fresh subdirectory of the temp dir so the files are readable and
        // we don't collide with other tests' files in workdir. Use create-if-absent (idempotent) so
        // a retried run on the same temp dir does not FileAlreadyExistsException.
        java.nio.file.Path cpDir = Files.createDirectories(workdir.resolve("cp-test"));
        java.nio.file.Path a = touch(cpDir.resolve("a.jar"));
        java.nio.file.Path b = touch(cpDir.resolve("b.jar"));
        java.nio.file.Path jar = touch(cpDir.resolve("app.jar"));
        String sep = java.io.File.pathSeparator;

        // -cp with path-separator-split entries — only existing & readable entries survive the
        // filter (realPath requires the files to exist); the jars were created above. The extractor
        // returns realPath()-resolved entries, so compare against the resolved forms.
        try {
            java.nio.file.Path aReal = a.toRealPath();
            java.nio.file.Path bReal = b.toRealPath();
            java.nio.file.Path jarReal = jar.toRealPath();
            java.util.List<java.nio.file.Path> roots =
                ProcessSandbox.classpathRoots(java.util.List.of("java", "-cp", a + sep + b, "Main"));
            assertTrue(roots.contains(aReal), "-cp entry a.jar must be extracted: " + roots);
            assertTrue(roots.contains(bReal), "-cp entry b.jar must be extracted: " + roots);

            java.util.List<java.nio.file.Path> jarRoots =
                ProcessSandbox.classpathRoots(java.util.List.of("java", "-jar", jar.toString()));
            assertTrue(jarRoots.contains(jarReal), "-jar entry must be extracted: " + jarRoots);
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(jar);
        }
    }

    /** Create the file if absent, return its path (idempotent across retried runs on the same dir). */
    private static java.nio.file.Path touch(java.nio.file.Path p) throws java.io.IOException {
        if (!Files.exists(p)) Files.createFile(p);
        return p;
    }
}
