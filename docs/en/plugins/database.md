---
title: Plugin Database Standard
description: DB-level isolation, the H2 in-process TCP server, admin credentials and user-authorized provisioning, and credential rules for isolated plugins.
lang: en
---

# Plugin Database Standard

A plugin declares `database` in its manifest to opt into database access. The host then injects **connection coordinates** (`FENGYU_DB_*` — type, driver, URL, username, password — plus a private data directory) into the isolated Worker environment, and `PluginDatabaseConfig.fromEnvironment(...)` reads them on the worker side. The coordinates are never exposed to the iframe, and the worker always opens its own connection.

The defining rule is **isolation by the database engine, not by plugin cooperation**. The host provisions each `database` plugin its own restricted DB user (or file, for SQLite) and the engine itself rejects any cross-namespace access. A plugin never receives the host's runtime credentials or the admin credentials used for provisioning.

## Database-level isolation (mandatory)

Every `database` plugin runs against an isolated namespace. The isolation mechanism depends on the host's configured database:

| Database | Isolation mechanism | Worker connection URL | Provisioning |
| --- | --- | --- | --- |
| **H2 (server mode)** | RBAC — per-plugin user + schema + `GRANT` | `jdbc:h2:tcp://127.0.0.1:<port>/...;SCHEMA=fengyu_<plugin>` | `CREATE USER` + `CREATE SCHEMA AUTHORIZATION` + `GRANT ALL ON SCHEMA` |
| **MySQL** | RBAC — per-plugin user + independent database + `GRANT` | `jdbc:mysql://host:port/fengyu_<plugin>` | `CREATE USER '...'@'127.0.0.1'` + `CREATE DATABASE` + `GRANT ALL PRIVILEGES` |
| **PostgreSQL** | RBAC — per-plugin role + schema + `GRANT` | `jdbc:postgresql://host:port/<host-db>?currentSchema=fengyu_<plugin>` | `CREATE ROLE LOGIN` + `CREATE SCHEMA AUTHORIZATION` + `GRANT USAGE, CREATE` |
| **SQLite** | **File-level (documented exception)** | `jdbc:sqlite:<host-allocated-path>` | None — host allocates an independent file under the plugin's data directory |

**Key invariants:**

- A worker never receives the host's runtime credentials or the admin credentials. It only ever holds its own restricted credentials.
- For H2, MySQL, and PostgreSQL the database engine itself enforces the boundary — a plugin literally cannot read or write another plugin's (or the host's) tables, regardless of what table names it uses.
- The host's own connection (HikariCP) and Hibernate dialect are URL-scheme agnostic; `H2Dialect` is unchanged whether the host connects over `file:` or `tcp://`.

### Why SQLite is an exception

SQLite has no TCP server and no `CREATE USER` / `GRANT` model — the engine offers nothing for the host to restrict. Rather than ship a home-grown DB server (which would violate YAGNI and reintroduce the very sharing that isolation is meant to prevent), SQLite is treated as a **documented technical exception**: isolation is file-level. The host allocates an independent `.db` file under the plugin's private data directory and hands the worker that path. The plugin cannot choose its own database path; it must use the file the host assigns.

## H2 in-process TCP server

The host's H2 database can run in two modes. An embedded `jdbc:h2:file:...` connection holds an exclusive OS file lock, so no second process (especially a sandboxed worker) can attach to the same file — H2's `AUTO_SERVER=TRUE` is defeated by the OS sandbox and is intentionally not used. To support per-plugin RBAC the host therefore promotes H2 to an **in-process TCP server**:

- The host starts an `org.h2.tools.Server` TCP instance bound to **`127.0.0.1`** on an OS-assigned dynamic port. Binding to loopback uses the **`h2.bindAddress` system property** — H2 2.4.240 has no `-tcpHost` flag (passing it throws `JdbcSQLFeatureNotSupportedException`), so loopback is forced via `System.setProperty("h2.bindAddress", "127.0.0.1")` before the server is created. `-tcpAllowOthers` is intentionally omitted.
- On first boot the host's own `db.url` is migrated from `jdbc:h2:file:...` to `jdbc:h2:tcp://127.0.0.1:<port>/...`. The Hibernate dialect does not depend on the URL scheme, so this switch is safe.
- **Lifecycle ordering (critical):** `HeadlessLauncher.main` starts the TCP server **before** the startup DB probe (`probeAndDecide`), because that probe opens a JDBC connection before Spring boots — and in server mode that connection is `tcp://` and needs the server already listening. The Spring bean only owns shutdown (`@PreDestroy`).
- The chosen port is recorded to `<config>/h2-server.properties` (non-secret, diagnostics only).

With the server running, a `database` plugin gets an H2 user plus a `fengyu_<plugin>` schema and connects via `tcp://127.0.0.1:<port>/...;SCHEMA=fengyu_<plugin>` — true DB-level RBAC isolation. If the host is still on file-mode H2 (before promotion), a `database` plugin is treated like the SQLite case: the host allocates an independent file under the plugin's data directory.

## Admin credentials and provisioning

RBAC provisioning requires a set of **admin credentials** — a DB account with `CREATE USER` / `CREATE SCHEMA` / `GRANT` privileges, separate from the host's normal runtime account. These are optional and collected by the setup wizard: shown for H2 / MySQL / PostgreSQL, hidden for SQLite. They are stored AES-GCM encrypted in `datasource.properties` as `db.admin.username` / `db.admin.password`, and **used only for provisioning DDL** — never injected into a worker.

Per-plugin worker credentials (a per-plugin user plus a random URL-safe-base64 password) are generated on first authorization and stored AES-GCM encrypted in `plugin-db.properties`. They are never the host's or the admin's credentials, and never exposed to the iframe.

### Provisioning flow

Provisioning is **user-authorized, never implicit**:

1. A plugin declaring `database` shows an **"Authorize database"** action in Settings → "Database isolation".
2. The user clicks it, confirms the dialog (which explains that a dedicated DB user/schema will be created for this plugin), and the host issues `POST /api/plugin-db/provision/{pluginId}`.
3. The provisioner reads the admin credentials (only at this moment), checks for an existing record (idempotent — returns the stored credentials without re-running DDL), then runs the engine-specific DDL with `IF NOT EXISTS` clauses, stores the new per-plugin credentials, and returns the connection coordinates for injection.
4. On the next worker launch the environment service injects the plugin's restricted `FENGYU_DB_*` coordinates.

A `database` plugin that has **not yet been authorized** receives **no** database environment at all — the UI guides the user to authorize it. The host's global DB credentials never reach a worker.

If the admin credentials lack the required privileges, provisioning fails with a clear error pointing the user to the setup wizard. It **never silently degrades** to sharing the host's credentials.

### Deprovisioning on uninstall

Uninstalling a plugin drops its DB user and namespace (`DROP USER` / `DROP SCHEMA` / `DROP DATABASE` via the admin credentials) and then removes the store record. Deprovisioning is **non-blocking**: a DDL failure is caught and logged (left for a later retry), but the store record is always removed so uninstall itself always succeeds. SQLite has no deprovisioning step — the plugin's `.db` file under its data directory is cleaned up as part of normal uninstall.

## Table-prefix convention (naming hygiene)

Earlier versions treated the `FengTu_PL_<Plugin>_<Table>` prefix as the isolation mechanism. With engine-level RBAC now in place, the prefix is **naming hygiene, not a security boundary**:

```text
FengTu_PL_<Plugin>_<Table>
```

Each plugin still owns and migrates its schema independently, must not depend on host JPA entities or another plugin's tables, and should keep the prefix so tables are easy to attribute. But a plugin can no longer escape its namespace by picking a different prefix — the engine-level grant defines the boundary. Keep migrations dialect-specific, versioned, and idempotent so they run on all four databases.

## Secrets

The host protects the datasource password, the admin credentials, and every per-plugin worker credential with machine-bound AES-GCM. Plugin-owned secrets remain the plugin's responsibility — Email Center, for example, stores its AES key in the stable private data directory and encrypts SMTP/IMAP passwords before persistence. Passwords are write-only over RPC, errors are redacted, and database configuration never enters the iframe.

## Checklist

- Declare `database` and use the official Worker SDK.
- Treat engine-level isolation as authoritative; keep the `FengTu_PL_<Plugin>_` prefix for naming hygiene.
- Authorize the plugin's DB access from Settings → "Database isolation" before expecting database features to work.
- Keep migrations dialect-specific, versioned, and idempotent (H2 and SQLite are mandatory locally; MySQL and PostgreSQL run when configured).
- Encrypt plugin-owned credentials and never return them over RPC.

See [Manifest](/en/plugins/manifest), [Worker](/en/plugins/worker), and [Email Center](/en/plugins/email-center).
