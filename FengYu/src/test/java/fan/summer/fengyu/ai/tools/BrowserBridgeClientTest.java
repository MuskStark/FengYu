package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeClientTest {

    private com.sun.net.httpserver.HttpServer server;
    private String receivedToken;
    private String receivedBody;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void startStub(String responseJson, int status) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/invoke", exchange -> {
            receivedToken = exchange.getRequestHeaders().getFirst("X-Browser-Token");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @Test
    void postsMethodAndParamsWithTokenHeaderAndParsesEnvelope() throws Exception {
        startStub("{\"success\":true,\"summary\":\"navigated to https://example.com\",\"url\":\"https://example.com\",\"title\":\"Example\"}", 200);
        var client = new BrowserBridgeClient(server.getAddress().getPort(), "secret-token");

        Map<String, Object> result = client.invoke("browser_navigate", Map.of("url", "https://example.com"), 60);

        assertEquals("secret-token", receivedToken);
        assertTrue(receivedBody.contains("\"method\":\"browser_navigate\""));
        assertTrue(receivedBody.contains("\"url\":\"https://example.com\""));
        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("navigated to https://example.com", result.get("summary"));
        assertEquals("https://example.com", result.get("url"));
    }

    @Test
    void throwsWhenBridgeReturnsNon200() throws Exception {
        startStub("{\"success\":false,\"summary\":\"bad request\"}", 400);
        var client = new BrowserBridgeClient(server.getAddress().getPort(), "tok");
        BrowserBridgeUnavailableException ex = assertThrows(BrowserBridgeUnavailableException.class,
                () -> client.invoke("browser_click", Map.of("selector", "#x"), 30));
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void throwsWhenBridgeUnreachable() {
        // port 1 is reserved/unlikely to have a listener
        var client = new BrowserBridgeClient(1, "tok");
        assertThrows(BrowserBridgeUnavailableException.class,
                () -> client.invoke("browser_close", Map.of(), 5));
    }

    @Test
    void asyncInvokeCompletesWithoutUsingBlockingHttpSend() throws Exception {
        startStub("{\"success\":true,\"summary\":\"async ok\"}", 200);
        var client = new BrowserBridgeClient(server.getAddress().getPort(), "async-token");

        Map<String, Object> result = client.invokeAsync("browser_snapshot", Map.of(), 5).get();

        assertEquals(Boolean.TRUE, result.get("success"));
        assertEquals("async ok", result.get("summary"));
        assertEquals("async-token", receivedToken);
    }
}
