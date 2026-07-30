package fan.summer.fengyu.plugin.email.database;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {
    @TempDir Path temp;

    @Test void h2MigrationCreatesNinePrefixedTablesAndIsRepeatable() throws Exception {
        var data = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:email;DB_CLOSE_DELAY=-1", "sa", "");
        assertSchema("h2", data);
    }

    @Test void sqliteMigrationCreatesNinePrefixedTablesAndIsRepeatable() throws Exception {
        var data = new UnpooledDataSource("org.sqlite.JDBC", "jdbc:sqlite:" + temp.resolve("email.db"), "", "");
        assertSchema("sqlite", data);
    }

    @Test void sqliteMigrationUpgradesVersionOneArchiveTimestampsWithoutLosingRows() throws Exception {
        var data = new UnpooledDataSource("org.sqlite.JDBC", "jdbc:sqlite:" + temp.resolve("legacy.db"), "", "");
        Instant sentAt = Instant.parse("2026-04-05T06:07:08.123Z");
        try (Connection connection = data.getConnection(); Statement statement = connection.createStatement()) {
            String legacy = Files.readString(Path.of("src/main/resources/db/sqlite/V1__email_schema.sql"))
                .replace("CURRENT_TIMESTAMP", "CURRENT_TEXT")
                .replace("sent_at INTEGER, received_at INTEGER", "sent_at TEXT, received_at TEXT")
                .replace("archived_at INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000)",
                    "archived_at TEXT NOT NULL DEFAULT CURRENT_TEXT");
            for (String sql : legacy.split(";")) if (!sql.isBlank()) statement.execute(sql.trim());
            statement.execute("INSERT INTO FENGYU_PL_Email_Archive(account_id,account_email,folder,message_uid,"
                + "sent_at,received_at,eml_path,archived_at) VALUES(7,'owner@example.com','INBOX','42','"
                + sentAt.toEpochMilli() + "','" + sentAt.toEpochMilli() + "','/tmp/42.eml','"
                + sentAt.toEpochMilli() + "')");
            statement.execute("INSERT INTO FENGYU_PL_Email_Account(display_name,email,encrypted_password,smtp_host,"
                + "smtp_port,smtp_security,is_default,created_at) VALUES('Legacy','legacy@example.com','x','smtp',25,"
                + "'PLAIN',1,'CURRENT_TEXT')");
        }

        SchemaMigrator migrator = new SchemaMigrator("sqlite", data);
        migrator.migrate();
        migrator.migrate();

        try (Connection connection = data.getConnection();
             var row = connection.createStatement().executeQuery(
                 "SELECT sent_at, received_at, archived_at FROM FENGYU_PL_Email_Archive WHERE id=1")) {
            assertTrue(row.next());
            assertEquals(sentAt.toEpochMilli(), row.getLong("sent_at"));
            assertEquals(sentAt.toEpochMilli(), row.getLong("received_at"));
            assertEquals(sentAt.toEpochMilli(), row.getLong("archived_at"));
        }
        try (Connection connection = data.getConnection();
             var versions = connection.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM FENGYU_PL_Email_Schema_History WHERE version IN (2,3)")) {
            assertTrue(versions.next());
            assertEquals(2, versions.getInt(1));
        }
        try (Connection connection = data.getConnection();
             var account = connection.createStatement().executeQuery(
                 "SELECT created_at FROM FENGYU_PL_Email_Account WHERE email='legacy@example.com'")) {
            assertTrue(account.next());
            assertTrue(!"CURRENT_TEXT".equals(account.getString(1)));
        }
        try (Connection connection = data.getConnection();
             var columns = connection.createStatement().executeQuery("PRAGMA table_info(FENGYU_PL_Email_Account)")) {
            String defaultValue = null;
            while (columns.next()) if ("created_at".equals(columns.getString("name"))) defaultValue = columns.getString("dflt_value");
            assertEquals("CURRENT_TIMESTAMP", defaultValue);
        }
    }

    @Test void sqliteV4RenamesOldFengTuPrefixTablesToUserPrefixWithoutLosingRows() throws Exception {
        var data = new UnpooledDataSource("org.sqlite.JDBC", "jdbc:sqlite:" + temp.resolve("renamed.db"), "", "");
        // Seed a pre-fix v3 schema: the email tables use the old FengTu_PL_* prefix.
        try (Connection connection = data.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE FengTu_PL_Email_Schema_History (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Account (id INTEGER PRIMARY KEY AUTOINCREMENT, display_name VARCHAR(200) NOT NULL, email VARCHAR(320) NOT NULL UNIQUE, encrypted_password TEXT NOT NULL, smtp_host VARCHAR(255) NOT NULL, smtp_port INTEGER NOT NULL, smtp_security VARCHAR(20) NOT NULL, imap_host VARCHAR(255), imap_port INTEGER, imap_security VARCHAR(20), is_default INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Contact (id INTEGER PRIMARY KEY AUTOINCREMENT, email VARCHAR(320) NOT NULL UNIQUE, nickname VARCHAR(200), created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Tag (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(200) NOT NULL UNIQUE)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Contact_Tag (contact_id BIGINT NOT NULL, tag_id BIGINT NOT NULL, PRIMARY KEY(contact_id, tag_id))");
            statement.execute("CREATE TABLE FengTu_PL_Email_Mass_Config (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(200) NOT NULL, mode VARCHAR(30) NOT NULL, config_json TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Pending_Send (id INTEGER PRIMARY KEY AUTOINCREMENT, confirmation_id VARCHAR(64) NOT NULL UNIQUE, account_id BIGINT NOT NULL, mode VARCHAR(30) NOT NULL, snapshot_json TEXT NOT NULL, status VARCHAR(20) NOT NULL, expires_at TEXT NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Sent_Log (id INTEGER PRIMARY KEY AUTOINCREMENT, confirmation_id VARCHAR(64), account_email VARCHAR(320) NOT NULL, recipients_json TEXT NOT NULL, subject VARCHAR(998), attachment_json TEXT, status VARCHAR(20) NOT NULL, error_message TEXT, sent_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Archive (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id BIGINT NOT NULL, account_email VARCHAR(320) NOT NULL, folder VARCHAR(500) NOT NULL, message_uid VARCHAR(255) NOT NULL, subject VARCHAR(998), from_address VARCHAR(1000), recipients_json TEXT, sent_at INTEGER, received_at INTEGER, has_attachment INTEGER NOT NULL DEFAULT 0, body_preview VARCHAR(500), eml_path VARCHAR(2000) NOT NULL, archived_at INTEGER NOT NULL DEFAULT (unixepoch('subsec') * 1000), UNIQUE(account_id, folder, message_uid))");
            statement.execute("INSERT INTO FengTu_PL_Email_Account(display_name,email,encrypted_password,smtp_host,smtp_port,smtp_security,is_default) VALUES('Keep Me','keep@example.com','x','smtp',25,'PLAIN',1)");
            statement.execute("INSERT INTO FengTu_PL_Email_Schema_History(version) VALUES (1)");
            statement.execute("INSERT INTO FengTu_PL_Email_Schema_History(version) VALUES (2)");
            statement.execute("INSERT INTO FengTu_PL_Email_Schema_History(version) VALUES (3)");
        }

        SchemaMigrator migrator = new SchemaMigrator("sqlite", data);
        migrator.migrate();   // applies V4 (prefix rename)
        migrator.migrate();   // proves idempotency

        try (Connection connection = data.getConnection();
             var account = connection.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM FENGYU_PL_Email_Account WHERE email='keep@example.com'")) {
            assertTrue(account.next());
            assertEquals(1, account.getInt(1));
        }
        try (Connection connection = data.getConnection();
             var versions = connection.createStatement().executeQuery(
                 "SELECT MAX(version) FROM FENGYU_PL_Email_Schema_History")) {
            assertTrue(versions.next());
            assertEquals(4, versions.getInt(1));
        }
        Set<String> names = new HashSet<>();
        try (Connection connection = data.getConnection();
             var tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) names.add(tables.getString("TABLE_NAME").toLowerCase());
        }
        assertTrue(names.stream().noneMatch(name -> name.startsWith("fengtu_pl_email_")),
            "old FengTu_PL_* tables must be gone: " + names);
        assertTrue(names.contains("fengyu_pl_email_account"), names::toString);
    }

    private static void assertSchema(String dialect, UnpooledDataSource data) throws Exception {
        SchemaMigrator migrator = new SchemaMigrator(dialect, data);
        migrator.migrate(); migrator.migrate();
        Set<String> names = new HashSet<>();
        try (Connection connection = data.getConnection();
             var tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name.toLowerCase().startsWith("fengyu_pl_email_")) names.add(name.toLowerCase());
            }
        }
        assertEquals(9, names.size());
        assertTrue(names.stream().allMatch(name -> name.startsWith("fengyu_pl_email_")));
    }

    @Test void splitStatementsKeepsSemicolonsInsideStringsCommentsAndQuotedIdentifiers() {
        String script = String.join("\n",
            "CREATE TABLE a (x TEXT DEFAULT 'a;b;c');",                 // semicolon in single-quoted string
            "CREATE TABLE \"b;c\" (x TEXT);",                           // semicolon in quoted identifier
            "-- this line comment has a ; semicolon\nCREATE TABLE c (x TEXT);",
            "/* block comment with ; inside */ CREATE TABLE d (x TEXT);",
            "INSERT INTO a VALUES('it''s a ; semi');",                  // escaped quote + semicolon
            "CREATE TABLE e (x TEXT)");

        List<String> statements = SchemaMigrator.splitStatements(script);

        assertEquals(6, statements.size(),
            "semicolons inside strings/identifiers/comments must not split statements");
        assertTrue(statements.get(0).contains("'a;b;c'"));
        assertTrue(statements.get(1).contains("\"b;c\""));
        assertTrue(statements.get(4).contains("it''s a ; semi"));
        assertTrue(statements.get(5).startsWith("CREATE TABLE e"));
    }
}
