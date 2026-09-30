package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexMarketplaceAdapterTest {

    private final CodexMarketplaceAdapter adapter = new CodexMarketplaceAdapter();

    @Test
    void parsesLocalSourceWithInterfaceBlock() throws Exception {
        String json = Files.readString(Path.of(
            "src/test/resources/store-fixtures/codex-marketplace.json"));
        StoreSource src = new StoreSource("openai-curated", StoreSourceType.CODEX,
            "https://raw.githubusercontent.com/openai/curated/main/.agents/plugins/marketplace.json",
            "OpenAI");

        List<UnifiedCatalogEntry> entries = adapter.parse(src, json);

        assertEquals(1, entries.size());
        UnifiedCatalogEntry e = entries.get(0);
        assertEquals("openai-curated:CODEX:linear", e.uid());
        assertEquals("ChatGPT Official", e.displayName());
        assertEquals("Productivity", e.category());
        assertTrue(e.sourceRef() instanceof UnifiedCatalogEntry.GitLocalInRepoSource);
        var gl = (UnifiedCatalogEntry.GitLocalInRepoSource) e.sourceRef();
        assertEquals("https://github.com/openai/curated", gl.repoUrl());
        assertEquals("main", gl.ref());
        assertEquals("./plugins/linear", gl.path());
        assertNotNull(e.interfaceMeta());
        assertEquals("ChatGPT Official", e.interfaceMeta().displayName());
    }
}
