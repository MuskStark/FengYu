package fan.summer.fengyu.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTesterTest {

    /** Spin up a stub HTTP server on a random port, return its base URL (no trailing slash). */
    private static String stubServer(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            Handler.StubResp r = handler.handle(exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod());
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
        // NOTE: caller must server.stop(0) in finally — omitted for brevity in this stub helper.
    }

    interface Handler {
        record StubResp(int status, String body) {}
        StubResp handle(String path, String method);
    }

    @Test
    void testCloud_openAi_200_returnsSuccess() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"id\":\"chatcmpl-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testCloud("openai", base, "sk-test", "gpt-4o");
            assertTrue(r.success());
            assertNull(r.error());
        } finally { server.stop(0); }
    }

    @Test
    void testCloud_401_returnsFailureWithError() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testCloud("openai", base, "sk-bad", "gpt-4o");
            assertFalse(r.success());
            assertTrue(r.error().contains("401"));
        } finally { server.stop(0); }
    }

    @Test
    void testCloud_deepSeek_hitsV1ChatCompletions() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testCloud("deepseek", base, "sk-ds", "deepseek-chat");
            assertTrue(r.success());
        } finally { server.stop(0); }
    }

    @Test
    void testCloud_anthropic_hitsV1Messages() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        // The Anthropic SDK builds the /v1/messages path itself, so the probe POSTs to
        // {normalizedBase}/messages (BaseUri normalization strips a trailing /v1). The stub must
        // therefore serve /messages — the path ConnectionTester.testAnthropic actually requests —
        // not the full /v1/messages wire path the SDK emits in production. See BaseUrlNormalizer.
        server.createContext("/messages", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testCloud("anthropic", base, "sk-ant", "claude-sonnet-4-20250514");
            assertTrue(r.success());
        } finally { server.stop(0); }
    }

    @Test
    void testCloud_unreachable_returnsFailure() {
        ConnectionTester.TestResult r =
                ConnectionTester.testCloud("openai", "http://127.0.0.1:1", "sk-x", "gpt-4o");
        assertFalse(r.success());
        assertNotNull(r.error());
    }

    @Test
    void testCloud_blankFields_returnsFailure() {
        ConnectionTester.TestResult r =
                ConnectionTester.testCloud("openai", "", "", "");
        assertFalse(r.success());
        assertNotNull(r.error());
    }

    @Test
    void testOllama_200_modelPresent_returnsSuccess() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"qwen3:4b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testOllama(base, "qwen3:4b");
            assertTrue(r.success());
            assertNull(r.warning());
        } finally { server.stop(0); }
    }

    @Test
    void testOllama_200_modelMissing_returnsSuccessWithWarning() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"llama3:8b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionTester.TestResult r =
                    ConnectionTester.testOllama(base, "qwen3:4b");
            assertTrue(r.success());
            assertNotNull(r.warning());
            assertTrue(r.warning().contains("qwen3:4b"));
        } finally { server.stop(0); }
    }

    @Test
    void testOllama_unreachable_returnsFailure() {
        ConnectionTester.TestResult r =
                ConnectionTester.testOllama("http://127.0.0.1:1", "qwen3:4b");
        assertFalse(r.success());
        assertNotNull(r.error());
    }
}
