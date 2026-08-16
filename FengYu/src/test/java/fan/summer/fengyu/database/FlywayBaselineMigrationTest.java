package fan.summer.fengyu.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The versioned-migration contract (P3): V1 is an empty baseline; existing ddl-auto-created
 * schemas are baselined (not failed) on first boot with Flyway; V2+ scripts are the channel
 * for migrations {@code ddl-auto=update} cannot express. These tests exercise the exact
 * configuration application.yml ships.
 */
class FlywayBaselineMigrationTest {

    @TempDir
    Path temp;

    private Connection h2() throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private Flyway flyway(String jdbcUrl, String... extraLocations) {
        java.util.List<String> locations = new java.util.ArrayList<>();
        locations.add("classpath:db/migration");
        locations.addAll(List.of(extraLocations));
        return Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .locations(locations.toArray(new String[0]))
                .load();
    }

    @Test
    void freshDatabaseRunsTheV1BaselineFromZero() throws Exception {
        try (Connection conn = h2()) {
            flyway(conn.getMetaData().getURL()).migrate();
            assertEquals(1, historyRows(conn, "WHERE \"version\" = '1' AND \"type\" != 'BASELINE'"),
                    "fresh install executes V1 (the empty baseline) and records it");
        }
    }

    @Test
    void existingSchemaIsBaselinedInsteadOfFailing() throws Exception {
        try (Connection conn = h2()) {
            // Simulate an existing install: ddl-auto already created tables, no flyway history.
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE app_setting (id INT PRIMARY KEY)");
                stmt.execute("INSERT INTO app_setting VALUES (42)");
            }
            flyway(conn.getMetaData().getURL()).migrate();
            assertEquals(1, historyRows(conn, "WHERE \"version\" = '1' AND \"type\" = 'BASELINE'"),
                    "existing schema is baselined at V1, not rejected");
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT id FROM app_setting")) {
                assertTrue(rs.next() && rs.getInt(1) == 42, "baseline must not touch existing data");
            }
        }
    }

    @Test
    void v2AndBeyondCarryRealMigrationsAfterTheBaseline() throws Exception {
        // A future migration drops a real script into db/migration — prove the channel works
        // end to end for BOTH a fresh install and a baselined existing install.
        Path v2dir = temp.resolve("v2");
        Files.createDirectories(v2dir);
        Files.writeString(v2dir.resolve("V2__add_migration_channel_probe.sql"),
                "CREATE TABLE migration_probe (id INT PRIMARY KEY);");

        String location = "filesystem:" + v2dir;
        try (Connection conn = h2()) {
            flyway(conn.getMetaData().getURL(), location).migrate();
            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM migration_probe")) {
                assertTrue(rs.next(), "V2 ran on a fresh install");
            }
        }
        try (Connection conn = h2()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE app_setting (id INT PRIMARY KEY)");
            }
            flyway(conn.getMetaData().getURL(), location).migrate();
            try (ResultSet rs = conn.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM migration_probe")) {
                assertTrue(rs.next(), "V2 ran on a baselined existing install");
            }
            assertEquals(1, historyRows(conn, "WHERE \"version\" = '2'"));
        }
    }

    private static int historyRows(Connection conn, String where) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
