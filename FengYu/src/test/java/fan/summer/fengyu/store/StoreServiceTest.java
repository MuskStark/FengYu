package fan.summer.fengyu.store;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.skill.SkillManifest;
import fan.summer.fengyu.ai.skill.SkillPackageService;
import fan.summer.fengyu.plugin.market.PluginLifecycleOrchestrator;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.store.StoreModels.CatalogItem;
import fan.summer.fengyu.store.StoreModels.CatalogPage;
import fan.summer.fengyu.store.StoreModels.DownloadTicket;
import fan.summer.fengyu.store.StoreModels.ResolveResponse;
import fan.summer.fengyu.store.StoreModels.ResolutionItem;
import fan.summer.fengyu.store.StoreService.CatalogView;
import fan.summer.fengyu.store.StoreService.InstallResult;
import fan.summer.fengyu.store.StoreService.UpdateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestrator unit tests: resolution gating, per-type dispatch, ledger state
 * and update comparison. Package services are mocked — their own suites cover
 * archive validation.
 */
class StoreServiceTest {

    @TempDir
    Path temp;

    StoreClient client;
    PluginPackageService plugins;
    PluginLifecycleOrchestrator lifecycle;
    SkillPackageService skills;
    McpRuntimeManager mcp;
    StoreInstallLedger ledger;
    StoreService service;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(StoreClient.class);
        plugins = mock(PluginPackageService.class);
        lifecycle = mock(PluginLifecycleOrchestrator.class);
        skills = mock(SkillPackageService.class);
        when(skills.root()).thenReturn(temp.resolve("skills"));
        mcp = mock(McpRuntimeManager.class);
        ledger = new StoreInstallLedger(temp.resolve("installs.json"));
        service = new StoreService(client, ledger, plugins, lifecycle, skills, mcp,
                temp, "4.1.0");
    }

    private static PluginManifest pluginManifest(String id, String version) {
        return new PluginManifest(2, id, id, "d", version, "a", "i", "c", null, null,
                List.of("files.read"), null, false, null, null, null, null);
    }

    private static SkillManifest skillManifest(String id, String version) {
        return new SkillManifest(1, id, id, "d", version, "official", null, null, false);
    }

    private static ResolveResponse plan(String coordinate, String version, boolean resolvable) {
        return new ResolveResponse(resolvable, coordinate,
                List.of(StoreModels.resolutionItem(coordinate, "rel-1", version, "stable",
                        ">=4.0.0 <5.0.0", false, List.of())),
                resolvable ? List.of()
                        : List.of(new StoreModels.MissingDependency(
                                "infinia://plugin/official/missing", ">=1.0.0", "absent")));
    }

    private static DownloadTicket ticket() {
        return new DownloadTicket("rel-1", "/api/v1/blobs/x?sig=1", "2030-01-01T00:00:00Z",
                "abc123", null, "key-1", 128);
    }

    private Path fakeArchive(String suffix) throws IOException {
        Path file = Files.createTempFile(temp, "pkg", suffix);
        Files.writeString(file, "package-bytes");
        return file;
    }

    @Test
    void catalogMergesInstallState() throws Exception {
        when(client.browse(eq("PLUGIN"), isNull(), isNull(), eq(60))).thenReturn(
                new CatalogPage(List.of(new CatalogItem(
                        "infinia://plugin/official/markdown", "PLUGIN", "official",
                        "markdown", "Markdown", "sum", "Productivity", "2.4.0", "stable",
                        "official", "2026")), null));
        ledger.record("infinia://plugin/official/markdown", "PLUGIN", "official.markdown",
                "2.3.0", "old");

        List<CatalogView> view = service.catalog("PLUGIN", null);

        assertEquals(1, view.size());
        // Flat row: the SPA renders these fields directly.
        assertEquals("infinia://plugin/official/markdown", view.get(0).coordinate());
        assertEquals("PLUGIN", view.get(0).type());
        assertEquals("official", view.get(0).namespace());
        assertEquals("markdown", view.get(0).slug());
        assertEquals("Markdown", view.get(0).name());
        assertEquals("2.4.0", view.get(0).latestVersion());
        assertTrue(view.get(0).installed());
        assertEquals("2.3.0", view.get(0).installedVersion());
    }

    @Test
    void installRejectsUnresolvablePlan() throws Exception {
        when(client.resolve(eq("infinia://plugin/official/x"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/x", "1.0.0", false));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install("infinia://plugin/official/x", false));
        assertTrue(error.getMessage().contains("missing or incompatible dependencies"),
                error.getMessage());
        // The refused dependency is identified with its range and store reason,
        // so "absent" and "incompatible" failures are distinguishable.
        assertTrue(error.getMessage().contains(
                "infinia://plugin/official/missing@>=1.0.0 (absent)"), error.getMessage());
        verify(plugins, never()).install(any(Path.class), anyBoolean());
    }

    @Test
    void installRejectsIncompatibleDependencyWithReason() throws Exception {
        when(client.resolve(eq("infinia://plugin/official/x"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        new ResolveResponse(false, "infinia://plugin/official/x", List.of(),
                                List.of(new StoreModels.MissingDependency(
                                        "infinia://plugin/official/helper", ">=2.0.0",
                                        "incompatible host version"))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.install("infinia://plugin/official/x", false));
        assertTrue(error.getMessage().contains("infinia://plugin/official/helper@>=2.0.0"),
                error.getMessage());
        assertTrue(error.getMessage().contains("(incompatible host version)"),
                error.getMessage());
        verify(plugins, never()).install(any(Path.class), anyBoolean());
    }

    @Test
    void installDispatchesPluginsThroughThePackageService() throws Exception {
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        Path archive = fakeArchive(".fyp");
        when(client.download(any(), eq(".fyp"))).thenReturn(archive);
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        when(plugins.install(any(Path.class), eq(false))).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));

        InstallResult result = service.install("infinia://plugin/official/markdown", false);

        assertEquals("official.markdown", result.localId());
        assertEquals("2.4.0", result.version());
        verify(plugins).install(archive, false);
        assertTrue(Files.notExists(archive), "temp download is cleaned up");
        assertTrue(ledger.find("infinia://plugin/official/markdown").isPresent());
        // Fresh install: gate opened and committed, but no health preflight (the
        // plugin had no previous version to return to) — the orchestrator contract.
        verify(lifecycle).beginStaged("official.markdown");
        verify(lifecycle, never()).preflightStaged("official.markdown");
        verify(lifecycle).commitStaged("official.markdown");
        assertTrue(result.dependenciesInstalled().isEmpty());
        assertTrue(Files.notExists(temp.resolve("store").resolve("transaction.json")),
                "committed transaction journal is removed");
    }

    @Test
    void installDispatchesSkills() throws Exception {
        when(client.resolve(eq("infinia://skill/official/pdf-tools"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://skill/official/pdf-tools", "1.3.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        Path archive = fakeArchive(".fys");
        when(client.download(any(), eq(".fys"))).thenReturn(archive);
        when(skills.install(any(Path.class))).thenReturn(
                skillManifest("official.pdf-tools", "1.3.0"));

        InstallResult result = service.install("infinia://skill/official/pdf-tools", false);

        assertEquals("official.pdf-tools", result.localId());
        verify(skills).install(archive);
        assertEquals("SKILL", ledger.find("infinia://skill/official/pdf-tools")
                .orElseThrow().type());
    }

    @Test
    void installImportsMcpTemplatesAsDisabledServers() throws Exception {
        when(client.resolve(eq("infinia://mcp/official/calendar"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://mcp/official/calendar", "1.0.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        when(client.downloadBytes(any())).thenReturn(("""
                {"schemaVersion":1,"id":"official.calendar","transport":"STREAMABLE_HTTP",
                 "urlTemplate":"https://mcp.infinia.dev/mcp",
                 "requiredSecrets":[{"name":"authorization","target":"header","sensitive":true}]}
                """).getBytes());
        when(client.parseMcpTemplate(any())).thenAnswer(invocation ->
                new com.fasterxml.jackson.databind.json.JsonMapper().readTree(
                        (byte[]) invocation.getArgument(0)));
        when(client.mapper()).thenReturn(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build());

        InstallResult result = service.install("infinia://mcp/official/calendar", false);

        assertEquals("mcp.official.calendar", result.localId());
        Path imported = temp.resolve("mcp-servers").resolve(
                "store-mcp.official.calendar.json");
        assertTrue(Files.isRegularFile(imported), "imported server file: " + imported);
        String json = Files.readString(imported);
        assertTrue(json.contains("https://mcp.infinia.dev/mcp"), json);
        assertTrue(json.contains("REQUIRED_SECRET"), json);
        verify(mcp).syncImportedServers();
    }

    @Test
    void updatesFlagNewerVersionsOnly() throws Exception {
        ledger.record("infinia://plugin/official/markdown", "PLUGIN", "official.markdown",
                "2.3.0", "old");
        ledger.record("infinia://skill/official/pdf-tools", "SKILL", "official.pdf-tools",
                "1.3.0", "old");
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.resolve(eq("infinia://skill/official/pdf-tools"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://skill/official/pdf-tools", "1.3.0", true));

        List<UpdateView> updates = service.updates();

        assertEquals(1, updates.size());
        assertEquals("infinia://plugin/official/markdown", updates.get(0).coordinate());
        assertEquals("2.4.0", updates.get(0).availableVersion());
    }

    @Test
    void updateComparisonFollowsSemVerPrecedence() throws Exception {
        // M-1: the update flag must follow SemVer precedence, not numeric triples.
        record Case(String installed, String available, boolean flag) {}
        String coordinate = "infinia://plugin/official/markdown";
        List<Case> cases = List.of(
                new Case("4.0.0-beta.5", "4.0.0-rc.1", true),
                new Case("4.0.0-rc.1", "4.0.0", true),
                new Case("4.0.0-beta.10", "4.0.0-beta.2", false),
                new Case("4.0.0+build.1", "4.0.0+build.9", false),
                new Case("2.4.0", "2.3.0", false),
                new Case("1.2.3", "1.2.3", false));
        for (Case c : cases) {
            ledger.record(coordinate, "PLUGIN", "official.markdown", c.installed(), "old");
            when(client.resolve(eq(coordinate), anyString(), anyString(), anyString(),
                    anyMap())).thenReturn(plan(coordinate, c.available(), true));

            boolean flagged = !service.updates().isEmpty();

            assertEquals(c.flag(), flagged, c.installed() + " -> " + c.available());
            ledger.remove(coordinate);
            reset(client);
        }
    }

    @Test
    void installExecutesTheFullDependencyPlan() throws Exception {
        // The plan lists the root first on purpose: dependencies must still run first.
        ResolveResponse full = new ResolveResponse(true,
                "infinia://plugin/official/suite", List.of(
                        StoreModels.resolutionItem("infinia://plugin/official/suite", "rel-root",
                                "2.0.0", "stable", ">=4.0.0 <5.0.0", false, List.of()),
                        StoreModels.resolutionItem("infinia://skill/official/helper", "rel-dep",
                                "1.0.0", "stable", ">=4.0.0 <5.0.0", false, List.of())),
                List.of());
        when(client.resolve(eq("infinia://plugin/official/suite"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(full);
        when(client.ticket(eq("rel-root"), isNull(), anyString(), anyString()))
                .thenReturn(new DownloadTicket("rel-root", "/b/root",
                        "2030-01-01T00:00:00Z", "sha-root", null, "key-1", 128));
        when(client.ticket(eq("rel-dep"), isNull(), anyString(), anyString()))
                .thenReturn(new DownloadTicket("rel-dep", "/b/dep",
                        "2030-01-01T00:00:00Z", "sha-dep", null, "key-1", 128));
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(client.download(any(), eq(".fys"))).thenReturn(fakeArchive(".fys"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.suite", "2.0.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.suite", "2.0.0"));
        when(skills.install(any(Path.class))).thenReturn(
                skillManifest("official.helper", "1.0.0"));

        InstallResult result = service.install("infinia://plugin/official/suite", false);

        assertEquals("official.suite", result.localId());
        // The dependency actually installed through this transaction — not a claim
        // over the raw plan (M-2).
        assertEquals(List.of("infinia://skill/official/helper"),
                result.dependenciesInstalled());
        var inOrder = inOrder(skills, plugins);
        inOrder.verify(skills).install(any(Path.class));
        inOrder.verify(plugins).install(any(Path.class), eq(false));
        var depEntry = ledger.find("infinia://skill/official/helper").orElseThrow();
        assertEquals("1.0.0", depEntry.version());
        assertEquals("sha-dep", depEntry.sha256());
        assertTrue(ledger.find("infinia://plugin/official/suite").isPresent());
        verify(lifecycle).commitStaged("official.suite");
        assertTrue(Files.notExists(temp.resolve("store").resolve("transaction.json")),
                "committed transaction journal is removed");
    }

    @Test
    void installSkipsDependenciesTheStoreAlreadySatisfied() throws Exception {
        ResolveResponse full = new ResolveResponse(true,
                "infinia://plugin/official/suite", List.of(
                        StoreModels.resolutionItem("infinia://plugin/official/suite", "rel-root",
                                "2.0.0", "stable", ">=4.0.0 <5.0.0", false, List.of()),
                        StoreModels.resolutionItem("infinia://skill/official/helper", "rel-dep",
                                "1.0.0", "stable", ">=4.0.0 <5.0.0", true, List.of())),
                List.of());
        when(client.resolve(eq("infinia://plugin/official/suite"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(full);
        when(client.ticket(eq("rel-root"), isNull(), anyString(), anyString())).thenReturn(ticket());
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.suite", "2.0.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.suite", "2.0.0"));

        InstallResult result = service.install("infinia://plugin/official/suite", false);

        assertTrue(result.dependenciesInstalled().isEmpty());
        verify(client, never()).ticket(eq("rel-dep"), any(), anyString(), anyString());
        verify(skills, never()).install(any(Path.class));
    }

    @Test
    void pluginPreflightFailureRollsBackTheWholePlan() throws Exception {
        ledger.record("infinia://plugin/official/suite", "PLUGIN", "official.suite",
                "1.9.0", "old");
        ResolveResponse full = new ResolveResponse(true,
                "infinia://plugin/official/suite", List.of(
                        StoreModels.resolutionItem("infinia://plugin/official/suite", "rel-root",
                                "2.0.0", "stable", ">=4.0.0 <5.0.0", false, List.of()),
                        StoreModels.resolutionItem("infinia://skill/official/helper", "rel-dep",
                                "1.0.0", "stable", ">=4.0.0 <5.0.0", false, List.of())),
                List.of());
        when(client.resolve(eq("infinia://plugin/official/suite"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(full);
        when(client.ticket(anyString(), any(), anyString(), anyString())).thenReturn(ticket());
        when(client.download(any(), anyString())).thenAnswer(invocation ->
                fakeArchive(invocation.getArgument(1)));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.suite", "2.0.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.suite", "2.0.0"));
        when(lifecycle.isInstalled("official.suite")).thenReturn(true);
        when(skills.install(any(Path.class))).thenReturn(
                skillManifest("official.helper", "1.0.0"));
        doThrow(new RuntimeException("preflight unhealthy")).when(lifecycle)
                .preflightStaged("official.suite");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.install("infinia://plugin/official/suite", false));
        assertTrue(error.getMessage().contains("preflight unhealthy"));

        // The failed update restores its predecessor through the staged rollback …
        verify(lifecycle).rollbackStaged("official.suite");
        // … the already-applied fresh dependency is removed again …
        verify(skills).uninstall("official.helper");
        // … and the ledger is back to the pre-transaction state.
        assertEquals("1.9.0", ledger.find("infinia://plugin/official/suite")
                .orElseThrow().version());
        assertTrue(ledger.find("infinia://skill/official/helper").isEmpty());
        assertTrue(Files.notExists(temp.resolve("store").resolve("transaction.json")),
                "rolled-back transaction journal is removed");
    }

    @Test
    void restartRecoveryRollsBackAnInterruptedTransaction() throws Exception {
        // A skill update that crashed after the swap but before the plan committed:
        // the journal marks the item applied with its OLD ledger entry and a backup
        // of the previous skill directory. Recovery must undo it at construction.
        Path storeDir = temp.resolve("store");
        Path skillsRoot = temp.resolve("skills");
        Path newSkill = Files.createDirectories(skillsRoot.resolve("official.helper"));
        Files.writeString(newSkill.resolve("SKILL.md"), "new version");
        Path backupDir = storeDir.resolve("txn-backup-test1");
        Path backup = Files.createDirectories(backupDir.resolve("skill-official.helper"));
        Files.writeString(backup.resolve("SKILL.md"), "old version");
        var oldEntry = new StoreInstallLedger.Entry("infinia://skill/official/helper",
                "SKILL", "official.helper", "1.0.0", "old-sha", "2026-01-01T00:00:00Z");
        var tx = new StoreInstallJournal.PendingTransaction("test1",
                "infinia://plugin/official/suite", "2026-01-01T00:00:00Z",
                List.of(new StoreInstallJournal.ItemState(
                        "infinia://skill/official/helper", "SKILL", "rel-1", "2.0.0",
                        "sha", "official.helper", true, false, oldEntry,
                        "skill-official.helper", null, false)));
        Files.createDirectories(storeDir);
        Files.writeString(storeDir.resolve("transaction.json"),
                new com.fasterxml.jackson.databind.json.JsonMapper().writeValueAsString(tx));
        ledger.record("infinia://skill/official/helper", "SKILL", "official.helper",
                "2.0.0", "sha");
        SkillPackageService realSkills = new SkillPackageService(skillsRoot.toString());

        new StoreService(client, ledger, plugins, lifecycle, realSkills, mcp, temp, "4.1.0");

        // The old version is back, the ledger entry restored, journal + backups gone.
        assertEquals("old version",
                Files.readString(skillsRoot.resolve("official.helper").resolve("SKILL.md")));
        assertEquals("1.0.0", ledger.find("infinia://skill/official/helper")
                .orElseThrow().version());
        assertTrue(Files.notExists(storeDir.resolve("transaction.json")));
        assertTrue(Files.notExists(backupDir));
    }

    @Test
    void uninstallRoutesPluginsThroughTheLifecycleGate() throws Exception {
        ledger.record("infinia://plugin/official/markdown", "PLUGIN", "official.markdown",
                "2.4.0", "sha");

        service.uninstall("infinia://plugin/official/markdown", true);

        verify(lifecycle).uninstallWithGate("official.markdown", true);
        assertTrue(ledger.find("infinia://plugin/official/markdown").isEmpty());
    }

    @Test
    void uninstallRemovesMcpFileAndLedgerEntry() throws Exception {
        ledger.record("infinia://mcp/official/calendar", "MCP", "mcp.official.calendar",
                "1.0.0", "abc");
        Path dir = temp.resolve("mcp-servers");
        Files.createDirectories(dir);
        Path file = dir.resolve("store-mcp.official.calendar.json");
        Files.writeString(file, "{}");

        service.uninstall("infinia://mcp/official/calendar", false);

        assertTrue(Files.notExists(file));
        assertTrue(ledger.find("infinia://mcp/official/calendar").isEmpty());
        verify(mcp).syncImportedServers();
    }

    @Test
    void coordinateTypeParses() {
        assertEquals("PLUGIN", StoreService.coordinateType(
                "infinia://plugin/official/markdown"));
        assertEquals("MCP", StoreService.coordinateType("infinia://mcp/official/calendar"));
        assertThrows(IllegalArgumentException.class,
                () -> StoreService.coordinateType("not-a-coordinate"));
    }

    @Test
    void catalogFollowsPaginationCursorUpToThePageCap() throws Exception {
        // P3 pagination: the store pages the catalog; the host used to see only the first 60
        // rows. The cursor chain is followed until it ends …
        CatalogItem row = new CatalogItem("infinia://plugin/official/markdown", "PLUGIN",
                "official", "markdown", "Markdown", "sum", "Productivity", "2.4.0", "stable",
                "official", "2026");
        when(client.browse(isNull(), isNull(), isNull(), eq(60)))
                .thenReturn(new CatalogPage(List.of(row), "cursor-1"));
        when(client.browse(isNull(), isNull(), eq("cursor-1"), eq(60)))
                .thenReturn(new CatalogPage(List.of(row), null));

        List<CatalogView> view = service.catalog(null, null);

        assertEquals(2, view.size(), "both pages' rows must be visible");
        verify(client).browse(isNull(), isNull(), isNull(), eq(60));
        verify(client).browse(isNull(), isNull(), eq("cursor-1"), eq(60));

        // … but never beyond MAX_CATALOG_PAGES, so a misbehaving cursor loop cannot spin.
        reset(client);
        when(client.browse(isNull(), isNull(), any(), eq(60)))
                .thenAnswer(invocation -> new CatalogPage(List.of(row), "again"));
        assertEquals(StoreService.MAX_CATALOG_PAGES, service.catalog(null, null).size());
        verify(client, times(StoreService.MAX_CATALOG_PAGES))
                .browse(isNull(), isNull(), any(), eq(60));
    }

    @Test
    void downloadTicketCarriesPlatformAndPreferredArtifactId() throws Exception {
        // P2-16: the ticket request must declare os/arch and pin the artifactId when the plan's
        // artifacts let the host pick one deterministically (exact platform+arch match), or the
        // store 404s platform-specific releases with no UNIVERSAL artifact.
        String os = System.getProperty("os.name", "").toLowerCase().contains("win") ? "windows"
                : (System.getProperty("os.name", "").toLowerCase().contains("mac") ? "macos" : "linux");
        String archRaw = System.getProperty("os.arch", "").toLowerCase();
        String arch = archRaw.contains("aarch64") || archRaw.contains("arm") ? "arm64" : "x64";
        ResolveResponse withArtifacts = new ResolveResponse(true, "infinia://plugin/official/native",
                List.of(new ResolutionItem("infinia://plugin/official/native", "rel-1", "1.0.0",
                        "stable", ">=4.0.0 <5.0.0", false, List.of(), List.of(
                        new StoreModels.ArtifactRef("art-other", "PACKAGE",
                                os.equals("macos") ? "linux" : "macos", arch,
                                "other.bin", 1, "sha-o", null),
                        new StoreModels.ArtifactRef("art-mine", "PACKAGE", os, arch,
                                "mine.bin", 1, "sha-m", null)))),
                List.of());
        when(client.resolve(eq("infinia://plugin/official/native"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(withArtifacts);
        when(client.ticket(eq("rel-1"), eq("art-mine"), eq(os), eq(arch))).thenReturn(ticket());
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.native", "1.0.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.native", "1.0.0"));

        service.install("infinia://plugin/official/native", false);

        verify(client).ticket(eq("rel-1"), eq("art-mine"), eq(os), eq(arch));
    }

    @Test
    @SuppressWarnings("unchecked")
    void installAndUninstallReportTelemetryAsynchronously() throws Exception {
        // P2-17: install telemetry is reported asynchronously; a failure inside the client is
        // swallowed there, so the local outcome never depends on the report.
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));

        service.install("infinia://plugin/official/markdown", false);

        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<StoreModels.InstallEvent>>) (Class<?>) List.class);
        verify(client, timeout(2_000).times(1)).reportInstallEvents(captor.capture());
        List<StoreModels.InstallEvent> events = captor.getValue();
        assertEquals(1, events.size());
        StoreModels.InstallEvent event = events.get(0);
        assertEquals("infinia://plugin/official/markdown", event.coordinate());
        assertEquals("PLUGIN", event.type());
        assertEquals("2.4.0", event.version());
        assertEquals("install", event.action());
        assertNotNull(event.idempotencyKey());
        assertNotNull(event.hostVersion());

        reset(client, plugins, lifecycle);
        ledger.record("infinia://plugin/official/markdown", "PLUGIN", "official.markdown",
                "2.4.0", "sha");
        service.uninstall("infinia://plugin/official/markdown", false);
        verify(client, timeout(2_000).times(1)).reportInstallEvents(captor.capture());
        assertEquals("uninstall", captor.getValue().get(0).action());
    }

    @Test
    void freshInstallRollbackClearsTheBogusOfficialTombstone() throws Exception {
        // P3: the rollback's uninstall writes an "uninstalled by user" tombstone — bogus for a
        // failed store transaction. Without restoring the pre-transaction state,
        // OfficialPluginSeeder would skip re-seeding the bundled plugin forever.
        var integrityStore = new fan.summer.fengyu.plugin.market.PluginIntegrityStore(
                temp.resolve("digests"));
        when(plugins.integrityStore()).thenReturn(integrityStore);
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        doThrow(new RuntimeException("commit failed")).when(lifecycle)
                .commitStaged("official.markdown");
        // The REAL uninstall writes the tombstone (PluginPackageService.uninstall →
        // markUninstalled); simulate that so the restore is what clears it again.
        doAnswer(invocation -> {
            integrityStore.markUninstalled("official.markdown");
            return null;
        }).when(lifecycle).uninstallWithGate("official.markdown", false);

        assertThrows(java.io.IOException.class,
                () -> service.install("infinia://plugin/official/markdown", false));

        verify(lifecycle).uninstallWithGate("official.markdown", false);
        assertFalse(integrityStore.isUninstalled("official.markdown"),
                "a failed store install must not leave a user-uninstall tombstone behind");
    }

    @Test
    void rollbackReMarksATombstoneThatPredatedTheTransaction() throws Exception {
        // The other direction of the same fix: the user HAD uninstalled this official plugin
        // (tombstone), tried a store reinstall, and the transaction failed — the rollback must
        // restore the tombstone so the seeder keeps honouring the user's choice.
        var integrityStore = new fan.summer.fengyu.plugin.market.PluginIntegrityStore(
                temp.resolve("digests"));
        integrityStore.markUninstalled("official.markdown");
        when(plugins.integrityStore()).thenReturn(integrityStore);
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.ticket(eq("rel-1"), isNull(), anyString(), anyString())).thenReturn(ticket());
        when(client.download(any(), eq(".fyp"))).thenReturn(fakeArchive(".fyp"));
        when(plugins.readArchiveManifest(any(Path.class))).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        when(plugins.install(any(Path.class), anyBoolean())).thenReturn(
                pluginManifest("official.markdown", "2.4.0"));
        doThrow(new RuntimeException("commit failed")).when(lifecycle)
                .commitStaged("official.markdown");
        doAnswer(invocation -> {
            integrityStore.markUninstalled("official.markdown");
            return null;
        }).when(lifecycle).uninstallWithGate("official.markdown", false);

        assertThrows(java.io.IOException.class,
                () -> service.install("infinia://plugin/official/markdown", false));

        assertTrue(integrityStore.isUninstalled("official.markdown"),
                "the pre-transaction tombstone must be restored by the rollback");
    }

    private Map<String, String> unused() {
        return Map.of();
    }
}
