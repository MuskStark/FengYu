package fan.summer.zhiflow.security;

import fan.summer.zhiflow.database.SecurityConstants;

/**
 * No-operation auth provider for local offline mode. Login is disabled — every request
 * is treated as the virtual user (id=1, "ZFlow-Summer").
 */
public class NoopAuthProvider implements AuthProvider {

    @Override
    public AuthResult authenticate(AuthRequest request) {
        return new AuthResult(SecurityConstants.LOCAL_VIRTUAL_USER_ID,
                SecurityConstants.LOCAL_VIRTUAL_USERNAME, "local");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
