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

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            lastForm.set(form);
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
    void refreshGrantCarriesTheRefreshTokenAndNoVerifier() {
        HttpStoreAuthGateway gateway = gateway("");

        gateway.refresh("rotating-refresh-token");

        String form = lastForm.get();
        assertTrue(form.contains("grant_type=refresh_token"));
        assertTrue(form.contains("refresh_token=rotating-refresh-token"));
        assertFalse(form.contains("code_verifier="), "only the authorization_code grant sends a verifier");
        assertFalse(form.contains("client_secret="));
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
