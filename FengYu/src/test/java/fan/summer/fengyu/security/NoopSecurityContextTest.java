package fan.summer.fengyu.security;

import fan.summer.fengyu.database.SecurityConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoopSecurityContextTest {

    private final SecurityContext ctx = new NoopSecurityContext();

    @Test
    void currentUserId_returnsVirtualUserId() {
        assertEquals(SecurityConstants.LOCAL_VIRTUAL_USER_ID, ctx.currentUserId());
    }

    @Test
    void isAuthenticated_alwaysTrue() {
        assertTrue(ctx.isAuthenticated());
    }
}
