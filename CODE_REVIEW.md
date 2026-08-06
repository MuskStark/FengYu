# FengYu 4.0.0-alpha.8 — Code Quality & Security Review

**Review date:** 2026-08-05
**Scope:** Full reactor — backend (`FengYu/`), frontend (`frontend/`), desktop shell (`desktop/electron/`), plugin toolchain (`toolchain/`), official plugins (`OfficialPlugins/`), build/release/CI.
**Method:** Five parallel focused audits (backend security, backend quality, frontend, Electron, toolchain+build) plus verification of every CRITICAL/HIGH finding against source.
**Stat at a glance:** ~405 Java + 172 TS + 51 Vue + 7 JS files; 178 test files; version `4.0.0-alpha.8` consistent across all app manifests.

> **Headline:** No CRITICAL *exploit* in the running app — the loopback bind, robust process-tree kill, zip-slip/expansion-bomb defenses, and DOMPurify-everywhere posture are genuinely well built. However, **unsigned auto-updates ship and install silently**, the **host-level e2e smoke test is never run in CI**, and there is an **unbounded memory leak on every successful plugin call**. These three should be fixed before any public release.

---

## Severity Summary

| # | Severity | Area | Finding |
|---|----------|------|---------|
| 1 | 🔴 CRITICAL | Desktop | Auto-updater ships and installs **unsigned** updates |
| 2 | 🔴 CRITICAL | CI | `scripts/e2e-smoke.sh` covers the full backend→worker RPC path but is **never run in CI** |
| 3 | 🔴 CRITICAL | CI/Build | Qodana scans on **JDK 17** while the project targets **JDK 21** |
| 4 | 🟠 HIGH | Backend | `Worker.pending` map leaks an entry on **every successful** plugin invoke |
| 5 | 🟠 HIGH | Backend | Auth-token comparison is **not constant-time** (`String.equals`) |
| 6 | 🟠 HIGH | Desktop | Backend JAR/JRE binaries are **not integrity-checked** and live in a **user-writable** path |
| 7 | 🟠 HIGH | Toolchain | JSON-RPC stdio channel has **no frame-size limit** (DoS/OOM) |
| 8 | 🟠 HIGH | Toolchain | Manifest `permissions` are **not enforced** at RPC dispatch time |
| 9 | 🟠 HIGH | Frontend | **No 401 handling** and **no auth route guard** (token failures surface as generic errors) |
| 10 | 🟠 HIGH | Build | No `maven-enforcer-plugin` — logback split-pair is hand-pinned instead of converged |
| 11 | 🟡 MED | Backend | ~250 lines of tool-loop logic **duplicated** between the two chat backends |
| 12 | 🟡 MED | Backend | JDBC URL parameter injection via **unvalidated** setup-wizard fields |
| 13 | 🟡 MED | Backend | POSIX sandbox profiles are broad; `Backend.NONE` silently downgrades AI exec |
| 14 | 🟡 MED | Backend | Several **swallowed exceptions** with no logging |
| 15 | 🟡 MED | Desktop | `window-all-closed` no-op registered on **all** platforms (breaks Windows/Linux quit) |
| 16 | 🟡 MED | Desktop | `bootstrap()` is fire-and-forget with no top-level catch |
| 17 | 🟡 MED | Frontend | `AiAgent.vue` is a **1607-line god component** |
| 18 | 🟡 MED | Frontend | Token rides as `?token=` in SSE query strings |
| 19 | 🟡 MED | Toolchain | Manifest schema does not `require` `permissions`/`backend`/`aiTools` |
| 20 | 🟡 MED | Toolchain | `axios ^1.7.9`; **no Dependabot/Renovate** configured |
| 21 | ⚪ LOW | several | See "Aggregated low-severity issues" |

---

## Detailed Findings

### 🔴 1. [CRITICAL] Auto-updater installs unsigned updates
**File:** `desktop/electron/src/updater/auto-updater.ts:11-36`; `electron-builder.yml:104-107`; `.github/workflows/fengyu-release.yml`

The release workflow explicitly produces **unsigned** Alpha builds. `checkForUpdates()` calls `autoUpdater.checkForUpdates()` → `downloadUpdate()` → `quitAndInstall()` with no code-signing or certificate verification configured. electron-updater's integrity guarantee rests entirely on code signing; for unsigned builds it accepts any matching `latest.yml` + binary from the GitHub feed (`provider: github`, `owner: MuskStark`, `repo: FengYu`). A compromised release, a leaked `GH_TOKEN`, or a repository-takeover silently ships malware that the app auto-installs on next launch. **This is the single most serious issue in the project.**

**Fix:** Gate `checkForUpdates()` behind `if (app.isPackaged && isSigned)` until code signing (Windows `CSC_LINK`/Azure trusted signing; macOS Developer ID + notarization) is in place. For unsigned builds, disable auto-update entirely. As defense-in-depth, pin the feed via a custom `GenericProvider` with signature/blockmap verification.

---

### 🔴 2. [CRITICAL] Host-level e2e smoke test is never run in CI
**File:** `scripts/e2e-smoke.sh` (~270 lines); `.github/workflows/toolchain-release.yml:81`

The script that boots the real JAR and exercises token auth, plugin invocation, FileRef resolution, email batch preview, and excel split/export **ships to release with zero automated coverage**. The only reference in `.github/` is a NOTE in `toolchain-release.yml:81` explicitly stating it is NOT run. The release workflow gates only on `test-web-release.sh` + frontend unit tests.

**Fix:** Add a job to `fengyu-release.yml` (after official `.fyp` packages are built) that runs `scripts/e2e-smoke.sh` against the freshly built JAR + plugins under bubblewrap/AppArmor. Run the same job on PRs touching `FengYu/` or `OfficialPlugins/`.

---

### 🔴 3. [CRITICAL] Qodana scans on JDK 17, project targets JDK 21
**File:** `qodana.yaml` (`projectJDK: "17"`); `pom.xml` (`maven.compiler.source/target = 21`); all CI workflows use JDK 21.

Every CI workflow sets up JDK 21 temurin, but Qodana is told the project JDK is 17. The linter either fails to resolve JDK-21-only APIs (sealed classes, patterns, virtual-thread APIs used heavily via `Thread.ofVirtual()`) or silently misresolves types. The qodana workflow itself does not run `setup-java`, so it depends entirely on the action default.

**Fix:** Set `projectJDK: "21"` in `qodana.yaml` and add an explicit `actions/setup-java@v5` step (`java-version: '21'`) before `Qodana Scan`.

---

### 🟠 4. [HIGH] `Worker.pending` map leaks on every successful plugin call
**File:** `FengYu/.../plugin/runtime/PluginProcessManager.java:441-456`

```java
pending.put(id, future);                       // 441
try {
    ...
    JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);   // 454
    log.debug("Plugin {} ← {} id={} ok", pluginId, method, id);
    return json.treeToValue(result, Object.class);                    // 456 — NO remove
} catch (TimeoutException e)  { pending.remove(id, future); ... }     // 458
catch (InterruptedException e){ pending.remove(id, future); ... }     // 463
catch (ExecutionException e)  { pending.remove(id, future); ... }     // 467
catch (IOException e)         { pending.remove(id, future); ... }     // 472
```

Every catch block removes the pending entry, but the **success path returns at line 456 without removing**. The reader thread at line 427 (`slot.complete(response.get("result"))`) also never removes. Result: every successful plugin invoke permanently leaks a `UUID → CompletableFuture` entry in the `ConcurrentHashMap`, growing unbounded for the worker's lifetime. On a long-running agent that issues many tool calls, this is a slow OOM.

**Fix:** Either `pending.remove(id, future)` in a `finally`, or have the reader do `pending.remove(responseId)` immediately after completing the future.

---

### 🟠 5. [HIGH] Auth-token comparison is not constant-time
**File:** `FengYu/.../web/filter/TokenAuthFilter.java:56`

```java
if (expected.equals(provided)) {   // String.equals — short-circuits on first mismatched byte
```

`String.equals` returns false at the first differing byte, leaking timing information. A local attacker able to time responses to the loopback port (another user process, a malicious browser tab via `fetch` timing) could statistically recover the token byte-by-byte. Low practicality on single-user loopback hosts, but it is the one shared secret in the system and the fix is one line.

**Fix:** `MessageDigest.isEqual(expected.getBytes(UTF_8), provided == null ? new byte[0] : provided.getBytes(UTF_8))`.

---

### 🟠 6. [HIGH] Backend JAR/JRE binaries not integrity-checked; user-writable path
**File:** `desktop/electron/src/backend/spawn.ts:86-90`; `runtime-layout.ts:38-42`; `electron-builder.yml:99`

`spawnBackend` validates the JAR with `existsSync(layout.jar)` only — no hash or signature. With `nsis.perMachine: false`, the app installs under `%LOCALAPPDATA%\Programs\Infinia\resources\` (user-writable). The backend JAR (`binaries/FengYu.jar`) and `jre\bin\java.exe` are both writable by a non-admin user or co-resident malware; the next launch executes the replaced binary with the user's privileges — classic DLL/binary planting. `FENGYU_JAR` is also trusted unvalidated in dev.

**Fix:** Ship per-machine (`perMachine: true`) **or** verify a pinned SHA-256 of the JAR/java binary against a value baked into the signed asar before exec. Reject `FENGYU_JAR` paths outside an allow-list in dev.

---

### 🟠 7. [HIGH] No frame-size limit on the JSON-RPC stdio channel
**File:** `toolchain/sdk-java/.../StdioTransport.java:33` (`reader.readLine()` unbounded); `FengYu/.../PluginProcessManager.java:402` (host reader also unbounded)

A worker emitting an arbitrarily large response (or a buggy plugin looping on `writer.println`) is buffered into a single `String` with no upper bound on either side. `BufferedReader.readLine()` will absorb gigabytes. Package caps (100/300 MB) protect the *archive*, not the *wire*.

**Fix:** Cap frames at a sane limit (e.g. 16 MB). Wrap `readLine()` with a counting reader, or read into a bounded `ByteBuffer` and reject with `-32603` (or kill the worker) on overflow. Apply symmetrically.

---

### 🟠 8. [HIGH] Manifest `permissions` are not enforced at dispatch time
**File:** `FengYu/.../PluginProcessManager.java:187-205` (only `network`/`database`/`files.write` influence sandbox setup); `JsonRpcWorker.serve()` dispatches by method name alone.

Permission gating stops at process-sandbox configuration. A plugin declaring only `files.read` still gets its `email_*` / arbitrary handlers dispatched without any check. `aiTools[].effect` is never consulted at dispatch — an `effect: "read"` tool can call a file-writing handler. `clipboard.*` and `notifications` permissions are pure declarations nothing reads.

**Fix:** Either (a) add a per-method permission/effect check that rejects calls exceeding the manifest, or (b) explicitly document that permissions are advisory-only (UX prompt) and the sandbox is the only real boundary. Today the gap is silent and misleading.

---

### 🟠 9. [HIGH] Frontend has no 401 handling and no auth route guard
**File:** `frontend/src/router/index.ts`; `frontend/src/api/client.ts:45-61`

The only `beforeEach` guard checks *setup* status, not auth. There is no `interceptors.response`, so a 401/403 (expired or wrong-origin token) is silently surfaced as a generic error string with no redirect, no token-refresh, and no "session expired" UX. `grep` for `401|response.status|isAxiosError` returns nothing.

**Fix:** Add a response interceptor that on 401 clears the in-memory token snapshot and routes to a re-auth/setup state; at minimum map 401 to a translatable error.

---

### 🟠 10. [HIGH] No maven-enforcer — logback split-pair is hand-pinned
**File:** `FengYu/pom.xml:32-43` (manual logback-classic/core pin comment); root `pom.xml` (no enforcer)

The need to hand-pin logback-classic + core to the same version is a symptom of BOM-vs-parent version skew that `requireUpperBoundDeps`/`dependencyConvergence` would catch automatically. With no enforcer on any of the three poms, future transitive splits (Spring AI 2.0 + Boot 4.1 is high-risk) will surface only as a runtime `ClassNotFoundException` or `NoSuchMethodError`.

**Fix:** Add `maven-enforcer-plugin` with `requireUpperBoundDeps` + `dependencyConvergence` to parent `pluginManagement`, bound to the `validate` phase.

---

### 🟡 11. [MEDIUM] ~250 lines of tool-loop logic duplicated across backends
**File:** `SpringAiCloudBackend.java:319-527` ↔ `OllamaLocalBackend.java:268-440`

12 private methods are byte-for-byte copies: `runToolLoop`, `streamAndCollect`, `startChat`, `buildSpringAiMessages`, `mapToolCalls`, `parseArgs`, `fireToolCalls`, `fireToolEvents`, `lastToolResponseMessage`, `mirrorToolResultsToHistory`, `currentSystemPrompt`, `disposeActiveStream`. They implement the same `ChatBackend` interface but share no code — a fix must be applied twice.

**Fix:** Extract a `ToolLoopSupport` helper or a private abstract base holding shared `generating`/`activeStream`/`streamDone` state, leaving each backend only the model-resolution/prompt-options differences.

---

### 🟡 12. [MEDIUM] JDBC URL parameter injection via unvalidated wizard fields
**File:** `FengYu/.../setup/DataSourceConfigService.java:185-190`; reached unauthenticated via `/api/setup/*` (bypassed by `TokenAuthFilter.java:42`).

`urlTemplate.replace("{host}", params.host()).replace("{port}", ...).replace("{db}", params.database())` performs no validation. A submitted host/database containing `?`/`&`/`#` injects arbitrary JDBC URL parameters — e.g. MySQL `autoDeserialize=true&allowLoadLocalInfile=true` or H2 `;INIT=...`. Mitigated by loopback bind + first-flight-only, but the wizard does no allow-listing.

**Fix:** Reject `host`/`database` containing any of `:/?#&=;\s`; require port numeric. Or build the URL with a `URI`-based constructor.

---

### 🟡 13. [MEDIUM] POSIX sandbox profiles are broad; NONE silently downgrades
**File:** `ProcessSandbox.java:179-182` (macOS `(allow default)` + deny network/file-write), `:151-176` (bwrap read-binds all of `/`), `:134` / `CommandExecuteTool.java:86-92` (NONE)

The macOS profile begins with `(allow default)`, leaving process/IPC/syscall surface unrestricted. Linux bwrap mounts the entire host FS read-only, so a sandboxed AI command can read `~/.ssh`, `/etc/shadow`, app config, and exfiltrate via stdout (captured into chat). When no isolator is present (`Backend.NONE`), only the approval gate remains. The sandbox is lifecycle/write/network isolation, **not confidentiality** — but that's not documented.

**Fix:** Document the read-anywhere property explicitly; consider denying reads of `~/.ssh`, `.fengyu/config`, credentials roots in both profiles. Ensure the permission gate fails closed when `isNativeSandboxAvailable()` is false.

---

### 🟡 14. [MEDIUM] Swallowed exceptions with no logging
**File:** `PluginProcessManager.java:242` (`catch (IOException ignored) {}` on stderr reader), `:364`/`:370` (`deleteRecursive`), `:513`/`:532` (`terminateJob`/`closeJobHandle`); `AgentController.java:436` (`// best effort`)

Several catches discard the exception entirely with no log even at trace. The stderr-reader swallow means a worker whose stderr stream errors is silently dropped, making plugin debugging harder.

**Fix:** Log at `debug`/`trace` with the message; for the stderr reader, append to `logStore` so the failure is diagnosable.

---

### 🟡 15. [MEDIUM] `window-all-closed` no-op on all platforms
**File:** `desktop/electron/src/main.ts:302-304`

```ts
// On other platforms we intentionally let the default quit-on-all-closed stand.
app.on('window-all-closed', () => { /* no-op */ })   // ← overrides the default on EVERY platform
```

Registering the listener overrides Electron's default. On Windows/Linux, closing the last window leaves a headless backend + tray running with no obvious way out except the tray menu. The comment contradicts the code.

**Fix:** `if (process.platform !== 'darwin') app.quit();` inside the handler; keep the no-op for macOS only.

---

### 🟡 16. [MEDIUM] `bootstrap()` is fire-and-forget with no top-level catch
**File:** `desktop/electron/src/main.ts:287` (`void bootstrap()`)

Each subsection has its own try/catch, but `bootstrap()` itself has none. A throw from `createMainWindow`, `createTray`, `superviseSetupRestart`, or `resolveLayout` becomes an unhandled promise rejection: the splash stays parked over an invisible window with no error dialog.

**Fix:** Wrap the body in `try/catch` that calls `destroySplash`, `dialog.showErrorBox`, `app.quit()`.

---

### 🟡 17. [MEDIUM] `AiAgent.vue` is a 1607-line god component
**File:** `frontend/src/views/AiAgent.vue`

One file owns: (1) AI-planning SSE wiring (`openStream`/`closeStream`, 558-654); (2) the Vue Flow canvas + drag/drop + topology (384-553); (3) the per-node argument inspector with schema-driven inputs (298-382); (4) run history + persisted-run restore (229-272); (5) ~450 lines of scoped CSS. (`PluginMarket.vue` at 643 lines and `AiChat.vue` at 507 are similar but smaller.)

**Fix:** Extract `useAgentStream()` (composable), `AgentWorkflowCanvas.vue`, `WorkflowInspector.vue`, and `AgentRunHistory.vue`. The inspector alone (~430 template+style lines) is a natural split.

---

### 🟡 18. [MEDIUM] Token rides in SSE query string
**File:** `frontend/src/api/sse.ts:29-32`; `AiAgent.vue:560-563`; `TokenAuthFilter.java:50-53`

Because `EventSource` cannot set headers, the FengYu token rides as `?token=…` on `/api/ai/stream` and `/api/agent/stream`. It lands in backend access logs and any proxy/referrer capture. No server-side Tomcat access log is configured (so disk leakage is currently nil), but the client-side vector remains.

**Fix:** If the backend can issue a short-lived, single-stream SSE ticket, switch to that; otherwise ensure server-side log redaction of the `token` param and confirm the EventSource origin is always loopback.

---

### 🟡 19. [MEDIUM] Manifest schema does not require `permissions`/`backend`/`aiTools`
**File:** `toolchain/spec/manifest.schema.json:7` — `"required": ["schemaVersion","id","name","description","version","author","icon","category","ui"]`

A plugin with no `permissions` array passes validation, as does one with no `backend` (silently becomes UI-only). The host installer validates enum membership *if present* but never requires the field. The schema should make the default-empty contract explicit.

**Fix:** Require `permissions` (even if `[]`); consider requiring `backend` when `aiTools` is non-empty.

---

### 🟡 20. [MEDIUM] `axios ^1.7.9`; no Dependabot/Renovate
**File:** `frontend/package.json:20`; `.github/dependabot.yml` and `renovate.json` both absent

axios 1.7.x had a chain of SSRF/credential-leak CVEs fixed across 1.8.x. `^1.7.9` will accept anything ≥1.7.9 <2.0.0. No dependency automation exists across 6 npm packages + the Maven reactor + Electron — advisories and drift are entirely manual.

**Fix:** Bump axios to `^1.12.0` and regenerate the lockfile. Add `.github/dependabot.yml` covering each npm ecosystem + maven, with grouped minor updates and separate security-PR behavior.

---

### ⚪ Aggregated low-severity issues

| Area | Finding | File |
|------|---------|------|
| Backend | Dead field `chatClient` (built but never read) in both backends | `SpringAiCloudBackend.java:101,194`; `OllamaLocalBackend.java:93,130,149` |
| Backend | `catch (Throwable)` overly broad, swallows `Error` | both backends `currentSystemPrompt` (`:529`/`:442`) |
| Backend | REST error envelope inconsistency (`ok` vs `success`, mixed status) | `AgentController.java:187,204` vs `GlobalExceptionHandler.java:20-23` |
| Backend | Test gaps: `AgentRunner.validatePlan`, `PluginProcessManager.parseCommand` (security-relevant quote handling) | `AgentRunnerTest`, `PluginProcessManagerTest` |
| Backend | Magic numbers / unnamed token heuristic `length()/4` duplicated | both backends; `AgentController.java:63,94`; `AiController.java:84` |
| Backend | AI command output not redacted before return to model (e.g. `cat ~/.ssh/id_rsa`) | `CommandExecuteTool.java:129` |
| Desktop | IPC handlers perform no `event.sender` validation | `ipc/dialog.ts:8-14`; `desktop/appearance.ts:54` |
| Desktop | Updater feed hardcoded to personal GitHub repo (`owner: MuskStark`) | `electron-builder.yml:104-107` |
| Frontend | EventSource never closed on native drop → reconnect storm | `AiAgent.vue:631-642` |
| Frontend | Settings i18n labels non-reactive (built once with `t(...)`) | `Settings.vue:32-39,83-88` |
| Frontend | `as never` bypasses type check on AI-settings save | `Settings.vue:104` |
| Frontend | Hardcoded English in SetupWizard | `SetupWizard.vue:80,101` |
| Toolchain | Vite split across major versions (frontend v6, toolchain/ui v7) | `frontend/package.json:34`; `toolchain/ui/package.json:55` |
| Toolchain | Duplicated `handlers.handle(name, lambda)` registration across all 5 official plugins | `OfficialPlugins/plugin-email/.../EmailWorkerMain.java` (+ 4 others) |

---

## Strengths Worth Preserving

This is a mature, well-considered codebase. Highlighting what is done right so it isn't regressed:

- **Loopback binding is defense-in-depth** — pinned in both `application.yml` (`server.address: 127.0.0.1`) and re-pinned programmatically in `HeadlessLauncher.runtimeDefaults`.
- **Process-tree kill is robust** — Windows Job Object path correctly reclaims the created-but-unassigned handle on `assign()` failure (`WindowsJobSandbox.java:144`, `ProcessSandbox.java:139-148`); `KILL_ON_JOB_CLOSE` provides kill-on-JVM-death; plugin workers have both `@PreDestroy` and a JVM shutdown hook backstop; tree-kill (SIGTERM → SIGKILL after 5s) on the desktop side.
- **Archive handling is safe** — zip-slip prevented (`PluginPackageService.extract` `target.startsWith(staging)` check), expansion-bomb caps (100/300 MB), runtime-tree smuggling (`.git`, `node_modules`, `settings.xml`, `.npmrc`) blocked; manifest validation duplicated and kept in sync between host and CLI with an explicit sync contract comment.
- **Stdio cleanliness is genuinely enforced** — `JsonRpcWorker.run()` redirects `System.out → System.err` for the dispatch loop; the host reader explicitly detects and logs non-JSON stdout as protocol-desync; commit `225eef26` matches its claim.
- **Secret-leak hygiene is excellent** — param *keys* logged, never values; worker exception messages flagged untrusted and stripped to type-only; env-borne secrets go through `SensitiveValueRedactor`; env vars stripped before AI command launch.
- **XSS posture is solid** — every `v-html` flows through `renderMarkdown()` → `marked.parse` → `DOMPurify.sanitize` with a hardened profile; token is in-memory only (not `localStorage`); no `any`/`@ts-ignore` anywhere in the frontend.
- **Electron webPreferences are secure** — `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true` on both windows; minimal preload surface; strong per-frame CSP via `onHeadersReceived`; `setWindowOpenHandler` denies all and delegates only `http(s)` to `shell.openExternal`.
- **Version sync across app manifests is clean** — `4.0.0-alpha.8` consistent across root `pom.xml`, `frontend/package.json`, `desktop/electron/package.json`, and all five plugin `manifest.json` files (toolchain intentionally independent at 1.2.0/1.0.0).
- **Stores are well tested** — `account`, `aiConfirmation`, `aiSession`, `aiToolActivity`, `pluginStore` all have unit tests; Electron has 9-case `window-open-handler` test and coverage for spawn/supervisor/handshake/token/health/auto-updater.
- **Only 1 TODO in the entire source tree** — discipline signal.

---

## Recommended Priority Order

1. **Now (pre-release):** #1 (sign or disable auto-update), #2 (run e2e-smoke in CI), #4 (`pending` leak), #3 (Qodana JDK 21).
2. **Next sprint (security hardening):** #5 (constant-time compare), #6 (JAR integrity), #7 (frame cap), #8 (permission enforcement decision), #9 (401 handling), #12 (JDBC validation), #13 (sandbox docs + fail-closed).
3. **Quality pass:** #10 (enforcer), #11 (dedupe backends), #15/#16 (Electron lifecycle), #17 (decompose AiAgent.vue), #20 (Dependabot + axios bump), then the LOW table.
