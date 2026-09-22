package fan.summer.fengyu.ai.workspace;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the per-conversation workspace root (the {@code ai_conversation.workspace_root}
 * column) and validates it at every transition. The root is user-chosen and server-verified:
 * it must be an existing, readable, real directory; the canonical form ({@code toRealPath}) is
 * stored so the path jail's containment checks operate on the same identity the user saw when
 * picking the folder.
 */
@Service
public class WorkspaceService {

    private static final int MAX_ROOT_LENGTH = 1024;

    private final ConversationRepository conversations;
    private final SecurityContext securityContext;
    private final WorkspaceReadState readState;

    public WorkspaceService(ConversationRepository conversations, SecurityContext securityContext,
            WorkspaceReadState readState) {
        this.conversations = conversations;
        this.securityContext = securityContext;
        this.readState = readState;
    }

    /**
     * Attach {@code rawPath} as the workspace of {@code conversationId}. The path must name an
     * existing readable directory; returns the canonical stored root.
     */
    @Transactional
    public Path setWorkspace(Long conversationId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Workspace path must not be blank");
        }
        ConversationEntity conversation = owned(conversationId);
        Path canonical;
        try {
            Path supplied = Path.of(expandHome(rawPath.strip()));
            if (!Files.isDirectory(supplied)) {
                throw new IllegalArgumentException("Workspace path is not a directory: " + rawPath);
            }
            if (!Files.isReadable(supplied)) {
                throw new IllegalArgumentException("Workspace directory is not readable: " + rawPath);
            }
            canonical = supplied.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve workspace path: " + e.getMessage());
        }
        if (canonical.toString().length() > MAX_ROOT_LENGTH) {
            throw new IllegalArgumentException("Workspace path is too long");
        }
        conversation.setWorkspaceRoot(canonical.toString());
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversations.save(conversation);
        readState.clearConversation(conversationId);
        return canonical;
    }

    /** Detach the workspace root; coding tools disappear from the conversation's next turn. */
    @Transactional
    public void clearWorkspace(Long conversationId) {
        ConversationEntity conversation = owned(conversationId);
        conversation.setWorkspaceRoot(null);
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversations.save(conversation);
        readState.clearConversation(conversationId);
    }

    /**
     * The binding a chat turn should carry, or null. A stored root that has vanished from disk
     * yields null (and logs) instead of poisoning the turn — the user re-attaches a valid folder.
     */
    public WorkspaceContext.Binding bindingFor(Long conversationId) {
        if (conversationId == null) return null;
        return conversations.findByIdAndUserId(conversationId, userId())
                .map(conversation -> conversation.getWorkspaceRoot())
                .filter(root -> root != null && !root.isBlank())
                .map(root -> {
                    Path path = Path.of(root);
                    if (!Files.isDirectory(path)) return null;
                    return new WorkspaceContext.Binding(path, conversationId);
                })
                .orElse(null);
    }

    private ConversationEntity owned(Long conversationId) {
        if (conversationId == null) throw new IllegalArgumentException("conversationId is required");
        return conversations.findByIdAndUserId(conversationId, userId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown conversation: " + conversationId));
    }

    private long userId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
