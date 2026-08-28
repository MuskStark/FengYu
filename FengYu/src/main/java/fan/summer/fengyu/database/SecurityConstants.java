package fan.summer.fengyu.database;

/**
 * Security-related constants for the user system.
 *
 * <p>Local offline mode operates as a single virtual user (id=1, username "Summer").
 * All unauthenticated requests are attributed to this user. When login is enabled in a
 * future phase, real users start from id=2.
 */
public final class SecurityConstants {

    private SecurityConstants() {}

    /** The virtual user ID used in local offline mode. All unauthenticated data belongs here. */
    public static final long LOCAL_VIRTUAL_USER_ID = 1L;

    /** The fixed username of the local virtual user. */
    public static final String LOCAL_VIRTUAL_USERNAME = "Summer";
}
