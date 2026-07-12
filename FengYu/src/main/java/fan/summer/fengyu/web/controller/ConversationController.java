package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.SecurityConstants;
import fan.summer.fengyu.database.entity.ai.ChatMessageEntity;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent AI chat history — the sidebar conversation list. Conversations and their messages are
 * stored via JPA (portable across every configured {@code DbType}), user-scoped to the local
 * virtual user.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/ai/conversations} — summaries (id/title/updatedAt), newest first</li>
 *   <li>{@code GET  /api/ai/conversations/{id}} — one conversation with its full message list</li>
 *   <li>{@code POST /api/ai/conversations} — create; returns the new conversation with its id</li>
 *   <li>{@code PUT  /api/ai/conversations/{id}} — replace title + messages (idempotent save)</li>
 *   <li>{@code DELETE /api/ai/conversations/{id}} — remove a conversation and its messages</li>
 * </ul>
 *
 * <p>The frontend keeps the live conversation in memory and calls PUT after each assistant turn
 * completes, so history survives refresh/restart. The message body is the full turn list, which
 * the server replaces wholesale — simpler and race-free versus per-message deltas.
 *
 * @since 4.0.0
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class ConversationController {

    private static final long USER_ID = SecurityConstants.LOCAL_VIRTUAL_USER_ID;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    public ConversationController(ConversationRepository conversations, ChatMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationEntity c : conversations.findByUserIdOrderByUpdatedAtDesc(USER_ID)) {
            out.add(summary(c));
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        return conversations.findByIdAndUserId(id, USER_ID)
                .map(c -> ResponseEntity.ok(detail(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ConversationDto body) {
        LocalDateTime now = LocalDateTime.now();
        ConversationEntity c = new ConversationEntity();
        c.setUserId(USER_ID);
        c.setTitle(clampTitle(body.title()));
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        conversations.save(c);
        replaceMessages(c.getId(), body.messages());
        return detail(c);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody ConversationDto body) {
        return conversations.findByIdAndUserId(id, USER_ID)
                .map(c -> {
                    c.setTitle(clampTitle(body.title()));
                    c.setUpdatedAt(LocalDateTime.now());
                    conversations.save(c);
                    replaceMessages(c.getId(), body.messages());
                    return ResponseEntity.ok(detail(c));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return conversations.findByIdAndUserId(id, USER_ID)
                .map(c -> {
                    messages.deleteByConversationId(c.getId());
                    conversations.delete(c);
                    return new ResponseEntity<Void>(HttpStatus.NO_CONTENT);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Deletes existing messages and inserts the supplied list in order. */
    private void replaceMessages(Long conversationId, List<MessageDto> list) {
        messages.deleteByConversationId(conversationId);
        if (list == null) return;
        int seq = 0;
        List<ChatMessageEntity> batch = new ArrayList<>(list.size());
        for (MessageDto m : list) {
            ChatMessageEntity e = new ChatMessageEntity();
            e.setConversationId(conversationId);
            e.setSeq(seq++);
            e.setRole("assistant".equals(m.role()) ? "assistant" : "user");
            e.setContent(m.content() == null ? "" : m.content());
            e.setThinking(m.thinking());
            batch.add(e);
        }
        messages.saveAll(batch);
    }

    private Map<String, Object> summary(ConversationEntity c) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", c.getId());
        m.put("title", c.getTitle());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        m.put("updatedAt", c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> detail(ConversationEntity c) {
        Map<String, Object> m = summary(c);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (ChatMessageEntity e : messages.findByConversationIdOrderBySeqAsc(c.getId())) {
            Map<String, Object> dm = new java.util.HashMap<>();
            dm.put("role", e.getRole());
            dm.put("content", e.getContent() == null ? "" : e.getContent());
            dm.put("thinking", e.getThinking() == null ? "" : e.getThinking());
            msgs.add(dm);
        }
        m.put("messages", msgs);
        return m;
    }

    private static String clampTitle(String title) {
        if (title == null) return "";
        String t = title.strip();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    // ── DTOs ───────────────────────────────────────────────────────────────

    public record ConversationDto(String title, List<MessageDto> messages) {}
    public record MessageDto(String role, String content, String thinking) {}
}
