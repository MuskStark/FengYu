package fan.summer.fengyu.setup;

/**
 * Result of a {@code POST /api/setup/test-connection} attempt.
 *
 * @param success        whether the connection succeeded
 * @param dialect        the resolved Hibernate dialect (on success)
 * @param serverVersion  the database server version string (on success); null on failure
 * @param error          the error message (on failure); null on success
 */
public record ConnectionTestResult(
        boolean success,
        String dialect,
        String serverVersion,
        String error
) {
    public static ConnectionTestResult ok(String dialect, String serverVersion) {
        return new ConnectionTestResult(true, dialect, serverVersion, null);
    }

    public static ConnectionTestResult fail(String error) {
        return new ConnectionTestResult(false, null, null, error);
    }
}
