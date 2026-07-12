// Prevents an extra console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// `Child` is shared by both build paths (the Sidecar holder + run_desktop signature).
use std::process::Child;
use std::sync::Mutex;

use tauri::{Manager, State};

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

/// Holds the spawned Java sidecar so we can kill it on exit. `None` in dev (no sidecar).
struct Sidecar(Mutex<Option<Child>>);

/// Builds the window and runs the Tauri event loop. Shared by dev and prod `main()`.
///
/// - `child`: the spawned backend process to kill on window close. `None` in dev (backend is
///   external; nothing to kill).
/// - `init_script`: the `window.__FENGYU_TOKEN__` / `__FENGYU_API_BASE__` injection, run before
///   any page script. `None` in dev (the webview talks to the backend same-origin via Vite proxy,
///   reading the token from Vite env; no injection needed).
fn run_desktop(child: Option<Child>, init_script: Option<String>) {
    tauri::Builder::default()
        .manage(Sidecar(Mutex::new(child)))
        .setup(move |app| {
            // Build the window programmatically so the init script (if any) runs BEFORE page load
            // (a declarative window + window.eval() in setup runs too late — the SPA has already
            // fired its first API calls).
            let mut builder = tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::default(),
            )
            .title("FengYu")
            .inner_size(1280.0, 820.0)
            .min_inner_size(960.0, 640.0)
            .resizable(true);
            if let Some(script) = init_script.as_deref() {
                builder = builder.initialization_script(script);
            }
            builder.build()?;
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
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
        }
    }
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

/// Resolves the FengYu jar path. The jar is copied to `binaries/FengYu.jar` next to the
/// executable for the prod bundle (see desktop/README.md).
#[cfg(not(debug_assertions))]
fn jar_path() -> std::path::PathBuf {
    let candidates = [
        "binaries/FengYu.jar",
        "../../FengYu/target/FengYu-4.0.0-SNAPSHOT.jar",
    ];
    for c in candidates {
        let p = std::path::PathBuf::from(c);
        if p.exists() {
            return p;
        }
    }
    std::path::PathBuf::from("binaries/FengYu.jar")
}

/// Spawns the Java backend with `--port=24056 --token=<t>`, reads the bound port from its stdout
/// (`FENGYU_PORT=<n>`), and returns (child, port). The backend tries the fixed port first and
/// falls back to an OS-assigned port if it is taken, so the actual port is always read back here.
#[cfg(not(debug_assertions))]
fn spawn_backend(token: &str) -> Result<(Child, u16), String> {
    let jar = jar_path();
    if !jar.exists() {
        return Err(format!(
            "FengYu jar not found at {}. Build it and copy it to src-tauri/binaries/FengYu.jar (see desktop/README.md).",
            jar.display()
        ));
    }

    let mut child = Command::new("java")
        .arg("-cp")
        .arg(jar.as_os_str())
        .arg("fan.summer.fengyu.HeadlessLauncher")
        .arg("--port=24056")
        .arg(format!("--token={}", token))
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("failed to spawn java: {e}"))?;

    let stdout = child.stdout.take().ok_or("no stdout on sidecar")?;
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

    // Wait up to 30s for the port line.
    let port = rx
        .recv_timeout(Duration::from_secs(30))
        .map_err(|_| "backend did not report FENGYU_PORT within 30s".to_string())?;

    Ok((child, port))
}

/// Polls GET /api/health until 200 or timeout.
#[cfg(not(debug_assertions))]
fn wait_for_health(port: u16, token: &str) -> Result<(), String> {
    let url = format!("http://127.0.0.1:{port}/api/health");
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
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

/// Brings the backend up in APP mode, handling the first-launch SETUP detour, and returns the
/// live `(child, port)` ready for the window.
///
/// The backend may start in SETUP mode (first launch, no datasource.properties). When the setup
/// wizard completes, it exits with code 0 to signal a restart into APP mode. This loops:
/// spawn → wait for health → probe `/api/setup/status` → if SETUP, wait for exit(0) and respawn;
/// if APP, return. Any failure is fatal (`process::exit(1)`), so the caller always gets a ready
/// backend. Guards against an infinite loop if an already-APP backend exits unexpectedly.
#[cfg(not(debug_assertions))]
fn run_backend_until_app_mode(token: &str) -> (Child, u16) {
    let mut entered_app_mode = false;
    loop {
        let (child, port) = match spawn_backend(token) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("FATAL: {e}");
                std::process::exit(1);
            }
        };
        if let Err(e) = wait_for_health(port, token) {
            eprintln!("FATAL: {e}");
            let mut cc = child;
            let _ = cc.kill();
            std::process::exit(1);
        }
        // If we already entered APP mode on a previous iteration and the backend exited, that's an
        // unexpected crash — don't loop forever.
        if entered_app_mode {
            eprintln!("FATAL: backend exited unexpectedly after entering APP mode");
            std::process::exit(1);
        }
        match check_setup_mode(port, token) {
            Ok(true) => {
                println!("[desktop] backend in SETUP mode; waiting for wizard to complete…");
                let mut waiter = child;
                let status = waiter.wait().expect("failed to wait for setup sidecar");
                if status.code() == Some(0) {
                    println!("[desktop] setup complete; restarting backend into APP mode");
                    entered_app_mode = true;
                    continue; // respawn into APP mode
                }
                eprintln!("FATAL: setup sidecar exited with code {:?}", status.code());
                std::process::exit(1);
            }
            Ok(false) => return (child, port), // APP mode — proceed to window
            Err(e) => {
                eprintln!("[desktop] could not determine setup mode ({e}); assuming APP");
                return (child, port);
            }
        }
    }
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
    run_desktop(None, None);
}

/// PROD entry (`cargo tauri build`, a release build). Spawns the bundled jar as a sidecar, waits
/// for health, handles the SETUP→APP restart loop, then injects the token/api-base into the webview.
#[cfg(not(debug_assertions))]
fn main() {
    let token = gen_token();
    let (child, port) = run_backend_until_app_mode(&token);

    // Injected before any page script runs. The frontend reads these globals
    // (see frontend/src/api/config.ts): __FENGYU_API_BASE__ (absolute backend URL — the backend
    // defaults to a fixed port but may fall back to an OS-assigned one, so the actual port read
    // back from stdout is used) and __FENGYU_TOKEN__.
    let init_script = format!(
        "window.__FENGYU_TOKEN__ = '{token}'; window.__FENGYU_PORT__ = {port}; \
         window.__FENGYU_API_BASE__ = 'http://127.0.0.1:{port}';"
    );

    run_desktop(Some(child), Some(init_script));
}
