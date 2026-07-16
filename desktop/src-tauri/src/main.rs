// Prevents an extra console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// `Child` is shared by both build paths (the Sidecar holder + run_desktop signature).
use std::process::Child;
use std::sync::Mutex;

use tauri::{Manager, State};

/// Where the bundled runtime assets live under the Tauri resource directory.
///
/// The prod bundle stages the shaded jar at `binaries/FengYu.jar` and the official `.fyp` plugins
/// under `plugins/` (see `tauri.conf.json` `bundle.resources`). Tauri copies both into the
/// platform resource directory at install time; this struct resolves their absolute paths from a
/// single `resource_dir()` so the backend is spawned against the right files regardless of where
/// the OS installed the app.
#[derive(Clone)]
struct RuntimeLayout {
    jar: std::path::PathBuf,
    plugins: std::path::PathBuf,
}

/// Resolves the jar and plugin paths under `resource_dir`.
///
/// `resource_dir` is `app.path().resource_dir()` in prod (Tauri's platform install location) and
/// any path in tests. Kept cfg-free so the unit test (compiled under `debug_assertions`) can call it.
fn runtime_layout(resource_dir: &std::path::Path) -> RuntimeLayout {
    RuntimeLayout {
        jar: resource_dir.join("binaries").join("FengYu.jar"),
        plugins: resource_dir.join("plugins"),
    }
}

#[derive(Debug, PartialEq, Eq)]
enum StartupAction {
    ShowWindow,
    ShowWindowAndSupervise { port: u16 },
}

fn startup_action(setup_mode: bool, port: u16) -> StartupAction {
    if setup_mode {
        StartupAction::ShowWindowAndSupervise { port }
    } else {
        StartupAction::ShowWindow
    }
}

fn should_restart_setup(shutting_down: bool, exit_code: Option<i32>) -> bool {
    !shutting_down && exit_code == Some(0)
}

// The sidecar machinery below is PROD-only, gated on `not(debug_assertions)` (a release build).
// In dev (`cargo tauri dev`, a debug build) the backend is started externally (IDE /
// `mvn spring-boot:run` on :24056) and the webview reaches it via the Vite proxy — so none of the
// spawn/health/token code is compiled into the dev binary.
#[cfg(not(debug_assertions))]
use std::io::{BufRead, BufReader};
#[cfg(not(debug_assertions))]
use std::process::{Command, Stdio};
#[cfg(not(debug_assertions))]
use std::thread;
#[cfg(not(debug_assertions))]
use std::time::{Duration, Instant};

#[derive(Default)]
struct SidecarState {
    child: Option<Child>,
    shutting_down: bool,
}

/// Holds the spawned Java sidecar so it can be replaced after setup and killed on exit.
struct Sidecar {
    state: Mutex<SidecarState>,
    #[cfg(not(debug_assertions))]
    supervisor: Mutex<Option<std::thread::JoinHandle<()>>>,
}

struct PreparedDesktop {
    child: Option<Child>,
    init_script: Option<String>,
    #[cfg(not(debug_assertions))]
    supervisor: Option<SetupSupervisor>,
}

/// Builds the window and runs the Tauri event loop. Shared by dev and prod `main()`.
///
/// `prepare` runs inside `tauri::Builder::setup` with the live `App` handle so the prod path can
/// resolve the Tauri resource directory (only available there) and spawn the bundled backend. It
/// returns the prepared sidecar and token/api-base injection script. SETUP mode also returns a
/// supervisor configuration that starts only after the webview exists, so the setup wizard can
/// drive the backend restart. In dev the backend is external and all fields are empty.
fn run_desktop<F>(prepare: F)
where
    F: FnOnce(&tauri::App) -> Result<PreparedDesktop, Box<dyn std::error::Error>> + Send + 'static,
{
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        // Empty until setup resolves the sidecar; setup swaps the live child in.
        .manage(Sidecar {
            state: Mutex::new(SidecarState::default()),
            #[cfg(not(debug_assertions))]
            supervisor: Mutex::new(None),
        })
        .setup(move |app| {
            let mut prepared = prepare(app)?;
            if let Some(c) = prepared.child.take() {
                if let Some(state) = app.try_state::<Sidecar>() {
                    if let Ok(mut guard) = state.state.lock() {
                        guard.child = Some(c);
                    }
                }
            }
            // Build the window programmatically so the init script (if any) runs BEFORE page load
            // (a declarative window + window.eval() in setup runs too late — the SPA has already
            // fired its first API calls).
            let mut builder =
                tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::default())
                    .title("FengYu")
                    .inner_size(1280.0, 820.0)
                    .min_inner_size(960.0, 640.0)
                    .resizable(true);
            if let Some(script) = prepared.init_script.as_deref() {
                builder = builder.initialization_script(script);
            }
            builder.build()?;

            #[cfg(not(debug_assertions))]
            if let Some(supervisor) = prepared.supervisor.take() {
                let handle = supervise_setup_restart(app.handle().clone(), supervisor);
                if let Some(state) = app.try_state::<Sidecar>() {
                    if let Ok(mut guard) = state.supervisor.lock() {
                        *guard = Some(handle);
                    }
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                // Kill the sidecar cleanly when the window closes. No-op in dev (holder is None).
                if let Some(state) = window.try_state::<Sidecar>() {
                    kill_sidecar(&state);
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running FengYu desktop");
}

// ── PROD-only sidecar machinery ─────────────────────────────────────────────
// All of the spawn/health/token code below is compiled out in dev. `cargo tauri build` (prod) is a
// release build, so `debug_assertions` is OFF and these are present; `cargo tauri dev` is a debug
// build, so `debug_assertions` is ON and they are absent — the dev binary never references the jar,
// spawns java, or generates a token.
//
// NOTE: `kill_sidecar` is intentionally UNCONDITIONAL (no cfg) — it is referenced from the shared
// `run_desktop` on_window_event closure compiled in BOTH paths. It is a safe no-op in dev because
// `Sidecar` holds `None` there (its `take()` returns None and it skips).

/// Kills the spawned sidecar (if any). No-op when `Sidecar` holds `None` (dev mode).
fn kill_sidecar(state: &State<Sidecar>) {
    if let Ok(mut guard) = state.state.lock() {
        guard.shutting_down = true;
        if let Some(mut child) = guard.child.take() {
            terminate_child(&mut child);
        }
    }

    #[cfg(not(debug_assertions))]
    if let Ok(mut guard) = state.supervisor.lock() {
        if let Some(handle) = guard.take() {
            let _ = handle.join();
        }
    }
}

fn terminate_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

/// Generates a simple per-launch token (UUID-like) without pulling a uuid crate.
#[cfg(not(debug_assertions))]
fn gen_token() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("zf-{:x}-{:x}", nanos, std::process::id())
}

/// Spawns the Java backend with `--port=24056 --token=<t>`, reads the bound port from its stdout
/// (`FENGYU_PORT=<n>`), and returns (child, port). The backend tries the fixed port first and
/// falls back to an OS-assigned port if it is taken, so the actual port is always read back here.
///
/// `layout` points at the bundled jar + plugins under the Tauri resource directory
/// (see `runtime_layout`).
#[cfg(not(debug_assertions))]
fn spawn_backend<F>(
    layout: &RuntimeLayout,
    token: &str,
    requested_port: u16,
    should_cancel: &F,
) -> Result<(Child, u16), String>
where
    F: Fn() -> bool,
{
    let jar = &layout.jar;
    if !jar.exists() {
        return Err(format!(
            "FengYu jar not found at {}. Build it and stage it at src-tauri/binaries/FengYu.jar (see desktop/README.md).",
            jar.display()
        ));
    }

    let official_plugins = &layout.plugins;

    let mut child = Command::new("java")
        .arg(format!(
            "-Dfengyu.plugins.official-directory={}",
            official_plugins.display()
        ))
        .arg("-cp")
        .arg(jar.as_os_str())
        .arg("fan.summer.fengyu.HeadlessLauncher")
        .arg(format!("--port={requested_port}"))
        .arg(format!("--token={}", token))
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("failed to spawn java: {e}"))?;

    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => {
            terminate_child(&mut child);
            return Err("no stdout on sidecar".into());
        }
    };
    let (tx, rx) = std::sync::mpsc::channel::<u16>();

    // Reader thread: scan stdout for FENGYU_PORT=, forward the rest for visibility.
    thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines().map_while(Result::ok) {
            if let Some(rest) = line.strip_prefix("FENGYU_PORT=") {
                if let Ok(p) = rest.trim().parse::<u16>() {
                    let _ = tx.send(p);
                }
            }
            println!("[backend] {line}");
        }
    });

    // Wait up to 30s for the port line while remaining cancellable during app shutdown.
    let deadline = Instant::now() + Duration::from_secs(30);
    let port = loop {
        if should_cancel() {
            terminate_child(&mut child);
            return Err("backend startup cancelled".into());
        }
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(port) => break port,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) if Instant::now() < deadline => {}
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                terminate_child(&mut child);
                return Err("backend did not report FENGYU_PORT within 30s".into());
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                terminate_child(&mut child);
                return Err("backend exited before reporting FENGYU_PORT".into());
            }
        }
    };

    Ok((child, port))
}

/// Polls GET /api/health until 200 or timeout.
#[cfg(not(debug_assertions))]
fn wait_for_health<F>(port: u16, token: &str, should_cancel: &F) -> Result<(), String>
where
    F: Fn() -> bool,
{
    let url = format!("http://127.0.0.1:{port}/api/health");
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        if should_cancel() {
            return Err("backend health check cancelled".into());
        }
        let resp = ureq::get(&url)
            .set("X-FengYu-Token", token)
            .timeout(Duration::from_secs(2))
            .call();
        if let Ok(r) = resp {
            if r.status() == 200 {
                return Ok(());
            }
        }
        thread::sleep(Duration::from_millis(300));
    }
    Err("backend health check timed out".into())
}

/// Probes GET /api/setup/status. Returns Ok(true) if in SETUP mode (not initialized).
#[cfg(not(debug_assertions))]
fn check_setup_mode(port: u16, token: &str) -> Result<bool, String> {
    let url = format!("http://127.0.0.1:{port}/api/setup/status");
    let resp = ureq::get(&url)
        .set("X-FengYu-Token", token)
        .timeout(Duration::from_secs(2))
        .call()
        .map_err(|e| format!("setup status request failed: {e}"))?;
    let body = resp.into_string().map_err(|e| format!("read body: {e}"))?;
    // Crude parse: SETUP mode if "initialized":false appears.
    Ok(body.contains("\"initialized\":false") || body.contains("\"initialized\": false"))
}

#[cfg(not(debug_assertions))]
struct StartedBackend {
    child: Child,
    port: u16,
    setup_mode: bool,
}

#[cfg(not(debug_assertions))]
fn start_backend<F>(
    layout: &RuntimeLayout,
    token: &str,
    requested_port: u16,
    should_cancel: &F,
) -> Result<StartedBackend, String>
where
    F: Fn() -> bool,
{
    let (mut child, port) = spawn_backend(layout, token, requested_port, should_cancel)?;
    if let Err(error) = wait_for_health(port, token, should_cancel) {
        terminate_child(&mut child);
        return Err(error);
    }
    let setup_mode = check_setup_mode(port, token).map_err(|error| {
        terminate_child(&mut child);
        error
    })?;
    if should_cancel() {
        terminate_child(&mut child);
        return Err("backend startup cancelled".into());
    }
    Ok(StartedBackend {
        child,
        port,
        setup_mode,
    })
}

#[cfg(not(debug_assertions))]
struct SetupSupervisor {
    layout: RuntimeLayout,
    token: String,
    port: u16,
}

#[cfg(not(debug_assertions))]
fn is_shutting_down(app: &tauri::AppHandle) -> bool {
    let state = app.state::<Sidecar>();
    state
        .state
        .lock()
        .map(|guard| guard.shutting_down)
        .unwrap_or(true)
}

#[cfg(not(debug_assertions))]
fn supervise_setup_restart(
    app: tauri::AppHandle,
    config: SetupSupervisor,
) -> std::thread::JoinHandle<()> {
    thread::spawn(move || {
        let status = loop {
            let polled = {
                let state = app.state::<Sidecar>();
                let mut guard = match state.state.lock() {
                    Ok(guard) => guard,
                    Err(_) => return,
                };
                if guard.shutting_down {
                    return;
                }
                let Some(child) = guard.child.as_mut() else {
                    return;
                };
                match child.try_wait() {
                    Ok(Some(status)) => {
                        guard.child.take();
                        Some(Ok(status))
                    }
                    Ok(None) => None,
                    Err(error) => Some(Err(error)),
                }
            };

            match polled {
                Some(Ok(status)) => break status,
                Some(Err(error)) => {
                    eprintln!("FATAL: failed to poll setup sidecar: {error}");
                    return;
                }
                None => thread::sleep(Duration::from_millis(200)),
            }
        };

        if !should_restart_setup(is_shutting_down(&app), status.code()) {
            if is_shutting_down(&app) {
                return;
            }
            eprintln!("FATAL: setup sidecar exited with code {:?}", status.code());
            return;
        }

        println!("[desktop] setup complete; restarting backend into APP mode");
        let should_cancel = || is_shutting_down(&app);
        let mut restarted =
            match start_backend(&config.layout, &config.token, config.port, &should_cancel) {
                Ok(backend) => backend,
                Err(error) => {
                    eprintln!("FATAL: failed to restart backend after setup: {error}");
                    return;
                }
            };

        if restarted.port != config.port {
            terminate_child(&mut restarted.child);
            eprintln!(
                "FATAL: restarted backend moved from port {} to {}; the webview endpoint cannot change",
                config.port, restarted.port
            );
            return;
        }
        if restarted.setup_mode {
            terminate_child(&mut restarted.child);
            eprintln!("FATAL: backend remained in SETUP mode after successful initialization");
            return;
        }

        let state = app.state::<Sidecar>();
        if let Ok(mut guard) = state.state.lock() {
            if guard.shutting_down {
                terminate_child(&mut restarted.child);
            } else {
                guard.child = Some(restarted.child);
                println!(
                    "[desktop] backend restarted in APP mode on port {}",
                    config.port
                );
            }
        };
    })
}

// ── main(): dev vs prod ──────────────────────────────────────────────────────

/// DEV entry (`cargo tauri dev`, a debug build). The backend is started externally (IDE /
/// `mvn spring-boot:run` on :24056); the webview reaches it same-origin via the Vite proxy.
/// Tauri only opens the window.
#[cfg(debug_assertions)]
fn main() {
    println!(
        "[desktop] dev mode: backend must be running externally on :24056 \
         (IDE / `mvn -pl FengYu spring-boot:run`). Tauri only opens the window; \
         API calls go through the Vite proxy."
    );
    run_desktop(|_app| {
        Ok(PreparedDesktop {
            child: None,
            init_script: None,
        })
    });
}

/// PROD entry (`cargo tauri build`, a release build). Spawns the bundled jar as a sidecar, waits
/// for health, handles the SETUP→APP restart loop, then injects the token/api-base into the webview.
#[cfg(not(debug_assertions))]
fn main() {
    run_desktop(|app| {
        // The bundled jar + plugins live under Tauri's platform resource directory, which is only
        // reachable through the App handle (set in tauri.conf.json `bundle.resources`).
        let resource_dir = app.path().resource_dir().map_err(|e| {
            Box::<dyn std::error::Error>::from(format!(
                "failed to resolve Tauri resource directory: {e}"
            ))
        })?;
        let layout = runtime_layout(&resource_dir);
        let token = gen_token();
        let never_cancel = || false;
        let started = start_backend(&layout, &token, 24056, &never_cancel)
            .map_err(|error| std::io::Error::other(format!("failed to start backend: {error}")))?;
        let action = startup_action(started.setup_mode, started.port);

        // Injected before any page script runs. The frontend reads these globals
        // (see frontend/src/api/config.ts): __FENGYU_API_BASE__ (absolute backend URL — the backend
        // defaults to a fixed port but may fall back to an OS-assigned one, so the actual port read
        // back from stdout is used) and __FENGYU_TOKEN__.
        let init_script = format!(
            "window.__FENGYU_TOKEN__ = '{token}'; window.__FENGYU_PORT__ = {port}; \
             window.__FENGYU_API_BASE__ = 'http://127.0.0.1:{port}';",
            port = started.port,
        );

        let supervisor = match action {
            StartupAction::ShowWindow => None,
            StartupAction::ShowWindowAndSupervise { port } => {
                println!("[desktop] backend in SETUP mode; opening setup wizard");
                Some(SetupSupervisor {
                    layout,
                    token,
                    port,
                })
            }
        };

        Ok(PreparedDesktop {
            child: Some(started.child),
            init_script: Some(init_script),
            supervisor,
        })
    });
}

#[cfg(test)]
mod tests {
    use super::{runtime_layout, should_restart_setup, startup_action, StartupAction};
    use std::path::Path;

    #[test]
    fn runtime_layout_uses_tauri_resource_directory() {
        let layout = runtime_layout(Path::new("/app/resources"));
        assert_eq!(layout.jar, Path::new("/app/resources/binaries/FengYu.jar"));
        assert_eq!(layout.plugins, Path::new("/app/resources/plugins"));
    }

    #[test]
    fn app_mode_shows_the_window_without_supervision() {
        assert_eq!(startup_action(false, 24056), StartupAction::ShowWindow);
    }

    #[test]
    fn setup_mode_shows_the_window_and_supervises_the_same_port() {
        assert_eq!(
            startup_action(true, 43123),
            StartupAction::ShowWindowAndSupervise { port: 43123 }
        );
    }

    #[test]
    fn shutdown_prevents_a_setup_restart() {
        assert!(!should_restart_setup(true, Some(0)));
    }

    #[test]
    fn successful_setup_exit_restarts_only_while_running() {
        assert!(should_restart_setup(false, Some(0)));
        assert!(!should_restart_setup(false, Some(1)));
        assert!(!should_restart_setup(false, None));
    }
}
