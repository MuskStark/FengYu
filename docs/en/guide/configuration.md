---
title: Configuration
description: Manage user settings, switch AI backends and API keys with hot-swap, probe a connection, and reconfigure the database.
lang: en
---

# Configuration

Two config surfaces live in Infinia: **user settings** (theme, language, sidebar, logging) and **AI config** (active backend, API keys, models). Both are read and written over REST, and AI config can be hot-swapped at runtime without restarting. Database reconfiguration is also exposed here as a reset endpoint.

All endpoints require the `X-FengYu-Token` header. See [Backend](/en/architecture/backend).

## User settings

```text
GET /api/settings
  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": false,
             "logLevel": "INFO", "updateApiBase": "", "computerUseEnabled": true,
             "computerUse": { "available": true, "reason": null } }
```

`PUT /api/settings` accepts a **partial body** — only the keys you include are persisted; the rest stay as they are.

```text
PUT /api/settings
  Content-Type: application/json
  { "sidebarCollapsed": true }

  ◄── 200 { "theme": "dark", "language": "en", "sidebarCollapsed": true, "logLevel": "INFO" }
```

| Key | Type | Meaning |
| --- | --- | --- |
| `theme` | string | `"light"` or `"dark"`. See [Design System](/en/design-system). |
| `language` | string | UI locale (e.g. `en`, `zh-CN`). |
| `sidebarCollapsed` | boolean | Whether the sidebar starts collapsed. |
| `logLevel` | string | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`. Applied immediately to the main application and every Java plugin Worker. |
| `updateApiBase` | string | Empty for GitHub, or the absolute HTTP(S) base URL of an intranet FY-Proxy update server. |
| `computerUseEnabled` | boolean | Master switch for the desktop `computer_*` screen-control tools (default `true`). `false` removes them from the AI catalog on the next turn; input actions always keep the per-turn approval gate. |
| `computerUse` | object | Read-only capability probe: `{available, reason}`. Present only in desktop mode; `null` in plain web mode. |

Changing `logLevel` does not restart Workers. The host updates its Logback namespaces and sends a
built-in JSON-RPC notification to each running Worker; newly launched Workers inherit the same
value through `FENGYU_LOG_LEVEL`.

`updateApiBase` is loaded before the desktop window opens, so the automatic startup probe and the
manual **About → Check for updates** action use the same channel. FY-Proxy mode intentionally
supports only `Infinia-<version>-win32-x64-portable.zip` and the lite
`Infinia-<version>-linux-x64.deb`; NSIS, AppImage, macOS, JRE, and portable Web/JAR builds continue
to use the public GitHub channel and are rejected when pointed at FY-Proxy.

## AI config

`GET /api/ai/config` returns a **masked** snapshot so you can render the form without exposing raw keys:

- API keys are masked — only the first and last few characters are shown, e.g. `sk-1***wXYZ`.
- The snapshot also reports `activeMode` (the current backend) and `ready` (whether the active backend is usable).

```text
GET /api/ai/config
  ◄── 200 {
        "activeMode": "openai",
        "ready": true,
        "contextWindowTokens": 32768,
        "openai":  { "apiKey": "sk-1***wXYZ", "model": "gpt-4o", "baseUrl": "..." },
        ...
      }
```

`contextWindowTokens` controls long-chat compaction. FengYu starts summarizing old rounds at 60%
of this value; the default is `32768`, and `0` disables automatic compaction. Set it to the selected
model's actual context window rather than its output-token limit.

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

## MCP clients

FengYu can dynamically add, test, enable, disable, and remove MCP servers from **Settings → MCP**
or through the REST API. STDIO, SSE, and Streamable HTTP connections are established immediately
after saving, and discovered tools are added to the live chat and Agent catalogs without a restart.

To connect [mcp-chrome](https://github.com/hangwin/mcp-chrome):

1. Install its Chrome extension and `mcp-chrome-bridge` as described by the project, then click
   Connect in the extension.
2. Open **Settings → MCP** in FengYu and click **Add Chrome MCP**.
3. Save the prefilled Streamable HTTP configuration: `http://127.0.0.1:12306` with endpoint
   `/mcp`.

The official mcp-chrome client URL is `http://127.0.0.1:12306/mcp`. FengYu accepts either the
host URL plus `/mcp` endpoint or the complete URL in the address field.

For a Codex-style STDIO server file, Spring AI startup configuration remains available:

```bash
java -jar FengYu-*.jar \
  --spring.ai.mcp.client.stdio.servers-configuration=file:/absolute/path/mcp-servers.json
```

Inspect connections with `GET /api/mcp/status` and `GET /api/mcp/servers`. Configuring an external
STDIO command is explicit authorization to launch that command, so only use trusted server
definitions and keep credentials in protected local configuration.

## `datasource.properties` layout

The database connection is persisted separately from AI config, at
`<program-working-directory>/.fengyu/config/datasource.properties`, with keys:

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

## Secret storage at rest

Local secrets (the datasource password, AI provider API keys, MCP server credentials) are
encrypted with a machine-bound key before they are written to disk. By default that key is a
random value stored at `.fengyu/config/.machineid` — this binds every encrypted value to the
machine (a stolen config file is useless elsewhere) but does not protect against a reader
running as the same OS user.

Deployments that keep secrets in the operating system's credential store can inject the key
instead of using the file. Set `FENGYU_MACHINE_KEY` (system property or environment variable,
16+ characters, stable across restarts) before launching the backend — for example from the
macOS Keychain:

```bash
export FENGYU_MACHINE_KEY="$(security find-generic-password -s FengYu -a machine-key -w)"
java -jar Infinia.jar --token=...
```

Equivalent lookups: `secret-tool lookup fengyu machine-key` on Linux, or a Credential Manager
read in the Windows run script. Every encrypted value is bound to the injected key — switching
or losing it makes the stored ciphertexts undecryptable (reset them via the settings UI).
While `FENGYU_MACHINE_KEY` is set, the `.machineid` file is not used or created.

## Next steps


- [Database](/en/guide/database) — the first-launch wizard and the four backends.
- [AI Chat](/en/guide/ai-chat) — using the backend you just configured.
- [REST API](/en/reference/rest-api) — the full endpoint reference.
