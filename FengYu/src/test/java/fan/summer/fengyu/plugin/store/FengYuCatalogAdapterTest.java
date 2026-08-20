package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FengYuCatalogAdapterTest {

    private final FengYuCatalogAdapter adapter = new FengYuCatalogAdapter();

    @Test
    void parsesFixtureIntoUnifiedEntry() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/fengyu-catalog.json"));
        StoreSource src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "https://example.com/catalog.json", "FengYu");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        assertEquals(1, entries.size());
        UnifiedCatalogEntry e = entries.get(0);
        assertEquals("fengyu-default:FENGYU:fan.summer.markdown", e.uid());
        assertEquals("Markdown Editor", e.displayName());
        assertEquals("text", e.category());
        assertEquals("4.0.0-alpha.6", e.availableVersion());
        assertEquals("a".repeat(64), e.sha256());
        assertTrue(e.sourceRef() instanceof UnifiedCatalogEntry.ZipUrlSource);
        assertEquals("https://example.com/markdown.fyp",
            ((UnifiedCatalogEntry.ZipUrlSource) e.sourceRef()).url());
    }
}
