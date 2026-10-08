package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.security.SecurityContext;
import fan.summer.fengyu.database.repository.ai.ChatMessageRepository;
import fan.summer.fengyu.database.repository.ai.ConversationRepository;
import fan.summer.fengyu.ai.session.AiRolloutService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the unauthenticated mapping of the rollout endpoints: a missing user is a 401
 * client problem, not a 500 (mirrors ConversationController's pinned contract).
 */
class AiRolloutControllerTest {

    @Test
    void eventsWithoutAnAuthenticatedUserAnswers401Not500() {
        SecurityContext security = mock(SecurityContext.class);
        AiRolloutController controller = new AiRolloutController(
                mock(AiRolloutService.class), mock(ConversationRepository.class),
                mock(ChatMessageRepository.class), security);
        when(security.currentUserId()).thenReturn(null);

        org.springframework.web.server.ResponseStatusException rejected = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.events(7L));

        assertEquals(401, rejected.getStatusCode().value());
    }
}
