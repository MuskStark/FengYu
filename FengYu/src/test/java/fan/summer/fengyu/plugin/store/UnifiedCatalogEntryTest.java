package fan.summer.fengyu.plugin.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedCatalogEntryTest {

    @Test
    void uidConstructorPositionMatchesRecordHeader() {
        // The record has 19 components in this order:
        //   uid, origin, sourceType, name, displayName, description, author, category,
        //   keywords, homepage, pinnedSha, sourceRef, declaredSkills, mcpServers,
        //   interfaceMeta, installed, installedVersion, updateAvailable, enabled
        var entry = new UnifiedCatalogEntry(
            "anthropics-claude:CLAUDE:browser-use",   // uid
            "anthropics-claude",                       // origin
            StoreSourceType.CLAUDE,                   // sourceType
            "browser-use",                             // name
            "browser-use",                             // displayName
            "Give Claude a browser",                   // description
            null,                                      // author
            null,                                      // category
            java.util.List.of(),                       // keywords
            "https://example.com",                     // homepage
            "abc123",                                  // pinnedSha
            new UnifiedCatalogEntry.GitUrlSource("https://github.com/o/r.git", null),  // sourceRef
            java.util.List.of(),                       // declaredSkills
            java.util.List.of(),                       // mcpServers
            null,                                      // interfaceMeta
            false,                                     // installed
            null,                                      // installedVersion
            false,                                     // updateAvailable
            false);                                    // enabled
        assertEquals("anthropics-claude:CLAUDE:browser-use", entry.uid());
        assertEquals(StoreSourceType.CLAUDE, entry.sourceType());
        assertEquals("abc123", entry.pinnedSha());
        assertTrue(entry.sourceRef() instanceof UnifiedCatalogEntry.GitUrlSource);
    }

    @Test
    void storeSourceHoldsOriginAndUrl() {
        var src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "https://example.com/catalog.json", "FengYu Default");
        assertEquals(StoreSourceType.FENGYU, src.sourceType());
        assertEquals("fengyu-default", src.origin());
    }
}
