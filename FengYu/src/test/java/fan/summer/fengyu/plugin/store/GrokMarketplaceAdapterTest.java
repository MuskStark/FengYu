package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GrokMarketplaceAdapterTest {

    private final GrokMarketplaceAdapter adapter = new GrokMarketplaceAdapter();

    @Test
    void parsesPinnedRemoteSubdirectoryAndLocalMarketplaceSources() throws Exception {
        String catalog = Files.readString(Path.of("src/test/resources/store-fixtures/grok-marketplace.json"));
        StoreSource source = new StoreSource("xai-official", StoreSourceType.GROK,
                "https://raw.githubusercontent.com/xai-org/plugin-marketplace/main/.grok-plugin/marketplace.json",
                "xAI Official");

        List<UnifiedCatalogEntry> entries = adapter.parse(source, catalog);

        assertEquals(3, entries.size());
        UnifiedCatalogEntry vercel = entries.stream().filter(e -> e.name().equals("vercel")).findFirst().orElseThrow();
        assertEquals("xai-official:GROK:vercel", vercel.uid());
        assertEquals("11c32588786a9d49791372657433b88d49561874", vercel.pinnedSha());
        assertInstanceOf(UnifiedCatalogEntry.GitUrlSource.class, vercel.sourceRef());
        assertEquals(List.of("vercel", "vercel.com"), vercel.keywords());
        assertEquals("xAI", vercel.author().name());

        UnifiedCatalogEntry mongodb = entries.stream().filter(e -> e.name().equals("mongodb")).findFirst().orElseThrow();
        UnifiedCatalogEntry.GitSubdirSource subdir = assertInstanceOf(
                UnifiedCatalogEntry.GitSubdirSource.class, mongodb.sourceRef());
        assertEquals("plugins/mongodb", subdir.path());
        assertEquals("b4ea8150a020b9babaddc6c271c6dc177c06a83f", subdir.sha());

        UnifiedCatalogEntry neon = entries.stream().filter(e -> e.name().equals("neon")).findFirst().orElseThrow();
        UnifiedCatalogEntry.GitLocalInRepoSource local = assertInstanceOf(
                UnifiedCatalogEntry.GitLocalInRepoSource.class, neon.sourceRef());
        assertEquals("https://github.com/xai-org/plugin-marketplace", local.repoUrl());
        assertEquals("main", local.ref());
        assertEquals("./external_plugins/neon", local.path());
    }
}
