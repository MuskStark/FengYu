package fan.summer.fengyu.security;

/**
 * Provides the current user identity. All business code reads the current user via
 * {@link #currentUserId()} rather than a static or thread-local, so swapping the
 * implementation (offline virtual user vs. real logged-in user) requires no call-site changes.
 */
public interface SecurityContext {

    /** The current user's ID. Local offline mode always returns the virtual user id (1). */
    Long currentUserId();

    /** Whether a user is authenticated. Local offline mode always returns true. */
    boolean isAuthenticated();
}
