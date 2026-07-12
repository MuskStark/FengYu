package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Messages belonging to a {@link fan.summer.fengyu.database.entity.ai.ConversationEntity}.
 *
 * @since 4.0.0
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** Ordered message list for a conversation. */
    List<ChatMessageEntity> findByConversationIdOrderBySeqAsc(Long conversationId);

    /** Clears a conversation's messages before a full re-save (idempotent replace). */
    @Transactional
    void deleteByConversationId(Long conversationId);
}
