package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts paths explicitly supplied by the chat user into the same plugin-scoped {@code FileRef}
 * grants used by the file picker. Workers still receive a real path only after
 * {@link fan.summer.fengyu.plugin.runtime.PluginProcessManager} resolves the opaque reference.
 */
@Service
public class ChatFileGrantService {

    private static final Pattern QUOTED = Pattern.compile("[\\\"'`](.*?)[\\\"'`]", Pattern.DOTALL);
    private static final Pattern PATH_START = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:file:/+|~[/\\\\]|/|[A-Za-z]:[/\\\\])");

    private final PluginPackageService packages;
    private final PluginFileGrantService files;

    public ChatFileGrantService(PluginPackageService packages, PluginFileGrantService files) {
        this.packages = packages;
        this.files = files;
    }

    public void revoke(String pluginId, String refId) {
        files.revoke(pluginId, refId);
    }

    /** Grant every existing path explicitly present in the latest user message as read-only. */
    public List<ActiveFileRef> grantPathsFromUserText(String text) {
        List<ActiveFileRef> result = new ArrayList<>();
        for (Path path : extractExistingPaths(text)) {
            result.addAll(grantNative(path.toString(), Files.isDirectory(path) ? "directory" : "file", false));
        }
        return result;
    }

    /**
     * Grant one picker-selected native path to every enabled backend plugin that declared the
     * required file permission. Directly typed paths pass {@code writableDirectory=false}; a
     * selected directory may opt into write access because the selection itself is explicit.
     */
    public List<ActiveFileRef> grantNative(String rawPath, String kind, boolean writableDirectory) {
        if (!List.of("file", "directory").contains(kind)) {
            throw new IllegalArgumentException("Invalid native file kind");
        }
        List<ActiveFileRef> result = new ArrayList<>();
        for (PluginManifest plugin : eligiblePlugins()) {
            String access = accessFor(plugin, kind, writableDirectory);
            if (access == null) continue;
            try {
                result.add(new ActiveFileRef(plugin.id(), files.grantNative(plugin.id(), rawPath, kind, access)));
            } catch (IOException e) {
                revokeAll(result);
                throw new IllegalArgumentException("Cannot grant selected path: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                revokeAll(result);
                throw e;
            }
        }
        return List.copyOf(result);
    }

    /** Store one browser-selected file separately for every eligible backend plugin. */
    public List<ActiveFileRef> grantUpload(MultipartFile upload) {
        List<ActiveFileRef> result = new ArrayList<>();
        for (PluginManifest plugin : eligiblePlugins()) {
            if (accessFor(plugin, "file", false) == null) continue;
            try {
                result.add(new ActiveFileRef(plugin.id(), files.upload(plugin.id(), upload)));
            } catch (IOException e) {
                revokeAll(result);
                throw new IllegalArgumentException("Cannot grant uploaded file: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                revokeAll(result);
                throw e;
            }
        }
        return List.copyOf(result);
    }

    /** Store one browser-selected directory for every eligible backend plugin. */
    public List<ActiveFileRef> grantUploadDirectory(List<MultipartFile> uploads,
            List<String> relativePaths, boolean writableDirectory) {
        List<ActiveFileRef> result = new ArrayList<>();
        for (PluginManifest plugin : eligiblePlugins()) {
            String access = accessFor(plugin, "directory", writableDirectory);
            if (access == null) continue;
            try {
                result.add(new ActiveFileRef(plugin.id(),
                    files.uploadDirectory(plugin.id(), uploads, relativePaths, access)));
            } catch (IOException e) {
                revokeAll(result);
                throw new IllegalArgumentException("Cannot grant uploaded directory: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                revokeAll(result);
                throw e;
            }
        }
        return List.copyOf(result);
    }

    private List<PluginManifest> eligiblePlugins() {
        return packages.installed().stream()
            .filter(plugin -> packages.isEnabled(plugin.id()))
            .filter(plugin -> plugin.backend() != null
                && plugin.backend().command() != null
                && !plugin.backend().command().isBlank()
                && "json-rpc-2.0".equals(plugin.backend().protocol()))
            .toList();
    }

    private void revokeAll(List<ActiveFileRef> refs) {
        for (ActiveFileRef ref : refs) files.revoke(ref.pluginId(), ref.ref().id());
        refs.clear();
    }

    private static String accessFor(PluginManifest plugin, String kind, boolean writableDirectory) {
        List<String> permissions = plugin.permissions() == null ? List.of() : plugin.permissions();
        boolean read = permissions.contains("files.read");
        boolean write = permissions.contains("files.write");
        if ("file".equals(kind)) return read ? "read" : null;
        if (!read) return null;
        return writableDirectory && write ? "read-write" : "read";
    }

    /** Extract canonical existing absolute paths without treating arbitrary relative prose as IO. */
    static List<Path> extractExistingPaths(String text) {
        if (text == null || text.isBlank()) return List.of();
        Map<Path, Path> found = new LinkedHashMap<>();

        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) addIfExisting(found, quoted.group(1));

        Matcher starts = PATH_START.matcher(text);
        while (starts.find()) {
            String tail = text.substring(starts.start()).split("[\\r\\n]", 2)[0];
            addLongestExistingPrefix(found, tail);
        }
        return List.copyOf(found.values());
    }

    private static void addLongestExistingPrefix(Map<Path, Path> found, String raw) {
        String candidate = trimTrailingPunctuation(raw.trim());
        while (!candidate.isBlank()) {
            if (addIfExisting(found, candidate)) return;
            int boundary = lastWhitespace(candidate);
            if (boundary < 0) return;
            candidate = trimTrailingPunctuation(candidate.substring(0, boundary).trim());
        }
    }

    private static int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i)) && (i == 0 || value.charAt(i - 1) != '\\')) return i;
        }
        return -1;
    }

    private static boolean addIfExisting(Map<Path, Path> found, String raw) {
        try {
            String value = trimTrailingPunctuation(raw.trim()).replace("\\ ", " ");
            Path path;
            if (value.startsWith("file:")) {
                path = Path.of(URI.create(value));
            } else if (value.equals("~") || value.startsWith("~/") || value.startsWith("~\\")) {
                path = Path.of(System.getProperty("user.home"), value.length() == 1 ? "" : value.substring(2));
            } else {
                path = Path.of(value);
            }
            if (!path.isAbsolute() || !Files.exists(path)) return false;
            Path real = path.toRealPath();
            found.putIfAbsent(real, real);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && ",.;:!?，。；：！？)]}）】》\"'`".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }
}
