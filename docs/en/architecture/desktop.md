---
title: Desktop
description: The Infinia 4.0.0 desktop shell is a Tauri 2.0 application (productName Infinia, version 4.0.0) that spawns the Java sidecar on loopback, supervises the SETUP-to-APP transition, injects the token/port/api-base bridge, and kills the sidecar when the window closes.
lang: en
---

# Desktop

The Infinia desktop shell is a **Tauri 2.0** application. Its job is process supervision: spawn the Java backend, discover its port, drive it through SETUP into APP mode, hand the UI the credentials it needs, and tear everything down when the user closes the window. The product is named **Infinia**, version **4.0.0** (see `tauri.conf.json`, `productName: "Infinia"`).

## Dev vs release builds

The shell behaves differently depending on the build profile:

| Profile | Backend | Window |
| --- | --- | --- |
| **Dev** (`cfg(debug_assertions)`) | External — assumed running on `:24056`, reached via the Vite proxy | Opens immediately |
| **Release** | Spawned as a jar sidecar by the shell | Opens after the backend is healthy |

In dev the developer starts the backend and the Vite dev server separately; the shell just hosts the window. In release the shell owns the backend process end to end.

## Sidecar spawn (release)

The release build spawns the packaged jar with a fixed command shape:

```bash
java -Dfengyu.plugins.official-directory=... \
     -cp <jar> \
     fan.summer.fengyu.HeadlessLauncher \
     --port=24056 \
     --token=<t>
```

A reader thread scans the child process's stdout for the line `FENGYU_PORT=<n>`, with a **30-second deadline**. If the line does not appear in time, the launch fails.

## Health and setup orchestration

Once the port is known, the shell drives the backend through three stages:

1. **`wait_for_health`** — polls `GET /api/health` with the `X-FengYu-Token` header on a **300 ms interval** with a **30-second deadline**. The backend is not considered ready until it answers.
2. **`check_setup_mode`** — probes `GET /api/setup/status` to determine whether the backend booted into SETUP or APP mode.
3. **`run_backend_until_app_mode`** — ties the loop together: spawn → wait for health → check setup mode. If the backend is in SETUP mode, the shell waits for the process to exit with code `0` (`SETUP_DONE`), then **respawns** the sidecar, which comes back up in APP mode with the now-valid datasource.

## Bridge injection

Before the page loads, the shell injects three globals into the WebView's `window`:

```js
window.__FENGYU_TOKEN__ = '<t>'
window.__FENGYU_PORT__  = '<n>'
window.__FENGYU_API_BASE__ = 'http://127.0.0.1:{port}'
```

The Vue SPA reads these from the `connection` store to configure every API call. See [Frontend](/en/architecture/frontend).

## Window and dialog integration

- **Window size:** `1280 × 820`, minimum `960 × 640`.
- **Native dialogs:** `tauri_plugin_dialog` exposes native file/directory pickers; the frontend reaches them via the `desktop.ts` facade (`pickFile` / `pickDirectory`).

## Lifecycle

The shell kills the sidecar process on `WindowEvent::Destroyed` — closing the window tears down the backend. There is no daemon: the backend lives exactly as long as the window.

## Next steps

- [Backend](/en/architecture/backend) — what the sidecar is actually running, and the SETUP/APP modes the shell drives it through.
- [Frontend](/en/architecture/frontend) — how the SPA consumes the injected bridge globals.
- [Quick Start](/en/quickstart) — `cargo tauri dev` and `cargo tauri build`.
