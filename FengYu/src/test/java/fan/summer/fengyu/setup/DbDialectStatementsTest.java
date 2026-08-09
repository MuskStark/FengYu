package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDialectStatementsTest {
    private static final String SCHEMA = "fengyu_fan_summer_email";
    private static final String USER = "fengyu_plugin_email";
    private static final String PW = "S3cr3t!";

    @Test
    void h2CreateCreatesUserSchemaAndGrantsAll() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.H2, SCHEMA, USER, PW);
        // P1-5: an explicit ALTER USER ... SET PASSWORD follows CREATE so a re-provision of a
        // leftover user rotates the password to the freshly-generated one (CREATE IF NOT EXISTS is
        // a no-op on an existing user and would leave the stored password unable to log in).
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS " + USER + " PASSWORD '" + PW + "'",
            "ALTER USER " + USER + " SET PASSWORD '" + PW + "'",
            "CREATE SCHEMA IF NOT EXISTS " + SCHEMA + " AUTHORIZATION " + USER,
            "GRANT ALL ON SCHEMA " + SCHEMA + " TO " + USER), ddl);
    }

    @Test
    void h2DropDropsSchemaThenUser() {
        assertEquals(List.of(
            "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE",
            "DROP USER IF EXISTS " + USER),
            DbDialectStatements.dropStatements(DbType.H2, SCHEMA, USER));
    }

    @Test
    void mysqlCreateCreatesUserDatabaseAndGrantsPrivileges() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.MYSQL, SCHEMA, USER, PW);
        // P1-5: ALTER USER ... IDENTIFIED BY rotates the password for an existing user.
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + PW + "'",
            "ALTER USER '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + PW + "'",
            "CREATE DATABASE IF NOT EXISTS `" + SCHEMA + "`",
            "GRANT ALL PRIVILEGES ON `" + SCHEMA + "`.* TO '" + USER + "'@'127.0.0.1'"), ddl);
    }

    @Test
    void mysqlDropDropsDatabaseThenUser() {
        assertEquals(List.of(
            "DROP DATABASE IF EXISTS `" + SCHEMA + "`",
            "DROP USER IF EXISTS '" + USER + "'@'127.0.0.1'"),
            DbDialectStatements.dropStatements(DbType.MYSQL, SCHEMA, USER));
    }

    @Test
    void postgresCreateCreatesRoleSchemaAndGrantsUsageCreate() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.POSTGRESQL, SCHEMA, USER, PW);
        // PostgreSQL has no "CREATE ROLE IF NOT EXISTS". The create arm wraps the CREATE ROLE in a
        // DO block that swallows the DUPLICATE_OBJECT error, so a leftover role from a failed DROP
        // is reused instead of fatal — the statement is idempotent. P1-5: ALTER ROLE ... PASSWORD
        // then rotates the password for that leftover role so the stored creds always work.
        assertEquals(List.of(
            "DO $$ BEGIN"
                + " CREATE ROLE \"" + USER + "\" LOGIN PASSWORD '" + PW + "';"
                + " EXCEPTION WHEN DUPLICATE_OBJECT THEN NULL;"
                + " END $$",
            "ALTER ROLE \"" + USER + "\" LOGIN PASSWORD '" + PW + "'",
            "CREATE SCHEMA IF NOT EXISTS " + SCHEMA + " AUTHORIZATION \"" + USER + "\"",
            "GRANT USAGE, CREATE ON SCHEMA " + SCHEMA + " TO \"" + USER + "\""), ddl);
    }

    @Test
    void postgresDropDropsSchemaThenRole() {
        assertEquals(List.of(
            "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE",
            "DROP ROLE IF EXISTS \"" + USER + "\""),
            DbDialectStatements.dropStatements(DbType.POSTGRESQL, SCHEMA, USER));
    }

    @Test
    void sqliteEmitsNoDdlAndIsFlaggedAsNonRbac() {
        assertTrue(DbDialectStatements.createStatements(DbType.SQLITE, SCHEMA, USER, PW).isEmpty());
        assertTrue(DbDialectStatements.dropStatements(DbType.SQLITE, SCHEMA, USER).isEmpty());
        assertFalse(DbDialectStatements.supportsRbac(DbType.SQLITE));
        assertTrue(DbDialectStatements.supportsRbac(DbType.H2));
        assertTrue(DbDialectStatements.supportsRbac(DbType.MYSQL));
        assertTrue(DbDialectStatements.supportsRbac(DbType.POSTGRESQL));
    }
}
