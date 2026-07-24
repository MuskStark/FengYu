---
title: Desktop
description: The Infinia 4.0.0 desktop shell is an Electron 43.x application (productName Infinia, version 4.0.0) that spawns the Java backend on loopback, supervises the SETUP-to-APP transition, exposes a contextBridge API to the renderer, and owns the window, tray, logger, and auto-updater.
lang: en
---

# Desktop

The Infinia desktop shell is an **Electron 43.x** application written in TypeScript (main process).
Its job is process supervision: spawn the Java backend, discover its port, drive it through SETUP
into APP mode, hand the UI the credentials it needs, and tear everything down when the user quits.
The product is named **Infinia**, version **4.0.0** (see `desktop/electron/package.json`,
`productName: "Infinia"`). The backend lifecycle is **unchanged** from the previous Tauri shell —
only the shell that implements it was replaced.

## Dev vs release builds

The shell behaves differently depending on whether it is packaged:

| Profile | Backend | Window |
| --- | --- | --- |
| **Dev** (`!app.isPackaged`) | Spawned as a jar sidecar by the shell, using the jar at `FENGYU_JAR` (required) | Opens immediately, loads `localhost:5173` |
| **Release** (`app.isPackaged`) | Spawned as a jar sidecar by the shell | Opens after the backend is healthy, loads the bundled SPA |

In dev the shell still owns the backend process — it spawns the jar pointed at by `FENGYU_JAR`, which is
**required** (the shell throws `Dev mode requires FENGYU_JAR...` if it is unset). The Vite dev server
(port 5173) proxies `/api` to the shell-spawned backend, and is also how the developer runs the frontend
in a browser separately. In release the shell owns the backend process end to end.

## Backend spawn (release)

The release build spawns the packaged jar with a fixed command shape (ported verbatim from the old
Rust implementation):

```bash
java -Dfengyu.plugins.official-directory=<plugins-dir> \
     -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

The shell reads the child process's stdout for the line `FENGYU_PORT=<n>`, with a **30-second
deadline** (cancellable, so a window-close during a slow boot cannot hang). If the line does not
appear in time, the launch fails. Backend stdout/stderr lines are tee'd to
`~/.fengyu/logs/backend-stdout.log`.

Java is resolved at runtime: the **with-JRE** build prefers `<resourcesPath>/jre/bin/java`; the
**without-JRE** build uses `java` from `PATH`. If `java` is missing, the shell shows a native error
dialog and exits.

## Health and setup orchestration

Once the port is known, the shell drives the backend through three stages:

1. **`wait_for_health`** — polls `GET /api/health` with the `X-FengYu-Token` header on a **300 ms
   interval** with a **2-second per-request timeout** and a **30-second overall deadline**. Only HTTP
   200 counts as ready. Uses Node 24.17's built-in `fetch` + `AbortController`.
2. **`check_setup_mode`** — probes `GET /api/setup/status` to determine whether the backend booted
   into SETUP or APP mode (body contains `"initialized":false` → SETUP).
3. **`run_backend_until_app_mode`** — ties the loop together: spawn → wait for health → check setup
   mode. If the backend is in SETUP mode, the shell waits for the process to exit with code `0`
   (`SETUP_DONE`), then **respawns** the backend, which comes back up in APP mode with the
   now-valid datasource. After respawn the shell validates the port is unchanged and the backend is
   now in APP mode; either mismatch is fatal.

## Frontend bridge (contextBridge)

The shell's preload script uses `contextBridge` to expose a controlled API on `window.fengyu` before
the page loads:

```js
window.fengyu.apiBase()        // 'http://127.0.0.1:<port>' — read-only snapshot
window.fengyu.token()          // the per-launch X-FengYu-Token — read-only snapshot
window.fengyu.desktop          // true — feature flag
window.fengyu.pickFile(filters)   // → native open dialog (IPC)
window.fengyu.pickDirectory()     // → native open dialog (IPC)
```

`apiBase`/`token` are **read-only snapshots** captured at startup. The SPA talks to the backend
directly over loopback — AI chat SSE streaming, file uploads, and the plugin micro-frontend host all
need native `fetch`/`EventSource`/`FormData`, which IPC cannot carry, so the token is exposed as a
snapshot rather than hidden behind a full IPC proxy. The token is per-launch and loopback-only, and
the backend enforces endpoint ACLs regardless. This replaces the old Tauri `window.__FENGYU_*`
globals. The Vue SPA reads these via the `connection` store / `config.ts` to configure every API
call. `window.fengyu` is `undefined` in a plain browser, so web mode falls through to env vars.
See [Frontend](/en/architecture/frontend).

**BrowserWindow posture:** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`,
`webSecurity: true` (default) — standard Electron secure posture. CSP is governed by the backend's
SPA response headers, not set to `null` in the main process.

## Desktop enhancements

Four capabilities the old Tauri shell did not have:

- **Single-instance lock** — `app.requestSingleInstanceLock()`. A second launch shows and focuses the
  existing window (also restoring it from the tray).
- **System tray** — icon migrated from the old shell; menu: Show / Hide / Quit. Drives the close
  semantics below.
- **File logging** — `electron-log` writes main-process logs to `~/.fengyu/logs/desktop.log` (same
  dir as backend logs); backend stdout/stderr tee'd to `~/.fengyu/logs/backend-stdout.log`. Built-in
  size/date rotation.
- **Auto-update** — `electron-updater` against GitHub Releases (`latest*.yml` generated by
  electron-builder). Non-blocking check after `app.whenReady()`; on update available → native dialog
  confirm → download & install. Alpha unsigned: NSIS on Windows, user Gatekeeper allow on macOS.

## Shutdown semantics (changed — important)

Because of the tray, the backend lifetime is now tied to **app quit**, not window close:

| Action | Tauri (old) | Electron (new) |
| --- | --- | --- |
| Window close button | killed backend, exited | **hides to tray**, backend stays alive |
| Tray "Quit" / Cmd+Q / Alt+F4 | n/a | kills backend (SIGTERM, SIGKILL fallback), exits |

The main process kills the backend on the `before-quit` event (not on `window.on('close')`). The
close handler calls `preventDefault()` + `window.hide()` unless the app is genuinely quitting.

## Window and dialog integration

- **Window size:** `1280 × 820`, minimum `960 × 640` (matches the previous shell).
- **Native dialogs:** `pickFile` / `pickDirectory` go through IPC to Electron's native dialog and are
  exposed on `window.fengyu`; the frontend reaches them via the `desktop.ts` facade.

## Packaging

Packaging is handled by **electron-builder** (`desktop/electron/electron-builder.yml`). Two installer
variants ship per platform, built from the one base config via CI `--config` overrides:

| Platform | Without JRE (lite) | With JRE (self-contained) |
| --- | --- | --- |
| macOS (arm64 + x64) | `Infinia-<ver>-mac.dmg` | `Infinia-<ver>-mac-jre.dmg` |
| Windows (x64) | `Infinia-<ver>-win.exe` (NSIS) | `Infinia-<ver>-win-jre.exe` |
| Linux (x64) | `Infinia-<ver>.AppImage` | `Infinia-<ver>-jre.AppImage` |

The with-JRE variant bundles a **jlink-minimized** JRE (generated in CI from JDK 21 via
`jdeps` + `jlink --strip-debug`) under `<resources>/jre/`. Alpha builds are **unsigned**.

## Next steps

- [Backend](/en/architecture/backend) — what the sidecar is actually running, and the SETUP/APP modes the shell drives it through.
- [Frontend](/en/architecture/frontend) — how the SPA consumes the `window.fengyu` bridge.
- [Quick Start](/en/quickstart) — `cd desktop/electron && npm run dev` and `npm run build`.
