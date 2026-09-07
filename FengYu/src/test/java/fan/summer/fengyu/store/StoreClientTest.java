package fan.summer.fengyu.store;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.store.StoreModels.DownloadTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trust-chain regression for the store client (review M-4): mandatory SHA-256,
 * platform Ed25519 signature verification with key revocation, plain-HTTP
 * restricted to loopback, private-network (SSRF) blocking, and streaming byte
 * budgets for downloads and JSON responses.
 */
class StoreClientTest {

    @TempDir
    Path temp;

    HttpServer server;
    KeyPair platformKey;
    String platformKeyB64;
    byte[] artifact = "store-artifact-bytes".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        platformKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        platformKeyB64 = Base64.getEncoder().encodeToString(
                platformKey.getPublic().getEncoded());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/artifact.bin") || path.endsWith("/api/v1/catalog")) {
                body = path.endsWith("/artifact.bin")
                        ? artifact
                        : "x".repeat(3 * 1024 * 1024).getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/stream.bin")) {
                exchange.getResponseHeaders().set("Content-Type",
                        "application/octet-stream");
                exchange.sendResponseHeaders(200, 0); // chunked
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(new byte[64 * 1024]);
                }
                exchange.close();
                return;
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type",
                    path.endsWith(".bin") ? "application/octet-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
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

    private void trustPlatformKey() throws Exception {
        // The leading _comment mirrors the bundled trusted-store-keys.json documentation header:
        // every parse must tolerate it (the TrustDocument ignores unknown fields).
        Files.writeString(temp.resolve("keys.json"), """
                {"_comment":"documentation header","keys":[{"id":"platform-2026","publicKey":"%s"}],"revokedKeys":[]}
                """.formatted(platformKeyB64));
    }

    private StoreClient client(boolean requireSignature, long maxDownloadBytes) {
        return new StoreClient(base(), new StoreTrustStore(temp.resolve("keys.json")),
                requireSignature, false, maxDownloadBytes, 2 * 1024 * 1024);
    }

    private String sign(byte[] bytes) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(platformKey.getPrivate());
        signature.update(bytes);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private DownloadTicket ticket(String url, String sha, long size, String signature,
            String keyId) {
        return new DownloadTicket("rel-1", url, "2030-01-01T00:00:00Z", sha, signature,
                keyId, size);
    }

    @Test
    void loopbackHttpDownloadVerifiedByShaAndPlatformSignature() throws Exception {
        trustPlatformKey();
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        Path file = client.download(ticket(base() + "/artifact.bin", sha256(artifact),
                artifact.length, sign(artifact), "platform-2026"), ".fyp");

        assertArrayEquals(artifact, Files.readAllBytes(file));
        Files.deleteIfExists(file);
    }

    @Test
    void settingsChannelOverrideRoutesRequestsWithoutARestart() throws Exception {
        // A second loopback server plays the production store the channel points at;
        // the bootstrap base (port 9) has nothing listening, so a request that still
        // used it would fail instead of reaching the override.
        HttpServer channel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        channel.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        channel.start();
        try {
            String channelBase = "http://127.0.0.1:" + channel.getAddress().getPort();
            StoreClient client = new StoreClient("http://127.0.0.1:9",
                    new StoreTrustStore(temp.resolve("keys.json")), true, false,
                    StoreClient.MAX_DOWNLOAD_BYTES, StoreClient.MAX_JSON_BYTES);
            client.setEndpointProvider(
                    new StoreEndpointProvider("http://127.0.0.1:9", () -> channelBase, false));

            assertEquals(channelBase, client.apiBase());
            assertNotNull(client.browse(null, null, null, 5));
            assertEquals(1, hits.get(), "the catalog request must reach the channel override");
        } finally {
            channel.stop(0);
        }
    }

    @Test
    void refusesTicketWithoutSha256() throws Exception {
        trustPlatformKey();
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/artifact.bin", null, artifact.length,
                        sign(artifact), "platform-2026"), ".fyp"));
        assertTrue(error.getMessage().contains("no SHA-256"), error.getMessage());
    }

    @Test
    void refusesUnsignedTicketByDefault() throws Exception {
        trustPlatformKey();
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/artifact.bin", sha256(artifact), artifact.length,
                        null, null), ".fyp"));
        assertTrue(error.getMessage().contains("not platform-signed"),
                error.getMessage());
    }

    @Test
    void unsignedTicketsAllowedOnlyWhenExplicitlyDisabledForDev() throws Exception {
        StoreClient client = client(false, StoreClient.MAX_DOWNLOAD_BYTES);

        Path file = client.download(ticket(base() + "/artifact.bin",
                sha256(artifact), artifact.length, null, null), ".fyp");

        assertArrayEquals(artifact, Files.readAllBytes(file));
        Files.deleteIfExists(file);
    }

    @Test
    void rejectsTamperedArtifact() throws Exception {
        trustPlatformKey();
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);
        String wrongSha = sha256("other bytes".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/artifact.bin", wrongSha, artifact.length,
                        sign(artifact), "platform-2026"), ".fyp"));
        assertTrue(error.getMessage().contains("integrity check failed"),
                error.getMessage());
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        trustPlatformKey();
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);
        String signatureOverDifferentBytes = sign(
                "not the artifact".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/artifact.bin", sha256(artifact), artifact.length,
                        signatureOverDifferentBytes, "platform-2026"), ".fyp"));
        assertTrue(error.getMessage().contains("signature verification failed"),
                error.getMessage());
    }

    @Test
    void rejectsUnknownOrRevokedSigningKey() throws Exception {
        trustPlatformKey();
        Files.writeString(temp.resolve("keys.json"), """
                {"keys":[{"id":"platform-2026","publicKey":"%s"}],
                 "revokedKeys":["platform-2026"]}
                """.formatted(platformKeyB64));
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        assertThrows(IllegalArgumentException.class, () -> client.download(
                ticket(base() + "/artifact.bin", sha256(artifact), artifact.length,
                        sign(artifact), "platform-2026"), ".fyp"));
        assertThrows(IllegalArgumentException.class, () -> client.download(
                ticket(base() + "/artifact.bin", sha256(artifact), artifact.length,
                        sign(artifact), "ghost-key"), ".fyp"));
    }

    @Test
    void stopsStreamingAtTheByteBudget() throws Exception {
        StoreClient client = client(false, 4096);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/stream.bin", "00", 0, null, null), ".fyp"));
        assertTrue(error.getMessage().contains("exceeds the download budget"),
                error.getMessage());
    }

    @Test
    void rejectsOverBudgetDeclaredSizeWithoutFetching() throws Exception {
        StoreClient client = client(false, 1024);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/artifact.bin", "00", Long.MAX_VALUE, null, null),
                ".fyp"));
        assertTrue(error.getMessage().contains("bytes declared"), error.getMessage());
    }

    @Test
    void plainHttpIsOnlyAllowedOnLoopback() {
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);
        // 8.8.8.8 is a public IP literal: no DNS lookup, no connection, just policy.
        DownloadTicket offLoopback = ticket("http://8.8.8.8/x.fyp", "00", 0, null, null);

        IOException error = assertThrows(IOException.class,
                () -> client.download(offLoopback, ".fyp"));
        assertTrue(error.getMessage().contains("loopback"), error.getMessage());
    }

    @Test
    void privateNetworkTargetsAreBlockedByDefault() throws Exception {
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket("https://192.168.1.5/x.fyp", "00", 0, null, null), ".fyp"));
        assertTrue(error.getMessage().contains("SSRF policy"), error.getMessage());
    }

    @Test
    void apiBasePolicyWarnsAtConstructionInsteadOfCrashing() {
        // A base unreachable under the launch posture must not kill the boot: the
        // Settings toggle can legalize it later, and the toggle's backing store is
        // not readable during bean construction. The hard check runs per request.
        assertDoesNotThrow(() -> new StoreClient("https://10.0.0.5",
                new StoreTrustStore(temp.resolve("keys.json")), true, false,
                StoreClient.MAX_DOWNLOAD_BYTES, StoreClient.MAX_JSON_BYTES));
        assertDoesNotThrow(() -> new StoreClient("http://store.example.invalid",
                new StoreTrustStore(temp.resolve("keys.json")), true, false,
                StoreClient.MAX_DOWNLOAD_BYTES, StoreClient.MAX_JSON_BYTES),
                "a policy-blocked base is a warning, not a boot failure");
    }

    @Test
    void jsonResponsesAreBounded() throws Exception {
        StoreClient client = client(true, StoreClient.MAX_DOWNLOAD_BYTES);

        IOException error = assertThrows(IOException.class,
                () -> client.browse(null, null, null, 60));
        assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
    }

    @Test
    void downloadRetriesExactlyOnceOnATransportFailure() throws Exception {
        // P3: one transient transport failure (body truncated mid-stream — connection reset,
        // CDN hiccup) is retried on a fresh temp file; the retry succeeds.
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/flaky.bin", exchange -> {
            if (hits.incrementAndGet() == 1) {
                // Promise the full body, deliver half, drop the connection.
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, artifact.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(artifact, 0, artifact.length / 2);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, artifact.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(artifact);
            }
        });
        StoreClient client = client(false, StoreClient.MAX_DOWNLOAD_BYTES);

        Path file = client.download(ticket(base() + "/flaky.bin",
                sha256(artifact), artifact.length, null, null), ".fyp");

        assertArrayEquals(artifact, Files.readAllBytes(file));
        assertEquals(2, hits.get(), "exactly one retry after the transport failure");
        Files.deleteIfExists(file);
    }

    @Test
    void terminalDownloadVerdictsAreNotRetried() throws Exception {
        // HTTP error statuses are deterministic: retrying cannot change them, so a single hit.
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/missing.bin", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        StoreClient client = client(false, StoreClient.MAX_DOWNLOAD_BYTES);

        IOException error = assertThrows(IOException.class, () -> client.download(
                ticket(base() + "/missing.bin", "00", 0, null, null), ".fyp"));
        assertTrue(error.getMessage().contains("HTTP 404"), error.getMessage());
        assertEquals(1, hits.get(), "an HTTP-status verdict must not be retried");
    }

    @Test
    void installEventsAreOnlySentWithABearerSession() throws Exception {
        // P2-17: without a cloud Bearer session the telemetry never leaves the host …
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/v1/install-events", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        StoreClient anonymous = client(false, StoreClient.MAX_DOWNLOAD_BYTES);
        var event = new StoreModels.InstallEvent("key-1", "infinia://plugin/official/x",
                "1.0.0", "PLUGIN", "install", "success", "4.0.0", "linux", "x64",
                "2026-09-07T00:00:00Z");

        assertFalse(anonymous.reportInstallEvents(java.util.List.of(event)));
        assertEquals(0, hits.get(), "no anonymous telemetry is ever sent");

        // … with a session it posts the batch silently; a store-side failure stays non-fatal.
        java.util.concurrent.atomic.AtomicReference<String> authHeader = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> receivedBody = new java.util.concurrent.atomic.AtomicReference<>();
        server.removeContext("/api/v1/install-events");
        server.createContext("/api/v1/install-events", exchange -> {
            hits.incrementAndGet();
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        StoreClient signedIn = client(false, StoreClient.MAX_DOWNLOAD_BYTES);
        signedIn.setTokenSupplier(() -> "bearer-token-1");

        assertTrue(signedIn.reportInstallEvents(java.util.List.of(event)));
        assertEquals(1, hits.get());
        assertEquals("Bearer bearer-token-1", authHeader.get());
        assertTrue(receivedBody.get().contains("\"action\":\"install\"")
                        && receivedBody.get().contains("infinia://plugin/official/x"),
                "the batched JSON carries the event fields: " + receivedBody.get());

        // A failing endpoint returns false instead of throwing — reporting never blocks installs.
        server.removeContext("/api/v1/install-events");
        server.createContext("/api/v1/install-events", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        assertFalse(signedIn.reportInstallEvents(java.util.List.of(event)));
    }
}
