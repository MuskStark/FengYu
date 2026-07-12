package fan.summer.fengyu.setup;

/**
 * Supported database types for the multi-datasource setup wizard.
 *
 * <p>Each enum constant bundles its JDBC driver class, Hibernate dialect, URL template,
 * and whether it is an embedded (file-based) database. New database support is added by
 * extending this enum — the wizard UI and {@code DataSourceConfigService} derive everything
 * from these constants.
 *
 * <p>URL template placeholders: {@code {path}} for embedded databases,
 * {@code {host}}/{@code {port}}/{@code {db}} for remote servers.
 */
public enum DbType {
    H2("org.h2.Driver", "org.hibernate.dialect.H2Dialect",
            "jdbc:h2:file:{path};AUTO_SERVER=TRUE", true),
    SQLITE("org.sqlite.JDBC", "org.hibernate.community.dialect.SQLiteDialect",
            "jdbc:sqlite:{path}", true),
    MYSQL("com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
            "jdbc:mysql://{host}:{port}/{db}", false),
    POSTGRESQL("org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
            "jdbc:postgresql://{host}:{port}/{db}", false);

    public final String driver;
    public final String dialect;
    public final String urlTemplate;
    /** {@code true} for file-based embedded databases (no host/port needed). */
    public final boolean embedded;

    DbType(String driver, String dialect, String urlTemplate, boolean embedded) {
        this.driver = driver;
        this.dialect = dialect;
        this.urlTemplate = urlTemplate;
        this.embedded = embedded;
    }

    /** Resolves a type by its lowercase name (case-insensitive). Throws on unknown. */
    public static DbType fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("db type is null");
        }
        return valueOf(name.trim().toUpperCase());
    }
}
