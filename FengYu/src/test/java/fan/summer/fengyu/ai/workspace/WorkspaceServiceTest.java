package fan.summer.fengyu.ai.workspace;

import fan.summer.fengyu.database.entity.ai.ConversationEntity;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.security.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Workspace attach/detach validation: real directory checks, canonical storage, ownership. */
class WorkspaceServiceTest {

    private static final long USER = 7L;
    private static final long CONVERSATION = 41L;

    @TempDir
    Path root;

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final SecurityContext security = mock(SecurityContext.class);
    private WorkspaceService service;
    private ConversationEntity entity;

    @BeforeEach
    void wire() {
        when(security.currentUserId()).thenReturn(USER);
        entity = new ConversationEntity();
        entity.setId(CONVERSATION);
        entity.setUserId(USER);
        when(conversations.findByIdAndUserId(CONVERSATION, USER)).thenReturn(Optional.of(entity));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkspaceService(conversations, security, new WorkspaceReadState());
    }

    @Test
    void setWorkspaceCanonicalizesAndPersists() throws Exception {
        Path canonical = service.setWorkspace(CONVERSATION, root.toString());
        assertEquals(root.toRealPath(), canonical);
        assertEquals(canonical.toString(), entity.getWorkspaceRoot());
    }

    @Test
    void setWorkspaceExpandsHomeAndTrims() throws Exception {
        String home = System.getProperty("user.home");
        Path target = Files.createDirectories(Path.of(home).resolve(".fengyu-test-workspace-tmp"));
        try {
            Path canonical = service.setWorkspace(CONVERSATION, " ~/.fengyu-test-workspace-tmp ");
            assertEquals(target.toRealPath(), canonical);
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void setWorkspaceRejectsMissingDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setWorkspace(CONVERSATION, root.resolve("nope").toString()));
    }

    @Test
    void setWorkspaceRejectsPlainFile() throws Exception {
        Path file = Files.writeString(root.resolve("file.txt"), "x");
        assertThrows(IllegalArgumentException.class,
                () -> service.setWorkspace(CONVERSATION, file.toString()));
    }

    @Test
    void setWorkspaceRejectsBlankPath() {
        assertThrows(IllegalArgumentException.class, () -> service.setWorkspace(CONVERSATION, "  "));
    }

    @Test
    void setWorkspaceRejectsForeignConversation() {
        when(conversations.findByIdAndUserId(eq(99L), eq(USER))).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.setWorkspace(99L, root.toString()));
    }

    @Test
    void clearWorkspaceNullsTheColumn() {
        entity.setWorkspaceRoot(root.toString());
        service.clearWorkspace(CONVERSATION);
        assertNull(entity.getWorkspaceRoot());
    }

    @Test
    void bindingForReturnsNullWithoutStoredRoot() {
        assertNull(service.bindingFor(CONVERSATION));
    }

    @Test
    void bindingForReturnsNullForMissingDirectory() {
        entity.setWorkspaceRoot(root.resolve("vanished").toString());
        assertNull(service.bindingFor(CONVERSATION));
    }

    @Test
    void bindingForReturnsBindingForValidRoot() {
        entity.setWorkspaceRoot(root.toString());
        WorkspaceContext.Binding binding = service.bindingFor(CONVERSATION);
        assertEquals(CONVERSATION, binding.conversationId());
        assertEquals(root, binding.root());
    }

    @Test
    void bindingForHandlesNullConversationId() {
        assertNull(service.bindingFor(null));
    }
}
