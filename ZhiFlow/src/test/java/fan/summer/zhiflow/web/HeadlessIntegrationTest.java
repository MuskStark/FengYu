package fan.summer.zhiflow.web;

import fan.summer.zhiflow.ai.spring.AiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walking-skeleton acceptance test — boots the headless Spring Boot web context on a random port
 * and exercises the Phase 1 endpoints: health, plugin listing (the Markdown plugin must register
 * as a bean), and the plugin {@code invoke} render path (backend commonmark render).
 *
 * <p>The H2/JPA schema is now created automatically by Hibernate {@code ddl-auto} (Task 6); the
 * former manual {@code DatabaseInit.init()} is gone with the MyBatis removal.
 */
@SpringBootTest(classes = AiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeadlessIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void health_returnsOk() throws Exception {
        HttpResponse<String> resp = get("/api/health");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"ok\""), "got: " + resp.body());
    }

    @Test
    void plugins_listsMarkdown() throws Exception {
        HttpResponse<String> resp = get("/api/plugins");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("fan.summer.markdown"),
            "expected Markdown plugin in /api/plugins, got: " + resp.body());
        assertTrue(resp.body().contains("/plugin-ui/markdown/index.js"),
            "expected uiEntry in descriptor");
    }

    @Test
    void invokeRender_returnsHtml() throws Exception {
        HttpResponse<String> resp = postJson("/api/plugins/fan.summer.markdown/invoke",
            "{\"action\":\"render\",\"args\":{\"markdown\":\"# Test\\n\\n**bold**\"}}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"success\":true"), "got: " + resp.body());
        assertTrue(resp.body().contains("<h1>Test</h1>"), "expected h1 in html, got: " + resp.body());
        assertTrue(resp.body().contains("<strong>bold</strong>"), "expected strong in html, got: " + resp.body());
    }

    @Test
    void invokeUnknownPlugin_returns404() throws Exception {
        HttpResponse<String> resp = postJson("/api/plugins/does.not.exist/invoke",
            "{\"action\":\"x\",\"args\":{}}");
        assertEquals(404, resp.statusCode());
    }
}
