# Changelog

All notable changes to FengYu. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### ✨ Added
- **Plugin manifest schema v2 — the single hand-written contract.** `manifest.json` is now
  `schemaVersion: 2` with `additionalProperties: false` throughout. RPC methods live once in an
  `rpc.methods` table whose `inputSchema`/`outputSchema` are JSON-Schema **objects** (not escaped
  strings); `aiTools` reference those methods by `method` with a mandatory `effect`, duplicating no
  schema. `backend` keeps only `callTimeoutSeconds` — the worker is implicitly
  `java -jar backend/worker.jar` over JSON-RPC 2.0 (`backend.command`/`backend.protocol` removed).
  The CLI validator enforces every rule, including a raw-text duplicate-`rpc.methods`-key scan
  (JSON.parse would otherwise silently merge duplicates).
- **Deterministic contract generator.** `fengyu init|dev|build` generate a typed TS RPC client
  (`ui-src/src/generated/fengyu-rpc.ts` — `createPluginRpc(client)` + per-method `Input`/`Output`
  types) and Java records + a centralized `PluginMethods` name class
  (`src/main/java/<id>/generated/`) straight from `rpc.methods`. Output is byte-for-byte stable
  (sorted), handles identifier escaping / reserved words / nullable / required, nests records for
  object/array fields, and rejects unsupported JSON-Schema constructs instead of coercing to
  `Object`. `fengyu check` detects generated-file drift without writing.
- **Typed Java Worker SDK + standard cancellation.** `JsonRpcWorker.method(NAME, Input, Output,
  handler)` registers typed handlers; `RpcContext` exposes `callId`/`pluginId`/`pluginRoot`/
  `locale`/`cancellation()`/`logger()`. The dispatch loop is split into a reader + handler pool so a
  `$/cancelRequest` notification cancels an in-flight call (returns `CANCELLED`, worker survives);
  EOF drains gracefully. Stable `RpcError.Code` set (`INVALID_ARGUMENT`/`PERMISSION_DENIED`/
  `NOT_FOUND`/`CONFLICT`/`CANCELLED`/`INTERNAL`) maps to JSON-RPC codes and an `error.data.code`
  label. The old `JsonRpcWorker.string/integer` Map-parsing helpers were removed.
- **Reserved `_fengyu` metadata envelope on the worker RPC frame.** The request locale now rides in
  a host-owned top-level `_fengyu` object (`_fengyu.locale`) instead of being injected into `params`,
  so a plugin method may freely declare its own `locale` input field without it being overwritten by
  the request locale. The Worker SDK reads `_fengyu.locale` (binding both `RpcContext.locale()` and
  `WorkerLocale`, so synchronous handlers now resolve messages in the request language — previously
  only `Jobs` propagated locale) and falls back to the legacy `params.locale` key for hosts that have
  not yet adopted the envelope. Any frame-root key beginning with `_fengyu` is reserved.
- **Shared plugin bridge protocol `3.0.0` and capability pre-check.** `@infinia/plugin-sdk/protocol`
  is the side-effect-free source for iframe/host message types, method constants, capabilities, and
  structured errors. `HostEnvironment` now carries `pluginId`/`pluginVersion`/`permissions`;
  `HostError.code` adds `TIMEOUT`/`CANCELLED`. The SDK validates the capability before each request,
  posts the cancel notification on both timeout and abort, and silently drops responses for unknown
  ids / wrong origin / wrong protocol version.
- **Host v2 install + dispatch.** Install accepts schema v2 only and validates `rpc.methods`
  (unknown method rejected before worker start; AI tool schemas read from the referenced method's
  object schema — no string re-parsing). The iframe/HTTP `callId` is passed through as the JSON-RPC
  `id`. Cancel sends `$/cancelRequest` first and only force-restarts on timeout. Worker errors map
  to typed exceptions: `PERMISSION_DENIED` → HTTP 403 (no longer a generic 500), `CANCELLED` → 499.
- **All four official plugins migrated to the typed model.** `markdown` (canary), `excel`,
  `offlinepython`, and `email` now ship schema-v2 manifests, generated TS/Java contracts, typed
  `method(...)` workers, and transport-cancellation tests. `offlinepython`'s domain
  `build.cancel`/`deploy.cancel` now reaps the whole Python/pip subprocess tree.

### ♻️ Changed
- **Convention-based Toolchain 2 CLI.** The public command surface is `fengyu init`, `dev`,
  `check`, and `build`. `fengyu.plugin.json` and arbitrary command arrays were removed; projects use
  standard npm scripts and Maven lifecycles, the Worker is discovered as the unique
  `target/*-worker.jar`, and packages are written under `dist/`. The unused direct-ESM
  `default.mount(el, ctx)` loader was removed; sandboxed iframe + shared protocol is the only UI
  runtime.
- **`offlinepython` RPC method names are lowerCamelCase.** Schema v2 forbids dots in method keys,
  so `config.get`→`configGet`, `requirements.save`→`requirementsSave`, `build.start`→`buildStart`,
  etc. The smoke scripts were updated; external callers of the old dotted names must switch.

## [4.0.0-beta.2] — 2026-08-11

### ✨ Added
- **Settings-driven update channel.** The Settings page can now point the app's update check at an
  intranet/offline FY-Proxy base URL instead of the default GitHub feed. The value is persisted in
  the `app_setting` store and honored on both update paths: the backend `UpdateCheckService` reads
  it per check (live without a JVM restart), and the Electron shell bootstraps
  `FENGYU_UPDATE_API_BASE` from the backend before the first update check and updates it live via an
  `update:set-api-base` IPC from the renderer. `PUT /api/settings` validates the value as an absolute
  HTTP(S) URL with no credentials, query, or fragment, mirroring the desktop `update-feed.ts`
  validation so both channels accept the same value; invalid values map to HTTP 400.
- **Full i18n for all official plugins (front + back end).** Plugin worker backends now render
  localized `summary`/`error` messages, and the Markdown and Excel plugin UIs are fully localized
  to match the Email and Offline Python Builder plugins. Locale flows per-request from the host
  `Accept-Language` header (and the AI chat turn) into the worker, with no change to the JSON-RPC
  envelope or the `PluginHandler` signature:
  - **SDK `1.3.0`** adds `WorkerLocale` (per-request locale ThreadLocal bound by `JsonRpcWorker.serve`
    from a `locale` params key), `PluginMessages` (a classpath `i18n/messages[_zh].properties` bundle
    resolver), and keyed `okKey`/`failKey`/`t` helpers on `PluginHandlerSupport`. Workers without
    bundles keep their prior English behaviour (default locale `en`).
  - **Host** injects the resolved locale on both call paths: `PluginRuntimeController.invoke` reads
    `Accept-Language`, and the AI path carries the locale through a new `AiToolLocaleContext`
    ThreadLocal (mirroring `AiPermissionContext`) bound for the chat turn.
  - **Plugin backends** ship `i18n/messages[_zh].properties` and key their user-facing strings:
    Markdown, Excel (worker + progress logs), Email (handlers + services), and Offline Python Builder
    (activating the previously-orphaned bundles). The Offline Python doctor check `id`/`value`
    protocol tokens stay locale-neutral (the frontend translates them, as before).
  - **Markdown + Excel frontends** gain a lightweight `i18n.ts` + `useFengYuEnvironment()` composable
    (mirroring the Offline Python pattern), keying every visible UI string.
  - **Unified production lifecycle.** All six plugin-tooling artifacts now agree on `1.3.0`.
    `@infinia/plugin-ui` provides `mountFengYuApp` and `createFengYuI18n`, so the four official
    plugin UIs share one ready/theme/locale/mount/pagehide-dispose path. The TS SDK deduplicates
    `ready()`, caches and merges environment updates, and exposes `currentEnvironment()`.
    Worker `Jobs` inherit the initiating request locale, support race-safe cancellation and
    reject starts after close; `JsonRpcWorker.onClose` closes registered resources in reverse
    order before exit, and the official async workers register their job registries for teardown.
    Toolchain and official-plugin lockfiles also pick up patched `fast-uri`, `nanoid`, `dompurify`,
    and `brace-expansion` releases; their npm audits now report zero known vulnerabilities.

## [4.0.0-beta.1] — 2026-08-09

### ✨ Added
- **Official Browser Agent.** The fifth bundled plugin drives a real Chromium through nine
  confirmation-aware AI tools for navigation, clicking, typing, DOM inspection, screenshots,
  waiting, JavaScript evaluation, and session shutdown. Chromium resolution supports a configured
  system browser, a plugin-managed download, and Playwright fallback.
- **Isolated plugin database lifecycle and localized manifests.** Database-capable workers now use
  user-authorized per-plugin credentials with recoverable provisioning/deprovisioning state, while
  manifest display strings and AI-tool descriptions support locale-family fallback. Plugin logs are
  persisted per plugin and exposed through ordered REST/SSE history.
- **Windows Job Object process isolation backend (`ProcessSandbox` `WINDOWS_JOB`).** Plugin workers
  and AI-authored commands on Windows now run inside a Win32 Job Object configured with
  `KILL_ON_JOB_CLOSE`, giving reliable process-tree termination: closing the job handle (or
  `TerminateJobObject`) kills the worker and any descendants (e.g. a `pip` subprocess) without
  relying on `ProcessHandle.descendants()`, which was unreliable on Windows. The Job Object is a
  process-layer isolation only — filesystem and network confinement remain a known gap on Windows
  (the explicit-approval gate still guards every effect there). `GET /api/security/process-isolation`
  reports `backend: "windows-job"`. JNA 5.19.1 was added for the Win32 binding.

### ♻️ Changed
- **Browser automation moved from `plugin-browser` (Playwright) to a host-embedded capability.**
  Browser automation is now built into the desktop application and exposed by the backend
  `BrowserTool`, not a `.fyp` plugin. It drives a real browser window through Electron's native
  `webContents` and the Chrome DevTools Protocol (CDP) over a loopback HTTP bridge — **no Playwright
  dependency and no separate Chromium download**. The nine AI tools (`browser_navigate`,
  `browser_click`, `browser_type`, `browser_get_text`, `browser_query`, `browser_screenshot`,
  `browser_wait_for`, `browser_eval_js`, `browser_close`) remain, each approval-gated. The capability
  is **desktop-only**: it requires the Electron shell and is unavailable in pure-web / headless mode.

### 🗑️ Removed
- **`plugin-browser` (`fan.summer.browser`) official plugin.** The Playwright-based browser plugin
  has been removed; its function is now provided by the host-embedded `BrowserTool` (see Changed
  above). `OfficialPlugins` now ships four plugins: `plugin-markdown`, `plugin-excel`,
  `plugin-email`, and `plugin-offlinepython`.

### 🐛 Fixed
- **Official plugins no longer reappear after the user uninstalls them.** Uninstall previously
  deleted both the package directory and the integrity record, leaving no trace; on the next restart
  the official-plugin seeder could not distinguish a user uninstall from a never-installed plugin and
  reinstalled the bundled archive. A persistent **uninstall tombstone** now marks uninstalled
  plugins; the seeder checks it before seeding and skips them. A later reinstall (local upload,
  online upgrade, or a bundled upgrade) clears the tombstone so the cycle is repeatable.
- **Local `.fyp` install with a matching `.sha256` sidecar may now claim official identity.** The
  native install path now verifies a sibling `<archive>.sha256` sidecar (GNU coreutils
  `sha256sum -c` format — the same credential the official seeder verifies), letting a user install
  a rebuilt official plugin locally at the same trust level. Without a sidecar (or with a mismatched
  one) the install stays untrusted, so the existing reservation still blocks official /
  namespace-squatting. (Asymmetric signature verification remains a tracked follow-up; a sidecar is
  a tamper/corruption check, not an independent authenticity anchor.)
- **Excel split no longer falls back to copying the whole sheet after a worker restart.** The host
  tears down and relaunches a plugin worker whenever its file-grant version changes — in the Excel
  wizard, picking the output folder (Output step) grants the output dir *after* `configure` (Mode
  step), so the worker serving `split` is a fresh process whose in-memory session store is empty.
  `split` now re-applies the full split config from its own arguments (fields absent are left
  untouched, preserving partial-update callers), making the session store a cache rather than a
  correctness dependency.
- **Web and desktop dependency advisories.** Updated DOMPurify, nanoid, js-yaml, PostCSS, fast-uri,
  and brace-expansion to patched compatible versions; both npm dependency audits now report zero
  known vulnerabilities.
- **Beta plugin runtime hardening.** Official package checksum sidecars now travel with `.fyp`
  artifacts through Web/desktop release assembly; plugin DB provisioning/deprovisioning keeps
  recoverable lifecycle state; JSON-RPC frames, async job logs, and plugin-log SSE replay are
  bounded and ordered; Windows command Job Object handles are reclaimed on every path. Plugin
  uninstall now asks whether to retain or permanently delete runtime data and the provisioned DB
  namespace, and reports deletion failures instead of silently leaving data behind.
- **Plugin worker processes no longer leak after the host exits.** A plugin worker is an
  independent JVM spawned by the host backend; previously the worker was often not reaped when the
  host exited (macOS/Windows have no equivalent of Linux's `bwrap --die-with-parent`, and Electron
  only signalled the backend's direct PID with no tree-kill), so leaked workers kept holding the
  exclusive file locks of embedded databases (H2/SQLite), leaving the database files undeletable.
  Fixed with four complementary layers of defense:
  - **Worker SDK watchdog** (`fengyu-plugin-sdk` 1.1.0 → 1.2.0): the production `run()` entry point
    gained dual watchdogs — stdin-EOF (primary) and a parent-liveness poll (auxiliary). On host
    shutdown/crash the worker auto-`System.exit`s, ensuring it exits and releases file locks even
    when it holds non-daemon thread pools (HikariCP, etc.).
  - **Explicit host shutdown hook**: `HeadlessLauncher` registers a JVM shutdown hook in APP mode
    that is independent of Spring and calls `PluginProcessManager.close()` directly, instead of
    relying solely on the timing of Spring's default hook.
  - **Grandchild-process fallback**: after destroying a worker, `PluginProcessManager.Worker.close()`
    recursively `destroyForcibly`s its descendant processes (e.g. offlinepython's `pip` subprocess)
    to avoid orphaning them.
  - **Electron tree-kill**: the desktop shell adds a `tree-kill` dependency so that on exit it sends
    SIGTERM→SIGKILL to the entire backend process tree (including worker grandchildren), as a
    fallback when the host crashes.
  - The exit traps in `scripts/e2e-smoke.sh` and `scripts/offlinepython-e2e-smoke.sh` now also run
    `pkill -P` to clean up worker subprocess trees.
- **Offline Python Builder deploy now uses the deployment machine's Python.** The deploy step
  previously re-ran Python detection with a null hint, discarding the interpreter resolved from the
  target — so on machines whose Python (conda/pyenv/venv) was not on `PATH`, version detection
  returned `null`, every C-extension wheel (numpy/pandas/…) was judged incompatible, and the deploy
  silently installed zero packages (or reported success with nothing actually installed).
  `DeployService` now resolves the version from the target's interpreter and fails loudly if it
  cannot, instead of masking the failure as an empty match. The Deploy panel also auto-detects this
  machine's interpreter on load and falls back to a manual path input when detection fails, so
  offline machines with non-`PATH` interpreters can still deploy.

## [4.0.0-alpha.8] — 2026-08-04

### 🐛 Fixed
- **Email plugin now works with the default embedded-H2 database.** The host holds an exclusive
  file lock on its own H2 file, so a sandboxed plugin worker could not attach to the same file —
  H2 rejected the `AUTO_SERVER` + sandbox combination and every email RPC failed at worker boot.
  Database-permission workers now get their own DB file under their plugin data directory for
  embedded databases (H2/SQLite), while remote databases (MySQL/PostgreSQL) still share the host
  URL (real servers handle concurrent connections). The useless `AUTO_SERVER=TRUE` option was also
  dropped from the host H2 URL template. The e2e smoke now exercises an email RPC end-to-end.

## [4.0.0-alpha.7] — 2026-08-04

### ✨ Added
- **Unified plugin store (Claude / Codex / FengYu).** A new `/api/plugin-store/*` REST surface lets
  you subscribe to third-party Claude Code and OpenAI Codex marketplaces alongside the FengYu
  catalog, browse a merged, filtered, source-badged grid, and install Claude/Codex plugins by
  cloning their git source (JGit) with pinned-sha verification. The frontend ships a unified
  "Stores" tab with a source manager, install/update/uninstall actions, declared-skills and MCP
  rendering, and an in-app detail drawer. JGit 7.7.0 was added for clone support.
- **Windows unsandboxed-plugins toggle.** On platforms without a native process sandbox (Windows,
  or any host where `ProcessSandbox.detect()` is `NONE`), a new Settings row — gated behind a
  confirmation dialog and defaulting fail-closed — lets a user opt into running plugin workers via
  the `unrestricted()` channel (`effectiveUnrestricted = fullAccess || unsandboxedPluginsEnabled`).
  AI command-approval and the sandbox fail-closed primitive are untouched; this only unlocks plugin
  workers.

### 🐛 Fixed
- **Store installer path-traversal (security).** A malicious third-party marketplace entry with a
  `name` containing path-traversal sequences (`../…`) could delete or overwrite arbitrary
  user-writable files, because the raw name flowed unchecked into `skills/<uid>` and
  `mcp-servers/<uid>.json` paths that are both deleted and written on install. Catalog adapters now
  slugify the name to a single safe path segment, and the installer asserts every uid-derived path
  stays inside the runtime root before any delete/write (defense in depth).
- **Store clone URL scheme validation (security).** Clone URLs from third-party marketplace JSON are
  now restricted to `https`/`http`/`file`; `ftp:`, `jar:`, and bare local paths are rejected before
  JGit sees them.
- **Store clone cleanup and timeouts.** A failed clone no longer leaves a `.clone-/agent-*` temp dir
  (with `.git`) behind, and the configured `fengyu.store.git-clone-timeout-seconds` is now actually
  applied to the clone.
- **Symlink defense in skill extraction (security).** A malicious repo containing a symlink whose
  target escapes the plugin root can no longer leak host-readable files into the runtime tree; the
  skill walker now skips symlinks and copies with `NOFOLLOW_LINKS`.
- **Codex install integrity.** Codex sources (which declare no pinned sha) now record the resolved
  HEAD commit sha in the install record, so every install carries an auditable content fingerprint
  instead of `null`.
- **Catalog fetch size cap.** Catalog responses are now bounded to 16 MiB, so a malicious or broken
  catalog URL cannot OOM the backend by streaming an unbounded body into memory.
- **Homepage XSS (security).** The store detail drawer's Homepage button now allows only
  `http(s):`/`mailto:` URLs, blocking `javascript:` URIs from third-party catalog fields.
- **Frontend store error surfacing.** Install/update/uninstall failures now surface to the user
  instead of being silently swallowed; `busy` is always reset. Malformed catalog array fields are
  coerced to `[]` so the template never throws.
- **Plugin enable/disable marker.** Toggling a Claude/Codex plugin's enable state now writes the
  `.disabled` marker the skill loader reads, so disabling actually stops the skill from loading.

### ♻️ Changed
- **Hardened the unsandboxed-plugins platform gate** to rely on `compatibilityMode`
  (`isNativeSandboxAvailable()`), per the design — the toggle appears on any platform lacking a
  native sandbox, not strictly Windows.

## [4.0.0-alpha.6] — 2026-08-02

### ✨ Added
- **AI chat now has Codex-style action approval profiles.** The composer offers Ask for approval,
  Approve for me, and Full access; command execution and plugin-declared read/write/external
  effects share one host approval gate. Approval cards stay inside the composer, while calls render
  as compact progress rows such as `Read FengYu Plugin Dev skill`. Plugin manifests can declare an
  optional `aiTools[].effect`, with undeclared third-party effects treated conservatively.

### 🐛 Fixed
- **AI chat now handles Excel file workflows and approval-heavy replies reliably.** Attached
  files/directories are granted to every compatible backend plugin, and existing absolute paths
  typed in a user message are converted into read-only plugin-scoped FileRefs. Selected directories
  retain writable access where declared and are injected into single write-directory plugin tool
  parameters; Excel analysis is preferred before split operations, and unresolved FileRef objects
  can no longer become map-shaped output folders. IME composition no longer drops a trailing English
  segment on click-to-send, and final answers render below command approval cards.

### ✨ Added
- **Live visual-workflow tool contracts.** Plugin AI tools may declare an optional serialized
  `outputSchema`; the official Excel, Email, and Offline Python tools now publish user-facing input
  metadata and result-envelope schemas for canvas configuration.

### ♻️ Changed
- **Agent tool discovery now follows the installed-plugin lifecycle.** New runs read a live tool
  registry, while canvas nodes survive tool disable/uninstall, block unsafe execution, and reconcile
  newly required inputs when the same tool is enabled again.
- **A directory the user names as an output target never becomes worker-writable.** Instead a
  plugin-owned staging directory is created per turn and handed to the worker as a writable sandbox
  root; the host copies its contents to the real target after the turn completes and deletes the
  staging tree. The real directory stays read-only, so the OS sandbox writable-roots remain stable
  (one staging grant per turn) and a worker can never overwrite files in a user-named folder.
  Plugin workers also now receive a per-plugin writable temp directory
  (`-Djava.io.tmpdir` + `TMPDIR`/`TMP`/`TEMP`), and `PLUGIN_DATA_DIR_ENV` is set for every plugin
  rather than only database-capable ones. macOS sandbox writable-roots are canonicalized via
  `toRealPath()` before the profile is built (resolves `/var` → `/private/var`).
- **The chat tool-loop cap is now configurable.** A new `ai.max_tool_rounds` setting (default 50,
  `0` = unlimited) replaces the previously hard-coded per-backend limits and bounds the number of
  tool-call rounds a turn may take, stopping a model that re-requests the same tool from wedging the
  virtual thread and locking the backend. It is editable in Settings → AI.
- **Bumped Apache POI to 5.5.1.**

### 🐛 Fixed
- **The permission-mode menu is now disabled while a generation is in flight**, so an approval
  profile can no longer be switched mid-turn.

## [4.0.0] — 2026-07-29

### ✨ Added
- **Built-in `fengyu-plugin-dev` skill.** A second built-in skill (alongside `fengyu-features`)
  that teaches the in-app assistant the 4.0.0 plugin model: `.fyp` packages (sandboxed iframe UI +
  out-of-process JSON-RPC worker), the `manifest.json` fields, the permission enum, `aiTools`, and
  the `fengyu` CLI build/install flow. It is authored for the app's runtime context, distinct from
  the repo's agent-workflow skill of the same id.

### ♻️ Changed
- **Removed the legacy `FengYu-Api` module.** Host-only AI contracts, tool categories, and theme
  state now live in the headless `FengYu` application module. The obsolete JavaFX preview assets
  and in-process plugin logging bridge were deleted, and Maven no longer manages JavaFX artifacts.
- **Unified the application version at 4.0.0.** Maven, the frontend, Electron shell, built-in
  skills, and official plugin packages now use the stable version.
- **Scoped the VitePress toolchain to `docs/`.** The documentation package manifest, lockfile,
  installed dependencies, local commands, and CI cache now live with the documentation sources
  instead of occupying the repository root.

## [4.0.0-alpha.5] — 2026-07-29

### ✨ Added
- **Agent runs are now durable.** Each plan-and-execute run is snapshotted to the database
  (`ai_agent_run`) with a sequenced lifecycle-event append log (`ai_agent_run_event`) covering
  `plan_ready`, `plan_approval_requested`, `step_start`/`step_complete`, `step_approval_requested`,
  `complete`, and `error`/`cancelled`. History is listable/detailable per user, a failed or cancelled
  run can be resumed from its last completed step, and on restart any non-terminal in-flight run
  (PLANNING / AWAITING_*_APPROVAL / EXECUTING) is reclassified as FAILED. Persistence failures are
  logged but never kill a healthy run.
- **Sensitive tool calls require explicit user approval.** A new `ApprovalRequiredTool` contract
  marks host tools that must never run without confirmation. In ordinary chat,
  `ChatToolApprovalGate` blocks each model response containing such calls on a confirmation card
  (5-minute expiry), and in the Plan-and-Execute Agent each step pauses for the same gate. Cancelling
  a generation or swapping the backend rejects all pending approvals.
- **`execute_command` tool with OS-level process sandboxing.** AI-authored shell commands run inside
  a native isolator when one is available — `bwrap` (bubblewrap) on Linux and `sandbox-exec`
  (Seatbelt) on macOS — with read-only system files, writes confined to the working directory, and
  the network isolated unless explicitly opted in. Inherited environment variables holding
  `TOKEN`/`SECRET`/`PASSWORD`/`API_KEY`/`CREDENTIAL`/`COOKIE`/`AUTHORIZATION` are stripped before
  launch, output is bounded (default 64 KiB, max 256 KiB) with truncation flagged, and a bounded
  timeout (default 30s, max 600s) forcibly terminates descendants. Where no isolator exists the tool
  falls back to direct execution and discloses `compatibilityMode` in the result; approval stays
  mandatory regardless. `GET /api/security/process-isolation` reports the active backend.
- **MCP (Model Context Protocol) client.** MCP servers configured via `spring.ai.mcp.client.*` are
  connected at startup and surface their tools to the Agent. `GET /api/mcp/status` reports the
  enabled flag, connection/tool counts, and per-connection detail (name, version, protocol version,
  initialized).
- **The host and Java plugin Workers now share one live log level.** The Settings page persists
  `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`, applies it to the host's Logback namespaces,
  and pushes it to running Workers without a restart. The Java Worker SDK replaces
  `slf4j-simple` with a structured stderr provider, preserving logger name, thread, level, message,
  and exception stack while keeping stdout reserved for JSON-RPC; legacy free-form stderr remains
  supported.

### 🔒 Security
- **Runtime secret files are owner-only on POSIX.** `SensitiveFilePermissions` applies `rwx------`
  to secret/key-material directories and `rw-------` to their files on macOS/Linux (no-op on
  Windows, where user-profile ACLs apply).

### ♻️ Changed
- **Default runtime state is self-contained under the launch directory.** Without an explicit
  `fengyu.runtime.dir`, the app now stores embedded databases in
  `<program-working-directory>/.fengyu/database/` and configuration, logs, plugins, skills, and
  other writable state under `<program-working-directory>/.fengyu/`.
- **Plugin Workers are sandboxed per manifest permissions.** `ProcessSandbox.plugin(...)` confines
  each Worker's writes to its plugin-owned roots (broadened when `files.write` is declared) and
  isolates the network according to the manifest, on supported isolators.

---

## [4.0.0-alpha.4] — 2026-07-28

### 🐛 Fixed
- **Plugin UI icons now render reliably in sandboxed third-party plugins.** The host CSP explicitly
  permits same-origin and bundled `data:` fonts for compatibility with existing `.fyp` packages,
  while `@infinia/plugin-ui` leaves its MDI stylesheet to the consuming Vite app so new builds emit
  ordinary hashed font assets instead of embedding multi-megabyte fonts in the library CSS. Official
  plugin UIs now declare `@mdi/font` directly so the Vite build can resolve the externalized
  `@import` under strict `npm ci`.
- **`window.fengyu` access is SSR-safe.** The desktop-bridge calls added to `settings.ts`,
  `main.ts`, and `router/index.ts` are now guarded with `typeof window !== 'undefined'`, so they no
  longer throw `ReferenceError` in vite SSR / the `node --test` suite.

### ♻️ Changed
- **Windows desktop releases ship an extract-and-run ZIP instead of a self-extracting portable
  `.exe`.** Both the lite and JRE variants keep the NSIS installer (`*-win-x64-setup.exe`) and now
  publish `*-win-x64-portable.zip` (extract once, then run `Infinia.exe`); the startup-time
  self-extraction of the old portable executable is gone. Artifact names were unified to
  `<product>-<version>-<platform>-<arch>[<form>].<ext>` across macOS / Windows / Linux
  (`Infinia-4.0.0-mac-arm64.dmg`, `Infinia-4.0.0-win-x64-setup.exe`, …), and the release workflow +
  its contract test were updated to match.
- **Desktop startup no longer flashes and no longer requires a live backend to pick a route.** The
  shell shows a splash window while it probes the backend, exposes the pre-probed setup state and the
  chosen theme to the renderer via `window.fengyu.setupMode()` / `initialTheme()` / `setTheme()`, and
  the router consumes that snapshot on first navigation before falling back to live checks.

### 🔧 Internal
- **Backend runtime directories are centralized.** Plugin, skill, plugin-data, and transient-file
  directories are now derived from one stable root via the new `RuntimePaths` and overridable through
  `fengyu.runtime.dir` (default `~/.fengyu`), replacing scattered `System.getProperty("user.dir")`
  paths. `CryptoUtil` derives the `.machineid` from the same root. This makes the packaged Electron
  shell and the portable Web distribution agree on where state lives.
- **Portable Web distribution keeps its state self-contained.** `run.sh` / `run.bat` now pass
  `-Dfengyu.runtime.dir=<dist>/data`, so the database, config, logs, and plugin data land next to the
  launcher inside the extracted folder rather than in the user's home directory — preserving the
  "unzip and run, move/delete leaves nothing behind" portability contract. `scripts/e2e-smoke.sh`
  pins the same property to its temp dir so its run is repeatable.

---

## [4.0.0-alpha.3] — 2026-07-25

### ♻️ Changed — Desktop shell: Tauri → Electron
- **The desktop shell was rewritten from Tauri 2.0 (Rust) to Electron 43 (TypeScript).** The backend
  lifecycle is **unchanged** — Electron spawns the backend JAR over loopback, waits for `/api/health`,
  and hands the token + api-base to the renderer via a `contextBridge` preload (`window.fengyu`).
  This replaces the old Tauri `window.__FENGYU_*` globals.
- **Two release variants per platform.** Each platform ships a **lite** build (user supplies Java 21+
  on `PATH`) and a **JRE** build that bundles a `jlink`-minimized JRE under `<resources>/jre/`:
  - **macOS** (arm64 + x64) — `Infinia-<ver>-mac.dmg` and `Infinia-<ver>-mac-jre.dmg`.
  - **Windows** (x64) — `Infinia-<ver>-win.exe` (NSIS installer) and a portable exe; plus the
    `-jre.exe` NSIS variant.
  - **Linux** (x64) — `Infinia-<ver>.AppImage` / `.deb` and the `-jre.AppImage`.
- **Auto-updater** (`electron-updater` against GitHub Releases), **system tray** (hide-to-tray on
  window close, backend stays alive until app quit), **single-instance lock**, and **file logging**
  (`electron-log` → `~/.fengyu/logs/desktop.log`).
- **Dev mode** defaults to connecting an IDE-started backend at `http://127.0.0.1:24056` (no spawn,
  no token, no supervisor); set `FENGYU_JAR=<jar>` (or `FENGYU_DEV_BACKEND=disabled`) to make the
  shell spawn its own backend with the full release lifecycle.
- **Security posture:** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`. Navigation
  guards — `setWindowOpenHandler` delegates `http(s)` targets to the system browser and denies
  `window.open('file://...')`; `will-navigate` blocks cross-origin in-page navigation. See
  [docs/en/architecture/desktop.md](docs/en/architecture/desktop.md).

### ♻️ Toolchain directory consolidation
- Consolidated 7 plugin toolchain directories (2 Maven + 4 npm + schema) into `toolchain/`,
  flattening intermediate layers and unifying short semantic names
  (`sdk-java`/`devkit-java`/`sdk-ts`/`ui`/`dev`/`cli`/`spec`):
  - `FengYu-Plugin-Sdk`→`toolchain/sdk-java`
  - `FengYu-Plugin-DevKit`→`toolchain/devkit-java`
  - `plugin-sdk/typescript`→`toolchain/sdk-ts`
  - `plugin-ui/vue`→`toolchain/ui`
  - `plugin-dev`→`toolchain/dev`
  - `plugin-cli`→`toolchain/cli`
  - `plugin-spec`→`toolchain/spec`
- CI/release workflows and skills were renamed to `toolchain-*`
  (`plugin-tooling.yml`→`toolchain-ci.yml`, `plugin-tooling-release.yml`→`toolchain-release.yml`).
  The tag prefix `plugin-tooling-v*` is unchanged.
- **Maven artifactIds and npm package names are unchanged**: `fan.summer.fengyu.sdk:fengyu-plugin-sdk`,
  `fan.summer.fengyu.sdk:fengyu-plugin-devkit`, and `@infinia/plugin-sdk` / `@infinia/plugin-ui` /
  `@infinia/plugin-cli` / `@infinia/plugin-dev` are still published under their original names. The
  repo directories changed; the coordinates did not.

### ✨ Added
- **Skills** — a third extension surface (peer to plugins and AI tools) using Codex-style
  progressive disclosure. Enabled skills appear as a compact catalog in the system prompt, and
  the assistant loads a skill's full body on demand via the built-in `skill` tool — so large
  guidance documents never bloat the per-request token budget.
  - **Managed like plugins:** skills are packaged as **`.fys` archives** (zip: `manifest.json` +
    `SKILL.md`) and installed under `~/.fengyu/skills/<id>/` — a filesystem peer of
    `~/.fengyu/plugins/<id>/`. The full install/uninstall/enable/disable lifecycle mirrors the
    plugin system (atomic publish + backup rollback in `SkillPackageService`).
  - **Marketplace:** `fengyu.skills.catalog-url` points at a remote catalog JSON;
    `SkillMarketplaceService` merges remote entries with local install state (the lifecycle
    twin of `PluginMarketplaceService`). Install/update by id from the catalog.
  - **Enable state** is a `.disabled` filesystem marker (not a DB row), exactly like plugins,
    so it survives reinstall.
  - **OfficialSkillSeeder** (`ApplicationRunner`) idempotently seeds bundled `.fys` artifacts
    on boot, mirroring `OfficialPluginSeeder`.
  - Two discovery sources: **built-in** (`classpath:/skills/<id>/SKILL.md`, packaged in the
    JAR, cannot be uninstalled/disabled) and **installed** (`.fys` packages under
    `~/.fengyu/skills/`). Installed skills override built-ins on id clash.
  - New REST surface under `/api/skills`: list, detail, market, upload, upload-native, install,
    update, PATCH enabled, DELETE (409 for built-in skills). All require `X-FengYu-Token`.
  - Frontend: skill management is **integrated into the Plugins page** (`/plugins`) — a
    Codex-style `Plugins | Skills` tab pair at the top switches the view, with an installed
    fast-row and a card grid. A single Upload button accepts both `.fyp` and `.fys` archives
    and routes each by extension. No separate `/skills` route.
  - Built-in example skill `fengyu-features` (answers "what can FengYu do"), now with a
    `manifest.json` alongside its `SKILL.md`.
  - Skills are decoupled from plugins — they never touch `plugin-spec/` or a plugin manifest.
  - See [docs/en/skills/](docs/en/skills/) and [docs/zh/skills/](docs/zh/skills/).
- **IDE plugin debugging (plugin toolchain 1.1.0)** — `fengyu plugin dev` is replaced by an
  IDE-native flow so third-party authors debug UI and worker with real breakpoints, no JDWP
  remote attach.
  - **`@infinia/plugin-dev`** (new npm package, `plugin-dev/`) — a Vite plugin that turns the dev
    server into a FengYu host simulator: serves the iframe shell at `/__fengyu`, bridges
    `@infinia/plugin-sdk`'s `postMessage` calls, and forwards `rpc.invoke` to the dev worker over
    loopback TCP.
  - **`fengyu-plugin-devkit`** (new Maven artifact, `fan.summer.fengyu.sdk:fengyu-plugin-devkit`,
    `FengYu-Plugin-DevKit/`) — a loopback-only TCP JSON-RPC server (`PluginDevServer`) that drives
    the worker's `serve(RpcTransport)` loop. Scaffolded as `PluginDevMain` under
    `worker/src/test/java`; declared `<scope>test</scope>` so it never ships in the shaded JAR.
  - **`RpcTransport` abstraction** in the Java Worker SDK — `JsonRpcWorker.serve(RpcTransport)`
    shares the dispatch loop between production stdio (`StdioTransport`) and the devkit's loopback
    socket. `run()` / `run(InputStream, OutputStream)` are unchanged in behaviour.
  - The scaffolder now generates a shared `<Prefix>Worker.create()` handler factory, the production
    `<Prefix>WorkerMain`, and the IDE-debug `PluginDevMain`. UI-only scaffolds set `mockWorker: true`.
- **Real LLM planner + visual canvas workflow builder (AiAgent).** The empty
  `StubPlanGenerator` is replaced by `ChatBackendPlanGenerator`, which asks the active AI backend
  for a validated structured workflow while keeping tools disabled during planning (new
  `ChatBackend#chatWithoutTools` default — both `OllamaLocalBackend` and `SpringAiCloudBackend`
  honor the toggle). `AgentRunner` validates every workflow (model- or user-supplied) before any
  tool runs, resolves step-result references (`{{steps.N.result}}`, `{{last.result}}`), and accepts
  a caller-supplied workflow via `POST /api/agent/run` so the HTTP API can drive deterministic
  execution. The frontend gains a **Vue Flow** canvas (`AiAgent.vue`) with a tool palette,
  `WorkflowToolNode`, and `workflow.ts` that compiles the graph into the `AgentPlan` sent to the
  backend — a no-code peer to the AI plan path. EN/ZH strings updated. Also: the Electron main
  window starts hidden on a dark surface and is revealed only on first paint (or load failure),
  removing the white flash on cold start.

### 🐛 Fixed
- **IDE Worker failures no longer look successful.** When `workerEndpoint` is configured,
  `@infinia/plugin-dev` returns connection failures as RPC errors instead of silently substituting
  `devMock` data. All official plugin UIs now expose the documented `npm run dev` entry point.
- **Plugin-tooling release gates** now ship the canonical manifest schema, exempt the independently
  versioned Worker SDK from the application-parent check, and resolve the patched `fast-uri` 3.1.4.
- **First-launch (SETUP mode) no longer crashes.** `SkillController` was component-scanned into the
  DB-less SETUP context but depends on `SkillRegistry`/`SkillPackageService`/`SkillMarketplaceService`
  (in the `ai.skill` package, which SETUP mode does not scan), causing an
  `UnsatisfiedDependencyException` that aborted startup before the database wizard could run. It is
  now excluded from `SetupApplication`'s scan alongside the other APP-only controllers.
- **B1 — actuator `restart` endpoint removed from the default exposure.** `application.yml` now sets
  `management.endpoints.web.exposure.include: health` only. The `/actuator/restart` endpoint was
  reachable, and in the Web bundle's default no-token posture any loopback process could force a
  context restart (DoS). The SETUP→APP restart already goes through `System.exit(SETUP_DONE)` + the
  desktop supervisor, so no functionality is lost.
- **B2 — Web bundle generates a per-launch token by default.** `distribution/web/run.sh` and
  `run.bat` now generate a random `--token=` when the user passes none (previously auth was disabled
  by default). Explicit `--token=<t>` still overrides.
- **D1 — desktop navigation guards.** `setWindowOpenHandler` denies `window.open` and delegates
  `http(s)` targets to the system browser; `will-navigate` blocks cross-origin in-page navigation.
  Prevents a compromised page from `window.open('file://...')`.
- **D2 — auto-updater skips the JRE variant.** JRE-bundled builds detect `resourcesPath/jre` and skip
  the update check — the updater feed only references the lite variant, so auto-update would silently
  downgrade JRE users to the Java-dependent lite build. Full per-variant feeds are deferred.
- **D3 — supervisor `stop()` is now saved and called.** `main.ts` stores the
  `superviseSetupRestart` return value and calls it in `killBackend()` (defensive; prevents a future
  leak if a persistent listener is ever added).
- **D4 — APP-mode backend crash shows a dialog.** A lightweight exit listener in APP mode shows
  `dialog.showErrorBox` and quits on an unexpected backend crash (previously silent — the user saw
  only connection errors). The alpha does not auto-restart.
- **Desktop — right-edge dark strip on the window.** The shell allowed a document-level scrollbar
  whose transparent track exposed Electron's native window backing as a thin dark line on the
  right. `html/body/#app` now set `overflow: hidden` — the shell owns scrolling inside its panes
  (sidebar history, chat column) and no document scrollbar is ever created. The window's
  `backgroundColor` is also aligned to the dark theme (`#0d0d0d`) so the native backing never
  contrasts with the renderer.
- **Electron migration & tooling gates hardened.** New
  `desktop/electron/scripts/verify-frontend-dist.mjs` blocks a desktop build if
  `frontend-dist/` is missing or stale; `backend/spawn.ts`, `supervisor.ts`, `util/health.ts`
  and `main.ts` received additional lifecycle hardening with new unit tests
  (`health.test.ts`, `spawn.test.ts`, expanded `supervisor.test.ts`).
- **Plugin toolchain — `sdk-ts` lockfile desynced from 1.1.0** (root version stayed at 1.0.0);
  regenerated so the root and `packages[""]` agree. Also forced `brace-expansion=5.0.8` via
  `npm overrides` in `toolchain/ui` to clear 6 high-severity audit findings on
  `@vue/test-utils → js-beautify → … → brace-expansion@2.1.2` (no upstream fix exists on the
  2.x line).
- **Splash screen — shipped in the JRE build variant.** `electron-builder.jre.yml` had not been
  synced with the lite config when `resources/splash.html` was added to the desktop asar, so the
  self-contained JRE build (the flagship download) silently never showed a splash. The file list
  now matches `electron-builder.yml`, and both configs include `resources/splash.html`.
- **AI planner timeout no longer wedges the backend.** When a planning call exceeded its 180s
  budget (e.g. a hung Ollama process or a stalled provider connection),
  `ChatBackendPlanGenerator` gave up but the underlying stream kept blocking on `blockLast()` with
  no way to interrupt it. Both `OllamaLocalBackend` and `SpringAiCloudBackend` now hold the Reactor
  `Disposable` and await a `CountDownLatch` instead of `blockLast()`, so `cancelGeneration()` can
  `dispose()` the stream mid-flight and release the worker. The planner calls
  `cancelGeneration()` on any timeout/failure path, guaranteeing the `generating` flag is cleared
  and every subsequent `chat` / planning request no longer fails with
  *"Generation already in progress"*. Covered by a new regression test
  (`ChatBackendPlanGeneratorTest`).

### ♻️ Changed
- **CLI scope narrowed to `create` + `build`.** `fengyu plugin dev` moved to the IDE
  (`@infinia/plugin-dev` + `fengyu-plugin-devkit`); `fengyu plugin validate` is now a built-in step
  of `build` (the staging tree is always validated before packaging); `fengyu plugin install` is
  done through the host's plugin marketplace UI (`POST /api/plugin-market/upload`). `--port`,
  `--host`, `--token`, and `--ui-port` CLI flags were removed with their commands.
- **Plugin toolchain locked at six artifacts**, all released together as `plugin-tooling-vX.Y.Z`:
  the Worker SDK, the devkit, `@infinia/plugin-sdk`, `@infinia/plugin-ui`, `@infinia/plugin-cli`,
  and `@infinia/plugin-dev`. `plugin-cli/scripts/resolve-tooling-version.mjs` verifies all six.

### 🗑️ Removed
- `fengyu plugin dev`, `fengyu plugin validate`, and `fengyu plugin install` CLI subcommands and
  their source (`plugin-cli/src/dev.mjs`, `worker.mjs`, `install.mjs`). Development now happens in
  the IDE via `@infinia/plugin-dev`; the `FENGYU_DEBUG` JDWP remote-attach workaround is no longer
  needed (run `PluginDevMain` and set breakpoints directly).

---

## [4.0.0-alpha.1] — 2026-07-19

First public **alpha** of the 4.0 line. Infinia (FengYu) is re-architected from a JavaFX
desktop app into a **headless web + desktop application**: a loopback-only Spring Boot backend, a
Vue 3.5 + TypeScript SPA (identical for browser and desktop), and a Tauri 2.0 desktop shell that
sidecar-launches the backend. Built-in tools become isolated **`.fyp`** plugins — a sandboxed
iframe UI talking to an out-of-process JSON-RPC 2.0 worker. This alpha publishes unsigned
Windows/macOS/Linux Tauri packages and a portable, loopback-only Web distribution.

### ⚠️ Breaking Changes
- **JavaFX is gone.** All JavaFX code and dependencies are deleted — `FengYuApp`, the `ui/` shell,
  all built-in tool UI classes, the v1 `PluginRegistry`/`PluginLoader`, and every `org.openjfx:*`
  dependency. The running backend is headless (no window).
- **New entry point:** `fan.summer.fengyu.HeadlessLauncher` (was `fan.summer.Launcher`). It boots a
  loopback Spring Boot web server: `java -jar FengYu-4.0.0-alpha.1.jar --port=<n> --token=<t>`.
- **Plugin contract v2** (`FengYuPluginV2`): `descriptor()` + `invoke(action, args)` (JSON-in /
  JSON-out) + `aiTools()`. The old `createView()` → JavaFX `Node` contract is removed; UI is now a
  separately-served micro-frontend ESM bundle (`PluginDescriptor.uiEntry`).
- **`IconStyle` decoupled from JavaFX** — colours are RGB ints + `getColorHex()` (no
  `javafx.scene.paint.Color`).
- **Database layer migrated from MyBatis to Spring Data JPA + Hibernate 7** — see Removed.

### ✨ Added
- **Headless backend** (`fan.summer.fengyu.web.*`): `GET /api/health`, `GET /api/plugins`,
  `POST /api/plugins/{id}/invoke`, `GET /plugin-ui/{id}/**` (serves MF bundles), `GET/PUT
  /api/settings`, `POST /api/ai/chat` + `GET /api/ai/stream` (SSE: token / thinking / tool / done /
  error). Loopback-only bind + per-launch `X-FengYu-Token` auth (`?token=` for the SSE stream).
- **Alpha desktop + web release pipeline** — `v4.0.0-alpha.1` publishes unsigned Windows/macOS/Linux
  Tauri packages and a portable, loopback-only Web distribution. The Vue SPA is baked into the shaded
  backend JAR (`static/`) and served by a new `SpaForwardController`; a release-tag resolver
  (`scripts/resolve-release-version.mjs`) drives the version strings, and `scripts/package-web-release.sh`
  + `test-web-release.sh` assemble and smoke-test the archive. Code-signing, a bundled JRE, and the
  auto-updater remain deferred to a later release.
- **Multi-datasource setup wizard**: first launch guides users through database selection
  (H2 / SQLite / MySQL / PostgreSQL) with connection testing and automatic schema initialization.
  The backend boots in **SETUP mode** (minimal Spring context, no JPA) when
  `~/.fengyu/config/datasource.properties` is absent, and **APP mode** (full context) once it exists.
  The Tauri/desktop supervisor restarts the sidecar after the wizard completes.
- **JPA migration**: the database layer migrated from MyBatis to **Spring Data JPA + Hibernate 7**
  (`ddl-auto=update`). All 14 entities ported with `@Entity` annotations; 14 Spring Data repositories
  replace the MyBatis mappers.
- **User-system groundwork**: `sys_user` / `sys_session` tables, `user_id` row-level isolation on all
  user-scoped tables, and pluggable `AuthProvider` / `SecurityContext` interfaces with a Noop
  implementation (login UI deferred to a later phase). Local offline mode attributes all data to a
  single virtual user (id=1, "ZFlow-Summer"), created on APP-mode startup.
- **AES-GCM encryption** (`CryptoUtil`) for the datasource password field in
  `datasource.properties` — keys are machine-bound via a per-machine UUID.
- **Official plugin UI kit** `@infinia/plugin-ui` — a Vuetify 3 (Material Design 3) component library
  for generated plugins. Ships `FyPluginShell`, `FyPageHeader`, `FyToolbar`, SDK-backed
  `FyFilePicker` / `FyDirectoryPicker`, `FyStepWizard`, `FyTaskTable`, `FyNotificationCenter`, the
  `FyEmptyState` / `FyLoadingState` / `FyErrorState` / `FyPermissionNotice` state panels, and
  `FyConfirmDialog`, plus `createFengYuVuetify`, `bindFengYuEnvironment`, and `provideFengYuClient`
  so the scaffolded `main.ts` binds the host theme/locale automatically.
- **Email Center `.fyp`** (`fan.summer.email`) with a sandboxed five-tab Vue/Vuetify/TipTap UI, an
  isolated official-SDK Java Worker, and package permissions limited to database, email network, and
  authorized file read/write capabilities. Multi-account SMTP/IMAP configuration, AES-GCM credential
  storage, address-book/tag CRUD, confirmed single and batch sending, manual IMAP `.eml` collection,
  archive search/detail, and seven manifest-declared AI tools. Plugin-owned tables use the
  `FengTu_PL_Email_` namespace across H2, SQLite, MySQL, and PostgreSQL.
- **Excel Splitter `.fyp`** (`fan.summer.excel`) — BY_SHEET / BY_COLUMN / COMPLEX split modes with a
  stateful four-step wizard, six manifest-declared AI tools, and authorized file read/write.
- **Markdown Editor `.fyp`** (`fan.summer.markdown`) — first official v2 plugin: server-side
  commonmark render via `invoke("render", {markdown})` plus a Vue split-editor + live preview.
- **`frontend/`** — Vue 3.5.39 + TS shell: sidebar (collapsible, categories), theme (dark/light),
  settings, AI chat (SSE + markdown + collapsible thinking), ToolGrid, and a micro-frontend host
  that dynamically imports each plugin's `uiEntry`.
- **`desktop/`** — Tauri 2.0 shell: spawns the Java sidecar (`--port=24056`), reads `FENGYU_PORT`
  from stdout, polls `/api/health`, injects the backend URL + token into the webview, kills the
  sidecar on close.
- **Publishable plugin toolchain** — the Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk`,
  independently versioned, GitHub Packages) and the npm packages `@infinia/plugin-sdk`,
  `@infinia/plugin-ui`, `@infinia/plugin-cli`, plus a release workflow with a clean-consumer smoke job.
- **Default Vue + Java scaffold** — `fengyu plugin create` produces a complete plugin by default: a
  Vue/Vuetify UI (`ui-src/`) backed by a Java JSON-RPC worker (`worker/`), with the Maven Wrapper, a
  build declaration, tests, and a GitHub Packages `settings.xml`. `--ui-only` retains the lightweight
  UI-only template.
- **Real-worker dev simulator** — `fengyu plugin dev` builds the worker JAR (if missing), starts the
  real Java JSON-RPC worker, and forwards the UI's `rpc.invoke` calls over `POST /__rpc`. Java source
  edits trigger a debounced rebuild + worker restart.
- **Declared build lifecycle** — `fengyu.plugin.json` drives an ordered, atomic pipeline
  (prepare → install → test → build → validate staging → package) with the Maven Wrapper (no system
  Maven fallback). `--skip-tests` skips tests only, never type checking or packaging.
- **Shared manifest contract** — a canonical `plugin-spec/manifest.schema.json` + fixtures shared by
  the CLI and the host, including `database` and `network.email` permissions and AI-tool `method` /
  object-schema validation.
- **Offline Python Builder `.fyp`** (`fan.summer.offlinepython`) — doctor, dependency search, project
  init, async wheelhouse builds with streamed logs, and output verification.

### ♻️ Changed
- **Vuetify 3 (Material Design 3) adoption** — full visual-language switch for the web shell and
  plugin micro-frontends, from the legacy `--sk-*` IntelliJ-token system to MD3. Theme driven by
  Vuetify's global singleton from `useThemeStore`; plugins share the host's Vuetify instance via
  `PluginContext.vuetify`.
- **Stateful plugin workflows** — `@infinia/plugin-ui` provides a controlled, persistent-ready step
  wizard with explicit states, async validation, branching, invalidation, and snapshots; the official
  Excel plugin adopts it with reload re-analysis and configuration replay, worker-faithful mode
  validation, safe output reselection, and explicit completion/download.
- **Refined plugin-ui surface** — `@infinia/plugin-ui` gains a theme-driven polish layer so plugins
  using `FyPluginShell` share one calm, low-elevation design language (hairline borders, soft primary
  active chips, a brand marker, de-uppercased buttons, denser fields/tables). Every color resolves
  through a Vuetify theme variable; the email green palette is explicitly excluded.
- **Node.js 24.18.0 baseline** — documentation, `plugin-cli` engine metadata, and every GitHub Actions
  workflow now use the same exact Node.js version, protected by a repository contract test.
- **Official plugins built by the CLI** — Markdown, Excel, and Email are packaged by
  `fengyu plugin build` (a CI matrix); the legacy shell packager and centralized source manifests are
  removed.
- **Offline-first install** — `fengyu plugin install` validates the archive (limits, paths, manifest)
  before any network access; unsafe or invalid packages are rejected with zero fetch calls.
- **Strict SDK RPC contracts** — the worker surfaces canonical JSON-RPC errors (`-32700` parse,
  `-32600` invalid request, `-32601` unknown method, `-32000` handler failure); the TypeScript client
  removes abort listeners on every settled path.
- **HeadlessLauncher** now selects `SetupApplication` (SETUP) vs `AiApplication` (APP, with
  `fengyu.mode=app`) based on `datasource.properties` presence; the desktop host restarts the sidecar
  on `SETUP_DONE` (exit 0) to enter APP mode.
- `AiConfigService` / `AiConfigServiceHeadless` / `EmailUtil` converted from static utilities to
  Spring beans scoped by `SecurityContext.currentUserId()`. Setup-wizard endpoints (`/api/setup/*`)
  bypass token auth (`TokenAuthFilter`).
- Email batch sending creates one message per parsed attachment tag; all matching contacts share the
  To/CC fields, To takes precedence over CC, and failed-item retry was removed.

### 🗑️ Removed
- `DatabaseInit`, all MyBatis mapper interfaces (12), `mybatis-config.xml`, all mapper XML (12), and
  the MyBatis dependency.
- All JavaFX code and dependencies (see Breaking Changes above).

### 🐛 Fixed
- **Headless fat-jar boot**: aligned `logback-classic`/`logback-core` versions (a split pair crashed
  on `JaninoEventEvaluatorBase` at first logger init); added shade `AppendingTransformer` for
  `AutoConfiguration.imports` (Spring Boot 4 splits web/Tomcat autoconfig across module jars — without
  merging, embedded Tomcat silently never started); emit `-parameters` so Spring MVC resolves
  `@PathVariable`/`@RequestParam` names.
- `VirtualUserInitializer` native INSERT now runs inside `@Transactional` (was throwing
  `TransactionRequiredException`).
- Atomic `.fyp` packaging: a failure at any stage leaves no `.fyp`, no `.tmp-*`, and no staging dir.
- Offline Python Builder now opens a writable project workspace, passes complete `FileRef` objects
  through the host bridge, reports translated job states, stops failed polling, and performs real
  build/deploy cancellation instead of changing UI state only.
- Email archive timestamps on SQLite (including upgrade migration), literal wildcard search,
  account/folder path isolation, UTF-8 filename limits, and temporary-file cleanup.

---

## [3.2.0] — IDEA 2025 New UI Redesign

**v3.2.0** — 2026-06-30

This release re-skins the app from glassmorphism-dark to the JetBrains **IDEA 2025 New UI** look: a flat, token-based theme with switchable **dark / light** themes, a collapsible sidebar, and native OS window chrome. Theming is driven by JavaFX looked-up color tokens (`-sk-*`) declared per theme on the scene root, so a theme switch is just a root class swap — no stylesheet reload.

### ⚠️ Breaking Changes

- **`.glass-*` CSS utility classes renamed to `.sk-*`** (in `fengyu-common.css`). External plugins that call `getStyleClass().add("glass-...")` or reference `.glass-*` selectors must update. The full mapping:

  | old | new |
  |---|---|
  | `glass-dialog` | `sk-dialog` |
  | `glass-field` / `glass-field-label` | `sk-field` / `sk-field-label` |
  | `glass-tab-pane` | `sk-tab-pane` |
  | `glass-combo` | `sk-combo` |
  | `glass-table` | `sk-table` |
  | `glass-checkbox` | `sk-checkbox` |
  | `glass-btn-primary` / `glass-btn-secondary` | `sk-btn-primary` / `sk-btn-secondary` |
  | `glass-notif-*` | `sk-notif-*` |

  > The external plugin repo ([`MuskStark/FengYu-Plugin`](https://github.com/MuskStark/FengYu-Plugin)) is updated separately; flag this rename when migrating third-party plugins.

### 🎨 Theme (strict tokenization)

- **Token set expanded 14 → 19** — added `-sk-shadow`, `-sk-scrim`, `-sk-success-soft`, `-sk-warning-soft`, `-sk-danger-soft` (each under both `.theme-dark` and `.theme-light`). Custom themes/stylesheets that hardcoded the old 14 must add these 5 or popups/dialogs/cards will have undefined shadows and status soft-fills.
- **Fixed popups rendering as un-themed white** — `GlassNotification` (toast/notify/confirm) loaded the stylesheet but never stamped the theme class on its scene, so every `-sk-*` token was undefined and all popups fell back to JavaFX default white in both themes. Root-cause fix via `Themes.applyTo(scene)`.
- **Removed all hardcoded colors** from popups, dialogs, `StepWizard`, `ToggleSwitch`, status labels, and CSS drop-shadows. Everything now resolves through `-sk-*` tokens and adapts correctly to dark and light themes. Notably `StepWizard` idle dots and `ToggleSwitch` off-track were invisible on the light theme.

### ✨ New

- **Dark / light theme system** — `fan.summer.api.theme.ThemeService` (API module, no DB dependency) holds the active `Theme.DARK`/`Theme.LIGHT`, stamps a `theme-dark`/`theme-light` class on every registered scene root, and fires `onChange` listeners. Switchable from the sidebar footer (☀/☾) and the Settings page; persisted in the `theme` setting (`dark` default).
- **Looked-up color tokens** (`-sk-bg`, `-sk-bg-elevated`, `-sk-text`, `-sk-accent`, `-sk-border`, …) declared per theme in `fengyu-common.css`; swapping the root class re-resolves every token with no stylesheet reload.
- **Collapsible sidebar** — `«`/`»` toggle between the label view and a 48px icon-strip; collapse state persisted via the `sidebar.collapsed` setting.
- **Native window chrome** — `StageStyle.DECORATED` gives the real OS title bar + close/min/max (macOS traffic lights), replacing the custom transparent window.
- `MarkdownRenderer.render(md, Theme)` / `renderPlain(md, Theme)` overloads (theme-aware dark/light CSS palettes); no-arg forms delegate via `ThemeService.current()`.

### ♻️ Changed

- `fengyu-common.css` rewritten: token definitions under `.theme-dark`/`.theme-light`, every component flattened to IDEA New UI style (neutral-gray selection with a left accent bar, slim 4–8px scrollbars, flat fields/buttons/tables/tabs/dialogs/notifications), all `.glass-*` → `.sk-*`.
- `shell.css` rewritten token-based for the New UI shell (`.app-root`, `.sidebar` + `.collapsed`, capsule `.search-bar`, flat `.tool-card`, `.detail-panel`, `.statusbar`, `.store-*`).
- `Themes.applyTo(scene)` now delegates to `ThemeService.registerScene(scene)` (loads the common stylesheet + stamps the theme class); the shared stylesheet load is factored into `Themes.loadCommonStylesheet(scene)` to keep the delegation non-recursive.
- `FengYuApp` reads the persisted theme on startup and registers the main scene with `ThemeService`.
- `AiChatPlugin` derives its WebView background from the active theme and re-renders the conversation live on theme change.
- Inline `#5b8cf7` accent literals replaced with `#3574F0` / the dark palette across the sidebar and Markdown link CSS.

### 🔥 Removed

- `fan.summer.ui.titlebar.TitleBar` — replaced by native OS window chrome.
- `fan.summer.ui.util.WindowResizeHelper` — native `DECORATED` resize/drag/maximize replaces it; the macOS `isMaximized()`-on-`TRANSPARENT` bug is gone with it.

---

## [3.1.0] — LangChain4j ChatBackend + Plugin-Owned AI Tools

**v3.1.0** — 2026-06-25

This release rebuilds the AI subsystem on LangChain4j and unifies the two cloud providers (OpenAI + Anthropic) into a single `CloudChatBackend` class behind a new `ChatBackend` interface. Plugins can now self-declare their own AI tools. The local tool-calling model is Qwen3-4B (Hermes `<tool_call>` + streamed `<think>` reasoning), running in a hardened out-of-process worker.

### ⚠️ Breaking Changes

- **`AiService` interface removed** — replaced by `ChatBackend`. External plugins calling `AiServiceProvider.getService()` must change the return type from `AiService` to `ChatBackend`. See [`docs/migration-3.1.md`](migration-3.1.md) for the migration guide.
- **`OpenAiService` and `AnthropicService` concrete classes removed** — replaced by a single `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` static factories. One unified class serves both providers.
- **`CloudAiConfigProvider` and standalone `StreamingResponseHandlerBridge` removed** — their logic moved into `CloudChatBackend` (config accessors are public methods on the class; the stream bridge is a private inner class).
- **`AiServiceImpl` renamed to `LocalChatBackend`** — pure rename, no behavior change.
- **`BuiltinAiToolRegistrar` removed** — plugins now self-register AI tools via `FengYuPlugin.aiTools()`; the central registrar and its startup call are gone.

### ✨ New

- **Plugins self-declare AI tools** via `FengYuPlugin.aiTools()` — the registry auto-registers/unregisters them on add/remove (including JAR hot-reload). No central registrar.
- `AiTool` interface declares per-mode visibility (`supportsLocal` / `supportsCloud`) and dual descriptions (`getDescription` / `getLocalDescription`); `AiServiceProvider.getTools()` filters by the active backend mode.
- `AiToolDescriptions` helper centralises cloud-rich / local-concise description templates.
- **Qwen3-4B local tool-calling** — Hermes `<tool_call>` parsing (`ToolCallParser`), `ThinkingStreamSegmenter` (THINK / CONTENT / tool-call stream splitting), `Qwen3Adapter` (Hermes system prompt + `/no_think` toggle), and a collapsible thinking card in the chat UI.
- New `ChatBackend` interface (`fan.summer.api.ai.ChatBackend`).
- New `CloudChatBackend` class with `openAi(...)` / `anthropic(...)` factories.
- `LocalChatBackend` (renamed from `AiServiceImpl`).
- `AiToolCall.of(id, name, arguments)` overload to preserve server-issued tool-call IDs when bridging from LangChain4j.
- Tests: `CloudChatBackendTest` (11) + adapter tests for `ChatMessageMapper` / `AiToolToToolSpecification`; `ThinkingStreamSegmenterTest` (11) + `LocalChatBackendMaxTokensTest` (3).
- Migration guide at [`docs/migration-3.1.md`](migration-3.1.md) (EN + ZH).

### ♻️ Changed

- All 16 builtin AI tools return standardized JSON `{success, summary, ...payload}`; tool descriptions follow a cloud-rich / local-concise dual template.
- `BuiltinToolRegistrar.register()` routes through `PluginRegistry.addPlugins` to auto-register plugin AI tools in one pass.
- **Unified `ChatBackend` interface** in `FengYu-Api` — non-sealed (Java forbids cross-module sealed permits). Two known implementors: `CloudChatBackend`, `LocalChatBackend`. UI consumers use `instanceof` checks; the interface itself is treated as opaque.
- **`CloudChatBackend` unifies OpenAI + Anthropic** in one class (~450 LOC). HTTP/SSE, tool-loop plumbing, and stream bridging are delegated to LangChain4j's streaming models; provider differences isolated to a `buildStreamingModel(...)` switch on an internal `Provider` enum.
- `SynchronousChatHelper` (browser planner) rewritten to use LC4j's synchronous `OpenAiChatModel` directly via `CloudChatBackend` config accessors.
- `AiServiceProvider` exposes `ChatBackend` everywhere (method names unchanged).
- Sampling parameters (temperature / topP / maxTokens) are honoured per-call — settings changes take effect on the next message without restarting the chat.
- Default `maxTokens` raised 512 → 2048 (the Qwen3 thinking-model floor), enforced once at the `chat()` entry so both the native and Java backends benefit.

### 🐛 Fixes

- **Qwen3 silent empty answer** — a thinking model truncated mid-`<think>` produced an empty answer because `stripThink` wiped the unclosed block. The `maxTokens` budget is now floored to `QWEN3_MIN_MAX_TOKENS` (2048) at the unified `chat()` entry, with a diagnostic warning when output survives only as a think block.
- **Qwen3 on the Java backend** leaked raw `<think>` tags into the answer — now routed through `ThinkingStreamSegmenter` (thinking → collapsible card) and stripped from the final answer/history, matching the native path.
- **`AiConfigService.getAiMaxTokens()` default** synced to 2048 (was a stale 512 that disagreed with the settings UI).
- **AI worker IPC** — the child process pins a dedicated `logback-worker.xml` (no `ConsoleAppender`) so worker logs no longer corrupt the line-delimited JSON pipe on stdout; stderr is drained on its own thread into the shared log.
- **AI worker native load** — the child JVM loads the llama.cpp library at startup (`NativeLoader.load()`) so `LlamaContext` construction no longer throws "Native library not loaded".
- **AI worker crash recovery** — `handleChildExit` waits for a real exit code instead of throwing `IllegalThreadStateException` on stdout EOF, so pending callbacks are released and auto-restart runs reliably.
- **Qwen3.5 hybrid-model warning** — filenames matching `qwen3.5` / `qwen35` now warn that the native worker is known to SIGABRT on multi-turn (use Qwen3-4B).
- **Cloud `testConnection()` null-message bug on macOS** — `ConnectException` with a `null` message now falls back to `e.getClass().getSimpleName() + ": " + e`.
- **Anthropic multi-round tool calling** — server-issued `tool_use_id` preserved through the `AiToolCall → LangChain4j → AiToolCall` round-trip (previously caused HTTP 400 on round 2).
- **Multi-turn conversation continuity** — the assistant's final reply is appended to `history` before the service returns.
- **OpenAI tool-round message ordering** — the assistant-with-tools message is appended before `ToolExecutor.executeAndFeed`.
- `pdf_merge.filePaths` parameter type fixed (`"array"` → `"string[]"`); enums declared for `base64.mode`, `hash_calculate.algorithm`, `color_convert.from/to`.
- `ToolExecutor` error output is always JSON `{success:false,error:...}`; `ExcelConfigureTool` success returns `success:true`.
- `testConnection()` `HttpClient` wrapped in try-with-resources; thread-safety hardening on the cloud stream handler.

### 🔥 Removed

- `BuiltinAiToolRegistrar` — superseded by plugin-owned `aiTools()`.
- FunctionGemma adapter and `OfflineNlNormalizer` — replaced by the Qwen3 path.

### ⬆️ Dependencies

- `dev.langchain4j:langchain4j-open-ai:1.2.0`
- `dev.langchain4j:langchain4j-anthropic:1.2.0`
- (1.0.1 was originally pinned but `langchain4j-anthropic` was never published at that version; bumped to the lowest GA where both modules co-exist)

### ⚠️ Known Behavior Changes

- `cancelGeneration()` on cloud backends is best-effort (LangChain4j 1.x does not expose mid-stream cancellation on streaming models); the in-progress flag is still cleared. Local mode is unaffected.
- Mid-stream SSE errors now surface via `callback.onError` on the JavaFX Application Thread.
- The local tool-calling model is Qwen3-4B; the native worker requests full GPU offload automatically on builds that ship a GPU backend.

### 📉 Net Code Change

- Deleted: `AiService` (117 LOC), `OpenAiService` (244 LOC), `AnthropicService` (283 LOC), `CloudAiConfigProvider` (22 LOC), `StreamingResponseHandlerBridge` (120 LOC), `StreamingResponseHandlerBridgeTest` (214 LOC), `BuiltinAiToolRegistrar`, FunctionGemma adapter + `OfflineNlNormalizer` ≈ **1000+ LOC removed**.
- Added: `ChatBackend` (86 LOC), `CloudChatBackend` (450 LOC), the Qwen3 toolchain (`ThinkingStreamSegmenter`, `Qwen3Adapter`, `ToolCallParser`), worker hardening, tests, migration guides ≈ **1100+ LOC added**.
- Net: roughly even on LOC, but cloud code is one unified class and local AI has a dedicated tool-calling model + isolated worker.

---

## [3.0.1] — FunctionGemma Offline Adaptation

**v3.0.1** — 2026-06-21

### ✨ New Features

- **FunctionGemma Multi-Round Tool Loop**: Host-driven `analyze → configure → execute` loop for the FunctionGemma-270m-it local model; tool-call tokens are suppressed during call rounds and only the final response is forwarded to the UI
- **Offline CN→EN Keyword Normalizer**: `OfflineNlNormalizer` rewrites Chinese tool-name keywords to English before local-model parsing, no network required (resource-backed `nl-normalizer.properties`)
- **Enum-Schema Tool Parameters**: `AiToolParam` gains an `enumValues` field; tool declarations now emit `enum:[...]` constraints to FunctionGemma, OpenAI, and Anthropic backends — materially improves small-model parameter reliability
- Enriched Excel AI tool descriptions and added enum constraints on `mode`/`action` parameters

### 🐛 Fixes

- Harden `FunctionGemmaAdapter` parser: 🪙 (U+1FA99) string delimiter correctly handles values containing commas, braces, and multiple tool calls in a single response
- Release `GGUFModel` mmap on unload via best-effort `unmap`
- Harden `GGUFReader` against malformed or truncated model files
- Serialise `PluginLoader` JAR load/unload on a single-thread scheduler
- Complete `LlamaRunner` generation cleanly when cancelled during prefill
- Drive `TokenBatcher` flushes off the FX thread
- Let the native AI worker exit gracefully before force-killing it
- Close target POI `Workbook` in `ExcelUtil` even when copy/write throws
- Low-priority stability cleanup (MDI font log, daemon UI threads)

---

## [3.0.0] — JavaFX Migration

**v3.0.0** — 2026-06-12

- Update app icons for v3.0.0 release
- Resolve static analysis warnings across codebase (Qodana)

**v3.0.0-rc.3** — 2026-06-10

- **Slash Commands**: Type `/` in AI chat to list available tools, get help on a specific tool, or invoke a tool directly without model inference — supports both direct execution and guided model parameter extraction
- **Plugin Resource Isolation**: Child-first `ClassLoader` for external plugins ensures plugin resources are resolved from the plugin JAR before the host; `PluginContext` provides TCCL switching on every plugin lifecycle call and event dispatch
- **Plugin Store Redesign**: Searchable, filterable card grid for the online plugin store with install state indicators and version comparison
- **AI Configuration Service**: Extracted `AiConfigService` centralizes AI configuration access, decoupling it from UI settings code
- **Email Archive**: New `email_archive` table, entity, and mapper for email archive storage
- Fix sidebar icons not displaying on Windows — switched from JavaFX `Font` icons to MDI webfont
- Fix email settings save always failing; now shows missing required field names
- Fix Excel complex split Phase 3 corrupting pre-existing output files — only merge into files created during the split operation
- Fix POI `NullPointerException` during cross-workbook cell style cloning when data format string is null
- Harden Excel Splitter progress callback with null guard
- Extract `StorePlugin` and `StorePluginLogic` from `OnlineStorePane` with unit tests
- Add GPLv3 license file to the repository
- Add JUnit 5 test dependency to `FengYu` module

---

**v3.0.0-rc.2** — 2026-06-05

- **Tool Favorites**: Bookmark tools with a star toggle on tool cards and the detail panel; favorites persist across restarts via H2 database and are filterable from the sidebar "Favorites" category
- **Lazy AI Backend**: Local AI backend (native/Java) initialization is deferred until the AI tool is first opened, improving startup performance; Java/Native inference engine toggle in AI settings
- **Plugin Uninstall**: Uninstall external plugins from the detail panel with confirmation dialog; closes ClassLoader, removes JAR file, and cleans up from registry
- **Install Toast Notifications**: Success toast notification when a plugin is installed from the online store or local JAR
- **Token Batching**: AI token output is batched at 50ms intervals to reduce FX thread flooding during high-speed generation
- **Crash Rate Limiting**: Native worker auto-restart respects a time window (3 crashes within 5 min) to prevent restart storms
- **Settings Cache**: App settings are cached in memory with debounced DB writes (300ms) to reduce database load during rapid UI interaction
- Fix native library loading on hardened Linux distros (UOS/Deepin/Kylin) where `SecurityException` is thrown for unsigned `.so` files
- Fix email batch sending mutating shared recipient lists across iterations
- Fix online store plugin catalog parsing — replaced hand-rolled string slicing with Gson-based `JsonHelper`
- Fix `WindowResizeHelper` double-attachment causing duplicate event filters
- Thread-safety hardening across `PluginLoader`, `PluginRegistry`, and `MainWindow` (`ConcurrentHashMap`, `volatile`, `synchronizedSet`)
- Stagger limit for tool card entry animations (max 30) to avoid creating hundreds of `PauseTransition` instances
- Fix plugin JAR deletion on Windows — retry with `System.gc()` hint, fall back to `deleteOnExit()` if file is still locked
- Fix `onUnload()` lifecycle callback not fired when unloading plugin JARs
- Fix cached plugin view not cleared when uninstalling an inactive plugin, preventing GC of plugin classes
- Fix English locale (`Locale.ENGLISH`) returning Chinese strings on Chinese-locale systems — `ResourceBundle` no longer falls back to JVM default locale
- Fix Windows no-JRE release zip redundantly including the fat JAR alongside the Launch4j exe (which already embeds it)

---

**v3.0.0-rc.1** — 2026-06-04

- **Browser Automation**: AI-callable `browser_automate` tool that automates web browsers via natural language; uses Playwright with the system's installed Chrome/Edge/Chromium (no separate browser download); observe-think-act loop with page DOM snapshots, CSS selector targeting, and a planner LLM
- **Resizable Window**: Edge and corner drag resize for the undecorated `StageStyle.TRANSPARENT` window via `WindowResizeHelper`; uses screen coordinates for macOS compatibility
- **Responsive Layout**: Dynamic `FlowPane` wrap length bound to viewport width; `windowPane` and `ContentArea` properly fill parent with `setMaxWidth/Height(Double.MAX_VALUE)`
- **Pure Java PDF-to-DOCX**: `PdfBoxToDocxConverter` using PDFBox for extraction and Apache POI for DOCX generation — no external Office installation required; three-tier page strategy (text → extracted images → full-page render fallback)
- **Native Backend Health Tracking**: `NativeLoader.FailureReason` enum for structured failure diagnostics; degraded-mode banner in AI chat when native acceleration is unavailable
- Fix macOS window resize not working due to unreliable `stage.isMaximized()` with `StageStyle.TRANSPARENT`
- Fix tool grid layout not responsive to window width changes
- Fix Playwright runtime attempting to download browser driver unnecessarily
- Fix AI browser planner recursively invoking `browser_automate` tool via tool injection loop

---

**v3.0.0-beta.2** — 2026-05-26
