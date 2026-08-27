package fan.summer.fengyu.store;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import fan.summer.fengyu.store.StoreModels.CatalogPage;
import fan.summer.fengyu.store.StoreModels.DownloadTicket;
import fan.summer.fengyu.store.StoreModels.ListingDetail;
import fan.summer.fengyu.store.StoreModels.ResolveResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Server-side client for the Infinia Store Platform (design §4.1: the host calls
 * the store over outbound HTTPS only — never the other way around). Anonymous
 * access covers catalog, listing, resolution and ticketed downloads; the store
 * stays a content plane, not a local authority.
 */
@Service
public class StoreClient {

    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private StoreBearerTokenSupplier tokenSupplier;

    public StoreClient(@Value("${fengyu.store.api-base:http://localhost:8080}") String apiBase) {
        this.apiBase = normalize(apiBase);
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
            url.append("&type=").append(type.trim().toUpperCase());
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
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                        URI.create(apiBase + "/api/v1/resolutions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload)));
        authorize(requestBuilder);
        HttpResponse<String> response =
                http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        require2xx(response, "resolve");
        return mapper.readValue(response.body(), ResolveResponse.class);
    }

    /** POST /api/v1/releases/{id}/download-ticket — short-lived signed URL. */
    public DownloadTicket ticket(String releaseId) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                        URI.create(apiBase + "/api/v1/releases/" + releaseId + "/download-ticket"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody());
        authorize(requestBuilder);
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        require2xx(response, "download-ticket");
        return mapper.readValue(response.body(), DownloadTicket.class);
    }

    /**
     * Downloads the ticketed artifact to a temp file and verifies the store-attested
     * SHA-256 over the exact bytes (design §8.3: hash for integrity on every fetch).
     */
    public Path download(DownloadTicket ticket, String suffix)
            throws IOException, InterruptedException {
        String url = ticket.url().startsWith("http") ? ticket.url() : apiBase + ticket.url();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .GET().build();
        Path target = Files.createTempFile("infinia-store-", suffix);
        try {
            HttpResponse<Path> response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Store download failed: HTTP " + response.statusCode());
            }
            if (Files.size(target) > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Store artifact exceeds the download budget");
            }
            if (ticket.sha256() != null && !ticket.sha256().isBlank()) {
                String actual = sha256(target);
                if (!actual.equalsIgnoreCase(ticket.sha256())) {
                    throw new IOException("Store artifact integrity check failed: expected "
                            + ticket.sha256() + " but downloaded " + actual);
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

    // ---- helpers ----

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        authorize(builder);
        HttpRequest request = builder.build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        require2xx(response, "GET " + url);
        return response.body();
    }

    private static void require2xx(HttpResponse<?> response, String what) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Store " + what + " failed: HTTP " + response.statusCode());
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
