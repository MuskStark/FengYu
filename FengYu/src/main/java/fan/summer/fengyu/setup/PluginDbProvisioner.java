package fan.summer.fengyu.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static fan.summer.fengyu.setup.PluginDbProvisioningStore.STATUS_ACTIVE;
import static fan.summer.fengyu.setup.PluginDbProvisioningStore.STATUS_DELETE_PENDING;
import static fan.summer.fengyu.setup.PluginDbProvisioningStore.STATUS_PROVISIONING;

/**
 * Orchestrates per-plugin DB credential provisioning. For H2 / MySQL / PostgreSQL it uses the
 * admin credentials from {@code datasource.properties} to CREATE a dedicated DB user + namespace
 * (schema or database) + GRANT, persisted idempotently in {@link PluginDbProvisioningStore}.
 *
 * <p>SQLite is a documented technical exception: the engine has no RBAC, so this provisioner does
 * nothing for it — isolation stays file-level via {@code PluginRuntimeEnvironmentService}'s
 * host-allocated path. Callers should check {@link DbDialectStatements#supportsRbac} before calling.
 *
 * <p>Lives in {@code fan.summer.fengyu.setup} to share {@link CryptoUtil}'s package-private crypto
 * overloads via {@link PluginDbProvisioningStore}.
 */
@Service
public class PluginDbProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PluginDbProvisioner.class);

    /** Identifier sanitizer: keep [a-zA-Z0-9], collapse everything else to underscore. */
    private static final Pattern SAFE_CHAR = Pattern.compile("[^A-Za-z0-9]");
    private static final int PASSWORD_BYTES = 32;

    private final DataSourceConfigService dataSources;
    private final PluginDbProvisioningStore store;
    private final SecureRandom random = new SecureRandom();

    public PluginDbProvisioner(DataSourceConfigService dataSources, PluginDbProvisioningStore store) {
        this.dataSources = dataSources;
        this.store = store;
    }

    /** The credentials a worker environment is injected with for an isolated plugin DB. */
    public record ProvisionedCredentials(
            DbType type, String driver, String url, String username, String password) {}

    /** {@code true} only when credentials are fully provisioned and safe to inject. */
    public boolean isProvisioned(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb record = store.get(pluginId);
        return record != null && record.isActive();
    }

    /** Observable lifecycle state for the plugin DB API. */
    public String status(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb record = store.get(pluginId);
        if (record == null) return "not-provisioned";
        return switch (record.canonicalStatus()) {
            case STATUS_ACTIVE -> "provisioned";
            case STATUS_PROVISIONING -> "provisioning";
            case STATUS_DELETE_PENDING -> "delete-pending";
            default -> "unknown-" + record.canonicalStatus().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Provisions (or returns the existing) per-plugin DB credentials. Idempotent: a repeat call
     * for the same plugin returns the stored credentials without re-running DDL.
     *
     * @throws DbProvisioningException if admin credentials are absent or the DDL fails.
     */
    public synchronized ProvisionedCredentials provision(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb existing = store.get(pluginId);
        if (existing != null) {
            if (STATUS_DELETE_PENDING.equals(existing.canonicalStatus())) {
                throw new DbProvisioningException(
                    "Database cleanup is pending for plugin " + pluginId + "; retry cleanup first.");
            }
            if (existing.isActive()) return credentials(existing);
            if (STATUS_PROVISIONING.equals(existing.canonicalStatus())) {
                return resumeProvision(existing);
            }
            throw new DbProvisioningException(
                "Unknown database provisioning state for plugin " + pluginId + ": "
                    + existing.canonicalStatus());
        }

        DataSourceConfig cfg = requireProvisioningConfig(null);

        String schemaName = schemaNameFor(pluginId);
        String userName = userNameFor(pluginId);
        String password = generatePassword();
        String workerUrl = workerUrlFor(cfg, schemaName);
        PluginDbProvisioningStore.ProvisionedPluginDb record =
                new PluginDbProvisioningStore.ProvisionedPluginDb(
                        pluginId, cfg.type(), schemaName, userName, password,
                        workerUrl, cfg.driver(), Instant.now().toString(), STATUS_PROVISIONING);
        try {
            // Persist the full recovery record before the first side effect. If this fails no DDL
            // is attempted, so a DB account can never exist without durable coordinates.
            store.put(record);
        } catch (RuntimeException e) {
            throw new DbProvisioningException(
                "Could not persist DB provisioning intent; no database changes were attempted.", e);
        }
        return resumeProvision(record);
    }

    private ProvisionedCredentials resumeProvision(
            PluginDbProvisioningStore.ProvisionedPluginDb record) {
        DataSourceConfig cfg = requireProvisioningConfig(record.dbType());
        List<String> ddl = DbDialectStatements.createStatements(record.dbType(),
                record.schemaName(), record.userName(), record.password());
        executeDdl(cfg, cfg.adminUsername(), cfg.adminPassword(), ddl, record.pluginId());
        try {
            store.setStatus(record.pluginId(), STATUS_ACTIVE);
        } catch (RuntimeException e) {
            // The durable PROVISIONING record remains and the idempotent DDL can be reconciled.
            throw new DbProvisioningException(
                "Database DDL completed, but activation could not be persisted; recovery is pending.", e);
        }
        log.info("Provisioned DB credentials for plugin {} ({} schema {} as {})",
                record.pluginId(), record.dbType(), record.schemaName(), record.userName());
        return credentials(record);
    }

    /**
     * Drops the plugin's DB user + namespace and removes the store record. On DDL FAILURE the record
     * is marked {@code DELETE_PENDING} and retained (P1-3): the old behavior deleted the only record
     * holding the schema/user/password, leaving an orphan DB account with no way to retry the drop.
     * Retaining the record lets a background sweep (or the next deprovision attempt) retry with the
     * credentials still in hand. The caller (uninstall) is never blocked — a failed drop surfaces as
     * a {@code DELETE_PENDING} status the UI can distinguish from a clean removal.
     */
    public synchronized void deprovision(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb rec = store.get(pluginId);
        if (rec == null) {
            log.debug("Deprovision: no stored record for {}, nothing to do.", pluginId);
            return;
        }
        if (!STATUS_DELETE_PENDING.equals(rec.canonicalStatus())) {
            // Commit cleanup intent before DDL. A crash, missing config, or SQL error therefore
            // always leaves enough durable information for the scheduled reconciler.
            store.setStatus(pluginId, STATUS_DELETE_PENDING);
            rec = store.get(pluginId);
        }
        retryDelete(rec);
    }

    private boolean retryDelete(PluginDbProvisioningStore.ProvisionedPluginDb rec) {
        DataSourceConfig cfg;
        try {
            cfg = requireProvisioningConfig(rec.dbType());
        } catch (DbProvisioningException e) {
            log.warn("DB cleanup remains DELETE_PENDING for {}: {}", rec.pluginId(), e.getMessage());
            return false;
        }
        List<String> ddl = DbDialectStatements.dropStatements(rec.dbType(),
                rec.schemaName(), rec.userName());
        try {
            executeDdl(cfg, cfg.adminUsername(), cfg.adminPassword(), ddl, rec.pluginId());
        } catch (DbProvisioningException e) {
            log.warn("DB cleanup remains DELETE_PENDING for {}: {}", rec.pluginId(), e.getMessage());
            return false;
        }
        try {
            store.remove(rec.pluginId());
        } catch (RuntimeException e) {
            // Drop statements are idempotent. Keep DELETE_PENDING and retry record removal later.
            log.warn("DB cleanup DDL succeeded for {}, but recovery record removal failed",
                    rec.pluginId(), e);
            return false;
        }
        log.info("Deprovisioned DB credentials for plugin {}", rec.pluginId());
        return true;
    }

    /**
     * Reconciles durable operations after crashes and transient DB/config failures. The method is
     * public so operators/tests can trigger it, and Spring also invokes it periodically.
     */
    @Scheduled(initialDelayString = "${fengyu.plugin-db.reconcile-initial-delay-ms:30000}",
            fixedDelayString = "${fengyu.plugin-db.reconcile-delay-ms:60000}")
    public synchronized void reconcileIncompleteOperations() {
        for (PluginDbProvisioningStore.ProvisionedPluginDb record : store.list()) {
            try {
                switch (record.canonicalStatus()) {
                    case STATUS_PROVISIONING -> resumeProvision(record);
                    case STATUS_DELETE_PENDING -> retryDelete(record);
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.warn("DB reconciliation remains pending for {} ({}): {}",
                        record.pluginId(), record.canonicalStatus(), e.getMessage());
            }
        }
    }

    /** Reconciles one plugin immediately and returns its resulting observable state. */
    public synchronized String retryIncompleteOperation(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb record = store.get(pluginId);
        if (record == null) return "not-provisioned";
        switch (record.canonicalStatus()) {
            case STATUS_PROVISIONING -> resumeProvision(record);
            case STATUS_DELETE_PENDING -> retryDelete(record);
            default -> { }
        }
        return status(pluginId);
    }

    private DataSourceConfig requireProvisioningConfig(DbType expectedType) {
        DataSourceConfig cfg = dataSources.load();
        if (cfg == null) {
            throw new DbProvisioningException(
                "Host database is not configured; cannot reconcile plugin DB.");
        }
        if (expectedType != null && cfg.type() != expectedType) {
            throw new DbProvisioningException(
                "Host database type changed from " + expectedType + " to " + cfg.type()
                    + "; refusing to run recovery DDL against the wrong database.");
        }
        if (!DbDialectStatements.supportsRbac(cfg.type())) {
            throw new DbProvisioningException(
                "Database type " + cfg.type() + " does not support RBAC provisioning "
                + "(SQLite uses file-level isolation).");
        }
        if (cfg.adminUsername() == null || cfg.adminUsername().isBlank()) {
            throw new DbProvisioningException(
                "Admin credentials are required to provision plugin DBs. "
                + "Set db.admin.username / db.admin.password in the setup wizard.");
        }
        return cfg;
    }

    private static ProvisionedCredentials credentials(
            PluginDbProvisioningStore.ProvisionedPluginDb record) {
        return new ProvisionedCredentials(record.dbType(), record.driver(), record.url(),
                record.userName(), record.password());
    }

    private void executeDdl(DataSourceConfig cfg, String adminUser, String adminPw,
            List<String> ddl, String pluginId) {
        try (Connection conn = DriverManager.getConnection(cfg.url(), adminUser, adminPw);
                Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                log.debug("Plugin database DDL for {}: {}", pluginId, redactPassword(sql));
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            throw new DbProvisioningException(
                "Provisioning DDL failed for plugin " + pluginId + ": " + e.getMessage(), e);
        }
    }

    /** {@code fengyu_<safe_id>} — schema (H2/PG) or database (MySQL) name. */
    static String schemaNameFor(String pluginId) {
        return "fengyu_" + safeIdentifier(pluginId);
    }

    /** {@code fengyu_plugin_<safe_id>} — DB user / role name. */
    static String userNameFor(String pluginId) {
        return "fengyu_plugin_" + safeIdentifier(pluginId);
    }

    /**
     * Builds the worker JDBC URL. For MySQL the plugin's database replaces the host's in the path
     * (any existing query string preserved). For H2/PG the plugin's schema is selected via a URL
     * param so the plugin's unqualified DDL lands in its own namespace.
     */
    static String workerUrlFor(DataSourceConfig cfg, String schemaName) {
        return switch (cfg.type()) {
            case H2 -> cfg.url() + ";SCHEMA=" + schemaName;
            case POSTGRESQL -> appendQuery(cfg.url(), "currentSchema=" + schemaName);
            case MYSQL -> {
                // Keep the leading "//" so URI parses host:port as an authority, not an opaque
                // scheme. Stripping "//jdbc:mysql://" leaves "localhost:3306/db?q" which URI
                // treats as opaque (scheme=localhost) → host/port are lost.
                URI uri = URI.create(cfg.url().substring("jdbc:mysql:".length()));
                String hostPart = uri.getHost() == null ? "" : uri.getHost();
                if (uri.getPort() != -1) hostPart += ":" + uri.getPort();
                yield "jdbc:mysql://" + hostPart + "/" + schemaName
                    + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
            }
            default -> cfg.url();
        };
    }

    private static String appendQuery(String url, String param) {
        return url.contains("?") ? url + "&" + param : url + "?" + param;
    }

    private static String safeIdentifier(String pluginId) {
        String cleaned = SAFE_CHAR.matcher(pluginId).replaceAll("_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) cleaned = "p_" + cleaned;
        return cleaned;
    }

    /**
     * Masks the literal in {@code PASSWORD '...'} / {@code IDENTIFIED BY '...'} so plugin-user
     * credentials never enter the log, even at DEBUG. Keeps the keyword so the statement kind
     * stays legible. The actual {@code stmt.execute} always uses the unredacted statement.
     */
    static String redactPassword(String sql) {
        return sql.replaceAll("(?i)(PASSWORD|IDENTIFIED BY) '[^']*'", "$1 '***'");
    }

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        // URL-safe base64 never contains a single-quote, so it is safe to embed in '...'.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
