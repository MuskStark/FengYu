package fan.summer.fengyu.database;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * V2 — remove the cloud token columns from {@code cloud_account_binding}
 * (review M-5 / design §7.2: the access token lives only in memory, the refresh
 * token only in the OS credential store — beta installations must not keep
 * encrypted tokens at rest after upgrading).
 *
 * <p>Java, not SQL, because the drop must be conditional — fresh installs run
 * migrations before Hibernate ever creates the table — and portable column
 * IF-EXISTS does not exist on SQLite (H2/PostgreSQL only). Spring Boot
 * registers {@code JavaMigration} beans with the auto-configured Flyway.
 */
@Component
public class V2__DropCloudTokenColumns extends BaseJavaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(V2__DropCloudTokenColumns.class);

    private static final List<String> TOKEN_COLUMNS =
            List.of("access_token", "access_expires_at", "refresh_token");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        for (String column : TOKEN_COLUMNS) {
            if (columnExists(connection, "cloud_account_binding", column)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(
                            "ALTER TABLE cloud_account_binding DROP COLUMN " + column);
                }
                log.info("Dropped cloud_account_binding.{} — tokens no longer rest "
                        + "in the database", column);
            }
        }
    }

    private static boolean columnExists(Connection connection, String table,
            String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String variant : List.of(table, table.toUpperCase(Locale.ROOT),
                table.toLowerCase(Locale.ROOT))) {
            try (ResultSet columns = meta.getColumns(null, null, variant, column)) {
                if (columns.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
