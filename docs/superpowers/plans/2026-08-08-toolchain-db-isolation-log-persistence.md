# Toolchain DB 强制隔离 + 日志落盘 + SDK 小修 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade FengYu's plugin DB access from a table-prefix-convention to engine-enforced DB-level isolation (per-plugin user/schema/GRANT), add per-plugin persistent log files, and fix the SDK severity switch default — addressing the 4 findings from the toolchain SDK audit.

**Architecture:** For H2/MySQL/PostgreSQL, a new `PluginDbProvisioner` uses setup-wizard-collected admin credentials to create a dedicated DB user + namespace + GRANT per `database`-permission plugin, stored encrypted in `plugin-db.properties`; workers receive only their own credentials (never the host's). H2 switches from file-mode to an in-process TCP server (solving the exclusive file lock) started before the startup DB probe. SQLite is a documented RBAC exception (file-level isolation). Plugin logs persist via a Logback SiftingAppender keyed by an MDC `pluginId`. The SDK severity switch gains a defensive `default`.

**Tech Stack:** Java 21 (Spring Boot, HikariCP, Logback, H2 2.4.240 `org.h2.tools.Server`, SLF4J 2.x), Vue 3.5 + TypeScript + Vuetify 3, VitePress docs.

## Global Constraints

- Plugin workers stay out-of-process, JSON-RPC over stdio, own classpath — never share host Spring/JPA context, never receive a live `Connection`/`DataSource`/`EntityManager`.
- Never share host runtime or admin credentials with a worker — only per-plugin provisioned credentials.
- No Testcontainers (YAGNI, big build change) — H2 RBAC tested with a real in-process TCP server; MySQL/PG DDL tested at the SQL-string level.
- `CryptoUtil`'s `(String, Path)` crypto overloads are package-private — new credential stores live in package `fan.summer.fengyu.setup` (deliberate deviation from the spec's `plugin.runtime` placement).
- Toolchain SDK version is NOT bumped (Task 11 is defensive hardening, no contract change).
- Commit convention: conventional commits with emojis — ✨ feat, 🐛 fix, ♻️ refactor, 📝 docs.
- Run `./mvnw` (not system Maven) from the repo root. Frontend: `cd frontend && npm run build`.
- Spec: `docs/superpowers/specs/2026-08-08-toolchain-db-isolation-log-persistence-design.md`.

---

## Part A — Database Isolation (Tasks 1–9, the critical path)

### Task 1: Add optional admin credentials to datasource config layer

Adds `adminUsername` / `adminPassword` end-to-end through the setup record layer, encrypted at rest, mirroring existing `username`/`password`. **No behavior change yet** — later tasks consume them.

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfig.java` (whole record + compat constructor)
- Modify: `FengYu/src/main/java/fan/summer/fengyu/setup/WizardParams.java` (whole record + compat constructor)
- Modify: `FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfigService.java` (`load()` ~:81-88, `save()` ~:107-109, `buildFromWizard` ~:191-196)
- Test: `FengYu/src/test/java/fan/summer/fengyu/setup/DataSourceConfigAdminCredentialsTest.java` (Create)

**Interfaces:**
- Consumes: existing `CryptoUtil.encrypt(String, Path)` / `decrypt(String, Path)` package-private overloads; existing `machineIdFile()`.
- Produces: `DataSourceConfig.adminUsername()` / `adminPassword()` and `WizardParams.adminUsername()` / `adminPassword()` accessors (used by Task 4).

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/setup/DataSourceConfigAdminCredentialsTest.java`:

```java
package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceConfigAdminCredentialsTest {
    @TempDir Path temp;

    @Test
    void adminCredentialsRoundTripAndAreEncryptedOnDisk() throws Exception {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        DataSourceConfig cfg = new DataSourceConfig(DbType.POSTGRESQL,
            "jdbc:postgresql://db:5432/fengyu", "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect", "fengyu", "secret", null,
            "fengyu_admin", "admin-secret");
        svc.save(cfg);

        Properties raw = svc.readRawForTest();
        assertEquals("fengyu_admin", raw.getProperty("db.admin.username"));
        assertTrue(raw.getProperty("db.admin.password").startsWith("ENC("),
            "admin password must be encrypted at rest: " + raw.getProperty("db.admin.password"));
        assertFalse(raw.getProperty("db.admin.password").contains("admin-secret"),
            "plaintext admin secret must not appear on disk");

        DataSourceConfig loaded = svc.load();
        assertEquals("fengyu_admin", loaded.adminUsername());
        assertEquals("admin-secret", loaded.adminPassword());
        assertEquals("secret", loaded.password());
    }

    @Test
    void missingAdminCredentialsLoadAsNullNotBlank() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.SQLITE, "jdbc:sqlite:app.db", "org.sqlite.JDBC",
            "org.hibernate.community.dialect.SQLiteDialect", "", "", "/app.db", null, null));
        DataSourceConfig loaded = svc.load();
        assertNull(loaded.adminUsername());
        assertNull(loaded.adminPassword());
    }

    @Test
    void buildFromWizardCarriesAdminParamsThrough() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        WizardParams params = new WizardParams(null, "db", 5432, "fengyu",
            "fengyu", "secret", "fengyu_admin", "admin-secret");
        DataSourceConfig cfg = svc.buildFromWizard(DbType.POSTGRESQL, params);
        assertEquals("fengyu_admin", cfg.adminUsername());
        assertEquals("admin-secret", cfg.adminPassword());
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error — fields don't exist yet)**

Run: `./mvnw -q -pl FengYu test -Dtest=DataSourceConfigAdminCredentialsTest`
Expected: COMPILE FAILURE — `DataSourceConfig` record components `adminUsername`, `adminPassword` not found.

- [ ] **Step 3: Extend `DataSourceConfig` with the two new fields**

Replace the whole body of `FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfig.java`:

```java
package fan.summer.fengyu.setup;

/**
 * Immutable datasource configuration — the fully-resolved connection descriptor.
 *
 * <p>Built by {@link DataSourceConfigService} from a {@link DbType} + {@link WizardParams},
 * persisted to {@code datasource.properties}, and used to construct the HikariCP DataSource
 * on APP-mode startup.
 *
 * @param type           the database type
 * @param url            the fully-assembled JDBC URL
 * @param driver         the JDBC driver class name
 * @param dialect        the Hibernate dialect class name
 * @param username       remote DB username; blank for embedded H2/SQLite
 * @param password       remote DB password (plaintext in-memory; encrypted on disk); blank for embedded
 * @param filePath       embedded DB file path (H2/SQLite); null for remote
 * @param adminUsername  optional admin username used ONLY for plugin DB provisioning DDL
 *                       (CREATE USER/SCHEMA/GRANT); null/blank when not configured (e.g. SQLite).
 *                       Never injected into plugin worker environments.
 * @param adminPassword  optional admin password (plaintext in-memory; encrypted on disk); null/blank
 *                       when not configured.
 */
public record DataSourceConfig(
        DbType type,
        String url,
        String driver,
        String dialect,
        String username,
        String password,
        String filePath,
        String adminUsername,
        String adminPassword
) {
    /** Backwards-compatible constructor for callers that omit admin credentials. */
    public DataSourceConfig(DbType type, String url, String driver, String dialect,
            String username, String password, String filePath) {
        this(type, url, driver, dialect, username, password, filePath, null, null);
    }
}
```

- [ ] **Step 4: Extend `WizardParams` with the two new fields**

Replace the whole body of `FengYu/src/main/java/fan/summer/fengyu/setup/WizardParams.java`:

```java
package fan.summer.fengyu.setup;

/**
 * Raw parameters submitted by the setup wizard frontend, before being assembled into a
 * {@link DataSourceConfig}. Field relevance depends on the {@link DbType#embedded} flag.
 *
 * @param filePath       embedded DB file path (H2/SQLite); ignored for remote
 * @param host           remote DB hostname; ignored for embedded
 * @param port           remote DB port; ignored for embedded
 * @param database       remote DB name; ignored for embedded
 * @param username       remote DB username; ignored for embedded
 * @param password       remote DB password; ignored for embedded
 * @param adminUsername  optional admin username for plugin DB provisioning (H2 server / MySQL / PG);
 *                       hidden and unused for SQLite. May be null/blank.
 * @param adminPassword  optional admin password; hidden and unused for SQLite. May be null/blank.
 */
public record WizardParams(
        String filePath,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String adminUsername,
        String adminPassword
) {
    /** Backwards-compatible constructor for callers that omit admin credentials. */
    public WizardParams(String filePath, String host, Integer port,
            String database, String username, String password) {
        this(filePath, host, port, database, username, password, null, null);
    }
}
```

- [ ] **Step 5: Wire admin fields into `load()` and `save()`**

In `FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfigService.java`, edit the `load()` return so it reads (replacing the 7-arg constructor call):

```java
            return new DataSourceConfig(
                    type,
                    props.getProperty("db.url"),
                    props.getProperty("db.driver"),
                    props.getProperty("db.dialect"),
                    props.getProperty("db.username", ""),
                    CryptoUtil.decrypt(props.getProperty("db.password", ""), machineIdFile()),
                    props.getProperty("db.file.path", ""),
                    props.getProperty("db.admin.username", ""),
                    CryptoUtil.decrypt(props.getProperty("db.admin.password", ""), machineIdFile()));
```

Then in `save()`, insert this block immediately after the existing `db.password` block and before the `db.file.path` line:

```java
            if (cfg.adminUsername() != null && !cfg.adminUsername().isBlank()) {
                props.setProperty("db.admin.username", cfg.adminUsername());
            }
            if (cfg.adminPassword() != null && !cfg.adminPassword().isBlank()) {
                props.setProperty("db.admin.password",
                    CryptoUtil.encrypt(cfg.adminPassword(), machineIdFile()));
            }
```

- [ ] **Step 6: Carry admin params through `buildFromWizard`**

Edit the `buildFromWizard` return (it currently builds a 7-arg `DataSourceConfig`):

```java
        return new DataSourceConfig(
                type, url, type.driver, type.dialect,
                params.username() == null ? "" : params.username(),
                params.password() == null ? "" : params.password(),
                filePath,
                params.adminUsername(),
                params.adminPassword());
```

- [ ] **Step 7: Add a test-only `readRawForTest()` helper to DataSourceConfigService**

Add inside `DataSourceConfigService` (so the test can assert the on-disk encrypted form):

```java
    /** Test-only: read raw properties (with encrypted password) for assertions. */
    Properties readRawForTest() throws java.io.IOException {
        Properties props = new Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(configFile())) {
            props.load(in);
        }
        return props;
    }
```

- [ ] **Step 8: Run the new test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=DataSourceConfigAdminCredentialsTest`
Expected: PASS — all three tests green.

- [ ] **Step 9: Run existing setup + env-service suites to confirm no regression**

Run: `./mvnw -q -pl FengYu test -Dtest='DataSourceConfigServiceTest,PluginRuntimeEnvironmentServiceTest'`
Expected: PASS — the backwards-compatible constructors keep every existing caller working.

- [ ] **Step 10: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfig.java \
        FengYu/src/main/java/fan/summer/fengyu/setup/WizardParams.java \
        FengYu/src/main/java/fan/summer/fengyu/setup/DataSourceConfigService.java \
        FengYu/src/test/java/fan/summer/fengyu/setup/DataSourceConfigAdminCredentialsTest.java
git commit -m "✨ feat(setup): add optional admin credentials to datasource config for plugin DB provisioning"
```

---

### Task 2: Create `PluginDbProvisioningStore` — encrypted per-plugin credential store

Persists provisioned per-plugin DB credentials to `<config>/plugin-db.properties` with the plugin password AES-GCM encrypted (same `.machineid` as `datasource.properties`). Lives in package `fan.summer.fengyu.setup` to call the package-private `CryptoUtil` overloads.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioningStore.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisioningStoreTest.java`

**Interfaces:**
- Consumes: `CryptoUtil` package-private overloads; `SensitiveFilePermissions`; `RuntimePaths`.
- Produces:
  - `record ProvisionedPluginDb(String pluginId, DbType dbType, String schemaName, String userName, String password, String url, String driver, String provisionedAt)` (nested in the store)
  - `PluginDbProvisioningStore()` production constructor; `PluginDbProvisioningStore(Path baseDir)` test constructor
  - `ProvisionedPluginDb get(String pluginId)`, `void put(ProvisionedPluginDb)`, `boolean remove(String pluginId)`

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisioningStoreTest.java`:

```java
package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDbProvisioningStoreTest {
    @TempDir Path temp;

    private PluginDbProvisioningStore newStore() {
        return new PluginDbProvisioningStore(temp);
    }

    @Test
    void putGetRemoveRoundTripsAndPasswordIsEncryptedOnDisk() throws Exception {
        PluginDbProvisioningStore store = newStore();
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.POSTGRESQL, "fengyu_fan_summer_email",
            "fengyu_plugin_email", "super-secret-pw", "jdbc:postgresql://db/fengyu",
            "org.postgresql.Driver", "2026-08-08T10:00:00Z"));

        PluginDbProvisioningStore.ProvisionedPluginDb loaded = store.get("fan.summer.email");
        assertEquals("fengyu_plugin_email", loaded.userName());
        assertEquals("super-secret-pw", loaded.password(), "password must decrypt back to plaintext");

        Properties raw = store.readRawForTest();
        String onDisk = raw.getProperty("plugin.fan.summer.email.password");
        assertTrue(onDisk.startsWith("ENC("), "plugin password must be encrypted at rest: " + onDisk);
        assertFalse(raw.toString().contains("super-secret-pw"));

        assertTrue(store.remove("fan.summer.email"));
        assertNull(store.get("fan.summer.email"));
        assertFalse(store.remove("fan.summer.email"), "second remove returns false");
    }

    @Test
    void getReturnsNullForUnknownPlugin() {
        assertNull(newStore().get("no.such.plugin"));
    }

    @Test
    void overwritingSamePluginReplacesTheRecord() {
        PluginDbProvisioningStore store = newStore();
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "p", DbType.MYSQL, "fengyu_p", "u1", "pw1", "jdbc:mysql://h/fengyu_p",
            "com.mysql.cj.jdbc.Driver", "t1"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "p", DbType.MYSQL, "fengyu_p", "u2", "pw2", "jdbc:mysql://h/fengyu_p",
            "com.mysql.cj.jdbc.Driver", "t2"));
        PluginDbProvisioningStore.ProvisionedPluginDb loaded = store.get("p");
        assertEquals("u2", loaded.userName());
        assertEquals("pw2", loaded.password());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbProvisioningStoreTest`
Expected: COMPILE FAILURE — `PluginDbProvisioningStore` not found.

- [ ] **Step 3: Implement `PluginDbProvisioningStore`**

Create `FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioningStore.java`:

```java
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
    PluginDbProvisioningStore(Path baseDir) {
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbProvisioningStoreTest`
Expected: PASS — all three tests green; password is `ENC(...)` on disk.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioningStore.java \
        FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisioningStoreTest.java
git commit -m "✨ feat(setup): add encrypted PluginDbProvisioningStore for per-plugin DB credentials"
```

---

### Task 3: Create `DbDialectStatements` — per-DB-type DDL string generator

Pure, side-effect-free generator of `CREATE USER/SCHEMA/GRANT` and `DROP` SQL strings for H2, MySQL, PostgreSQL. SQLite returns empty lists (file-based exception). Tested at the SQL-string level.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/setup/DbDialectStatements.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/setup/DbDialectStatementsTest.java`

**Interfaces:**
- Consumes: `DbType` enum.
- Produces (all `static`, package-private): `List<String> createStatements(DbType, String schemaName, String userName, String password)`, `List<String> dropStatements(DbType, String schemaName, String userName)`, `boolean supportsRbac(DbType)`.

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/setup/DbDialectStatementsTest.java`:

```java
package fan.summer.fengyu.setup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDialectStatementsTest {
    private static final String SCHEMA = "fengyu_fan_summer_email";
    private static final String USER = "fengyu_plugin_email";
    private static final String PW = "S3cr3t!";

    @Test
    void h2CreateCreatesUserSchemaAndGrantsAll() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.H2, SCHEMA, USER, PW);
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS " + USER + " PASSWORD '" + PW + "'",
            "CREATE SCHEMA IF NOT EXISTS " + SCHEMA + " AUTHORIZATION " + USER,
            "GRANT ALL ON SCHEMA " + SCHEMA + " TO " + USER), ddl);
    }

    @Test
    void h2DropDropsSchemaThenUser() {
        assertEquals(List.of(
            "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE",
            "DROP USER IF EXISTS " + USER),
            DbDialectStatements.dropStatements(DbType.H2, SCHEMA, USER));
    }

    @Test
    void mysqlCreateCreatesUserDatabaseAndGrantsPrivileges() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.MYSQL, SCHEMA, USER, PW);
        assertEquals(List.of(
            "CREATE USER IF NOT EXISTS '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + PW + "'",
            "CREATE DATABASE IF NOT EXISTS `" + SCHEMA + "`",
            "GRANT ALL PRIVILEGES ON `" + SCHEMA + "`.* TO '" + USER + "'@'127.0.0.1'"), ddl);
    }

    @Test
    void mysqlDropDropsDatabaseThenUser() {
        assertEquals(List.of(
            "DROP DATABASE IF EXISTS `" + SCHEMA + "`",
            "DROP USER IF EXISTS '" + USER + "'@'127.0.0.1'"),
            DbDialectStatements.dropStatements(DbType.MYSQL, SCHEMA, USER));
    }

    @Test
    void postgresCreateCreatesRoleSchemaAndGrantsUsageCreate() {
        List<String> ddl = DbDialectStatements.createStatements(DbType.POSTGRESQL, SCHEMA, USER, PW);
        assertEquals(List.of(
            "CREATE ROLE \"" + USER + "\" LOGIN PASSWORD '" + PW + "'",
            "CREATE SCHEMA IF NOT EXISTS " + SCHEMA + " AUTHORIZATION \"" + USER + "\"",
            "GRANT USAGE, CREATE ON SCHEMA " + SCHEMA + " TO \"" + USER + "\""), ddl);
    }

    @Test
    void postgresDropDropsSchemaThenRole() {
        assertEquals(List.of(
            "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE",
            "DROP ROLE IF EXISTS \"" + USER + "\""),
            DbDialectStatements.dropStatements(DbType.POSTGRESQL, SCHEMA, USER));
    }

    @Test
    void sqliteEmitsNoDdlAndIsFlaggedAsNonRbac() {
        assertTrue(DbDialectStatements.createStatements(DbType.SQLITE, SCHEMA, USER, PW).isEmpty());
        assertTrue(DbDialectStatements.dropStatements(DbType.SQLITE, SCHEMA, USER).isEmpty());
        assertFalse(DbDialectStatements.supportsRbac(DbType.SQLITE));
        assertTrue(DbDialectStatements.supportsRbac(DbType.H2));
        assertTrue(DbDialectStatements.supportsRbac(DbType.MYSQL));
        assertTrue(DbDialectStatements.supportsRbac(DbType.POSTGRESQL));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=DbDialectStatementsTest`
Expected: COMPILE FAILURE — `DbDialectStatements` not found.

- [ ] **Step 3: Implement `DbDialectStatements`**

Create `FengYu/src/main/java/fan/summer/fengyu/setup/DbDialectStatements.java`:

```java
package fan.summer.fengyu.setup;

import java.util.List;

/**
 * Pure per-DB-type DDL string generator for plugin DB provisioning. No execution, no I/O —
 * the provisioner runs these via a {@code java.sql.Connection} opened with admin creds.
 *
 * <p>Design notes:
 * <ul>
 *   <li>H2 / PostgreSQL use schema-granular isolation inside the host's existing database;
 *       MySQL uses a per-plugin database (its GRANT model is database-granular).</li>
 *   <li>All {@code CREATE} statements are idempotent ({@code IF NOT EXISTS}).</li>
 *   <li>SQLite emits NO DDL — the engine has no RBAC and no TCP server. It is a documented
 *       technical exception; isolation for SQLite stays file-level (host-allocated path).</li>
 * </ul>
 *
 * <p>Generated strings embed caller-supplied identifiers. The provisioner constructs
 * {@code schemaName} and {@code userName} from a sanitized transform of the plugin id, and
 * passwords are URL-safe base64 (never a literal single-quote), so no escaping is needed.
 */
final class DbDialectStatements {

    private DbDialectStatements() {}

    /** {@code true} when the engine supports CREATE USER / GRANT (H2, MySQL, PostgreSQL). */
    static boolean supportsRbac(DbType type) {
        return type != DbType.SQLITE;
    }

    static List<String> createStatements(DbType type, String schemaName, String userName, String password) {
        return switch (type) {
            case H2 -> List.of(
                    "CREATE USER IF NOT EXISTS " + userName + " PASSWORD '" + password + "'",
                    "CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION " + userName,
                    "GRANT ALL ON SCHEMA " + schemaName + " TO " + userName);
            case MYSQL -> List.of(
                    "CREATE USER IF NOT EXISTS '" + userName + "'@'127.0.0.1' IDENTIFIED BY '" + password + "'",
                    "CREATE DATABASE IF NOT EXISTS `" + schemaName + "`",
                    "GRANT ALL PRIVILEGES ON `" + schemaName + "`.* TO '" + userName + "'@'127.0.0.1'");
            case POSTGRESQL -> List.of(
                    "CREATE ROLE \"" + userName + "\" LOGIN PASSWORD '" + password + "'",
                    "CREATE SCHEMA IF NOT EXISTS " + schemaName + " AUTHORIZATION \"" + userName + "\"",
                    "GRANT USAGE, CREATE ON SCHEMA " + schemaName + " TO \"" + userName + "\"");
            case SQLITE -> List.of();
        };
    }

    static List<String> dropStatements(DbType type, String schemaName, String userName) {
        return switch (type) {
            case H2 -> List.of(
                    "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE",
                    "DROP USER IF EXISTS " + userName);
            case MYSQL -> List.of(
                    "DROP DATABASE IF EXISTS `" + schemaName + "`",
                    "DROP USER IF EXISTS '" + userName + "'@'127.0.0.1'");
            case POSTGRESQL -> List.of(
                    "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE",
                    "DROP ROLE IF EXISTS \"" + userName + "\"");
            case SQLITE -> List.of();
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=DbDialectStatementsTest`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/setup/DbDialectStatements.java \
        FengYu/src/test/java/fan/summer/fengyu/setup/DbDialectStatementsTest.java
git commit -m "✨ feat(setup): add DbDialectStatements per-DB-type DDL generator for plugin provisioning"
```

---

### Task 4: Create `PluginDbProvisioner` — orchestrate provisioning + deprovisioning

Orchestrates: load admin creds → idempotency check → generate safe user/schema names + strong password → execute `DbDialectStatements` DDL over a JDBC connection (admin creds) → persist to store → return credentials. Deprovisioning runs DROP + removes the record, non-blocking on failure. H2 path tested end-to-end with a real in-process H2 TCP server.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioner.java`
- Create: `FengYu/src/main/java/fan/summer/fengyu/setup/DbProvisioningException.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisionerH2Test.java`

**Interfaces:**
- Consumes: `DataSourceConfigService.load()`; `PluginDbProvisioningStore` (Task 2); `DbDialectStatements` (Task 3).
- Produces:
  - `record ProvisionedCredentials(DbType type, String driver, String url, String username, String password)` (nested)
  - `ProvisionedCredentials provision(String pluginId)` — idempotent; throws `DbProvisioningException`
  - `void deprovision(String pluginId)` — non-blocking on failure
  - `boolean isProvisioned(String pluginId)`

- [ ] **Step 1: Write the failing test (real in-process H2 TCP server)**

Create `FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisionerH2Test.java`:

```java
package fan.summer.fengyu.setup;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the H2 provisioning path against a REAL in-process H2 TCP server.
 * Verifies CREATE USER / CREATE SCHEMA / GRANT actually run, and that the provisioned plugin
 * user can connect but is confined to its own schema (the DB engine enforces it).
 *
 * <p>MySQL/PostgreSQL DDL is covered at the string level by DbDialectStatementsTest; executing
 * it against live servers is a documented manual-e2e gap (no Testcontainers per spec).
 */
class PluginDbProvisionerH2Test {

    private static Server h2Server;
    private static int port;

    @BeforeAll
    static void startH2Tcp() throws Exception {
        Path dbDir = Path.of(System.getProperty("java.io.tmpdir"),
            "fengyu-provisioner-test-" + System.nanoTime());
        java.nio.file.Files.createDirectories(dbDir);
        h2Server = Server.createTcpServer(
                "-tcp", "-tcpHost", "127.0.0.1", "-tcpPort", "0",
                "-tcpAllowOthers", "false", "-ifNotExists",
                "-baseDir", dbDir.toString()).start();
        port = h2Server.getPort();
        // Seed two host databases with the H2 default admin "sa" (blank password).
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu;USER=sa;PASSWORD=")) {
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS PUBLIC");
        }
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu2;USER=sa;PASSWORD=")) {
            c.createStatement().execute("CREATE SCHEMA IF NOT EXISTS PUBLIC");
        }
    }

    @AfterAll
    static void stopH2Tcp() {
        if (h2Server != null) h2Server.stop();
    }

    @TempDir Path config;

    @Test
    void provisionCreatesUserSchemaGrantAndConfinesThePluginUser() throws Exception {
        DataSourceConfigService dataSources = new DataSourceConfigService(config) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2,
                    "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu", "org.h2.Driver",
                    "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config);
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);

        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("fan.summer.email");

        assertEquals(DbType.H2, creds.type());
        assertNotNull(creds.username());
        assertNotNull(creds.password());
        assertTrue(creds.url().startsWith("jdbc:h2:tcp://127.0.0.1:" + port + "/"),
            "worker URL must point at the H2 TCP server: " + creds.url());
        assertTrue(provisioner.isProvisioned("fan.summer.email"));

        // The plugin user can create a table in its own schema.
        try (Connection pluginConn = DriverManager.getConnection(
                creds.url(), creds.username(), creds.password())) {
            pluginConn.createStatement().execute(
                "CREATE TABLE fengyu_fan_summer_email.PLUGIN_TABLE (id INT)");
            pluginConn.createStatement().execute(
                "INSERT INTO fengyu_fan_summer_email.PLUGIN_TABLE VALUES (1)");
        }

        // The plugin user CANNOT write to the host's PUBLIC schema (no GRANT).
        try (Connection pluginConn = DriverManager.getConnection(
                creds.url(), creds.username(), creds.password())) {
            assertThrows(java.sql.SQLException.class, () ->
                pluginConn.createStatement().execute("CREATE TABLE PUBLIC.PLUGIN_LEAK (id INT)"),
                "plugin user must NOT be able to create tables in the host's PUBLIC schema");
        }

        // Idempotent: second provision returns the SAME credentials without re-creating.
        PluginDbProvisioner.ProvisionedCredentials again =
            provisioner.provision("fan.summer.email");
        assertEquals(creds.username(), again.username());
        assertEquals(creds.password(), again.password());
    }

    @Test
    void deprovisionDropsUserAndSchemaAndRemovesStoreRecord() throws Exception {
        DataSourceConfigService dataSources = new DataSourceConfigService(config) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2,
                    "jdbc:h2:tcp://127.0.0.1:" + port + "/fengyu2", "org.h2.Driver",
                    "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(config);
        PluginDbProvisioner provisioner = new PluginDbProvisioner(dataSources, store);

        PluginDbProvisioner.ProvisionedCredentials creds =
            provisioner.provision("fan.summer.calendar");
        assertTrue(provisioner.isProvisioned("fan.summer.calendar"));

        provisioner.deprovision("fan.summer.calendar");
        assertFalse(provisioner.isProvisioned("fan.summer.calendar"),
            "store record must be gone after deprovision");

        assertThrows(java.sql.SQLException.class, () ->
            DriverManager.getConnection(creds.url(), creds.username(), creds.password()),
            "dropped plugin user must not be able to connect");
    }

    @Test
    void provisionThrowsWhenAdminCredentialsMissing() {
        DataSourceConfigService dataSources = new DataSourceConfigService(config) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.MYSQL, "jdbc:mysql://h/d",
                    "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
                    "u", "p", null, null, null);
            }
        };
        PluginDbProvisioner provisioner =
            new PluginDbProvisioner(dataSources, new PluginDbProvisioningStore(config));
        DbProvisioningException ex = assertThrows(DbProvisioningException.class,
            () -> provisioner.provision("fan.summer.email"));
        assertTrue(ex.getMessage().toLowerCase().contains("admin"),
            "error message must explain admin credentials are required: " + ex.getMessage());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbProvisionerH2Test`
Expected: COMPILE FAILURE — `PluginDbProvisioner` / `ProvisionedCredentials` / `DbProvisioningException` not found.

- [ ] **Step 3: Implement `DbProvisioningException`**

Create `FengYu/src/main/java/fan/summer/fengyu/setup/DbProvisioningException.java`:

```java
package fan.summer.fengyu.setup;

/**
 * Thrown when plugin DB provisioning cannot complete — typically because admin credentials are
 * absent or lack the {@code CREATE USER / SCHEMA} privileges the DDL needs. The host MUST surface
 * this to the user and MUST NOT silently fall back to sharing the host's global DB credentials
 * (that would defeat the DB-level isolation invariant).
 */
public class DbProvisioningException extends RuntimeException {
    public DbProvisioningException(String message) { super(message); }
    public DbProvisioningException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 4: Implement `PluginDbProvisioner`**

Create `FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioner.java`:

```java
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbProvisionerH2Test`
Expected: PASS — all 3 tests green. The confinement assertion proves the H2 engine actually rejects the plugin user's write to `PUBLIC`.

- [ ] **Step 6: Run the store + dialect tests together to confirm the package compiles cleanly**

Run: `./mvnw -q -pl FengYu test -Dtest='PluginDb*,DbDialectStatementsTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/setup/PluginDbProvisioner.java \
        FengYu/src/main/java/fan/summer/fengyu/setup/DbProvisioningException.java \
        FengYu/src/test/java/fan/summer/fengyu/setup/PluginDbProvisionerH2Test.java
git commit -m "✨ feat(setup): add PluginDbProvisioner with idempotent H2/MySQL/PG RBAC provisioning"
```

---

### Task 5: Create `H2TcpServerConfig` + wire startup before `probeAndDecide`

Starts `org.h2.tools.Server` in-process bound to loopback on an OS-assigned port, **before** `HeadlessLauncher.main` calls `probeAndDecide` (which opens a JDBC connection — for H2 server mode that connection is `tcp://` and needs the server up first). The host's `db.url` is migrated from `file:` to `tcp://` on first boot; `H2Dialect` is unaffected by the URL scheme.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/HeadlessLauncher.java` (import + `main`)
- Create: `FengYu/src/test/java/fan/summer/fengyu/config/H2TcpServerConfigTest.java`

**Interfaces:**
- Consumes: `DataSourceConfigService.load()`/`save()`; `RuntimePaths`.
- Produces: `static int H2TcpServerConfig.startIfNeeded(DataSourceConfigService)` (called from `main` BEFORE `probeAndDecide`), `static int H2TcpServerConfig.port()`, `@PreDestroy stop()` Spring bean.

- [ ] **Step 1: Write the failing test**

Create `FengYu/src/test/java/fan/summer/fengyu/config/H2TcpServerConfigTest.java`:

```java
package fan.summer.fengyu.config;

import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2TcpServerConfigTest {

    @TempDir Path temp;

    @AfterEach
    void stopServer() {
        H2TcpServerConfig.stopForTest();
    }

    @Test
    void startsOnLoopbackWithDynamicPortWhenHostDbIsH2() throws Exception {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));

        int port = H2TcpServerConfig.startIfNeeded(svc);
        assertTrue(port > 0, "H2 TCP server should have started on a dynamic port");

        DataSourceConfig reloaded = svc.load();
        assertTrue(reloaded.url().startsWith("jdbc:h2:tcp://127.0.0.1:" + port + "/"),
            "host url must be rewritten to tcp://: " + reloaded.url());

        try (Connection c = DriverManager.getConnection(reloaded.url(), "sa", "")) {
            ResultSet rs = c.createStatement().executeQuery("SELECT 1");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void doesNotStartWhenHostDbIsNotH2() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.POSTGRESQL, "jdbc:postgresql://h/d",
            "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
            "u", "p", null, null, null));
        assertEquals(0, H2TcpServerConfig.startIfNeeded(svc),
            "no H2 TCP server should start for a PostgreSQL host");
    }

    @Test
    void doesNotStartWhenHostDbIsUnconfigured() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        assertEquals(0, H2TcpServerConfig.startIfNeeded(svc));
    }

    @Test
    void startingTwiceIsIdempotentAndReusesPort() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu2"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));
        int first = H2TcpServerConfig.startIfNeeded(svc);
        int second = H2TcpServerConfig.startIfNeeded(svc);
        assertEquals(first, second, "second start must reuse the running server's port");
    }

    @Test
    void rewrittenTcpUrlUsesDifferentPortThan24056() {
        DataSourceConfigService svc = new DataSourceConfigService(temp.toString());
        svc.save(new DataSourceConfig(DbType.H2, "jdbc:h2:file:" + temp.resolve("fengyu3"),
            "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", ""));
        int port = H2TcpServerConfig.startIfNeeded(svc);
        assertNotEquals(24056, port);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=H2TcpServerConfigTest`
Expected: COMPILE FAILURE — `H2TcpServerConfig` not found.

- [ ] **Step 3: Implement `H2TcpServerConfig`**

Create `FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java`:

```java
package fan.summer.fengyu.config;

import fan.summer.fengyu.runtime.RuntimePaths;
import fan.summer.fengyu.setup.DataSourceConfig;
import fan.summer.fengyu.setup.DataSourceConfigService;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.SensitiveFilePermissions;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the in-process H2 TCP server (loopback, dynamic port) so that:
 * <ol>
 *   <li>the host's own DataSource can connect via {@code tcp://} (no exclusive file lock), and</li>
 *   <li>plugin workers provisioned by {@code PluginDbProvisioner} can attach to per-plugin
 *       schemas on the SAME running server (true DB-level RBAC isolation).</li>
 * </ol>
 *
 * <p><b>Lifecycle ordering (critical).</b> {@code HeadlessLauncher.probeAndDecide} opens a JDBC
 * connection BEFORE Spring boots. So {@link #startIfNeeded(DataSourceConfigService)} is invoked
 * from {@code HeadlessLauncher.main} BEFORE the probe, not from a Spring {@code @PostConstruct}.
 * This class's Spring identity exists only so {@link #stop()} runs on context shutdown via
 * {@code @PreDestroy}.
 *
 * <p>On first start the host's persisted {@code db.url} is migrated from {@code file:} to
 * {@code tcp://127.0.0.1:<port>/...}; the Hibernate dialect ({@code H2Dialect}) is URL-scheme
 * agnostic so this is safe. The chosen port is recorded to {@code <config>/h2-server.properties}
 * for diagnostics.
 */
@Configuration
@ConditionalOnProperty(name = "fengyu.mode", havingValue = "app")
public class H2TcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServerConfig.class);
    private static final Pattern H2_FILE_PATH = Pattern.compile("jdbc:h2:file:(.+)");
    private static final Pattern H2_TCP_PATH = Pattern.compile("jdbc:h2:tcp://[^/]+/(.+)");

    private static volatile Server server;
    private static volatile int boundPort;

    /**
     * Starts the H2 TCP server if the host DB is H2 and no server is running. Rewrites the host's
     * {@code db.url} from {@code file:} to {@code tcp://} on the dynamic port. Returns the bound
     * port, or 0 if no server was started (non-H2 / unconfigured / start failed).
     */
    public static int startIfNeeded(DataSourceConfigService dataSources) {
        DataSourceConfig cfg = dataSources.load();
        if (cfg == null || cfg.type() != DbType.H2) {
            return 0;
        }
        if (server != null && server.isRunning(false)) {
            return boundPort;
        }
        try {
            // OS-assigned loopback port: bind a ServerSocket to 0, read the port, close, hand to H2.
            int port;
            try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                port = probe.getLocalPort();
            }
            server = Server.createTcpServer(
                    "-tcp", "-tcpHost", "127.0.0.1", "-tcpPort", String.valueOf(port),
                    "-tcpAllowOthers", "false", "-ifNotExists").start();
            boundPort = server.getPort();
            log.info("Started H2 TCP server on 127.0.0.1:{} (requested {})", boundPort, port);

            String newUrl = rewriteUrlToTcp(cfg.url(), boundPort);
            if (!newUrl.equals(cfg.url())) {
                DataSourceConfig migrated = new DataSourceConfig(cfg.type(), newUrl, cfg.driver(),
                        cfg.dialect(), cfg.username(), cfg.password(), cfg.filePath(),
                        cfg.adminUsername(), cfg.adminPassword());
                dataSources.save(migrated);
            }
            recordPort(boundPort);
            return boundPort;
        } catch (Exception e) {
            // Non-fatal: fall through to the original URL. probeAndDecide then either succeeds in
            // single-process file mode or fails and the setup wizard reappears.
            log.error("Failed to start H2 TCP server; continuing without it: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** The port the H2 TCP server bound in this JVM, or 0 if none. */
    public static int port() {
        return boundPort;
    }

    /** Spring-owned shutdown: stops the server on context close. */
    @PreDestroy
    public void stop() {
        stopInternal();
    }

    private static void stopInternal() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.debug("H2 TCP server stop failed: {}", e.getMessage());
            }
            server = null;
            boundPort = 0;
        }
    }

    /** Test-only: stop + reset static state between tests. */
    static void stopForTest() {
        stopInternal();
    }

    private static String rewriteUrlToTcp(String url, int port) {
        Matcher file = H2_FILE_PATH.matcher(url);
        if (file.matches()) {
            return "jdbc:h2:tcp://127.0.0.1:" + port + "/" + file.group(1);
        }
        Matcher tcp = H2_TCP_PATH.matcher(url);
        if (tcp.matches()) {
            return "jdbc:h2:tcp://127.0.0.1:" + port + "/" + tcp.group(1);
        }
        return url;
    }

    private static void recordPort(int port) {
        try {
            Path file = RuntimePaths.configDirectory(RuntimePaths.root()).resolve("h2-server.properties");
            Files.createDirectories(file.getParent());
            SensitiveFilePermissions.protectDirectory(file.getParent());
            Properties props = new Properties();
            props.setProperty("h2.tcp.port", String.valueOf(port));
            props.setProperty("h2.tcp.host", "127.0.0.1");
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "FengYu H2 in-process TCP server (non-secret, diagnostics)");
            }
        } catch (IOException e) {
            log.debug("Could not record H2 server port: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=H2TcpServerConfigTest`
Expected: PASS — all 5 tests green; the host connects via `tcp://`.

- [ ] **Step 5: Wire startup into `HeadlessLauncher.main` BEFORE `probeAndDecide`**

In `FengYu/src/main/java/fan/summer/fengyu/HeadlessLauncher.java`, add the import near the other `fan.summer.fengyu.config` imports:

```java
import fan.summer.fengyu.config.H2TcpServerConfig;
```

Then edit `main` so the H2 TCP server starts before the probe, and `probeAndDecide` reuses the same service (so the URL rewrite inside `startIfNeeded` is visible to the subsequent probe). Replace the lines that currently read:

```java
        boolean configured = probeAndDecide(new DataSourceConfigService());
        startWithFallback(port, configured);
```

with:

```java
        DataSourceConfigService configService = new DataSourceConfigService();
        // H2 TCP server must start BEFORE probeAndDecide: the probe opens a JDBC connection, and
        // in H2 server mode that connection is tcp:// and needs the server already listening.
        // No-op for non-H2 / SETUP-mode hosts.
        H2TcpServerConfig.startIfNeeded(configService);
        boolean configured = probeAndDecide(configService);
        startWithFallback(port, configured);
```

- [ ] **Step 6: Run the launcher + config suites to confirm no regression**

Run: `./mvnw -q -pl FengYu test -Dtest='H2TcpServerConfigTest,HeadlessLauncher*Test'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/config/H2TcpServerConfig.java \
        FengYu/src/main/java/fan/summer/fengyu/HeadlessLauncher.java \
        FengYu/src/test/java/fan/summer/fengyu/config/H2TcpServerConfigTest.java
git commit -m "✨ feat(config): start in-process H2 TCP server before DB probe for RBAC isolation"
```

---

### Task 6: Rework `PluginRuntimeEnvironmentService.environmentFor` to inject provisioned creds

The single injection point changes: for H2/MySQL/PG `database` plugins, the worker receives the per-plugin credentials from `PluginDbProvisioningStore` (NOT the host's global creds). If not yet provisioned, NO db env is injected. SQLite is unchanged (host-allocated file).

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentService.java` (imports + constructors + `environmentFor`)
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentServiceTest.java` (rewrite the remote test + add two tests)

**Interfaces:**
- Consumes: `PluginDbProvisioningStore` (Task 2); `DbDialectStatements.supportsRbac(DbType)` (Task 3).
- Produces: unchanged `environmentFor` semantics for non-database and SQLite plugins; new provisioned-or-nothing behavior for H2/MySQL/PG database plugins.

- [ ] **Step 1: Update the existing test (rewrite the remote test + add provisioned + not-provisioned tests)**

Open `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentServiceTest.java`. Add to the imports:

```java
import fan.summer.fengyu.setup.PluginDbProvisioningStore;
```

Replace the existing `remoteDatabaseWorkerReusesHostUrlUnchanged` method with these three methods:

```java
    @Test
    void provisionedRemoteDatabaseWorkerReceivesIsolatedCredentials() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.POSTGRESQL, "jdbc:postgresql://db:5432/fengyu",
                    "org.postgresql.Driver", "org.hibernate.dialect.PostgreSQLDialect",
                    "fengyu", "secret", null, "fengyu_admin", "admin-secret");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.POSTGRESQL, "fengyu_fan_summer_email",
            "fengyu_plugin_email", "plugin-pw",
            "jdbc:postgresql://db:5432/fengyu?currentSchema=fengyu_fan_summer_email",
            "org.postgresql.Driver", "2026-08-08T00:00:00Z"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));

        assertEquals("jdbc:postgresql://db:5432/fengyu?currentSchema=fengyu_fan_summer_email",
            env.get(PluginWorkerProtocol.DB_URL_ENV),
            "provisioned worker must receive its OWN url, not the host's");
        assertEquals("fengyu_plugin_email", env.get(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertEquals("plugin-pw", env.get(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertFalse(env.get(PluginWorkerProtocol.DB_PASSWORD_ENV).contains("secret"),
            "host's global DB password must NEVER appear in the worker env");
    }

    @Test
    void notProvisionedRemoteDatabaseWorkerReceivesNoDatabaseEnv() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.MYSQL, "jdbc:mysql://db:3306/fengyu",
                    "com.mysql.cj.jdbc.Driver", "org.hibernate.dialect.MySQLDialect",
                    "fengyu", "secret", null, "fengyu_admin", "admin-secret");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_URL_ENV));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertFalse(env.containsKey(PluginWorkerProtocol.DB_PASSWORD_ENV));
        assertEquals("INFO", env.get(PluginWorkerProtocol.LOG_LEVEL_ENV));
        assertTrue(env.containsKey(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV));
    }

    @Test
    void provisionedH2WorkerReceivesTcpUrlAndItsOwnCredentials() {
        DataSourceConfigService dataSources = new DataSourceConfigService(temp.resolve("host").toString()) {
            @Override public DataSourceConfig load() {
                return new DataSourceConfig(DbType.H2, "jdbc:h2:tcp://127.0.0.1:12345/fengyu",
                    "org.h2.Driver", "org.hibernate.dialect.H2Dialect", "sa", "", null, "sa", "");
            }
        };
        PluginDbProvisioningStore store = new PluginDbProvisioningStore(temp.resolve("host"));
        store.put(new PluginDbProvisioningStore.ProvisionedPluginDb(
            "fan.summer.email", DbType.H2, "fengyu_fan_summer_email", "fengyu_plugin_email",
            "plugin-pw", "jdbc:h2:tcp://127.0.0.1:12345/fengyu;SCHEMA=fengyu_fan_summer_email",
            "org.h2.Driver", "2026-08-08T00:00:00Z"));
        PluginRuntimeEnvironmentService service =
            new PluginRuntimeEnvironmentService(dataSources, temp.resolve("plugin-data").toString(), store);

        Map<String, String> env = service.environmentFor(manifest("fan.summer.email", List.of("database")));
        assertEquals("h2", env.get(PluginWorkerProtocol.DB_TYPE_ENV));
        assertEquals("jdbc:h2:tcp://127.0.0.1:12345/fengyu;SCHEMA=fengyu_fan_summer_email",
            env.get(PluginWorkerProtocol.DB_URL_ENV));
        assertEquals("fengyu_plugin_email", env.get(PluginWorkerProtocol.DB_USERNAME_ENV));
        assertEquals("plugin-pw", env.get(PluginWorkerProtocol.DB_PASSWORD_ENV));
    }
```

- [ ] **Step 2: Run the test to verify the new ones fail**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginRuntimeEnvironmentServiceTest`
Expected: COMPILE FAILURE — no three-arg constructor accepting `PluginDbProvisioningStore`.

- [ ] **Step 3: Update `PluginRuntimeEnvironmentService` to inject the store and branch on provisioning**

Edit `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentService.java`. Add imports:

```java
import fan.summer.fengyu.setup.DbDialectStatements;
import fan.summer.fengyu.setup.PluginDbProvisioningStore;
```

Add a `provisioningStore` field and replace the constructors + `environmentFor`. Note: **exactly one** `@Autowired` constructor (the store-accepting one) — Spring rejects multiple `@Autowired` constructors as ambiguous.

```java
@Service
public class PluginRuntimeEnvironmentService {
    private final DataSourceConfigService dataSources;
    private final Path dataRoot;
    private final java.util.function.Supplier<String> logLevel;
    private final PluginDbProvisioningStore provisioningStore;

    /** Test-only constructor used by older tests; store defaults to null. */
    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            @Value("${fengyu.plugins.data-directory:}") String dataRoot) {
        this(dataSources, dataRoot, () -> LoggingLevelService.DEFAULT_LEVEL, null);
    }

    /** Spring production constructor — injects the mandatory provisioning store. */
    @Autowired
    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            @Value("${fengyu.plugins.data-directory:}") String dataRoot,
            LoggingLevelService logging, PluginDbProvisioningStore provisioningStore) {
        this(dataSources, dataRoot, logging::currentLevel, provisioningStore);
    }

    /** Test constructor that supplies an explicit store. */
    public PluginRuntimeEnvironmentService(DataSourceConfigService dataSources, String dataRoot,
            PluginDbProvisioningStore store) {
        this(dataSources, dataRoot, () -> LoggingLevelService.DEFAULT_LEVEL, store);
    }

    private PluginRuntimeEnvironmentService(DataSourceConfigService dataSources,
            String dataRoot, java.util.function.Supplier<String> logLevel,
            PluginDbProvisioningStore provisioningStore) {
        this.dataSources = dataSources;
        this.dataRoot = dataRoot == null || dataRoot.isBlank()
                ? RuntimePaths.pluginDataDirectory(RuntimePaths.root())
                : Path.of(dataRoot).toAbsolutePath().normalize();
        this.logLevel = logLevel;
        this.provisioningStore = provisioningStore;
    }

    public Map<String, String> environmentFor(PluginManifest manifest) {
        Map<String, String> environment = new HashMap<>();
        environment.put(PluginWorkerProtocol.LOG_LEVEL_ENV, logLevel.get());
        Path pluginData = dataRoot.resolve(manifest.id()).normalize();
        if (!pluginData.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Invalid plugin id for data directory");
        }
        try {
            Files.createDirectories(pluginData);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create plugin data directory", e);
        }
        environment.put(PluginWorkerProtocol.PLUGIN_DATA_DIR_ENV, pluginData.toString());

        if (manifest.permissions() == null || !manifest.permissions().contains("database")) {
            return Map.copyOf(environment);
        }
        DataSourceConfig config = dataSources.load();
        if (config == null) {
            throw new IllegalStateException("Host database is not configured");
        }

        // SQLite is the documented RBAC exception: no CREATE USER/GRANT, so it keeps the
        // host-allocated independent file under the plugin data dir.
        if (!DbDialectStatements.supportsRbac(config.type())) {
            String workerDbUrl = resolveWorkerDbUrl(config, pluginData);
            environment.putAll(Map.of(
                PluginWorkerProtocol.DB_TYPE_ENV, config.type().name().toLowerCase(Locale.ROOT),
                PluginWorkerProtocol.DB_DRIVER_ENV, config.driver(),
                PluginWorkerProtocol.DB_URL_ENV, workerDbUrl,
                PluginWorkerProtocol.DB_USERNAME_ENV, nullToEmpty(config.username()),
                PluginWorkerProtocol.DB_PASSWORD_ENV, nullToEmpty(config.password())));
            return Map.copyOf(environment);
        }

        // H2 / MySQL / PostgreSQL: inject per-plugin provisioned credentials only. If the user has
        // not authorized the plugin yet (no stored record), inject NO db env at all — the UI guides
        // authorization. The host's global DB credentials NEVER reach a worker.
        if (provisioningStore != null) {
            PluginDbProvisioningStore.ProvisionedPluginDb creds = provisioningStore.get(manifest.id());
            if (creds != null) {
                environment.putAll(Map.of(
                    PluginWorkerProtocol.DB_TYPE_ENV, creds.dbType().name().toLowerCase(Locale.ROOT),
                    PluginWorkerProtocol.DB_DRIVER_ENV, creds.driver(),
                    PluginWorkerProtocol.DB_URL_ENV, creds.url(),
                    PluginWorkerProtocol.DB_USERNAME_ENV, creds.userName(),
                    PluginWorkerProtocol.DB_PASSWORD_ENV, creds.password()));
            }
        }
        return Map.copyOf(environment);
    }
```

Leave the existing `resolveWorkerDbUrl`, `stripExtension`, `nullToEmpty` helpers unchanged.

- [ ] **Step 4: Run the full test class to verify all tests pass**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginRuntimeEnvironmentServiceTest`
Expected: PASS — the three new tests green AND all existing file/SQLite/traversal tests still green (they use the two-arg or default-store constructor).

- [ ] **Step 5: Run the broader plugin runtime suite to catch any downstream breakage**

Run: `./mvnw -q -pl FengYu test -Dtest='PluginRuntime*,PluginProcess*Test'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentService.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginRuntimeEnvironmentServiceTest.java
git commit -m "✨ feat(plugin-runtime): inject per-plugin provisioned DB creds instead of host's global credentials"
```

---

### Task 7: `PluginDbController` + deprovisioning on uninstall

Exposes `POST /api/plugin-db/provision/{id}` (user-authorized provisioning) and `POST /api/plugin-db/status/{id}` (UI status), and wires `PluginDbProvisioner.deprovision` into `PluginPackageService.uninstall` so a plugin's DB user/schema are cleaned up when removed. Deprovisioning is non-blocking so uninstall always succeeds.

**Files:**
- Create: `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginDbController.java`
- Create: `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginDbControllerTest.java`
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginPackageService.java` (field + Spring constructor + `uninstall`)
- Modify: `FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java` (add deprovision-on-uninstall test)

**Interfaces:**
- Consumes: `PluginDbProvisioner.provision/isProvisioned/deprovision` (Task 4); `PluginPackageService.find(String)`.
- Produces:
  - `POST /api/plugin-db/provision/{id}` → `ProvisionResponse(boolean provisioned, String status, String pluginId)`
  - `POST /api/plugin-db/status/{id}` → `ProvisionResponse`
  - `PluginPackageService.uninstall` now also deprovisions (transparent to callers).

> **Note for the implementer:** Before writing the controller test, read `PluginDbControllerTest`'s `manifest(...)` helper against the *actual* current `PluginManifest` record shape in `fan/summer/fengyu/plugin/market/PluginManifest.java` — the constructor arity and nested-record names (e.g. `Ui`, `Backend`) must match exactly. If the manifest has changed since this plan was written, adjust the helper's `new PluginManifest(...)` call to the current signature rather than copying the plan's verbatim.

- [ ] **Step 1: Write the failing controller test**

Create `FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginDbControllerTest.java`:

```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.setup.DbProvisioningException;
import fan.summer.fengyu.setup.DbType;
import fan.summer.fengyu.setup.PluginDbProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PluginDbControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PluginDbProvisioner provisioner;
    @MockBean PluginPackageService packages;

    @Test
    void provisionCallsProvisionerAndReturnsOk() throws Exception {
        when(packages.find(eq("fan.summer.email"))).thenReturn(Optional.of(
            manifest("fan.summer.email", List.of("database"))));
        when(provisioner.isProvisioned("fan.summer.email")).thenReturn(false);
        when(provisioner.provision("fan.summer.email")).thenReturn(
            new PluginDbProvisioner.ProvisionedCredentials(
                DbType.POSTGRESQL, "org.postgresql.Driver",
                "jdbc:postgresql://db/fengyu?currentSchema=fengyu_email",
                "fengyu_plugin_email", "pw"));

        mvc.perform(post("/api/plugin-db/provision/fan.summer.email"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"provisioned\":true,\"status\":\"provisioned\",\"pluginId\":\"fan.summer.email\"}"));

        verify(provisioner).provision("fan.summer.email");
    }

    @Test
    void provisionReturns404WhenPluginNotInstalled() throws Exception {
        when(packages.find("no.such.plugin")).thenReturn(Optional.empty());

        mvc.perform(post("/api/plugin-db/provision/no.such.plugin"))
            .andExpect(status().isNotFound());
    }

    @Test
    void provisionReturns409WhenPluginLacksDatabasePermission() throws Exception {
        when(packages.find("fan.summer.markdown")).thenReturn(Optional.of(
            manifest("fan.summer.markdown", List.of())));

        mvc.perform(post("/api/plugin-db/provision/fan.summer.markdown"))
            .andExpect(status().isConflict());
    }

    @Test
    void provisionReturns500WithMessageWhenAdminCredentialsMissing() throws Exception {
        when(packages.find("fan.summer.email")).thenReturn(Optional.of(
            manifest("fan.summer.email", List.of("database"))));
        when(provisioner.provision("fan.summer.email")).thenThrow(
            new DbProvisioningException("Admin credentials are required"));

        mvc.perform(post("/api/plugin-db/provision/fan.summer.email"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Admin credentials")));
    }

    // Adjust this helper to the CURRENT PluginManifest constructor signature before running.
    private static PluginManifest manifest(String id, List<String> permissions) {
        return new PluginManifest(1, id, "Test", "Test", "1.0.0", "FengYu", "email", "net",
            new PluginManifest.Ui("ui/index.html"),
            new PluginManifest.Backend("java -jar backend/worker.jar", "json-rpc-2.0"),
            permissions, null, true, List.of());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbControllerTest`
Expected: COMPILE FAILURE — `PluginDbController` not found.

- [ ] **Step 3: Implement `PluginDbController`**

Create `FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginDbController.java`:

```java
package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.plugin.market.PluginManifest;
import fan.summer.fengyu.plugin.market.PluginPackageService;
import fan.summer.fengyu.setup.DbProvisioningException;
import fan.summer.fengyu.setup.PluginDbProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User-authorized plugin DB provisioning endpoint. Mirrors the existing {@code network.email}
 * confirm pattern: the frontend shows a confirm dialog, then POSTs here to actually create the
 * per-plugin DB user/schema via {@link PluginDbProvisioner}. Provisioning is NEVER implicit on
 * install or worker spawn — only on this explicit, user-initiated call.
 */
@RestController
public class PluginDbController {
    private static final Logger log = LoggerFactory.getLogger(PluginDbController.class);

    private final PluginPackageService packages;
    private final PluginDbProvisioner provisioner;

    public PluginDbController(PluginPackageService packages, PluginDbProvisioner provisioner) {
        this.packages = packages;
        this.provisioner = provisioner;
    }

    @PostMapping("/api/plugin-db/provision/{id}")
    public ResponseEntity<ProvisionResponse> provision(@PathVariable String id) {
        PluginManifest manifest = packages.find(id).orElse(null);
        if (manifest == null) {
            return ResponseEntity.notFound().build();
        }
        List<String> perms = manifest.permissions() == null ? List.of() : manifest.permissions();
        if (!perms.contains("database")) {
            return ResponseEntity.status(409).body(
                new ProvisionResponse(false, "plugin does not declare the 'database' permission", id));
        }
        try {
            provisioner.provision(id);
            log.info("User authorized DB provisioning for plugin {}", id);
            return ResponseEntity.ok(new ProvisionResponse(true, "provisioned", id));
        } catch (DbProvisioningException e) {
            log.warn("DB provisioning failed for {}: {}", id, e.getMessage());
            return ResponseEntity.status(500).body(new ProvisionResponse(false, e.getMessage(), id));
        }
    }

    @PostMapping("/api/plugin-db/status/{id}")
    public ProvisionResponse status(@PathVariable String id) {
        boolean provisioned = provisioner.isProvisioned(id);
        return new ProvisionResponse(provisioned, provisioned ? "provisioned" : "not-provisioned", id);
    }

    /** Response body for provision/status. Never includes the credentials themselves. */
    public record ProvisionResponse(boolean provisioned, String status, String pluginId) {}
}
```

- [ ] **Step 4: Run the controller test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginDbControllerTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 5: Write the failing uninstall-deprovisions test**

Open `FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java`. Add the imports:

```java
import fan.summer.fengyu.setup.PluginDbProvisioner;
import fan.summer.fengyu.setup.PluginDbProvisioningStore;
```

Add this test method (it uses its own `@TempDir` so it does not disturb existing shared fixtures). **Adjust the `manifest.json` JSON below to match the current manifest schema** if the required fields have changed:

```java
    @Test
    void uninstallInvokesDeprovisionWhenAProvisionerIsAttached(
            org.junit.jupiter.api.io.TempDir Path pluginsRoot,
            org.junit.jupiter.api.io.TempDir Path hostConfig) throws Exception {
        Path pluginDir = pluginsRoot.resolve("fan.summer.email");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"),
            "{\"schemaVersion\":1,\"id\":\"fan.summer.email\",\"name\":\"Email\","
            + "\"description\":\"d\",\"version\":\"1.0.0\",\"author\":\"a\",\"icon\":\"email\","
            + "\"category\":\"net\",\"ui\":{\"entry\":\"ui/index.html\"},"
            + "\"backend\":{\"command\":\"java\",\"protocol\":\"json-rpc-2.0\"},"
            + "\"permissions\":[\"database\"]}");

        java.util.List<String> deprovisioned = new java.util.ArrayList<>();
        PluginDbProvisioner provisioner = new PluginDbProvisioner(
            new fan.summer.fengyu.setup.DataSourceConfigService(hostConfig.toString()),
            new PluginDbProvisioningStore(hostConfig)) {
            @Override public void deprovision(String pluginId) { deprovisioned.add(pluginId); }
        };
        PluginPackageService service = new PluginPackageService(pluginsRoot.toString());
        service.attachProvisionerForTest(provisioner);

        service.uninstall("fan.summer.email");

        assertEquals(java.util.List.of("fan.summer.email"), deprovisioned,
            "uninstall must deprovision the plugin's DB credentials");
        assertFalse(Files.exists(pluginDir), "plugin directory must be deleted too");
    }
```

- [ ] **Step 6: Run to verify the new test fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginPackageServiceTest#uninstallInvokesDeprovisionWhenAProvisionerIsAttached`
Expected: COMPILE FAILURE — no `attachProvisionerForTest` method.

- [ ] **Step 7: Wire the provisioner into `PluginPackageService`**

Edit `FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginPackageService.java`. Add the import:

```java
import fan.summer.fengyu.setup.PluginDbProvisioner;
```

Add a nullable field immediately after the existing final fields (find the `private final HttpClient http;` line):

```java
    private PluginDbProvisioner dbProvisioner;  // nullable; null when no DB isolation is active
```

Add a Spring-injection constructor (immediately after the existing constructor) and a test attach helper:

```java
    @org.springframework.beans.factory.annotation.Autowired
    public PluginPackageService(
            @org.springframework.beans.factory.annotation.Value("${fengyu.plugins.directory:}") String directory,
            PluginDbProvisioner provisioner) {
        this(directory);
        this.dbProvisioner = provisioner;
    }

    /** Test-only: attach a provisioner so uninstall can be asserted to deprovision. */
    void attachProvisionerForTest(PluginDbProvisioner provisioner) {
        this.dbProvisioner = provisioner;
    }
```

Change `uninstall` to deprovision first (the method currently is just the `pluginDir` + `deleteTree`):

```java
    public void uninstall(String id) throws IOException {
        Path dir = pluginDir(id);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Plugin is not installed: " + id);
        // Deprovision the plugin's DB credentials/user/schema FIRST. Non-blocking: deprovision
        // catches+logs its own DDL failures, so even a broken DB never blocks the file cleanup.
        if (dbProvisioner != null) {
            try {
                dbProvisioner.deprovision(id);
            } catch (RuntimeException e) {
                log.warn("DB deprovision for {} failed; continuing with file removal: {}", id, e.getMessage());
            }
        }
        deleteTree(dir);
    }
```

- [ ] **Step 8: Run the package-service test to verify the new test passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginPackageServiceTest`
Expected: PASS — the new deprovision test green AND every existing test still green (they use the single-arg constructor).

- [ ] **Step 9: Run the whole module test suite to confirm no regression**

Run: `./mvnw -q -pl FengYu test`
Expected: PASS — full module green.

- [ ] **Step 10: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/web/controller/PluginDbController.java \
        FengYu/src/test/java/fan/summer/fengyu/web/controller/PluginDbControllerTest.java \
        FengYu/src/main/java/fan/summer/fengyu/plugin/market/PluginPackageService.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/market/PluginPackageServiceTest.java
git commit -m "✨ feat(plugin-db): add user-authorized provisioning endpoint + deprovision-on-uninstall"
```

---

### Task 8: Frontend — DB authorization button + confirm dialog in Settings.vue

Mirrors the existing unsandboxed-plugins confirm pattern. Adds a per-plugin "Authorize database" button that opens a confirm dialog, then calls `POST /api/plugin-db/provision/{id}`. Adds the `client.ts` method and i18n strings (en + zh).

**Files:**
- Modify: `frontend/src/api/client.ts` (add `provisionPluginDb` + `pluginDbStatus` methods)
- Modify: `frontend/src/api/types.ts` (add `PluginDbProvisionResult`)
- Modify: `frontend/src/views/Settings.vue` (imports + refs + functions + template section + dialog)
- Modify: `frontend/src/i18n/en.json` (add `pluginDb.*` strings under `settings`)
- Modify: `frontend/src/i18n/zh.json` (mirror)

**Interfaces:**
- Consumes: `POST /api/plugin-db/provision/{id}` and `POST /api/plugin-db/status/{id}` (Task 7); existing `api.plugins()` descriptor list.
- Produces: `api.provisionPluginDb(id)` and `api.pluginDbStatus(id)`; a confirm dialog driven by `showDbProvisionConfirm`.

> **Note for the implementer:** Before editing, read the current `Settings.vue` and `client.ts` to confirm the existing structure (refs, `api` import, i18n key paths) — match the surrounding style exactly. The `api.plugins()` descriptor list shape (whether it has `permissions`/`id`/`name`) must be verified against `types.ts`; if the plugin descriptor uses different field names, adjust the `.filter` and `.map` accordingly.

- [ ] **Step 1: Add the `PluginDbProvisionResult` type**

In `frontend/src/api/types.ts`, add alongside the other exported interfaces:

```ts
export interface PluginDbProvisionResult {
  provisioned: boolean
  status: string
  pluginId: string
}
```

- [ ] **Step 2: Add the API client methods**

In `frontend/src/api/client.ts`, add these two methods near the other plugin methods (find `setPluginEnabled` or `installPlugin` and add after):

```ts
  async provisionPluginDb(id: string): Promise<PluginDbProvisionResult> {
    const { data } = await http.post<PluginDbProvisionResult>(`/api/plugin-db/provision/${encodeURIComponent(id)}`)
    return data
  },

  async pluginDbStatus(id: string): Promise<PluginDbProvisionResult> {
    const { data } = await http.post<PluginDbProvisionResult>(`/api/plugin-db/status/${encodeURIComponent(id)}`)
    return data
  },
```

Ensure `PluginDbProvisionResult` is imported from `./types` alongside the existing type imports at the top of `client.ts`.

- [ ] **Step 3: Add the i18n strings (en)**

In `frontend/src/i18n/en.json`, inside the `settings` object (after the `unsandboxedPluginsConfirm` line), add:

```json
                "pluginDbSection": "Database isolation",
                "pluginDbSectionHint": "Plugins that declare the 'database' permission get their own isolated DB user and schema. Authorization is required before each plugin can access its database.",
                "pluginDbAuthorize": "Authorize database",
                "pluginDbAuthorized": "Authorized",
                "pluginDbNoPlugins": "No installed plugin requests database access.",
                "pluginDbConfirmTitle": "Authorize database access",
                "pluginDbConfirm": "This creates an isolated database user and schema for this plugin. The plugin will only be able to access its own schema, enforced by the database engine. Continue?",
                "pluginDbProvisioning": "Authorizing…",
                "pluginDbError": "Authorization failed: {message}",
```

- [ ] **Step 4: Add the i18n strings (zh) — mirror**

In `frontend/src/i18n/zh.json`, inside the corresponding `settings` object, add:

```json
                "pluginDbSection": "数据库隔离",
                "pluginDbSectionHint": "声明 'database' 权限的插件将获得独立的 DB 用户与 schema。每个插件在访问其数据库前需要单独授权。",
                "pluginDbAuthorize": "授权数据库",
                "pluginDbAuthorized": "已授权",
                "pluginDbNoPlugins": "没有已安装的插件请求数据库访问。",
                "pluginDbConfirmTitle": "授权数据库访问",
                "pluginDbConfirm": "这将为该插件创建独立的数据库用户与 schema。插件只能访问自己的 schema，由数据库引擎强制隔离。是否继续？",
                "pluginDbProvisioning": "授权中…",
                "pluginDbError": "授权失败：{message}",
```

- [ ] **Step 5: Add the script-side state and functions to Settings.vue**

In `frontend/src/views/Settings.vue`, extend the existing type import to add `PluginDbProvisionResult`. After the `showUnsandboxedConfirm` ref, add:

```ts
const showDbProvisionConfirm = ref(false)
const dbProvisionTargetId = ref<string | null>(null)
const dbPlugins = ref<Array<PluginDbProvisionResult & { name: string }>>([])
const dbProvisioning = ref<string | null>(null)
const dbError = ref<string | null>(null)
```

Extend `onMounted` to load database-permission plugins, and add the request/confirm functions:

```ts
async function loadDbPlugins() {
  try {
    const all = await api.plugins()
    const dbOnes = all.filter((p) => p.permissions?.includes('database'))
    const results = await Promise.all(
      dbOnes.map(async (p) => {
        const status = await api.pluginDbStatus(p.id).catch(() => null)
        return {
          provisioned: status?.provisioned ?? false,
          status: status?.status ?? 'unknown',
          pluginId: p.id,
          name: p.name,
        }
      }),
    )
    dbPlugins.value = results
  } catch {
    dbPlugins.value = []
  }
}

function requestDbProvision(pluginId: string) {
  dbProvisionTargetId.value = pluginId
  dbError.value = null
  showDbProvisionConfirm.value = true
}

async function confirmDbProvision() {
  const id = dbProvisionTargetId.value
  showDbProvisionConfirm.value = false
  if (!id) return
  dbProvisioning.value = id
  dbError.value = null
  try {
    const result = await api.provisionPluginDb(id)
    if (!result.provisioned) {
      dbError.value = result.status
    } else {
      void loadDbPlugins()
    }
  } catch (e: unknown) {
    dbError.value = e instanceof Error ? e.message : String(e)
  } finally {
    dbProvisioning.value = null
  }
}
```

Add `void loadDbPlugins()` inside the existing `onMounted(...)` body.

- [ ] **Step 6: Add the template section + dialog**

In the `<template>`, add a new "Database isolation" card after the runtime-security card (match the existing `cx-section-title` / `cx-card` / `cx-setting-row` classes already used in the file):

```html
      <div class="cx-section-title">{{ $t('settings.pluginDbSection') }}</div>
      <div class="cx-card">
        <div class="cx-muted" style="font-size: 12px; margin-bottom: 12px">
          {{ $t('settings.pluginDbSectionHint') }}
        </div>
        <div v-if="dbPlugins.length === 0" class="cx-muted" style="font-size: 13px">
          {{ $t('settings.pluginDbNoPlugins') }}
        </div>
        <div
          v-for="p in dbPlugins"
          :key="p.pluginId"
          class="cx-setting-row"
        >
          <div class="cx-setting-row__label">
            <i class="mdi mdi-database-lock-outline" />
            <span>{{ p.name }} <span class="cx-muted" style="font-size: 12px">{{ p.pluginId }}</span></span>
          </div>
          <span v-if="p.provisioned" class="cx-chip cx-chip--success">
            {{ $t('settings.pluginDbAuthorized') }}
          </span>
          <button
            v-else
            class="cx-btn cx-btn--primary"
            :disabled="dbProvisioning === p.pluginId"
            @click="requestDbProvision(p.pluginId)"
          >
            {{ dbProvisioning === p.pluginId ? $t('settings.pluginDbProvisioning') : $t('settings.pluginDbAuthorize') }}
          </button>
        </div>
        <div
          v-if="dbError"
          class="cx-muted"
          style="color: var(--md-sys-color-error); font-size: 12px; margin-top: 8px"
        >
          {{ $t('settings.pluginDbError', { message: dbError }) }}
        </div>
      </div>
```

Add the confirm dialog next to the existing unsandboxed dialog:

```html
    <v-dialog v-model="showDbProvisionConfirm" max-width="480">
      <v-card>
        <v-card-title>{{ $t('settings.pluginDbConfirmTitle') }}</v-card-title>
        <v-card-text>{{ $t('settings.pluginDbConfirm') }}</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="showDbProvisionConfirm = false">{{ $t('common.cancel') }}</v-btn>
          <v-btn color="primary" variant="tonal" @click="confirmDbProvision()">{{ $t('common.confirm') }}</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
```

- [ ] **Step 7: Run the frontend type-check + build**

Run: `cd /Users/phoebej/Develop/Java/FengYu/frontend && npm run build`
Expected: BUILD SUCCEEDS — vue-tsc passes; the new methods/types/i18n keys resolve.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/client.ts frontend/src/api/types.ts \
        frontend/src/views/Settings.vue \
        frontend/src/i18n/en.json frontend/src/i18n/zh.json
git commit -m "✨ feat(frontend): add per-plugin DB authorization button + confirm dialog in Settings"
```

---

### Task 9: Documentation — rewrite `database.md` + isolation parts of `manifest.md`, mirror `docs/zh`

Rewrites the DB isolation docs to match the new architecture. The logging docs and the manifest.md "connection coordinates" wording (#4) are handled by Task 12 — this task touches only the DB isolation docs.

**Files:**
- Modify: `docs/en/plugins/database.md` (full rewrite)
- Modify: `docs/zh/plugins/database.md` (full mirror)
- Modify: `docs/en/plugins/manifest.md` (`database` permission row)
- Modify: `docs/zh/plugins/manifest.md` (corresponding permission row)

**Interfaces:**
- Consumes: the implemented behavior from Tasks 1–8 (isolation matrix, provisioning flow, admin creds, H2 server mode, SQLite exception).
- Produces: docs that match the running app.

- [ ] **Step 1: Rewrite `docs/en/plugins/database.md`**

Replace the entire contents of `docs/en/plugins/database.md` with the English text from **spec §4 + §5** — the isolation matrix table (H2/MySQL/PostgreSQL RBAC + SQLite file-level exception), the H2 in-process TCP server explanation, the admin credentials + user-authorized provisioning flow (Settings → "Authorize database" → POST `/api/plugin-db/provision/{id}`), deprovisioning on uninstall, and the table-prefix convention demoted to "naming hygiene on top of isolation."

Key headings to include: `# Plugin Database Standard`, `## Database-level isolation (mandatory)` (with the 4-row matrix), `### Why SQLite is an exception`, `## H2 in-process TCP server`, `## Admin credentials and provisioning` (`### Provisioning flow`, `### Deprovisioning on uninstall`), `## Table-prefix convention (naming hygiene)`, `## Secrets`, `## Checklist`.

- [ ] **Step 2: Rewrite `docs/zh/plugins/database.md` (mirror)**

Mirror Step 1 in Chinese, structurally aligned (same headings, same matrix, same flow). Headings: `# 插件数据库规范`, `## 数据库级强制隔离`, `### 为什么 SQLite 是例外`, `## H2 进程内 TCP server`, `## Admin 凭据与 provisioning` (`### Provisioning 流程`, `### 卸载时 deprovision`), `## 表前缀约定（命名整洁）`, `## 机密`, `## 检查清单`.

- [ ] **Step 3: Update the `database` permission row in manifest.md (en + zh)**

In `docs/en/plugins/manifest.md`, change the `database` permission row to describe connection coordinates + isolated user/schema (coordinate with Task 12's wording — the two tasks both touch this row, so apply only the isolation-flavored expansion here; Task 12 narrows "datasource connection" → "connection coordinates"). Final wording:

```
| `database` | The host injects database connection coordinates (`FENGYU_DB_*` — type/driver/url/username/password — plus a private data directory) into the worker environment, provisioned as an isolated DB user/schema. The worker opens its own connection. See [Plugin Database Standard](/en/plugins/database). |
```

Mirror in `docs/zh/plugins/manifest.md`:

```
| `database` | 宿主向 worker 环境注入数据库连接坐标（`FENGYU_DB_*` —— type/driver/url/username/password —— 以及一个私有数据目录），以隔离 DB 用户/schema 形式 provision；由 worker 自行建立连接。参见[插件数据库规范](/zh/plugins/database)。 |
```

- [ ] **Step 4: Verify the docs site still builds (EN + ZH)**

Run: `npm --prefix docs run build`
Expected: BUILD SUCCESS — no broken markdown links/anchors introduced.

- [ ] **Step 5: Commit**

```bash
git add docs/en/plugins/database.md docs/zh/plugins/database.md \
        docs/en/plugins/manifest.md docs/zh/plugins/manifest.md
git commit -m "📝 docs(plugins): rewrite database isolation docs for per-plugin RBAC + H2 server mode"
```

---

## Part B — Logging Persistence + Small Fixes (Tasks 10–12, decoupled from Part A)

### Task 10: Per-plugin log persistence (SiftingAppender + MDC)

Add a Logback `SiftingAppender` keyed by MDC `pluginId` so each plugin's forwarded log events also land in `<LOG_DIR>/plugin-<pluginId>.log` (10MB + daily rolling, 7-day / 50MB cap). Pure host-side: `logback.xml` + one MDC put/remove in `forwardPluginLog`. No SDK/worker changes.

**Files:**
- Modify: `FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java:16` (add `org.slf4j.MDC` import) and `:312-326` (MDC put/remove in `forwardPluginLog`)
- Modify: `FengYu/src/main/resources/logback.xml` (add `PLUGIN_FILE` SiftingAppender, wire to `plugin` logger with additivity)
- Test (new): `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginLogbackSiftingConfigTest.java`
- Test (modify): `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java`

**Interfaces:**
- Consumes: `forwardPluginLog` (existing), `safeLoggerName(String)` (existing), the host SLF4J/Logback binding.
- Produces: a new MDC key `pluginId` (= `safeLoggerName(pluginId)`) on every `plugin.<pluginId>.<source>` event; per-plugin file `${LOG_DIR}/plugin-<pluginId>.log`. `PluginLogStore` (in-memory SSE source) is untouched; `fengyu.log` double-write preserved (additivity=true).

**Design decision (MDC value).** The MDC `pluginId` value is `safeLoggerName(pluginId)` — the same sanitize-truncate already used for the logger name. This keeps the on-disk filename consistent with the logger name and prevents path-traversal/weird chars from the manifest `id` reaching the filesystem (e.g. `../../etc/evil` → `_.._.._etc_evil`).

- [ ] **Step 1: Write the failing config test (per-plugin file created by MDC)**

Create `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginLogbackSiftingConfigTest.java`:

```java
package fan.summer.fengyu.plugin.runtime;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the PRODUCTION logback.xml into a fresh LoggerContext pointed at a temp fengyu.log.dir,
 * and asserts that a log event carrying MDC["pluginId"]=myplugin lands in plugin-myplugin.log via
 * the SiftingAppender — and that an event without the MDC key routes to the defaultValue bucket.
 */
class PluginLogbackSiftingConfigTest {
    @TempDir Path temp;

    private LoggerContext context;

    @AfterEach
    void tearDown() {
        if (context != null) context.stop();
        System.clearProperty("fengyu.log.dir");
        MDC.clear();
    }

    @Test
    void perPluginLogFileCreatedByMdcKey() throws Exception {
        System.setProperty("fengyu.log.dir", temp.toString());
        context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(getClass().getResourceAsStream("/logback.xml"));
        } catch (JoranException e) {
            throw new IllegalStateException("logback.xml failed to parse", e);
        }

        MDC.put("pluginId", "myplugin");
        context.getLogger("plugin.myplugin.stderr").info("[main] hello from worker");
        MDC.remove("pluginId");

        Path pluginFile = temp.resolve("plugin-myplugin.log");
        assertTrue(Files.exists(pluginFile), "plugin-myplugin.log not created; dir=" + diagnose(temp));
        String content = Files.readString(pluginFile);
        assertTrue(content.contains("hello from worker"), "content missing; got: " + content);

        context.getLogger("plugin.orphan.stderr").info("no mdc here");
        assertTrue(Files.exists(temp.resolve("plugin-unknown.log")),
            "plugin-unknown.log (defaultValue bucket) not created; dir=" + diagnose(temp));
    }

    private static String diagnose(Path dir) {
        try {
            return Files.list(dir).map(p -> p.getFileName().toString()).toList().toString();
        } catch (Exception e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }
}
```

- [ ] **Step 2: Run the config test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginLogbackSiftingConfigTest`
Expected: FAIL — `plugin-myplugin.log not created` (the current `logback.xml` has no SiftingAppender).

- [ ] **Step 3: Add the PLUGIN_FILE SiftingAppender to logback.xml**

Modify `FengYu/src/main/resources/logback.xml`. Insert this block right after the existing `FILE` appender (after the `</appender>` of FILE, before the noisy-frameworks comment):

```xml
    <!-- ── Per-plugin rolling file: keyed by MDC "pluginId", set by PluginProcessManager.
              Each plugin's forwarded events are persisted to <LOG_DIR>/plugin-<pluginId>.log,
              rolling at 10MB and daily, kept 7 days, capped at 50MB total per plugin.
              additivity stays true so fengyu.log (the shared FILE appender at root) still gets
              every event too. PLUGIN_FILE is referenced on the "plugin" logger below. -->
    <appender name="PLUGIN_FILE" class="ch.qos.logback.classic.sift.SiftingAppender">
        <discriminator class="ch.qos.logback.classic.sift.MDCBasedDiscriminator">
            <key>pluginId</key>
            <defaultValue>unknown</defaultValue>
        </discriminator>
        <sift>
            <appender name="PLUGIN_FILE-${pluginId}" class="ch.qos.logback.core.rolling.RollingFileAppender">
                <file>${LOG_DIR}/plugin-${pluginId}.log</file>
                <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                    <fileNamePattern>${LOG_DIR}/plugin-${pluginId}.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
                    <maxFileSize>10MB</maxFileSize>
                    <maxHistory>7</maxHistory>
                    <totalSizeCap>50MB</totalSizeCap>
                </rollingPolicy>
                <encoder>
                    <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger - %msg%n</pattern>
                    <charset>UTF-8</charset>
                </encoder>
            </appender>
        </sift>
    </appender>
```

Then change the `plugin` logger declaration (currently `<logger name="plugin" level="INFO"/>`) to:

```xml
    <logger name="plugin" level="INFO" additivity="true">
        <appender-ref ref="PLUGIN_FILE"/>
    </logger>
```

- [ ] **Step 4: Run the config test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginLogbackSiftingConfigTest`
Expected: PASS — both `plugin-myplugin.log` (with the message) and `plugin-unknown.log` exist.

- [ ] **Step 5: Write the failing host-side test (forwardPluginLog sets MDC)**

Add to `FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java` (mirror the existing `redactsDatabasePasswordFromWorkerStderrLogs` test's `manager()` / `EchoWorker` setup — read that test first to reuse its fixtures). The test attaches a `ListAppender` to the `plugin.com.example.worker.stderr` logger, invokes the worker, and asserts `event.getMDCPropertyMap().get("pluginId")` equals `"com.example.worker"`:

```java
    @Test
    void forwardedPluginLogCarriesPluginIdMdc() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("plugin.com.example.worker.stderr");
        ch.qos.logback.classic.Level previousLevel = logger.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        PluginProcessManager manager = manager(List.of("database"));
        try {
            manager.invoke("com.example.worker", "stderr-secret", Map.of());
            assertFalse(appender.list.isEmpty(), "no forwarded event captured");
            ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.getLast();
            assertEquals("com.example.worker", event.getMDCPropertyMap().get("pluginId"),
                "forwarded plugin log must carry MDC pluginId for SiftingAppender routing");
        } finally {
            manager.close();
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
```

> **Note for the implementer:** Adapt the `manager(...)` / `invoke(...)` / `waitForLog(...)` calls to the *actual* helper methods in `PluginProcessManagerTest` — read the existing `redactsDatabasePasswordFromWorkerStderrLogs` test and reuse its exact fixture pattern. The assertion is the contract: the MDC key must be present.

- [ ] **Step 6: Run the host-side test to verify it fails**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginProcessManagerTest#forwardedPluginLogCarriesPluginIdMdc`
Expected: FAIL — `pluginId` is `null` in the captured event's MDC map.

- [ ] **Step 7: Implement MDC put/remove in forwardPluginLog**

Add the import to `PluginProcessManager.java`:

```java
import org.slf4j.MDC;
```

Replace the `forwardPluginLog` body with:

```java
    private static void forwardPluginLog(String pluginId, PluginLogLineParser.Parsed event,
            String message) {
        // safeLoggerName is reused as the MDC value so the on-disk filename (plugin-<that>.log)
        // matches the logger name AND keeps manifest-supplied ids out of the filesystem
        // (path-traversal / weird chars collapse to underscores). Truncation keeps it within
        // OS filename limits.
        String safePluginId = safeLoggerName(pluginId);
        String source = event.logger() == null || event.logger().isBlank()
            ? "stderr" : safeLoggerName(event.logger());
        Logger pluginLogger = LoggerFactory.getLogger("plugin." + safePluginId + "." + source);
        String rendered = event.thread() == null || event.thread().isBlank()
            ? message : "[" + event.thread() + "] " + message;
        MDC.put("pluginId", safePluginId);
        try {
            switch (event.level()) {
                case "TRACE" -> pluginLogger.trace(rendered);
                case "DEBUG" -> pluginLogger.debug(rendered);
                case "WARN" -> pluginLogger.warn(rendered);
                case "ERROR" -> pluginLogger.error(rendered);
                default -> pluginLogger.info(rendered);
            }
        } finally {
            MDC.remove("pluginId");
        }
    }
```

- [ ] **Step 8: Run the host-side test to verify it passes**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginProcessManagerTest#forwardedPluginLogCarriesPluginIdMdc`
Expected: PASS.

- [ ] **Step 9: Run the full plugin-runtime test class to confirm no regression**

Run: `./mvnw -q -pl FengYu test -Dtest=PluginProcessManagerTest,PluginLogbackSiftingConfigTest,PluginLogStoreTest`
Expected: PASS.

- [ ] **Step 10: Compile the backend module to confirm the production code builds**

Run: `./mvnw -q -pl FengYu -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add FengYu/src/main/java/fan/summer/fengyu/plugin/runtime/PluginProcessManager.java \
        FengYu/src/main/resources/logback.xml \
        FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginLogbackSiftingConfigTest.java \
        FengYu/src/test/java/fan/summer/fengyu/plugin/runtime/PluginProcessManagerTest.java
git commit -m "✨ feat(plugin-log): persist per-plugin forwarded logs via logback SiftingAppender"
```

---

### Task 11: SDK severity switch default

Add a `default` branch to `PluginLogging.severity` that throws `IllegalArgumentException` (defensive; SLF4J 2.x `Level` has exactly 5 constants and `isEnabled` short-circuits OFF, so the branch is unreachable today). Add a severity-lattice regression guard.

**Files:**
- Modify: `toolchain/sdk-java/src/main/java/fan/summer/fengyu/sdk/PluginLogging.java:47-55`
- Modify: `toolchain/sdk-java/src/test/java/fan/summer/fengyu/sdk/PluginLoggingTest.java`

**Interfaces:**
- Consumes: `org.slf4j.event.Level`; existing `PluginLogging.isEnabled(Level)`.
- Produces: `private static int severity(Level)` now throws on any non-constant value. No public API change. Toolchain version NOT bumped.

**Honest reachability note.** The SLF4J 2.x `Level` enum has exactly five constants (TRACE, DEBUG, INFO, WARN, ERROR), and `isEnabled` short-circuits `OFF` before calling `severity`. So the new `default` branch is genuinely unreachable today — it exists as defensive code against future enum growth. You cannot synthesize a new enum constant in a test, so the meaningful test is a regression guard on the five real constants.

- [ ] **Step 1: Write the regression test (full severity lattice)**

Add to `toolchain/sdk-java/src/test/java/fan/summer/fengyu/sdk/PluginLoggingTest.java` (after `rejectsUnknownLevel`):

```java
    /**
     * Regression guard for PluginLogging.severity(Level). The SLF4J 2.x Level enum has exactly
     * TRACE..ERROR, and isEnabled short-circuits OFF before reaching severity — so severity's
     * new default branch (which throws) is unreachable today. We cannot synthesize a new enum
     * constant in a test, so instead assert the observable invariant: for every (level,
     * threshold) pair, isEnabled matches level.severity >= threshold.severity. This walks all
     * five constants through severity() and catches a reordering.
     */
    @Test
    void severityMapsEachSlf4jLevel() {
        for (SeverityLattice threshold : SeverityLattice.values()) {
            PluginLogging.setLevel(threshold.name());
            for (SeverityLattice level : SeverityLattice.values()) {
                boolean expected = level.severity >= threshold.severity;
                org.slf4j.event.Level slf4j = org.slf4j.event.Level.valueOf(level.name());
                assertEquals(expected, PluginLogging.isEnabled(slf4j),
                    "level=" + level + " threshold=" + threshold);
            }
        }
    }

    private enum SeverityLattice {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);
        final int severity;
        SeverityLattice(int severity) { this.severity = severity; }
    }
```

- [ ] **Step 2: Run the test to confirm it PASSES against the current code**

Run: `./mvnw -q -f toolchain/sdk-java/pom.xml test -Dtest=PluginLoggingTest`
Expected: PASS (the current `severity` switch already maps all five constants correctly — this is a guard written first, recorded green).

- [ ] **Step 3: Add the default branch that throws**

Replace `severity(Level)` in `toolchain/sdk-java/src/main/java/fan/summer/fengyu/sdk/PluginLogging.java`:

```java
    private static int severity(Level level) {
        return switch (level) {
            case TRACE -> 0;
            case DEBUG -> 1;
            case INFO -> 2;
            case WARN -> 3;
            case ERROR -> 4;
            // Defensive: Level currently has exactly these five constants and isEnabled()
            // short-circuits OFF, so this branch is unreachable today. Throw (mirroring
            // Threshold.parse's message) rather than silently mis-grade a future enum constant.
            default -> throw new IllegalArgumentException("Unsupported plugin log level: " + level);
        };
    }
```

- [ ] **Step 4: Run the SDK tests to verify everything still passes**

Run: `./mvnw -q -f toolchain/sdk-java/pom.xml test -Dtest=PluginLoggingTest`
Expected: PASS — all four tests green; the new `default` arm is never hit by any real `Level`.

- [ ] **Step 5: Commit (independent SDK commit; toolchain version unchanged)**

```bash
git add toolchain/sdk-java/src/main/java/fan/summer/fengyu/sdk/PluginLogging.java \
        toolchain/sdk-java/src/test/java/fan/summer/fengyu/sdk/PluginLoggingTest.java
git commit -m "🐛 fix(sdk): throw on unknown Level in PluginLogging.severity default arm"
```

---

### Task 12: Doc wording fix — "connection coordinates" (#4 partial)

Manifest.md `database` permission "a datasource connection" → "connection coordinates", and worker.md logging note about the per-plugin disk file. **Coordinate with Task 9** — Task 9 expands the manifest.md row with isolation wording; this task's manifest.md change is already folded into Task 9 Step 3's final wording. So this task only does the worker.md logging note + a final verification. If Part A and Part B are executed by different sessions, apply Task 9 Step 3's wording there and this task's worker.md note here — they don't collide (different files).

**Files:**
- Modify: `docs/en/plugins/worker.md` (Logging section: add per-plugin disk-file note)
- Modify: `docs/zh/plugins/worker.md` (mirror)

**Interfaces:**
- Consumes: the runtime behavior delivered in Task 10 (`<LOG_DIR>/plugin-<id>.log`, 10MB/daily, 7-day/50MB cap).
- Produces: docs describing the per-plugin disk log file.

- [ ] **Step 1: Add the per-plugin disk-file note to worker.md (EN)**

In `docs/en/plugins/worker.md`, the Logging paragraph ends with `... defaults to INFO when no level token can be recognized.` Append a new sentence after it:

```
Forwarded events are also persisted to their own rolling file at `<LOG_DIR>/plugin-<pluginId>.log` (10 MB and daily rotation, 7-day history, 50 MB cap per plugin), so recent plugin output survives a host restart. The shared `fengyu.log` still contains every event as well.
```

- [ ] **Step 2: Mirror the logging note in worker.md (ZH)**

In `docs/zh/plugins/worker.md`, append after the corresponding Logging paragraph:

```
转发的事件还会落盘到各自的滚动文件 `<LOG_DIR>/plugin-<pluginId>.log`（按 10 MB 与每日滚动，保留 7 天，每个插件上限 50 MB），因此宿主重启后近期的插件输出仍然可查。共享的 `fengyu.log` 仍会包含全部事件。
```

- [ ] **Step 3: Verify the docs site still builds (EN + ZH)**

Run: `npm --prefix docs run build`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add docs/en/plugins/worker.md docs/zh/plugins/worker.md
git commit -m "📝 docs(plugins): note per-plugin log file in worker logging section"
```

---

## Execution notes

- **Dependency order:** Tasks 1→2→3→4→5→6→7 (Part A, sequential — each consumes the previous task's types). Task 8 depends on 7. Task 9 depends on 1–8. Tasks 10, 11, 12 are mutually independent and independent of Part A — they can be interleaved anywhere, except Task 12's worker.md note describes Task 10's behavior (sequence 10 → 12).
- **Task 11 (SDK)** is a fully isolated commit in `toolchain/sdk-java` — no host dependency. Can run first as a warm-up.
- **Per-module verification:** Each task runs the smallest check that proves it (the named test class). Do not run the whole reactor between tasks.
- **Final smoke:** After all 12 tasks, run `scripts/e2e-smoke.sh` for an end-to-end sanity check (it does not cover DB provisioning — that needs a real DB; the H2 in-process path is covered by Task 4/5 tests).
