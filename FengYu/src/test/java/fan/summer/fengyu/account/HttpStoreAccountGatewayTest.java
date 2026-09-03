package fan.summer.fengyu.account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.store.StoreEndpointProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP contract for the user-center gateway: every method hits the agreed
 * store path with the bearer token, the canonical fixtures under
 * {@code store-fixtures/infinia-store/} parse into the host's records, and an
 * upstream failure surfaces the store's status and body on the exception
 * message (the frontend error banner renders exactly that text).
 */
class HttpStoreAccountGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> lastMethod = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicReference<String> lastAuth = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> forcedError = new AtomicReference<>();
    private final AtomicReference<String> forcedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            String forced = forcedError.get();
            if (forced != null) {
                respond(exchange, 429, forced);
                return;
            }
            String forcedOk = forcedBody.get();
            if (forcedOk != null) {
                respond(exchange, 200, forcedOk);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod()) && "/api/v1/me".equals(path)) {
                respond(exchange, 200, fixture("profile.json"));
            } else if ("PUT".equals(exchange.getRequestMethod())
                    && "/api/v1/me/password".equals(path)) {
                respond(exchange, 200, "{\"succeeded\":true,\"message\":null}");
            } else if ("/api/v1/me/library".equals(path)) {
                respond(exchange, 200, fixture("library.json"));
            } else if ("/api/v1/me/sessions".equals(path)) {
                respond(exchange, 200, fixture("sessions.json"));
            } else if ("/api/v1/me/devices".equals(path)) {
                respond(exchange, 200, fixture("devices.json"));
            } else if ("/api/v1/organizations".equals(path)) {
                respond(exchange, 200, fixture("organizations.json"));
            } else if ("DELETE".equals(exchange.getRequestMethod())
                    && (path.startsWith("/api/v1/me/sessions/")
                            || path.startsWith("/api/v1/me/devices/"))) {
                respond(exchange, 200, "{}");
            } else {
                respond(exchange, 200, "{\"userId\":null}");
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = HttpStoreAccountGatewayTest.class.getResourceAsStream(
                "/store-fixtures/infinia-store/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private HttpStoreAccountGateway gateway() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpStoreAccountGateway(new StoreEndpointProvider(base(), () -> base, false));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertBearer() {
        assertEquals("Bearer tok-1", lastAuth.get(), "the signed-in access token rides as a bearer");
    }

    @Test
    void updateDisplayNamePutsTheProfileShape() {
        var profile = gateway().updateDisplayName("tok-1", "新昵称");

        assertEquals("PUT", lastMethod.get());
        assertEquals("/api/v1/me", lastPath.get());
        assertBearer();
        assertTrue(lastBody.get().contains("\"displayName\":\"新昵称\""),
                "body: " + lastBody.get());
        assertEquals("usr-7723", profile.userId());
        assertEquals("峰语用户", profile.displayName());
        assertEquals(3, profile.beeLevel());
        assertTrue(profile.roles().contains("PUBLISHER"));
    }

    @Test
    void changePasswordPutsBothSecrets() {
        var result = gateway().changePassword("tok-1", "old-pass", "new-pass");

        assertEquals("PUT", lastMethod.get());
        assertEquals("/api/v1/me/password", lastPath.get());
        assertBearer();
        assertTrue(lastBody.get().contains("\"currentPassword\":\"old-pass\""));
        assertTrue(lastBody.get().contains("\"newPassword\":\"new-pass\""));
        assertTrue(result.succeeded());
    }

    @Test
    void libraryMapsFavoritesEntitlementsAndHistory() {
        var library = gateway().library("tok-1");

        assertEquals("/api/v1/me/library", lastPath.get());
        assertBearer();
        assertEquals(1, library.favorites().size());
        assertEquals("fan.summer.markdown/markdown", library.favorites().get(0).listingCoordinate());
        assertEquals(1, library.entitlements().size());
        assertEquals(2, library.installHistory().size());
        assertEquals("FAILED", library.installHistory().get(1).outcome());
    }

    @Test
    void sessionsListAndRevokeRoundTrip() {
        var sessions = gateway().sessions("tok-1");

        assertEquals("/api/v1/me/sessions", lastPath.get());
        assertBearer();
        assertEquals(2, sessions.size());
        assertEquals("DESKTOP", sessions.get(0).kind());

        gateway().revokeSession("tok-1", "sess-web");

        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/v1/me/sessions/sess-web", lastPath.get());
        assertBearer();
    }

    @Test
    void devicesListAndRevokeRoundTrip() {
        var devices = gateway().devices("tok-1");

        assertEquals("/api/v1/me/devices", lastPath.get());
        assertBearer();
        assertEquals(2, devices.size());
        assertEquals("windows", devices.get(1).platform());
        assertTrue(devices.get(1).revoked());

        gateway().revokeDevice("tok-1", "dev-home");

        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/v1/me/devices/dev-home", lastPath.get());
        assertBearer();
    }

    @Test
    void organizationsList() {
        var organizations = gateway().organizations("tok-1");

        assertEquals("/api/v1/organizations", lastPath.get());
        assertBearer();
        assertEquals(1, organizations.size());
        assertEquals("summer", organizations.get(0).slug());
    }

    @Test
    void upstreamErrorSurfacesStatusAndBody() {
        forcedError.set("{\"error\":\"rate_limited\",\"retryAfter\":30}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> gateway().library("tok-1"));

        assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
        assertTrue(e.getMessage().contains("rate_limited"), e.getMessage());
    }

    @Test
    void missingRequiredProfileFieldFailsLoudly() {
        forcedBody.set("{\"email\":\"x@example.com\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> gateway().updateDisplayName("tok-1", "n"));

        assertTrue(e.getMessage().contains("userId"), e.getMessage());
    }
}
