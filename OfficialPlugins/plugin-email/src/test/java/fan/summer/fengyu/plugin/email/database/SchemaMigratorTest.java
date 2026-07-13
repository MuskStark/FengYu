package fan.summer.fengyu.plugin.email.database;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
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
