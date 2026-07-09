# Tauri dev/prod 后端通信分流 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 dev 模式让 Tauri 不启动 Java sidecar,改由开发者外部启动后端(IDE / spring-boot:run)经 Vite proxy 通信,支持热重载;prod 模式保持 jar sidecar 启动不变。

**Architecture:** 用 Rust 编译期 `#[cfg(dev)]` / `#[cfg(not(dev))]` 把 `main.rs` 拆成两条路径。`#[cfg(dev)]` 版 `main()` 跳过所有 sidecar/token/health/restart-loop 逻辑,只开窗口加载 Vite dev server(经 proxy 到外部后端 24056);`#[cfg(not(dev))]` 保留现有完整 sidecar 管理。窗口构建(`tauri::Builder` + `WebviewWindowBuilder`)抽成公共函数 `run_desktop`,两个 main 分别调用,避免重复。前端零改动(Vite proxy + `config.ts` 已容错)。

**Tech Stack:** Tauri 2.0,Rust(`tauri-build` 2.6.3 的 `cfg(dev)` 机制),`cargo tauri dev` / `cargo tauri build`。

## Global Constraints

- **`#[cfg(dev)]` 是 `tauri-build` 通过 `cargo:rustc-cfg=dev` 设置的编译期标志**(已验证:`tauri-build` 2.6.3 源码 `lib.rs:519` `cfg_alias("dev", is_dev())`,dev 模式编译时 `#[cfg(dev)]` 为真)。不是 Cargo feature,不要新增 feature。
- **`cargo tauri dev` 触发 `cfg(dev)`**;`cargo tauri build` 不触发(走 `#[cfg(not(dev))]`)。
- **前端零改动**:`frontend/src/api/config.ts`(`getApiBase`/`getToken` 在无 `window` 全局时回退 env / 空 base)、`frontend/vite.config.ts`(`/api`、`/plugin-ui` proxy → `localhost:24056`)已就绪。本计划不碰前端任何文件。
- **dev 下后端端口固定 24056**(Vite proxy 固定指向);若被占,后端 fallback 到随机端口会导致 proxy 失连——开发者需确保 24056 可用。
- **dev 下 token 可空**:后端用 IDE/spring-boot:run 启动且不传 `--token` 时,`zhiflow.auth.token` 为空 → `TokenAuthFilter` 放行所有请求;前端 `getToken()` 回退空。一致。
- **`desktop/src-tauri/Cargo.toml` 不需改**:`tauri-build = { version = "2.0", features = [] }` 已支持;`[features]` 现有的 `custom-protocol = ["tauri/custom-protocol"]` 保留(prod 用,与本次无关)。
- **dev 下 setup 向导完成后需手动重启后端**:Tauri 不监控外部进程退出,向导 `System.exit(0)` 后前端会掉线,需手动重启后端进 APP 模式(文档说明)。
- **提交信息用 emoji 前缀**(与现有约定一致):`✨ feat`/`♻️ refactor`/`📝 docs`。
- **验证手段**:Tauri 入口是 GUI 进程,无单元测试可写。本计划的"测试"是 `cargo check` 在 dev 与 prod 两种 cfg 下都编译通过(确认两条路径语法/类型正确),以及确认 sidecar 相关符号在 dev 编译下不出现 unused 错误。端到端 GUI 验证(`cargo tauri dev` 真开窗口)由人工/用户做,计划末尾给出验证步骤。

---

## 文件结构

| 文件 | 改动 |
|---|---|
| `desktop/src-tauri/src/main.rs` | 修改:用 `#[cfg(dev)]`/`#[cfg(not(dev))]` 拆 main;抽 `run_desktop` 公共函数;sidecar 相关函数/imports 加 `#[cfg(not(dev))]` |
| `desktop/README.md` | 修改:重写 "dev run" 章节(外部启动后端 + 手动重启说明),保留 prod 步骤 |

---

## Task 1: main.rs 用 cfg(dev) 拆分 dev/prod 后端路径

**Files:**
- Modify: `desktop/src-tauri/src/main.rs`(全文重写)

**Interfaces:**
- Consumes: 无(纯入口改造)
- Produces:`#[cfg(dev)]` 版 `main()`(只开窗口)、`#[cfg(not(dev))]` 版 `main()`(保留 sidecar 逻辑)、公共 `run_desktop(child: Option<Child>, init_script: Option<String>)`

**关键设计决策(实现时严格遵循):**

1. **公共尾部 `run_desktop`**:把窗口构建(`tauri::Builder` + `setup` 里的 `WebviewWindowBuilder` + `on_window_event`)抽成一个函数,两个 main 调用它,避免窗口代码写两份。签名:
   ```rust
   #[allow(dead_code)]  // prod 的 child 在 dev 不用,但为统一签名保留
   fn run_desktop(child: Option<Child>, init_script: Option<String>)
   ```
   - `Sidecar(Mutex<Option<Child>>)` 用 `Sidecar(Mutex::new(child))` 构造(dev 传 None,prod 传 Some(child))。
   - `setup` 闭包里 `.initialization_script(&init_script)` 改为按 `Option` 处理:`init_script.as_deref()`(Some 时注入脚本,None 时无脚本)。注意 `initialization_script` 接收 `&str`,需用条件构造或 `.initialization_script(script.as_str())`(用 `Option::as_deref` + 在 None 时跳过该 builder 调用)。
   - `on_window_event` 的 `kill_sidecar` 在 `Sidecar` 内为 None 时是 no-op(现有 `kill_sidecar` 已 `take()` None 跳过),无需改。

2. **`#[cfg(dev)] fn main()`**:
   ```rust
   #[cfg(dev)]
   fn main() {
       println!("[desktop] dev mode: backend is started externally (IDE / mvn spring-boot:run on :24056); Tauri only opens the window.");
       run_desktop(None, None);
   }
   ```

3. **`#[cfg(not(dev))] fn main()`**:把现有 main 的 token + spawn + restart-loop 逻辑保留,最后调用 `run_desktop(Some(child), Some(init_script))`。即把现有 `main()` 第 108-160 行(sidecar 准备)+ 162-169 行(init_script 构造)保留,把 171-199 行(tauri::Builder 块)替换为 `run_desktop(Some(child), Some(init_script));`。

4. **sidecar 相关函数加 cfg**:`gen_token`、`jar_path`、`spawn_backend`、`wait_for_health`、`check_setup_mode`、`kill_sidecar` 全部加 `#[cfg(not(dev))]`(它们只在 prod main 用到)。`Sidecar` 结构体保留(两个 main 都用)。

5. **imports 加 cfg**:`use std::io::{BufRead, BufReader};`、`use std::process::{Child, Command, Stdio};`、`use std::thread;`、`use std::time::{Duration, Instant};` 这几个只在 sidecar 函数用到,加 `#[cfg(not(dev))]`(可整段用 `#[cfg(not(dev))] use ...;`)。但 `Child` 类型出现在 `Sidecar(Mutex<Option<Child>>)` 和 `run_desktop` 签名里(两 main 共用),所以 `Child` 必须无条件可用。**处理方式**:`use std::process::Child;` 保留无条件;`use std::process::{Command, Stdio};` 加 cfg(只在 spawn_backend 用)。同理 `BufRead`/`BufReader`(spawn_backend 用)、`thread`/`Duration`/`Instant`(spawn_backend/wait_for_health/check_setup_mode 用)加 cfg。

   具体(实现时按此):
   ```rust
   use std::process::Child;
   use std::sync::Mutex;
   use tauri::{Manager, State};

   #[cfg(not(dev))]
   use std::io::{BufRead, BufReader};
   #[cfg(not(dev))]
   use std::process::{Command, Stdio};
   #[cfg(not(dev))]
   use std::thread;
   #[cfg(not(dev))]
   use std::time::{Duration, Instant};
   ```

- [ ] **Step 1: 全文重写 main.rs**

替换 `desktop/src-tauri/src/main.rs` 全文为:

```rust
// Prevents an extra console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// `Child` is shared by both build paths (the Sidecar holder + run_desktop signature).
use std::process::Child;
use std::sync::Mutex;

use tauri::{Manager, State};

// The sidecar machinery below is PROD-only. In dev (`cargo tauri dev`) the backend is started
// externally (IDE / `mvn spring-boot:run` on :24056) and the webview reaches it via the Vite
// proxy — so none of the spawn/health/token code is compiled into the dev binary.
#[cfg(not(dev))]
use std::io::{BufRead, BufReader};
#[cfg(not(dev))]
use std::process::{Command, Stdio};
#[cfg(not(dev))]
use std::thread;
#[cfg(not(dev))]
use std::time::{Duration, Instant};

/// Holds the spawned Java sidecar so we can kill it on exit. `None` in dev (no sidecar).
struct Sidecar(Mutex<Option<Child>>);

/// Builds the window and runs the Tauri event loop. Shared by dev and prod `main()`.
///
/// - `child`: the spawned backend process to kill on window close. `None` in dev (backend is
///   external; nothing to kill).
/// - `init_script`: the `window.__ZHIFLOW_TOKEN__` / `__ZHIFLOW_API_BASE__` injection, run before
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
            .title("ZhiFlow")
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
        .expect("error while running ZhiFlow desktop");
}

// ── PROD-only sidecar machinery ─────────────────────────────────────────────
// All of the following is compiled out in dev. `cargo tauri build` (prod) sets the `dev` cfg off,
// so these are present; `cargo tauri dev` sets `cfg(dev)` on, so they are absent and the dev
// binary never references the jar / spawns java / generates a token.

#[cfg(not(dev))]
fn kill_sidecar(state: &State<Sidecar>) {
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
        }
    }
}

/// Generates a simple per-launch token (UUID-like) without pulling a uuid crate.
#[cfg(not(dev))]
fn gen_token() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("zf-{:x}-{:x}", nanos, std::process::id())
}

/// Resolves the ZhiFlow jar path. The jar is copied to `binaries/ZhiFlow.jar` next to the
/// executable for the prod bundle (see desktop/README.md).
#[cfg(not(dev))]
fn jar_path() -> std::path::PathBuf {
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
#[cfg(not(dev))]
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
#[cfg(not(dev))]
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

/// Probes GET /api/setup/status. Returns Ok(true) if in SETUP mode (not initialized).
#[cfg(not(dev))]
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

// ── main(): dev vs prod ──────────────────────────────────────────────────────

/// DEV entry (`cargo tauri dev`). The backend is started externally (IDE / `mvn spring-boot:run`
/// on :24056); the webview reaches it same-origin via the Vite proxy. Tauri only opens the window.
#[cfg(dev)]
fn main() {
    println!(
        "[desktop] dev mode: backend must be running externally on :24056 \
         (IDE / `mvn -pl ZhiFlow spring-boot:run`). Tauri only opens the window; \
         API calls go through the Vite proxy."
    );
    run_desktop(None, None);
}

/// PROD entry (`cargo tauri build`). Spawns the bundled jar as a sidecar, waits for health,
/// handles the SETUP→APP restart loop, then injects the token/api-base into the webview.
#[cfg(not(dev))]
fn main() {
    let token = gen_token();

    // The backend may start in SETUP mode (first launch, no datasource.properties).
    // When the setup wizard completes, it exits with code SETUP_DONE (0) to signal us to restart
    // it into APP mode. We loop: spawn → wait for health → if it exits with 0 and we haven't
    // entered APP mode yet, respawn.
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
        // If we already entered APP mode on a previous iteration and the backend exited, that's an
        // unexpected crash — don't loop forever.
        if entered_app_mode {
            eprintln!("FATAL: backend exited unexpectedly after entering APP mode");
            std::process::exit(1);
        }
        // Check whether the backend is in setup mode by probing /api/setup/status.
        match check_setup_mode(p, &token) {
            Ok(true) => {
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

    run_desktop(Some(child), Some(init_script));
}
```

- [ ] **Step 2: 验证 prod 路径编译(cargo check 默认/dev off)**

注意:`cargo check`(不带 `cargo tauri dev`)默认不走 tauri 的 dev cfg 设置,即编译的是 `#[cfg(not(dev))]` 路径。但保险起见,显式验证。由于 `cfg(dev)` 是由 `tauri-build` 在 `cargo tauri dev` 经 `TAURI_DEV` 注入的,直接 `cargo check` 不会设置它 → 编译 prod 路径(期望)。

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ/desktop/src-tauri && cargo check --offline 2>&1 | tail -20
```
Expected: `Finished` (无 error/warning)。这验证 **prod 路径**(`not(dev)`)编译干净——所有 sidecar 函数被编译、`#[cfg(dev)] fn main` 被排除、`#[cfg(not(dev))] fn main` 生效。

**若 `--offline` 因缺缓存失败,改用 `cargo check 2>&1 | tail -20`(允许联网)。**

- [ ] **Step 3: 验证 dev 路径编译(`RUSTFLAGS="--cfg dev"`)**

由于 `cfg(dev)` 平时只由 `cargo tauri dev` 注入,普通 `cargo check` 不会设它。为验证 dev 路径(`#[cfg(dev)]` 版 main、sidecar 函数被排除)编译干净,用 `RUSTFLAGS="--cfg dev"` 模拟。但注意:tauri 的 `check-cfg` 已声明 `cfg(dev)` 合法(见 spec 验证),所以不会报 "unexpected cfg"。

Run:
```bash
cd /Users/phoebej/Develop/Java/SwissKitJ/desktop/src-tauri && RUSTFLAGS="--cfg dev" cargo check --offline 2>&1 | tail -25
```
Expected: `Finished`,**无 unused-import / unused-function 错误**。这验证:
- `#[cfg(dev)] fn main()` 生效(只调 `run_desktop(None, None)`)。
- `#[cfg(not(dev))]` 的 `gen_token`/`jar_path`/`spawn_backend`/`wait_for_health`/`check_setup_mode`/`kill_sidecar` 全部被排除(dev 二进制不含它们)。
- `#[cfg(not(dev))] use ...`(BufRead/BufReader/Command/Stdio/thread/Duration/Instant)被排除,无 unused import warning。
- `run_desktop` 在 dev 下 `child` 参数为 `None`、`kill_sidecar` 调用仍在(但 `#[cfg(not(dev))]` 的 `kill_sidecar` 不存在)→ **这里会编译失败**,因为 `run_desktop` 的 `on_window_event` 闭包调用了 `kill_sidecar`,而 dev 下 `kill_sidecar` 被 cfg 排除了。

**修正方案(实现时若 Step 3 报 `kill_sidecar` 找不到):** 把 `kill_sidecar` 的 `#[cfg(not(dev))]` 去掉,改为无条件定义(它对 `Sidecar(Mutex<Option<Child>>)` 内 None 时是 no-op,dev 下也能安全调用)。即 `kill_sidecar` 保留无条件,只有真正 spawn 相关的 5 个函数(`gen_token`/`jar_path`/`spawn_backend`/`wait_for_health`/`check_setup_mode`)保持 `#[cfg(not(dev))]`。

按此修正 `kill_sidecar` 的注解(去掉其 `#[cfg(not(dev))]`),重跑 Step 3 验证通过。

- [ ] **Step 4: 提交**

```bash
git add desktop/src-tauri/src/main.rs
git commit -m "♻️ refactor(desktop): split dev/prod backend path via cfg(dev) — dev skips sidecar"
```

---

## Task 2: 更新 desktop/README.md dev/prod 流程文档

**Files:**
- Modify: `desktop/README.md`(全文重写)

**Interfaces:**
- Produces: 准确的 dev/prod 运行说明

- [ ] **Step 1: 重写 desktop/README.md**

替换 `desktop/README.md` 全文为:

```markdown
# ZhiFlow Desktop (Tauri 2.0)

The desktop shell wraps the identical Vue frontend in a native window. In **dev** mode it only
opens the window — the backend is started externally and reached via the Vite proxy (enabling
hot-reload). In **prod** mode it sidecar-launches the bundled Java jar, manages its lifecycle, and
injects the backend URL + token into the webview.

The dev/prod split is compile-time: `cargo tauri dev` sets Rust's `cfg(dev)` (via `tauri-build`),
so the sidecar spawn code is compiled out of the dev binary entirely.

## Prerequisites

- Rust toolchain (`rustup`, `cargo`) — https://rustup.rs
- Tauri CLI: `cargo install tauri-cli --version '^2.0'`
- A JRE on `PATH` (prod: the sidecar runs `java -cp ZhiFlow.jar ...`; dev: only if you run the
  backend as a jar)
- Node + the `frontend/` deps installed (`cd ../frontend && npm install`)

## Dev run (hot-reload backend)

The desktop does NOT start the backend in dev. Start it yourself, then open the window.

1. Start the backend on the fixed port **24056** (the Vite proxy targets it):
   - IDE: run `fan.summer.zhiflow.HeadlessLauncher` (or `AiApplication`) — add `--port=24056` to the
     run config if needed.
   - Or Maven: `mvn -pl ZhiFlow spring-boot:run` (binds 24056 by default via `DEFAULT_PORT`).
2. From `desktop/src-tauri/`, run: `cargo tauri dev`
   - `beforeDevCommand` starts the Vite dev server (`frontend`, port 5173).
   - Tauri opens the window loading Vite; the frontend's `/api/*` and `/plugin-ui/*` requests are
     proxied to `localhost:24056`.

**Notes:**
- **Token:** if you start the backend without `--token`, auth is disabled (`TokenAuthFilter` allows
  all requests). The frontend reads an empty token from Vite env. No setup needed.
- **Backend code changes:** just restart the backend process — no rebuild/re-jar needed (that's the
  whole point of dev mode). The Tauri window can stay open.
- **First-launch setup wizard:** on first run (no `datasource.properties`) the backend boots in
  SETUP mode and the wizard appears. After you complete it the backend calls `System.exit(0)` — in
  dev the desktop does NOT supervise the backend, so **manually restart the backend once** to enter
  APP mode (subsequent launches skip the wizard since `datasource.properties` now exists).
- **Port conflict:** the backend must use 24056 (the Vite proxy is hardcoded to it). If 24056 is
  taken, free it and restart the backend.

## Prod build (bundled jar sidecar)

1. Build the backend jar:
   `mvn -f ZhiFlow-Api/pom.xml install -DskipTests && mvn -f plugin-markdown/pom.xml install -DskipTests && mvn -f ZhiFlow/pom.xml package -DskipTests`
2. Copy the jar so the sidecar can find it:
   `mkdir -p src-tauri/binaries && cp ../ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar src-tauri/binaries/ZhiFlow.jar`
3. From `desktop/src-tauri/`, run: `cargo tauri build`
   - The prod binary (`#[cfg(not(dev))]`) spawns `java -cp ZhiFlow.jar HeadlessLauncher
     --port=24056 --token=<generated>`, waits on `/api/health`, handles the SETUP→APP restart loop,
     and injects `window.__ZHIFLOW_TOKEN__` / `window.__ZHIFLOW_API_BASE__`.

## Lifecycle (prod only)

- On launch: spawn java → parse `ZHIFLOW_PORT` → poll `/api/health` (30s) → probe
  `/api/setup/status` → (SETUP) wait for exit 0 + respawn into APP → (APP) inject token/URL →
  load webview.
- On window close: the sidecar Java process is killed.

## Out of scope

Signed installers, a per-platform bundled JRE, and Tauri `externalBin` sidecar packaging are a
later phase.
```

- [ ] **Step 2: 提交**

```bash
git add desktop/README.md
git commit -m "📝 docs(desktop): rewrite dev/prod run guide (dev uses external backend + Vite proxy)"
```

---

## Task 3: 端到端人工验证清单(无代码改动,记录验证结果)

**Files:** 无(纯验证)

**Interfaces:** 无

这一步由执行者(或最终交回用户)实际跑一遍两种模式,记录结果。GUI 进程无法自动化测试,所以这是交付前的必要人工 gate。

- [ ] **Step 1: dev 模式验证**

前提:后端外部启动。
1. 启动后端:`cd /Users/phoebej/Develop/Java/SwissKitJ && mvn -pl ZhiFlow spring-boot:run`(若首次,会进 SETUP 模式;在向导选 H2 默认路径 → 完成 → 后端退出 → 重启后端进 APP 模式)。
2. 确认后端在 24056 监听(curl 或日志 `ZHIFLOW_PORT=24056`)。
3. 开窗口:`cd desktop/src-tauri && cargo tauri dev`。
4. 预期:窗口打开、加载 Vite 页面;前端能调 `/api/health`(经 proxy);控制台打印 dev-mode 提示行。
5. **改动后端代码 → 仅重启后端进程**(不重新打包 jar)→ 前端可见新行为。记录是否成立。

记录结果(成功/失败 + 截图或日志摘要)到执行报告。若 dev 下窗口打开但 API 不通,检查后端是否真在 24056、Vite proxy 配置。

- [ ] **Step 2: prod 模式验证(回归,确保不破坏)**

1. 打包 jar:`mvn -f ZhiFlow-Api/pom.xml install -DskipTests && mvn -f plugin-markdown/pom.xml install -DskipTests && mvn -f ZhiFlow/pom.xml package -DskipTests`。
2. `mkdir -p desktop/src-tauri/binaries && cp ZhiFlow/target/ZhiFlow-4.0.0-SNAPSHOT.jar desktop/src-tauri/binaries/ZhiFlow.jar`。
3. `cd desktop/src-tauri && cargo tauri build`。
4. 运行产物,预期:与改造前一致——Tauri 启动 sidecar、注入 token、SETUP→APP 自动重启(若首次)、窗口显示主 shell。

若 `cargo tauri build` 因平台工具链(签名/notarize)无法完整跑,至少 `cargo build --release`(在 src-tauri)验证 prod 路径编译,记录。

- [ ] **Step 3: 无新增提交(纯验证),除非发现 bug 需修复**

若 Step 1/2 发现回归 bug,回到 Task 1 修复并补提交。若全绿,本任务无提交,在执行报告中标注 "e2e 验证通过"。

---

## Self-Review

**1. Spec 覆盖:**
- ✅ dev 模式 Tauri 不启动 sidecar → Task 1 的 `#[cfg(dev)] main` + run_desktop(None, None)。
- ✅ prod 保持 jar sidecar → Task 1 的 `#[cfg(not(dev))] main` 保留全部逻辑。
- ✅ `#[cfg(dev)]` 编译期区分 → Global Constraints 已注明 + Task 1 Step 3 用 RUSTFLAGS 验证。
- ✅ 外部启动后端经 Vite proxy → 前端零改动(Global Constraints),Task 3 Step 1 端到端验证。
- ✅ 文档更新 → Task 2 重写 README。
- ✅ dev 下手动重启后端说明 → Task 2 README + Task 3 Step 1。

**2. Placeholder 扫描:** 无 TBD/TODO。Task 1 Step 3 预判了 `kill_sidecar` 的 cfg 问题并给了明确修正(去掉其 cfg 注解),非 placeholder。Task 3 是人工验证清单,步骤具体(curl、命令、预期)。

**3. 类型一致性:**
- `run_desktop(child: Option<Child>, init_script: Option<String>)` 在 Task 1 定义,dev/prod 两个 main 调用签名一致 ✅
- `Sidecar(Mutex<Option<Child>>)` 接收 `Mutex::new(child)`(child 为 Option),dev 传 None、prod 传 Some ✅
- `kill_sidecar(&State<Sidecar>)` 无条件定义(Step 3 修正后),两个路径都能调 ✅

**已知风险(实现时注意):**
- `RUSTFLAGS="--cfg dev" cargo check` 模拟 dev 编译——需确认这能正确触发 `#[cfg(dev)]`(应该能,Rust cfg 是编译期标志,RUSTFLAGS 的 `--cfg dev` 等价于 `cfg(dev)`)。若不生效,改用 `cargo tauri dev` 的 `--no-run` 或类似(但 tauri CLI 可能无此选项)。Task 1 Step 2/3 给了 fallback(允许联网、观察实际输出)。
- `tauri::WebviewWindowBuilder` 的 `.initialization_script()` 链式调用在 Option 分支下需正确处理 builder 所有权——Step 1 代码用 `if let Some(script) = init_script.as_deref() { builder = builder.initialization_script(script); }` 处理。实现时验证 builder 的所有权转移正确(若编译报借用错误,调整写法)。
