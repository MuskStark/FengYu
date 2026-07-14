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
            statement.execute("INSERT INTO FengTu_PL_Email_Archive(account_id,account_email,folder,message_uid,"
                + "sent_at,received_at,eml_path,archived_at) VALUES(7,'owner@example.com','INBOX','42','"
                + sentAt.toEpochMilli() + "','" + sentAt.toEpochMilli() + "','/tmp/42.eml','"
                + sentAt.toEpochMilli() + "')");
            statement.execute("INSERT INTO FengTu_PL_Email_Account(display_name,email,encrypted_password,smtp_host,"
                + "smtp_port,smtp_security,is_default,created_at) VALUES('Legacy','legacy@example.com','x','smtp',25,"
                + "'PLAIN',1,'CURRENT_TEXT')");
        }

        SchemaMigrator migrator = new SchemaMigrator("sqlite", data);
        migrator.migrate();
        migrator.migrate();

        try (Connection connection = data.getConnection();
             var row = connection.createStatement().executeQuery(
                 "SELECT sent_at, received_at, archived_at FROM FengTu_PL_Email_Archive WHERE id=1")) {
            assertTrue(row.next());
            assertEquals(sentAt.toEpochMilli(), row.getLong("sent_at"));
            assertEquals(sentAt.toEpochMilli(), row.getLong("received_at"));
            assertEquals(sentAt.toEpochMilli(), row.getLong("archived_at"));
        }
        try (Connection connection = data.getConnection();
             var versions = connection.createStatement().executeQuery(
                 "SELECT COUNT(*) FROM FengTu_PL_Email_Schema_History WHERE version IN (2,3)")) {
            assertTrue(versions.next());
            assertEquals(2, versions.getInt(1));
        }
        try (Connection connection = data.getConnection();
             var account = connection.createStatement().executeQuery(
                 "SELECT created_at FROM FengTu_PL_Email_Account WHERE email='legacy@example.com'")) {
            assertTrue(account.next());
            assertTrue(!"CURRENT_TEXT".equals(account.getString(1)));
        }
        try (Connection connection = data.getConnection();
             var columns = connection.createStatement().executeQuery("PRAGMA table_info(FengTu_PL_Email_Account)")) {
            String defaultValue = null;
            while (columns.next()) if ("created_at".equals(columns.getString("name"))) defaultValue = columns.getString("dflt_value");
            assertEquals("CURRENT_TIMESTAMP", defaultValue);
        }
    }

    private static void assertSchema(String dialect, UnpooledDataSource data) throws Exception {
        SchemaMigrator migrator = new SchemaMigrator(dialect, data);
        migrator.migrate(); migrator.migrate();
        Set<String> names = new HashSet<>();
        try (Connection connection = data.getConnection();
             var tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name.toLowerCase().startsWith("fengtu_pl_email_")) names.add(name.toLowerCase());
            }
        }
        assertEquals(9, names.size());
        assertTrue(names.stream().allMatch(name -> name.startsWith("fengtu_pl_email_")));
    }
}
