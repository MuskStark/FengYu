package fan.summer.fengyu.plugin.offlinepython.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fan.summer.fengyu.plugin.offlinepython.domain.PlatformCatalog;
import fan.summer.fengyu.plugin.offlinepython.infra.ProcessRunner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency lookups for the DepsPanel: latest version via `pip index versions`,
 * and best-effort wheel size via the PyPI JSON API. Pure parsers are unit-tested;
 * the live calls are thin wrappers.
 */
public final class DepsService {

    private static final Pattern FIRST_VERSION = Pattern.compile("\\(([0-9][^)]*)\\)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Public no-arg ctor: DepsPanel instantiates this stateless service. */
    public DepsService() {}

    /** Run `pip index versions <pkg>`, return the latest version, or empty. */
    public Optional<String> latestVersion(String pkg, String pythonExe) {
        String out = ProcessRunner.captureQuiet(pythonExe, "-m", "pip", "index", "versions", pkg);
        return parseLatestVersion(out);
    }

    /** Best-effort wheel size (bytes) for pkg@versionSpec on platform; 0 if unknown. */
    public long fetchSizeBytes(String pkg, String versionSpec, String platform) {
        String ver = versionSpec == null ? "" : versionSpec.replaceAll("[<>=!~]", "").trim();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://pypi.org/pypi/" + pkg + "/json"))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return parsePyPIWheelSize(resp.body(), platform, ver);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** `pip index versions numpy` → "numpy (1.26.4)\n..." → "1.26.4". */
    public static Optional<String> parseLatestVersion(String out) {
        if (out == null || out.isBlank()) return Optional.empty();
        Matcher m = FIRST_VERSION.matcher(out);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }

    /** Extract the size of the wheel matching platform (and version if given) from PyPI JSON; 0 if none. */
    public static long parsePyPIWheelSize(String json, String platform, String version) {
        if (json == null || json.isBlank()) return 0L;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Iterable<JsonElement> urls = root.has("urls")
                    ? root.getAsJsonArray("urls")
                    : () -> root.entrySet().stream().map(java.util.Map.Entry::getValue).iterator();
            long fallback = 0L;
            for (JsonElement e : urls) {
                JsonObject o = e.getAsJsonObject();
                String fn = o.get("filename").getAsString();
                long size = o.get("size").getAsLong();
                if (!fn.endsWith(".whl")) continue;
                if (version != null && !version.isBlank() && !fn.contains(version)) continue;
                if (platform != null && !platform.isBlank() && fn.contains(platform)) return size;
                if (fallback == 0L) fallback = size;
            }
            return fallback;
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Fetch all wheels for pkg from PyPI JSON (across releases). Empty on any failure. */
    public List<WheelInfo> searchWheels(String pkg) {
        if (pkg == null || pkg.isBlank()) return List.of();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://pypi.org/pypi/" + pkg.trim() + "/json"))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return parseWheels(resp.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Parse PyPI JSON into wheel entries (version/platform/size/filename). Caps at 50. */
    public static List<WheelInfo> parseWheels(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<WheelInfo> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("releases") || !root.get("releases").isJsonObject()) return List.of();
            JsonObject releases = root.getAsJsonObject("releases");
            for (String ver : releases.keySet()) {
                JsonElement arrEl = releases.get(ver);
                if (arrEl == null || !arrEl.isJsonArray()) continue;
                for (JsonElement e : arrEl.getAsJsonArray()) {
                    JsonObject o = e.getAsJsonObject();
                    String fn = o.has("filename") ? o.get("filename").getAsString() : "";
                    if (!fn.endsWith(".whl")) continue; // 跳过 sdist
                    long size = o.has("size") ? o.get("size").getAsLong() : 0L;
                    out.add(new WheelInfo(ver, extractPlatformTag(fn), size, fn));
                }
                if (out.size() >= 50) break;
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** Extract the platform tag (PEP 427 末段) from a wheel filename; map to a catalog tag if exact match. */
    public static String extractPlatformTag(String filename) {
        if (filename == null) return "";
        String core = filename.endsWith(".whl")
                ? filename.substring(0, filename.length() - 4) : filename;
        String[] parts = core.split("-");
        String raw = parts.length >= 1 ? parts[parts.length - 1] : core;
        for (PlatformCatalog.Entry e : PlatformCatalog.ALL) {
            if (raw.equals(e.tag())) return e.tag();
        }
        return raw;
    }
}
