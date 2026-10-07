package fan.summer.fengyu.ai.config;

import com.sun.net.httpserver.HttpServer;
import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.database.repository.AppSettingRepository;
import fan.summer.fengyu.security.NoopSecurityContext;
import fan.summer.fengyu.ai.AiConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-C1 fail-safe contract: 404 keeps the baseline (store not shipping the
 * resource yet), unsigned-over-HTTP is accepted (stage 1), a present-but-wrong
 * Ed25519 signature REFUSES the update, and a valid overlay actually shifts
 * catalog lookups (remote wins over the bundled baseline).
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class RemoteModelCatalogServiceTest {

    @Autowired private AppSettingRepository repo;

    private HttpServer server;
    private RemoteModelCatalogService service;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> signatureHeader = new AtomicReference<>(null);

    @BeforeEach
    void setUp() throws Exception {
        var sc = new NoopSecurityContext();
        AiConfigService cfg = new AiConfigService(repo, sc);
        cfg.init();
        AiConfigServiceHeadless h = new AiConfigServiceHeadless(repo, sc, cfg);
        var init = AiConfigServiceHeadless.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(h);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/model-catalog", exchange -> {
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            var headers = exchange.getResponseHeaders();
            if (signatureHeader.get() != null) headers.add("X-Store-Signature", signatureHeader.get());
            if (signatureHeader.get() != null) headers.add("X-Store-Key-Id", "test-key");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new RemoteModelCatalogService(base, null,
                java.net.http.HttpClient.newHttpClient());
        ModelMetadataCatalog.applyRemoteOverlay(null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        ModelMetadataCatalog.applyRemoteOverlay(null);
    }

    private static final String VALID_CATALOG = """
            {"revision": 7, "models": [
               {"id": "fengyu-future-model", "contextWindowTokens": 999000,
                "thinking": {"style": "reasoning_effort", "levels": ["off","high"], "defaultLevel": "high"}}
             ], "families": []}
            """;

    @Test
    void notFoundKeepsBaselineSilently() {
        status.set(404);
        String result = service.refresh();
        assertEquals("not-available", result);
        assertEquals(-1, service.cachedRevision());
    }

    @Test
    void unsignedFetchIsAcceptedAndShiftsLookups() {
        responseBody.set(VALID_CATALOG);
        signatureHeader.set(null);
        String result = service.refresh();
        assertTrue(result.startsWith("updated"), result);
        assertEquals(7, service.cachedRevision());
        // The overlay shifts catalog lookups: remote wins over the bundled baseline.
        assertTrue(ModelMetadataCatalog.thinkingFor("fengyu-future-model").isPresent());
        assertEquals("reasoning_effort",
                ModelMetadataCatalog.thinkingFor("fengyu-future-model").orElseThrow().style());
        assertEquals(Integer.valueOf(999000),
                ModelMetadataCatalog.contextWindow("fengyu-future-model").orElse(null));
    }

    @Test
    void malformedPayloadIsRefused() {
        responseBody.set("{not-json");
        assertEquals("error: invalid catalog payload", service.refresh());
        assertEquals(-1, service.cachedRevision());
        // A valid revision without array nodes is refused too.
        responseBody.set("{\"revision\": 3, \"models\": {}}");
        assertEquals("error: invalid catalog payload", service.refresh());
        // R3-P2: non-positive revisions are refused outright (only a monotonic,
        // positive sequence may ever replace the cache).
        responseBody.set(VALID_CATALOG.replace("\"revision\": 7", "\"revision\": 0"));
        assertEquals("error: invalid catalog payload", service.refresh());
        responseBody.set(VALID_CATALOG.replace("\"revision\": 7", "\"revision\": -2"));
        assertEquals("error: invalid catalog payload", service.refresh());
    }

    @Test
    void nonMonotonicRevisionIsSkipped() {
        responseBody.set(VALID_CATALOG); // revision 7
        service.refresh();
        assertEquals(7, service.cachedRevision());
        String older = VALID_CATALOG.replace("\"revision\": 7", "\"revision\": 3");
        responseBody.set(older);
        String result = service.refresh();
        assertTrue(result.startsWith("unchanged"), result);
        assertEquals(7, service.cachedRevision(), "a lower revision must not replace the cache");
    }

    @Test
    void wrongSignatureRefusesTheUpdate() {
        responseBody.set(VALID_CATALOG);
        // A present-but-garbage signature must refuse the payload outright.
        signatureHeader.set("bm90LWEtc2lnbmF0dXJl");
        String result = service.refresh();
        assertEquals("error: signature verification failed", result);
        assertEquals(-1, service.cachedRevision());
    }
}
