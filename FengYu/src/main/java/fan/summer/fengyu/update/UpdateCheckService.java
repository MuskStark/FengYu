package fan.summer.fengyu.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fan.summer.fengyu.ai.service.AiConfigServiceHeadless;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Checks the configured GitHub repository's latest release against the running build's version
 * and reports whether an update is available. Used by portable Web and browser deployments via
 * {@code GET /api/updates/check}; the desktop shell owns both GitHub and FY-Proxy checks.
 *
 * <p>The actual download + install is mode-specific: the desktop shell uses electron-updater
 * against {@code latest*.yml}, while the portable/{@code java -jar} deployment uses
 * {@link SelfUpdateService} to swap the JAR via a detached restart script. This service only
 * answers "is there something newer, and where is it?".
 */
@Service
public class UpdateCheckService {
    /** System property set only by the portable launcher scripts (run.sh/run.bat). */
    static final String PORTABLE_PROPERTY = "fengyu.update.portable";

    private static final String PORTABLE_ASSET_NAME = "Infinia.jar";
    private static final int RELEASE_NOTES_MAX = 4000;

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http;
    private final String repo;
    private final String apiBase;
    private final long cacheTtlSeconds;

    private volatile Cached cached;

    public UpdateCheckService(
            @Value("${fengyu.updates.repo:MuskStark/FengYu}") String repo,
            @Value("${fengyu.updates.api-base:}") String apiBase,
            @Value("${fengyu.updates.cache-ttl-seconds:600}") long cacheTtlSeconds) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.repo = repo == null ? "MuskStark/FengYu" : repo.trim();
        // 内网镜像地址（如 http://10.0.0.5:8088）。默认空 → 走 GitHub。
        // 配了就只走该地址（内网机器不连 GitHub），避免每次检查都等 GitHub 超时。
        this.apiBase = apiBase == null ? "" : apiBase.trim().replaceAll("/+$", "");
        this.cacheTtlSeconds = cacheTtlSeconds <= 0 ? 600 : cacheTtlSeconds;
    }

    /**
     * The running build's version, read from the shaded JAR manifest
     * ({@code Implementation-Version}). Falls back to {@code "Dev"} in IDE/classpath runs where
     * the manifest attribute is absent.
     */
    public String currentVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "Dev" : version;
    }

    /**
     * {@code true} when this JVM is a portable/{@code java -jar} deployment that owns its own JAR
     * (can self-update). The desktop Electron sidecar does NOT set the {@code fengyu.update.portable}
     * property, so it returns {@code false} — the shell owns updates via electron-updater.
     */
    public boolean isPortableMode() {
        return Boolean.parseBoolean(System.getProperty(PORTABLE_PROPERTY));
    }

    /**
     * Check the latest release, subject to a simple in-memory TTL cache (so concurrent callers
     * and polling don't hammer the GitHub API and hit rate limits). Pass {@code force=true} to
     * bypass the cache (e.g. a user-initiated "check again" click).
     */
    public UpdateInfo check(boolean force) {
        Cached snapshot = cached;
        if (!force && snapshot != null && snapshot.isValid(cacheTtlSeconds)) {
            return snapshot.info;
        }
        UpdateInfo info = fetchLatest();
        cached = new Cached(info, Instant.now());
        return info;
    }

    private UpdateInfo fetchLatest() {
        // FY-Proxy deliberately distributes only desktop Windows-portable/deb packages. Desktop
        // requests never reach this backend service; they use Electron IPC. Reject a custom base
        // here so portable Web cannot report an update whose required Infinia.jar is unavailable.
        String configuredBase = AiConfigServiceHeadless.getUpdateApiBase(this.apiBase);
        if (!configuredBase.isBlank()) {
            throw new IllegalStateException(
                    "The FY-Proxy update channel supports only Electron Windows portable ZIP and Debian packages");
        }
        String url = "https://api.github.com/repos/" + repo + "/releases?per_page=1";
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "FengYu-Updater")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Release check returned HTTP " + status);
            }
            JsonNode root = json.readTree(response.body());
            // GitHub 返回数组 [release]。
            JsonNode release = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
            if (release == null || release.isNull()) {
                throw new IllegalStateException("No releases available");
            }
            return parse(release);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read latest release from GitHub", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Release check was interrupted", e);
        }
    }

    private UpdateInfo parse(JsonNode release) {
        String tagName = text(release, "tag_name");
        String latest = stripLeadingV(tagName);
        String current = currentVersion();
        String downloadAssetUrl = findAssetUrl(release);
        return new UpdateInfo(
                current,
                latest,
                compareAppVersions(latest, current) > 0,
                text(release, "html_url"),
                text(release, "name"),
                text(release, "published_at"),
                release.path("prerelease").asBoolean(false),
                truncate(text(release, "body")),
                isPortableMode(),
                downloadAssetUrl
        );
    }

    private String findAssetUrl(JsonNode release) {
        JsonNode assets = release.path("assets");
        if (!assets.isArray()) return null;
        for (JsonNode asset : assets) {
            if (PORTABLE_ASSET_NAME.equals(text(asset, "name"))) {
                return text(asset, "browser_download_url");
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= RELEASE_NOTES_MAX ? value : value.substring(0, RELEASE_NOTES_MAX) + "…";
    }

    private static String stripLeadingV(String tag) {
        if (tag == null || tag.isBlank()) return "";
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /**
     * App-semantic version comparison. App versions are {@code MAJOR.MINOR.PATCH} optionally
     * followed by {@code -alpha.N} / {@code -beta.N} / {@code -rc.N} (see
     * {@code scripts/resolve-release-version.mjs}). Pre-release ordering is
     * {@code alpha < beta < rc < release}, so {@code 4.0.0-beta.2 < 4.0.0-rc.1 < 4.0.0}.
     *
     * <p>This is intentionally NOT a copy of {@code PluginMarketplaceService.compareVersions} —
     * that one falls back to {@code String.compareTo} for the suffix, which would order
     * {@code 4.0.0-beta.2} AFTER {@code 4.0.0} (wrong for app releases).
     *
     * @return {@code >0} if {@code left} is newer, {@code 0} if equal, {@code <0} if {@code right} is newer
     */
    static int compareAppVersions(String left, String right) {
        int[] a = numeric(left);
        int[] b = numeric(right);
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return comparePreRelease(preReleaseLabel(left), preReleaseNumber(left),
                                 preReleaseLabel(right), preReleaseNumber(right));
    }

    private static int[] numeric(String version) {
        int[] out = new int[3];
        if (version == null || version.isBlank()) return out;
        String core = version.split("[+]", 2)[0].split("-", 2)[0];
        String[] parts = core.split("\\.");
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Lowercase pre-release label ({@code "alpha"/"beta"/"rc"}) or empty string for a release. */
    private static String preReleaseLabel(String version) {
        if (version == null) return "";
        int dash = version.indexOf('-');
        if (dash < 0) return "";
        String suffix = version.substring(dash + 1);
        int dot = suffix.indexOf('.');
        return (dot < 0 ? suffix : suffix.substring(0, dot)).toLowerCase();
    }

    private static int preReleaseNumber(String version) {
        if (version == null) return 0;
        int dash = version.indexOf('-');
        if (dash < 0) return 0;
        String suffix = version.substring(dash + 1);
        int dot = suffix.indexOf('.');
        if (dot < 0) return 0;
        try {
            return Integer.parseInt(suffix.substring(dot + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Pre-release rank: release (no suffix) ranks highest. Returns the comparison result.
     * A release (label {@code ""}) beats any pre-release; among pre-releases,
     * {@code alpha < beta < rc}, tie-broken by the trailing number.
     */
    private static int comparePreRelease(String labelA, int numA, String labelB, int numB) {
        int rankA = preReleaseRank(labelA);
        int rankB = preReleaseRank(labelB);
        if (rankA != rankB) return Integer.compare(rankA, rankB);
        return Integer.compare(numA, numB);
    }

    private static int preReleaseRank(String label) {
        if (label == null || label.isEmpty()) return 4; // release
        return switch (label) {
            case "alpha" -> 1;
            case "beta" -> 2;
            case "rc" -> 3;
            default -> 0; // unknown suffix — treat as older than anything recognizable
        };
    }

    private record Cached(UpdateInfo info, Instant fetchedAt) {
        boolean isValid(long ttlSeconds) {
            return Instant.now().isBefore(fetchedAt.plusSeconds(ttlSeconds));
        }
    }
}
