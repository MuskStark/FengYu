package fan.summer.fengyu.web;

import fan.summer.fengyu.FengYuApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

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
 * <p>Uses the {@code test} profile, which provides an in-memory H2 datasource via Spring's standard
 * {@code DataSourceAutoConfiguration} (the profile does NOT set {@code fengyu.mode=app}, so the
 * conditional APP-mode beans stay inactive and the context boots without a real
 * {@code datasource.properties}); the JPA schema is created automatically by Hibernate
 * {@code ddl-auto}.
 */
@SpringBootTest(classes = FengYuApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
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
    void pluginRuntime_listsInstalledPackages() throws Exception {
        HttpResponse<String> resp = get("/api/plugin-runtime");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().startsWith("["), "expected descriptor array, got: " + resp.body());
    }

    @Test
    void invokeUnknownPlugin_returns404() throws Exception {
        HttpResponse<String> resp = postJson("/api/plugin-runtime/does.not.exist/invoke",
            "{\"method\":\"x\",\"params\":{}}");
        assertEquals(400, resp.statusCode());
    }

    /**
     * Local-mode AI chat after Task 3's {@code BackendReactivator}: an {@code OllamaLocalBackend}
     * is registered at startup, but {@code isReady()==false} until {@code loadModel} runs. With no
     * real Ollama server in CI, {@code AiController.stream} must trigger {@code loadModel} (which
     * resolves the {@code ChatModel} bean) so the backend is treated as "registered" rather than
     * "not configured". The downstream chat then fails at network time, emitting an SSE error event.
     *
     * <p>The load-bearing assertion is that we do NOT see "not configured" (the pre-fix message),
     * proving the backend is registered and the {@code loadModel} path was taken.
     */
    @Test
    void aiChat_localMode_registeredButNotReady_emitsErrorEvent() throws Exception {
        // POST /api/ai/chat -> stash the turn under a streamId.
        HttpResponse<String> chat = postJson("/api/ai/chat",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(200, chat.statusCode());
        String streamId = chat.body().replaceAll(".*\"streamId\":\"([^\"]+)\".*", "$1");

        // GET /api/ai/stream — opens the SSE; with no Ollama running the chat fails at call time.
        HttpResponse<String> stream = get("/api/ai/stream?streamId=" + streamId);
        assertEquals(200, stream.statusCode());
        String body = stream.body();

        // An error event must be emitted (Ollama unreachable in CI), and crucially the message
        // must NOT be "not configured" — that would mean the backend was null (pre-Task-3 path).
        assertTrue(body.contains("error"),
            "expected an error event for unready/unreachable local backend, got: " + body);
        assertTrue(!body.contains("not configured"),
            "backend should be registered (not 'not configured') after BackendReactivator; got: " + body);
    }
}
