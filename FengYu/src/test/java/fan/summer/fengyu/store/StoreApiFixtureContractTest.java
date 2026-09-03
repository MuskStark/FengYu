package fan.summer.fengyu.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the anonymous store REST contract (/api/v1 catalog, listing,
 * resolution, download-ticket) to the canonical fixtures under
 * {@code store-fixtures/infinia-store/}. {@link StoreClientTest} covers the
 * download trust chain; this class fails when the host's DTOs and the fixture
 * shape drift apart, so the fixtures stay a truthful mirror of what the store
 * deployment must serve.
 */
class StoreApiFixtureContractTest {

    @TempDir
    Path temp;

    private HttpServer server;
    private final AtomicReference<String> lastResolutionRequest = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/catalog".equals(path)) {
                respond(exchange, fixture("catalog.json"));
            } else if ("/api/v1/listings/fan.summer.markdown/markdown".equals(path)) {
                respond(exchange, fixture("listing.json"));
            } else if ("/api/v1/resolutions".equals(path)) {
                lastResolutionRequest.set(body);
                respond(exchange, fixture("resolution.json"));
            } else if ("/api/v1/releases/rel-md-120/download-ticket".equals(path)) {
                respond(exchange, fixture("download-ticket.json"));
            } else {
                respond(exchange, "{\"unexpected\":\"" + path + "\"}");
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = StoreApiFixtureContractTest.class.getResourceAsStream(
                "/store-fixtures/infinia-store/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private StoreClient client() {
        return new StoreClient(base(), new StoreTrustStore(temp.resolve("keys.json")),
                false, false, StoreClient.MAX_DOWNLOAD_BYTES, StoreClient.MAX_JSON_BYTES);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void catalogFixtureParsesIntoTheCatalogPage() throws Exception {
        var page = client().browse(null, null, null, 20);

        assertEquals(2, page.items().size());
        var markdown = page.items().get(0);
        assertEquals("fan.summer.markdown/markdown", markdown.coordinate());
        assertEquals("PLUGIN", markdown.type());
        assertEquals("1.2.0", markdown.latestVersion());
        assertEquals(null, page.nextCursor());
    }

    @Test
    void listingFixtureParsesReleasesArtifactsAndPermissions() throws Exception {
        var listing = client().listing("fan.summer.markdown", "markdown");

        assertEquals("PUBLISHED", listing.status());
        assertEquals(1, listing.releases().size());
        var release = listing.releases().get(0);
        assertEquals("rel-md-120", release.releaseId());
        assertEquals(">=4.0.0", release.requiresHost());
        assertEquals(1, release.artifacts().size());
        assertEquals("platform-2026", release.artifacts().get(0).keyId());
        assertEquals(204800, release.artifacts().get(0).size());
        assertTrue(release.dependencies().get(0).optional());
        assertTrue(release.permissions().get(0).required());
    }

    @Test
    void resolutionFixtureParsesThePlanAndClientPayloadShape() throws Exception {
        var resolution = client().resolve("fan.summer.markdown/markdown", "4.0.0-rc.1",
                "macos", "aarch64", Map.of("fan.summer.excel/excel", "1.1.0"));

        assertTrue(lastResolutionRequest.get().contains("\"coordinate\":\"fan.summer.markdown/markdown\""),
                "body: " + lastResolutionRequest.get());
        assertTrue(lastResolutionRequest.get().contains("\"hostVersion\":\"4.0.0-rc.1\""));
        assertTrue(lastResolutionRequest.get().contains("\"channel\":\"stable\""));
        assertTrue(resolution.resolvable());
        assertEquals(2, resolution.plan().size());
        assertTrue(resolution.plan().get(1).alreadyInstalled());
        assertEquals("rel-md-120", resolution.plan().get(0).releaseId());
    }

    @Test
    void downloadTicketFixtureKeepsTheMandatoryTrustFields() throws Exception {
        var ticket = client().ticket("rel-md-120");

        assertEquals("rel-md-120", ticket.releaseId());
        assertNotNull(ticket.sha256(), "the attested digest is mandatory (design §8.3)");
        assertNotNull(ticket.keyId());
        assertNotNull(ticket.signature());
        assertEquals(204800, ticket.size());
        assertTrue(ticket.url().startsWith("/api/v1/releases/"),
                "a relative ticket URL resolves against the store base: " + ticket.url());
    }
}
