package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private final fan.summer.fengyu.ai.workspace.WorkspaceService workspaces =
            mock(fan.summer.fengyu.ai.workspace.WorkspaceService.class);

    private ConversationController controller() {
        when(security.currentUserId()).thenReturn(1L);
        return new ConversationController(conversations, messages, security, workspaces);
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

    // ── attachment metadata (E13: persisted for display, never authorization) ─────────

    @Test
    void attachmentMetadataRoundTripsThroughCreateAndDetail() {
        ConversationController controller = controller();
        var saved = new java.util.ArrayList<fan.summer.fengyu.database.entity.ai.ChatMessageEntity>();
        when(messages.saveAll(anyList())).thenAnswer(invocation -> {
            saved.addAll((java.util.List<fan.summer.fengyu.database.entity.ai.ChatMessageEntity>)
                    invocation.getArgument(0));
            return saved;
        });
        when(messages.findByConversationIdOrderBySeqAsc(null)).thenReturn(saved);

        controller.create(new ConversationController.ConversationDto("t", List.of(
                new ConversationController.MessageDto("user", "here", null, List.of(
                        new ConversationController.AttachmentDto("报表.xlsx", "file"),
                        new ConversationController.AttachmentDto("资料", "directory"))),
                new ConversationController.MessageDto("assistant", "done", null))));

        assertEquals(2, saved.size());
        assertTrue(saved.get(0).getAttachments().contains("报表.xlsx"));
        assertTrue(saved.get(1).getAttachments() == null,
                "assistant turns carry no attachment metadata");
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailReturnsPersistedAttachmentsAndOldRowsLoadWithoutThem() {
        ConversationController controller = controller();
        fan.summer.fengyu.database.entity.ai.ChatMessageEntity withAttachments =
                new fan.summer.fengyu.database.entity.ai.ChatMessageEntity();
        withAttachments.setRole("user");
        withAttachments.setContent("here");
        withAttachments.setAttachments(
                "[{\"name\":\"报表.xlsx\",\"kind\":\"file\"},{\"name\":\"资料\",\"kind\":\"directory\"}]");
        fan.summer.fengyu.database.entity.ai.ChatMessageEntity legacyRow =
                new fan.summer.fengyu.database.entity.ai.ChatMessageEntity();
        legacyRow.setRole("user");
        legacyRow.setContent("old");
        legacyRow.setAttachments(null); // pre-4.0.0-rc rows have no attachments column value
        when(messages.findByConversationIdOrderBySeqAsc(7L))
                .thenReturn(List.of(legacyRow, withAttachments));

        ConversationEntity existing = new ConversationEntity();
        existing.setId(7L);
        existing.setTitle("t");
        when(conversations.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(existing));

        Map<String, Object> detail = controller.get(7L).getBody();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");

        assertFalse(messages.get(0).containsKey("attachments"),
                "legacy rows load clean with no attachments key");
        assertEquals(List.of(
                Map.of("name", "报表.xlsx", "kind", "file"),
                Map.of("name", "资料", "kind", "directory")),
                messages.get(1).get("attachments"),
                "E13: persisted attachment metadata displays again after restart");
    }
}
