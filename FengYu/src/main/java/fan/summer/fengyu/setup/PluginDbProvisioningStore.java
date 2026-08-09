package fan.summer.fengyu.setup;

import fan.summer.fengyu.runtime.RuntimePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        "dbType", "schemaName", "userName", "password", "url", "driver", "provisionedAt", "status"
    };

    public static final String STATUS_PROVISIONING = "PROVISIONING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETE_PENDING = "DELETE_PENDING";

    private final Path baseDir;

    /** Production constructor — uses the {@code .fengyu} runtime root. */
    public PluginDbProvisioningStore() {
        this(RuntimePaths.root());
    }

    /** Test constructor — injects the base dir (typically a @TempDir). */
    public PluginDbProvisioningStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /**
     * Immutable record of a provisioned plugin DB namespace + credentials. {@code status} tracks the
     * provisioning lifecycle: {@code PROVISIONING} is a durable intent written before DDL,
     * {@code ACTIVE} is ready for worker injection, and {@code DELETE_PENDING} is retained until
     * cleanup DDL is confirmed. Absent/blank is treated as {@code ACTIVE} for backwards
     * compatibility with records written before the status field existed.
     */
    public record ProvisionedPluginDb(
            String pluginId,
            DbType dbType,
            String schemaName,
            String userName,
            String password,
            String url,
            String driver,
            String provisionedAt,
            String status
    ) {
        /** Backwards-compatible 8-arg constructor: status defaults to ACTIVE. */
        public ProvisionedPluginDb(String pluginId, DbType dbType, String schemaName, String userName,
                String password, String url, String driver, String provisionedAt) {
            this(pluginId, dbType, schemaName, userName, password, url, driver, provisionedAt,
                    STATUS_ACTIVE);
        }
        /** Canonical status, defaulting to ACTIVE for blank/legacy records. */
        public String canonicalStatus() {
            return (status == null || status.isBlank())
                    ? STATUS_ACTIVE
                    : status.toUpperCase(Locale.ROOT);
        }

        public boolean isActive() {
            return STATUS_ACTIVE.equals(canonicalStatus());
        }
    }

    /** Returns the provisioned record for {@code pluginId}, or {@code null} if none. */
    public synchronized ProvisionedPluginDb get(String pluginId) {
        Properties props = read();
        return decode(props, pluginId);
    }

    /** Returns a consistent snapshot of every durable provisioning operation. */
    public synchronized List<ProvisionedPluginDb> list() {
        Properties props = read();
        List<ProvisionedPluginDb> records = new ArrayList<>();
        String prefix = "plugin.";
        String suffix = ".userName";
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(prefix) || !key.endsWith(suffix)) continue;
            String pluginId = key.substring(prefix.length(), key.length() - suffix.length());
            try {
                ProvisionedPluginDb record = decode(props, pluginId);
                if (record != null) records.add(record);
            } catch (RuntimeException e) {
                // One damaged credential must not prevent reconciliation of every other plugin.
                log.error("Could not decode DB recovery record for plugin {}", pluginId, e);
            }
        }
        return List.copyOf(records);
    }

    private ProvisionedPluginDb decode(Properties props, String pluginId) {
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
                props.getProperty(prefix + "provisionedAt"),
                props.getProperty(prefix + "status", STATUS_ACTIVE));
    }

    /** Inserts or replaces the record for {@code record.pluginId()}. Encrypts the password. */
    public synchronized void put(ProvisionedPluginDb record) {
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
            props.setProperty(prefix + "status", record.canonicalStatus());
            write(props);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write plugin-db.properties", e);
        }
    }

    /**
     * Update only the status field of an existing record (P1-3). Used to mark a record
     * {@code DELETE_PENDING} when deprovision DDL fails, so the record (with schema/user/password)
     * survives for a background retry instead of being deleted.
     */
    public synchronized void setStatus(String pluginId, String status) {
        try {
            Properties props = read();
            String prefix = "plugin." + pluginId + ".";
            if (props.getProperty(prefix + "userName") == null) return; // no record to update
            props.setProperty(prefix + "status",
                    status == null ? STATUS_ACTIVE : status.toUpperCase(Locale.ROOT));
            write(props);
        } catch (IOException e) {
            throw new RuntimeException("Failed to update plugin-db.properties status", e);
        }
    }

    /** Removes the record for {@code pluginId}. Returns true if a record was present. */
    public synchronized boolean remove(String pluginId) {
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
        if (Files.notExists(file)) return props;
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("plugin-db.properties is not a readable regular file");
        }
        try (InputStream in = Files.newInputStream(file)) {
            SensitiveFilePermissions.protectDirectory(file.getParent());
            SensitiveFilePermissions.protectFile(file);
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read plugin-db.properties", e);
        }
        return props;
    }

    private void write(Properties props) throws IOException {
        Path file = configFile();
        Files.createDirectories(file.getParent());
        SensitiveFilePermissions.protectDirectory(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "plugin-db-", ".tmp");
        try {
            SensitiveFilePermissions.protectFile(temp);
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "FengYu per-plugin DB provisioning records (passwords encrypted)");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        SensitiveFilePermissions.protectFile(file);
    }

    /** Test-only: read raw properties (with encrypted password) for assertions. */
    synchronized Properties readRawForTest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile())) {
            props.load(in);
        }
        return props;
    }
}
