---
title: SDK & CLI
description: Reference for the @fengyu/plugin-sdk TypeScript client, the independently-versioned Java Worker SDK (1.0.0), and the five fengyu plugin CLI subcommands — create, dev, build, validate, install.
lang: en
---

# SDK & CLI

Plugin authors use two SDKs (one per side of the runtime) and one CLI. The TypeScript SDK lives in the iframe UI; the Java Worker SDK builds the `worker.jar`; the `fengyu plugin` CLI scaffolds, develops, packages, validates, and installs plugins. The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0`) is **independently versioned** from the host app and published to GitHub Packages.

## `@fengyu/plugin-sdk` (TypeScript)

Source: `plugin-sdk/typescript/src/index.ts`. The current SDK version is `1.0.0`. Import the singleton client and the helper/types:

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@fengyu/plugin-sdk'
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

Artifact `fan.summer.fengyu.sdk:fengyu-plugin-sdk:1.0.0` (independently versioned, published to GitHub Packages). Package `fan.summer.fengyu.sdk`. The runtime is `JsonRpcWorker`; handlers implement the `@FunctionalInterface PluginHandler`:

```java
Object handle(Map<String, Object> params) throws Exception
```

Build a worker main class:

```java
public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .on("hello", MyHandler::handle)         // register one method per call
            .run();                                  // blocks, reading stdin / writing stdout
    }
}
```

- `on(method, handler)` rejects duplicates, blank method names, and `null` handlers.
- `run()` redirects `System.out` to `System.err` for the run loop — protocol output stays clean.
- Strict request parsing surfaces the canonical JSON-RPC error codes: `-32700` (parse error), `-32600` (invalid request — missing/blank method or wrong `jsonrpc` version), `-32601` (unknown method), and `-32000` (handler failure). The request `id` is echoed back whenever it was parseable.
- Throw `JsonRpcWorker.RpcException(code, message)` for a structured error; anything else surfaces as `-32000`.
- Helpers: `JsonRpcWorker.string(params, key)`, `JsonRpcWorker.integer(params, key, fallback)`.
- Build a shaded fat JAR with `maven-shade-plugin`; set `mainClass` to your `*WorkerMain`. See [Build & Deploy](/en/plugins/build-deploy).

### Database environment

With the manifest `database` permission, the host injects `FENGYU_DB_TYPE`, `FENGYU_DB_DRIVER`,
`FENGYU_DB_URL`, `FENGYU_DB_USERNAME`, `FENGYU_DB_PASSWORD`, and `FENGYU_PLUGIN_DATA_DIR` into the
Worker. The last value defaults to the stable private path `~/.fengyu/plugin-data/<pluginId>/`.

```java
PluginDatabaseConfig database = PluginDatabaseConfig.fromEnvironment(System.getenv())
    .orElseThrow(() -> new IllegalStateException("database permission is required"));
```

The environment is Worker-only. Do not forward it to the iframe. Plugins own their migrations,
table prefix, and credential encryption; see [Plugin Database Standard](/en/plugins/database).

## `fengyu plugin` CLI

Source: `plugin-cli/src/cli.mjs`. Usage:

```
fengyu plugin <create|dev|build|validate|install> [path] [options]
```

There are exactly **five** subcommands — there is no `init`.

| Subcommand | Options | Description |
| --- | --- | --- |
| `create <path> --id <id>` | `--id` (required), `--no-install`, `--ui-only` | Scaffold a new plugin. By default produces a complete Vue + Java project (`vue-java` template): `manifest.json`, `fengyu.plugin.json`, `ui-src/` (Vue), `worker/` (Java + Maven Wrapper), and `.mvn/settings.xml`. `--ui-only` keeps the lightweight UI-only template. Runs `npm install` by default (`--no-install` skips it). Refuses to overwrite an existing directory. |
| `dev [path] [--port <n>]` | `--port` (default `4173`) | Start a loopback dev host. For a declared worker project it builds the worker JAR (if missing), starts the **real** Java JSON-RPC worker, and serves a simulator that forwards `rpc.invoke` to it over `POST /__rpc`; Java edits rebuild + restart the worker. For a Vue/Vite project it spawns Vite (HMR); for a static project it serves `ui/` + an SSE reload watcher with a mock. |
| `build [path] [--out <file>]` | `--out` (default `dist-package/<id>-<version>.fyp`), `--skip-tests` | For a declared project, run the full staged lifecycle (prepare → install → test → build → validate staging → package). `--skip-tests` skips tests only — never type checking or packaging. Zero-config Vue/Vite and static projects keep their existing build detection. The archive write is atomic — no partial `.fyp`, `.tmp-*`, or staging dir is left on failure. |
| `validate [path]` | — | Check the source manifest for object/escape errors; exits non-zero with a message on failure. (Build outputs are validated post-build by the staging step.) |
| `install <file> [--host <url>] [--token <t>]` | `--host` (default `http://127.0.0.1:24056`), `--token` (default `$FENGYU_TOKEN`) | Validate the `.fyp` **offline first** (archive limits/paths + manifest), then upload to the marketplace's `POST /api/plugin-market/upload`. An unsafe or invalid package is rejected with zero fetch calls. |

### Examples

```bash
# Scaffold (installs deps by default; add --no-install to skip)
fengyu plugin create ./my-plugin --id com.example.my-plugin

# Develop (Vue/Vite: spawns Vite + serves a simulator at /__fengyu)
fengyu plugin dev . --port 4173

# Package (runs the frontend build, validates, zips atomically)
fengyu plugin build . --out dist-package/com.example.my-plugin-1.0.0.fyp

# Sanity-check before publishing
fengyu plugin validate

# Install into a running host
fengyu plugin install ./com.example.my-plugin-1.0.0.fyp \
  --host http://127.0.0.1:24056 --token "$FENGYU_TOKEN"
```

The scaffolded project depends on `@fengyu/plugin-sdk` **and** [`@fengyu/plugin-ui`](/en/plugins/ui-components); its `src/main.ts` already calls `bindFengYuEnvironment` to sync theme/locale, and `provideFengYuClient` to inject the SDK client app-wide. Legacy static plugins (plain `ui/` with no build tooling) are still accepted by `dev` and `build`.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + dev loop in narrative form.
- [UI Components](/en/plugins/ui-components) — the `@fengyu/plugin-ui` Vuetify kit.
- [Worker (JSON-RPC)](/en/plugins/worker) — the protocol `JsonRpcWorker` implements.
- [Build & Deploy](/en/plugins/build-deploy) — the shaded-JAR + `.fyp` flow.
