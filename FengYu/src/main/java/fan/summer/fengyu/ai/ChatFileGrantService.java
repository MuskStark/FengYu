package fan.summer.fengyu.ai;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *
 * <p><b>Output directories never become worker-writable.</b> A directory the user names as an
 * output target is granted read-only; instead a plugin-owned <em>staging</em> directory is created
 * per turn and handed to the worker. The host copies the staging contents to the real target after
 * the turn completes (see {@link StagedOutput} / {@link #exportStaging(List)}), then deletes the
 * staging tree. This keeps the OS sandbox writable-roots stable (one staging grant per turn) and
 * never lets a worker overwrite files in a user-named directory.
 */
@Service
public class ChatFileGrantService {

    /**
     * One staging directory created for a write-capable plugin, paired with the user-named native
     * target directory the host will copy it to when the turn ends.
     */
    public record StagedOutput(String pluginId, PluginFileGrantService.FileRef stagingRef, Path targetDir) {}

    private static final Logger log = LoggerFactory.getLogger(ChatFileGrantService.class);

    private static final Pattern QUOTED = Pattern.compile("[\\\"'`](.*?)[\\\"'`]", Pattern.DOTALL);
    private static final Pattern PATH_START = Pattern.compile(
        "(?<![A-Za-z0-9_])(?:file:/+|~[/\\\\]|/|[A-Za-z]:[/\\\\])");
    private static final Pattern WRITE_PATH_INTENT = Pattern.compile(
        "(?i)(?:输出(?:目录)?|保存(?:目录)?|写入(?:目录)?|写到|导出(?:目录)?|生成到|生成至|"
            + "存放(?:到|至)?|放到|落盘|目标目录|output|save|write|export|destination|target)");

    private final PluginPackageService packages;
    private final PluginFileGrantService files;

    public ChatFileGrantService(PluginPackageService packages, PluginFileGrantService files) {
        this.packages = packages;
        this.files = files;
    }

    public void revoke(String pluginId, String refId) {
        files.revoke(pluginId, refId);
    }

    /**
     * Grant every existing path explicitly present in the latest user message, always read-only.
     * A directory the user names as an output target is <em>not</em> made writable here — see
     * {@link #prepareStagingForWriteTargets(String)}, which creates a plugin-owned staging
     * directory instead. Markdown decoration is ignored and never affects the access decision.
     */
    public List<ActiveFileRef> grantPathsFromUserText(String text) {
        List<ActiveFileRef> result = new ArrayList<>();
        for (Path path : extractExistingPaths(text)) {
            boolean directory = Files.isDirectory(path);
            result.addAll(grantNative(path.toString(), directory ? "directory" : "file", false));
        }
        return result;
    }

    /**
     * Create one plugin-owned staging directory per write-capable plugin for each distinct
     * directory the user named as an output target in the latest message. Returns the staging
     * grants (to add to the turn's active refs) paired with the native target directories, so the
     * caller can {@link #exportStaging(List)} them after the turn.
     *
     * <p>A staging directory is a sandbox-writable root from the worker's first call onward, so the
     * worker process restarts at most once per turn (when its {@code grantVersion} bumps) instead
     * of on every write-tool call. The real target directory is never granted to the worker.
     */
    public StagingPreparation prepareStagingForWriteTargets(String text) {
        List<StagedOutput> staged = new ArrayList<>();
        List<ActiveFileRef> refs = new ArrayList<>();
        if (text == null || text.isBlank()) return new StagingPreparation(refs, staged);
        List<Path> targets = new ArrayList<>();
        for (Path path : extractExistingPaths(text)) {
            if (Files.isDirectory(path) && isWriteTarget(text, path)) targets.add(path);
        }
        if (targets.isEmpty()) return new StagingPreparation(refs, staged);
        for (PluginManifest plugin : eligiblePlugins()) {
            List<String> permissions = plugin.permissions() == null ? List.of() : plugin.permissions();
            if (!permissions.contains("files.write")) continue;
            for (Path target : targets) {
                try {
                    var staging = files.outputDirectory(plugin.id());
                    staged.add(new StagedOutput(plugin.id(), staging, target));
                    refs.add(new ActiveFileRef(plugin.id(), staging));
                } catch (IOException e) {
                    revokeAll(refs);
                    throw new IllegalArgumentException("Cannot create staging directory: " + e.getMessage(), e);
                }
            }
        }
        return new StagingPreparation(List.copyOf(refs), List.copyOf(staged));
    }

    /** Result of {@link #prepareStagingForWriteTargets}: staging refs to activate + staging/target pairs to export. */
    public record StagingPreparation(List<ActiveFileRef> refs, List<StagedOutput> staged) {}

    /**
     * Copy each staging directory's contents into its user-named native target, then delete the
     * staging tree. Runs in the host process (outside the worker sandbox). A failed copy is logged
     * and skipped; it never blocks the staging cleanup, so a bad target cannot leak staging files.
     */
    public List<String> exportStaging(List<StagedOutput> staged) {
        List<String> exported = new ArrayList<>();
        if (staged == null || staged.isEmpty()) return exported;
        for (StagedOutput item : staged) {
            Path stagingDir;
            try {
                stagingDir = files.resolve(item.pluginId(), item.stagingRef().id());
            } catch (RuntimeException ignored) {
                continue; // staging already revoked (e.g. turn cancelled)
            }
            try {
                copyTree(stagingDir, item.targetDir());
                exported.add(item.targetDir().toString());
            } catch (IOException e) {
                logCopyFailure(item, e);
            } finally {
                files.revoke(item.pluginId(), item.stagingRef().id());
            }
        }
        return exported;
    }

    /** Revoke unexported staging grants when a chat transport disconnects or is abandoned. */
    public void discardStaging(List<StagedOutput> staged) {
        if (staged == null) return;
        for (StagedOutput item : staged) {
            files.revoke(item.pluginId(), item.stagingRef().id());
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var paths = Files.walk(source)) {
            for (Path entry : paths.toList()) {
                Path relative = source.relativize(entry);
                Path copy = target.resolve(relative).normalize();
                if (!copy.startsWith(target)) throw new IOException("Staging entry escapes the target directory");
                if (Files.isDirectory(entry)) Files.createDirectories(copy);
                else Files.copy(entry, copy, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void logCopyFailure(StagedOutput item, IOException e) {
        // Best-effort: a real target write failure is logged but never aborts the whole turn, and
        // the staging tree is still revoked in the caller's finally so nothing leaks.
        log.warn("Failed to export plugin {} staging to {}: {}", item.pluginId(), item.targetDir(), e.toString());
    }

    /**
     * Grant one picker-selected native path to every enabled backend plugin that declared the
     * required file permission. A selected directory may opt into write access because the picker
     * selection itself is explicit authorization.
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
        Path path = longestExistingPrefix(raw);
        if (path != null) found.putIfAbsent(path, path);
    }

    private static int lastWhitespace(String value) {
        for (int i = value.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(value.charAt(i)) && (i == 0 || value.charAt(i - 1) != '\\')) return i;
        }
        return -1;
    }

    private static boolean addIfExisting(Map<Path, Path> found, String raw) {
        Path path = existingPath(raw);
        if (path == null) return false;
        found.putIfAbsent(path, path);
        return true;
    }

    private static Path longestExistingPrefix(String raw) {
        String candidate = trimTrailingPunctuation(raw.trim());
        while (!candidate.isBlank()) {
            Path path = existingPath(candidate);
            if (path != null) return path;
            int boundary = lastWhitespace(candidate);
            if (boundary < 0) return null;
            candidate = trimTrailingPunctuation(candidate.substring(0, boundary).trim());
        }
        return null;
    }

    private static Path existingPath(String raw) {
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
            if (!path.isAbsolute() || !Files.exists(path)) return null;
            return path.toRealPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWriteTarget(String text, Path path) {
        if (text == null || text.isBlank()) return false;
        Matcher starts = PATH_START.matcher(text);
        while (starts.find()) {
            String tail = text.substring(starts.start()).split("[\\r\\n]", 2)[0];
            Path occurrencePath = longestExistingPrefix(tail);
            if (!path.equals(occurrencePath)) continue;
            int occurrence = starts.start();
            int clauseStart = occurrence;
            while (clauseStart > 0 && "\r\n,，。;；".indexOf(text.charAt(clauseStart - 1)) < 0) {
                clauseStart--;
            }
            String prefix = text.substring(clauseStart, occurrence);
            if (WRITE_PATH_INTENT.matcher(prefix).find()) return true;
        }
        return false;
    }

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && ",.;:!?，。；：！？)]}）】》\"'`*".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }
}
