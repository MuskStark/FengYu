package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.AiChatMessage;
import fan.summer.fengyu.ai.session.AiRolloutService;
import fan.summer.fengyu.database.entity.ai.ChatMessageEntity;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side rollout records: the JSONL log the backends append every turn, plus the two
 * operations that make it more than an audit trail — <b>resume</b> (rebuild the message
 * list from the SERVER's memory when the client transcript was lost) and <b>fork</b>
 * (branch a new conversation from any recorded sequence point, with the fork's own log
 * copied from the prefix so the two conversations diverge independently).
 *
 * <p>Paths sit under the conversation resource they operate on. APP-only: excluded from
 * the SETUP context (depends on the {@code ai} graph and the AI-history repositories).</p>
 */
@RestController
@RequestMapping("/api/ai/conversations/{id}/rollout")
public class AiRolloutController {

    private final AiRolloutService rollouts;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final SecurityContext securityContext;

    public AiRolloutController(AiRolloutService rollouts, ConversationRepository conversations,
            ChatMessageRepository messages, SecurityContext securityContext) {
        this.rollouts = rollouts;
        this.conversations = conversations;
        this.messages = messages;
        this.securityContext = securityContext;
    }

    /** The recorded events of one conversation, oldest first (bounded by the log cap). */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> events(@PathVariable Long id) {
        return owned(id)
                ? ResponseEntity.ok(rollouts.events(id))
                : ResponseEntity.notFound().build();
    }

    /**
     * Rebuilds the message list from the server's rollout record — the crash-recovery
     * view: a client that lost its transcript GETs this instead of re-typing the history.
     * {@code upToSeq} optionally limits the replay to a prefix.
     */
    @GetMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable Long id,
            @RequestParam(required = false) Long upToSeq) {
        if (!owned(id)) return ResponseEntity.notFound().build();
        List<AiChatMessage> rebuilt = rollouts.rebuild(id,
                upToSeq == null ? Long.MAX_VALUE : upToSeq);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", id);
        out.put("messageCount", rebuilt.size());
        out.put("messages", rebuilt.stream().map(AiRolloutController::messageView).toList());
        return ResponseEntity.ok(out);
    }

    /**
     * Forks a NEW conversation from the recorded prefix up to {@code upToSeq}: the prefix
     * becomes the fork's message history, its rollout log starts as a copy under a
     * provenance header, and the SOURCE conversation is untouched from here on.
     */
    @PostMapping("/fork")
    @Transactional
    public ResponseEntity<Map<String, Object>> fork(@PathVariable Long id,
            @RequestParam Long upToSeq) {
        Long userId = userId();
        ConversationEntity source = conversations.findByIdAndUserId(id, userId).orElse(null);
        if (source == null) return ResponseEntity.notFound().build();

        List<AiChatMessage> prefix = rollouts.rebuild(id, upToSeq);
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException(
                    "No recorded messages up to seq " + upToSeq + " to fork from");
        }

        LocalDateTime now = LocalDateTime.now();
        ConversationEntity fork = new ConversationEntity();
        fork.setUserId(userId);
        fork.setTitle(clampTitle(("Fork: " + (source.getTitle() == null ? "" : source.getTitle()))
                .strip()));
        fork.setCreatedAt(now);
        fork.setUpdatedAt(now);
        fork.setWorkspaceRoot(source.getWorkspaceRoot());
        conversations.save(fork);

        int seq = 0;
        List<ChatMessageEntity> batch = new ArrayList<>(prefix.size());
        for (AiChatMessage message : prefix) {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setConversationId(fork.getId());
            entity.setSeq(seq++);
            entity.setRole(message.role() == AiChatMessage.Role.USER ? "user" : "assistant");
            entity.setContent(message.content() == null ? "" : message.content());
            batch.add(entity);
        }
        messages.saveAll(batch);

        boolean logCopied = rollouts.writeFork(id, upToSeq, fork.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", fork.getId());
        out.put("title", fork.getTitle());
        out.put("messagesCopied", prefix.size());
        out.put("upToSeq", upToSeq);
        out.put("rolloutLogCopied", logCopied);
        return ResponseEntity.ok(out);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> messageView(AiChatMessage message) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("role", message.role().name().toLowerCase());
        view.put("content", message.content());
        if (message.role() == AiChatMessage.Role.TOOL) {
            view.put("toolName", message.toolName());
            view.put("toolCallId", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            view.put("toolCalls", message.toolCalls());
        }
        return view;
    }

    private boolean owned(Long id) {
        return conversations.findByIdAndUserId(id, userId()).isPresent();
    }

    private long userId() {
        Long id = securityContext.currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user");
        return id;
    }

    private static String clampTitle(String title) {
        String t = title == null ? "" : title.strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
