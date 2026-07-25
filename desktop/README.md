# FengYu Desktop (Electron)

The Infinia desktop shell. An **Electron 43.x** application (TypeScript main process) that
sidecar-launches the Java backend on loopback, supervises the SETUP → APP transition, exposes a
`contextBridge` API to the renderer, and owns the window, tray, logger, and auto-updater. It replaces
the former Tauri shell; the backend lifecycle it implements is **unchanged**.

- Entry point: `desktop/electron/src/main.ts` → compiled to `desktop/electron/dist/main.js`.
- Build config: `desktop/electron/electron-builder.yml`.
- Version carrier: `desktop/electron/package.json` (mirrors the Maven `${revision}`).

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| Node.js + npm | 24.18.0 | Electron main process + build toolchain |
| JDK | 21+ (Eclipse Temurin recommended) | Backend JAR (and the bundled JRE build, if any) |

The desktop shell does **not** require Rust or a system WebView runtime — Electron ships its own
Chromium. You only need Java to run the backend.

## Layout

```
desktop/
├── README.md                 # this file
├── .gitignore
└── electron/
    ├── package.json          # electron ^43, electron-builder, electron-updater, electron-log, vitest, @playwright/test
    ├── tsconfig.json         # main-process TS (module=commonjs, target=ES2022)
    ├── electron-builder.yml  # base config; CI builds two variants via --config overrides
    ├── src/
    │   ├── main.ts           # app.whenReady → orchestrate backend → create window + tray + updater
    │   ├── backend/          # runtime-layout, spawn, handshake, supervisor, orchestrator
    │   ├── window/           # create-window (BrowserWindow) + preload (contextBridge)
    │   ├── ipc/              # dialog:open IPC handlers (pickFile / pickDirectory)
    │   ├── desktop/          # single-instance, tray, logger
    │   ├── updater/          # electron-updater (GitHub Releases)
    │   └── util/             # token, health polling
    ├── resources/            # icons; CI injects binaries/FengYu.jar + plugins/ (+ jre/ for with-JRE)
    └── test/                 # vitest unit tests + @playwright/test e2e launch spec
```

## Dev

Install dependencies, then build the TypeScript main process and launch Electron against the dev Vite
server (`http://localhost:5173`):

```bash
cd desktop/electron
npm install
npm run dev      # = npm run build:ts && electron .
```

The desktop shell **auto-starts the Vite frontend** in dev (the old Tauri shell did this via
`beforeDevCommand`). When you run `npm run dev`, the shell spawns `npm run dev` in `frontend/` itself,
waits for Vite to be ready on `127.0.0.1:5173`, then opens the window pointed at it — you don't need a
separate terminal for the frontend. (Vite is forced to `--host 127.0.0.1 --port 5173 --strictPort` so
the bind is deterministic; if 5173 is busy, the shell errors clearly instead of letting Vite silently
move ports.) If you already have Vite running, the shell detects it and reuses it. You only need a
separate `cd frontend && npm run dev` terminal if you want browser-only frontend work without the shell.

### Default dev — connect to an IDE-started backend (no env vars needed)

By default, `npm run dev` connects to a backend you started yourself in the IDE (or
`mvn -pl FengYu spring-boot:run`) at `127.0.0.1:24056`. The shell does NOT spawn java, generate a
token, run the SETUP→APP supervisor, or manage the backend lifetime — you own it. Start the backend
**without** `--token=` so `TokenAuthFilter` disables auth (convenient for dev); the shell then passes
an empty token that lines up with the SPA's empty-token fallback.

```bash
# Terminal 1: backend (IDE run config, or):
./mvnw -pl FengYu spring-boot:run        # binds 127.0.0.1:24056, no token → auth disabled

# Terminal 2: desktop shell (auto-starts Vite, no env vars needed)
cd desktop/electron && npm run dev
```

If the IDE backend isn't running, the shell waits up to 30s for `/api/health`, then shows an error
telling you to start it. Overrides:

- `FENGYU_DEV_BACKEND=http://127.0.0.1:<other-port>` — connect to a backend on a different port.
- `FENGYU_TOKEN=<t>` — if you started the backend **with** `--token=<t>`, pass it through.
- `FENGYU_DEV_BACKEND=disabled` (or just set `FENGYU_JAR`) — opt out of the default and let the
  shell spawn its own backend from a jar (see below).

### Self-contained dev — shell spawns the backend from a jar

Set `FENGYU_DEV_BACKEND=disabled` (or just set `FENGYU_JAR`), and the shell spawns java, generates a
per-launch token, runs the health check + SETUP→APP supervisor — the full release lifecycle, just
loaded from the dev Vite server instead of the bundled SPA:

1. **Build the jar** (once, or after any backend change):

   ```bash
   ./mvnw -pl FengYu -am package -DskipTests -Drevision=4.0.0-alpha.2
   ```

2. **Set `FENGYU_JAR`** to the resulting jar, then start the shell:

   ```bash
   export FENGYU_JAR=/path/to/FengYu/target/FengYu-4.0.0-alpha.2.jar
   npm run dev
   ```

   This path requires `FENGYU_JAR` set explicitly (or `FENGYU_DEV_BACKEND=disabled`); the default
   with no env vars is the IDE-backend connection above.

## Staging the backend for packaging

The packaged app resolves the backend from `process.resourcesPath`. Before a local build, stage:

```
desktop/electron/resources/binaries/FengYu.jar   # the shaded backend jar
desktop/electron/resources/binaries/plugins/     # the official .fyp packages
desktop/electron/resources/jre/                  # only for the with-JRE variant (CI-generated via jlink)
```

`resources/binaries/` is git-ignored. The release workflow injects these in CI.

## Build

```bash
cd desktop/electron
npm run build            # = npm run build:ts && electron-builder (host platform)
npm run build:win        # build for Windows (NSIS)
npm run build:mac        # build for macOS (dmg, arm64 + x64)
npm run build:linux      # build for Linux (AppImage)
```

Output lands in `desktop/dist-electron/`. The frontend SPA must be built first and copied into
`desktop/electron/frontend-dist/` (CI does this; for a local build run
`cd frontend && npm run build` and copy `frontend/dist` → `desktop/electron/frontend-dist`).

### Two installer variants

The release workflow builds **two** variants per platform from the single `electron-builder.yml`,
overriding `extraResources` + `artifactName` via `--config`:

| Variant | JRE | Artifact naming |
| --- | --- | --- |
| **lite** (without JRE) | None — user needs Java 21+ on PATH | `Infinia-<ver>-<platform>.<ext>` |
| **jre** (with JRE) | Bundles a jlink-minimized JRE under `<resources>/jre/` | `Infinia-<ver>-<platform>-jre.<ext>` |

The with-JRE JRE is generated in CI with `jdeps` + `jlink` (strip-debug) from JDK 21.

## Tray semantics (changed from the old Tauri shell)

The close button does **not** kill the backend anymore:

| Action | What happens |
| --- | --- |
| **Window close button** | Window hides to the **system tray**; the backend **stays alive**. |
| **Tray "Quit"** / Cmd+Q / Alt+F4 | The backend is killed (SIGTERM, SIGKILL fallback) and the app exits. |

This is a deliberate consequence of adding the tray: backend lifetime is now tied to **app quit**, not
window close. (The old Tauri shell killed the backend on window close.)

Other shell enhancements: **single-instance lock** (a second launch focuses the existing window),
**file logging** to `~/.fengyu/logs/desktop.log` (+ `backend-stdout.log`), and an **auto-updater**
(electron-updater against GitHub Releases; Alpha unsigned — NSIS on Windows, user Gatekeeper allow on
macOS).

## Frontend bridge

A preload script uses `contextBridge` to expose `window.fengyu`:

```js
window.fengyu.apiBase()        // 'http://127.0.0.1:<port>' — read-only snapshot
window.fengyu.token()          // the per-launch X-FengYu-Token — read-only snapshot
window.fengyu.desktop          // true — feature flag (replaces the old isTauri() probe)
window.fengyu.pickFile(filters)   // → native open dialog (IPC)
window.fengyu.pickDirectory()     // → native open dialog (IPC)
```

`apiBase`/`token` are read-only snapshots captured at startup — the SPA talks to the backend directly
over loopback (SSE streaming, uploads, and the plugin micro-frontend host all need native
`fetch`/`EventSource`/`FormData`, which IPC cannot carry). This replaces the old Tauri
`window.__FENGYU_*` globals. `window.fengyu` is `undefined` in a plain browser, so web mode falls
through to env vars unchanged.

## Troubleshooting

- **"Java not found" error dialog at launch** — the without-JRE variant did not find `java` on PATH.
  Install a JRE/JDK 21+ (e.g. from https://adoptium.net) or use the with-JRE build.
- **`FENGYU_PORT` never appears / backend launch fails** — check `~/.fengyu/logs/backend-stdout.log`
  and `~/.fengyu/logs/desktop.log`. The most common cause is a port conflict on `24056`; the backend
  falls back to an OS-assigned port and announces it.
- **Window hides instead of closing** — that is the tray behavior (see above). Use tray "Quit" to exit.

## Tests

```bash
cd desktop/electron
npm test            # vitest unit tests (runtime-layout, supervisor, handshake, token, health)
npm run test:e2e    # @playwright/test + electron launch spec (needs a prebuilt backend JAR)
```

## Next steps

- [Desktop architecture](../docs/en/architecture/desktop) — the main-process orchestration in depth.
- [Architecture overview](../docs/en/architecture/overview) — how the three layers fit together.
- [Quick Start](../docs/en/quickstart) — build and run the whole stack from source.
