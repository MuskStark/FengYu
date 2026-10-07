package fan.summer.fengyu.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import fan.summer.fengyu.store.StoreClient;
import fan.summer.fengyu.store.StoreTrustStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;

/**
 * Batch-C1 remote model catalog: fetches a revision-stamped catalog overlay from the
 * store ({@code GET /api/v1/model-catalog}), caches it in one user-scoped setting,
 * and hands it to {@link ModelMetadataCatalog} as a lookup layer that wins over the
 * bundled baseline (same id/pattern ⇒ remote wins; new entries append).
 *
 * <p><b>Fail-safe by design (plan §6/D7):</b> the store does not serve the resource
 * yet ⇒ 404 keeps the bundled baseline silently, so this client ships in 4.1.0 and
 * activates the moment the store lands the endpoint — no client release needed.
 * Structural damage, a non-monotonic revision, or a FAILED signature (when the store
 * attaches one) also fall back to the last-known-good overlay/baseline; a refresh
 * failure never throws to a caller.
 *
 * <p><b>Signature posture (staged):</b> when the response carries
 * {@code X-Store-Signature} + {@code X-Store-Key-Id}, the body is verified with the
 * platform Ed25519 key via {@link StoreTrustStore} and a mismatch REFUSES the
 * update. Without headers (stage 1, HTTPS-only) the fetch is accepted; enforcement
 * becomes mandatory when the store ships the MODEL_CATALOG artifact type with
 * signatures on.
 */
@Component
public class RemoteModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(RemoteModelCatalogService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Cache setting: the last accepted remote catalog JSON (revision included). */
    static final String CACHE_KEY = "ai.model_catalog.remote";

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final java.util.function.Supplier<String> apiBase;
    private final StoreTrustStore trustStore;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public RemoteModelCatalogService(StoreClient storeClient,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StoreTrustStore trustStore) {
        this(storeClient::apiBase, trustStore,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /** Test seam: fixed base URL + trust store + client. */
    RemoteModelCatalogService(String apiBase, StoreTrustStore trustStore, HttpClient http) {
        this(() -> apiBase, trustStore, http);
    }

    private RemoteModelCatalogService(java.util.function.Supplier<String> apiBase,
            StoreTrustStore trustStore, HttpClient http) {
        this.apiBase = apiBase;
        this.trustStore = trustStore;
        this.http = http;
    }

    /**
     * Best-effort refresh: fetch → verify → accept if structurally valid and newer.
     * Returns a human-readable outcome for the settings UI; never throws.
     */
    public synchronized String refresh() {
        try {
            // Inside the try on purpose: base() runs the endpoint UrlPolicy and can
            // throw on a misconfigured posture — refresh() promises never to throw.
            String url = apiBase.get() + "/api/v1/model-catalog";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return "not-available"; // store has not shipped the resource yet — baseline stays
            }
            if (response.statusCode() != 200) {
                log.debug("Model catalog refresh: HTTP {} from {}", response.statusCode(), url);
                return "error: HTTP " + response.statusCode();
            }
            String body = response.body();
            if (!verifySignatureIfPresent(response, body)) {
                log.warn("Model catalog signature mismatch; refusing the update");
                return "error: signature verification failed";
            }
            String normalized = validateAndNormalize(body);
            if (normalized == null) return "error: invalid catalog payload";
            int incoming = revisionOf(body);
            int cached = revisionOf(cachedJson());
            if (incoming <= cached) {
                return "unchanged (revision " + incoming + ")";
            }
            AiConfigServiceHeadless.persistRawSetting(CACHE_KEY, normalized);
            ModelMetadataCatalog.applyRemoteOverlay(normalized);
            log.info("Model catalog overlay updated to revision {}", incoming);
            return "updated (revision " + incoming + ")";
        } catch (Exception e) {
            log.debug("Model catalog refresh failed: {}", e.toString());
            return "error: " + e.getClass().getSimpleName();
        }
    }

    /** Loads the cached overlay (if any) into the catalog — startup wiring. */
    public void loadCachedIntoCatalog() {
        String cached = cachedJson();
        if (cached != null && revisionOf(cached) > 0) {
            ModelMetadataCatalog.applyRemoteOverlay(cached);
        }
    }

    /** The cached overlay's revision, or -1 when none is cached. */
    public int cachedRevision() {
        return revisionOf(cachedJson());
    }

    private String cachedJson() {
        return AiConfigServiceHeadless.readRawSetting(CACHE_KEY);
    }

    private boolean verifySignatureIfPresent(HttpResponse<String> response, String body) {
        String signature = response.headers().firstValue("X-Store-Signature").orElse(null);
        String keyId = response.headers().firstValue("X-Store-Key-Id").orElse(null);
        if (signature == null || keyId == null) return true; // stage 1: HTTPS-only
        if (trustStore == null) return false;
        try {
            java.security.PublicKey key = trustStore.verificationKey(keyId);
            if (key == null) return false;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(body.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Structural validation: must parse, carry a positive numeric {@code revision},
     * and its {@code models}/{@code families} nodes must be arrays whose entries
     * carry usable ids/patterns. Returns re-serialized normalized JSON (canonical
     * cache bytes) or null.
     */
    private String validateAndNormalize(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode revision = root.get("revision");
            if (revision == null || !revision.canConvertToInt() || revision.asInt() <= 0) return null;
            if (!root.path("models").isArray() && !root.path("families").isArray()) return null;
            for (JsonNode m : root.path("models")) {
                JsonNode id = m.get("id");
                if (id == null || !id.isTextual() || id.asText().isBlank()) return null;
            }
            for (JsonNode f : root.path("families")) {
                JsonNode pattern = f.get("pattern");
                if (pattern == null || !pattern.isTextual() || pattern.asText().isBlank()) return null;
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return null;
        }
    }

    private int revisionOf(String json) {
        if (json == null || json.isBlank()) return -1;
        try {
            JsonNode revision = JSON.readTree(json).get("revision");
            return revision != null && revision.canConvertToInt() ? revision.asInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
