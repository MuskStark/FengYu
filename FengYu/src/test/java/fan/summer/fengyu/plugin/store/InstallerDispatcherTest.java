package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstallerDispatcherTest {

    @TempDir Path temp;

    @Test
    void routesFengyuToPackageService() {
        // A spy/stub: track that installFromUrl is called for the FENGYU entry.
        var pkg = new PluginPackageService(temp.toString()); // real, but URL is unreachable — we only assert routing throws the right type
        // Use a fake AgentContentInstaller that records calls.
        CapturingAgentInstaller agent = new CapturingAgentInstaller();

        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);
        UnifiedCatalogEntry fyp = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:x", "fengyu-default", StoreSourceType.FENGYU,
            "x", "x", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/x.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        // For FENGYU, the dispatcher must call the package service (which will try to fetch the URL).
        // We assert that the agent installer is NOT invoked, and the package service path is taken.
        assertThrows(Exception.class, () -> d.install(fyp)); // URL unreachable in test
        assertFalse(agent.invoked, "FENGYU must NOT go through AgentContentInstaller");
    }

    @Test
    void routesClaudeToAgentInstaller() {
        var pkg = new PluginPackageService(temp.toString());
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        InstallerDispatcher d = new InstallerDispatcher(pkg, agent);

        UnifiedCatalogEntry cl = new UnifiedCatalogEntry(
            "test:CLAUDE:y", "test", StoreSourceType.CLAUDE,
            "y", "y", "d", null, null, List.of(), null, "sha",
            new UnifiedCatalogEntry.GitUrlSource("file:///tmp/x", "sha"),
            List.of(), List.of(), null, false, null, false, false);

        d.install(cl);
        assertTrue(agent.invoked, "CLAUDE must go through AgentContentInstaller");
        assertEquals("test:CLAUDE:y", agent.lastUid);
    }

    @Test
    void fengyuUpdateUsesProcessGateAndUninstallHonorsDataPolicy() {
        CapturingPackageService packages = new CapturingPackageService(temp);
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        PluginProcessManager processes = mock(PluginProcessManager.class);
        PluginLogStore logs = mock(PluginLogStore.class);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, agent, processes, logs);
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:com.example.demo", "fengyu-default", StoreSourceType.FENGYU,
            "com.example.demo", "Demo", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/demo.fyp"),
            List.of(), List.of(), null, true, "1.0.0", true, true);

        dispatcher.update(entry);
        verify(processes).beginUpdate("com.example.demo");
        verify(processes).endUpdate("com.example.demo");
        assertTrue(packages.installedFromUrl);

        // Uninstall uses the update gate (not a bare stop): an invoke arriving
        // mid-uninstall must not respawn a worker from the directory being deleted.
        dispatcher.uninstall(entry, false);
        verify(processes, times(2)).beginUpdate("com.example.demo");
        verify(processes, times(2)).endUpdate("com.example.demo");
        verify(logs).clear("com.example.demo");
        assertFalse(packages.deleteData);
    }

    @Test
    void fengyuInstallPassesCatalogDigestToPackageVerifier() {
        CapturingPackageService packages = new CapturingPackageService(temp);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, new CapturingAgentInstaller());
        String digest = "a".repeat(64);
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:com.example.demo", "fengyu-default", StoreSourceType.FENGYU,
            "com.example.demo", "Demo", "d", null, null, List.of(), null, null,
            "1.1.0", digest, new UnifiedCatalogEntry.ZipUrlSource("https://example.com/demo.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        dispatcher.install(entry);

        assertEquals(digest, packages.expectedSha256);
    }

    @Test
    void updateWithoutProcessManagerCommitsSuccessfulSwap() {
        CapturingPackageService packages = new CapturingPackageService(temp);
        packages.existing = true;
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, new CapturingAgentInstaller());
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:com.example.demo", "fengyu-default", StoreSourceType.FENGYU,
            "com.example.demo", "Demo", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/demo.fyp"),
            List.of(), List.of(), null, true, "1.0.0", true, true);

        dispatcher.update(entry);

        assertEquals("com.example.demo", packages.committedId);
    }

    @Test
    void validationVerdictsPassThroughAsIllegalArgumentNotWrapped500() {
        // A bad URL scheme is an install-validation verdict: it must surface as
        // IllegalArgumentException (→ 400 with the actionable message), not be
        // rewrapped into the dispatcher's generic RuntimeException (→ opaque 500).
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        InstallerDispatcher dispatcher = new InstallerDispatcher(new PluginPackageService(temp.toString()), agent);
        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:ftp", "fengyu-default", StoreSourceType.FENGYU,
            "ftp", "ftp", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("ftp://example.com/demo.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> dispatcher.install(entry));
        assertTrue(e.getMessage().contains("HTTP(S)"));
    }

    /** Minimal AgentContentInstaller stand-in that records invocations. */
    static class CapturingAgentInstaller extends AgentContentInstaller {
        boolean invoked;
        String lastUid;
        CapturingAgentInstaller() { super(null, Path.of(System.getProperty("java.io.tmpdir")), 10); }
        @Override public void install(UnifiedCatalogEntry e) { invoked = true; lastUid = e.uid(); }
        @Override public void uninstall(String uid) { invoked = true; lastUid = uid; }
    }

    static class CapturingPackageService extends PluginPackageService {
        boolean installedFromUrl;
        boolean deleteData;
        boolean existing;
        String expectedSha256;
        String committedId;
        CapturingPackageService(Path root) { super(root.toString()); }
        @Override public Optional<PluginManifest> find(String id) {
            return existing ? Optional.of(mock(PluginManifest.class)) : Optional.empty();
        }
        @Override public PluginManifest installFromUrl(String url, String expectedSha256,
                String signature, String keyId, boolean confirmPermissionEscalation) {
            installedFromUrl = true;
            this.expectedSha256 = expectedSha256;
            return null;
        }
        @Override public void uninstall(String id, boolean deleteData) {
            this.deleteData = deleteData;
        }
        @Override public void commitUpdate(String id) {
            this.committedId = id;
        }
    }
}
