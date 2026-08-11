package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.repository.PluginInstallRecordRepository;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class UnifiedStoreServiceTest {

    @TempDir Path temp;
    @Autowired private PluginInstallRecordRepository records;

    @Test
    void aggregatesAndFiltersBySourceType() {
        StoreSource feng = new StoreSource("fengyu", StoreSourceType.FENGYU, "https://e/f.json", "F");
        StoreSource claude = new StoreSource("claude", StoreSourceType.CLAUDE, "https://e/c.json", "C");
        StubRegistry registry = new StubRegistry(List.of(feng, claude), Map.of(
            "fengyu", List.of(entry("fengyu:FENGYU:a", StoreSourceType.FENGYU, "a", "Alpha")),
            "claude", List.of(entry("claude:CLAUDE:b", StoreSourceType.CLAUDE, "b", "Bravo"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        List<UnifiedCatalogEntry> all = svc.list(new UnifiedStoreService.StoreFilter(null, null, null));
        assertEquals(2, all.size());

        List<UnifiedCatalogEntry> onlyClaude = svc.list(
            new UnifiedStoreService.StoreFilter(StoreSourceType.CLAUDE, null, null));
        assertEquals(1, onlyClaude.size());
        assertEquals("claude:CLAUDE:b", onlyClaude.get(0).uid());
    }

    @Test
    void searchMatchesNameAndDescription() {
        StoreSource s = new StoreSource("s", StoreSourceType.FENGYU, "https://e/f.json", "S");
        StubRegistry registry = new StubRegistry(List.of(s), Map.of(
            "s", List.of(
                entry("s:FENGYU:a", StoreSourceType.FENGYU, "a", "Alpha editor"),
                entry("s:FENGYU:b", StoreSourceType.FENGYU, "b", "Bravo browser"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        List<UnifiedCatalogEntry> hits = svc.list(new UnifiedStoreService.StoreFilter(null, null, "bravo"));
        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).name());
    }

    @Test
    void mergesInstalledFypManifestIntoUnifiedCatalog() throws Exception {
        // 1. Drop a fake .fyp manifest on disk: <pluginDir>/<id>/manifest.json
        //    PluginPackageService.installed() scans <root>/<id>/manifest.json and returns it.
        //    The FENGYU catalog entry's name() must equal the manifest id() for the merge to match.
        String pluginId = "fan.summer.demo";
        Path pluginDir = temp.resolve(pluginId);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"),
            "{\"schemaVersion\":2,\"id\":\"" + pluginId + "\",\"name\":\"Demo\","
            + "\"description\":\"d\",\"version\":\"1.2.3\",\"author\":\"a\",\"icon\":\"i\","
            + "\"category\":\"c\",\"ui\":{\"entry\":\"index.html\"}}");

        StoreSource s = new StoreSource("fengyu-default", StoreSourceType.FENGYU, "https://e/f.json", "F");
        StubRegistry registry = new StubRegistry(List.of(s), Map.of(
            "fengyu-default", List.of(entry("fengyu-default:FENGYU:" + pluginId,
                StoreSourceType.FENGYU, pluginId, "Demo plugin"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        List<UnifiedCatalogEntry> all = svc.list(new UnifiedStoreService.StoreFilter(null, null, null));
        assertEquals(1, all.size());
        UnifiedCatalogEntry e = all.get(0);
        assertTrue(e.installed(), "installed .fyp plugin should be merged as installed=true");
        assertEquals("1.2.3", e.installedVersion(), "installedVersion should come from manifest version");
        assertTrue(e.enabled(), "freshly installed .fyp plugin (no .disabled marker) should be enabled");
    }

    private static UnifiedCatalogEntry entry(String uid, StoreSourceType type, String name, String desc) {
        return new UnifiedCatalogEntry(uid, uid.split(":")[0], type, name, name, desc,
            null, null, List.of(), null, null,
            new UnifiedCatalogEntry.ZipUrlSource("https://e/" + name + ".fyp"),
            List.of(), List.of(), null, false, null, false, false);
    }

    /** In-memory StoreSourceRegistry stub for service tests (no HTTP, no DB). */
    static class StubRegistry extends StoreSourceRegistry {
        final List<StoreSource> sources;
        final Map<String, List<UnifiedCatalogEntry>> catalog;
        StubRegistry(List<StoreSource> sources, Map<String, List<UnifiedCatalogEntry>> catalog) {
            super(null, List.of(), 600); // repo unused — we override every method that touches it
            this.sources = sources;
            this.catalog = catalog;
        }
        @Override public List<StoreSource> listSources() { return sources; }
        @Override public List<UnifiedCatalogEntry> fetchCatalog(String origin) {
            return catalog.getOrDefault(origin, List.of());
        }
    }
}
