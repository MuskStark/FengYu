# ZhiFlow Desktop (Tauri 2.0) — Phase 1 dev

The desktop shell wraps the identical Vue frontend in a native window and sidecar-launches the
Java backend (`fan.summer.zhiflow.HeadlessLauncher`), reading the bound loopback port from the
backend's stdout (`ZHIFLOW_PORT=<n>`), polling `/api/health`, then injecting the backend URL +
per-launch token into the webview via `window.__ZHIFLOW_TOKEN__` / `window.__ZHIFLOW_API_BASE__`.

## Prerequisites (not installed in this repo's CI/dev image yet)

- Rust toolchain (`rustup`, `cargo`) — https://rustup.rs
- Tauri CLI: `cargo install tauri-cli --version '^2.0'`
- A JRE on `PATH` (the sidecar runs `java -cp ZhiFlow.jar fan.summer.zhiflow.HeadlessLauncher`)
- Node + the `frontend/` deps installed (`cd ../frontend && npm install`)

## Phase 1 dev run

1. Build the backend jar (via IntelliJ Maven or the bundled Maven):
   `mvn -f ZhiFlow-Api/pom.xml install -DskipTests && mvn -f plugin-markdown/pom.xml install -DskipTests && mvn -f ZhiFlow/pom.xml package -DskipTests`
2. Copy the jar so the sidecar can find it:
   `mkdir -p src-tauri/binaries && cp ../ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar src-tauri/binaries/ZhiFlow.jar`
   (If you skip this, `main.rs` falls back to `../../ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar`.)
3. From `desktop/src-tauri/`, run: `cargo tauri dev`
   - `beforeDevCommand` starts the Vite dev server (`frontend`, port 5173).
   - Tauri spawns the Java sidecar, waits on health, then opens the window.

## Lifecycle

- On launch: spawn `java ... --port=24056 --token=<generated>` → parse `ZHIFLOW_PORT` → poll
  `/api/health` (30s timeout, fatal on failure) → inject token/URL → load webview. The backend
  tries the fixed port `24056` first and falls back to an OS-assigned port if it is taken; the
  actual port is always read back from `ZHIFLOW_PORT`.
- On window close: the sidecar Java process is killed.

## Out of scope (Phase F-prod)

Signed installers, a per-platform bundled JRE, and Tauri `externalBin` sidecar packaging are a
later phase. Phase 1 targets a working dev-mode window only.
