package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Conversations for the AI chat sidebar history. User-scoped queries only.
 *
 * @since 4.0.0
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {

    /** All conversations for a user, most-recently-updated first (sidebar order). */
    List<ConversationEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /** A single conversation, scoped to its owner so cross-user access is impossible. */
    Optional<ConversationEntity> findByIdAndUserId(Long id, Long userId);
}
