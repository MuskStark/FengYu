// Prevents an extra console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

use tauri::{Manager, State};

/// Holds the spawned Java sidecar so we can kill it on exit.
struct Sidecar(Mutex<Option<Child>>);

/// Generates a simple per-launch token (UUID-like) without pulling a uuid crate.
fn gen_token() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("zf-{:x}-{:x}", nanos, std::process::id())
}

/// Resolves the ZhiFlow jar path. Phase 1 dev: the jar is copied to `binaries/ZhiFlow.jar`
/// next to the executable (see desktop/README.md). Later phases bundle it as a Tauri sidecar.
fn jar_path() -> std::path::PathBuf {
    // During `cargo tauri dev` the CWD is src-tauri/. Look for binaries/ZhiFlow.jar there,
    // falling back to the repo target dir for convenience.
    let candidates = [
        "binaries/ZhiFlow.jar",
        "../../ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar",
    ];
    for c in candidates {
        let p = std::path::PathBuf::from(c);
        if p.exists() {
            return p;
        }
    }
    std::path::PathBuf::from("binaries/ZhiFlow.jar")
}

/// Spawns the Java backend with `--port=24056 --token=<t>`, reads the bound port from its stdout
/// (`ZHIFLOW_PORT=<n>`), and returns (child, port). The backend tries the fixed port first and
/// falls back to an OS-assigned port if it is taken, so the actual port is always read back here.
fn spawn_backend(token: &str) -> Result<(Child, u16), String> {
    let jar = jar_path();
    if !jar.exists() {
        return Err(format!(
            "ZhiFlow jar not found at {}. Build it and copy it to src-tauri/binaries/ZhiFlow.jar (see desktop/README.md).",
            jar.display()
        ));
    }

    let mut child = Command::new("java")
        .arg("-cp")
        .arg(jar.as_os_str())
        .arg("fan.summer.zhiflow.HeadlessLauncher")
        .arg("--port=24056")
        .arg(format!("--token={}", token))
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("failed to spawn java: {e}"))?;

    let stdout = child.stdout.take().ok_or("no stdout on sidecar")?;
    let (tx, rx) = std::sync::mpsc::channel::<u16>();

    // Reader thread: scan stdout for ZHIFLOW_PORT=, forward the rest for visibility.
    thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines().map_while(Result::ok) {
            if let Some(rest) = line.strip_prefix("ZHIFLOW_PORT=") {
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
        .map_err(|_| "backend did not report ZHIFLOW_PORT within 30s".to_string())?;

    Ok((child, port))
}

/// Polls GET /api/health until 200 or timeout.
fn wait_for_health(port: u16, token: &str) -> Result<(), String> {
    let url = format!("http://127.0.0.1:{port}/api/health");
    let deadline = Instant::now() + Duration::from_secs(30);
    while Instant::now() < deadline {
        let resp = ureq::get(&url)
            .set("X-ZhiFlow-Token", token)
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

fn main() {
    let token = gen_token();

    // The backend may start in SETUP mode (first launch, no datasource.properties).
    // When the setup wizard completes, it exits with code SETUP_DONE (0) to signal
    // us to restart it into APP mode. We loop: spawn → wait for health → if it exits
    // with 0 and we haven't entered APP mode yet, respawn.
    let mut entered_app_mode = false;
    let (child, port) = loop {
        let (c, p) = match spawn_backend(&token) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("FATAL: {e}");
                std::process::exit(1);
            }
        };
        if let Err(e) = wait_for_health(p, &token) {
            eprintln!("FATAL: {e}");
            let mut cc = c;
            let _ = cc.kill();
            std::process::exit(1);
        }
        // If we already entered APP mode on a previous iteration and the backend exited,
        // that's an unexpected crash — don't loop forever.
        if entered_app_mode {
            eprintln!("FATAL: backend exited unexpectedly after entering APP mode");
            std::process::exit(1);
        }
        // Check whether the backend is in setup mode by probing /api/setup/status.
        match check_setup_mode(p, &token) {
            Ok(true) => {
                // SETUP mode: wait for the sidecar to exit (it will exit 0 when done),
                // then loop to respawn into APP mode.
                println!("[desktop] backend in SETUP mode; waiting for wizard to complete…");
                let mut waiter = c;
                let status = waiter.wait().expect("failed to wait for setup sidecar");
                if status.code() == Some(0) {
                    println!("[desktop] setup complete; restarting backend into APP mode");
                    entered_app_mode = true;
                    continue; // respawn
                } else {
                    eprintln!("FATAL: setup sidecar exited with code {:?}", status.code());
                    std::process::exit(1);
                }
            }
            Ok(false) => break (c, p), // APP mode — proceed to window
            Err(e) => {
                // Probe failed — assume APP mode and proceed (best effort).
                eprintln!("[desktop] could not determine setup mode ({}); assuming APP", e);
                break (c, p);
            }
        }
    };

    // Injected before any page script runs. The frontend reads these globals
    // (see frontend/src/api/config.ts): __ZHIFLOW_API_BASE__ (absolute backend URL — the backend
    // defaults to a fixed port but may fall back to an OS-assigned one, so the actual port read
    // back from stdout is used) and __ZHIFLOW_TOKEN__.
    let init_script = format!(
        "window.__ZHIFLOW_TOKEN__ = '{token}'; window.__ZHIFLOW_PORT__ = {port}; \
         window.__ZHIFLOW_API_BASE__ = 'http://127.0.0.1:{port}';"
    );

    tauri::Builder::default()
        .manage(Sidecar(Mutex::new(Some(child))))
        .setup(move |app| {
            // Build the window programmatically so the init script runs BEFORE page load
            // (a declarative window + window.eval() in setup runs too late — the SPA has
            // already fired its first API calls).
            tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::default(),
            )
            .title("ZhiFlow")
            .inner_size(1280.0, 820.0)
            .min_inner_size(960.0, 640.0)
            .resizable(true)
            .initialization_script(&init_script)
            .build()?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                // Kill the sidecar cleanly when the window closes.
                if let Some(state) = window.try_state::<Sidecar>() {
                    kill_sidecar(&state);
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running ZhiFlow desktop");
}

fn kill_sidecar(state: &State<Sidecar>) {
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
        }
    }
}

/// Probes GET /api/setup/status. Returns Ok(true) if in SETUP mode (not initialized).
fn check_setup_mode(port: u16, token: &str) -> Result<bool, String> {
    let url = format!("http://127.0.0.1:{port}/api/setup/status");
    let resp = ureq::get(&url)
        .set("X-ZhiFlow-Token", token)
        .timeout(Duration::from_secs(2))
        .call()
        .map_err(|e| format!("setup status request failed: {e}"))?;
    let body = resp.into_string().map_err(|e| format!("read body: {e}"))?;
    // Crude parse: SETUP mode if "initialized":false appears.
    Ok(body.contains("\"initialized\":false") || body.contains("\"initialized\": false"))
}
