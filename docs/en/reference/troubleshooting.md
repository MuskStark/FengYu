---
title: Troubleshooting
description: Common Infinia 4.0.0 problems and their fixes — port conflicts, database connection failures, token mismatches, plugin worker crashes, and micro-frontend load errors — each as symptom, cause, and resolution.
lang: en
---

# Troubleshooting

The five issues users and plugin authors hit most often. Each is laid out as **symptom → cause → fix**.

## Port conflict on 24056

**Symptom.** The backend fails to bind `24056` (another process owns it), or two instances collide.

**Cause.** `--port` defaults to `24056`. When the port is already taken, the launcher falls back to an OS-assigned port and announces it on stdout as `FENGYU_PORT=<n>` — but a client that hardcoded `24056` will now talk to the wrong process (or nothing).

**Fix.**

- Read the actual port from the launcher's stdout `FENGYU_PORT=` line. The desktop shell and any external supervisor parse this line to discover the port — do the same.
- To opt out of the fixed port entirely, launch with `--port=0` and always read the announced `FENGYU_PORT`.
- Free a stuck port: find the holding process (`lsof -i :24056` on macOS/Linux) and stop it before relaunching.

See [Backend — Port announcement](/en/architecture/backend#port-announcement).

## Database connection failure

**Symptom.** The backend starts but falls back into the first-launch wizard even though `datasource.properties` exists; or `POST /api/setup/initialize` reports the DB is unreachable.

**Cause.** At startup the launcher probes the configured database with a JDBC `SELECT 1` (5-second login timeout). If the probe fails, it backs the existing config up to a `.bak` sibling and re-enters SETUP mode so you can supply corrected parameters.

**Fix.**

1. Re-run the wizard: `GET /api/setup/status`, `GET /api/setup/types`, then `POST /api/setup/test-connection` with `{type, params}` to validate **without persisting**.
2. Once the test passes, `POST /api/setup/initialize` to persist and restart into APP mode.
3. Your previous config is safe — look for `datasource.properties.bak` next to the live config.
4. For external DBs (`MYSQL`, `POSTGRESQL`), confirm the server is reachable, credentials are correct, and the JDBC URL points at the right host. For embedded backends (`H2`, `SQLITE`), confirm the file path is writable.

See [Database — Unreachable database](/en/guide/database#unreachable-database) and [Backend — SETUP vs APP mode](/en/architecture/backend#setup-vs-app-mode).

## Token mismatch (401 / 403 everywhere)

**Symptom.** Every authenticated request returns `401` or `403`, but `/api/health` works.

**Cause.** The `X-FengYu-Token` header the client sends does not match the value the launcher was given via `--token`. The token bypass list (`/api/health`, `/api/setup/*`, `/plugin-runtime/{id}/**`) keeps working, which is why health still responds.

**Fix.**

- Confirm the token the launcher was started with: it is stored as the system property `fengyu.auth.token`, derived from `--token=<t>`.
- Send the same value verbatim as the `X-FengYu-Token` header on every request — including the SSE streams (`?streamId=` / `?runId=` carry the stream id, **never** the token).
- If you don't know the token, restart the backend with a fresh `--token` and update all clients.

See [REST API — Authentication](/en/reference/rest-api#authentication).

## Plugin worker crash

**Symptom.** A plugin's `client.invoke(...)` rejects, the host reports the worker exited non-zero, or calls start timing out.

**Cause.** The worker is a separate OS process (`backend/worker.jar`) speaking newline-delimited JSON-RPC 2.0 over stdio. It can crash from an unhandled exception, an out-of-memory, or — most commonly — a log line written to `stdout` that desynchronizes the RPC framing.

**Fix.**

1. **Check stderr.** Worker logs go to `stderr` (the SDK redirects `System.out` to `System.err` to protect the protocol channel). The crash reason is there.
2. **Check JSON-RPC framing.** `stdout` is reserved for protocol messages — one JSON-RPC object per line. Anything else on `stdout` (a stray `println`, a banner, a stack trace) corrupts the stream. Keep all diagnostics on `stderr`.
3. **Restart the worker** by disabling and re-enabling the plugin: `PATCH /api/plugin-packages/{id}/enabled {enabled:false}` then `{enabled:true}`. Disabling tears the process down; enabling spawns it lazily on next invoke.
4. For a worker that hangs rather than crashes, cancel any in-flight RPC and disable the plugin to reclaim the process.

See [Worker (JSON-RPC)](/en/plugins/worker) and [Pitfalls — Logging to stdout](/en/plugins/pitfalls).

## Micro-frontend load errors

**Symptom.** The plugin UI iframe is blank, scripts silently fail to run, or the UI never adopts the host theme.

**Cause.** Two distinct mechanisms:

- **CSP.** The host serves plugin UI assets under a strict Content Security Policy. Inline scripts and disallowed origins are refused — they never execute.
- **Bridge setup.** The iframe is an isolated JavaScript realm and must initialize its own `@infinia/plugin-ui` instance, then bind it to a ready `FengYuClient`.

**Fix.**

- **CSP:** put all JavaScript in external files loaded via `<script src>` (the scaffolder writes `<script type="module" src="app.js">`), and load every asset from the plugin's own `/plugin-runtime/{id}/**` tree. Do not inline scripts or inline event handlers.
- **Bridge setup:** bundle the plugin's declared Vue/Vuetify dependencies, create Vuetify with `createFengYuVuetify`, and call `bindFengYuEnvironment(vuetify, fengyu)`. Check the `host.ready` error for an exact protocol-version mismatch.
- For "module not found" errors, install the dependencies declared by the plugin UI and rebuild it with the standard `yarn run build` path (npm for third-party scaffolds).

See [UI Micro-frontend](/en/plugins/ui-microfrontend) and [Pitfalls](/en/plugins/pitfalls).

## Next steps

- [REST API](/en/reference/rest-api) — confirm you are hitting the right endpoint with the right auth.
- [SSE Events](/en/reference/sse-events) — stream framing reference.
- [Pitfalls](/en/plugins/pitfalls) — the plugin-author-focused traps, in problem/cause/fix form.
