package fan.summer.fengyu.plugin.email.database;

import fan.summer.fengyu.sdk.PluginMessages;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Executes the plugin's versioned, dialect-specific schema resources. */
public final class SchemaMigrator {
    private static final PluginMessages MSGS = PluginMessages.forClassLoader(PluginMessages.DEFAULT_BASE_NAME, SchemaMigrator.class);
    private static final String HISTORY = "FENGYU_PL_Email_Schema_History";
    private static final int LATEST_VERSION = 6;
    private final String dialect;
    private final DataSource dataSource;

    public SchemaMigrator(String dialect, DataSource dataSource) {
        this.dialect = dialect.toLowerCase();
        this.dataSource = dataSource;
    }

    private static final String LEGACY_HISTORY = "FengTu_PL_Email_Schema_History";

    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Pre-fix builds misspelled the brand prefix as FengTu_PL. A surviving legacy
            // Schema_History table signals such a deployed database; rename its tables to the
            // corrected FENGYU_PL_* prefix BEFORE the V1 guard below, so the fresh-install path does
            // not create a duplicate (empty) schema on top of it. No-op on fresh installs.
            if (tableExists(connection, LEGACY_HISTORY)) renameLegacyPrefixedTables(connection);
            if (!tableExists(connection, HISTORY)) executeScript(connection, resource("V1__email_schema.sql"));
            int version = currentVersion(connection);
            for (int next = version + 1; next <= LATEST_VERSION; next++) {
                executeScript(connection, resource("V" + next + "__email_schema.sql"));
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException(MSGS.format("em.err.databaseMigrationFailed"), e);
        }
    }

    /**
     * Renames any surviving {@code FengTu_PL_Email_*} tables — created by pre-4.0.0 builds that
     * misspelled the brand prefix — to the corrected {@code FENGYU_PL_Email_*} names. Each table is
     * guarded by {@link #tableExists(Connection, String)} so this is a no-op on fresh installs and
     * re-runnable after a crash (already-renamed tables are skipped). Cross-dialect: plain
     * {@code ALTER TABLE ... RENAME TO} is standard SQL supported by h2, mysql, postgres, and sqlite,
     * so no per-dialect DDL is needed. Indexes keep their old-prefixed names after a rename, so they
     * are dropped and recreated under the new prefix.
     */
    private void renameLegacyPrefixedTables(Connection connection) throws SQLException {
        String[] tables = {"Schema_History", "Account", "Contact", "Tag", "Contact_Tag",
            "Mass_Config", "Pending_Send", "Sent_Log", "Archive"};
        for (String suffix : tables) {
            String oldName = "FengTu_PL_Email_" + suffix;
            String newName = "FENGYU_PL_Email_" + suffix;
            if (!tableExists(connection, oldName) || tableExists(connection, newName)) continue;
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + oldName + " RENAME TO " + newName);
            }
        }
        // Indexes survive the rename bound to the new table but keep their old-prefixed names;
        // recreate them under the new prefix so the schema is internally consistent.
        renameIndex(connection, "FengTu_PL_Email_Sent_Status_Idx", "FENGYU_PL_Email_Sent_Status_Idx",
            "FENGYU_PL_Email_Sent_Log(status, sent_at)");
        renameIndex(connection, "FengTu_PL_Email_Archive_Query_Idx", "FENGYU_PL_Email_Archive_Query_Idx",
            "FENGYU_PL_Email_Archive(account_id, sent_at, subject)");
    }

    private static void renameIndex(Connection connection, String oldName, String newName, String definition)
            throws SQLException {
        if (!indexExists(connection, oldName)) return; // already renamed, or never existed
        try (var statement = connection.createStatement()) {
            statement.execute("DROP INDEX " + oldName);
            statement.execute("CREATE INDEX " + newName + " ON " + definition);
        }
    }

    private static boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "%", false, true)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) return true;
            }
        }
        return false;
    }

    private String resource(String name) throws IOException {
        String path = "db/" + dialect + "/" + name;
        try (var input = SchemaMigrator.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing schema resource for " + dialect);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String statement : splitStatements(script)) {
            try (var command = connection.createStatement()) { command.execute(statement); }
        }
    }

    /**
     * Split a SQL script into individual statements, aware of single-quoted string literals (with
     * {@code ''} escapes), double-quoted identifiers, line comments ({@code --}), and block comments
     * ({@code /* *}{@code /}). A semicolon terminates a statement only when it is not nested inside
     * any of those contexts. The naive {@code split(";")} this replaced would mis-split any script
     * whose string literal, comment, or quoted identifier contained a semicolon.
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = script.length();
        int index = 0;
        while (index < length) {
            char c = script.charAt(index);
            char next = index + 1 < length ? script.charAt(index + 1) : '\0';
            if (c == '-' && next == '-') {                       // line comment until newline
                current.append("--");
                index += 2;
                while (index < length && script.charAt(index) != '\n') current.append(script.charAt(index++));
                continue;
            }
            if (c == '/' && next == '*') {                       // block comment until */
                current.append("/*");
                index += 2;
                while (index < length) {
                    if (script.charAt(index) == '*' && index + 1 < length && script.charAt(index + 1) == '/') {
                        current.append("*/");
                        index += 2;
                        break;
                    }
                    current.append(script.charAt(index++));
                }
                continue;
            }
            if (c == '\'') {                                    // single-quoted string literal
                current.append('\'');
                index++;
                while (index < length) {
                    char ch = script.charAt(index);
                    current.append(ch);
                    if (ch == '\'' && index + 1 < length && script.charAt(index + 1) == '\'') {
                        current.append('\'');                    // escaped quote
                        index += 2;
                        continue;
                    }
                    index++;
                    if (ch == '\'') break;
                }
                continue;
            }
            if (c == '"') {                                     // double-quoted identifier
                current.append('"');
                index++;
                while (index < length) {
                    char ch = script.charAt(index);
                    current.append(ch);
                    if (ch == '"' && index + 1 < length && script.charAt(index + 1) == '"') {
                        current.append('"');                     // escaped quote
                        index += 2;
                        continue;
                    }
                    index++;
                    if (ch == '"') break;
                }
                continue;
            }
            if (c == ';') {                                     // statement terminator
                String statement = current.toString().trim();
                if (!statement.isEmpty()) statements.add(statement);
                current.setLength(0);
                index++;
                continue;
            }
            current.append(c);
            index++;
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) statements.add(trailing);
        return statements;
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
