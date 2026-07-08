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

/// Spawns the Java backend with `--port=0 --token=<t>`, reads the chosen port from its stdout
/// (`ZHIFLOW_PORT=<n>`), and returns (child, port).
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
        .arg("--port=0")
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

    // Spawn + wait for readiness before the window loads.
    let (child, port) = match spawn_backend(&token) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("FATAL: {e}");
            std::process::exit(1);
        }
    };
    if let Err(e) = wait_for_health(port, &token) {
        eprintln!("FATAL: {e}");
        // Best-effort kill before exiting.
        let mut c = child;
        let _ = c.kill();
        std::process::exit(1);
    }

    // Injected before any page script runs. The frontend reads these globals
    // (see frontend/src/api/config.ts): __ZHIFLOW_API_BASE__ (absolute backend URL, since the
    // sidecar port is random and the Vite dev proxy can't target it) and __ZHIFLOW_TOKEN__.
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
