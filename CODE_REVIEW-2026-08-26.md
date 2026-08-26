# FengYu 4.0.0 Full Code Review Report

| Item | Value |
|---|---|
| Review date | 2026-08-26 |
| Review baseline | Branch `4.0.0-folw`, commit `66117b67` (plus 3 uncommitted i18n working-tree changes, reviewed separately — see Appendix C) |
| Scope | Full backend (FengYu module: 279 Java files / ~39.4k lines + 145 test files), frontend (90 Vue/TS files / ~23.9k lines), Electron desktop shell (64 TS files / ~8.5k lines), plugin toolchain (~40 Java + ~40 TS files), official plugins (4), build/CI/scripts |
| Method | Seven parallel review agents, one per subsystem; every Critical/Major finding was personally re-verified against the source by the lead reviewer before inclusion |

## Executive Summary

The codebase's security and engineering discipline is well above average for this class of project: no Critical findings; high-risk surfaces — zip extraction defenses, worker process lifecycle, database credential encryption, constant-time token comparison, SSE resource recovery, the markdown sanitization pipeline, the iframe sandbox, and the Electron window security baseline — are mostly done right, usually with comments explaining the threat model and backed by tests or CI contract gates.

The remaining issues cluster into three groups:

1. **Child processes inherit the host environment and leak the primary API token** — in desktop mode, `FENGYU_AUTH_TOKEN` is inherited verbatim by third-party MCP servers and hook commands, despite correct precedents already existing in the repository.
2. **A data-correctness defect where mid-stream cancellation terminates as "success"** — a generation cancelled by the user ends via `onComplete`: partial text is marked done and staging is exported, directly conflicting with the intent of a recent fix commit.
3. **A handful of "default policy" gaps** — missing Host header validation (DNS rebinding surface), no effective CSP in the packaged desktop build, and Electron's default auto-approval of permission requests combined with the iframe `camera` delegation.

**Finding counts** (deduplicated): Critical 0 / Major 8 / Minor ~36 / Nit ~21.

---

## 1. Major Findings (fix before the 4.0.0 stable release)

> All 8 items below were personally re-verified against source by the lead reviewer.

### M-1 MCP stdio servers and hook child processes inherit the full host environment, leaking the primary API token

- **Location**: `FengYu/src/main/java/fan/summer/fengyu/ai/mcp/McpRuntimeManager.java:365-369`; `FengYu/src/main/java/fan/summer/fengyu/ai/hooks/HookDispatcher.java:190-204`
- **Problem**: In desktop mode the JVM receives `FENGYU_AUTH_TOKEN` (primary API bearer) and `FENGYU_BROWSER_BRIDGE_PORT/TOKEN` via environment variables (`desktop/electron/src/backend/spawn.ts:160-168`, `main.ts`). The MCP `StdioClientTransport` builds on a copy of the host environment and then overlays the configured env (decompiling mcp-core 2.0.0 confirmed `.environment().putAll(params.getEnv())` is additive semantics) — `sanitizeEnv` only sanitizes the *configured* env, not the *inherited* host env; `HookDispatcher.runCommand`'s `builder.environment().put(...)` likewise only adds, never removes.
- **Impact**: Third-party MCP servers (commonly `npx` packages) and hook commands contributed by trusted plugins can all read the primary token, call every REST endpoint on `127.0.0.1:24056` directly, **bypass the entire tool-approval system**, and drive arbitrary navigate/eval_js/screenshot via the browser bridge token. `DENIED_ENV_KEYS` only guards against interpreter injection, not credential exfiltration.
- **Fix direction**: Build child-process environments from an allowlist (PATH/HOME/TEMP + configured entries), reusing the existing `CommandExecuteTool.removeSensitiveEnvironment` / `PluginProcessManager.applyEnvironmentAllowlist` implementations; force https and forbid loopback targets for plugin-contributed HTTP hooks.

### M-2 Mid-stream cancellation terminates via `onComplete` (the success terminal), and the cancelled turn's staging gets exported

- **Location**: `FengYu/src/main/java/fan/summer/fengyu/ai/service/SpringAiCloudBackend.java:462-493` (the `runToolLoop` terminal branch) and `597-630` (`streamAndCollect`); `OllamaLocalBackend.java:376-391`, `468-502` is structurally identical
- **Problem**: On user cancellation, `cancelGeneration()` first calls `disposeActiveStream()` (dispose the subscription + count down the latch), then sets `cancelled=true` and interrupts. Reactor's **cancel signal does not deliver onError/onComplete downstream**, so `failure` stays null and `aggregated` is null → `hasToolCalls==false` → `callback.onComplete(...)` is invoked with the **partial** accumulated text (lines 486-492). The round-boundary `if (cancelled) throw` (line 442) only protects the next round, not the current one. If the interrupt wins the race and wakes `await` first, it goes to onError instead — nondeterministic. Existing tests only cover cancel-mid-tool, not mid-stream.
- **Impact**: AiController's SseCallback treats `done` as the sole success terminal → `lease.complete()` → `exportStaging` copies the **user-cancelled** turn's output into the user's named directory, and the client renders/persists the partial answer as complete — exactly what commit `ac09a560` (cancel → abort() discards staging) was meant to prevent.
- **Fix direction**: After `streamAndCollect`'s await returns (or after line 478's `streamError` null-check, before evaluating `aggregated`), add `if (cancelled) throw new AiServiceException("cancelled")`; fix both backends and add a mid-stream cancellation test.

### M-3 `/api/setup/**` unconditionally bypasses token auth, and this window can recur

- **Location**: `FengYu/src/main/java/fan/summer/fengyu/web/filter/TokenAuthFilter.java:64`; together with `setup/SetupController.java:123-176`, `HeadlessLauncher.java:139-144`
- **Problem**: `/api/setup/**` is unconditionally allowed through the filter, without checking whether a token is configured. The wizard window does not only appear on first launch: `HeadlessLauncher.probeAndDecide`, when "config exists but DB unreachable" (remote MySQL hiccup, laptop offline), backs up the config to `.bak` and degrades back to SETUP — a recurring unguarded window.
- **Impact**: A local process (or a rebinding page per M-4) can `POST /api/setup/initialize` to overwrite `datasource.properties` and point the app at an attacker-controlled database — after which all user data (including AI provider API keys stored in the DB) flows to the attacker; `DELETE /api/setup/config` enables repeated resets (DoS); `test-connection` makes the process open credentialed JDBC connections to arbitrary host:port (intranet probing).
- **Fix direction**: Stop allowing that prefix when `fengyu.auth.token` is set — the wizard frontend `client.ts` already attaches `X-FengYu-Token`, so the desktop SETUP flow is unaffected; the browser-dev scenario with auth disabled stays usable. This brings the wizard under token protection at zero cost.

### M-4 No Host header validation anywhere: DNS rebinding defeats "loopback-only bind" against browser tabs

- **Location**: `TokenAuthFilter.java:30-32` (comment claims the loopback bind keeps random tabs out); no Host/`getServerName` validation anywhere in the repo; the desktop browser bridge `desktop/electron/src/browser/bridge.ts:45,83` likewise lacks Host validation and compares tokens with `!==` (not constant-time)
- **Problem**: Binding to 127.0.0.1 does not stop malicious websites: after resolving a domain to `127.0.0.1`, a page's fetch to that domain on port 24056 is a **same-origin request** as far as the browser is concerned — SOP does not block it and the response is readable. The author was aware of this surface (the line 97 comment explicitly mentions "via DNS rebinding from a browser tab") but only used it to justify constant-time comparison, not to add Host validation.
- **Impact**: With the token enabled, the rebinding-reachable surface is `/api/health` (harmless), `POST /api/workflow-hooks/*` (requires a 32-byte secret, safe), and `GET/HEAD /plugin-runtime/**` (see M-5). **With auth off** (manual `java -jar` without `--token`, IDE launches — the desktop dev default described in AGENTS.md), any website can drive the entire API: change settings, start agent runs, read session history and redacted AI config, etc.
- **Fix direction**: Validate the Host header at the start of `TokenAuthFilter`, allowing only `127.0.0.1[:port]` / `localhost[:port]` / `[::1][:port]`, returning 403 otherwise (optionally also check Origin); add `crypto.timingSafeEqual` and Host validation to the browser bridge. One change tightens both the auth-on and auth-off forms.

### M-5 `/plugin-runtime/{id}/**` GET/HEAD is token-exempt, and asset reads are not limited to the UI directory

- **Location**: `TokenAuthFilter.java:69-71`; `web/controller/PluginRuntimeController.java:164-182`; `plugin/market/PluginPackageService.java:178-183` (`asset()` only does path containment, not restriction to the ui directory)
- **Problem**: The filter unconditionally allows `GET/HEAD /plugin-runtime/**` (the rationale being that iframe navigation cannot carry custom headers). `backend/worker.jar` and every asset packaged into a plugin (potentially including third-party embedded credentials, mail templates) can be downloaded without a token; the CSP header only constrains page behavior, not direct GETs. Combined with M-4, any rebinding page and any other local user can pull all installed plugin package bytes (the `PluginRuntimeController` lines 164-182 comment itself acknowledges this threat model).
- **Fix direction**: Switch asset loading to `StreamTicketService`-style single-use/short-lived signed URLs (iframe src can carry a query ticket), or at minimum narrow servable paths to the `ui.entry` directory, plus the Host/Origin validation from M-4.

### M-6 The packaged desktop build's main window effectively has no CSP

- **Location**: `desktop/electron/src/window/create-window.ts:121-134` (header injection) and `:176` (`loadFile` → file://); `frontend/vite.config.ts:98-108` (comment explicitly admits the header hook "never fires for file://" and that the desktop build deliberately skips the meta CSP because it would white-screen under file:// opaque origins)
- **Problem**: Electron's `webRequest.onHeadersReceived` never fires for file:// requests (electron#23485), so the carefully constructed CSP only takes effect in dev (http://127.0.0.1:5173); meanwhile the desktop build deliberately bakes no meta CSP — the two mechanisms pass the buck to each other, leaving the production renderer with no CSP at all. The `create-window.ts:120` comment does not mention this limitation, so maintainers can easily believe the CSP is active.
- **Impact**: The Electron Security Checklist CSP item is unmet. If the SPA is ever XSS-ed (it renders AI output and plugin-provided data), there is no `connect-src` (unrestricted data exfiltration), no `frame-src`, no `object-src 'none'` backstop.
- **Fix direction**: Register a standard custom protocol (e.g. `app://`) via `protocol.handle()` to load the SPA (the Electron official security docs' recommended approach), making the origin non-opaque so header/meta CSP works; also replace the production `script-src`'s `'unsafe-inline'` with the importmap sha256 (already demonstrated by the web-release variant at `vite.config.ts:137-143`).

### M-7 No `setPermissionRequestHandler` + the plugin iframe already delegates `allow="camera"` → third-party plugins can silently enable the camera

- **Location**: `desktop/electron/src/ipc/displayMedia.ts` (only registers `setDisplayMediaRequestHandler`); `frontend/src/views/PluginView.vue:295` (`allow="display-capture; camera"`); repo-wide grep finds no `setPermissionRequestHandler` at all
- **Problem**: Electron **auto-approves all permission requests by default** when no permission handler is set (electron#12931 / Doyensec analysis). `getUserMedia` (camera/microphone), notifications, geolocation, etc. all take the default-approval path, and the plugin iframe's `allow` attribute already delegates `camera`.
- **Impact**: Any installed third-party plugin UI (trusted at install time, code never audited, untrusted code hosted in a sandboxed iframe) can call `getUserMedia({video:true})` to enable the camera — with no application-level prompt on Windows/Linux (macOS relies on system TCC). The `getDisplayMedia` side likewise has no screen picker and no manifest permission gate (acknowledged in a code comment).
- **Fix direction**: Register `session.defaultSession.setPermissionRequestHandler`, default-deny, allowing `media` only per the manifest permission model or a user gesture; make the `camera` delegation per-plugin-declared.

### M-8 BrowserTool has no private-network/loopback restriction, asymmetric with `web_fetch` policy, and the permission-rule syntax cannot cover it

- **Location**: `ai/tools/BrowserTool.java:86-94` (navigate does no URL validation); `desktop/electron/src/browser/handlers.ts:122` (only a `^https?://` scheme check); `ai/tools/ToolGuardService.java:187-190` (`accessFor` only extracts URLs for `web_fetch`/`web_search`)
- **Problem**: The AI can navigate to the local Ollama (:11434), router admin pages, or any intranet host, read content with `browser_get_text`/`browser_snapshot`, and execute `browser_eval_js` in the target origin. `WebFetch(domain:...)` rules get no URL for `browser_*` calls, so users cannot express "the browser must not visit this domain".
- **Mitigations** (verified present): in non-FULL_ACCESS modes every navigate/eval_js is an EXTERNAL effect requiring per-action approval; the browser window is visible; the admin API requires a bearer token and the browser uses a separate partition. In FULL_ACCESS mode everything is open.
- **Fix direction**: Have `ToolGuardService.accessFor` extract URLs for `browser_navigate`/`browser_new_tab` so the existing rule syntax covers them; or ship a built-in deny preset that warns on loopback/private-network targets by default.

---

## 2. Minor Findings

### Backend — AI chat subsystem

| # | Location | Problem | Fix direction |
|---|---|---|---|
| A1 | `OllamaLocalBackend.java:352` | No hard cap when `maxToolRounds=0` (unlimited); the cloud-side `HARD_MAX_TOOL_ROUNDS=200` guard (`SpringAiCloudBackend.java:433`) was never ported. A runaway model plus an auto-approve allow rule can leave `generating` permanently true, rejecting all subsequent requests | Copy the cloud-side normalization |
| A2 | `OllamaLocalBackend.java:252-267` | Missing the 7371a318 fix (clear `generating` before onError); in local mode a "Generation already in progress" race exists between the onError synchronous chain and the finally-flag-clear (same shape as the beta.1 CI flake) | Port the cloud-side catch-block structure |
| A3 | `SpringAiCloudBackend.java:308/313/386`, `OllamaLocalBackend.java:242/247/311` | Lost-cancellation race: a cancellation completing after the CAS succeeds but before the worker executes `cancelled=false` gets overwritten (extremely narrow window) | Move `cancelled=false` before the spawn |
| A4 | `SpringAiCloudBackend.java:318`, `OllamaLocalBackend.java:252`, `AgentRunner.java:321`, `BackgroundTaskRegistry.java:436-455` | `Error` (OOM/StackOverflow etc.) escapes `catch (Exception)`: SSE hangs on heartbeat, `activeStreamId` is held forever (subsequent /stream requests all rejected until the client disconnects), background tasks stuck RUNNING and never evicted | Add an outer `catch (Throwable)` that notifies before rethrowing; have the Registry record FAILED in finally |
| A5 | `AgentRunPersistenceService.java:149-156`, `AiMemoryService.java:130-132` | Two instances of `@Transactional` self-invocation defeat: snapshot updates and event appends actually commit independently; the annotation's atomicity promise is false | Split into a separate bean or remove the misleading annotation |
| A6 | `BackgroundTaskScheduler.java:324-355` | `deleteWorkflow` only marks the DB entity CANCELLED, not the in-memory `Schedule.status`; between monitor release and commit, `tick()` can fire a to-be-deleted schedule once more | Set the in-memory CANCELLED inside the method |

### Backend — AI tools/skills/workflows

| # | Location | Problem | Fix direction |
|---|---|---|---|
| B1 | `ChatFileGrantService.java:162-173` | Staging export's `Files.copy` follows symlinks by default: a sandbox-restricted worker can place a symlink in staging, and the host export copies the target file's contents into the output directory (an actual leak when the output directory happens to be plugin-readable) | `NOFOLLOW_LINKS` + reject symlink entries |
| B2 | `WorkflowWebhookTriggerService.java:117-146` | Webhook triggers snapshot the creating session's permission mode: a webhook created in a FULL_ACCESS session thereafter executes arbitrary commands unattended with just the secret | Disable FULL_ACCESS for non-interactive triggers or require re-confirmation |
| B3 | `SkillPackageService.java:109-124`, `SkillMarketplaceService.java:81-85` | The skill marketplace allows plaintext-http downloads with no checksum/signature requirement (the plugin marketplace already has `require_checksum` hardening); catalog fetches have no response size cap. Skills are text injected into the system prompt — supply-chain sensitivity is non-trivial | Default to https only, cap catalog bytes, introduce checksums |
| B4 | `WorkflowExecutionService.waiters` | Waiters registered by `aiSink` are only removed by `waitForAiRun`; if startForAi succeeds but the task throws before arrival, small objects accumulate slowly | Also remove in `whenComplete` |

### Backend — plugins/database/updates

| # | Location | Problem | Fix direction |
|---|---|---|---|
| C1 | `PluginFileGrantService.java:57` | Upload filename `""`/`".."` makes `getFileName()` return null → NPE → HTTP 500 (client-controlled filename) | Validate non-empty/non-dot, or fall back on null |
| C2 | `PluginProcessManager.java:762-779` | The worker stderr pump's `catch (IOException ignored)` also swallows the "line over 1MB" overrun and the pump thread exits silently: after a runaway worker's stderr flood there is a monitoring blind spot, and the runaway signal itself is unobservable (only the stdout channel fails hard) | log.warn + persist on overrun; consider tearing down the worker for parity |
| C3 | `PluginTrustStore.java:72-77` | Signing-key namespace prefix match `id.startsWith(prefix)` lacks a `.` boundary: a key authorized for `a.b` can sign `a.bevil` | `id.equals(prefix) \|\| id.startsWith(prefix + ".")` |
| C4 | `AgentContentInstaller.java:60-86` | Deletes first, installs after, no rollback: a failure leaves a half-installed state and destroys the user's local modifications in the skill directory (the `.fyp` path has journal+backup; this path has no equivalent) | staging + atomic move |
| C5 | `SkillPackageService.java:176-198` | Zip extraction has a 50MB decompression cap but **no entry-count cap** (the plugin side has a 10,000-entry cap): a flood of empty entries can exhaust inodes | Copy the plugin-side cap |
| C6 | `SelfUpdateService.java:280-301` | Downloads land in `update-staging-<ts>.jar` (512MB cap) and are only cleaned on hash-mismatch/copy-exception paths; a JVM crash between download and the restart script's `mv` leaves permanent residue that accumulates | Sweep stale staging files at startup by mtime |

### Backend — web/security

| # | Location | Problem | Fix direction |
|---|---|---|---|
| D1 | `setup/CryptoUtil.java:36-37,74-91` | The machine-bound encryption key `.machineid` sits on the same disk, readable by the same user as the ciphertext (trade-off declared in javadoc): a malicious plugin worker (macOS reduced-sandbox tier, no Windows sandbox) can decrypt DB credentials | Document the keychain deployment, or derive from the OS credential store by default on macOS/Windows |
| D2 | `web/GlobalExceptionHandler.java:119-137,97-106` | Unclassified exceptions' `ClassName: message` and IOException local paths enter HTTP response bodies (helps filesystem probing in the M-4 rebinding + auth-off scenario) | Return generic text on 500 paths, keep detail in logs |
| D3 | `AiConfigController.java:92-96` | `maskKey` returns the first 4 characters for keys ≤8 chars (50% of the entropy prefix) | Return only `***` for ≤12 chars |

### Frontend

| # | Location | Problem | Fix direction |
|---|---|---|---|
| E1 | `Settings.vue:1238` | `$t('common.save')`/`$t('common.delete')` keys do not exist (en/zh `common` only has back/cancel/confirm/copied/loading/off/on/retry): the MCP server detail footer's save/delete buttons render the literal key names — **visible broken copy** | Add both keys in en+zh |
| E2 | `AppShell.vue:41` | `t('tools.title')` key does not exist (no `tools` node): when `/plugin/:id` has an empty id the page header shows the literal key | Add the key or change the reference |
| E3 | `FlowNodeInspector.vue:734` | Third-party manifest `docsUrl` is bound to `:href` without a scheme check (schema declares plain string); the repo itself decided in `PluginMarket.vue:98-108` (`safeHomepage`) that such catalog fields must block `javascript:` URIs — the same protection is missing here | Reuse the https?/mailto check or add a schema pattern |
| E4 | `main.ts` | No `app.config.errorHandler` and no global `unhandledrejection` listener: stray errors in a long-lived desktop webview only reach the console | Attach a minimal errorHandler wired to notifications |
| E5 | `SetupWizard.vue` | First-launch wizard is half-localized: field labels go through i18n while titles/buttons/status copy is hardcoded English — the first-run experience for Chinese users | Complete the en/zh keys |
| E6 | `StoreSourceManager.vue:43,45`, `stores/pluginStore.ts:68-77` | `refreshSource`/`deleteSource` throw without setting `error.value` and the buttons have no catch: backend failures leave the UI silent + unhandled rejections | Catch and set error like install/uninstall |
| E7 | `stores/aiSession.ts:59-62,332-334` | `installedPlugins`/`loadInstalledPlugins` are dead code with no consumers (only their own unit test), and have no internal catch | Delete or document why kept |

### Desktop shell

| # | Location | Problem | Fix direction |
|---|---|---|---|
| F1 | `updater/portable-updater.ts:143-155` | Over FY-Proxy plain HTTP, the digest and zip come from the same MITM-controllable response (guards corruption, not replacement); the GitHub HTTPS path has no byte verification at all | Document the threat model; long-term, sign distributions |
| F2 | `updater/portable-updater.ts:380-395` | Portable update relaunches even if the second robocopy fails: can boot a "new resources + old executable" hybrid tree, no warning, no rollback | Abort relaunch on RC≥8 + explicit failure marker |
| F3 | `window/create-window.ts:50-52` | (Effective once M-6 is fixed) the production CSP `script-src` contains `'unsafe-inline'` (root cause: inline importmap in index.html) | Compute the importmap sha256, like the web-release variant |
| F4 | `updater/portable-updater.ts:381,389` | `robocopy`/`ping` invoked by bare name in the replace script, inconsistent with the same script's `%SystemRoot%` absolute-path PATH-shadowing hardening for tasklist/find/taskkill | Add the absolute-path prefix |

### Toolchain + official plugins

| # | Location | Problem | Fix direction |
|---|---|---|---|
| G1 | `toolchain/cli/src/create.mjs:26,75-78` | The id pattern allows all-numeric segments: `123.456` generates digit-leading package/class names — illegal Java identifiers, deferring failure to the user's first compile (contradicts the file's own stated fail-fast intent) | On `runtime=java` paths, validate that no segment starts with a digit |
| G2 | `toolchain/sdk-java/pom.xml:64,69-70`, `devkit-java/pom.xml:42-44` | JUnit 5.10.2 (2024-02), compiler-plugin 3.13.0, surefire 3.2.5 each lag about two minors; JUnit 5.x is now in bug/security-fix-only maintenance (6.x is GA, Java 17 baseline — this project is JDK 21). **Not EOL, so not a repo-rule blocker** | Routine bump to 5.14.x or plan 6.x |
| G3 | root `pom.xml:62` | commonmark-java 0.24.0 (2024-11; current 0.30.0): markdown→HTML is an XSS-sensitive surface, five minors behind | Routine bump + run the markdown plugin tests |
| G4 | `plugin-offlinepython/.../BundleReader.java:44-46`, `DeployService.java:128-130` | Bundle zip entries read with `readAllBytes()` have no size cap and `Files.copy` has no total-decompression cap (weak threat model — bundles are usually self-produced; defense in depth) | Add caps following the cli `archive.mjs` 100MB/300MB pattern |
| G5 | `toolchain/dev/src/simulator-html.ts:57-72` | Only `manifestJson` gets `<` escaping; `environment` and `iframeSrc` are interpolated directly into `<script>` (JSON.stringify does not escape `<`): inconsistent protection standards within one file (minimal real incremental risk, dev tooling) | Route all three injection points through one `safeJson()` |

---

## 3. Nits (selected)

- `TokenAuthFilter.java:61-71`: the allowlist uses `startsWith` on the raw undecoded URI, relying on container normalization behavior; match on the decoded+normalized path instead (defense in depth).
- `H2TcpServerConfig.java:86-89`: `-ifNotExists` lets local processes create database files at arbitrary paths via the dynamic port (local processes have equivalent capability anyway; no privilege gain).
- `SkillController.java:51-53`, `TokenAuthFilter.java:41`, `WebConfig.java:14-24`: stale comments (inaccurate allowlist description; "Tauri" is actually Electron) — such comments are what audits rely on and should be corrected.
- `OllamaLocalBackend.java:626-640`: `probeReachable`'s HttpClient is never closed (currently no callers; legacy hook).
- `ai/session/ChatSession.java`: non-thread-safe legacy class with no references repo-wide; recommend deleting.
- `AiConfigServiceHeadless`: some static getters lack INSTANCE null-guards (NPE only in pure unit-test environments).
- `AiController`: the pending-100 cap check and the `put` can slightly overshoot; sweeping of expired pending turns only happens on the next POST /chat.
- `BrowserTool.java:52-58`: `safeFromEnv` only catches IllegalStateException; a non-numeric `FENGYU_BROWSER_BRIDGE_PORT` throws NumberFormatException and prevents application startup.
- `PluginIntegrityStore.java:289`: digest loop uses `> 0` instead of `!= -1` (currently unreachable).
- `SelfUpdateService.java:440-459`: update-script cp/mv lines quote paths with double quotes only, without shellQuote (paths come from local launch args; self-harm surface).
- macOS worker resource monitoring forks `ps` every second (`PluginProcessManager.java:1099-1113`).
- `AppShell.vue:55-57`: the "More options" button is a dead control with no @click + hardcoded English aria-label.
- Large components `FlowBuilder.vue` (2350 lines) / `FlowNodeInspector.vue` (1579 lines) / `Settings.vue` (1550 lines): partially mitigated by tested composables; directional note only.
- `desktop/electron/src/ipc/notification.ts:15-33`: `notification:show` has no origin/rate limiting (an XSS-ed SPA could phish with forged system notifications).
- `desktop/electron/src/browser/session-hub.ts:79-145`: contexts/tabs have no count cap (self-DoS).
- `toolchain/devkit-java/.../PluginDevServer.java:112`: AUTH token compared with `String.equals` (unexploitable in the loopback+local-file scenario).
- `frontend/package.json:31`: `@types/node ^22` inconsistent with the Node 24 baseline (types layer only).
- `desktop/electron/package.json:24`: Electron 43.4.0 vs current 43.4.1 (routine patch).

---

## 4. High-Risk Surfaces Checked and Found Sound

**Auth & exposure**: constant-time token comparison (`MessageDigest.isEqual`); desktop token is `zf-`+32 random bytes via env (never in argv/logs); single-use SSE tickets (32B SecureRandom, 60s TTL, endpoint-bound, 10k cap); webhook secrets stored as SHA-256 only + constant-time comparison + at-most-once claim; loopback-only bind double-insured (yml + programmatic default); H2 TCP forced loopback without `-tcpAllowOthers`; CORS limited to loopback any-port; no websocket endpoints; actuator exposes only health/metrics behind the token.

**Injection surfaces**: `.fyp`/`.fys` extraction both have zip-slip guards + decompression-size/entry-count caps (only the skill side lacks the entry cap — see C5); export zips reject symlinks + toRealPath prefix checks; JDBC URL metacharacter injection specifically rejected; the only native SQL is parameterized; DDL identifiers whitelist-sanitized; `ProcessSandbox` uses argv arrays throughout; ComputerApps name whitelist with no shell concatenation; restart-script paths shellQuoted; CI has no unsanitized `github.event.*` interpolation.

**AI approvals & tools**: catastrophic-command hard floor (`rm -rf /` collected across the whole invocation, mkfs/dd, unverifiable commands force human approval even in FULL_ACCESS); `execute_command` has sensitive-env scrubbing + timeout/output caps + process-tree kills; `web_fetch`/`web_search` have scheme allowlists + per-hop private-network revalidation + 5-hop/2MB caps; all MCP tools wrapped as EXTERNAL for approval; tool-name uniquification ordered by trust so third parties cannot shadow host tools; skill progressive disclosure (system prompt carries only id+description + an "untrusted data" declaration) + double path fencing.

**Resources & concurrency**: all four SSE implementations (heartbeat, disconnect/error/completion triple recovery, rebuffering deduped by seq, buffer caps); the `generating` CAS single-generation gate; TurnLease/SseCallback terminal-state CAS at-most-once and at-least-once; the four request-side ThreadLocal contexts cleared in finally; grant failure paths self-revoke (verified for 7034a355); `AgentRunner` try-with-resources + `invokeAll` cancel(true).

**Plugin system**: worker crash backoff (20s/3 strikes/exponential cooldown) + process-tree reaping (Windows Job Object) + `@PreDestroy` full cleanup; JSON-RPC frame caps (stdout 16MB/stderr 1MB) + malformed lines degrade without killing the channel + cooperative cancellation; a positive environment-variable allowlist (which makes M-1 a missed application of an existing pattern, not an oversight); overwrite installs have transactional journal + atomic swap + startup recovery + preflight rollback; DB credentials SecureRandom 32B + AES-GCM + 0600 atomic writes + scheduled revoke reconciliation; iframe CSP `connect-src 'none'` + strict postMessage origin checks + proactive `allow-same-origin` drop when isolation is unavailable.

**Updates**: self-update SHA-256 checksums + optional Ed25519 fail-closed + 512MB cap + byte-rate limiting; FY-Proxy explicitly rejects non-portable shapes; plugin downloads over http require a digest.

**Frontend**: all 3 v-html sites go through marked + DOMPurify (FORBID_TAGS/ATTR, forced noopener on external links, escaped code blocks, LRU caching); tokens never touch localStorage, SSE exchanges tickets first; all three SSE streams self-manage reconnection and clean up on unmount; timers/listeners paired and cleaned (verified one by one); Pinia race guards (monotonic applySeq); localStorage used only for flow drafts with TTL/shape validation.

**Desktop shell**: all three window creation sites have contextIsolation/sandbox on, nodeIntegration off; setWindowOpenHandler deny-all + openExternal whitelisted to http(s); preload exposes a read-only snapshot + 7 narrow IPC channels (no arbitrary file read/write, no exec passthrough); the JVM sidecar exit chain is complete (before-quit graceful → SIGKILL backstop → uncaughtException kills the tree); unsigned builds force auto-download/install off + native confirmation; the browser bridge binds 127.0.0.1 only + per-request token + request-body caps + per-context/tab partitions; logs contain no tokens/credentials throughout; e2e matches the AGENTS.md pitfalls (opt-in gating, domcontentloaded waits).

**Build & versions**: the app line 4.0.0-beta.5 is consistent across seven places (root pom revision, .mvn/maven.config, frontend/desktop package.json, four plugin manifest.base.json) + the CI `assert-release-versions.mjs` gate; the toolchain line 2.1.0 is consistent across eight places; the two lines do not cross-contaminate. Dependency checks (Electron 43.x / Vite 7.3 security-backport line / Spring Boot 4.1 / POI 5.5.1 / Gson 2.13.1 / jsoup 1.21.2 / Vue 3.5.39 / vue-i18n 11.4.8 / vitest 4.1.10 / Playwright 1.62, etc.) are all on maintained lines with no EOL blockers. The stale "five official plugins" count mentioned by AGENTS.md has already been fixed in `release-workflow.test.mjs` and the app-release SKILL.md (that AGENTS.md paragraph itself is now stale and can be cleaned up in passing).

---

## 5. Recommended Fix Order

1. **One small `TokenAuthFilter` patch closes three items**: setup requires auth when a token is set (M-3), Host header allowlist (M-4), plugin-runtime narrowing (M-5). Small change, large payoff.
2. **Child-process environment allowlist (MCP + hooks)**, reusing the existing `CommandExecuteTool`/`PluginProcessManager` scrubbing (M-1).
3. **Mid-stream cancellation `onComplete` fix** (M-2, both backends) + port the three cloud-side fixes to Ollama (A1/A2/A3).
4. **Electron permission handler + desktop CSP landing** (M-6/M-7).
5. **Quick frontend i18n missing-key fixes** (E1/E2 — visible broken copy, minutes of work).
6. Handle the remaining Minors per subsystem across iterations; G2/G3 routine dependency bumps can ride the next release.

---

## Appendix A: Review Coverage

| Area | Scope | Size |
|---|---|---|
| Backend web/security/config | `web/`, `security/`, `config/`, `setup/`, HeadlessLauncher | ~7.5k lines |
| Backend AI chat | `ai/` root + `service/`, `agent/`, `tasks/`, `session/`, `memory/`, `metrics/`, `util/` | ~9.7k lines |
| Backend AI tools | `ai/tools/`, `skill/`, `workflow/`, `hooks/`, `mcp/`, `ai/config/` | ~11k lines |
| Backend plugins/db/update | `plugin/`, `database/`, `update/`, `notification/`, `log/`, `runtime/`, `utils/` | ~10.6k lines |
| Frontend | all of `frontend/src` | ~23.9k lines |
| Desktop shell | all `desktop/electron` TS + builder config + e2e | ~8.5k lines |
| Toolchain+plugins+build | `toolchain/*`, `OfficialPlugins/*`, pom/package.json, `scripts/`, `.github/workflows/` | ~15k lines |

## Appendix B: Method and Confidence Notes

- Each area was reviewed by an independent agent: full reads of entry points/core classes + dangerous-pattern greps (`v-html`, `ProcessBuilder`, `ZipInputStream`, `readLine`, `Files.copy`, `new URL`, `setWindowOpenHandler`, `environment()`, etc.) each chased down and verified + cross-referencing recent fix commits to confirm intent.
- All 8 Major findings were re-verified line-by-line by the lead reviewer before inclusion (including the TokenAuthFilter exemptions, the `streamAndCollect` cancellation path, MCP/hook environment inheritance, the vite.config.ts desktop CSP-skip comment, the missing permission handler, and the i18n key-set comparison); Minors/Nits are agent-reported, of which E1/E2 were also personally verified.
- Version-consistency and dependency-EOL checks are based on actually reading files and upstream research, not on documentation claims.

## Appendix C: Uncommitted Working-Tree Changes

`frontend/src/i18n/en.json`, `zh.json`, `frontend/src/views/Settings.vue` (+3/-1): switches the update-channel save button from the misused `aiSettings.save` ("Save AI Config") to a new `settings.save` ("Save"/"保存"), with en/zh keys aligned and no dead keys left behind. **Verdict: a correct copy bug fix, committable as-is.**
