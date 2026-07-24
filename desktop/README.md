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
| Node.js + npm | 24.17+ | Electron main process + build toolchain |
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

The shell needs a backend to talk to. Two options:

1. **Point at a built JAR** — set `FENGYU_JAR` to a packaged shaded jar and the shell will spawn it:

   ```bash
   export FENGYU_JAR=/path/to/FengYu/target/FengYu-4.0.0-alpha.2.jar
   npm run dev
   ```

2. **Run the backend externally** — start the backend yourself on `:24056` (the shell's
   `runtime-layout` falls back to an externally running backend when no JAR is configured):

   ```bash
   java -jar FengYu/target/FengYu-4.0.0-alpha.2.jar --port=24056 --token=<t>
   ```

   Then start the frontend Vite dev server in a second terminal (`cd frontend && npm run dev`) so the
   Electron window (which loads `localhost:5173`) proxies `/api` + `/plugin-runtime` to the backend.

The frontend SPA is loaded from the Vite dev server in dev, so you need the frontend running too.

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
