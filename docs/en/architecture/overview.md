---
title: Architecture Overview
description: Infinia 4.0.0 is a three-layer system — a headless Spring Boot backend, a Vue 3 SPA, and a Tauri 2.0 desktop shell — bound to loopback 127.0.0.1 and guarded by a per-launch token.
lang: en
---

# Architecture Overview

Infinia 4.0.0 is a **three-layer system**: a headless Spring Boot backend, a Vue 3 single-page app, and a Tauri 2.0 desktop shell that owns the process lifecycle. The same Vue UI runs in a browser tab or inside the Tauri window — the shell just changes how the backend is started and how the UI is served.

## Three layers

```
┌─────────────────────────────────────────────────────────────────┐
│  Tauri 2.0 desktop shell  (desktop/, Rust)                      │
│  • spawns / owns the Java sidecar process                       │
│  • injects window.__FENGYU_TOKEN__ | __FENGYU_PORT__ |          │
│    __FENGYU_API_BASE__  before page load                        │
│  • serves the built SPA in a system WebView                     │
│  • kills the sidecar when the window is destroyed               │
└───────────────┬───────────────────────────────┬─────────────────┘
                │ spawns (release)               │ loads HTML/JS
                ▼                                │
┌──────────────────────────────────┐             │
│  Headless Spring Boot backend    │             │
│  fan.summer.fengyu.HeadlessLauncher             │
│  • binds 127.0.0.1:24056         │             │
│  • token-gated REST + SSE        │             │
│  • spawns plugin worker processes│             │
│  • Spring Boot 4.1.0 + Spring AI │             │
└───────────────┬──────────────────┘             │
                │ HTTP (loopback only)            │
                ▼                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  Vue 3 SPA  (frontend/, TypeScript)                             │
│  Pinia + vue-router 4 + vue-i18n 10, Vuetify 3 (MD3)            │
│  • talks to the backend over the loopback HTTP API              │
│  • loads plugin UI micro-frontends via the MF host              │
└─────────────────────────────────────────────────────────────────┘
```

## Request flow

Every request from the UI travels the same loopback path:

1. The SPA (in a browser or in the Tauri WebView) issues an HTTP request to the backend.
2. The request targets `127.0.0.1:<port>`, where `<port>` is the value the backend printed as `FENGYU_PORT=<n>` on startup (default `24056`).
3. The backend's `TokenAuthFilter` inspects the `X-FengYu-Token` header. Requests without a matching token are rejected — with three exemptions: `/api/health`, `/api/setup/*`, and the static plugin UI assets under `/plugin-runtime/{id}/**`.
4. Authenticated requests reach the controllers, which may dispatch work to out-of-process plugin workers via JSON-RPC 2.0.

## Loopback-only bind

The backend forces `server.address=127.0.0.1`. It does not listen on any external interface — the API is reachable only from the same machine that runs the process. This is the primary network security boundary: there is no way to reach the API from another host.

## Per-launch token auth

Each backend launch is authenticated by a single token:

- The launcher accepts a `--token=<t>` CLI argument and stores it as the system property `fengyu.auth.token`.
- Every protected request must carry `X-FengYu-Token: <t>`.
- The token is regenerated per launch; there is no persisted credential. The desktop shell injects it into the SPA as `window.__FENGYU_TOKEN__` before the page loads.

The combination of loopback binding and per-launch token keeps the API private to the local user even when the process is running.

## Layer responsibilities

| Layer | Owns |
| --- | --- |
| [Backend](/en/architecture/backend) | REST/SSE surface, persistence, AI backends, plugin worker lifecycle, auth |
| [Frontend](/en/architecture/frontend) | Vue 3 SPA, Pinia stores, plugin UI mounting, setup wizard routing |
| [Desktop](/en/architecture/desktop) | Sidecar spawn/health/setup orchestration, bridge injection, window lifecycle |
| [Plugin System](/en/architecture/plugin-system) | `.fyp` package contract, out-of-process workers, sandboxed UI |

## Next steps

- [Backend](/en/architecture/backend) — `HeadlessLauncher`, SETUP/APP modes, and the token filter.
- [Desktop](/en/architecture/desktop) — how the Tauri shell spawns and supervises the backend.
- [Quick Start](/en/quickstart) — build and run the three layers from source.
