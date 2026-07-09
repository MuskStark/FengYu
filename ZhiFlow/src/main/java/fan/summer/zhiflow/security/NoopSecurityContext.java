package fan.summer.zhiflow.security;

import fan.summer.zhiflow.database.SecurityConstants;

/**
 * SecurityContext for local offline mode. Always returns the virtual user identity.
 */
public class NoopSecurityContext implements SecurityContext {

    @Override
    public Long currentUserId() {
        return SecurityConstants.LOCAL_VIRTUAL_USER_ID;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }
}
