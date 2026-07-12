package fan.summer.fengyu.setup;

/**
 * Immutable datasource configuration — the fully-resolved connection descriptor.
 *
 * <p>Built by {@link DataSourceConfigService} from a {@link DbType} + {@link WizardParams},
 * persisted to {@code datasource.properties}, and used to construct the HikariCP DataSource
 * on APP-mode startup.
 *
 * @param type      the database type
 * @param url       the fully-assembled JDBC URL
 * @param driver    the JDBC driver class name
 * @param dialect   the Hibernate dialect class name
 * @param username  remote DB username; blank for embedded H2/SQLite
 * @param password  remote DB password (plaintext in-memory; encrypted on disk); blank for embedded
 * @param filePath  embedded DB file path (H2/SQLite); null for remote
 */
public record DataSourceConfig(
        DbType type,
        String url,
        String driver,
        String dialect,
        String username,
        String password,
        String filePath
) {}
