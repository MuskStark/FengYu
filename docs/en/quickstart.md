---
title: Quick Start
description: Build and run Infinia 4.0.0 from source.
lang: en
---

# Quick Start

Get Infinia 4.0.0 — an AI-native orchestration platform — running from source in a few minutes.
Build the backend, then launch it alongside the frontend.

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21+ (Eclipse Temurin recommended) | Backend (`Java 21`) |
| Node.js + npm | 20+ | Frontend dev server |
| Node.js + npm | 24.18.0 | Desktop shell only (skip if you only need web) |

## Build from source

Build the backend through the repository Maven wrapper:

```bash
git clone https://github.com/MuskStark/FengYu.git
cd FengYu
./mvnw clean package -f FengYu/pom.xml -DskipTests
```

The packaged backend jar lands at `FengYu/target/FengYu-*.jar` (versioned, e.g. `FengYu-4.0.0-beta.2.jar`).

## Run the backend

Launch the headless Spring Boot backend. It binds `127.0.0.1:24056` by default and prints `FENGYU_PORT=<n>` on startup.

```bash
java -jar FengYu/target/FengYu-*.jar --token=<your-token>
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

The Electron desktop shell sidecar-launches the Java backend. From the repo root:

```bash
cd desktop/electron
npm install
npm run dev       # DEFAULT: connects to an IDE-started backend at http://127.0.0.1:24056
                  #   (start the backend without --token so auth is disabled; shell does NOT spawn java)
                  # To spawn its own backend instead: set FENGYU_JAR=<built shaded jar>
                  #   (build it first: ./mvnw -pl FengYu -am package -DskipTests -Drevision=4.0.0)
                  #   or set FENGYU_DEV_BACKEND=disabled
```

For a distributable build:

```bash
npm run build     # = npm run build:ts && electron-builder (host platform)
```

::: tip
The desktop shell ships its own Chromium (no system WebView needed). You only need Java to run the
backend. See the [desktop README](https://github.com/MuskStark/FengYu/blob/4.0.0/desktop/README.md)
for staging the JAR / plugins and the two with/without-JRE build variants.
:::

## Releases

Release tags (`v4.0.0`, `v4.0.0-beta.*`, and `v4.0.0-rc.*`) trigger a GitHub Actions pipeline that
publishes **unsigned** Electron packages (Windows/macOS/Linux) and a **portable Web distribution**.
The Web archive runs the same backend + bundled Vue SPA from a folder:

```bash
# Unzip Infinia-<version>-web.zip, then:
./run.sh          # macOS/Linux (run.bat on Windows)
```

Requires **Java 21** (or use the Electron build that bundles a JRE). The backend binds **loopback
only** (`127.0.0.1`). Code-signing is deferred to a later release; the Electron auto-updater ships
through GitHub Releases.

## Next steps

- [Architecture overview](/en/architecture/overview) — how the headless backend, Vue UI, and Electron shell fit together.
- [Configuration](/en/guide/configuration) — ports, tokens, database selection, and AI backends.
