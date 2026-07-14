---
title: Database
description: Pick and configure one of four database backends through the first-launch setup wizard, then reconfigure later without reinstalling.
lang: en
---

# Database

Infinia ships with a first-launch **setup wizard** that asks you to pick a database backend and prove it works before the app finishes initializing. Until a datasource is configured, the backend runs in SETUP mode serving only the wizard; once initialized it restarts into APP mode with the full stack.

## Backends

Four databases are supported, declared by the `DbType` enum. Two are embedded (no separate server) and two are external:

| `DbType` | Driver family | Mode |
| --- | --- | --- |
| `H2` | H2 | Embedded |
| `SQLITE` | SQLite | Embedded |
| `MYSQL` | MySQL | External |
| `POSTGRESQL` | PostgreSQL | External |

Embedded backends write a local file managed by the app; external backends connect to a server you provide.

## Setup endpoints

All `/api/setup/*` endpoints **bypass the token filter** so the wizard can run before a token exists. See [Backend](/en/architecture/backend) for the bypass list.

| Method + path | Body / returns | Purpose |
| --- | --- | --- |
| `GET /api/setup/status` | `{initialized, supportedTypes[], embeddedTypes[]}` | Whether the app is initialized, plus the backend types it supports and which are embedded. |
| `GET /api/setup/types` | Per-type form metadata | The fields each backend needs (so the wizard can render the right form). |
| `POST /api/setup/test-connection` | `{type, params}` → test result | Probes the connection **without persisting** anything. |
| `POST /api/setup/initialize` | `{type, params}` → result | Re-tests the connection, then persists the config and signals a restart into APP mode. |
| `DELETE /api/setup/config` | — | Backs up the current config and clears it, then restarts into SETUP mode. |

### Recommended flow

1. `GET /api/setup/status` — confirm `initialized:false` and see which backends are available.
2. `GET /api/setup/types` — render the form for the backend you picked.
3. `POST /api/setup/test-connection` — validate the parameters before committing.
4. `POST /api/setup/initialize` — persist and let the backend restart into APP mode.

## Where config lives

The persisted datasource lives at:

```text
~/.fengyu/config/datasource.properties
```

with keys:

| Key | Meaning |
| --- | --- |
| `db.type` | One of `H2`, `SQLITE`, `MYSQL`, `POSTGRESQL`. |
| `db.url` | JDBC URL. |
| `db.driver` | JDBC driver class. |
| `db.dialect` | Hibernate dialect. |
| `db.username` | DB username. |
| `db.password` | DB password, **AES/GCM encrypted** (see below). |
| `db.file.path` | For embedded backends, the file location. |

### Password encryption

Passwords are encrypted with AES/GCM via `CryptoUtil`. The key is **machine-bound**:

1. The launcher writes a per-machine UUID to `~/.fengyu/config/.machineid`.
2. The AES key is `SHA-256("FengYu-4.0-Phase4-SetupKey:" + <machine UUID>)`.
3. Encrypted values are wrapped as `ENC(...)` in the properties file.

Because the key is derived from the local machine ID, a copied `datasource.properties` will not decrypt on another host.

## Unreachable database

If `datasource.properties` exists but the configured database is unreachable at startup, the launcher backs the config up to a `.bak` sibling and falls back to SETUP mode so the wizard can collect corrected parameters. See [Backend — SETUP vs APP mode](/en/architecture/backend#setup-vs-app-mode).

## Virtual user

On first APP-mode startup the app creates a virtual local user:

- **id:** `1`
- **name:** `ZFlow-Summer`
- **role:** admin / local

This is the identity conversations and other records attach to.

## Reconfigure

Two equivalent ways to re-enter the wizard:

- **Manual** — delete `~/.fengyu/config/datasource.properties` and restart the backend; with no config it boots into SETUP mode.
- **API** — `POST /api/settings/database/reset` backs up the current config, clears it, and restarts into SETUP mode. See [Configuration](/en/guide/configuration).

Both produce the same end state: a backed-up config and a SETUP-mode process waiting for new parameters.

## Next steps

- [Configuration](/en/guide/configuration) — user settings, AI config, and the reset endpoint.
- [Backend](/en/architecture/backend) — SETUP vs APP mode and the reachability probe.
- [Quick Start](/en/quickstart) — building and launching the backend.
