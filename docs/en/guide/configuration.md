---
title: Configuration
description: Manage user settings, switch AI backends and API keys with hot-swap, probe a connection, and reconfigure the database.
lang: en
---

# Configuration

Two config surfaces live in Infinia: **user settings** (theme, language, sidebar) and **AI config** (active backend, API keys, models). Both are read and written over REST, and AI config can be hot-swapped at runtime without restarting. Database reconfiguration is also exposed here as a reset endpoint.

All endpoints require the `X-FengYu-Token` header. See [Backend](/en/architecture/backend).

## User settings

```text
GET /api/settings
  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": false }
```

`PUT /api/settings` accepts a **partial body** — only the keys you include are persisted; the rest stay as they are.

```text
PUT /api/settings
  Content-Type: application/json
  { "sidebarCollapsed": true }

  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": true }
```

| Key | Type | Meaning |
| --- | --- | --- |
| `theme` | string | `"light"` or `"dark"`. See [Design System](/en/design-system). |
| `language` | string | UI locale (e.g. `en`, `zh-CN`). |
| `sidebarCollapsed` | boolean | Whether the sidebar starts collapsed. |

## AI config

`GET /api/ai/config` returns a **masked** snapshot so you can render the form without exposing raw keys:

- API keys are masked — only the first and last few characters are shown, e.g. `sk-1***wXYZ`.
- The snapshot also reports `activeMode` (the current backend) and `ready` (whether the active backend is usable).

```text
GET /api/ai/config
  ◄── 200 {
        "activeMode": "openai",
        "ready": true,
        "openai":  { "apiKey": "sk-1***wXYZ", "model": "gpt-4o", "baseUrl": "..." },
        ...
      }
```

### Updating AI config

`PUT /api/ai/config` takes a **partial** body. A key insight for round-trips: because `GET` masks the API key, sending that masked value back would clobber it. To avoid that, **any API key string containing `***` is treated as "unchanged"** and not persisted — so the masked value can be echoed straight back without losing the real key.

```text
PUT /api/ai/config
  Content-Type: application/json
  { "activeMode": "anthropic", "anthropic": { "apiKey": "sk-a***9zzz", "model": "..." } }
```

After persisting, the backend **hot-swaps** the active backend via `BackendReactivator.reactivate()` — no restart needed. The four supported modes are `local` (Ollama), `openai`, `anthropic`, and `deepseek` (OpenAI-compatible). See [AI Chat](/en/guide/ai-chat) for what each mode does.

### Testing a connection

Before committing a new mode, probe it without saving:

```text
POST /api/ai/config/test
  Content-Type: application/json
  { "mode": "deepseek", "endpoint": "...", "apiKey": "...", "model": "...", "baseUrl": "..." }

  ◄── 200 { "success": true, ... }
```

Use this to validate credentials and endpoint reachability up front.

## `datasource.properties` layout

The database connection is persisted separately from AI config, at `~/.fengyu/config/datasource.properties`, with keys:

| Key | Meaning |
| --- | --- |
| `db.type` | One of `H2`, `SQLITE`, `MYSQL`, `POSTGRESQL`. |
| `db.url` | JDBC URL. |
| `db.driver` | JDBC driver class. |
| `db.dialect` | Hibernate dialect. |
| `db.username` | DB username. |
| `db.password` | DB password, AES/GCM encrypted (see [Database](/en/guide/database)). |
| `db.file.path` | For embedded backends, the file location. |

`db.password` is encrypted with a machine-bound AES/GCM key derived from the local `.machineid`, and stored wrapped as `ENC(...)`. See [Database — Password encryption](/en/guide/database#password-encryption).

## Reconfigure the database

```text
POST /api/settings/database/reset
  X-FengYu-Token: <token>
```

This backs up the current `datasource.properties`, clears it, and restarts the backend into SETUP mode so the first-launch wizard can collect new parameters. Functionally equivalent to deleting `datasource.properties` and restarting manually — see [Database — Reconfigure](/en/guide/database#reconfigure).

## Next steps

- [Database](/en/guide/database) — the first-launch wizard and the four backends.
- [AI Chat](/en/guide/ai-chat) — using the backend you just configured.
- [REST API](/en/reference/rest-api) — the full endpoint reference.
