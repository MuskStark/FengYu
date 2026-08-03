package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeMarketplaceAdapterTest {

    private final ClaudeMarketplaceAdapter adapter = new ClaudeMarketplaceAdapter();

    @Test
    void parsesAllSourceBranches() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/claude-marketplace.json"));
        StoreSource src = new StoreSource("claude-plugins-official", StoreSourceType.CLAUDE,
            "https://example.com/m.json", "Claude");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        // local-skip is dropped; url-plugin and subdir-plugin remain
        assertEquals(2, entries.size());
        var url = entries.stream().filter(e -> e.name().equals("url-plugin")).findFirst().orElseThrow();
        assertEquals("claude-plugins-official:CLAUDE:url-plugin", url.uid());
        assertEquals("abc123sha", url.pinnedSha());
        assertTrue(url.sourceRef() instanceof UnifiedCatalogEntry.GitUrlSource);
        var sub = entries.stream().filter(e -> e.name().equals("subdir-plugin")).findFirst().orElseThrow();
        assertTrue(sub.sourceRef() instanceof UnifiedCatalogEntry.GitSubdirSource);
        assertEquals(List.of("design", "figma"), sub.keywords());
        var gs = (UnifiedCatalogEntry.GitSubdirSource) sub.sourceRef();
        assertEquals("plugins/x", gs.path());
        assertEquals("main", gs.ref());
    }
}
