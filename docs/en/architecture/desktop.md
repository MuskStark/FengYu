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
| **Dev — external** (default; `!app.isPackaged`, no env or `FENGyu_DEV_BACKEND` set) | None — connects to a backend you started (IDE / `mvn spring-boot:run`) at `http://127.0.0.1:24056`. No spawn, no token, no supervisor. | Opens once `/api/health` on the external backend responds |
| **Dev — spawned** (`!app.isPackaged`, `FENGyu_JAR` set or `FENGyu_DEV_BACKEND=disabled`) | Spawned as a jar sidecar by the shell, using the jar at `FENGyu_JAR` | Opens immediately, loads `localhost:5173` |
| **Release** (`app.isPackaged`) | Spawned as a jar sidecar by the shell | Opens after the backend is healthy, loads the bundled SPA |

By default, `yarn run dev` connects to a backend you started in your IDE **without** `--token=` —
`TokenAuthFilter` then disables auth, and the shell passes an empty token so the SPA's empty-token
fallback lines up. The shell does NOT spawn java, generate a token, or run the SETUP→APP supervisor;
you own the backend's lifetime. If you started the backend with `--token=<t>`, also set
`FENGyu_TOKEN=<t>`. To point at a different port, set `FENGyu_DEV_BACKEND=http://127.0.0.1:<port>`.

To make the shell spawn its own backend instead (self-contained dev), set `FENGyu_JAR=<path>` (or
`FENGyu_DEV_BACKEND=disabled`): it spawns the jar, generates a per-launch token, runs the health check +
supervisor — the full release lifecycle, just loaded from the dev Vite server. `FENGyu_JAR` is
**required** on this path (the shell throws `Dev mode requires FENGyu_JAR...` if it's unset). The Vite
dev server (port 5173) proxies `/api` to whichever backend is active, and is also how the developer runs
the frontend in a browser separately. In release the shell owns the backend process end to end.

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
`<program-working-directory>/.fengyu/logs/backend-stdout.log`.

Java is resolved at runtime: the **with-JRE** build prefers `<resourcesPath>/jre/bin/java`; the
**without-JRE** build uses `java` from `PATH`. If `java` is missing, the shell shows a native error
dialog and exits.

## Health and setup orchestration

Once the port is known, the shell drives the backend through three stages:

1. **`wait_for_health`** — polls `GET /api/health` with the `X-FengYu-Token` header on a **300 ms
   interval** with a **2-second per-request timeout** and a **30-second overall deadline**. Only HTTP
   200 counts as ready. Uses Node 24.18's built-in `fetch` + `AbortController`.
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
window.fengyu.initialTheme()   // 'dark' | 'light' — theme chosen by the shell at startup (no flash)
window.fengyu.setupMode()      // boolean | null — pre-probed setup state, null in a browser
window.fengyu.setTheme(theme)  // asks the shell to persist/apply the theme
window.fengyu.pickFile(filters)   // → native open dialog (IPC)
window.fengyu.pickDirectory()     // → native open dialog (IPC)
window.fengyu.openExternal(url)   // → validated http(s) URL in the system browser (IPC)
```

`apiBase`/`token` are **read-only snapshots** captured at startup. The SPA talks to the backend
directly over loopback — AI chat SSE streaming, file uploads, and the plugin micro-frontend host all
need native `fetch`/`EventSource`/`FormData`, which IPC cannot carry, so the token is exposed as a
snapshot rather than hidden behind a full IPC proxy. The token is per-launch and loopback-only, and
the backend enforces endpoint ACLs regardless. This replaces the old Tauri `window.__FENGYU_*`
globals. The Vue SPA reads these via the `connection` store / `config.ts` to configure every API
call. `window.fengyu` is `undefined` in a plain browser, so web mode falls through to env vars.
See [Frontend](/en/architecture/frontend).

Cloud account sign-in uses `openExternal`: the headless backend starts the PKCE attempt and returns
an authorization URL, then the renderer asks Electron to open it. The main process parses the URL
again and rejects every scheme except `http:` and `https:` before calling `shell.openExternal`.
Plain browser mode uses a new tab instead.

**BrowserWindow posture:** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`,
`webSecurity: true` (default) — standard Electron secure posture. CSP is governed by the backend's
SPA response headers, not set to `null` in the main process.

## Desktop enhancements

Four capabilities the old Tauri shell did not have:

- **Single-instance lock** — `app.requestSingleInstanceLock()`. A second launch shows and focuses the
  existing window (also restoring it from the tray).
- **System tray** — icon migrated from the old shell; menu: Show / Hide / Quit. Drives the close
  semantics below.
- **File logging** — `electron-log` writes main-process logs to
  `<program-working-directory>/.fengyu/logs/desktop.log` (same dir as backend logs); backend
  stdout/stderr tee'd to `<program-working-directory>/.fengyu/logs/backend-stdout.log`. Built-in
  size/date rotation.
- **Auto-update** — `electron-updater` against GitHub Releases (`latest*.yml` generated by
  electron-builder). Non-blocking check after `app.whenReady()`. Auto-install (download +
  `quitAndInstall`) is gated on a signed release (`FENGYU_SIGNED_RELEASE=true`, set by a future
  signed+notarized build). Current builds are unsigned, so an available update only **notifies** the
  user and offers to open the manual download page — it never invokes the installer, because the
  GitHub feed alone does not verify the publisher (no OS code signing / macOS notarization yet).

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

### macOS title-bar alignment invariant

The renderer-owned window bar is **48 px** tall. On macOS the native traffic lights, the sidebar
toggle, and route-toolbar controls must share its `y = 24` centerline. Keep this BrowserWindow
combination in `desktop/electron/src/window/create-window.ts`:

```ts
frame: false,
titleBarStyle: 'hidden',

win.setWindowButtonVisibility(true)
win.setWindowButtonPosition({ x: 14, y: 18 })
```

Do **not** simplify it to `frame: false` plus `setWindowButtonVisibility(true)`. With the default
title-bar style, Electron 43 does not create its native `WindowButtonsProxy`; the position API then
stores the point but has no proxy to redraw, leaving the controls at the system-default height.
`titleBarStyle: 'hidden'` initializes that proxy while `frame: false` preserves the fully frameless
renderer and its interactive HTML controls.

The call order is also intentional: restore visibility first, then apply the position. A native
visibility update can relayout/reset the button frame on current macOS releases. The `y = 18`
top inset centers the 12 px native buttons at `y = 24`; the 28 px HTML toggle uses `top: 10px` and
therefore has the same center.

Before changing this contract, run both focused checks and inspect a real macOS window—the bare JAR
smoke test does not exercise Electron's native chrome:

```bash
cd desktop/electron
yarn build:ts
yarn vitest run test/window-open-handler.test.ts

cd ../../frontend
yarn node --test test/sidebar-collapse.test.mjs

cd ../desktop/electron
yarn run dev  # with the IDE backend on :24056; inspect the active window
```

## Packaging

Packaging is handled by **electron-builder** (`desktop/electron/electron-builder.yml`). Two installer
variants ship per platform, built from the one base config via CI `--config` overrides. Artifacts
follow a uniform scheme `<product>-<version>-<platform>-<arch>[<form>].<ext>` (e.g.
`Infinia-4.0.0-mac-arm64.dmg`, `Infinia-4.0.0-win-x64-setup.exe`):

| Platform | Without JRE (lite) | With JRE (self-contained) |
| --- | --- | --- |
| macOS (arm64) | `Infinia-<ver>-mac-arm64.dmg` | `Infinia-<ver>-mac-arm64-jre.dmg` |
| Windows (x64) | `Infinia-<ver>-win-x64-setup.exe` (NSIS) + `*-portable.zip` | `Infinia-<ver>-win-x64-setup-jre.exe` + `*-portable-jre.zip` |
| Linux (x64) | `Infinia-<ver>-linux-x64.AppImage` + `.deb` | `Infinia-<ver>-linux-x64-jre.AppImage` |

The Windows **portable** form is an extract-and-run ZIP (extract, then run `Infinia.exe`) — no
installation and no startup-time self-extraction. The with-JRE variant bundles a **jlink-minimized**
JRE (generated in CI from JDK 21 via `jdeps` + `jlink --strip-debug`) under `<resources>/jre/`. Alpha
builds are **unsigned**.

A third, Linux-only **UOS (统信) variant** ships `Infinia-UOS-<ver>-linux-x64.AppImage` + `.deb`
(`desktop/electron/electron-builder.uos.yml`, JRE-based and self-contained). It bakes
`fengyu.uos: true` into the package metadata; at startup the main process (`src/desktop/uos.ts`)
detects it and launches with the Chromium sandbox disabled (`no-sandbox`) plus the working directory
re-anchored to the user's home — non-root UOS systems forbid every OS-level sandbox and a
menu-launched app starts with an unwritable cwd. Renderer hardening (`webPreferences.sandbox`,
contextIsolation) is unaffected.

## Next steps

- [Backend](/en/architecture/backend) — what the sidecar is actually running, and the SETUP/APP modes the shell drives it through.
- [Frontend](/en/architecture/frontend) — how the SPA consumes the `window.fengyu` bridge.
- [Quick Start](/en/quickstart) — `cd desktop/electron && yarn run dev` and `yarn run build`.
