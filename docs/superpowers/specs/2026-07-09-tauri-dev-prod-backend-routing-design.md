# Tauri dev/prod 后端通信分流 — 设计文档

> **日期:** 2026-07-09
> **状态:** 设计已确认,待实现

---

## 背景

ZhiFlow 桌面壳(Tauri 2.0,Rust)当前在 **`cargo tauri dev` 和 `cargo tauri build` 两种模式**下都让 Rust
启动 Java sidecar(`main.rs` 的 `spawn_backend` 读 `binaries/ZhiFlow.jar`)。开发时,每次后端 Java 代码改动都要
重新 `mvn package` + 复制 jar 到 `desktop/src-tauri/binaries/ZhiFlow.jar`,无法热重载,严重拖慢开发迭代。

## 目标

让 **dev 模式**与 **prod 模式**用不同的后端启动策略:

- **dev**(`cargo tauri dev`):Tauri **完全不启动任何 Java 进程**。开发者用 IDE 的 Spring Boot Run 或
  `mvn -pl ZhiFlow spring-boot:run` 在固定端口 `24056` 启动后端(支持热重载 / spring-boot-devtools),
  Tauri webview 经 Vite dev-server 的 proxy(`/api`、`/plugin-ui` → `localhost:24056`)与后端通信。
  Tauri 只负责开窗口 + 加载 Vite dev server(`devUrl = http://localhost:5173`)。
- **prod**(`cargo tauri build`):保持现状——Rust 启动打包好的 jar sidecar,注入 token / api-base 到 webview,
  管理生命周期,处理 SETUP→APP 重启。

**判定方式:** Rust 编译期 `#[cfg(dev)]` / `#[cfg(not(dev))]`。
`tauri-build` 2.6.3 在 `cargo tauri dev` 时设置 `cfg(dev)`(由 `TAURI_DEV` 环境变量触发),`cargo tauri build`
时设置 `custom-protocol` feature。dev 二进制里 sidecar spawn 代码根本不编译进去,**零运行时开销**。

## 为什么选这个方案

| 维度 | 选定方案:`#[cfg(dev)]` + 外部启动后端 |
|---|---|
| 开发效率 | 后端可热重载(IDE / spring-boot:run / devtools),前端 Vite HMR 不变,改动立即可见 |
| 改动量 | 极小:只改 `main.rs`(cfg 包裹)+ 文档。Vite proxy、前端 `config.ts` 已就绪,零改动 |
| prod 影响 | 零。`cargo tauri build` 编译 `not(dev)` 分支,行为与现在完全一致 |
| 可靠性 | 编译期分流,不存在运行时配置读取错误;dev 二进制不含 sidecar 代码,不会误启 Java |

## 现状(改动前)

### dev 流程(`cargo tauri dev`,当前)

1. `tauri.conf.json` 的 `beforeDevCommand` 启动 Vite(`frontend/`,端口 5173)。
2. `main.rs` 的 `main()`:`gen_token()` → `spawn_backend(token)`(读 `binaries/ZhiFlow.jar`,执行
   `java -cp ... fan.summer.zhiflow.HeadlessLauncher --port=24056 --token=...`)→ 读 stdout 的
   `ZHIFLOW_PORT=<n>` → `wait_for_health` → (Task 18 的 restart-loop:`check_setup_mode` → SETUP 时
   等退出、APP 时 break)→ 注入 `window.__ZHIFLOW_TOKEN__` / `window.__ZHIFLOW_API_BASE__` → 开窗口。
3. 前端 `getApiBase()` / `getToken()` 读 `window` 全局 → 直连 Rust 启动的后端。
4. **痛点:** 后端代码改一行 → `mvn package` + `cp jar` → 重启。无热重载。

### prod 流程(`cargo tauri build`,当前) — 保持不变

同上的 Rust 逻辑,但编译进 release 二进制,打包时 `binaries/ZhiFlow.jar` 随产物分发。

## 设计(改动后)

### dev 流程(`cargo tauri dev`,改动后)

1. `beforeDevCommand` 启动 Vite(不变)。
2. `main.rs` 的 `#[cfg(dev)]` 版 `main()`:**跳过** sidecar / token / health / restart-loop /
   init_script 注入,直接 `tauri::Builder` + 开窗口。webview 加载 `devUrl`(`localhost:5173`)。
3. 开发者**外部**在 24056 启动后端(IDE Run 或 `mvn -pl ZhiFlow spring-boot:run`)。
4. 前端 API 请求 `/api/*` / `/plugin-ui/*` 走 Vite dev-server → Vite proxy → `localhost:24056`。
5. `getApiBase()` 在 `window` 全局不存在时回退 `''`(同源),`getToken()` 回退 `VITE_ZHIFLOW_TOKEN`(可空)。

### prod 流程(`cargo tauri build`,改动后) — 与现状一致

`#[cfg(not(dev))]` 版 `main()`:保留完整逻辑(token + spawn_backend + wait_for_health + restart-loop
+ init_script 注入)。行为与现在完全相同。

## 改动点

### 1. `desktop/src-tauri/src/main.rs`(核心)

把后端启动相关逻辑用 `#[cfg(not(dev))]` 包裹,并提供两版 `main()`:

- **公共尾部 `run_desktop(backend: Option<(Child, u16)>)`** 或更简单的方式:把窗口构建 + Builder 部分抽成一个
  被两个 main 调用的函数,避免 prod/dev 各写一份窗口代码(DRY)。
  - 注意:`Sidecar(Mutex<Option<Child>>)` 状态在 dev 下没有 child(`Option` 为 None),`on_window_event` 的
    `kill_sidecar` 在 None 时是 no-op——结构兼容,无需特殊处理。
- **`#[cfg(dev)] fn main()`**:`run_desktop(None)`(不传后端,不注入 token 脚本——dev 下 webview 直接走 Vite)。
- **`#[cfg(not(dev))] fn main()`**:现有逻辑(token + spawn + health + restart-loop → 得到 `(child, port)`)→
  `run_desktop(Some((child, port)))`,并在该分支内构造 `init_script`(注入 `window.__ZHIFLOW_TOKEN__` /
  `__ZHIFLOW_API_BASE__`)。

sidecar 相关的辅助函数(`spawn_backend`、`wait_for_health`、`check_setup_mode`、`gen_token`、`jar_path`、
`kill_sidecar`)在 dev 下不被引用——用 `#[cfg(not(dev))]` 标注,或 `#[allow(dead_code)]`,或保持原样
(Rust 对未调用函数只发 warning,dev 下会提示 unused)。**实现时优先 `#[cfg(not(dev))]` 标注这些函数**,
让 dev 编译彻底不含这些代码(连同它们用到的 `std::process::{Child,Command,Stdio}`、`BufRead`/`BufReader`、
`thread` 等 import 也要相应 cfg 包裹,避免 dev 下 unused import warning)。

### 2. `desktop/src-tauri/Cargo.toml`(确认/微调)

- `tauri-build = { version = "2.0", features = [] }` 已存在,2.6.3 正式支持 `cfg(dev)`,无需改。
- `[features]` 当前只有 `custom-protocol = ["tauri/custom-protocol"]`(prod 用)。无需新增 feature——
  `dev` 是 `tauri-build` 通过 `cargo:rustc-cfg=dev` 设置的编译期标志,不是 Cargo feature。
- **已验证的机制(基于 tauri-build 2.6.3 源码):**
  - `tauri-build/src/lib.rs:519` 调用 `cfg_alias("dev", is_dev())`。
  - `is_dev()`(`lib.rs:425`)读 `DEP_TAURI_DEV` 环境变量(由 `tauri` crate 的 `build.rs` 在
    `cargo tauri dev` 设置 `TAURI_DEV` 时传 `"true"`)。
  - `cfg_alias("dev", true)`(`lib.rs:206`)发出 `cargo:rustc-cfg=dev` + `cargo:rustc-check-cfg=cfg(dev)`,
    使源码中的 `#[cfg(dev)]` 在 dev 编译时为真,且编译器不报 "unexpected cfg" 警告。
  - 因此 **用 `#[cfg(dev)]` 表示 dev,`#[cfg(not(dev))]` 表示 prod**。备选 `not(feature="custom-protocol")`
    仅在 `cfg(dev)` 不工作时的兜底,本设计不采用。

### 3. `desktop/README.md`(文档)

重写 "Phase 1 dev run" 章节:

- **删除** "Build the backend jar" + "Copy the jar" 步骤。
- **新增** dev 流程:
  1. 启动后端:`mvn -pl ZhiFlow spring-boot:run`(或 IDE Run `AiApplication`/`HeadlessLauncher`),绑定 24056。
     首次启动无 `datasource.properties` → SETUP 模式 → 向导(选 H2,默认路径)→ `System.exit(0)`。
  2. **手动重启后端**(进入 APP 模式)——dev 下 Tauri 不监控后端退出,所以向导完成后需手动重启一次后端。
  3. `cargo tauri dev`(在 `desktop/src-tauri/` 下)——Tauri 开窗口,加载 Vite,API 经 proxy 到 24056。
- 新增 "dev 注意事项":token 可空(auth disabled via 空命令行 token);改后端代码只需重启后端进程,无需重新打包。

### 4. 前端 — 零改动(验证)

`frontend/src/api/config.ts`、`vite.config.ts` 已容错并配好 proxy。确认 dev 下 Tauri 不注入 `window` 全局时,
前端正确回退到 Vite proxy(同源)+ env token。无需改任何前端文件。

## 行为细节与边界

- **setup 向导 dev 交互:** 外部后端首次启动 → SETUP 模式 → 向导 → `System.exit(0)`。dev 下 Tauri 不监控后端退出,
  所以向导完成后**前端会因后端掉线而失败**——开发者需手动重启后端进入 APP 模式。这是可接受的 dev 体验(一次性,
  之后 `datasource.properties` 存在,每次直接 APP 模式)。文档明确说明。
- **token:** dev 下后端若用 IDE/spring-boot:run 启动且不传 `--token`,则 `zhiflow.auth.token` 系统属性为空 →
  `TokenAuthFilter` 放行所有请求(`expected.isBlank()` 分支)。前端 `getToken()` 回退空 → 完全无 token。一致。
- **端口冲突:** dev 下后端必须用 24056(Vite proxy 固定指向它)。若 24056 被占,后端会 fallback 到随机端口,
  Vite proxy 就连不上——开发者需确保 24056 可用(或重启后端释放它)。与现状约束一致。
- **prod jar 准备:** prod build 前仍需 `mvn package` + copy jar 到 `binaries/`(README 现有步骤保留,可加一个
  `beforeBuildCommand` 自动化或文档强调)。

## 不做什么(YAGNI)

- 不改 `tauri.conf.json` 的 `externalBin` / Tauri 官方 sidecar bundle 机制(更大的重构,超出范围)。
- 不改前端任何代码。
- 不引入运行时环境变量判定 dev/prod(用编译期 cfg,更干净)。
- 不为 dev 自动化 setup 向导后的后端重启(手动重启可接受)。

## 验证

实现后:

1. **dev**:`cargo tauri dev` → 窗口打开、加载 Vite;外部 `mvn -pl ZhiFlow spring-boot:run` → 前端能调 `/api/health`、
   `/api/setup/status`;改后端代码 → 重启后端进程即生效(无需重新打包 jar)。
2. **prod**:`cargo tauri build`(先确保 jar 在 `binaries/`)→ 产物行为与现在一致(sidecar 启动、token 注入、
   SETUP→APP 自动重启)。
3. **cargo check 两种模式都过**:`cargo check` + (若可行)`cargo tauri build` 的编译检查。
