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

    @Test
    void localizesInstalledEntryNameAndDescriptionByLocale() throws Exception {
        // The catalog entry carries single-language strings, but the installed manifest has a zh
        // i18n override. The store must surface the localized name/description so an installed
        // plugin's display strings follow the request locale (regression guard for the lost-i18n
        // bug: previously the catalog path ignored the manifest i18n block entirely).
        String pluginId = "fan.summer.demo";
        Path pluginDir = temp.resolve(pluginId);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"),
            "{\"schemaVersion\":2,\"id\":\"" + pluginId + "\",\"name\":\"Demo\","
            + "\"description\":\"Demo plugin\",\"version\":\"1.0.0\",\"author\":\"a\",\"icon\":\"i\","
            + "\"category\":\"c\",\"ui\":{\"entry\":\"index.html\"},"
            + "\"i18n\":{\"zh\":{\"name\":\"\u6f14\u793a\u63d2\u4ef6\",\"description\":\"\u6f14\u793a\u8bf4\u660e\"}}}");

        StoreSource s = new StoreSource("fengyu-default", StoreSourceType.FENGYU, "https://e/f.json", "F");
        StubRegistry registry = new StubRegistry(List.of(s), Map.of(
            "fengyu-default", List.of(entry("fengyu-default:FENGYU:" + pluginId,
                StoreSourceType.FENGYU, pluginId, "Catalog description"))
        ));
        UnifiedStoreService svc = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        // zh locale: installed entry surfaces the manifest's localized name/description, not the
        // catalog's English strings.
        UnifiedCatalogEntry zh = svc.list(new UnifiedStoreService.StoreFilter(null, null, null), "zh").get(0);
        assertTrue(zh.installed());
        assertEquals("\u6f14\u793a\u63d2\u4ef6", zh.displayName(), "zh locale must surface the localized name");
        assertEquals("\u6f14\u793a\u8bf4\u660e", zh.description(), "zh locale must surface the localized description");

        // null locale (install-lifecycle lookup path): no localization, catalog strings preserved.
        UnifiedCatalogEntry raw = svc.list(new UnifiedStoreService.StoreFilter(null, null, null)).get(0);
        assertEquals(pluginId, raw.displayName(), "null locale keeps the catalog display name");
        assertEquals("Catalog description", raw.description(), "null locale keeps the catalog description");
    }

    @Test
    void marksFengyuUpdateFromAdvertisedVersionUsingSemver() throws Exception {
        String pluginId = "fan.summer.demo";
        Path pluginDir = temp.resolve(pluginId);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"),
            "{\"schemaVersion\":2,\"id\":\"" + pluginId + "\",\"name\":\"Demo\","
            + "\"description\":\"d\",\"version\":\"4.0.0-beta.9\",\"author\":\"a\",\"icon\":\"i\","
            + "\"category\":\"c\",\"ui\":{\"entry\":\"index.html\"}}");

        StoreSource source = new StoreSource("fengyu", StoreSourceType.FENGYU,
            "https://e/f.json", "F");
        UnifiedCatalogEntry remote = new UnifiedCatalogEntry(
            "fengyu:FENGYU:" + pluginId, "fengyu", StoreSourceType.FENGYU,
            pluginId, "Demo", "d", null, "c", List.of(), null, null,
            "4.0.0-beta.10", "a".repeat(64),
            new UnifiedCatalogEntry.ZipUrlSource("https://e/demo.fyp"),
            List.of(), List.of(), null, false, null, false, false);
        StubRegistry registry = new StubRegistry(List.of(source), Map.of("fengyu", List.of(remote)));
        UnifiedStoreService service = new UnifiedStoreService(registry, records,
            new PluginPackageService(temp.toString()));

        UnifiedCatalogEntry merged = service.list(
            new UnifiedStoreService.StoreFilter(null, null, null)).getFirst();
        assertEquals("4.0.0-beta.10", merged.availableVersion());
        assertTrue(merged.updateAvailable());
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
