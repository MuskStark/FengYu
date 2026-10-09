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
| **Dev — external** (default; `!app.isPackaged`, no env or `FENGyu_DEV_BACKEND` set) | None — connects to a backend you started (IDE / `mvn spring-boot:run`) at `http://127.0.0.1:24056`. No spawn, no token, no supervisor. | Opens as soon as Vite is listening; the in-app boot screen holds until `/api/health` on the external backend responds |
| **Dev — spawned** (`!app.isPackaged`, `FENGyu_JAR` set or `FENGyu_DEV_BACKEND=disabled`) | Spawned as a jar sidecar by the shell, using the jar at `FENGyu_JAR` | Opens as soon as Vite is listening, loads `localhost:5173`, boot screen covers the whole backend boot |
| **Release** (`app.isPackaged`) | Spawned as a jar sidecar by the shell | Opens immediately at startup (before the spawn), loads the bundled SPA behind its boot screen |

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
java -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

The shell reads the child process's stdout for the line `FENGYU_PORT=<n>`, with a **120-second
deadline** (cancellable, so a window-close during a slow boot cannot hang). The port line is only
printed on `WebServerInitializedEvent` — after the whole Spring context is built — so the deadline
must cover the full cold boot; slow hardware (UOS field boot measured ~39 s) can far exceed half a
minute, while a JVM crash fails fast via the stdout end. If the line does not
appear in time, the launch fails. Startup-window backend stdout (everything before the
port line) and backend stderr (for the child's whole lifetime) are logged into
`<program-working-directory>/.fengyu/logs/desktop.log` under `[backend]`/`[backend-err]`
prefixes — steady-state backend logging lives in `fengyu.log`.

The `<program-working-directory>` above is the shell's **runtime anchor** (`bootstrap-cwd.ts`),
picked per platform for packaged builds:

- **Windows** — the executable's directory: the install root for the NSIS setup, the extract folder
  for the portable ZIP. The whole `.fengyu` tree (config, embedded database, logs, plugins, skills,
  chat data) stays with the app, like the web distribution's `<extract>\data`. A legacy
  `%APPDATA%\fengyu-desktop\.fengyu` from earlier builds is moved there automatically on the first
  writable launch (same-volume atomic rename; a cross-volume install copies through a
  `.fengyu.migrating` staging sibling that is renamed into place, so an interrupted copy never
  leaves a half-populated tree — the next launch retries from the intact legacy tree). Only the
  instance holding the single-instance lock runs the move; an unwritable install directory
  (e.g. Program Files without elevation) keeps the previous `userData` anchor instead, and the
  legacy tree is never moved off it.
- **macOS / Linux** — Electron's `userData` directory (`~/Library/Application Support/…` /
  `~/.config/…`). The macOS .app bundle must not host user data (the zip auto-update replaces the
  whole bundle), and a Linux AppImage's executable path is a read-only, ephemeral squashfs mount.

Dev runs keep their own working directory (`desktop/electron/`); the UOS build re-anchors to the
user's home as described below. Electron's own profile data (browser partitions, Local Storage,
updater cache) always stays in the OS user-data location (`%APPDATA%\fengyu-desktop` on Windows)
— only the FengYu runtime tree moves.

Java is resolved at runtime: the **with-JRE** build prefers `<resourcesPath>/jre/bin/java`; the
**without-JRE** build uses `java` from `PATH`. If `java` is missing, the shell shows a native error
dialog and exits.

## Health and setup orchestration

The main window is created **first — before the backend spawn** — so the app's own loading
surface owns the whole startup that a separate splash window used to cover (the splash was
removed outright: no second window, no hand-off). The window's entry document carries a
**static boot shell** (`#boot-loading` in `frontend/index.html`: the inline brand mark plus a
traveling progress track, no script, so no CSP hash changes) that paints before any bundled
CSS/JS arrives; on the first React commit, `main.tsx`'s `StartupReady` adds
`fengyu-startup-ready` to `<body>`, cross-fades the shell out and `#root` in, and removes it.
The SPA mounts behind a boot gate (`shell/BootGate.tsx` showing the shared `StartupScreen` —
the same mark and track the HTML shell played) that holds the full shell behind its own
`/api/health` poll, detects SETUP mode once the backend answers, and enables features only
then. Fine-grained startup progress rides the same `boot:state` channel as failure recovery:
the shell pushes `{phase:'booting', stage}` with the stages `spawning` → `port-ready` →
`health-ready` → `loading-ui`, and the StartupScreen maps each stage to a localized label
over its progress track (the renderer subscribes first and then pulls `boot:get-state` once —
a push to an unloaded page is dropped, not queued). The gate → app hand-off is a cross-fade:
App keeps the BootGate mounted as a fixed, pointer-transparent veil that fades out over the
freshly mounted app shell, and the wrapper element is stable across the switch so the startup
screen's running animations continue through the fade (reduced-motion skips it).

Because the document loads before the backend port is known, the endpoint cannot ride the
preload's env snapshot on the first load: the shell pushes `{apiBase, token}` over the
`endpoint:ready` channel (`ipc/endpoint.ts`) the moment the spawn resolves the port, and the
renderer's platform layer prefers that live push over the snapshot (the axios client resolves
its baseURL per request, so the boot gate's health poll picks the endpoint up without a
reload; page loads after boot re-read the env snapshot). Until the endpoint is known, the
header CSP admits loopback wildcards (`http://127.0.0.1:*` / `http://localhost:*`) — the same
baseline the document's own meta CSP already grants — instead of the exact backend origin.
Meanwhile the shell drives the backend through three stages in parallel with the renderer:

1. **`wait_for_health`** — polls `GET /api/health` with the `X-FengYu-Token` header on a **300 ms
   interval** with a **2-second per-request timeout** and a **120-second overall deadline**. Only HTTP
   200 counts as ready. Uses Node 24.18's built-in `fetch` + `AbortController`. The overall deadline is
   deliberately generous: on slow hardware (UOS field boot: ~39 s before the port line even appears)
   the Spring cold boot can far exceed half a minute, while a crashed JVM is caught by the exit race
   described below — the deadline only bounds the alive-but-slow case.
2. **`check_setup_mode`** — probes `GET /api/setup/status` to determine whether the backend booted
   into SETUP or APP mode (body contains `"initialized":false` → SETUP).
3. **`run_backend_until_app_mode`** — ties the loop together: (window) → spawn → wait for health →
   check setup mode. A backend exit during the wait fails fast (no deadline parking on a
   dead JVM). If the backend is in SETUP mode, the shell waits for the process to exit with code `0`
   (`SETUP_DONE`), then **respawns** the backend, which comes back up in APP mode with the
   now-valid datasource. After respawn the shell validates the port is unchanged and the backend is
   now in APP mode; either mismatch is fatal.

The whole launch is timed as **T0–T6 marks** — T0–T3 in the main process
(`desktop/launch-marks.ts`: process creation, main-bundle eval, `whenReady`, main-window
load), T4–T6 in the renderer (`shell/launch-perf.ts`: bundle eval, first React commit,
entering the app shell). When the renderer reaches T6 it reports over the one-way
`perf:launch-report` IPC and the main process writes one merged `[perf] launch …` line to
`desktop.log` next to the other startup timing logs (first-run SETUP is excluded).

**Boot failures during the backend wait are recoverable in the app.** A backend exit,
an exceeded health deadline, or a broken setup probe no longer quits the app with a native
dialog: the shell pushes `boot:state {phase:'failed', reason, exitCode, detail}`
(`ipc/boot.ts`) and the boot gate swaps in `StartupFailureScreen` — reason + exit code +
**Retry / Open logs / Copy diagnostics / Quit**. Retry reuses the same token and port
(the renderer endpoint cannot change), force-kills the leftover JVM tree, respawns, and
races an exit watcher against the health wait so a dead retry fails fast. Because the
failure can fire before the page even loaded (`webContents.send` to an unloaded page is
dropped, not queued), the renderer subscribes first and then pulls the current state once
(`boot:get-state`). The screen ACKs visibility on mount (`boot:failure-visible`); a
failure never ACKed within 15 s falls back to exactly the old native-dialog-and-quit
behavior (renderer never loaded / SPA broken). On desktop the boot gate now also *holds*
the startup screen until the backend is healthy — the mount-time setup probe runs against
a booting backend and fails, so the gate's health poll takes over and re-probes SETUP vs
APP on success; the browser keeps falling straight through to the app shell as before.

## Frontend bridge (contextBridge)

The shell's preload script uses `contextBridge` to expose a controlled API on `window.fengyu` before
the page loads:

```js
window.fengyu.apiBase()        // 'http://127.0.0.1:<port>' — env snapshot; EMPTY on the first load (the window opens before the spawn resolves the port)
window.fengyu.onEndpoint(cb)   // live endpoint handoff: cb({apiBase, token}) when the port resolves (endpoint:ready push)
window.fengyu.getEndpoint()    // → Promise<{apiBase, token} | null> — pulls the last pushed endpoint (covers a push that raced the page load)
window.fengyu.token()          // the per-launch X-FengYu-Token — env snapshot; the platform layer prefers the live endpoint push
window.fengyu.desktop          // true — feature flag
window.fengyu.initialTheme()   // 'dark' | 'light' — theme chosen by the shell at startup (no flash)
window.fengyu.setupMode()      // boolean | null — pre-probed setup state; null on first boot (the SPA live-probes at its boot gate) and in a browser
window.fengyu.setTheme(theme)  // asks the shell to persist/apply the theme
window.fengyu.pickFile(filters)   // → native open dialog (IPC)
window.fengyu.pickDirectory()     // → native open dialog (IPC)
window.fengyu.openExternal(url)   // → validated http(s) URL in the system browser (IPC)
```

`apiBase`/`token` are **env snapshots** captured at page load (empty on the first load — see the
`endpoint:ready` live handoff above). The SPA talks to the backend
directly over loopback — AI chat SSE streaming, file uploads, and the plugin micro-frontend host all
need native `fetch`/`EventSource`/`FormData`, which IPC cannot carry, so the token is exposed as a
snapshot rather than hidden behind a full IPC proxy. The token is per-launch and loopback-only, and
the backend enforces endpoint ACLs regardless. This replaces the old Tauri `window.__FENGYU_*`
globals. The React SPA reads these via the `connection` store and the `src/platform` layer to
configure every API call. `window.fengyu` is `undefined` in a plain browser, so web mode falls through to env vars.
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
  `<program-working-directory>/.fengyu/logs/desktop.log` (same dir as backend logs), alongside
  backend startup stdout (`[backend]`), backend stderr (`[backend-err]`), and forwarded renderer
  errors (`[renderer]`) — one file, 5 MB rotation. The update pipeline keeps its own
  `update.log` (2 MB rollover) in the same directory.
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
JRE (generated in CI from JDK 25 via `jdeps` + `jlink --strip-debug`) under `<resources>/jre/`, plus
a CI-trained **AOT startup cache** (`<resources>/jre/FengYu.aot`, produced by
`desktop/electron/scripts/train-aot-cache.sh` right after the jlink pass) that cuts backend
time-to-health roughly in half. The lite variant and CLI-style boots self-train the same cache in
the background after a successful first boot (`src/backend/aot-cache.ts`). Alpha builds are
**unsigned**.

A third, Linux-only **UOS (统信) variant** ships `Infinia-UOS-<ver>-linux-x64.AppImage` + `.deb`
(`desktop/electron/electron-builder.uos.yml`, JRE-based and self-contained). Its launch entries
start Electron with `--no-sandbox` on the real command line (`linux.executableArgs` writes the
switch into the deb's `/usr/share/applications/infinia-uos.desktop` menu shortcut and the
AppImage's embedded desktop file) — a JS-added switch alone is unreliable on UOS, where Chromium
reads sandbox decisions from the process argv. Upgrading the deb overwrites the arg-less menu
shortcut installed by earlier builds, and a postinst (`scripts/uos-deb-postinstall.sh`) refreshes
the desktop database so menus serve the new entry without a relogin. The build also bakes
`fengyu.uos: true` into the package metadata; the main process (`src/desktop/uos.ts`) detects it
and adds an in-process `appendSwitch` fallback (covers launches that bypass a desktop entry)
plus the working directory re-anchored to the user's home — non-root UOS systems forbid every
OS-level sandbox and a menu-launched app starts with an unwritable cwd. Renderer hardening
(`webPreferences.sandbox`, `contextIsolation`) is unaffected.

## Next steps

- [Backend](/en/architecture/backend) — what the sidecar is actually running, and the SETUP/APP modes the shell drives it through.
- [Frontend](/en/architecture/frontend) — how the SPA consumes the `window.fengyu` bridge.
- [Quick Start](/en/quickstart) — `cd desktop/electron && yarn run dev` and `yarn run build`.
