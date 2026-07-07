package fan.summer.zhiflow.ai.service;

import org.junit.jupiter.api.Test;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests only the connection-probe helper on OllamaLocalBackend, which pings
 * {base}/api/tags. The full chat path needs a live Ollama and is covered by
 * manual smoke-testing.
 */
class OllamaLocalBackendConnectionTest {

    @Test
    void probeReturnsTrueWhenServerResponds200() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread accepter = new Thread(() -> {
                try (java.net.Socket s = server.accept();
                     var os = s.getOutputStream()) {
                    // minimal HTTP 200 response
                    String body = "{\"models\":[]}";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: " + body.length() + "\r\n\r\n" + body;
                    os.write(resp.getBytes());
                } catch (Exception ignored) {}
            });
            accepter.setDaemon(true);
            accepter.start();

            assertTrue(OllamaLocalBackend.probeReachable("http://localhost:" + port));
        }
    }

    @Test
    void probeReturnsFalseWhenConnectionRefused() {
        // pick a port that's almost certainly closed
        assertFalse(OllamaLocalBackend.probeReachable("http://localhost:65500"));
    }
}
