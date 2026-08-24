---
name: fengyu-plugin-dev
description: Scaffold, develop, IDE-debug, validate, build, and locally verify FengYu 4.x plugins using the code-first Java/Python/Go contract model, iframe UI, JSON-RPC workers, Flow overlays, and .fyp packaging. Use for official or third-party plugin work, plugin tooling tests, manifest/contract migration, and requests mentioning `.fyp`, `manifest.base.json`, `flowNodes`, `@infinia/plugin-*`, or FengYu plugin workers.
---

# FengYu Plugin Development

Build the smallest maintainable FengYu plugin: define executable behavior once in code, add only
the UI metadata the Flow editor cannot infer, and let `fengyu generate|check|build` compile the
installable manifest.

## Read the applicable source of truth first

Do not design from this skill alone. Inspect the files relevant to the requested change:

- Manifest and Flow vocabulary: `toolchain/spec/manifest.schema.json` and
  `toolchain/spec/flow-node.schema.json`. The CLI mirror in `toolchain/cli/spec/` must remain
  byte-identical when the canonical schema changes.
- Authoring, generation, and packaging: `toolchain/cli/src/`, its tests, and the matching template
  under `toolchain/cli/templates/`.
- Contracts and workers: `toolchain/sdk-java/`, `toolchain/devkit-java/`,
  `toolchain/sdk-python/`, or `toolchain/sdk-go/` for the selected runtime.
- Host install/runtime enforcement: `FengYu/src/main/java/fan/summer/fengyu/plugin/`.
- Focused docs: `docs/en/plugins/` and `docs/zh/plugins/`; keep the two languages structurally
  aligned when documentation is in scope.
- A current official reference under `OfficialPlugins/`. There are four: `markdown`, `excel`,
  `email`, and `offlinepython`. Browser automation is a host capability, not a plugin.

When prose and source disagree, follow the repository. Read versions from their source files; the
application and plugin toolchain have independent version lines.

## Choose the authoring mode

| Situation | Use | Canonical source |
| --- | --- | --- |
| New Java, Python, or Go Worker plugin | **Code-first** (default) | `manifest.base.json` + language contract |
| Existing code-first plugin | Code-first | Edit its contract/base/overlays in place |
| UI-only plugin | Manifest-first | Short `manifest.json`; no Worker contract |
| Existing Java manifest-first Worker | Migrate, review, then switch | `fengyu migrate manifest-codegen <path>` |
| Existing Python/Go manifest-first Worker | Migrate manually when useful | Create the runtime-native contract and base/overlays |

Never put `manifest.json` and `manifest.base.json` in the same project; `check` and `build` reject
the ambiguity. Migration deliberately leaves the old `manifest.json` untouched. Compare generated
output, then delete the old file only after the user has authorized the migration and the new
sources are verified.

All four official plugins are code-first Java Worker projects. Edit them in place; never
re-scaffold them.

## Scaffold third-party plugins

Use the CLI instead of hand-rolling the package layout:

```bash
# Vue UI + code-first Worker (Java is the default runtime)
fengyu init ./my-plugin --id com.example.my-plugin --runtime java
fengyu init ./my-plugin --id com.example.my-plugin --runtime python
fengyu init ./my-plugin --id com.example.my-plugin --runtime go

# UI-only
fengyu init ./my-plugin --id com.example.my-plugin --ui-only
```

Add `--no-install` to skip dependency installation. The id is required and must match
`^[a-z0-9]+(?:[.-][a-z0-9]+)+$`; official ids begin with `fan.summer.`. Third-party scaffolds use
npm, while this repository's JS workspaces and official plugin UIs use the committed Yarn 4 setup.

## Author the executable contract once

A code-first project has non-overlapping sources:

```text
manifest.base.json          identity, version, UI, backend, permissions
manifest/flow-nodes.json    optional Flow presentation overlay
manifest/i18n/<locale>.json optional locale deltas
worker/...                  runtime-owned RPC/AI contract and implementation
```

`manifest.base.json` must not contain `rpc`, `aiTools`, `flowNodes`, or `i18n`. The contract owns
RPC names, input/output schemas, descriptions, AI exposure, effects, timeouts, idempotency, and
sensitive fields:

- **Java:** annotate one or more interfaces with `@FengYuContract`; use `@FengYuRpc`, optional
  `@FengYuAiTool`, `@FengYuField`, and `@FengYuSensitive`. Handlers should consume the contract's
  records rather than duplicated DTOs. For a large surface, group methods into bounded nested
  contract interfaces by feature, as the Excel, Email, and OfflinePython contracts do.
- **Python:** define input/output `@dataclass` types, attach descriptions with
  `Annotated[T, Field(...)]`, and register them with `Contract(pluginId).rpc(...)` in
  `worker/contract.py`. Reuse its method constants in `worker.py`.
- **Go:** define typed structs with `json`, `description`, optional `title`, and optional `default`
  tags, then register them with `fengyu.NewContract(pluginId).RPC(...)` in
  `worker/plugincontract/contract.go`. Reuse its method constants in `main.go`.

Do not weaken unsupported types into generic objects. Bare maps, unbounded generics, recursive or
polymorphic DTOs must fail contract generation until the schema is made explicit.

Run after every contract change:

```bash
fengyu generate .  # runtime extraction -> merged manifest -> typed UI bindings/constants
fengyu check .     # repeats extraction and validates the effective project
```

Java extraction runs the DevKit annotation processor during Maven `generate-resources`; Python
runs `contract.py`; Go runs `go run ./cmd/fengyu-contract`. The deterministic preview is
`target/fengyu-manifest/manifest.json`. It is generated output: inspect it, but never edit or copy
it back into the authored sources.

## Keep Flow configuration short

The RPC schemas are the sole executable contract for Flow inputs and outputs. A tool without an
explicit `flowNodes` entry still appears as a schema-derived fallback node. Add a Flow overlay only
when the curated canvas experience needs presentation or edit-time behavior that the schema cannot
express.

For inputs, the RPC `inputSchema` owns names, types, required fields, and ordinary defaults. An
overlay begins with `name` and may add only useful UI details such as `title`, `description`,
`help`, `placeholder`, `examples`, `advanced`, `options`, `context`, `fields`, or an exceptional
widget override. Omit `widget` when the UI can infer it.

Never put `type`, `required`, or `default` in a Flow input/output/nested-property overlay. Those
are executable JSON-Schema fields and belong only in the language contract's generated RPC schema;
the CLI and host installer reject them in overlays.

For outputs, declare only real top-level fields from the RPC `outputSchema`; use `properties` or
`items` only to improve nested variable discovery. Do not create synthetic passthrough outputs and
do not use the removed `outputs[].valueFrom` or `AgentStep.outputBindings` model.

A useful overlay can therefore stay small:

```json
{
  "flowNodes": [{
    "tool": "report_build",
    "inputs": [{ "name": "format", "title": "Format", "options": ["pdf", "html"] }],
    "outputs": [{ "name": "file", "title": "Generated file" }]
  }]
}
```

Locale files are display-only deltas keyed by tool and canonical port name; they never repeat
types or executable behavior:

```json
{
  "flowNodes": {
    "report_build": {
      "inputs": { "format": { "title": "格式" } },
      "outputs": { "file": { "title": "生成文件" } }
    }
  }
}
```

Both the CLI and host installer reject unknown tools, ports, nested fields, and output properties.
Fix the contract or overlay rather than bypassing those checks.

### Reference upstream values directly

Flows expose both a step's effective input and its result. The canvas authors:

```text
{{node.<id>.input.outputDirectory}}
{{node.<id>.result.files[0]}}
```

The compiler emits:

```text
{{steps.0.input.outputDirectory}}
{{steps.0.result.files[0]}}
```

The `input` channel is the post-template-resolution argument snapshot, recursively filtered for
sensitive names and schema fields. Missing paths fail explicitly. This is why plugins must not
duplicate input passthroughs in `flowNodes.outputs`.

## Implement runtime behavior safely

- A Worker is an out-of-process newline-delimited JSON-RPC 2.0 program with its own classpath or
  runtime dependencies. `stdout` is protocol-only. Keep each UTF-8 request and response below the
  enforced 16 MiB frame limit; paginate or return opaque file references for large data.
- Request the minimum manifest permissions. File access uses host-mediated opaque references; the
  iframe never receives native paths. Database access requires explicit `database` permission and
  provisioning; never fall back to host credentials.
- Split work longer than `backend.callTimeoutSeconds` into start/status/cancel job methods. Emit
  progress through the SDK job channel and diagnostics through the runtime's structured stderr
  logging. In Java, use SLF4J, log failures with the throwable, and catch-log-rethrow async bodies.
- Mark a write/external AI tool `idempotent: true` only when its handler really deduplicates side
  effects. Flow retries reuse a stable `<root-run>:step:<index>` call id after restart. Java exposes
  it as `RpcContext.callId()`; persist the id with the effect/result and return the recorded result
  on replay. The stable id alone does not provide idempotency.
- File grants are session/process-local. A recovered run that depends on an expired file grant
  cannot resume and must start again with newly selected files.

## Develop the iframe UI

The UI runs in a sandboxed iframe with strict CSP. Use `@infinia/plugin-sdk` for host/Worker calls
and `@infinia/plugin-ui` for host-consistent Vue/Vuetify behavior; never call the network or OS
directly from the iframe.

Prefer `mountFengYuApp`, which correctly binds live theme and locale state. Custom bindings must
subscribe to `client.on('environment', ...)` before awaiting `client.ready()`, merge partial events,
and update document, Vuetify, and message-table state. When this shared behavior is wrong, fix and
test the SDK/UI kit rather than adding per-plugin workarounds. Keep
`frontend/src/plugins/md3-themes.ts` and `toolchain/ui/src/theme.ts` value-aligned.

Run `fengyu dev` first; it extracts the contract and writes the exact manifest Vite reads at
`target/fengyu-manifest/manifest.json`. Start the real Worker separately on `127.0.0.1:24057`:

```bash
# Java: debug the generated test-scope main from the IDE
PluginDevMain.main()

# Python / Go: run under the language debugger or from a terminal
cd worker && python3 worker.py --dev
cd worker && go run . --dev
```

All three entry points use the same per-start token file at `~/.fengyu/dev-token-24057` and the
same JSON-RPC handlers as production stdio. A configured but unavailable endpoint must surface an
RPC error; use `mockWorker: true` only for intentional UI-only/stub work.

## Validate, build, and install

Use the CLI for packaging; never hand-zip:

```bash
fengyu check .
fengyu build .                    # dist/<id>-<version>.fyp + .fyp.sha256
fengyu build . --out dist/x.fyp --skip-tests
```

Treat the `.fyp` and checksum as a pair; the checksum detects corruption but is not a trusted
publisher signature. Install through the host marketplace UI or, for automated local verification,
the authenticated `POST /api/plugin-market/upload` path used by `scripts/e2e-smoke.sh`. There is no
`fengyu plugin install` command.

Choose focused verification based on the change:

- Official UI: `yarn install --immutable`, tests/typecheck as available, then `yarn run build` in
  `ui-src/`. Third-party scaffold: the equivalent npm commands.
- Java Worker: the plugin Maven tests plus a real JSON-RPC round trip. Python/Go Worker: the
  runtime-native tests and a packaged-host round trip.
- Contract/Flow change: `fengyu generate`, `fengyu check`, and diff the generated manifest; test a
  representative variable path when Flow behavior changed.
- Package change: `fengyu build` without `--skip-tests`, inspect the archive when contents matter,
  then run the narrow host smoke path.
- Toolchain-wide or release verification: use the separate `toolchain-release` skill rather than
  duplicating its release gates here.

Never publish, tag, push, or install into an external environment unless the user explicitly asks.

## Reject legacy models

Do not generate or recommend JavaFX views, `FengYuPluginV2`, in-process Spring plugin beans,
`ServiceLoader` SPI registration, shared host classpaths/JPA contexts, host-provided Worker
dependencies, arbitrary backend commands, legacy `fengyu.plugin.json`, or direct iframe
`fetch`/`connect-src`. These do not describe FengYu 4.x.
