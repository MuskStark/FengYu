package fan.summer.fengyu.plugin.email.database;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
            statement.execute("CREATE TABLE FengTu_PL_Email_Schema_History "
                + "(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TEXT)");
            statement.execute("INSERT INTO FengTu_PL_Email_Schema_History(version) VALUES (1)");
            statement.execute("CREATE TABLE FengTu_PL_Email_Archive (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "account_id BIGINT NOT NULL, account_email VARCHAR(320) NOT NULL, folder VARCHAR(500) NOT NULL, "
                + "message_uid VARCHAR(255) NOT NULL, subject VARCHAR(998), from_address VARCHAR(1000), "
                + "recipients_json TEXT, sent_at TEXT, received_at TEXT, has_attachment INTEGER NOT NULL DEFAULT 0, "
                + "body_preview VARCHAR(500), eml_path VARCHAR(2000) NOT NULL, archived_at TEXT NOT NULL DEFAULT CURRENT_TEXT, "
                + "UNIQUE(account_id, folder, message_uid))");
            statement.execute("INSERT INTO FengTu_PL_Email_Archive(account_id,account_email,folder,message_uid,"
                + "sent_at,received_at,eml_path,archived_at) VALUES(7,'owner@example.com','INBOX','42','"
                + sentAt.toEpochMilli() + "','" + sentAt.toEpochMilli() + "','/tmp/42.eml','"
                + sentAt.toEpochMilli() + "')");
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
                 "SELECT COUNT(*) FROM FengTu_PL_Email_Schema_History WHERE version=2")) {
            assertTrue(versions.next());
            assertEquals(1, versions.getInt(1));
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
