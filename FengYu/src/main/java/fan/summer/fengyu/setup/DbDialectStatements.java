package fan.summer.fengyu.setup;

import java.util.List;

/**
 * Pure per-DB-type DDL string generator for plugin DB provisioning. No execution, no I/O —
 * the provisioner runs these via a {@code java.sql.Connection} opened with admin creds.
 *
 * <p>Design notes:
 * <ul>
 *   <li>H2 / PostgreSQL use schema-granular isolation inside the host's existing database;
 *       MySQL uses a per-plugin database (its GRANT model is database-granular).</li>
 *   <li>All {@code CREATE} statements are idempotent ({@code IF NOT EXISTS}).</li>
 *   <li>SQLite emits NO DDL — the engine has no RBAC and no TCP server. It is a documented
 *       technical exception; isolation for SQLite stays file-level (host-allocated path).</li>
 * </ul>
 *
 * <p>Generated strings embed caller-supplied identifiers. The provisioner constructs
 * {@code schemaName} and {@code userName} from a sanitized transform of the plugin id, and
 * passwords are URL-safe base64 (never a literal single-quote), so no escaping is needed.
 */
final class DbDialectStatements {

    private DbDialectStatements() {}

    /** {@code true} when the engine supports CREATE USER / GRANT (H2, MySQL, PostgreSQL). */
    static boolean supportsRbac(DbType type) {
        return type != DbType.SQLITE;
    }

    static List<String> createStatements(DbType type, String schemaName, String userName, String password) {
        return switch (type) {
            case H2 -> List.of(
                    "CREATE USER IF NOT EXISTS " + userName + " PASSWORD '" + password + "'",
                    "CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION " + userName,
                    "GRANT ALL ON SCHEMA " + schemaName + " TO " + userName);
            case MYSQL -> List.of(
                    "CREATE USER IF NOT EXISTS '" + userName + "'@'127.0.0.1' IDENTIFIED BY '" + password + "'",
                    "CREATE DATABASE IF NOT EXISTS `" + schemaName + "`",
                    "GRANT ALL PRIVILEGES ON `" + schemaName + "`.* TO '" + userName + "'@'127.0.0.1'");
            case POSTGRESQL -> List.of(
                    "CREATE ROLE \"" + userName + "\" LOGIN PASSWORD '" + password + "'",
                    "CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION \"" + userName + "\"",
                    "GRANT USAGE, CREATE ON SCHEMA " + schemaName + " TO \"" + userName + "\"");
            case SQLITE -> List.of();
        };
    }

    static List<String> dropStatements(DbType type, String schemaName, String userName) {
        return switch (type) {
            case H2 -> List.of(
                    "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE",
                    "DROP USER IF EXISTS " + userName);
            case MYSQL -> List.of(
                    "DROP DATABASE IF EXISTS `" + schemaName + "`",
                    "DROP USER IF EXISTS '" + userName + "'@'127.0.0.1'");
            case POSTGRESQL -> List.of(
                    "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE",
                    "DROP ROLE IF EXISTS \"" + userName + "\"");
            case SQLITE -> List.of();
        };
    }
}
