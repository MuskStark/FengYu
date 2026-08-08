package fan.summer.fengyu.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Pattern;

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

    /** {@code true} if a provisioned record exists for {@code pluginId}. */
    public boolean isProvisioned(String pluginId) {
        return store.get(pluginId) != null;
    }

    /**
     * Provisions (or returns the existing) per-plugin DB credentials. Idempotent: a repeat call
     * for the same plugin returns the stored credentials without re-running DDL.
     *
     * @throws DbProvisioningException if admin credentials are absent or the DDL fails.
     */
    public ProvisionedCredentials provision(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb existing = store.get(pluginId);
        if (existing != null) {
            return new ProvisionedCredentials(
                    existing.dbType(), existing.driver(), existing.url(),
                    existing.userName(), existing.password());
        }

        DataSourceConfig cfg = dataSources.load();
        if (cfg == null) {
            throw new DbProvisioningException(
                "Host database is not configured; cannot provision plugin DB.");
        }
        if (!DbDialectStatements.supportsRbac(cfg.type())) {
            throw new DbProvisioningException(
                "Database type " + cfg.type() + " does not support RBAC provisioning "
                + "(SQLite uses file-level isolation).");
        }
        String adminUser = cfg.adminUsername();
        String adminPw = cfg.adminPassword();
        if (adminUser == null || adminUser.isBlank()) {
            throw new DbProvisioningException(
                "Admin credentials are required to provision plugin DBs. "
                + "Set db.admin.username / db.admin.password in the setup wizard.");
        }

        String schemaName = schemaNameFor(pluginId);
        String userName = userNameFor(pluginId);
        String password = generatePassword();

        List<String> ddl = DbDialectStatements.createStatements(cfg.type(), schemaName, userName, password);
        executeDdl(cfg, adminUser, adminPw, ddl, pluginId);

        String workerUrl = workerUrlFor(cfg, schemaName);
        PluginDbProvisioningStore.ProvisionedPluginDb record =
                new PluginDbProvisioningStore.ProvisionedPluginDb(
                        pluginId, cfg.type(), schemaName, userName, password,
                        workerUrl, cfg.driver(), Instant.now().toString());
        store.put(record);
        log.info("Provisioned DB credentials for plugin {} ({} schema {} as {})",
                pluginId, cfg.type(), schemaName, userName);
        return new ProvisionedCredentials(cfg.type(), cfg.driver(), workerUrl, userName, password);
    }

    /**
     * Drops the plugin's DB user + namespace and removes the store record. Non-blocking on
     * failure: a DDL error is logged but never prevents the store record removal — uninstall
     * must always succeed so the user is not stuck with an orphaned plugin entry.
     */
    public void deprovision(String pluginId) {
        PluginDbProvisioningStore.ProvisionedPluginDb rec = store.get(pluginId);
        if (rec == null) {
            log.debug("Deprovision: no stored record for {}, nothing to do.", pluginId);
            return;
        }
        DataSourceConfig cfg = dataSources.load();
        if (cfg != null && DbDialectStatements.supportsRbac(cfg.type())
                && cfg.adminUsername() != null && !cfg.adminUsername().isBlank()) {
            List<String> ddl = DbDialectStatements.dropStatements(cfg.type(), rec.schemaName(), rec.userName());
            try {
                executeDdl(cfg, cfg.adminUsername(), cfg.adminPassword(), ddl, pluginId);
            } catch (DbProvisioningException e) {
                log.warn("Deprovision DDL failed for {} (left for retry): {}", pluginId, e.getMessage());
            }
        }
        store.remove(pluginId);
        log.info("Deprovisioned DB credentials for plugin {}", pluginId);
    }

    private void executeDdl(DataSourceConfig cfg, String adminUser, String adminPw,
            List<String> ddl, String pluginId) {
        try (Connection conn = DriverManager.getConnection(cfg.url(), adminUser, adminPw);
                Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                log.debug("Provisioning DDL for {}: {}", pluginId, sql);
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
                URI uri = URI.create(cfg.url().substring("jdbc:mysql://".length()));
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

    private String generatePassword() {
        byte[] bytes = new byte[PASSWORD_BYTES];
        random.nextBytes(bytes);
        // URL-safe base64 never contains a single-quote, so it is safe to embed in '...'.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
