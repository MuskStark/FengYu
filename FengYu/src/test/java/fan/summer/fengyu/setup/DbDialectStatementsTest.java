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
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS " + USER + " PASSWORD '" + PW + "'",
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
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + PW + "'",
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
        assertEquals(List.of(
            "CREATE ROLE \"" + USER + "\" LOGIN PASSWORD '" + PW + "'",
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
