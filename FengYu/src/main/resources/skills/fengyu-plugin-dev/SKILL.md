---
name: Plugin Development
description: How FengYu plugins work and how to build/package them — the 4.0.0 .fyp model (sandboxed iframe UI + out-of-process JSON-RPC worker), manifest.json fields, the permission enum, aiTools, and the fengyu CLI build/install flow. Load when the user asks about developing, packaging, installing, or troubleshooting plugins, or mentions .fyp, manifest.json, fengyu.plugin.json, plugin workers, aiTools, plugin permissions, or the plugin marketplace.
---

# FengYu Plugin Development

Guidance for building FengYu plugins against the **4.0.0** model. A plugin is an isolated
**`.fyp`** package containing a **sandboxed iframe UI** and an optional **out-of-process worker**.
Plugins contribute *callable capabilities*; they are a peer extension surface to skills.

If anything here disagrees with what the installed app actually does, **the app wins** —
this is guidance, not authority.

## Plugin shapes

Every plugin is one of two shapes. Read the target plugin's `manifest.json` to decide which:

| Shape | Has `backend`? | Worker | Example |
|---|---|---|---|
| **UI-only** | no `backend` (or no worker) | none | UI calls host/SDK only |
| **UI + Java Worker** | `backend.protocol == "json-rpc-2.0"` | a shaded worker JAR | UI ↔ host ↔ out-of-process worker |

## The .fyp package model

- **UI (always)** — a Vue micro-frontend that runs inside a **sandboxed iframe** served by the
  host under a strict Content-Security-Policy (`connect-src 'none'` — the UI **cannot** call out
  directly). To reach the backend or host capabilities, the UI uses `@infinia/plugin-sdk` to
  `postMessage` to the host shell, which proxies the call. The UI **never** sees absolute
  filesystem paths — file access flows through opaque file-reference objects mediated by the host.
- **Worker (UI + Java Worker only)** — a Java `main()` that links the Java Worker SDK and speaks
  **newline-delimited JSON-RPC 2.0** over stdin/stdout (one request object per line, responses
  matched by `id`). The worker runs in **its own process** with its own classpath; it must not
  assume any host-provided dependency beyond the SDK. A worker crash can never take down the host.
  The host injects env vars `FENGYU_PLUGIN_ID`, `FENGYU_PLUGIN_ROOT`, and (for plugins with the
  `database` permission) a datasource.

## manifest.json contract

`manifest.json` is the runtime contract. Required top-level fields: `schemaVersion`, `id`,
`name`, `description`, `version`, `author`, `icon`, `category`, `ui`. Rules:

- `schemaVersion` must be `1`.
- `id` must match `^[a-z0-9]+(?:[.-][a-z0-9]+)+$` (same rule plugin ids use). **Official** plugin
  ids start with `fan.summer.`.
- `version` is semver: `^\\d+\\.\\d+\\.\\d+(?:[-+].+)?$`.
- `.fyp` packages only (no loose directories); the host loader rejects anything else.

## permissions

Request the **minimum** set from the allowed enum only. The host enforces these; do not attempt
capability access you did not declare:

- `files.read` — read file references the host mediates
- `files.write` — write/create files
- `network` — outbound HTTP
- `network.email` — SMTP/IMAP (e.g. the Email Center)
- `clipboard.read` / `clipboard.write`
- `notifications`
- `database` — host-injected datasource for the worker

## aiTools

Optional `aiTools[]` exposes plugin capabilities as tools the AI model can call during chat. Each
entry needs `name`, `description`, `method` (the JSON-RPC method the worker implements), and
`inputSchema` (a JSON Schema string). Optional `timeoutSeconds` (1–600). Keep `description` short
and model-readable — that is what the AI primarily reads.

## Build, package, install

The `fengyu` CLI has only two plugin subcommands:

```bash
# Scaffold a new plugin (third-party). --id is required.
fengyu plugin create my-plugin --id com.example.my-plugin          # UI + Java worker (default)
fengyu plugin create my-plugin --id com.example.my-plugin --ui-only
fengyu plugin create my-plugin --id com.example.my-plugin --no-install

# Build into a .fyp. Validation runs as an unconditional build stage.
fengyu plugin build ./my-plugin                                   # → .fyp in package.outputDirectory
fengyu plugin build ./my-plugin --out dist/x.fyp --skip-tests
```

There is **no** `fengyu plugin dev`, `fengyu plugin install`, or `fengyu plugin validate` —
development happens in the IDE, validation runs as part of `build`, and installing a `.fyp` is
done through the host. Install the resulting `.fyp` via the **Plugins** page (`/plugins`) upload
button or the in-app **plugin marketplace**.

## Hard prohibitions (these describe legacy versions, not 4.0.0)

Do **not** generate or recommend any of:

- JavaFX views, `createView()`, `StepWizard`, `-sk-*` / `.glass-*` CSS, or any `javafx.*` code.
- `FengYuPluginV2` / `FengYuPlugin` Java interface implementations, or in-process Spring
  `@Component` plugin beans.
- `META-INF/services/...` Java `ServiceLoader` SPI registration files.
- Any assumption that the worker shares the host classpath, host Spring context, or host JPA
  session. Workers are out-of-process; bring every dependency in the worker's own shaded JAR.
- Direct `fetch` / `connect-src` from the iframe UI — route through the `@infinia/plugin-sdk`
  `postMessage` bridge instead.
