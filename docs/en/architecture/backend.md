---
title: Backend
description: The Infinia 4.0.0 backend is a headless Spring Boot 4.1.0 application launched by fan.summer.fengyu.HeadlessLauncher — loopback-bound, token-gated, and auto-switching between SETUP and APP modes.
lang: en
---

# Backend

The Infinia backend is a **headless Spring Boot** application. It has no JavaFX or built-in UI server of its own — it exposes a REST + SSE API over loopback, and a separate Vue SPA renders the UI. The entry point is `fan.summer.fengyu.HeadlessLauncher`.

## Stack

- **Spring Boot 4.1.0**
- **Spring AI 2.0.0**
- **Java 21**

## Entry point and CLI

`HeadlessLauncher` builds the Spring context directly via `SpringApplicationBuilder`. It accepts exactly two CLI arguments:

| Argument | Default | Behavior |
| --- | --- | --- |
| `--port=<n>` | `24056` | If the port is already taken, the launcher falls back to an OS-assigned port. |
| `--token=<t>` | — | Stored as the system property `fengyu.auth.token`; clients send it as the `X-FengYu-Token` header. |

There is no `--mode` flag. The launcher unconditionally forces `server.address=127.0.0.1`, so the API is reachable only from the local machine.

## Port announcement

Once the embedded server is up, the launcher prints the chosen port to stdout in a fixed, machine-readable form:

```text
FENGYU_PORT=<n>
```

The desktop shell and any external supervisor parse this line to discover which port to talk to. `PortAnnouncer` is responsible for emitting it.

## SETUP vs APP mode

The launcher auto-detects which Spring application to boot. The decision is based on the datasource configuration file at `~/.fengyu/config/datasource.properties` and whether the configured database is actually reachable:

```text
datasource.properties present? ──► probe DB (JDBC SELECT 1, 5s login timeout)
   │
   ├─ absent        ──► SETUP mode
   ├─ present + OK  ──► APP mode
   └─ present + unreachable ──► back up config to .bak, then SETUP mode
```

- **SETUP mode** boots `SetupApplication` with **no JPA**. It serves the first-launch wizard endpoints under `/api/setup/*` and exits `SETUP_DONE = 0` once initialization completes.
- **APP mode** boots `FengYuApplication` with the application property `fengyu.mode=app` and the full persistence + AI + plugin stack.

The reachability probe issues a plain JDBC `SELECT 1` with a **5-second login timeout**. On unreachable DB, the existing config is backed up to a `.bak` sibling before the launcher falls back to SETUP mode so the wizard can collect a corrected configuration.

## Exit codes

| Code | Name | Meaning |
| --- | --- | --- |
| `0` | `SETUP_DONE` | SETUP mode finished initialization cleanly. |
| `1` | `FATAL` | Unrecoverable startup failure. |

## Authentication

Every request passes through `TokenAuthFilter`, which compares the `X-FengYu-Token` header to the value supplied via `--token`. Three path prefixes bypass the filter so the system can bootstrap without a credential:

- `/api/health` — liveness probe.
- `/api/setup/*` — first-launch wizard (the token may not exist yet).
- `/plugin-runtime/{id}/**` — static plugin UI assets, served under a strict CSP.

All other endpoints require a matching token.

## Process model

The backend process is the host for plugin workers, but it does **not** load plugin code into its own Spring context. Plugin workers are spawned and owned by `PluginProcessManager` as separate out-of-process JSON-RPC 2.0 servers. See [Plugin System](/en/architecture/plugin-system).

## Next steps

- [Architecture Overview](/en/architecture/overview) — how the backend fits between the SPA and the Electron shell.
- [Desktop](/en/architecture/desktop) — how the shell supervises the SETUP → APP transition.
- [Plugin System](/en/architecture/plugin-system) — the worker process model.
