package fan.summer.fengyu.setup;

/**
 * Immutable datasource configuration — the fully-resolved connection descriptor.
 *
 * <p>Built by {@link DataSourceConfigService} from a {@link DbType} + {@link WizardParams},
 * persisted to {@code datasource.properties}, and used to construct the HikariCP DataSource
 * on APP-mode startup.
 *
 * @param type           the database type
 * @param url            the fully-assembled JDBC URL
 * @param driver         the JDBC driver class name
 * @param dialect        the Hibernate dialect class name
 * @param username       remote DB username; blank for embedded H2/SQLite
 * @param password       remote DB password (plaintext in-memory; encrypted on disk); blank for embedded
 * @param filePath       embedded DB file path (H2/SQLite); null for remote
 * @param adminUsername  optional admin username used ONLY for plugin DB provisioning DDL
 *                       (CREATE USER/SCHEMA/GRANT); null/blank when not configured (e.g. SQLite).
 *                       Never injected into plugin worker environments.
 * @param adminPassword  optional admin password (plaintext in-memory; encrypted on disk); null/blank
 *                       when not configured.
 */
public record DataSourceConfig(
        DbType type,
        String url,
        String driver,
        String dialect,
        String username,
        String password,
        String filePath,
        String adminUsername,
        String adminPassword
) {
    /** Backwards-compatible constructor for callers that omit admin credentials. */
    public DataSourceConfig(DbType type, String url, String driver, String dialect,
            String username, String password, String filePath) {
        this(type, url, driver, dialect, username, password, filePath, null, null);
    }
}
