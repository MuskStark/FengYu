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
- The target plugin's `manifest.json` (runtime contract) and `fengyu.plugin.json` (toolchain /
  build contract: `ui.{root,output,prepare,install,test,build}`, `worker.{root,test,build,artifact,mainClass}`, `package.outputDirectory`).
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
fengyu plugin create my-plugin --id com.example.my-plugin
# UI-only (no backend worker)
fengyu plugin create my-plugin --id com.example.my-plugin --ui-only
# Skip auto-installing SDK/UI deps during scaffold
fengyu plugin create my-plugin --id com.example.my-plugin --no-install
```

`--id` is required and must match the manifest id pattern `^[a-z0-9]+(?:[.-][a-z0-9]+)+$`. Official
plugin ids start with `fan.summer.`.

**Official (in this repo):** the plugin already lives under `OfficialPlugins/<name>/` with
`manifest.json`, `fengyu.plugin.json`, `pom.xml` (if it has a worker), `src/`, `ui-src/`, and a
`dist-package/` output dir. Do not re-scaffold; edit in place.

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

**Worker (UI + Java Worker only):** a Java `main()` that links `toolchain/sdk-java` and speaks
newline-delimited JSON-RPC 2.0 over stdin/stdout (one request object per line, responses matched by
`id`). The worker runs in **its own process** with its own classpath; it must not assume any
host-provided dependency beyond the SDK. The host sets env vars `FENGYU_PLUGIN_ID`,
`FENGYU_PLUGIN_ROOT`, and (for plugins with the `database` permission) an injected datasource.

**IDE dev loop:** do not look for a CLI dev command. The CLI has exactly `create` and `build`.
Run the two development processes from the tools the developer prefers:

```bash
# UI terminal or IDE npm run configuration
cd ui-src && npm run dev                   # simulator: http://127.0.0.1:5173/__fengyu

# Java IDE Debug configuration
PluginDevMain.main()                       # Worker endpoint: 127.0.0.1:24057
```

For Java plugins, debug `PluginDevMain` under `src/test/java`, not the production stdio
`WorkerMain`. The Vite config must pass the same endpoint to `fengyuPluginDev`. Set handler
breakpoints in the IDE and invoke them from the simulator UI. A configured but unavailable Worker
must surface an RPC error; never accept a `devMock` response as evidence that the backend works.
Use `mockWorker: true` only for intentional UI-only/stub development. To change ports, use
`-Dfengyu.dev.port=<n>` and update `vite.config.ts` together.

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

## Step 5 — Validate, build, package, install

Use the CLI for build/package only; do not hand-zip. Validation is an unconditional build stage,
and installation is a host UI/API operation rather than a CLI subcommand:

```bash
fengyu plugin build ./my-plugin            # → .fyp in the configured package.outputDirectory
fengyu plugin build ./my-plugin --out dist/x.fyp --skip-tests
```

Install the resulting `.fyp` through the host plugin marketplace UI. For automated local host
verification, use the authenticated `POST /api/plugin-market/upload` path exercised by
`scripts/e2e-smoke.sh`; do not invent a `fengyu plugin install` command.

For official plugins, prefer the repo's Maven-driven worker build declared in `fengyu.plugin.json`
(`worker.build` runs `mvn ... -pl OfficialPlugins/<name> -am package -DskipTests`) so the worker JAR
is fresh before the CLI packages the `.fyp`.

## Step 6 — Focused verification

- **UI-only:** `cd ui-src && npm ci && npm test && npm run build` — verify the UI builds
  and its unit/visual tests pass.
- **Toolchain UI contract:** for changes to CLI UI templates, theme definitions, icons, or public
  components, run the exact scaffold/component regression tests plus
  `cd toolchain/ui && npm test && npm run typecheck && npm run build`. Run
  `npm run test:visual` when rendered presentation changes.
- **UI + Java Worker:** also build/test the worker (`mvn -f OfficialPlugins/<name>/pom.xml test` or
  the `fengyu.plugin.json` `worker.test` command) and confirm the worker's JSON-RPC methods round-trip.
- **IDE integration:** start `PluginDevMain` with the IDE and `npm run dev` in `ui-src`; call a real
  Worker method through `/__fengyu/rpc` and verify its non-mock result or expected domain error.
- **Package:** run `fengyu plugin build <path>` without `--skip-tests` and inspect the resulting
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

Build all four official plugins through `node toolchain/cli/bin/fengyu.mjs plugin build <plugin>` and
run `npm audit` for every publishable npm package. Treat any schema drift, dependency-boundary
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
