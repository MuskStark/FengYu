package fan.summer.zhiflow.setup;

/**
 * Raw parameters submitted by the setup wizard frontend, before being assembled into a
 * {@link DataSourceConfig}. Field relevance depends on the {@link DbType#embedded} flag.
 *
 * @param filePath embedded DB file path (H2/SQLite); ignored for remote
 * @param host     remote DB hostname; ignored for embedded
 * @param port     remote DB port; ignored for embedded
 * @param database remote DB name; ignored for embedded
 * @param username remote DB username; ignored for embedded
 * @param password remote DB password; ignored for embedded
 */
public record WizardParams(
        String filePath,
        String host,
        Integer port,
        String database,
        String username,
        String password
) {}
