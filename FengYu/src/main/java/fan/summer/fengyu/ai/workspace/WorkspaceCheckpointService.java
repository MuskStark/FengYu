package fan.summer.fengyu.ai.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Conversation-scoped checkpoints for the workspace coding tools: before {@code write_file}/
 * {@code edit_file} mutates a file, the current on-disk content is snapshotted OUTSIDE the
 * workspace (under {@code ~/.fengyu/workspace-checkpoints/<conversationId>/}).
 * The Changes pane lists each touched file with its cumulative diff (first snapshot vs
 * current content) and can roll a file — or everything — back to the state before this
 * conversation first touched it.
 *
 * <p>In-memory index by design (working context, like {@link WorkspaceReadState}); the
 * snapshot FILES persist on disk so a host restart keeps the bytes while the index
 * rebuilds empty (rollback then reports "no checkpoint" — the safe direction). Entries are
 * bounded per conversation; evicted snapshots delete their files.</p>
 */
@Service
public class WorkspaceCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCheckpointService.class);

    /** Files larger than this are recorded but not snapshotted (rollback unavailable). */
    static final long MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    /** Index bound per conversation; oldest entries (and their files) evict beyond it. */
    static final int MAX_ENTRIES_PER_CONVERSATION = 200;
    /** Conversation dirs untouched for this long are swept (orphaned/deleted conversations). */
    static final java.time.Duration SWEEP_AGE = java.time.Duration.ofDays(14);

    /** One recorded write: the pre-write state of one file. */
    public record Entry(long seq, Long conversationId, String relativePath,
                        Path snapshotFile, boolean existed, boolean snapshotSkipped,
                        Instant timestamp) {}

    /** Aggregated per-file change view for the Changes pane. */
    public record FileChange(String path, boolean created, boolean reverted, boolean gone,
                             int snapshots, Instant firstTouchedAt, String diff) {}

    private final Map<Long, List<Entry>> byConversation = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> sequences = new ConcurrentHashMap<>();

    /**
     * Snapshots live under the USER HOME (never {@code user.dir} — the process CWD can sit
     * inside an attached workspace, which would make snapshots model-reachable through
     * glob/grep and corruptible through write_file).
     */
    private static Path checkpointsHome() {
        return Path.of(System.getProperty("user.home"), ".fengyu", "workspace-checkpoints");
    }

    private static Path checkpointRoot(Long conversationId) {
        return checkpointsHome().resolve(String.valueOf(conversationId));
    }

    /**
     * Snapshots {@code file}'s current content before a workspace write mutates it. Failures
     * degrade to a snapshotSkipped entry — a checkpoint problem must never block the edit.
     */
    public void snapshotBefore(Long conversationId, Path root, Path file) {
        if (conversationId == null || root == null || file == null) return;
        // A workspace rooted at (or inside) ~/.fengyu — or at any ancestor of it, e.g. the
        // home dir itself — would expose every conversation's checkpoint tree to the coding
        // tools (read_file reaches it even though search skips .fengyu, and write_file could
        // corrupt other conversations' snapshots): record nothing for such workspaces.
        Path checkpointsParent = checkpointRoot(conversationId).getParent();
        if (root.startsWith(checkpointsParent) || checkpointsParent.startsWith(root)) return;
        String relative = WorkspacePathPolicy.display(root, file);
        long seq = sequences.computeIfAbsent(conversationId, id -> new AtomicLong())
                .incrementAndGet();
        boolean existed = Files.isRegularFile(file);
        Path snapshotFile = null;
        boolean skipped = false;
        if (!existed) {
            skipped = false; // creation: the "snapshot" is the absence, rollback deletes
        } else {
            try {
                long size = Files.size(file);
                if (size > MAX_SNAPSHOT_BYTES) {
                    skipped = true;
                } else {
                    snapshotFile = checkpointRoot(conversationId)
                            .resolve(seq + "/" + safeSegments(relative));
                    Files.createDirectories(snapshotFile.getParent());
                    Files.copy(file, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failure) {
                skipped = true;
                log.debug("checkpoint snapshot failed for {}: {}", relative, failure.toString());
            }
        }
        Entry entry = new Entry(seq, conversationId, relative, snapshotFile, existed, skipped,
                Instant.now());
        List<Entry> entries = byConversation.computeIfAbsent(conversationId, id -> new ArrayList<>());
        synchronized (entries) {
            entries.add(entry);
            while (entries.size() > MAX_ENTRIES_PER_CONVERSATION) {
                Entry evicted = entries.remove(0);
                deleteQuietly(evicted.snapshotFile());
            }
        }
    }

    /** The per-file change list of one conversation, oldest-first, with cumulative diffs. */
    public List<FileChange> changes(Long conversationId, Path root) {
        List<Entry> entries = byConversation.get(conversationId);
        if (entries == null) return List.of();
        // First (baseline) snapshot per path defines the pre-conversation state.
        Map<String, Entry> baseline = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        for (Entry entry : snapshot) {
            baseline.putIfAbsent(entry.relativePath(), entry);
            counts.merge(entry.relativePath(), 1, Integer::sum);
        }
        List<FileChange> out = new ArrayList<>();
        for (Map.Entry<String, Entry> item : baseline.entrySet()) {
            String relative = item.getKey();
            Entry first = item.getValue();
            Path current = resolveIn(root, relative);
            boolean existsNow = Files.isRegularFile(current);
            String diff = "";
            boolean created = !first.existed();
            boolean gone = first.existed() && !existsNow;
            if (first.snapshotSkipped() || first.snapshotFile() == null) {
                diff = first.existed()
                        ? "(checkpoint unavailable — file was too large to snapshot)"
                        : "";
            } else if (!existsNow) {
                diff = "(file deleted during this conversation)";
            } else {
                try {
                    String before = Files.readString(first.snapshotFile());
                    String after = Files.readString(current);
                    diff = before.equals(after) ? "" : UnifiedDiff.diff(relative, before, after);
                } catch (IOException | RuntimeException unreadable) {
                    diff = "(checkpoint unreadable)";
                }
            }
            boolean reverted = existsNow && diff.isEmpty();
            out.add(new FileChange(relative, created, reverted, gone,
                    counts.getOrDefault(relative, 1), first.timestamp(), diff));
        }
        out.sort(Comparator.comparing(FileChange::firstTouchedAt));
        return out;
    }

    /**
     * Rolls one file back to its pre-conversation state: restores the FIRST snapshot, or
     * deletes the file when the conversation created it. Returns a short result string.
     */
    public String rollbackFile(Long conversationId, Path root, String relativePath) {
        Entry baseline = firstEntry(conversationId, relativePath);
        if (baseline == null) return "No checkpoint recorded for " + relativePath;
        return restore(conversationId, root, relativePath, baseline);
    }

    /** Rolls every checkpointed file of the conversation back; returns a summary. */
    public String rollbackAll(Long conversationId, Path root) {
        List<Entry> entries = byConversation.get(conversationId);
        if (entries == null || entries.isEmpty()) return "No checkpoints recorded";
        Map<String, Entry> baseline = new LinkedHashMap<>();
        List<Entry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<>(entries);
        }
        for (Entry entry : snapshot) baseline.putIfAbsent(entry.relativePath(), entry);
        int restored = 0;
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Entry> item : baseline.entrySet()) {
            String result = restore(conversationId, root, item.getKey(), item.getValue());
            if (result.startsWith("restored") || result.startsWith("deleted")) restored++;
            else problems.add(item.getKey() + ": " + result);
        }
        return problems.isEmpty()
                ? "restored " + restored + " file(s)"
                : "restored " + restored + " file(s); " + String.join("; ", problems);
    }

    /** Drops the index and deletes every snapshot file of one conversation. */
    public void clearConversation(Long conversationId) {
        if (conversationId == null) return;
        List<Entry> entries = byConversation.remove(conversationId);
        sequences.remove(conversationId);
        if (entries != null) {
            for (Entry entry : entries) deleteQuietly(entry.snapshotFile());
        }
        deleteTreeQuietly(checkpointRoot(conversationId));
    }

    /**
     * Daily sweep of orphaned conversation dirs (deleted conversations that never ran
     * {@link #clearConversation}, host crashes): snapshot bytes must not accumulate
     * forever. Best effort — an unsweepable dir retries on the next pass. A dir's own
     * mtime approximates activity: every snapshot seq creates a fresh subdir under it.
     */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    public void sweepStaleConversations() {
        sweepStale(checkpointsHome(), Instant.now().minus(SWEEP_AGE));
    }

    /** Package-visible for tests: deletes conversation dirs under {@code home} older than {@code cutoff}. */
    static void sweepStale(Path home, Instant cutoff) {
        if (!Files.isDirectory(home)) return;
        try (Stream<Path> dirs = Files.list(home)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                        deleteTreeQuietly(dir);
                    }
                } catch (IOException ignored) {
                    // best effort — the next sweep retries
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String restore(Long conversationId, Path root, String relativePath, Entry baseline) {
        Path current = resolveIn(root, relativePath);
        if (!baseline.existed()) {
            try {
                Files.deleteIfExists(current);
                return "deleted " + relativePath + " (created during this conversation)";
            } catch (IOException e) {
                return "could not delete " + relativePath + ": " + e.getMessage();
            }
        }
        if (baseline.snapshotSkipped() || baseline.snapshotFile() == null) {
            return "no snapshot for " + relativePath + " (too large to checkpoint)";
        }
        try {
            Files.createDirectories(current.getParent());
            Files.copy(baseline.snapshotFile(), current, StandardCopyOption.REPLACE_EXISTING);
            return "restored " + relativePath;
        } catch (IOException e) {
            return "could not restore " + relativePath + ": " + e.getMessage();
        }
    }

    private Entry firstEntry(Long conversationId, String relativePath) {
        List<Entry> entries = byConversation.get(conversationId);
        if (entries == null) return null;
        synchronized (entries) {
            for (Entry entry : entries) {
                if (entry.relativePath().equals(relativePath)) return entry;
            }
        }
        return null;
    }

    private static Path resolveIn(Path root, String relativePath) {
        // Model-supplied once upon a time, now a UI round-trip: re-jail defensively anyway.
        return WorkspacePathPolicy.resolve(root, relativePath);
    }

    /** Path segments sanitized so a relative path can never climb out of the checkpoint dir. */
    private static String safeSegments(String relativePath) {
        String cleaned = relativePath.replace('\\', '/');
        StringBuilder out = new StringBuilder();
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) continue;
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.isEmpty() ? "file" : out.toString();
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
