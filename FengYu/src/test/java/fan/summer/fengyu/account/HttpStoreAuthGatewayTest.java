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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The confidential-client contract against the current store registration
 * (client_secret_post on top of PKCE): the token request carries the configured
 * secret, and an {@code invalid_client} rejection surfaces with an actionable
 * hint instead of a bare HTTP 401.
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
            boolean success = form.contains("code_verifier=good");
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

    private HttpStoreAuthGateway gatewayWithSecret(String secret) {
        return new HttpStoreAuthGateway(
                new StoreEndpointProvider(base(), () -> base(), false),
                "fengyu-desktop", secret);
    }

    @Test
    void tokenRequestSendsTheConfiguredSecretViaClientSecretPost() {
        HttpStoreAuthGateway gateway = gatewayWithSecret("dev-only-desktop-secret");

        gateway.exchange("auth-code", "good", "http://127.0.0.1:1/callback");

        String form = lastForm.get();
        assertTrue(form.contains("grant_type=authorization_code"));
        assertTrue(form.contains("client_id=fengyu-desktop"));
        assertTrue(form.contains("client_secret=dev-only-desktop-secret"),
                "the confidential client authenticates with client_secret_post");
        assertTrue(form.contains("code_verifier=good"), "PKCE stays mandatory");
    }

    @Test
    void invalidClientRejectionCarriesTheConfigurationHint() {
        HttpStoreAuthGateway gateway = gatewayWithSecret("wrong-secret");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> gateway.exchange("auth-code", "bad", "http://127.0.0.1:1/callback"));
        assertTrue(e.getMessage().contains("invalid_client"));
        assertTrue(e.getMessage().contains("fengyu.store.client-secret"),
                "the error names the misconfiguration: " + e.getMessage());
    }
}
