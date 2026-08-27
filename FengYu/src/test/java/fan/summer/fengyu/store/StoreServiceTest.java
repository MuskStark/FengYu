package fan.summer.fengyu.store;

import fan.summer.fengyu.ai.mcp.McpRuntimeManager;
import fan.summer.fengyu.ai.skill.SkillPackageService;
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
    SkillPackageService skills;
    McpRuntimeManager mcp;
    StoreInstallLedger ledger;
    StoreService service;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(StoreClient.class);
        plugins = mock(PluginPackageService.class);
        skills = mock(SkillPackageService.class);
        mcp = mock(McpRuntimeManager.class);
        ledger = new StoreInstallLedger(temp.resolve("installs.json"));
        service = new StoreService(client, ledger, plugins, skills, mcp,
                temp, "4.1.0");
    }

    private static ResolveResponse plan(String coordinate, String version, boolean resolvable) {
        return new ResolveResponse(resolvable, coordinate,
                List.of(new ResolutionItem(coordinate, "rel-1", version, "stable",
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
        assertTrue(error.getMessage().contains("missing dependencies"));
        verify(plugins, never()).install(any(Path.class), anyBoolean());
    }

    @Test
    void installDispatchesPluginsThroughThePackageService() throws Exception {
        when(client.resolve(eq("infinia://plugin/official/markdown"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://plugin/official/markdown", "2.4.0", true));
        when(client.ticket("rel-1")).thenReturn(ticket());
        Path archive = fakeArchive(".fyp");
        when(client.download(any(), eq(".fyp"))).thenReturn(archive);
        when(plugins.install(any(Path.class), eq(false))).thenReturn(
                new fan.summer.fengyu.plugin.market.PluginManifest(2, "official.markdown",
                        "Markdown", "d", "2.4.0", "a", "i", "c", null, null,
                        List.of("files.read"), null, false, null, null, null, null));

        InstallResult result = service.install("infinia://plugin/official/markdown", false);

        assertEquals("official.markdown", result.localId());
        assertEquals("2.4.0", result.version());
        verify(plugins).install(archive, false);
        assertTrue(Files.notExists(archive), "temp download is cleaned up");
        assertTrue(ledger.find("infinia://plugin/official/markdown").isPresent());
    }

    @Test
    void installDispatchesSkills() throws Exception {
        when(client.resolve(eq("infinia://skill/official/pdf-tools"), anyString(), anyString(),
                anyString(), anyMap())).thenReturn(
                        plan("infinia://skill/official/pdf-tools", "1.3.0", true));
        when(client.ticket("rel-1")).thenReturn(ticket());
        Path archive = fakeArchive(".fys");
        when(client.download(any(), eq(".fys"))).thenReturn(archive);
        when(skills.install(any(Path.class))).thenReturn(new fan.summer.fengyu.ai.skill
                .SkillManifest(1, "official.pdf-tools", "PDF", "d", "1.3.0", "official",
                        null, null, false));

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
        when(client.ticket("rel-1")).thenReturn(ticket());
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

    private Map<String, String> unused() {
        return Map.of();
    }
}
