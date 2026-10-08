package fan.summer.fengyu.plugin.store;

import fan.summer.fengyu.store.StoreModels.CatalogItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FengYuCatalogAdapterTest {

    // Null client: the legacy catalogUrl path never touches the official-store channel.
    private final FengYuCatalogAdapter adapter = new FengYuCatalogAdapter(null);

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

    @Test
    void mapsOfficialStoreItemToCoordinateEntry() {
        StoreSource src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "", "FengYu Default");
        CatalogItem item = new CatalogItem(
            "infinia://plugin/infinia/qrsync", "PLUGIN", "infinia", "qrsync",
            "FY-QRSync Offline Transfer", "Move files across air-gapped machines.",
            "utilities", "1.2.0", "stable", "Infinia", "2026-09-01T00:00:00Z");

        UnifiedCatalogEntry e = adapter.mapOfficial(src, item);

        assertEquals("fengyu-default:FENGYU:qrsync", e.uid());
        assertEquals("qrsync", e.name());
        assertEquals("FY-QRSync Offline Transfer", e.displayName());
        assertEquals("1.2.0", e.availableVersion());
        assertEquals("utilities", e.category());
        assertEquals("Infinia", e.author().name());
        assertTrue(e.sourceRef() instanceof UnifiedCatalogEntry.StoreCoordinateSource);
        assertEquals("infinia://plugin/infinia/qrsync",
            ((UnifiedCatalogEntry.StoreCoordinateSource) e.sourceRef()).coordinate());
        assertFalse(e.installed());
    }

    @Test
    void skipsOfficialStoreItemsWithoutCoordinate() {
        StoreSource src = new StoreSource("fengyu-default", StoreSourceType.FENGYU,
            "", "FengYu Default");
        CatalogItem noCoordinate = new CatalogItem(
            null, "PLUGIN", "infinia", "x", "X", null, null, null, null, null, null);

        assertNull(adapter.mapOfficial(src, noCoordinate));
    }

    /**
     * P2 (catalogUrl SSRF gap): the legacy catalog fetch runs the same UrlPolicy as download
     * URLs — under the default posture a catalog URL aimed at a link-local metadata endpoint or
     * an intranet host is refused BEFORE the request, mirroring downloadToStaging's contract.
     */
    @Test
    void legacyCatalogFetchRejectsNonTraversableCatalogUrls() {
        for (String intranet : new String[] {
                "http://169.254.169.254/latest/meta-data/catalog.json",
                "https://192.168.1.5/catalog.json",
                "http://93.184.216.34/catalog.json"}) {  // plain HTTP off loopback
            StoreSource src = new StoreSource("fengyu-intranet", StoreSourceType.FENGYU,
                intranet, "Intranet");
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> adapter.fetchCatalog(src), intranet);
            assertTrue(rejected.getMessage().contains("egress policy"),
                intranet + ": " + rejected.getMessage());
        }
    }

    /**
     * The private-network posture is the same escape hatch as for downloads. {@code 0.0.0.0} is a
     * plain-HTTP non-loopback target (refused by the default posture) that every TCP stack maps
     * to the local loopback — with the flag on, the fetch passes the policy and then fails on the
     * CLOSED port instantly, proving the flag (not luck) carried it past the check.
     */
    @Test
    void legacyCatalogFetchAllowsNonLoopbackPlainHttpUnderThePrivateNetworkPosture() {
        FengYuCatalogAdapter intranetAllowed = new FengYuCatalogAdapter(null, true, () -> true);
        StoreSource src = new StoreSource("fengyu-lan", StoreSourceType.FENGYU,
            "http://0.0.0.0:1/catalog.json", "LAN");
        IllegalStateException connectFailure = assertThrows(IllegalStateException.class,
            () -> intranetAllowed.fetchCatalog(src));
        assertFalse(connectFailure.getMessage().contains("egress policy"),
            "the posture flag must exempt the URL from the policy check: "
                + connectFailure.getMessage());
    }
}
