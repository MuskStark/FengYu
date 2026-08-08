package fan.summer.fengyu.setup;

import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Encrypted, idempotent store of provisioned per-plugin DB credentials.
 *
 * <p>Persists to {@code <config>/plugin-db.properties}. The plugin user password is encrypted with
 * the machine-bound {@link CryptoUtil} (same {@code .machineid} as {@code datasource.properties}),
 * so a stolen file is useless off-machine. Other fields (user name, schema name, url, driver,
 * provisionedAt) are non-secret and stored plaintext for diagnosability.
 *
 * <p>Lives in package {@code fan.summer.fengyu.setup} (not {@code plugin.runtime}) specifically to
 * call the package-private {@code CryptoUtil.encrypt/decrypt(String, Path)} overloads with the
 * shared {@code machineIdFile()}, mirroring {@link DataSourceConfigService}. This is a deliberate
 * deviation from the design spec which placed the provisioner in {@code plugin.runtime}.
 *
 * <p>Property key scheme: {@code plugin.<pluginId>.<field>} where field is one of
 * {@code dbType, schemaName, userName, password, url, driver, provisionedAt}.
 */
@Service
public class PluginDbProvisioningStore {

    private static final Logger log = LoggerFactory.getLogger(PluginDbProvisioningStore.class);
    private static final String[] FIELDS = {
        "dbType", "schemaName", "userName", "password", "url", "driver", "provisionedAt"
    };

    private final Path baseDir;

    /** Production constructor — uses the {@code .fengyu} runtime root. */
    public PluginDbProvisioningStore() {
        this(RuntimePaths.root());
    }

    /** Test constructor — injects the base dir (typically a @TempDir). */
    public PluginDbProvisioningStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /** Immutable record of a provisioned plugin DB namespace + credentials. */
    public record ProvisionedPluginDb(
            String pluginId,
            DbType dbType,
            String schemaName,
            String userName,
            String password,
            String url,
            String driver,
            String provisionedAt
    ) {}

    /** Returns the provisioned record for {@code pluginId}, or {@code null} if none. */
    public ProvisionedPluginDb get(String pluginId) {
        Properties props = read();
        String prefix = "plugin." + pluginId + ".";
        if (props.getProperty(prefix + "userName") == null) return null;
        return new ProvisionedPluginDb(
                pluginId,
                DbType.fromName(props.getProperty(prefix + "dbType")),
                props.getProperty(prefix + "schemaName"),
                props.getProperty(prefix + "userName"),
                CryptoUtil.decrypt(props.getProperty(prefix + "password", ""), machineIdFile()),
                props.getProperty(prefix + "url"),
                props.getProperty(prefix + "driver"),
                props.getProperty(prefix + "provisionedAt"));
    }

    /** Inserts or replaces the record for {@code record.pluginId()}. Encrypts the password. */
    public void put(ProvisionedPluginDb record) {
        try {
            Properties props = read();
            String prefix = "plugin." + record.pluginId() + ".";
            props.setProperty(prefix + "dbType", record.dbType().name().toLowerCase());
            props.setProperty(prefix + "schemaName", record.schemaName());
            props.setProperty(prefix + "userName", record.userName());
            props.setProperty(prefix + "password",
                    CryptoUtil.encrypt(record.password(), machineIdFile()));
            props.setProperty(prefix + "url", record.url());
            props.setProperty(prefix + "driver", record.driver());
            props.setProperty(prefix + "provisionedAt", record.provisionedAt());
            write(props);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write plugin-db.properties", e);
        }
    }

    /** Removes the record for {@code pluginId}. Returns true if a record was present. */
    public boolean remove(String pluginId) {
        try {
            Properties props = read();
            String prefix = "plugin." + pluginId + ".";
            boolean had = false;
            for (String f : FIELDS) {
                if (props.remove(prefix + f) != null) had = true;
            }
            if (had) write(props);
            return had;
        } catch (IOException e) {
            throw new RuntimeException("Failed to update plugin-db.properties", e);
        }
    }

    private Path configFile() {
        return baseDir.resolve("config").resolve("plugin-db.properties");
    }

    private Path machineIdFile() {
        return baseDir.resolve("config").resolve(".machineid");
    }

    private Properties read() {
        Properties props = new Properties();
        Path file = configFile();
        if (!Files.isRegularFile(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            SensitiveFilePermissions.protectDirectory(file.getParent());
            SensitiveFilePermissions.protectFile(file);
            props.load(in);
        } catch (IOException e) {
            log.warn("Failed to read plugin-db.properties: {}", e.getMessage());
        }
        return props;
    }

    private void write(Properties props) throws IOException {
        Path file = configFile();
        Files.createDirectories(file.getParent());
        SensitiveFilePermissions.protectDirectory(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "FengYu per-plugin DB provisioning records (passwords encrypted)");
        }
        SensitiveFilePermissions.protectFile(file);
    }

    /** Test-only: read raw properties (with encrypted password) for assertions. */
    Properties readRawForTest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile())) {
            props.load(in);
        }
        return props;
    }
}
