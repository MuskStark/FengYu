---
title: Plugin Database Standard
description: Database environment, schema ownership, and credential rules for isolated plugins.
lang: en
---

# Plugin Database Standard

Plugins can use the host-selected H2, SQLite, MySQL, or PostgreSQL datasource without linking to host persistence code. The host injects database type, driver, JDBC URL, username, password, and a stable private data directory into the isolated Worker environment. `PluginDatabaseConfig.fromEnvironment(...)` reads these values. They are never exposed to the iframe, and are provided only when the manifest declares `database`.

## Independent schema ownership

Each plugin owns and migrates its schema independently. It must not depend on host JPA entities, host repositories, or another plugin's tables. Every table follows:

```text
FengTu_PL_<Plugin>_<Table>
```

Email Center therefore creates only `FengTu_PL_Email_*` tables. Versioned dialect migrations are repeatable on all four databases. H2 and SQLite contracts are mandatory locally; MySQL and PostgreSQL run when their CI URLs are configured.

## Secrets

The host protects its datasource password with machine-bound AES-GCM. Plugin-owned secrets remain the plugin's responsibility. Email Center stores its AES key in the stable private data directory and encrypts SMTP/IMAP passwords before persistence. Passwords are write-only over RPC, errors are redacted, and database configuration never enters the iframe.

## Checklist

- Declare `database` and use the official Worker SDK.
- Keep migrations dialect-specific, versioned, and idempotent.
- Prefix every table with `FengTu_PL_<Plugin>_`.
- Encrypt plugin credentials and never return them over RPC.

See [Manifest](/en/plugins/manifest), [Worker](/en/plugins/worker), and [Email Center](/en/plugins/email-center).
