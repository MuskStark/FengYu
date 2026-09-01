package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.store.StoreModels.CatalogPage;
import fan.summer.fengyu.store.StoreModels.DownloadTicket;
import fan.summer.fengyu.store.StoreModels.ListingDetail;
import fan.summer.fengyu.store.StoreModels.ResolveResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side client for the Infinia Store Platform (design §4.1: the host calls
 * the store over outbound HTTPS only — never the other way around). Anonymous
 * access covers catalog, listing, resolution and ticketed downloads; the store
 * stays a content plane, not a local authority.
 *
 * <p>Trust chain (design §8.3 / §13.1, review M-4): every request URL must be
 * HTTPS (plain HTTP only on loopback, for local development) and must not
 * resolve into a private/link-local network; downloads stream through a byte
 * budget with the SHA-256 digest computed on the fly (mandatory — a ticket
 * without an attested hash is refused), and the platform Ed25519 signature is
 * verified over the exact bytes before the artifact is handed to an installer.
 */
@Service
public class StoreClient {

    static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    static final long MAX_JSON_BYTES = 2L * 1024 * 1024;

    private final String apiBase;
    private final StoreTrustStore trust;
    private final boolean requireSignature;
    private final boolean allowPrivateNetwork;
    private final long maxDownloadBytes;
    private final long maxJsonBytes;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private StoreBearerTokenSupplier tokenSupplier;

    @Autowired
    public StoreClient(@Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase,
            StoreTrustStore trust,
            @Value("${fengyu.store.require-signature:true}") boolean requireSignature,
            @Value("${fengyu.store.allow-private-network:false}") boolean allowPrivateNetwork) {
        this(apiBase, trust, requireSignature, allowPrivateNetwork,
                MAX_DOWNLOAD_BYTES, MAX_JSON_BYTES);
    }

    /** Test seam: explicit limits. */
    StoreClient(String apiBase, StoreTrustStore trust, boolean requireSignature,
            boolean allowPrivateNetwork, long maxDownloadBytes, long maxJsonBytes) {
        this.apiBase = normalize(apiBase);
        this.trust = trust;
        this.requireSignature = requireSignature;
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.maxDownloadBytes = maxDownloadBytes;
        this.maxJsonBytes = maxJsonBytes;
        try {
            // Fail fast on a misconfigured base instead of on the first request.
            UrlPolicy.requireTraversable(URI.create(this.apiBase + "/"),
                    allowPrivateNetwork);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Invalid store API base " + this.apiBase + ": " + e.getMessage(), e);
        }
    }

    /** Optional bearer token for authenticated calls when a cloud account is signed in. */
    @Autowired(required = false)
    public void setTokenSupplier(@Nullable StoreBearerTokenSupplier tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    private void authorize(HttpRequest.Builder builder) {
        if (tokenSupplier != null) {
            String token = tokenSupplier.accessToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
    }

    private static String normalize(String base) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String apiBase() {
        return apiBase;
    }

    /** GET /api/v1/catalog — anonymous browse with type/text filters. */
    public CatalogPage browse(String type, String query, String cursor, int limit)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(apiBase + "/api/v1/catalog?limit=" + limit);
        if (type != null && !type.isBlank()) {
            url.append("&type=").append(type.trim().toUpperCase(Locale.ROOT));
        }
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(java.net.URLEncoder.encode(query.trim(),
                    StandardCharsets.UTF_8));
        }
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(java.net.URLEncoder.encode(cursor,
                    StandardCharsets.UTF_8));
        }
        return mapper.readValue(getJson(url.toString()), CatalogPage.class);
    }

    /** GET /api/v1/listings/{namespace}/{slug} — detail with visible releases. */
    public ListingDetail listing(String namespace, String slug)
            throws IOException, InterruptedException {
        String url = apiBase + "/api/v1/listings/"
                + java.net.URLEncoder.encode(namespace, StandardCharsets.UTF_8) + "/"
                + java.net.URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return mapper.readValue(getJson(url), ListingDetail.class);
    }

    /** POST /api/v1/resolutions — version + dependency closure for this host. */
    public ResolveResponse resolve(String coordinate, String hostVersion, String os,
            String arch, Map<String, String> installed)
            throws IOException, InterruptedException {
        var payload = mapper.createObjectNode();
        payload.put("coordinate", coordinate);
        var client = payload.putObject("client");
        client.put("hostVersion", hostVersion);
        client.put("os", os);
        client.put("arch", arch);
        client.put("channel", "stable");
        var installedArray = client.putArray("installed");
        installed.forEach((id, version) -> {
            var row = installedArray.addObject();
            row.put("coordinate", id);
            row.put("version", version);
        });
        return mapper.readValue(postJson(apiBase + "/api/v1/resolutions",
                mapper.writeValueAsString(payload)), ResolveResponse.class);
    }

    /** POST /api/v1/releases/{id}/download-ticket — short-lived signed URL. */
    public DownloadTicket ticket(String releaseId) throws IOException, InterruptedException {
        return mapper.readValue(postJson(
                apiBase + "/api/v1/releases/" + releaseId + "/download-ticket", null),
                DownloadTicket.class);
    }

    /**
     * Downloads the ticketed artifact to a temp file, streaming through the byte
     * budget: the store-attested SHA-256 is mandatory and computed on the fly,
     * the platform Ed25519 signature (when the ticket carries one) verifies over
     * the exact bytes, and nothing above the budget ever reaches the disk in
     * full (design §8.3: hash for integrity on every fetch, §13.1 SSRF policy).
     */
    public Path download(DownloadTicket ticket, String suffix)
            throws IOException, InterruptedException {
        URI uri = ticketUri(ticket);
        if (ticket.sha256() == null || ticket.sha256().isBlank()) {
            throw new IOException("Store ticket carries no SHA-256; refusing an "
                    + "unattested download");
        }
        if (requireSignature
                && (isBlank(ticket.keyId()) || isBlank(ticket.signature()))) {
            throw new IOException("Store ticket is not platform-signed (keyId or "
                    + "signature missing); refusing an unverified artifact");
        }
        Signature signature = null;
        String keyId = null;
        if (!isBlank(ticket.keyId())) {
            keyId = ticket.keyId();
            PublicKey key = trust.verificationKey(keyId);
            try {
                signature = Signature.getInstance("Ed25519");
                signature.initVerify(key);
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot verify a store signature", e);
            }
        }
        if (ticket.size() > maxDownloadBytes) {
            throw new IOException("Store artifact exceeds the download budget ("
                    + ticket.size() + " bytes declared)");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .GET().build();
        Path target = Files.createTempFile("infinia-store-", suffix);
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Store download failed: HTTP "
                        + response.statusCode());
            }
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream body = response.body();
                    OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = body.read(buffer)) >= 0) {
                    total += count;
                    if (total > maxDownloadBytes) {
                        throw new IOException("Store artifact exceeds the download "
                                + "budget");
                    }
                    digest.update(buffer, 0, count);
                    if (signature != null) {
                        try {
                            signature.update(buffer, 0, count);
                        } catch (GeneralSecurityException e) {
                            throw new IOException("Cannot verify a store signature", e);
                        }
                    }
                    out.write(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(ticket.sha256())) {
                throw new IOException("Store artifact integrity check failed: expected "
                        + ticket.sha256() + " but downloaded " + actual);
            }
            if (signature != null) {
                boolean verified;
                try {
                    verified = signature.verify(
                            Base64.getDecoder().decode(ticket.signature()));
                } catch (IllegalArgumentException badBase64) {
                    throw new IOException("Store signature is not valid base64");
                } catch (GeneralSecurityException e) {
                    throw new IOException("Store signature verification failed", e);
                }
                if (!verified) {
                    throw new IOException("Store artifact signature verification "
                            + "failed (key " + keyId + ")");
                }
            }
            return target;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    /** Raw bytes variant for JSON artifacts (MCP templates). */
    public byte[] downloadBytes(DownloadTicket ticket)
            throws IOException, InterruptedException {
        Path file = download(ticket, ".json");
        try {
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Parses the MCP template into the {url, headers} server definition. */
    public JsonNode parseMcpTemplate(byte[] templateBytes) throws IOException {
        return mapper.readTree(templateBytes);
    }

    public JsonMapper mapper() {
        return mapper;
    }

    // ---- request helpers ----

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        authorize(builder);
        HttpResponse<InputStream> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        require2xx(response, "GET " + UrlPolicy.describe(URI.create(url)));
        return boundedRead(response.body(), maxJsonBytes, url);
    }

    private String postJson(String url, @Nullable String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (jsonBody == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        authorize(builder);
        HttpResponse<InputStream> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        require2xx(response, "POST " + UrlPolicy.describe(URI.create(url)));
        return boundedRead(response.body(), maxJsonBytes, url);
    }

    private static String boundedRead(InputStream body, long limit, String what)
            throws IOException {
        try (body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = body.read(buffer)) >= 0) {
                total += count;
                if (total > limit) {
                    throw new IOException("Store response exceeds " + limit
                            + " bytes: " + what);
                }
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void require2xx(HttpResponse<?> response, String what)
            throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Store " + what + " failed: HTTP "
                    + response.statusCode());
        }
    }

    // ---- URL / SSRF policy ----

    private URI ticketUri(DownloadTicket ticket) throws IOException {
        if (ticket == null || ticket.url() == null || ticket.url().isBlank()) {
            throw new IOException("Store ticket carries no download URL");
        }
        String raw = ticket.url().startsWith("http") ? ticket.url() : apiBase + ticket.url();
        URI uri = URI.create(raw);
        UrlPolicy.requireTraversable(uri, allowPrivateNetwork);
        return uri;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
