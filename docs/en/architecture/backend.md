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

The launcher auto-detects which Spring application to boot. The decision is based on the
datasource configuration file at
`<program-working-directory>/.fengyu/config/datasource.properties` and whether the configured
database is actually reachable:

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

### Cloud account sign-in

The launch token above protects the local host API; it is separate from the optional Infinia
Store identity used for authenticated outbound Store calls. The SPA starts sign-in through the
local `/api/account/*` endpoints. The headless `CloudAccountService` creates the OAuth 2.1
Authorization Code + PKCE attempt and returns its authorization URL; the renderer opens that URL
in the system browser. The host receives the code on a one-time ephemeral loopback port
(RFC 8252 §7.3), exchanges it, and resolves the Store profile through `GET /api/v1/me`. Browser
launching must never depend on Java AWT: headless test and packaged environments commonly report
`Desktop.isDesktopSupported()` as false.

The desktop authorization request must include `openid profile offline_access`, and the Store's
`fengyu-desktop` registered client must allow the same scopes plus authorization-code and refresh
grants. This is an interoperability invariant: omitting `offline_access` produces a working initial
login but no refresh token, so authenticated Store calls become anonymous when the 30-minute access
token expires. `AuthAndAccountFlowTest.fengYuDesktopPkceGrantCanRefreshAndCallMe` in the Store
repository covers the complete PKCE → `/me` → refresh → `/me` contract.

The access token lives only in memory (refreshes are serialized so a server-side rotation is
persisted exactly once) and the refresh token only in the OS credential store — macOS Keychain,
Windows Credential Manager, or Linux Secret Service; the database keeps the identity binding row
only (Flyway V2 dropped the legacy token columns). Signing in never changes the local virtual
user's ownership of chats, flows, or plugin data, and signing out revokes the refresh token
best-effort before deleting the cloud binding.

The user center page reads live Store data through the same access token: `/api/account/store-profile`,
`/profile`, `/password`, `/library`, `/organizations`, `/sessions`, and `/devices` proxy the
signed-in user's Store resources (see [REST API — Account](/en/reference/rest-api#account)) and
answer 401 when signed out. Nothing is persisted locally except a display-name rename, which syncs
the binding row so the fast `/api/account/me` view follows.

The Store base URL resolves per request through `StoreEndpointProvider`: the Settings 升级渠道
(`updateApiBase`) override wins — production deploys the store separately from the app, and
plugin installs/updates, cloud-account sign-in, and the user center all route through that one
channel without a restart — with `FENGYU_STORE_API_BASE` (default
`http://localhost:8080`) as the bootstrap fallback. Each resolution re-runs the SSRF policy, so
the channel may not point at a private network unless `fengyu.store.allow-private-network` is
explicitly set.

The desktop OAuth client ships in the public-client form (RFC 8252 §8.5): PKCE with no
shared secret, because a secret baked into a distributed desktop build is public knowledge
and cannot make the client confidential. A deployment whose store still registers
`fengyu-desktop` as a confidential client opts in explicitly via `FENGYU_STORE_CLIENT_SECRET`
(`fengyu.store.client-secret`); token and revocation requests then carry it as
`client_secret_post` on top of the always-mandatory PKCE verifier. Long-term login for the
public form is a store-side mechanism (per-install credentials or a BFF), not a shipped
secret. If the store answers `invalid_client`, the sign-in error carries a hint pointing at
this setting.

## Process model

The backend process is the host for plugin workers, but it does **not** load plugin code into its own Spring context. Plugin workers are spawned and owned by `PluginProcessManager` as separate out-of-process JSON-RPC 2.0 servers. See [Plugin System](/en/architecture/plugin-system).

## Next steps

- [Architecture Overview](/en/architecture/overview) — how the backend fits between the SPA and the Electron shell.
- [Desktop](/en/architecture/desktop) — how the shell supervises the SETUP → APP transition.
- [Plugin System](/en/architecture/plugin-system) — the worker process model.
