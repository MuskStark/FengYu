---
title: Quick Start
description: Build and run Infinia 4.0.0 from source.
lang: en
---

# Quick Start

Get Infinia 4.0.0 — an AI-native orchestration platform — running from source in a few minutes.
The reactor consists of two Maven modules — build them in order, then launch the backend and frontend.

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21+ (Eclipse Temurin recommended) | Backend (`Java 21`) |
| Node.js + npm | 20+ | Frontend dev server |
| Rust + `tauri-cli` | stable | Desktop shell only (skip if you only need web) |

## Build from source

The build is a two-module reactor. `FengYu-Api` **must install first** because `FengYu` depends on it.

```bash
git clone https://github.com/MuskStark/FengYu.git
cd FengYu
mvn install -f FengYu-Api/pom.xml -DskipTests
mvn clean package -f FengYu/pom.xml -DskipTests
```

The packaged backend jar lands at `FengYu/target/FengYu-4.0.0-alpha.2.jar`.

## Run the backend

Launch the headless Spring Boot backend. It binds `127.0.0.1:24056` by default and prints `FENGYU_PORT=<n>` on startup.

```bash
java -jar FengYu/target/FengYu-4.0.0-alpha.2.jar --token=<your-token>
```

The entry point is `fan.summer.fengyu.HeadlessLauncher`. CLI flags are `--port` and `--token` only.

## Run the frontend (dev)

The Vue 3 + Vuetify 3 frontend runs against the backend via Vite, which proxies `/api` and `/plugin-runtime` to `localhost:24056`.

```bash
cd frontend
npm install
npm run dev
```

Open the printed local URL and the UI will talk to the backend you started above.

## Smoke test

A helper script boots the packaged jar and probes every endpoint.

```bash
scripts/e2e-smoke.sh
```

Run it whenever you want a quick end-to-end sanity check after a build.

## Run desktop (dev)

The Tauri 2.0 desktop shell sidecar-launches the Java jar. From the repo root:

```bash
cd desktop
cargo tauri dev
```

For a distributable build:

```bash
cargo tauri build
```

::: tip
Desktop builds require Rust **and** the system WebView runtime for your platform. See the [Tauri prerequisites](https://tauri.app/start/prerequisites/) if `cargo tauri dev` fails to start.
:::

## Releases (Alpha)

Release tags (`v4.0.0-alpha.2`, and later stable/beta/rc) trigger a GitHub Actions pipeline that
publishes **unsigned** Tauri packages (Windows/macOS/Linux) and a **portable Web distribution**. The
Web archive runs the same backend + bundled Vue SPA from a folder:

```bash
# Unzip Infinia-<version>-web.zip, then:
./run.sh          # macOS/Linux (run.bat on Windows)
```

Requires **Java 21**. The backend binds **loopback only** (`127.0.0.1`). Code-signing, a bundled JRE,
and the auto-updater are deferred to a later release.

## Next steps

- [Architecture overview](/en/architecture/overview) — how the headless backend, Vue UI, and Tauri shell fit together.
- [Configuration](/en/guide/configuration) — ports, tokens, database selection, and AI backends.
