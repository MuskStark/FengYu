package fan.summer.fengyu.database.entity.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One message within a {@link ConversationEntity}. Ordered by {@code seq} within a conversation.
 *
 * <p>{@code content}/{@code thinking} use {@code columnDefinition = "TEXT"} rather than
 * {@code @Lob}: plain {@code TEXT} is understood by every configured dialect (H2, SQLite, MySQL,
 * PostgreSQL) and avoids Hibernate mapping a {@code @Lob String} to a PostgreSQL large-object OID
 * (which needs streaming APIs and breaks simple reads).
 *
 * @since 4.0.0
 */
@Entity
@Table(name = "ai_chat_message",
        indexes = @Index(name = "idx_ai_msg_conversation", columnList = "conversation_id"))
@Data
public class ChatMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** Message order within the conversation, 0-based. */
    @Column(nullable = false)
    private int seq;

    /** "user" or "assistant". */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** Assistant reasoning (Qwen3 THINK regions); null/empty for user turns. */
    @Column(columnDefinition = "TEXT")
    private String thinking;

    /**
     * JSON array of the attachments a USER message was sent with
     * ({@code [{"name":"a.csv","kind":"file"}]}). Metadata only — display after a restart, never
     * authorization: old rows have null and load as "no attachments" (E13 compat).
     */
    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    /**
     * JSON array of an ASSISTANT message's ordered parts
     * ({@code [{"kind":"text","text":"…"},{"kind":"tool","activity":{…}},…]}) — the interleaved
     * text/thinking/tool timeline as it streamed, so the transcript can render turns in true
     * order after reload. Display metadata only, opaque to the server (same contract as
     * {@code attachments}); null on old rows degrades to the legacy bucket layout.
     *
     * @since 4.1.0
     */
    @Column(name = "parts", columnDefinition = "TEXT")
    private String parts;
}
