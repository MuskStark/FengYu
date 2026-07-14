package fan.summer.fengyu.plugin.email.database;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Executes the plugin's versioned, dialect-specific schema resources. */
public final class SchemaMigrator {
    private static final String HISTORY = "FengTu_PL_Email_Schema_History";
    private static final int LATEST_VERSION = 3;
    private final String dialect;
    private final DataSource dataSource;

    public SchemaMigrator(String dialect, DataSource dataSource) {
        this.dialect = dialect.toLowerCase();
        this.dataSource = dataSource;
    }

    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (!tableExists(connection, HISTORY)) executeScript(connection, resource("V1__email_schema.sql"));
            int version = currentVersion(connection);
            for (int next = version + 1; next <= LATEST_VERSION; next++) {
                executeScript(connection, resource("V" + next + "__email_schema.sql"));
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Email database migration failed", e);
        }
    }

    private String resource(String name) throws IOException {
        String path = "db/" + dialect + "/" + name;
        try (var input = SchemaMigrator.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing schema resource for " + dialect);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String statement : script.split(";")) {
            String sql = statement.trim();
            if (!sql.isEmpty()) try (var command = connection.createStatement()) { command.execute(sql); }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, null, new String[]{"TABLE"})) {
            while (tables.next()) if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM " + HISTORY)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }
}
