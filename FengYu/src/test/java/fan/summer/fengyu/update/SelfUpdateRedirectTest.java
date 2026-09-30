package fan.summer.fengyu.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 regression: the portable self-update downloads (checksums, signature, JAR) must follow
 * bounded, validated HTTP redirects. The JDK HttpClient defaults to {@code Redirect.NEVER} and
 * GitHub asset URLs answer 302 to a signed CDN URL — without this handling every redirect
 * aborted the update. Tested against a real loopback HttpServer, hop by hop.
 */
class SelfUpdateRedirectTest {

    private final SelfUpdateService service = new SelfUpdateService(null);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private URI start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void followsChainedRedirectsToTheFinalBody() throws Exception {
        URI base = start(0);
        AtomicInteger finalHits = new AtomicInteger();
        server.createContext("/hop1", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "hop2");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/hop2", exchange -> {
            // Relative Location (no scheme/host) must resolve against the current URI.
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            finalHits.incrementAndGet();
            byte[] body = "the real bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var response = service.sendFollowingRedirects(
                base.resolve("hop1"), Duration.ofSeconds(5), "checksums.txt");

        assertEquals(200, response.statusCode());
        assertArrayEquals("the real bytes".getBytes(StandardCharsets.UTF_8),
                response.body().readAllBytes());
        assertEquals(1, finalHits.get(), "the final hop was fetched exactly once");
    }

    @Test
    void redirectLoopsTerminateAtTheHopBound() throws Exception {
        URI base = start(0);
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.sendFollowingRedirects(base.resolve("loop"),
                        Duration.ofSeconds(5), "Infinia.jar"));
        assertTrue(error.getMessage().contains("redirects"),
                "the loop must end at the hop cap: " + error.getMessage());
    }

    @Test
    void refusesNonHttpRedirectTargets() throws Exception {
        URI base = start(0);
        server.createContext("/to-file", exchange -> {
            exchange.getResponseHeaders().add("Location", "file:///etc/passwd");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.sendFollowingRedirects(base.resolve("to-file"),
                        Duration.ofSeconds(5), "checksums.txt"));
        assertTrue(error.getMessage().contains("non-HTTP"),
                "a file:// redirect must be refused: " + error.getMessage());
    }

    @Test
    void redirectWithoutALocationHeaderIsRejected() throws Exception {
        URI base = start(0);
        server.createContext("/no-location", exchange -> {
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.sendFollowingRedirects(base.resolve("no-location"),
                        Duration.ofSeconds(5), "checksums.txt.sig"));
        assertTrue(error.getMessage().contains("without a Location"),
                "a 3xx without Location must fail loudly: " + error.getMessage());
    }

    @Test
    void nonRedirectStatusesPassThroughUntouched() throws Exception {
        URI base = start(0);
        server.createContext("/plain-404", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        var response = service.sendFollowingRedirects(base.resolve("plain-404"),
                Duration.ofSeconds(5), "checksums.txt.sig");
        assertEquals(404, response.statusCode(), "404 must reach the caller (signature optional)");
        try (var ignored = response.body()) { }
    }
}
