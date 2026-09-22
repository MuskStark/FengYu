package fan.summer.fengyu.ai.workspace;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which workspace files the model has read, and the file's modification time at that
 * read — the read-before-edit freshness contract shared by terminal coding agents: an
 * {@code edit_file}/{@code write_file} on an existing file is accepted only when the file was
 * read during the conversation AND has not changed on disk since. The model can therefore
 * never blind-edit a file it has not seen, and an external editor's save invalidates the
 * model's stale view.
 *
 * <p>In-memory by design: state is conversation-scoped working context, not durable data.
 * After a host restart the map is empty and the model must read again — the safe direction.
 */
@Component
public class WorkspaceReadState {

    /** One recorded read: the file's mtime when it was read (null when unavailable). */
    public record ReadEntry(FileTime mtime) {}

    static final int MAX_FILES_PER_CONVERSATION = 512;

    private final Map<Long, Map<String, ReadEntry>> byConversation = new ConcurrentHashMap<>();

    public void recordRead(Long conversationId, Path file, FileTime mtime) {
        if (conversationId == null || file == null) return;
        byConversation.computeIfAbsent(conversationId, id -> boundedMap()).put(key(file), new ReadEntry(mtime));
    }

    /** The read entry for {@code file}, or null when the model has not read it. */
    public ReadEntry readEntry(Long conversationId, Path file) {
        if (conversationId == null || file == null) return null;
        Map<String, ReadEntry> files = byConversation.get(conversationId);
        return files == null ? null : files.get(key(file));
    }

    public void invalidate(Long conversationId, Path file) {
        if (conversationId == null || file == null) return;
        Map<String, ReadEntry> files = byConversation.get(conversationId);
        if (files != null) files.remove(key(file));
    }

    public void clearConversation(Long conversationId) {
        if (conversationId != null) byConversation.remove(conversationId);
    }

    /** Insertion-ordered with oldest-first eviction: bounded memory for long coding sessions. */
    private static Map<String, ReadEntry> boundedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ReadEntry> eldest) {
                return size() > MAX_FILES_PER_CONVERSATION;
            }
        });
    }

    private static String key(Path file) {
        return file.toString();
    }
}
