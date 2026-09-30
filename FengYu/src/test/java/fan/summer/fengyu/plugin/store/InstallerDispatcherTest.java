package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.runtime.PluginLogStore;
import fan.summer.fengyu.plugin.runtime.PluginProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstallerDispatcherTest {

    @TempDir Path temp;

    @Test
    void routesFengyuToPackageService() {
        // A spy/stub: track that the download path is taken for the FENGYU entry.
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
    void updateGateKeysOnThePackagesRealIdNotTheCatalogSlug() {
        // P2-13 regression: the update gate used to be keyed on the CATALOG entry name. When a
        // third-party catalog's slug differed from the package's manifest id, beginUpdate stopped
        // the WRONG worker (or none), and because the slug was "not installed" no preflight and no
        // commit ran — the package journal stayed open and the next startup's recovery silently
        // rolled the successful install back. The gate must key on the id read from the package.
        CapturingPackageService packages = new CapturingPackageService(temp);
        packages.manifestId = "com.example.real";
        packages.existing = true; // the real id is installed → update path runs preflight + commit
        PluginProcessManager processes = mock(PluginProcessManager.class);
        PluginLogStore logs = mock(PluginLogStore.class);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages,
                new CapturingAgentInstaller(), processes, logs);

        dispatcher.update(catalogEntry("catalog-slug"));

        verify(processes).beginUpdate("com.example.real");
        verify(processes, never()).beginUpdate("catalog-slug");
        verify(processes).endUpdate("com.example.real");
        assertTrue(packages.installedStaged, "the staged installer runs inside the gate");
        assertEquals("com.example.real", packages.committedId,
            "commit must close the journal opened under the REAL id");
    }

    @Test
    void updateWithoutProcessManagerCommitsUnderTheRealId() {
        // Same regression through the legacy/test constructor: the commit (which deletes the
        // package update journal) must reference the package id, or a journal opened by the
        // installer under the real id would survive and be "recovered" at the next startup.
        CapturingPackageService packages = new CapturingPackageService(temp);
        packages.manifestId = "com.example.real";
        packages.existing = true;
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, new CapturingAgentInstaller());

        dispatcher.update(catalogEntry("catalog-slug"));

        assertEquals("com.example.real", packages.committedId);
    }

    @Test
    void fengyuUpdateUsesProcessGateAndUninstallHonorsDataPolicy() {
        CapturingPackageService packages = new CapturingPackageService(temp);
        packages.manifestId = "com.example.demo";
        CapturingAgentInstaller agent = new CapturingAgentInstaller();
        PluginProcessManager processes = mock(PluginProcessManager.class);
        PluginLogStore logs = mock(PluginLogStore.class);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, agent, processes, logs);
        UnifiedCatalogEntry entry = catalogEntry("com.example.demo");

        dispatcher.update(entry);
        verify(processes).beginUpdate("com.example.demo");
        verify(processes).endUpdate("com.example.demo");
        assertTrue(packages.installedStaged);

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

    /** A FENGYU catalog entry whose slug is {@code name} (may differ from the package id). */
    private static UnifiedCatalogEntry catalogEntry(String name) {
        return new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:" + name, "fengyu-default", StoreSourceType.FENGYU,
            name, "Demo", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/" + name + ".fyp"),
            List.of(), List.of(), null, true, "1.0.0", true, true);
    }

    /** Minimal AgentContentInstaller stand-in that records invocations. */
    static class CapturingAgentInstaller extends AgentContentInstaller {
        boolean invoked;
        String lastUid;
        CapturingAgentInstaller() { super(null, Path.of(System.getProperty("java.io.tmpdir")), 10); }
        @Override public void install(UnifiedCatalogEntry e) { invoked = true; lastUid = e.uid(); }
        @Override public void uninstall(String uid) { invoked = true; lastUid = uid; }
    }

    /**
     * Stands in for the download→preview→installStaged flow the dispatcher drives since P2-13:
     * the staging file is real (the dispatcher deletes it in its finally), the previewed manifest
     * id is configurable so catalog-slug ≠ package-id can be exercised.
     */
    static class CapturingPackageService extends PluginPackageService {
        boolean installedStaged;
        boolean deleteData;
        boolean existing;
        String expectedSha256;
        String committedId;
        String manifestId = "com.example.demo";
        String downloadUrl;
        CapturingPackageService(Path root) { super(root.toString()); }
        @Override public Optional<PluginManifest> find(String id) {
            return existing && manifestId.equals(id) ? Optional.of(manifest(manifestId)) : Optional.empty();
        }
        @Override public Path downloadToStaging(String url, String expectedSha256) {
            this.downloadUrl = url;
            this.expectedSha256 = expectedSha256;
            try {
                return Files.createTempFile("capturing-", ".fyp");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public PluginManifest readArchiveManifest(Path archive) {
            return manifest(manifestId);
        }
        @Override public PluginManifest installStaged(Path staging, String expectedSha256,
                String signature, String keyId, boolean confirmPermissionEscalation) {
            installedStaged = true;
            return manifest(manifestId);
        }
        @Override public void uninstall(String id, boolean deleteData) {
            this.deleteData = deleteData;
        }
        @Override public void commitUpdate(String id) {
            this.committedId = id;
        }
        private PluginManifest manifest(String id) {
            return new PluginManifest(2, id, id, "d", "1.0.0", "a", "i", "c", null, null,
                    List.of(), null, false, null, null, null, null);
        }
    }
}
