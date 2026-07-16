# FengYu Desktop (Tauri 2.0) — Phase 1 dev

The desktop shell wraps the identical Vue frontend in a native window. How it reaches the Java
backend (`fan.summer.fengyu.HeadlessLauncher`) differs by build profile, decided at compile time
via `debug_assertions` (see `src-tauri/src/main.rs`):

- **Dev (`cargo tauri dev`, a debug build)** — the backend is **NOT** spawned by Tauri. You run it
  yourself (IDE / `mvn -pl FengYu spring-boot:run`) on `:24056`, and the webview reaches it
  same-origin through the Vite dev-server proxy. No sidecar, no token injection, no jar needed. The
  whole spawn/health/token code path is compiled out of the dev binary.
- **Prod (`cargo tauri build`, a release build)** — Tauri sidecar-launches the bundled jar, reads
  the bound loopback port from the backend's stdout (`FENGYU_PORT=<n>`), polls `/api/health`, then
  injects the backend URL + per-launch token into the webview via `window.__FENGYU_TOKEN__` /
  `window.__FENGYU_API_BASE__`.

## Prerequisites (not installed in this repo's CI/dev image yet)

- Rust toolchain (`rustup`, `cargo`) — https://rustup.rs
- Tauri CLI: `cargo install tauri-cli --version '^2.0'`
- A JRE on `PATH` — **prod only** (the sidecar runs `java -cp FengYu.jar fan.summer.fengyu.HeadlessLauncher`)
- Node + the `frontend/` deps installed (`cd ../frontend && npm install`)

## Dev run (`cargo tauri dev`) — backend accessed directly, no sidecar

1. Start the backend yourself on `:24056` (pick one):
   - IntelliJ: run `HeadlessLauncher` (or the Spring Boot run config).
   - Shell: `mvn -pl FengYu spring-boot:run` (or `java -jar FengYu/target/FengYu-4.0.0-SNAPSHOT.jar`).
2. From `desktop/src-tauri/`, run: `cargo tauri dev`
   - `beforeDevCommand` starts the Vite dev server (`frontend`, port 5173), which proxies
     `/api` and `/plugin-ui` to `localhost:24056`.
   - Tauri only opens the window — it does **not** spawn or manage the backend.

No jar copy is needed in dev; the debug binary never touches the jar.

## Prod build (`cargo tauri build`) — bundled jar sidecar

The bundled jar + official `.fyp` plugins are resolved from Tauri's resource directory at runtime
(see `runtime_layout` in `src/main.rs`). The prod binary looks for:

- `binaries/FengYu.jar` — the shaded backend jar (uniform name).
- `binaries/plugins/*.fyp` — the official plugins (`markdown`, `excel`, `email`).

Both are declared as `bundle.resources` in `tauri.conf.json`, so Tauri copies them into the platform
resource directory at install time. Before a local `cargo tauri build`, stage them:

```bash
# From desktop/src-tauri/:
mkdir -p binaries/plugins
cp ../../FengYu/target/FengYu-4.0.0-alpha.1.jar binaries/FengYu.jar
cp ../../OfficialPlugins/target/packages/*.fyp binaries/plugins/
cargo tauri build --config '{"version":"4.0.0"}'
```

CI (`.github/workflows/fengyu-release.yml`) stages the tested jar + plugins and runs
`cargo tauri build` across a Windows/macOS/Linux matrix.

## Lifecycle (prod)

- On launch: resolve `app.path().resource_dir()` → `runtime_layout` → spawn
  `java ... --port=24056 --token=<generated>` → parse `FENGYU_PORT` → poll
  `/api/health` (30s timeout, fatal on failure) → inject token/URL → load webview. The backend
  tries the fixed port `24056` first and falls back to an OS-assigned port if it is taken; the
  actual port is always read back from `FENGYU_PORT`.
- On window close: the sidecar Java process is killed.

## Out of scope (Phase F-prod)

Signed installers, a per-platform bundled JRE, and the Tauri auto-updater are deferred. The Alpha
release ships **unsigned** packages; your OS may warn about an unidentified developer.
