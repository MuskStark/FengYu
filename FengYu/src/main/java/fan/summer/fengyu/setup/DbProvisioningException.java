package fan.summer.fengyu.setup;

/**
 * Thrown when plugin DB provisioning cannot complete — typically because admin credentials are
 * absent or lack the {@code CREATE USER / SCHEMA} privileges the DDL needs. The host MUST surface
 * this to the user and MUST NOT silently fall back to sharing the host's global DB credentials
 * (that would defeat the DB-level isolation invariant).
 */
public class DbProvisioningException extends RuntimeException {
    public DbProvisioningException(String message) { super(message); }
    public DbProvisioningException(String message, Throwable cause) { super(message, cause); }
}
