---
title: SDK & CLI
description: Reference for the @infinia/plugin-sdk TypeScript client, the independently-versioned Java Worker SDK (1.1.0), the @infinia/plugin-dev Vite plugin + fengyu-plugin-devkit for IDE debugging, and the two fengyu plugin CLI subcommands — create, build.
lang: en
---

# SDK & CLI

Plugin authors use two SDKs (one per side of the runtime), a Vite dev plugin + devkit for IDE debugging, and one CLI. The TypeScript SDK lives in the iframe UI; the Java Worker SDK builds the `worker.jar`; `@infinia/plugin-dev` + `fengyu-plugin-devkit` turn your editor into a FengYu host simulator so you can debug UI and worker with breakpoints; the `fengyu plugin` CLI scaffolds (`create`) and packages (`build`) plugins — development happens in the IDE. The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0`) is **independently versioned** from the host app and published to GitHub Packages.

## `@infinia/plugin-sdk` (TypeScript)

Source: `toolchain/sdk-ts/src/index.ts`. The current SDK version is `1.1.0`. Import the singleton client and the helper/types:

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@infinia/plugin-sdk'
```

### `FengYuClient`

A `postMessage` bridge to the host. Construct your own with options, or use the exported `fengyu` singleton.

| Member | Signature | Notes |
| --- | --- | --- |
| `ready()` | `() => Promise<Environment>` | Negotiates `sdkVersion`; **throws on major-version mismatch**. Applies theme/locale to the document. |
| `invoke<T>(method, params?, options?)` | `→ Promise<T>` | RPC to the worker. `InvokeOptions { signal?, timeoutMs? }`. |
| `notify(message)` | `→ Promise<boolean>` | Show a host toast. |
| `files.open(opts?, req?)` | `→ Promise<FileRef \| null>` | Single file. `{extensions?, filters?}`. Perm `files.read`. |
| `files.inputDirectory(req?)` | `→ Promise<FileRef \| null>` | Input directory. Perm `files.read`. |
| `files.outputDirectory(req?)` | `→ Promise<FileRef \| null>` | Writable output dir. Perm `files.write`. |
| `files.export(ref, req?)` | `→ Promise<boolean>` | Zip + download. Perm `files.write`. |
| `on(event, handler)` | `→ () => void` | Subscribe; returns unsubscribe. Emits `environment` updates. |
| `dispose()` | `→ void` | Tear down listeners + reject pending. |

Constructor options: `FengYuClientOptions { target?: Window (default window.parent), timeoutMs?: 30_000, allowedOrigin?: '*' }`.

### Types

```ts
type Theme = 'dark' | 'light'
type FileAccess = 'read' | 'write' | 'read-write'

interface FileRef     { id: string; name: string; kind: 'file'|'directory'; access: FileAccess; size: number }
interface FileFilter  { name: string; extensions: string[] }
interface Environment { sdkVersion?: string; theme: Theme; locale: string; platform?: 'web'|'desktop'; capabilities?: string[] }
interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
```

### `createId()`

`createId(): string` — correlation id for `postMessage` requests. Uses `crypto.randomUUID()` when available, and falls back to a deterministic counter-based id for opaque sandbox origins where Web Crypto is unavailable.

## Java Worker SDK

Artifact `fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.1.0` (independently versioned, published to GitHub Packages). Package `fan.summer.fengyu.sdk`. The runtime is `JsonRpcWorker`; handlers implement the `@FunctionalInterface PluginHandler`:

```java
Object handle(Map<String, Object> params) throws Exception
```

Register handlers in a shared factory so the production entry point and the IDE-debug entry point run exactly the same code:

```java
public final class MyWorker {
    private MyWorker() {}
    public static JsonRpcWorker create() {
        return new JsonRpcWorker()
            .on("hello", MyHandler::handle);         // register one method per call
    }
}
```

Production entry point — speaks JSON-RPC over stdin/stdout (how the host drives the worker):

```java
public final class MyWorkerMain {
    public static void main(String[] args) throws Exception {
        MyWorker.create().run();                      // blocks, reading stdin / writing stdout
    }
}
```

- `on(method, handler)` rejects duplicates, blank method names, and `null` handlers.
- `run()` redirects `System.out` to `System.err` for the run loop — protocol output stays clean.
- The bundled SLF4J provider emits structured events to `stderr`; use
  `PluginLogging.setLevel(...)` for an explicit local override. In production the host supplies
  `FENGYU_LOG_LEVEL` and updates running Workers automatically.
- `serve(RpcTransport)` (new in 1.1.0) drives the same dispatch loop over any transport. `run()` and `run(InputStream, OutputStream)` are unchanged; the devkit's loopback-TCP server uses `serve()` to expose your handlers to the IDE.
- Strict request parsing surfaces the canonical JSON-RPC error codes: `-32700` (parse error), `-32600` (invalid request — missing/blank method or wrong `jsonrpc` version), `-32601` (unknown method), and `-32000` (handler failure). The request `id` is echoed back whenever it was parseable.
- Throw `JsonRpcWorker.RpcException(code, message)` for a structured error; anything else surfaces as `-32000`.
- Helpers: `JsonRpcWorker.string(params, key)`, `JsonRpcWorker.integer(params, key, fallback)`.
- Build a shaded fat JAR with `maven-shade-plugin`; set `mainClass` to your `*WorkerMain`. See [Build & Deploy](/en/plugins/build-deploy).

### Database environment

With the manifest `database` permission, the host injects `FENGYU_DB_TYPE`, `FENGYU_DB_DRIVER`,
`FENGYU_DB_URL`, `FENGYU_DB_USERNAME`, `FENGYU_DB_PASSWORD`, and `FENGYU_PLUGIN_DATA_DIR` into the
Worker. The last value defaults to the stable private path
`<program-working-directory>/.fengyu/plugin-data/<pluginId>/`.

```java
PluginDatabaseConfig database = PluginDatabaseConfig.fromEnvironment(System.getenv())
    .orElseThrow(() -> new IllegalStateException("database permission is required"));
```

The environment is Worker-only. Do not forward it to the iframe. Plugins own their migrations,
table prefix, and credential encryption; see [Plugin Database Standard](/en/plugins/database).

## IDE development

Development happens in your editor, not through the CLI. The scaffolded `vite.config.ts` loads
`@infinia/plugin-dev`, which turns the Vite dev server into a FengYu host simulator: it serves an
iframe shell at `/__fengyu` (running your real plugin UI with HMR), bridges the
`@infinia/plugin-sdk` `postMessage` calls, and forwards `rpc.invoke` to the dev worker.

For the worker, run `PluginDevMain.main()` (scaffolded into `worker/src/test/java/...`) from your
IDE's **Debug** action. It starts the `fengyu-plugin-devkit` loopback TCP server at
`127.0.0.1:24057` that serves the **same handlers** as the production worker — so breakpoints in
your `JsonRpcWorker` handlers fire directly, with no JDWP remote attach. The devkit is a
test-scope dependency, so it never ships in the shaded production JAR.

```bash
# UI side (in ui-src/)
npm run dev                       # → http://127.0.0.1:5173/__fengyu

# Worker side (in your IDE)
Debug PluginDevMain.main()        # → listens on 127.0.0.1:24057
```

UI-only plugins set `mockWorker: true` (or omit `workerEndpoint`) — `rpc.invoke` returns a
deterministic stub, so you can iterate the UI before any worker exists. See
[`toolchain/dev/README.md`](https://github.com/MuskStark/FengYu/tree/main/toolchain/dev) for the full
guide. If `workerEndpoint` is configured, connection failures are surfaced as RPC errors and never
silently replaced by mock responses.

## `fengyu plugin` CLI

Source: `toolchain/cli/src/cli.mjs`. The CLI only scaffolds and packages — development and validation
both happen elsewhere (IDE for dev; `build` validates automatically). Usage:

```
fengyu plugin <create|build> [path] [options]
```

There are exactly **two** subcommands.

| Subcommand | Options | Description |
| --- | --- | --- |
| `create <path> --id <id>` | `--id` (required), `--no-install`, `--ui-only` | Scaffold a new plugin. By default produces a complete Vue + Java project (`vue-java` template): `manifest.json`, `fengyu.plugin.json`, `ui-src/` (Vue, with `@infinia/plugin-dev` wired into `vite.config.ts`), `worker/` (Java + Maven Wrapper, with `PluginDevMain` scaffolded under `src/test/java`), and `.mvn/settings.xml`. `--ui-only` keeps the lightweight UI-only template. Runs `npm install` by default (`--no-install` skips it). Refuses to overwrite an existing directory. |
| `build [path] [--out <file>]` | `--out` (default `dist-package/<id>-<version>.fyp`), `--skip-tests` | For a declared project, run the full staged lifecycle (prepare → install → test → build → **validate staging** → package). `--skip-tests` skips tests only — never type checking, validation, or packaging. Zero-config Vue/Vite and static projects keep their existing build detection. The archive write is atomic — no partial `.fyp`, `.tmp-*`, or staging dir is left on failure. |

::: tip What happened to `dev` / `validate` / `install`?
`fengyu plugin dev` moved to the IDE via `@infinia/plugin-dev` + `fengyu-plugin-devkit` (see
[IDE development](#ide-development) above) — you get real breakpoints instead of a CLI-managed
process. `validate` is now a built-in step of `build` (the staging tree is always validated before
packaging). `install` is done through the host's plugin marketplace UI (`POST /api/plugin-market/upload`);
see [Marketplace](/en/plugins/marketplace).
:::

### Examples

```bash
# Scaffold (installs deps by default; add --no-install to skip)
fengyu plugin create ./my-plugin --id com.example.my-plugin

# Develop: open the project in your IDE, then
#   UI:   cd ui-src && npm run dev
#   Worker: Debug PluginDevMain (see "IDE development" above)

# Package (runs the frontend build, validates staging, zips atomically)
fengyu plugin build . --out dist-package/com.example.my-plugin-1.0.0.fyp
```

The scaffolded project depends on `@infinia/plugin-sdk` **and** [`@infinia/plugin-ui`](/en/plugins/ui-components); its `src/main.ts` already calls `bindFengYuEnvironment` to sync theme/locale, and `provideFengYuClient` to inject the SDK client app-wide. Legacy static plugins (plain `ui/` with no build tooling) are still accepted by `build`.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + IDE-debug loop in narrative form.
- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` Vuetify kit.
- [Worker (JSON-RPC)](/en/plugins/worker) — the protocol `JsonRpcWorker` implements.
- [Build & Deploy](/en/plugins/build-deploy) — the shaded-JAR + `.fyp` flow.
