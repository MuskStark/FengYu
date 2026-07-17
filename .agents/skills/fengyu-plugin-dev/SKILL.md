---
name: fengyu-plugin-dev
description: Build, scaffold, develop, validate, build, package, and install FengYu plugins (official or third-party) against the 4.0.0 .fyp + iframe + JSON-RPC Worker model. Use whenever the user wants to create or work on a plugin, mentions `.fyp`, `manifest.json`, `fengyu.plugin.json`, the plugin CLI (`fengyu plugin ...`), `@infinia/plugin-sdk`, `@infinia/plugin-ui`, `FengYu-Plugin-Sdk`, plugin workers, or the plugin marketplace.
---

# FengYu Plugin Development

End-to-end workflow for authoring FengYu plugins against the **4.0.0** model: `.fyp` packages
containing a sandboxed iframe UI and an out-of-process JSON-RPC worker. Covers official plugins
(in this repo under `OfficialPlugins/`) and third-party plugins (scaffolded elsewhere).

## Step 0 — Load authoritative inputs BEFORE acting

Do not write plugin code from memory. Read the current contract first:

- `plugin-spec/manifest.schema.json` — the canonical manifest JSON schema (required fields,
  permission enum, `aiTools` shape).
- The target plugin's `manifest.json` (runtime contract) and `fengyu.plugin.json` (toolchain /
  build contract: `ui.{root,output,prepare,install,test,build}`, `worker.{root,test,build,artifact,mainClass}`, `package.outputDirectory`).
- `FengYu-Plugin-Sdk/` — the Java Worker SDK (`JsonRpcWorker`, `PluginHandler`, `PluginEnvironment`,
  `FileRef`).
- `plugin-sdk/typescript/` — `@infinia/plugin-sdk`, the browser `postMessage` bridge the iframe UI uses.
- `plugin-ui/vue/` — `@infinia/plugin-ui`, the Vue/Vuetify component kit for plugin UIs.
- `plugin-cli/` — the `fengyu` CLI source (`src/cli.mjs`, `src/args.mjs`) — the real subcommand set.
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
| **UI-only** | `manifest.json` has no `backend` (or no worker) | none | Markdown-style: UI calls host/SDK only |
| **UI + Java Worker** | `manifest.json` `backend.protocol == "json-rpc-2.0"` | a shaded worker JAR | Excel-style: UI ↔ host ↔ out-of-process worker |

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

**Worker (UI + Java Worker only):** a Java `main()` that links `FengYu-Plugin-Sdk` and speaks
newline-delimited JSON-RPC 2.0 over stdin/stdout (one request object per line, responses matched by
`id`). The worker runs in **its own process** with its own classpath; it must not assume any
host-provided dependency beyond the SDK. The host sets env vars `FENGYU_PLUGIN_ID`,
`FENGYU_PLUGIN_ROOT`, and (for plugins with the `database` permission) an injected datasource.

**Dev loop:** run the headless backend, then use the CLI dev server for hot UI work:

```bash
fengyu plugin dev ./my-plugin              # optional --port <n> (default 4173)
```

## Step 4 — Declare permissions and (optional) AI tools

Edit `manifest.json`:

- `permissions[]` — request the **minimum** set from the allowed enum only: `files.read`,
  `files.write`, `network`, `network.email`, `clipboard.read`, `clipboard.write`, `notifications`,
  `database`. The host enforces these; do not attempt capability access you did not declare.
- `aiTools[]` (optional) — each entry needs `name`, `description`, `method` (the JSON-RPC method
  the worker implements), and `inputSchema`. Keep `description` short and model-readable; it is
  what the AI primarily reads.

Confirm the manifest validates against `plugin-spec/manifest.schema.json` and matches the runtime
loader's rules in `FengYu/src/main/java/fan/summer/fengyu/plugin/market/` (`.fyp` only,
`schemaVersion == 1`, semver `version`, official ids start `fan.summer.`).

## Step 5 — Validate, build, package, install

All through the CLI — do not hand-zip:

```bash
fengyu plugin validate ./my-plugin         # manifest + layout checks
fengyu plugin build ./my-plugin            # → .fyp in the configured package.outputDirectory
fengyu plugin build ./my-plugin --out dist/x.fyp --skip-tests
fengyu plugin install ./my-plugin          # optional --host http://127.0.0.1:24056 --token <t>
```

For official plugins, prefer the repo's Maven-driven worker build declared in `fengyu.plugin.json`
(`worker.build` runs `mvn ... -pl OfficialPlugins/<name> -am package -DskipTests`) so the worker JAR
is fresh before the CLI packages the `.fyp`.

## Step 6 — Focused verification

- **UI-only:** `cd ui-src && npm install && npm test` (and `npm run build`) — verify the UI builds
  and its unit/visual tests pass.
- **UI + Java Worker:** also build/test the worker (`mvn -f OfficialPlugins/<name>/pom.xml test` or
  the `fengyu.plugin.json` `worker.test` command) and confirm the worker's JSON-RPC methods round-trip.
- **End to end:** with the backend running, `fengyu plugin install` the built `.fyp`, open it in the
  UI, and exercise the documented methods. Optionally run `scripts/e2e-smoke.sh` to confirm the host
  is healthy.

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
