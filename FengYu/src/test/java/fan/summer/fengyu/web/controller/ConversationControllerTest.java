package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bounded message-list contract of {@code ConversationController}: the frontend PUTs the whole
 * turn list after every assistant turn, so {@code replaceMessages} caps the batch instead of
 * persisting an arbitrary number of rows per request.
 */
class ConversationControllerTest {

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final SecurityContext security = mock(SecurityContext.class);

    private ConversationController controller() {
        when(security.currentUserId()).thenReturn(1L);
        return new ConversationController(conversations, messages, security);
    }

    private static List<ConversationController.MessageDto> turns(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ConversationController.MessageDto("user", "m" + i, null))
                .toList();
    }

    @Test
    void createRejectsMoreMessagesThanTheCeiling() {
        ConversationController controller = controller();
        List<ConversationController.MessageDto> tooMany =
                turns(ConversationController.MAX_MESSAGES_PER_CONVERSATION + 1);

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> controller.create(new ConversationController.ConversationDto("t", tooMany)));

        assertTrue(rejected.getMessage().contains("maximum"), rejected.getMessage());
        verify(messages, never()).saveAll(anyList());
    }

    @Test
    void updateRejectsMoreMessagesThanTheCeiling() {
        ConversationController controller = controller();
        ConversationEntity existing = new ConversationEntity();
        when(conversations.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(existing));

        List<ConversationController.MessageDto> tooMany =
                turns(ConversationController.MAX_MESSAGES_PER_CONVERSATION + 1);

        assertThrows(IllegalArgumentException.class, () -> controller.update(7L,
                new ConversationController.ConversationDto("t", tooMany)));
        verify(messages, never()).saveAll(anyList());
    }

    @Test
    void createAcceptsExactlyTheCeiling() {
        ConversationController controller = controller();

        controller.create(new ConversationController.ConversationDto("t",
                turns(ConversationController.MAX_MESSAGES_PER_CONVERSATION)));

        verify(conversations).save(any(ConversationEntity.class));
        verify(messages).saveAll(anyList());
    }

    @Test
    void createWithNoMessageListPersistsAnEmptyConversation() {
        ConversationController controller = controller();

        controller.create(new ConversationController.ConversationDto("t", null));

        verify(conversations).save(any(ConversationEntity.class));
        verify(messages).deleteByConversationId(any());
        verify(messages, never()).saveAll(anyList());
        assertEquals(2000, ConversationController.MAX_MESSAGES_PER_CONVERSATION);
    }
}
