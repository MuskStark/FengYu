package fan.summer.fengyu.account;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.store.StoreEndpointProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token-endpoint contract. The shipped default is the RFC 8252 public client
 * (PKCE only — no client_secret anywhere), and deployments pairing with a
 * confidential store registration opt into client_secret_post explicitly. An
 * {@code invalid_client} rejection must surface with an actionable hint
 * instead of a bare HTTP 401 either way.
 */
class HttpStoreAuthGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> lastForm = new AtomicReference<>("");
    private final AtomicReference<String> lastJson = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            lastForm.set(form);
            lastPath.set("/oauth2/token");
            boolean success = form.contains("code_verifier=good")
                    || form.contains("grant_type=refresh_token");
            byte[] body = (success
                    ? "{\"access_token\":\"t\",\"expires_in\":600,\"refresh_token\":\"r\"}"
                    : "{\"error\":\"invalid_client\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(success ? 200 : 401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/oauth2/revoke", exchange -> {
            lastForm.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            lastPath.set("/oauth2/revoke");
            exchange.sendResponseHeaders(200, -1);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[0]);
            }
        });
        server.createContext("/api/v1/auth/refresh", exchange -> {
            lastJson.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            lastPath.set("/api/v1/auth/refresh");
            byte[] body = ("{\"accessToken\":\"fresh\",\"expiresIn\":1799,"
                    + "\"refreshToken\":\"next-credential\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/v1/auth/desktop-session", exchange -> {
            lastJson.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set("/api/v1/auth/desktop-session");
            byte[] body = ("{\"refreshToken\":\"issued-credential\",\"refreshExpiresAt\":\""
                    + java.time.Instant.now().plusSeconds(2592000) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/v1/auth/revoke", exchange -> {
            lastJson.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            lastPath.set("/api/v1/auth/revoke");
            exchange.sendResponseHeaders(204, -1);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[0]);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpStoreAuthGateway gateway(String secret) {
        return new HttpStoreAuthGateway(
                new StoreEndpointProvider(base(), () -> base(), false),
                "fengyu-desktop", secret);
    }

    @Test
    void publicClientDefaultOmitsTheSecretEntirely() {
        HttpStoreAuthGateway gateway = gateway("");

        gateway.exchange("auth-code", "good", "http://127.0.0.1:1/callback");

        String form = lastForm.get();
        assertTrue(form.contains("grant_type=authorization_code"));
        assertTrue(form.contains("client_id=fengyu-desktop"));
        assertFalse(form.contains("client_secret="),
                "the shipped default is the RFC 8252 public client — no secret in the form");
        assertTrue(form.contains("code_verifier=good"), "PKCE stays mandatory");
    }

    @Test
    void confidentialPairingSendsTheConfiguredSecretViaClientSecretPost() {
        HttpStoreAuthGateway gateway = gateway("store-paired-secret");

        gateway.exchange("auth-code", "good", "http://127.0.0.1:1/callback");

        String form = lastForm.get();
        assertTrue(form.contains("grant_type=authorization_code"));
        assertTrue(form.contains("client_id=fengyu-desktop"));
        assertTrue(form.contains("client_secret=store-paired-secret"),
                "an explicitly paired confidential registration authenticates with client_secret_post");
        assertTrue(form.contains("code_verifier=good"), "PKCE stays mandatory");
    }

    @Test
    void publicClientRefreshUsesTheRotatingCredentialEndpoint() {
        HttpStoreAuthGateway gateway = gateway("");

        StoreAuthGateway.TokenGrant grant = gateway.refresh("rotating-refresh-token");

        assertEquals("/api/v1/auth/refresh", lastPath.get(),
                "the public form refreshes through the store-managed credential endpoint");
        assertTrue(lastJson.get().contains("\"refreshToken\":\"rotating-refresh-token\""));
        assertFalse(lastJson.get().contains("client_secret"));
        assertEquals("fresh", grant.accessToken());
        assertEquals(1799, grant.expiresInSeconds());
        assertEquals("next-credential", grant.refreshToken(),
                "the rotated credential rides back on the grant");
    }

    @Test
    void confidentialPairingRefreshKeepsTheAuthorizationServerGrant() {
        HttpStoreAuthGateway gateway = gateway("store-paired-secret");

        gateway.refresh("rotating-refresh-token");

        assertEquals("/oauth2/token", lastPath.get());
        String form = lastForm.get();
        assertTrue(form.contains("grant_type=refresh_token"));
        assertTrue(form.contains("refresh_token=rotating-refresh-token"));
        assertFalse(form.contains("code_verifier="), "only the authorization_code grant sends a verifier");
        assertTrue(form.contains("client_secret=store-paired-secret"));
    }

    @Test
    void issueSessionCredentialPostsTheBearerAndParsesTheCredential() {
        HttpStoreAuthGateway gateway = gateway("");

        StoreAuthGateway.SessionCredential credential =
                gateway.issueSessionCredential("access-of-fresh-sign-in");

        assertEquals("/api/v1/auth/desktop-session", lastPath.get());
        assertEquals("Bearer access-of-fresh-sign-in", lastAuthHeader.get());
        assertEquals("issued-credential", credential.refreshToken());
        assertTrue(credential.refreshExpiresInSeconds() > 0,
                "the sliding TTL is parsed from refreshExpiresAt");
    }

    @Test
    void publicClientRevokeUsesTheDesktopRevokeEndpoint() {
        HttpStoreAuthGateway gateway = gateway("");

        gateway.revoke("credential-to-revoke");

        assertEquals("/api/v1/auth/revoke", lastPath.get());
        assertTrue(lastJson.get().contains("\"refreshToken\":\"credential-to-revoke\""));
        assertFalse(lastJson.get().contains("client_secret"));
    }

    @Test
    void confidentialPairingRevokeKeepsRfc7009() {
        HttpStoreAuthGateway gateway = gateway("store-paired-secret");

        gateway.revoke("token-to-revoke");

        assertEquals("/oauth2/revoke", lastPath.get());
        assertTrue(lastForm.get().contains("token=token-to-revoke"));
        assertTrue(lastForm.get().contains("client_secret=store-paired-secret"));
    }

    @Test
    void invalidClientRejectionCarriesTheConfigurationHint() {
        HttpStoreAuthGateway gateway = gateway("wrong-secret");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> gateway.exchange("auth-code", "bad", "http://127.0.0.1:1/callback"));
        assertTrue(e.getMessage().contains("invalid_client"));
        assertTrue(e.getMessage().contains("fengyu.store.client-secret"),
                "the error names the misconfiguration: " + e.getMessage());
    }
}
