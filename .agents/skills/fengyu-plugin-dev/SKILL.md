---
name: fengyu-plugin-dev
description: Scaffold, develop, IDE-debug, test, build, package, and locally verify FengYu plugins (official or third-party) against the 4.0.0 .fyp + iframe + JSON-RPC Worker model. Use whenever the user wants to create or work on a plugin, test plugin tooling before release, debug UI and Java Worker code from an IDE, or mentions `.fyp`, `manifest.json`, `fengyu.plugin.json`, `fengyu plugin`, `@infinia/plugin-sdk`, `@infinia/plugin-ui`, `@infinia/plugin-dev`, `toolchain/sdk-java`, plugin workers, or the plugin marketplace.
---

# FengYu Plugin Development

End-to-end workflow for authoring FengYu plugins against the **4.0.0** model: `.fyp` packages
containing a sandboxed iframe UI and an out-of-process JSON-RPC worker. Covers official plugins
(in this repo under `OfficialPlugins/`) and third-party plugins (scaffolded elsewhere).

## Step 0 — Load authoritative inputs BEFORE acting

Do not write plugin code from memory. Read the current contract first:

- `toolchain/spec/manifest.schema.json` — the canonical manifest JSON schema (required fields,
  permission enum, `aiTools` shape).
- The target plugin's `manifest.json` and its conventional `ui-src/package.json` plus optional
  `worker/pom.xml` (or root `pom.xml`). Toolchain 2 does not use `fengyu.plugin.json`.
- `toolchain/sdk-java/` — the Java Worker SDK (`JsonRpcWorker`, `PluginHandler`, `PluginEnvironment`,
  `FileRef`).
- `toolchain/sdk-ts/` — `@infinia/plugin-sdk`, the browser `postMessage` bridge the iframe UI uses.
- `toolchain/ui/` — `@infinia/plugin-ui`, the Vue/Vuetify component kit for plugin UIs.
- `toolchain/cli/` — the `fengyu` CLI source (`src/cli.mjs`, `src/args.mjs`) — the real subcommand set.
- A reference official plugin, e.g. `OfficialPlugins/plugin-markdown/` (UI-only-style) or
  `OfficialPlugins/plugin-excel/` (Vue UI + Java worker).
- Current plugin docs: `docs/en/plugins/` and `docs/zh/plugins/` (especially `overview.md`,
  `manifest.md`, `worker.md`, `ui-microfrontend.md`, `build-deploy.md`, `sdk-cli.md`,
  `ai-tools.md`, `database.md`, `file-io.md`, `pitfalls.md`).

If any of these disagree with this skill, **the repo wins** — follow the file.

## Step 1 — Classify the plugin

Every plugin is one of two shapes. The workflow diverges here:

| Shape | Has `backend`? | Worker | Reference |
|---|---|---|---|
| **UI-only** | `manifest.json` has no `backend` (or no worker) | none | `toolchain/cli/templates/vue-codex`: UI calls host/SDK only |
| **UI + Java Worker** | `manifest.json` `backend.protocol == "json-rpc-2.0"` | a shaded worker JAR | Excel-style: UI ↔ host ↔ out-of-process worker |

All current official plugins have Java Workers; do not infer UI-only status from a plugin's feature
set. Read its manifest.

Read the target plugin's `manifest.json` and decide. Everything below branches on this.

## Step 2 — Scaffold (third-party) or locate (official)

**Third-party:** use the CLI to scaffold, never hand-roll:

```bash
# UI + Java worker (default)
fengyu init my-plugin --id com.example.my-plugin
# UI-only (no backend worker)
fengyu init my-plugin --id com.example.my-plugin --ui-only
# Skip auto-installing SDK/UI deps during scaffold
fengyu init my-plugin --id com.example.my-plugin --no-install
```

`--id` is required and must match the manifest id pattern `^[a-z0-9]+(?:[.-][a-z0-9]+)+$`. Official
plugin ids start with `fan.summer.`.

**Official (in this repo):** the plugin already lives under `OfficialPlugins/<name>/` with
`manifest.json`, `pom.xml`, `src/`, `ui-src/`, and a `dist/` output dir. Do not re-scaffold; edit
in place.

## Step 3 — Develop

**UI (always):** a Vue micro-frontend built from `ui-src/`. It runs inside a **sandboxed iframe**
served by the host under a strict Content-Security-Policy (`connect-src 'none'` — the UI cannot call
out directly). To reach the backend or host capabilities, the UI uses `@infinia/plugin-sdk` to
`postMessage` to the host shell, which proxies via the plugin-runtime invoke path. The UI **never**
sees absolute filesystem paths — file access flows through opaque file-reference objects mediated by
the host.

### UI ownership contract

The host frontend is the visual source of truth. The project toolchain must provide that UI inside
the isolated iframe: `@infinia/plugin-ui` owns host-consistent themes, component defaults, and
FengYu UI components; `@infinia/plugin-sdk` carries live theme/locale state; the CLI wires both into
every generated Vue plugin.

Treat official CLI templates as executable compatibility contracts, not illustrative snippets. Any
icon, component prop, or initialization form emitted by a template must render correctly through
the corresponding SDK/UI versions. Where an icon API accepts a string, both Vuetify `mdi-*` names
and `@mdi/js` SVG path strings are valid. If a legal template input fails, fix and test the
toolchain; do not rewrite plugin business UI merely to avoid the defect.

When changing CLI UI templates, theme definitions, icon handling, or public UI components, compare
against the host implementation and add a contract test for the exact generated input. Keep
`frontend/src/plugins/md3-themes.ts` and `toolchain/ui/src/theme.ts` value-aligned.

### Host environment synchronization invariant

Theme and locale are live host state, not one-time startup options. Prefer `mountFengYuApp`, which
owns the correct ordering. If custom code binds a `FengYuClient` manually, register
`client.on('environment', ...)` **before** awaiting `client.ready()`. The host may emit its initial
environment event as soon as the iframe loads; subscribing after `await ready()` creates a race in
which that event is lost, leaving the plugin on the UI kit's default dark/English state. Merge
partial events into the last environment and apply both the document attributes and UI runtimes:

- `document.documentElement.dataset.theme` and Vuetify's active theme;
- `document.documentElement.lang`, Vuetify locale, and the plugin's message-table locale.

Do not fix this independently in each business plugin. Fix the shared `@infinia/plugin-ui` binding,
add a regression test that emits an environment event while `ready()` is still pending, then rebuild
and refresh every affected plugin's local `file:` dependency before packaging. `npm install` may
reuse the old copied package; verify `ui-src/node_modules/@infinia/plugin-ui/dist/index.js` contains
the new ordering and use a targeted forced reinstall when it does not.

**Worker (UI + Java Worker only):** a Java `main()` that links `toolchain/sdk-java` and speaks
newline-delimited JSON-RPC 2.0 over stdin/stdout (one request object per line, responses matched by
`id`). The worker runs in **its own process** with its own classpath; it must not assume any
host-provided dependency beyond the SDK. The host sets env vars `FENGYU_PLUGIN_ID`,
`FENGYU_PLUGIN_ROOT`, and (for plugins with the `database` permission) an injected datasource.
The host, SDK, and DevKit enforce a 16 MiB UTF-8 frame limit in both directions. Keep responses
bounded; return paginated results or opaque file references instead of embedding arbitrarily large
payloads.

**IDE dev loop:** `fengyu dev` runs the UI simulator; start the Java Worker separately in the IDE
so handler breakpoints remain native:

```bash
# UI terminal or IDE npm run configuration
fengyu dev                                 # simulator: http://127.0.0.1:5173/__fengyu

# Java IDE Debug configuration
PluginDevMain.main()                       # Worker endpoint: 127.0.0.1:24057
```

For Java plugins, debug `PluginDevMain` under `src/test/java`, not the production stdio
`WorkerMain`. The Vite config must pass the same endpoint to `fengyuPluginDev`. Set handler
breakpoints in the IDE and invoke them from the simulator UI. A configured but unavailable Worker
must surface an RPC error; never accept a `devMock` response as evidence that the backend works.
Use `mockWorker: true` only for intentional UI-only/stub development. To change ports, use
`-Dfengyu.dev.port=<n>` and update `vite.config.ts` together.

### Logging (Java workers) — the canonical pattern

A worker has **two parallel log channels**; a well-instrumented plugin uses both. Read this before
writing any handler or service — it is the difference between an observable plugin and a black box.

**Channel 1 — SLF4J → stderr → host log + frontend SSE (diagnostics).** Use the standard SLF4J API:
`private static final Logger log = LoggerFactory.getLogger(MyClass.class)`. The SDK ships its own
SLF4J 2.x provider (auto-discovered via SPI; **no** logback/log4j dependency or config file needed),
which formats each event as a `@fengyu-log:`-prefixed JSON line on **stderr**. The host's
`plugin-<id>-stderr` drain captures it, redacts secrets, forwards it to the host log file (logger
name `plugin.<id>.<source>`) and to the frontend via `GET /api/plugin-runtime/{id}/logs/stream` (SSE)
and `GET /api/plugin-runtime/{id}/logs` (REST history, 500-line ring buffer).

**Channel 2 — `Jobs.handle.log()` → job snapshot (real-time progress).** For long-running async
operations launched via `Jobs.start(type, handle -> ...)`, call `handle.log(line)` for each step.
These lines land in the `Job.logs` queue and are returned to the UI via `<method>.status` polling —
they do **not** enter the host log file or the frontend SSE stream, and expire with the job (30 min
TTL). Use this for per-step progress ("reading Excel…", "installed 3/10 wheels"); use SLF4J for
milestones, failures, and anything an operator needs after the job is gone.

**`stdout` is JSON-RPC only.** Never `System.out.println(...)`. Both stdio entry points
(`run()` and `run(in, out)`) redirect `System.out` → `System.err` to protect the protocol stream,
so stray prints end up on stderr anyway — but do not rely on that; use SLF4J.

**Mandatory logging rules (from the official plugins + FY-Report):**

1. **Every class that does I/O or business logic holds its own SLF4J logger** — services,
   repositories, listeners, utils. Don't centralize; a logger named after the class tells you *where*
   the event happened. Declare it as `private static final Logger log = LoggerFactory.getLogger(X.class)`.
2. **Failure paths always log, with the throwable.** Every `catch (Exception e)` that represents a
   real failure (not a deliberate best-effort swallow) calls `log.warn`/`log.error("... failed: {}", context, e)`.
   The exception object is essential — the SDK's `Jobs.start` flattens a thrown exception to a
   one-line `markFailed` message without a stack trace, so the host log surface has no diagnostics
   unless you log it yourself first. Pattern (see `FyreportWorker`, `BuildService`):
   ```java
   } catch (Exception e) {
       log.error("[SetTarget] job failed", e);   // full stack to stderr → host log
       throw e;                                  // rethrow so Jobs marks it FAILED
   }
   ```
3. **Async job bodies wrap their work in try/catch-log-rethrow.** This is the single most important
   rule: a bare `jobs.start("X", handle -> { doWork(); })` loses all stack traces on failure. Wrap it:
   ```java
   jobs.start("X", handle -> {
       try {
           Result r = doWork(handle::log);       // progress via Channel 2
           handle.setSummary(toMap(r));
       } catch (Exception e) {
           log.error("X job failed: {}", context, e);  // Channel 1 for the host log
           throw e;
       }
   });
   ```
4. **Milestones log at INFO; recovery/fallback logs at WARN.** "sent mail to 3 recipients" = INFO;
   "reclaimed 2 stuck tasks (dead worker)" = WARN. Avoid DEBUG for anything operators need by default.
5. **Redact secrets before logging, or let the host redactor catch them.** The host's
   `SensitiveValueRedactor` scrubs values from env vars named `*_PASSWORD/*_SECRET/*_TOKEN`. But
   business data (mail bodies, credentials in exception messages) is *not* redacted on the direct
   RPC return path — so do not log raw request params or full exception messages that embed them.
   The SDK's `JsonRpcWorker.serve()` deliberately logs only method name + exception class for this
   reason; match that discipline in your handlers.

**Reference implementations:**
- `FY-Report` (`FengYu-Plugin-Private/FY-Report`) — the gold standard: 21 logger classes, 97 log
  calls, every async body wrapped, both channels used. Start here when in doubt.
- `OfficialPlugins/plugin-excel` — SLF4J in splitter/util/listener + `handle.log` progress in
  `splitStart`; async body try/catch-log-rethrow.
- `OfficialPlugins/plugin-email` — SLF4J in Send/Archive/Pending services; IMAP/SMTP failures logged
  with context; `EmailWorkerMain` shows the one-line `System.setOut(System.err)` for construction-
  phase silence when DB/driver init noise risks leaking to stdout before `run()` takes over.

**Anti-patterns to remove on sight:**
- `System.out.println(...)` / `printStackTrace()` — replace with SLF4J.
- A bare `catch (Exception e) { failures++; }` with no log — at minimum add
  `log.warn("... failed: {}", e.toString())`.
- An async `jobs.start(...)` body with no try/catch — wrap it so failures reach the host log.
- A custom `OpbLogger`-style wrapper that reinvents logging — use SLF4J directly; the SDK provider
  already routes to stderr, and a custom wrapper risks double-logging or missing the host drain.

## Step 4 — Declare permissions and (optional) AI tools

Edit `manifest.json`:

- `permissions[]` — request the **minimum** set from the allowed enum only: `files.read`,
  `files.write`, `network`, `network.email`, `clipboard.read`, `clipboard.write`, `notifications`,
  `database`. The host enforces these; do not attempt capability access you did not declare.
- `aiTools[]` (optional) — each entry needs `name`, `description`, `method` (the JSON-RPC method
  the worker implements), and `inputSchema`. Keep `description` short and model-readable; it is
  what the AI primarily reads.

Confirm the manifest validates against `toolchain/spec/manifest.schema.json` and matches the runtime
loader's rules in `FengYu/src/main/java/fan/summer/fengyu/plugin/market/` (`.fyp` only,
`schemaVersion == 1`, semver `version`, official ids start `fan.summer.`).

`database` is explicit user authorization, not an install-time grant. On server databases the host
creates a per-plugin user/schema only after `POST /api/plugin-db/provision/{id}`. Credentials are
injected only after the durable state reaches `ACTIVE`; `PROVISIONING` and `DELETE_PENDING` remain
recoverable through `/status/{id}` and `/retry/{id}`. A Worker must surface a clear unavailable or
not-authorized error when DB environment variables are absent and must never fall back to host
credentials. Embedded file databases use the host-allocated plugin data directory.

## Step 5 — Validate, build, package, install

Use the CLI for build/package only; do not hand-zip. Validation is an unconditional build stage,
and installation is a host UI/API operation rather than a CLI subcommand:

```bash
fengyu check ./my-plugin
fengyu build ./my-plugin                   # → dist/<id>-<version>.fyp
fengyu build ./my-plugin --out dist/x.fyp --skip-tests
```

Every successful build also writes `<archive>.fyp.sha256`. Treat the archive and checksum sidecar
as a pair when staging, copying, or publishing an official plugin. The sidecar detects corruption
and partial releases; it is not an independent authenticity signature if an attacker can replace
both files. Do not claim package provenance without a separate trusted signature or pinned digest.

Install the resulting `.fyp` through the host plugin marketplace UI. For automated local host
verification, use the authenticated `POST /api/plugin-market/upload` path exercised by
`scripts/e2e-smoke.sh`; do not invent a `fengyu plugin install` command.

For official plugins, the CLI runs the plugin's standard npm scripts and Maven lifecycle so the
Worker JAR is fresh before packaging.

## Step 6 — Focused verification

- **UI-only:** `cd ui-src && npm ci && npm test && npm run build` — verify the UI builds
  and its unit/visual tests pass.
- **Toolchain UI contract:** for changes to CLI UI templates, theme definitions, icons, or public
  components, run the exact scaffold/component regression tests plus
  `cd toolchain/ui && npm test && npm run typecheck && npm run build`. Run
  `npm run test:visual` when rendered presentation changes.
- **Environment synchronization:** test an `environment` event both after ready and while the ready
  promise is pending. Package at least one representative plugin, inspect the installed bundle (not
  only source/dist), and verify a live host theme + language switch updates the open iframe without
  reloading it.
- **UI + Java Worker:** also build/test the worker (`mvn -f OfficialPlugins/<name>/pom.xml test`)
  and confirm the worker's JSON-RPC methods round-trip.
- **IDE integration:** start `PluginDevMain` with the IDE and `npm run dev` in `ui-src`; call a real
  Worker method through `/__fengyu/rpc` and verify its non-mock result or expected domain error.
- **Package:** run `fengyu build <path>` without `--skip-tests` and inspect the resulting
  `.fyp` when package contents are in question.
- **End to end:** run `scripts/e2e-smoke.sh` to boot the host, upload built plugins, and exercise the
  documented API methods.

When the user asks for thorough local verification before publishing the plugin toolchain, also run:

```bash
cd toolchain/cli && npm run prepack
cd ../dev && npm run prepack
cd ../sdk-ts && npm run prepack
cd ../ui && npm run prepack && npm run test:visual
cd ../.. && ./mvnw -pl toolchain/devkit-java -am test
scripts/check-plugin-dependency-boundaries.sh
scripts/plugin-tooling-local-smoke.sh
npm --prefix docs run build
```

Build all five official plugins (`markdown`, `excel`, `email`, `offlinepython`, `browser`) through
`node toolchain/cli/bin/fengyu.mjs build <plugin>` and verify every `.fyp.sha256` sidecar. Run
`npm audit` for every publishable npm package. Treat any schema drift, dependency-boundary
failure, high/critical audit finding, missing `npm run dev`, mock response from a configured Worker,
or dirty generated package content as a release blocker. Never publish, tag, or push unless the user
explicitly asks after all blockers are resolved.

## Hard prohibitions (these describe legacy versions, not 4.0.0)

Do **not** generate or recommend any of:

- JavaFX views, `createView()`, `StepWizard`, `-sk-*` / `.glass-*` CSS, or any `javafx.*` code.
- `FengYuPluginV2` / `FengYuPlugin` Java interface implementations, or in-process Spring `@Component`
  plugin beans.
- `META-INF/services/...` Java `ServiceLoader` SPI registration files.
- Any assumption that the worker shares the host classpath, host Spring context, or host JPA session.
  Workers are out-of-process; bring every dependency in the worker's own shaded JAR.
- Direct `fetch`/`connect-src` from the iframe UI — route through the `@infinia/plugin-sdk`
  `postMessage` bridge instead.
