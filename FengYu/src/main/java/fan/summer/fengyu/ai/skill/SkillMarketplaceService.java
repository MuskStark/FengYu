package fan.summer.fengyu.ai.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.plugin.market.SemanticVersion;
import fan.summer.fengyu.store.StoreTrustStore;
import fan.summer.fengyu.store.UrlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the configured skill catalog and merges it with skills installed on this host.
 *
 * <p>Remote-catalog fetch, remote/local merge and version comparison follow the
 * host's catalog conventions. The catalog URL comes from
 * {@code fengyu.skills.catalog-url} (default empty → no remote, degrades to
 * local-installed only).
 *
 * <p>Trust chain (review M-6): skill guidance enters the AI's prompt context, so
 * remote artifacts are at least as sensitive as plugins. Catalog and download
 * URLs go through the shared {@link UrlPolicy} (HTTPS except loopback, private
 * networks blocked), catalog responses are size- and entry-capped, and every
 * downloaded {@code .fys} must carry an attested SHA-256 plus a platform
 * Ed25519 signature from a key in the store trust registry before it reaches
 * the installer — which additionally refuses builtin-id collisions and
 * untrustworthy official claims. {@link #install(String)} and update share one
 * path (re-fetch catalog → find entry → verified download → trusted install).
 *
 * @since 4.0.0
 */
@Service
public class SkillMarketplaceService {

    private static final long MAX_CATALOG_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CATALOG_ENTRIES = 2000;
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

    private final ObjectMapper json;
    private final SkillPackageService packages;
    private final StoreTrustStore trust;
    private final String catalogUrl;
    private final boolean requireSignature;
    private final HttpClient http;

    public SkillMarketplaceService(SkillPackageService packages, StoreTrustStore trust,
            @Value("${fengyu.skills.catalog-url:}") String catalogUrl,
            @Value("${fengyu.store.require-signature:true}") boolean requireSignature) {
        this.json = JsonMapper.builder().findAndAddModules().build();
        this.packages = packages;
        this.trust = trust;
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.trim();
        this.requireSignature = requireSignature;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Merges the remote catalog with locally-installed skills, sorted by name (case-insensitive). */
    public List<MarketplaceSkill> list() {
        Map<String, SkillCatalogEntry> catalog = new LinkedHashMap<>();
        for (SkillCatalogEntry entry : fetchCatalog()) {
            if (entry.id() != null && !entry.id().isBlank()) catalog.put(entry.id(), entry);
        }
        Map<String, SkillManifest> installed = new LinkedHashMap<>();
        for (SkillManifest manifest : packages.installed()) installed.put(manifest.id(), manifest);

        List<MarketplaceSkill> result = new ArrayList<>();
        for (SkillCatalogEntry entry : catalog.values()) {
            SkillManifest local = installed.remove(entry.id());
            result.add(toView(entry, local));
        }
        for (SkillManifest local : installed.values()) result.add(toView(null, local));
        return result.stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    /** Install (or update) a skill by id from the configured catalog. */
    public SkillManifest install(String id) throws IOException, InterruptedException {
        SkillCatalogEntry entry = fetchCatalog().stream()
            .filter(item -> item.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Skill is not present in the configured catalog: " + id));
        if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) {
            throw new IllegalArgumentException("Catalog entry has no download URL: " + id);
        }
        return installVerified(entry);
    }

    private List<SkillCatalogEntry> fetchCatalog() {
        if (catalogUrl.isBlank()) return List.of();
        try {
            URI uri = URI.create(catalogUrl);
            UrlPolicy.requireTraversable(uri, false);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Skill marketplace catalog returned HTTP "
                        + response.statusCode());
            }
            List<SkillCatalogEntry> entries = json.readValue(
                    boundedRead(response.body()), new TypeReference<>() {});
            // Hostile-catalog hygiene: drop unusable rows and cap the lot.
            return entries == null ? List.of() : entries.stream()
                    .filter(e -> e != null && e.id() != null && !e.id().isBlank()
                            && e.name() != null && !e.name().isBlank())
                    .limit(MAX_CATALOG_ENTRIES)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read skill marketplace catalog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Skill marketplace catalog request was interrupted", e);
        }
    }

    private static String boundedRead(InputStream body) throws IOException {
        try (body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = body.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_CATALOG_BYTES) {
                    throw new IOException("Skill marketplace catalog exceeds "
                            + MAX_CATALOG_BYTES + " bytes");
                }
                out.write(buffer, 0, count);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * Downloads the entry's {@code .fys} with the same trust chain as store
     * artifacts: mandatory SHA-256, platform Ed25519 signature over the exact
     * bytes, streaming size cap — then hands the verified archive to the
     * trusted installer path.
     */
    private SkillManifest installVerified(SkillCatalogEntry entry)
            throws IOException, InterruptedException {
        if (entry.sha256() == null || entry.sha256().isBlank()) {
            throw new IllegalArgumentException("Catalog entry carries no SHA-256; "
                    + "refusing an unattested skill download: " + entry.id());
        }
        if (requireSignature
                && (isBlank(entry.keyId()) || isBlank(entry.signature()))) {
            throw new IllegalArgumentException("Catalog entry is not signed (keyId or "
                    + "signature missing); refusing an unverified skill: " + entry.id());
        }
        URI uri = URI.create(entry.downloadUrl());
        UrlPolicy.requireTraversable(uri, false);
        Signature signature = null;
        if (!isBlank(entry.keyId())) {
            PublicKey key = trust.verificationKey(entry.keyId());
            try {
                signature = Signature.getInstance("Ed25519");
                signature.initVerify(key);
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot verify a skill signature", e);
            }
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2)).GET().build();
        Path archive = Files.createTempFile("fengyu-skill-", ".fys");
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("Skill download failed with HTTP "
                        + response.statusCode());
            }
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream body = response.body();
                    OutputStream out = Files.newOutputStream(archive)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = body.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw new IllegalArgumentException("Skill download exceeds "
                                + "10 MB");
                    }
                    digest.update(buffer, 0, count);
                    if (signature != null) {
                        try {
                            signature.update(buffer, 0, count);
                        } catch (GeneralSecurityException e) {
                            throw new IOException("Cannot verify a skill signature", e);
                        }
                    }
                    out.write(buffer, 0, count);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(entry.sha256())) {
                throw new IllegalArgumentException("Skill package integrity check "
                        + "failed: expected " + entry.sha256() + " but downloaded "
                        + actual);
            }
            if (signature != null) {
                boolean verified;
                try {
                    verified = signature.verify(
                            Base64.getDecoder().decode(entry.signature()));
                } catch (IllegalArgumentException badBase64) {
                    throw new IllegalArgumentException(
                            "Skill signature is not valid base64");
                } catch (GeneralSecurityException e) {
                    throw new IOException("Skill signature verification failed", e);
                }
                if (!verified) {
                    throw new IllegalArgumentException("Skill signature verification "
                            + "failed (key " + entry.keyId() + ")");
                }
            }
            return packages.installTrusted(archive);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private MarketplaceSkill toView(SkillCatalogEntry remote, SkillManifest local) {
        boolean installed = local != null;
        String id = installed ? local.id() : remote.id();
        String available = remote != null ? remote.version() : local.version();
        return new MarketplaceSkill(
            id,
            remote != null ? remote.name() : local.name(),
            remote != null ? remote.description() : local.description(),
            available,
            installed ? local.version() : null,
            remote != null ? remote.author() : local.author(),
            remote != null ? remote.icon() : local.icon(),
            remote != null ? remote.homepage() : local.homepage(),
            remote != null ? remote.downloadUrl() : null,
            // Official identity is signature-anchored (M-6): the catalog's claim
            // only displays when the entry's keyId actually verifies.
            remote != null ? remote.official() && isTrustedSigningKey(remote.keyId())
                    : local.official(),
            installed,
            installed && packages.isEnabled(id),
            installed && remote != null && remote.version() != null
                    && local.version() != null
                    && compareVersions(remote.version(), local.version()) > 0
        );
    }

    private boolean isTrustedSigningKey(String keyId) {
        if (isBlank(keyId)) {
            return false;
        }
        try {
            trust.verificationKey(keyId);
            return true;
        } catch (IllegalArgumentException untrusted) {
            return false;
        }
    }

    /**
     * SemVer-aware comparison (M-6): prerelease precedence must order correctly
     * (beta.10 &gt; beta.2, rc after beta); non-SemVer data degrades to a
     * case-insensitive string comparison.
     */
    static int compareVersions(String left, String right) {
        try {
            return SemanticVersion.compare(left, right);
        } catch (IllegalArgumentException notSemVer) {
            return String.CASE_INSENSITIVE_ORDER.compare(
                    left == null ? "" : left, right == null ? "" : right);
        }
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
