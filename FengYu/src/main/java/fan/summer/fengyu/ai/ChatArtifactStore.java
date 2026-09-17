package fan.summer.fengyu.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import fan.summer.fengyu.plugin.runtime.PluginFileGrantService;
import fan.summer.fengyu.runtime.RuntimePaths;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Host-managed registry for generated chat artifacts, separated from the "terminate-and-delete"
 * staging lifecycle on purpose: a failed or not-yet-chosen save must never destroy the only copy
 * of a result (task doc 7.3). Pending copies live under
 * {@code <runtime-root>/chat-artifacts/pending/<artifactId>/} — outside the runtime-files root,
 * so the grant service's startup sweep cannot touch them — plus an {@code index.json} manifest
 * that restores metadata (not directory write authorization) after a restart.
 *
 * <p>Save semantics (task doc 7.2): the host is the only writer; files are staged into a temp
 * name inside the target directory and atomically moved into place; same-name collisions keep
 * both files by default ({@code report.xlsx}, {@code report (2).xlsx}, …); per-artifact success
 * is recorded individually; a failed save retains the pending copy for retry.
 */
@Service
public class ChatArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ChatArtifactStore.class);

    public static final String STATE_READY = "ready-to-save";
    public static final String STATE_SAVING = "saving";
    public static final String STATE_SAVED = "saved";
    public static final String STATE_FAILED = "save-failed";

    /** Unsaved artifacts are reclaimed after this; saved ones are already in the user's hands. */
    static final Duration PENDING_TTL = Duration.ofDays(7);
    /** Total capacity for unsaved artifacts; beyond it new collection fails loudly, never silently. */
    static final long MAX_PENDING_BYTES = 2L * 1024 * 1024 * 1024;

    private final PluginFileGrantService files;
    private final Path root;
    private final Path pendingRoot;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ChatArtifactStore(PluginFileGrantService files) {
        this(files, RuntimePaths.root().resolve("chat-artifacts"));
    }

    public ChatArtifactStore(PluginFileGrantService files, Path root) {
        this.files = files;
        this.root = root;
        this.pendingRoot = root.resolve("pending");
        try {
            Files.createDirectories(pendingRoot);
        } catch (IOException e) {
            log.warn("Cannot create chat artifact storage {}: {}", pendingRoot, e.toString());
        }
        loadManifest();
        sweepExpired();
    }

    // ── model ───────────────────────────────────────────────────────────────────────────

    /**
     * One registered artifact. {@code savedPath} is set only after the copy was confirmed on
     * disk; {@code error} carries the last save failure while the pending copy is retained.
     */
    public record Artifact(String artifactId, String scopeId, Long conversationId, String name,
            long size, Instant createdAt, String state, String savedPath, String error) {}

    // ── registration (turn completion) ──────────────────────────────────────────────────

    /**
     * Terminal handler for a chat turn's write staging: every regular file under a staging root
     * becomes a registered artifact (symlinks and escaping entries are refused), each staging
     * grant is revoked afterwards, and — when the turn captured an output target — artifacts are
     * saved into it immediately. Per-file failures keep the artifact pending; they never abort
     * the remaining files (acceptance C06).
     */
    public synchronized List<Artifact> completeTurn(String scopeId, Long conversationId,
            List<ChatFileGrantService.StagedOutput> staged) {
        List<Artifact> registered = new ArrayList<>();
        if (staged == null) return registered;
        for (ChatFileGrantService.StagedOutput item : staged) {
            Path stagingDir;
            try {
                stagingDir = files.resolve(item.pluginId(), item.stagingRef().id());
            } catch (RuntimeException gone) {
                continue; // staging already revoked (e.g. cancelled turn)
            }
            try {
                List<Path> outputs = listOutputFiles(stagingDir);
                for (Path output : outputs) {
                    try {
                        Artifact artifact = register(scopeId, conversationId, output);
                        registered.add(artifact);
                    } catch (IOException | RuntimeException e) {
                        log.warn("Could not register chat artifact {}: {}",
                                output.getFileName(), e.toString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Could not collect chat staging for plugin {}: {}",
                        item.pluginId(), e.toString());
            } finally {
                files.revoke(item.pluginId(), item.stagingRef().id());
            }
        }
        // Auto-save only AFTER every file is safely registered, so a target failure can never
        // strand a file that was still sitting in revocable staging.
        String target = staged.isEmpty() ? null : firstNonNullTarget(staged);
        List<Artifact> result = new ArrayList<>(registered);
        if (target != null) {
            for (Artifact artifact : registered) {
                result.set(result.indexOf(artifact), saveQuietly(artifact.artifactId(), target));
            }
        }
        persistManifest();
        return result;
    }

    /** Registers one staging file as an artifact by moving it into the managed pending store. */
    public Artifact register(String scopeId, Long conversationId, Path stagingFile) throws IOException {
        if (!Files.isRegularFile(stagingFile)) {
            throw new IllegalArgumentException("Artifact source is not a regular file");
        }
        if (Files.isSymbolicLink(stagingFile)) {
            throw new IllegalArgumentException("Artifact source must not be a symbolic link");
        }
        long pendingBytes = pendingBytes();
        long size = Files.size(stagingFile);
        if (pendingBytes + size > MAX_PENDING_BYTES) {
            throw new IllegalStateException("Pending artifact storage is full — save or clear earlier results");
        }
        String artifactId = "art_" + UUID.randomUUID();
        String name = sanitizeFileName(stagingFile.getFileName().toString());
        Path dir = pendingRoot.resolve(artifactId);
        Files.createDirectories(dir);
        Path stored = dir.resolve(name);
        try {
            moveAtomic(stagingFile, stored);
        } catch (IOException e) {
            deleteTree(dir);
            throw e;
        }
        Artifact artifact = new Artifact(artifactId, scopeId, conversationId, name, size,
                Instant.now(), STATE_READY, null, null);
        artifacts.put(artifactId, artifact);
        persistManifest();
        return artifact;
    }

    // ── save ────────────────────────────────────────────────────────────────────────────

    /**
     * Copies the artifact into {@code targetDir} (keep-both on collision) and confirms the copy
     * before reporting {@code saved}. Every failure — including a target that vanished or lost
     * its permissions (C05) — retains the pending copy, records the reason, and stays retryable.
     * Idempotent for already-saved artifacts (returns the recorded outcome instead of writing
     * twice).
     */
    public synchronized Artifact save(String artifactId, String targetDir) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null) throw new IllegalArgumentException("Unknown chat artifact");
        if (STATE_SAVED.equals(artifact.state())) return artifact;
        Path target = targetDir == null || targetDir.isBlank()
                ? null : Path.of(targetDir).toAbsolutePath().normalize();
        if (target == null || !Files.isDirectory(target)) {
            return failed(artifact, "Save target is not an existing directory");
        }
        Path pending = pendingFile(artifact);
        if (!Files.isRegularFile(pending)) {
            return failed(artifact, "The generated file is no longer available");
        }
        artifacts.put(artifactId, withState(artifact, STATE_SAVING, null, null));
        try {
            Path destination = keepBothTarget(target, artifact.name());
            Path temp = target.resolve("." + destination.getFileName() + ".fengyu-save-" + UUID.randomUUID() + ".tmp");
            Files.copy(pending, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                moveAtomic(temp, destination);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            if (!Files.isRegularFile(destination) || Files.size(destination) != artifact.size()) {
                Files.deleteIfExists(destination);
                throw new IOException("Saved file could not be confirmed at " + destination);
            }
            // Success: record the result, then release the pending copy (7.3).
            Files.deleteIfExists(pending);
            deleteTree(pending.getParent());
            Artifact saved = new Artifact(artifact.artifactId(), artifact.scopeId(),
                    artifact.conversationId(), artifact.name(), artifact.size(), artifact.createdAt(),
                    STATE_SAVED, destination.toString(), null);
            artifacts.put(artifactId, saved);
            persistManifest();
            return saved;
        } catch (IOException | RuntimeException e) {
            return failed(artifact, e instanceof IOException io ? io.getMessage() : e.toString());
        }
    }

    /** Records a retryable failure while keeping the pending copy (C05/C06). */
    private Artifact failed(Artifact artifact, String reason) {
        Artifact failedRecord = new Artifact(artifact.artifactId(), artifact.scopeId(),
                artifact.conversationId(), artifact.name(), artifact.size(), artifact.createdAt(),
                STATE_FAILED, null, reason);
        artifacts.put(artifact.artifactId(), failedRecord);
        persistManifest();
        return failedRecord;
    }

    /** The on-disk location of a SAVED artifact — the only path ever handed to open/reveal. */
    public Path savedPath(String artifactId) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null || !STATE_SAVED.equals(artifact.state()) || artifact.savedPath() == null) {
            throw new IllegalArgumentException("Artifact has not been saved yet");
        }
        return Path.of(artifact.savedPath());
    }

    /** Pending copy for the web download path (the browser's "save" is a download). */
    public Path pendingPath(String artifactId) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null) throw new IllegalArgumentException("Unknown chat artifact");
        Path pending = pendingFile(artifact);
        if (!Files.isRegularFile(pending)) throw new IllegalArgumentException("Artifact file is gone");
        return pending;
    }

    public Artifact get(String artifactId) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null) throw new IllegalArgumentException("Unknown chat artifact");
        return artifact;
    }

    public List<Artifact> listByScope(String scopeId) {
        return artifacts.values().stream()
                .filter(artifact -> scopeId.equals(artifact.scopeId()))
                .sorted(Comparator.comparing(Artifact::createdAt))
                .toList();
    }

    /** Restart recovery (C09): pending artifacts for a conversation, metadata without write auth. */
    public List<Artifact> listPendingByConversation(Long conversationId) {
        return artifacts.values().stream()
                .filter(artifact -> conversationId != null
                        && conversationId.equals(artifact.conversationId())
                        && !STATE_SAVED.equals(artifact.state()))
                .sorted(Comparator.comparing(Artifact::createdAt))
                .toList();
    }

    /**
     * Late binding: artifacts registered before the conversation was persisted adopt its id, so
     * restart recovery (C09) can find them again. Only null bindings are filled — an explicit
     * later rebinding never rewrites history.
     */
    public synchronized void bindConversation(String scopeId, Long conversationId) {
        if (scopeId == null || conversationId == null) return;
        boolean changed = false;
        for (Artifact artifact : artifacts.values()) {
            if (!scopeId.equals(artifact.scopeId()) || artifact.conversationId() != null) continue;
            artifacts.put(artifact.artifactId(), new Artifact(artifact.artifactId(), artifact.scopeId(),
                    conversationId, artifact.name(), artifact.size(), artifact.createdAt(),
                    artifact.state(), artifact.savedPath(), artifact.error()));
            changed = true;
        }
        if (changed) persistManifest();
    }

    /** Drops a scope's artifacts: pending copies are deleted, saved results stay on disk. */
    public synchronized void purgeScope(String scopeId) {
        for (Artifact artifact : artifacts.values()) {
            if (!scopeId.equals(artifact.scopeId())) continue;
            if (!STATE_SAVED.equals(artifact.state())) deleteTree(pendingRoot.resolve(artifact.artifactId()));
            artifacts.remove(artifact.artifactId());
        }
        persistManifest();
    }

    // ── internals ───────────────────────────────────────────────────────────────────────

    private Artifact saveQuietly(String artifactId, String targetDir) {
        Artifact saved = save(artifactId, targetDir);
        if (!STATE_SAVED.equals(saved.state())) {
            log.warn("Auto-save of chat artifact {} failed: {}", artifactId, saved.error());
        }
        return saved;
    }

    private static String firstNonNullTarget(List<ChatFileGrantService.StagedOutput> staged) {
        for (ChatFileGrantService.StagedOutput item : staged) {
            if (item.targetDir() != null) return item.targetDir().toString();
        }
        return null;
    }

    private List<Path> listOutputFiles(Path stagingDir) throws IOException {
        List<Path> outputs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(stagingDir)) {
            for (Path entry : paths.toList()) {
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Staging must not contain symbolic links");
                }
                Path relative = stagingDir.relativize(entry).normalize();
                if (relative.startsWith("..")) throw new IOException("Staging entry escapes its root");
                if (Files.isRegularFile(entry)) outputs.add(entry);
            }
        }
        outputs.sort(Comparator.comparing(Path::toString));
        return outputs;
    }

    private Path pendingFile(Artifact artifact) {
        return pendingRoot.resolve(artifact.artifactId()).resolve(artifact.name());
    }

    private static Artifact withState(Artifact artifact, String state, String savedPath, String error) {
        return new Artifact(artifact.artifactId(), artifact.scopeId(), artifact.conversationId(),
                artifact.name(), artifact.size(), artifact.createdAt(), state, savedPath, error);
    }

    /** First free {@code name (2).ext}, {@code name (3).ext}… — never a silent overwrite (C04). */
    private static Path keepBothTarget(Path targetDir, String name) {
        Path candidate = targetDir.resolve(name);
        if (Files.notExists(candidate)) return candidate;
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            candidate = targetDir.resolve(base + " (" + i + ")" + extension);
            if (Files.notExists(candidate)) return candidate;
        }
        throw new IllegalStateException("Too many duplicate names in the save target");
    }

    private static String sanitizeFileName(String raw) {
        String name = raw == null ? "" : raw.trim();
        Path single = name.isEmpty() ? null : Path.of(name).getFileName();
        if (single == null || single.toString().isBlank() || single.toString().equals(".")) {
            return "artifact";
        }
        return single.toString();
    }

    private long pendingBytes() {
        return artifacts.values().stream()
                .filter(artifact -> !STATE_SAVED.equals(artifact.state()))
                .mapToLong(Artifact::size)
                .sum();
    }

    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedHere) {
            // Cross-filesystem or FS without atomic rename — a plain move still lands the data.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        for (Artifact artifact : List.copyOf(artifacts.values())) {
            if (STATE_SAVED.equals(artifact.state())) continue;
            if (Duration.between(artifact.createdAt(), now).compareTo(PENDING_TTL) <= 0) continue;
            deleteTree(pendingRoot.resolve(artifact.artifactId()));
            artifacts.remove(artifact.artifactId());
            log.info("Expired unsaved chat artifact {} ({})", artifact.artifactId(), artifact.name());
        }
        // Drop empty pending directories the sweep may have left behind.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pendingRoot)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                        if (!children.iterator().hasNext()) Files.deleteIfExists(entry);
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort cleanup only
        }
        persistManifest();
    }

    private void loadManifest() {
        Path manifest = root.resolve("index.json");
        if (!Files.isRegularFile(manifest)) return;
        try {
            List<ManifestRow> rows = json.readValue(manifest.toFile(),
                    json.getTypeFactory().constructCollectionType(List.class, ManifestRow.class));
            for (Artifact row : rows.stream().map(ManifestRow::toArtifact).toList()) {
                if (STATE_SAVING.equals(row.state())) {
                    // A crash mid-save: the pending copy is intact, so retry is possible.
                    artifacts.put(row.artifactId(), withState(row, STATE_FAILED, null,
                            "Save was interrupted — retry"));
                } else {
                    artifacts.put(row.artifactId(), row);
                }
            }
        } catch (IOException e) {
            log.warn("Could not load chat artifact manifest {}: {}", manifest, e.toString());
        }
    }

    private void persistManifest() {
        try {
            Files.createDirectories(root);
            Path manifest = root.resolve("index.json");
            Path temp = root.resolve("index.json.tmp");
            json.writerFor(List.class).writeValue(temp.toFile(),
                    artifacts.values().stream().map(ManifestRow::of).toList());
            moveAtomic(temp, manifest);
        } catch (IOException e) {
            log.warn("Could not persist chat artifact manifest: {}", e.toString());
        }
    }

    /** Manifest DTO — {@code createdAt} rides as ISO text so no Jackson datetime module is needed. */
    private record ManifestRow(String artifactId, String scopeId, Long conversationId, String name,
            long size, String createdAt, String state, String savedPath, String error) {

        static ManifestRow of(Artifact artifact) {
            return new ManifestRow(artifact.artifactId(), artifact.scopeId(), artifact.conversationId(),
                    artifact.name(), artifact.size(), artifact.createdAt().toString(),
                    artifact.state(), artifact.savedPath(), artifact.error());
        }

        Artifact toArtifact() {
            Instant created;
            try {
                created = createdAt == null ? Instant.now() : Instant.parse(createdAt);
            } catch (RuntimeException e) {
                created = Instant.now();
            }
            return new Artifact(artifactId, scopeId, conversationId, name, size, created,
                    state, savedPath, error);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | java.io.UncheckedIOException raced) {
            // deleted concurrently or locked — the expiry sweep will retry later
        }
    }

    @PreDestroy
    void persistOnShutdown() {
        persistManifest();
    }

    /** Visible for tests: the pending store root. */
    Path pendingRoot() {
        return pendingRoot;
    }
}
