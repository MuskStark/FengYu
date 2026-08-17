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
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

/**
 * Reads/writes {@code datasource.properties} and assembles {@link DataSourceConfig} from
 * wizard params. Also handles password encryption via {@link CryptoUtil} and connection testing.
 *
 * <p>Config file location defaults to
 * {@code <programWorkingDirectory>/.fengyu/config/datasource.properties}. The runtime root is
 * injectable for testing.
 */
@Service
public class DataSourceConfigService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfigService.class);

    private final Path baseDir;
    private final List<Path> legacyBaseDirs;

    /** Production constructor — uses {@code .fengyu} under the program working directory. */
    public DataSourceConfigService() {
        this(RuntimePaths.root(), legacyBaseDirs(Boolean.getBoolean(RuntimePaths.PINNED_MARKER_PROPERTY)));
    }

    /**
     * Legacy config locations probed when the runtime root has no config yet. An explicitly
     * pinned runtime root ({@code -Dfengyu.runtime.dir=...} — the desktop shell pins it on
     * every launch) means the operator chose where state lives, so a fresh pinned root must
     * STAY fresh: a new portable-extraction directory shows the setup wizard instead of
     * silently adopting the config from {@code <cwd>} or {@code ~/.fengyu}. Unpinned runs
     * keep the legacy probes so upgrades from the old layouts continue to migrate.
     */
    static List<Path> legacyBaseDirs(boolean runtimeDirPinned) {
        if (runtimeDirPinned) {
            return List.of();
        }
        return List.of(
                Path.of(System.getProperty("user.dir")),
                Path.of(System.getProperty("user.home"), ".fengyu"));
    }

    /** Test constructor — injects base dir (temp dir). */
    public DataSourceConfigService(String baseDir) {
        this(Path.of(baseDir), List.of());
    }

    DataSourceConfigService(Path baseDir, Path legacyBaseDir) {
        this(baseDir, legacyBaseDir == null ? List.of() : List.of(legacyBaseDir));
    }

    DataSourceConfigService(Path baseDir, List<Path> legacyBaseDirs) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.legacyBaseDirs = legacyBaseDirs.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private Path configFile() {
        return baseDir.resolve("config").resolve("datasource.properties");
    }

    /** The program-default embedded data-file path: {@code <baseDir>/database/fengyu}. */
    public Path defaultEmbeddedPath() {
        return baseDir.resolve("database").resolve("fengyu");
    }

    /** Loads the datasource config. Returns {@code null} if the file is missing or invalid. */
    public DataSourceConfig load() {
        migrateLegacyConfigIfNeeded();
        Path file = configFile();
        if (!Files.exists(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            SensitiveFilePermissions.protectDirectory(file.getParent());
            SensitiveFilePermissions.protectFile(file);
            Properties props = new Properties();
            props.load(in);
            String typeStr = props.getProperty("db.type");
            if (typeStr == null || typeStr.isBlank()) return null;
            DbType type = DbType.fromName(typeStr);
            String adminUser = props.getProperty("db.admin.username");
            String adminPass = CryptoUtil.decrypt(props.getProperty("db.admin.password"), machineIdFile());
            return new DataSourceConfig(
                    type,
                    props.getProperty("db.url"),
                    props.getProperty("db.driver"),
                    props.getProperty("db.dialect"),
                    props.getProperty("db.username", ""),
                    CryptoUtil.decrypt(props.getProperty("db.password", ""), machineIdFile()),
                    props.getProperty("db.file.path", ""),
                    adminUser != null && adminUser.isBlank() ? null : adminUser,
                    adminPass != null && adminPass.isBlank() ? null : adminPass);
        } catch (Exception e) {
            log.warn("Failed to load datasource.properties: {}", e.getMessage());
            return null;
        }
    }

    /** Persists the config, encrypting the password. */
    public void save(DataSourceConfig cfg) {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            SensitiveFilePermissions.protectDirectory(file.getParent());
            Properties props = new Properties();
            props.setProperty("db.type", cfg.type().name().toLowerCase());
            props.setProperty("db.url", cfg.url());
            props.setProperty("db.driver", cfg.driver());
            props.setProperty("db.dialect", cfg.dialect());
            if (cfg.username() != null) props.setProperty("db.username", cfg.username());
            if (cfg.password() != null && !cfg.password().isBlank()) {
                props.setProperty("db.password", CryptoUtil.encrypt(cfg.password(), machineIdFile()));
            }
            if (cfg.adminUsername() != null && !cfg.adminUsername().isBlank()) {
                props.setProperty("db.admin.username", cfg.adminUsername());
            }
            if (cfg.adminPassword() != null && !cfg.adminPassword().isBlank()) {
                props.setProperty("db.admin.password",
                    CryptoUtil.encrypt(cfg.adminPassword(), machineIdFile()));
            }
            if (cfg.filePath() != null) props.setProperty("db.file.path", cfg.filePath());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "FengYu datasource configuration (generated by setup wizard)");
            }
            SensitiveFilePermissions.protectFile(file);
            log.info("Saved datasource.properties: type={}", cfg.type());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write datasource.properties", e);
        }
    }

    /**
     * Backs up and clears {@code datasource.properties}: moves it to
     * {@code datasource.properties.bak} (or {@code .bak.<millis>} if a {@code .bak} already
     * exists, to avoid clobbering a prior backup). If the move fails, falls back to a direct
     * delete. Returns the backup path, or {@code null} if there was no file or backup/delete
     * failed entirely. Never throws — callers (startup probe, reset endpoints) rely on this to
     * degrade gracefully so the app can still boot into SETUP mode.
     */
    public Path backupAndClear() {
        Path file = configFile();
        if (!Files.exists(file)) return null;
        Path bak = file.resolveSibling(file.getFileName() + ".bak");
        if (Files.exists(bak)) {
            bak = file.resolveSibling(file.getFileName() + ".bak." + System.currentTimeMillis());
        }
        try {
            Files.move(file, bak);
            log.warn("Backed up stale datasource.properties to {}", bak);
            return bak;
        } catch (IOException moveErr) {
            log.warn("Move to .bak failed ({}); attempting direct delete", moveErr.getMessage());
            try {
                Files.deleteIfExists(file);
                log.warn("Deleted datasource.properties directly (backup unavailable)");
            } catch (IOException delErr) {
                log.error("Could not backup or delete datasource.properties: {}", delErr.getMessage());
            }
            return null;
        }
    }

    /**
     * Assembles a {@link DataSourceConfig} from wizard params, resolving paths/URL. For embedded
     * databases (H2/SQLite) the parent directory of the data file is created here so the JDBC
     * driver can initialize the database file on first connection — without this the connection
     * test fails because the target directory does not exist. Does NOT persist the config —
     * call {@link #save} after testing.
     */
    public DataSourceConfig buildFromWizard(DbType type, WizardParams params) {
        String url;
        String filePath = null;
        if (type.embedded) {
            // Default data file lives under <programWorkingDirectory>/.fengyu/database/fengyu.
            String rawPath = (params.filePath() == null || params.filePath().isBlank())
                    ? defaultEmbeddedPath().toString()
                    : params.filePath();
            Path resolved = Path.of(rawPath);
            if (!resolved.isAbsolute()) {
                resolved = baseDir.resolve(rawPath).toAbsolutePath();
            }
            // Ensure the parent directory exists — the JDBC driver creates the file itself, but
            // only if its directory is already present (H2/SQLite both fail otherwise).
            Path parent = resolved.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to create database directory " + parent + ": " + e.getMessage(), e);
                }
            }
            filePath = resolved.toString().replace("\\", "/");
            // ';' opens the H2 settings segment (e.g. ";INIT=RUNSCRIPT..."), '?' opens the SQLite
            // query segment — neither may ever ride in through a wizard-supplied path.
            rejectUrlMetachars("file path", filePath, ";?");
            url = type.urlTemplate.replace("{path}", filePath);
        } else {
            validateHost(params.host());
            if (params.database() == null || params.database().isBlank()) {
                throw new IllegalArgumentException("database name is required");
            }
            rejectUrlMetachars("database name", params.database(), ";?/\\=&#");
            int port = params.port() != null ? params.port() : defaultPort(type);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535, got " + port);
            }
            url = type.urlTemplate
                    .replace("{host}", params.host())
                    .replace("{port}", String.valueOf(port))
                    .replace("{db}", params.database());
        }
        return new DataSourceConfig(
                type, url, type.driver, type.dialect,
                params.username() == null ? "" : params.username(),
                params.password() == null ? "" : params.password(),
                filePath,
                params.adminUsername(),
                params.adminPassword());
    }

    private int defaultPort(DbType type) {
        return switch (type) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            default -> 0;
        };
    }

    /**
     * Wizard-supplied URL components are interpolated raw into the JDBC URL template, so any
     * metacharacter that could start a settings/query segment (or otherwise re-shape the URL)
     * must be rejected up front — e.g. an H2 path of {@code /x;INIT=RUNSCRIPT ...} would execute
     * SQL at connect time. Control characters are rejected with the same brush.
     */
    private static void rejectUrlMetachars(String name, String value, String forbidden) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || forbidden.indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                        "Invalid " + name + ": character '"
                                + (c < 0x20 ? "\\x" + Integer.toHexString(c) : c)
                                + "' is not allowed in a JDBC URL component");
            }
        }
    }

    /** Hostnames/IPv4/IPv6 literals only — a broader charset here would allow connector-parameter
     *  injection (e.g. {@code host?useSSL=false} or comma-separated multi-host lists). */
    private static void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || ".-_:%[]".indexOf(c) >= 0;
            if (!ok) {
                throw new IllegalArgumentException(
                        "Invalid host: character '" + c + "' is not allowed");
            }
        }
    }

    /**
     * Tests a connection WITHOUT persisting. Opens a raw JDBC connection (3s timeout),
     * runs {@code SELECT 1}, returns metadata. Connection is always closed.
     */
    public ConnectionTestResult testConnection(DataSourceConfig cfg) {
        try {
            Class.forName(cfg.driver());
        } catch (ClassNotFoundException e) {
            return ConnectionTestResult.fail("Driver not found: " + cfg.driver());
        }
        String sql = "SELECT 1";
        // SQLite uses a different validation query syntax but SELECT 1 works on all four.
        try (Connection conn = DriverManager.getConnection(cfg.url(),
                cfg.username().isBlank() ? null : cfg.username(),
                cfg.password().isBlank() ? null : cfg.password())) {
            conn.createStatement().execute(sql);
            DatabaseMetaData md = conn.getMetaData();
            return ConnectionTestResult.ok(cfg.dialect(),
                    md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
        } catch (Exception e) {
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    /** Decrypts the password field (load() already decrypts; this is for explicitness). */
    public String decryptPassword(DataSourceConfig cfg) {
        return CryptoUtil.decrypt(cfg.password(), machineIdFile());
    }

    /**
     * Deterministic, machine-bound, per-plugin credential for a worker's EMBEDDED database —
     * deliberately independent of the host's own DB username/password, which must never reach
     * a worker process (it would also open the host's database file).
     */
    public String derivePluginDbCredential(String pluginId) {
        return CryptoUtil.deriveMachineSecret("plugin-db:" + pluginId, machineIdFile());
    }

    private Path machineIdFile() {
        return baseDir.resolve("config").resolve(".machineid");
    }

    /**
     * One-time compatibility bridge for builds that stored setup state directly under the
     * working directory or under {@code user.home/.fengyu}. Copying (rather than moving) keeps
     * the old installation recoverable.
     */
    private void migrateLegacyConfigIfNeeded() {
        if (Files.exists(configFile())) return;
        for (Path legacyBaseDir : legacyBaseDirs) {
            if (!legacyBaseDir.equals(baseDir) && migrateLegacyConfig(legacyBaseDir)) {
                return;
            }
        }
    }

    private boolean migrateLegacyConfig(Path legacyBaseDir) {
        Path legacyConfig = legacyBaseDir.resolve("config").resolve("datasource.properties");
        if (!Files.isRegularFile(legacyConfig)) return false;
        try {
            Files.createDirectories(configFile().getParent());
            Path legacyMachineId = legacyBaseDir.resolve("config").resolve(".machineid");
            if (Files.isRegularFile(legacyMachineId) && !Files.exists(machineIdFile())) {
                Files.copy(legacyMachineId, machineIdFile());
                SensitiveFilePermissions.protectFile(machineIdFile());
            }
            Files.copy(legacyConfig, configFile());
            SensitiveFilePermissions.protectDirectory(configFile().getParent());
            SensitiveFilePermissions.protectFile(configFile());
            log.info("Migrated datasource configuration from legacy runtime directory {}", legacyBaseDir);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another startup process completed the same migration.
            return true;
        } catch (IOException e) {
            log.warn("Could not migrate legacy datasource configuration: {}", e.getMessage());
            return false;
        }
    }

    /** Test-only: read raw properties (with encrypted password) for assertions. */
    Properties readRawForTest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configFile())) {
            props.load(in);
        }
        return props;
    }

    /** Test-only: the config file path (for existence assertions). Public so tests in
     *  other packages (e.g. {@code fan.summer.fengyu.HeadlessLauncherProbeTest}) can assert
     *  on the config file without duplicating path logic. */
    public Path configFileForTest() {
        return configFile();
    }
}
