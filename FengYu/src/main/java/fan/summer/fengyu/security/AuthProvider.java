package fan.summer.fengyu.security;

/**
 * Pluggable authentication provider. Implementations handle specific auth mechanisms
 * (local username/password, OAuth2/OIDC SSO). The Noop implementation covers local
 * offline mode (no login required).
 *
 * <p>This is groundwork — only {@link NoopAuthProvider} is implemented in Phase 4.
 * LocalAuthProvider and SsoAuthProvider arrive in later phases.
 */
public interface AuthProvider {

    /**
     * Authenticates credentials. Throws {@code AuthenticationException} (to be defined
     * when login is implemented) on failure.
     *
     * @param request the authentication request (username/password, OAuth token, etc.)
     * @return the authenticated user identity
     */
    AuthResult authenticate(AuthRequest request);

    /**
     * Whether login is enabled. Local offline mode returns {@code false} (no login required);
     * enabled providers return {@code true}.
     */
    boolean isEnabled();
}
