package fan.summer.fengyu.database.repository.ai;

import fan.summer.fengyu.FengYuApplication;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sidebar's hottest query contract (per-user, most-recently-updated first) and
 * the additive {@code idx_ai_conversation_user_updated} index declared on the entity:
 * every sibling AI entity carries the composite user index, and ddl-auto=update creates
 * it on existing installs too — the additive-index channel a portable Flyway script
 * could not use (MySQL has no CREATE INDEX IF NOT EXISTS; Flyway also runs before
 * Hibernate creates the fresh-install table).
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = FengYuApplication.class)
class ConversationRepositoryTest {

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private DataSource dataSource;

    @Test
    void conversationsAreScopedToTheirOwnerAndOrderedByRecency() {
        ConversationEntity mine = conversation(7L, "mine", 5);
        ConversationEntity other = conversation(8L, "theirs", 20);
        ConversationEntity mineOlder = conversation(7L, "mine older", 10);
        conversations.saveAll(List.of(mine, other, mineOlder));

        List<ConversationEntity> sidebar = conversations.findByUserIdOrderByUpdatedAtDesc(7L);

        assertEquals(2, sidebar.size());
        assertEquals("mine", sidebar.get(0).getTitle());
        assertEquals("mine older", sidebar.get(1).getTitle());
        Optional<ConversationEntity> found = conversations.findByIdAndUserId(mine.getId(), 7L);
        assertTrue(found.isPresent());
        assertTrue(conversations.findByIdAndUserId(mine.getId(), 8L).isEmpty(),
                "cross-user access must be impossible");
    }

    @Test
    void theUserUpdatedIndexExistsAfterDdlAuto() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData().getIndexInfo(
                     null, null, "AI_CONVERSATION", false, false)) {
            boolean found = false;
            while (indexes.next()) {
                if ("IDX_AI_CONVERSATION_USER_UPDATED".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    found = true;
                }
            }
            assertTrue(found, "idx_ai_conversation_user_updated must be created from the entity");
        }
    }

    private ConversationEntity conversation(Long userId, String title, int updatedMinutesAgo) {
        ConversationEntity entity = new ConversationEntity();
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(updatedMinutesAgo + 1));
        entity.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(updatedMinutesAgo));
        return entity;
    }
}
