package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.database.entity.store.PluginInstallRecordEntity;
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
    void routesFengyuZipEntriesToPackageService() {
        // A spy/stub: track that the download path is taken for the legacy FENGYU entry.
        var pkg = new PluginPackageService(temp.toString()); // real, but URL is unreachable
        InstallerDispatcher d = new InstallerDispatcher(pkg);

        UnifiedCatalogEntry fyp = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:x", "fengyu-default", StoreSourceType.FENGYU,
            "x", "x", "d", null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://example.com/x.fyp"),
            List.of(), List.of(), null, false, null, false, false);

        assertThrows(Exception.class, () -> d.install(fyp)); // URL unreachable in test
    }

    @Test
    void coordinateEntriesInstallAndUninstallThroughTheStorePipeline() throws Exception {
        // Official-store catalog rows carry a coordinate instead of a download URL: install and
        // uninstall must route through the store transaction pipeline, never the .fyp zip path.
        CapturingPackageService packages = new CapturingPackageService(temp);
        fan.summer.fengyu.store.StoreService store = mock(fan.summer.fengyu.store.StoreService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.store.StoreService>
                provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(store);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, null, null, provider, null, null);

        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:qrsync", "fengyu-default", StoreSourceType.FENGYU,
            "qrsync", "FY-QRSync", "d", null, null, List.of(), null, null,
            "1.0.0", null,
            new UnifiedCatalogEntry.StoreCoordinateSource("infinia://plugin/infinia/qrsync"),
            List.of(), List.of(), null, false, null, false, false);

        dispatcher.install(entry);
        verify(store).install("infinia://plugin/infinia/qrsync", false);

        dispatcher.uninstall(entry, true);
        verify(store).uninstall("infinia://plugin/infinia/qrsync", true);

        assertFalse(packages.installedStaged,
            "coordinate entries must not take the .fyp download path");
    }

    @Test
    void coordinateSkillEntriesToggleThroughTheSkillPackageService() throws Exception {
        // Enable/disable for ledger-bound SKILL items routes to the skill manager, so the
        // unified page's toggle covers the store's whole catalog, not just plugins.
        CapturingPackageService packages = new CapturingPackageService(temp);
        fan.summer.fengyu.store.StoreService store = mock(fan.summer.fengyu.store.StoreService.class);
        when(store.installed()).thenReturn(List.of(new fan.summer.fengyu.store.StoreService
                .InstalledView("infinia://skill/skillhub/demo", "SKILL", "skillhub.demo",
                        "1.0.0", true)));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.store.StoreService>
                storeProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(storeProvider.getObject()).thenReturn(store);
        fan.summer.fengyu.ai.skill.SkillPackageService skills =
                mock(fan.summer.fengyu.ai.skill.SkillPackageService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.skill.SkillPackageService>
                skillProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(skillProvider.getObject()).thenReturn(skills);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, null, null,
                storeProvider, skillProvider, null);

        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:demo", "fengyu-default", StoreSourceType.FENGYU,
            "demo", "Demo Skill", "d", null, null, List.of(), null, null,
            "1.0.0", null,
            new UnifiedCatalogEntry.StoreCoordinateSource("infinia://skill/skillhub/demo"),
            List.of(), List.of(), null, true, "1.0.0", false, true);

        dispatcher.setEnabled(entry, false);
        verify(skills).setEnabled("skillhub.demo", false);
        assertNull(packages.setEnabledId,
            "SKILL toggles must not hit the plugin package service");
    }

    @Test
    void mcpEntriesToggleThroughTheMcpRuntime() throws Exception {
        // MCP ledger items toggle the imported server definition via the MCP runtime — the
        // package path would always fail ("Plugin is not installed: <serverKey>"). Enabling
        // carries the explicit imported-server review (confirmImported); disabling does not.
        CapturingPackageService packages = new CapturingPackageService(temp);
        fan.summer.fengyu.store.StoreService store = mock(fan.summer.fengyu.store.StoreService.class);
        when(store.installed()).thenReturn(List.of(new fan.summer.fengyu.store.StoreService
                .InstalledView("infinia://mcp/official/calendar", "MCP",
                        "mcp.official.calendar", "1.0.0", true)));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.store.StoreService>
                storeProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(storeProvider.getObject()).thenReturn(store);
        fan.summer.fengyu.ai.mcp.McpRuntimeManager mcp =
                mock(fan.summer.fengyu.ai.mcp.McpRuntimeManager.class);
        when(mcp.servers()).thenReturn(List.of(new fan.summer.fengyu.ai.mcp.McpRuntimeManager
                .ServerView("mcp.official.calendar", "Calendar", "STREAMABLE_HTTP", null,
                        List.of(), "https://mcp.example/calendar", null, false,
                        "disconnected", null, null, null, List.of(), List.of(), List.of(),
                        List.of(), 0, 0, "store", "official_calendar")));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.ai.mcp.McpRuntimeManager>
                mcpProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(mcpProvider.getObject()).thenReturn(mcp);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, null, null,
                storeProvider, null, mcpProvider);

        UnifiedCatalogEntry entry = new UnifiedCatalogEntry(
            "fengyu-default:FENGYU:calendar", "fengyu-default", StoreSourceType.FENGYU,
            "calendar", "Calendar MCP", "d", null, null, List.of(), null, null,
            "1.0.0", null,
            new UnifiedCatalogEntry.StoreCoordinateSource("infinia://mcp/official/calendar"),
            List.of(), List.of(), null, false, null, false, true);

        dispatcher.setEnabled(entry, true);
        dispatcher.setEnabled(entry, false);

        var captor = org.mockito.ArgumentCaptor
                .forClass(fan.summer.fengyu.ai.mcp.McpRuntimeManager.ServerRequest.class);
        verify(mcp, times(2)).save(captor.capture(),
                org.mockito.ArgumentMatchers.eq("mcp.official.calendar"));
        var enableRequest = captor.getAllValues().get(0);
        assertTrue(enableRequest.enabled());
        assertEquals(Boolean.TRUE, enableRequest.confirmImported(),
            "enabling an imported template carries the explicit review");
        var disableRequest = captor.getAllValues().get(1);
        assertFalse(disableRequest.enabled());
        assertNull(disableRequest.confirmImported(),
            "disabling never needs the imported-server review");
        assertNull(packages.setEnabledId,
            "MCP toggles must not hit the plugin package service");
    }

    @Test
    void legacyThirdPartyInstallsUninstallThroughTheInstallRecord() throws Exception {
        // 4.0 Claude/Codex/Grok installs can never resolve a catalog entry again — the
        // install-record fallback must still remove their materialized content.
        fan.summer.fengyu.database.repository.PluginInstallRecordRepository records =
                mock(fan.summer.fengyu.database.repository.PluginInstallRecordRepository.class);
        PluginInstallRecordEntity rec = new PluginInstallRecordEntity();
        rec.setUid("github:CLAUDE:some-plugin");
        when(records.findByUidAndUserId("github:CLAUDE:some-plugin",
                fan.summer.fengyu.database.SecurityConstants.LOCAL_VIRTUAL_USER_ID))
                .thenReturn(Optional.of(rec));
        Path root = Files.createDirectories(temp.resolve("runtime"));
        Files.createDirectories(root.resolve("skills").resolve("github:CLAUDE:some-plugin"));
        Files.createDirectories(root.resolve("mcp-servers"));
        Files.writeString(root.resolve("mcp-servers").resolve("github:CLAUDE:some-plugin.json"), "{}");
        CapturingPackageService packages = new CapturingPackageService(temp);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, null, records,
                null, null, null);

        dispatcher.uninstallLegacyThirdParty("github:CLAUDE:some-plugin", root);

        assertFalse(Files.exists(root.resolve("skills/github:CLAUDE:some-plugin")),
            "the materialized skill dir is removed");
        assertFalse(Files.exists(root.resolve("mcp-servers/github:CLAUDE:some-plugin.json")),
            "the materialized MCP config is removed");
        verify(records).delete(rec);

        when(records.findByUidAndUserId(eq("nope"),
                any())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.uninstallLegacyThirdParty("nope", root),
                "unknown uids keep the normal 400 verdict");
    }

    @Test
    void storeValidationVerdictsPassThroughAsIllegalArgumentNotWrapped500() throws Exception {
        // The store pipeline throws user-actionable IllegalArgumentExceptions (dependency
        // resolution, "not installed from the store"); they must map to 400s, not 500s.
        CapturingPackageService packages = new CapturingPackageService(temp);
        fan.summer.fengyu.store.StoreService store = mock(fan.summer.fengyu.store.StoreService.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("missing or incompatible dependencies"))
                .when(store).install("infinia://plugin/infinia/qrsync", false);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<fan.summer.fengyu.store.StoreService>
                provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(store);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, null, null, provider, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.install(new UnifiedCatalogEntry(
                    "fengyu-default:FENGYU:qrsync", "fengyu-default", StoreSourceType.FENGYU,
                    "qrsync", "FY-QRSync", "d", null, null, List.of(), null, null,
                    "1.0.0", null,
                    new UnifiedCatalogEntry.StoreCoordinateSource("infinia://plugin/infinia/qrsync"),
                    List.of(), List.of(), null, false, null, false, false)));
        assertEquals("missing or incompatible dependencies", ex.getMessage());
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
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, processes, logs);

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
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages);

        dispatcher.update(catalogEntry("catalog-slug"));

        assertEquals("com.example.real", packages.committedId);
    }

    @Test
    void fengyuUpdateUsesProcessGateAndUninstallHonorsDataPolicy() {
        CapturingPackageService packages = new CapturingPackageService(temp);
        packages.manifestId = "com.example.demo";
        PluginProcessManager processes = mock(PluginProcessManager.class);
        PluginLogStore logs = mock(PluginLogStore.class);
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages, processes, logs);
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
        InstallerDispatcher dispatcher = new InstallerDispatcher(packages);
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
        InstallerDispatcher dispatcher = new InstallerDispatcher(new PluginPackageService(temp.toString()));
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
        String setEnabledId;
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
        @Override public void setEnabled(String id, boolean value) throws java.io.IOException {
            this.setEnabledId = id;
        }
        private PluginManifest manifest(String id) {
            return new PluginManifest(2, id, id, "d", "1.0.0", "a", "i", "c", null, null,
                    List.of(), null, false, null, null, null, null);
        }
    }
}
