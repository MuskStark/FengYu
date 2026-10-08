package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatArtifactStore;
import fan.summer.fengyu.ai.ChatResourceScopeService;
import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership contract of the artifact-only endpoints (path/download/list): a bare
 * artifactId or conversationId must never be enough to read, save, or enumerate another
 * user's chat artifacts — the same ownership-checked gate every scope operation applies.
 */
class ChatResourceControllerTest {

    private static final long USER = 1L;

    private final ChatResourceScopeService scopes = mock(ChatResourceScopeService.class);
    private final ChatArtifactStore artifacts = mock(ChatArtifactStore.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final SecurityContext security = mock(SecurityContext.class);

    private ChatResourceController controller() {
        when(security.currentUserId()).thenReturn(USER);
        return new ChatResourceController(scopes, artifacts, security, conversations);
    }

    private static ChatArtifactStore.Artifact artifact(String scopeId, Long conversationId) {
        return new ChatArtifactStore.Artifact("art-1", scopeId, conversationId,
                "out.txt", 12, Instant.parse("2026-10-08T00:00:00Z"), "pending", null, null);
    }

    /** save(): a foreign scopeId is rejected before the artifact's bytes are touched. */
    @Test
    void saveRejectsAForeignScopeBeforeTouchingTheArtifact() {
        when(scopes.snapshot("scope-foreign", USER))
                .thenThrow(new IllegalArgumentException("Resource scope belongs to another session"));

        assertThrows(IllegalArgumentException.class, () -> controller()
                .save("scope-foreign", "art-1",
                        new ChatResourceController.SaveRequest("/tmp/out.txt")));
        verify(artifacts, never()).get(any());
        verify(artifacts, never()).save(any(), any());
    }

    /** path()/download(): a scoped artifact of another user is rejected via the scope gate. */
    @Test
    void artifactEndpointsRejectAForeignScopedArtifact() {
        when(artifacts.get("art-1")).thenReturn(artifact("scope-foreign", 7L));
        when(scopes.snapshot("scope-foreign", USER))
                .thenThrow(new IllegalArgumentException("Resource scope belongs to another session"));

        assertThrows(IllegalArgumentException.class, () -> controller().savedPath("art-1"));
        assertThrows(IllegalArgumentException.class, () -> controller().download("art-1"));
        verify(artifacts, never()).savedPath(any());
        verify(artifacts, never()).pendingPath(any());
    }

    /** path()/download(): a legacy (scope-less) artifact anchors on its conversation's owner. */
    @Test
    void artifactEndpointsRejectALegacyArtifactOfAForeignConversation() {
        when(artifacts.get("art-1")).thenReturn(artifact(null, 9L));
        when(conversations.findByIdAndUserId(9L, USER)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> controller().savedPath("art-1"));
        assertThrows(IllegalArgumentException.class, () -> controller().download("art-1"));
    }

    /** path()/download(): the owner of the bound conversation passes the gate. */
    @Test
    void artifactEndpointsAllowTheOwningUser() {
        when(artifacts.get("art-1"))
                .thenReturn(artifact(null, 9L));
        when(conversations.findByIdAndUserId(9L, USER))
                .thenReturn(Optional.of(new ConversationEntity()));
        when(artifacts.pendingPath("art-1"))
                .thenReturn(java.nio.file.Path.of("build/out.txt"));

        controller().download("art-1");

        verify(artifacts).pendingPath("art-1");
    }

    /** The conversation-keyed pending listing refuses another user's conversation id. */
    @Test
    void pendingListingRejectsAForeignConversation() {
        when(conversations.findByIdAndUserId(9L, USER)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> controller().pendingByConversation(9L));
        verify(artifacts, never()).listPendingByConversation(anyLong());
    }

    /** Scoped-artifact gate is per user: the same scope works for its owner. */
    @Test
    void scopedArtifactGateUsesTheCallingUser() {
        when(artifacts.get("art-1")).thenReturn(artifact("scope-mine", 7L));
        when(scopes.snapshot("scope-mine", USER))
                .thenReturn(new ChatResourceScopeService.ScopeSnapshot(
                        java.util.List.of(), null, 7L));
        when(artifacts.savedPath("art-1"))
                .thenReturn(java.nio.file.Path.of("build/saved.txt"));

        controller().savedPath("art-1");

        verify(scopes).snapshot(eq("scope-mine"), anyLong());
    }
}
