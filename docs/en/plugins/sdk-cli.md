---
title: SDK & CLI
description: Reference for the TypeScript client, Java/Python/Go Worker SDKs, IDE simulator, and Toolchain 2 CLI.
lang: en
---

# SDK & CLI

Plugin authors use the iframe TypeScript SDK, one of three Worker SDKs, a Vite simulator + DevKit,
and the `fengyu` CLI. Java, Python, and Go Workers share protocol version 1 and the same reserved
startup handshake.
The Java Worker SDK (`fan.summer.fengyu.sdk:fengyu-plugin-sdk:2.1.0`) is independently versioned
from the host app and published to GitHub Packages.

## `@infinia/plugin-sdk` (TypeScript)

Source: `toolchain/sdk-ts/src/index.ts`. The current plugin-tooling version is `2.0.0`. Import the singleton client and the helper/types:

```ts
import { fengyu, FengYuClient, createId, type FileRef, type Environment } from '@infinia/plugin-sdk'
```

### `FengYuClient`

A `postMessage` bridge to the host. Construct your own with options, or use the exported `fengyu` singleton.

| Member | Signature | Notes |
| --- | --- | --- |
| `ready(options?)` | `(InvokeOptions?) => Promise<Environment>` | Deduplicates negotiation and requires exact protocol `2.0.0`. Applies and caches theme/locale. |
| `currentEnvironment()` | `→ Environment \| undefined` | Latest merged ready/event state, without a host round-trip. |
| `invoke<T>(method, params?, options?)` | `→ Promise<T>` | RPC to the worker. Aborting `signal` propagates cancellation to the host and Worker. |
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
interface Environment { protocolVersion: string; theme: Theme; locale: string; platform: 'web'|'desktop'; capabilities: HostMethod[] }
interface InvokeOptions { signal?: AbortSignal; timeoutMs?: number }
```

### `createId()`

`createId(): string` — correlation id for `postMessage` requests. Uses `crypto.randomUUID()` when available, and falls back to a deterministic counter-based id for opaque sandbox origins where Web Crypto is unavailable.

## Java Worker SDK

Artifact `fan.summer.fengyu.sdk:fengyu-plugin-sdk:2.1.0` (independently versioned, published to GitHub Packages). Package `fan.summer.fengyu.sdk`. The runtime is `JsonRpcWorker`; handlers are typed `(Input input, RpcContext ctx) -> Output`, where `Input`/`Output` are records generated from the manifest's `rpc.methods` and `PluginMethods` holds a constant per method name:

```java
Output handle(Input input, RpcContext ctx) throws Exception
```

Register handlers in a shared factory so the production entry point and the IDE-debug entry point run exactly the same code:

```java
public final class MyWorker {
    private MyWorker() {}
    public static JsonRpcWorker create() {
        MyHandlers handlers = new MyHandlers();
        return new JsonRpcWorker()
            .method(PluginMethods.HELLO, HelloInput.class, HelloOutput.class,
                    (HelloInput input, RpcContext ctx) -> handlers.hello(input, ctx));
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

- `method(name, Input.class, Output.class, handler)` rejects duplicate method names, blank names, and `null` handlers.
- `run()` redirects `System.out` to `System.err` for the run loop — protocol output stays clean.
  `run(InputStream, OutputStream)` (the overload that takes an explicit input/output pair) applies
  the same redirection, so both stdio entry points enforce the "stdout is JSON-RPC only" contract.
- The bundled SLF4J provider emits structured events to `stderr`; use
  `PluginLogging.setLevel(...)` for an explicit local override. In production the host supplies
  `FENGYU_LOG_LEVEL` and updates running Workers automatically.
- `serve(RpcTransport)` (new in 1.1.0) drives the same dispatch loop over any transport and
  performs **no** `System.setOut` redirection — that is exclusive to the stdio entry points.
  The devkit's loopback-TCP server uses `serve()` to expose your handlers to the IDE.
- Strict request parsing surfaces the canonical JSON-RPC error codes: `-32700` (parse error), `-32600` (invalid request — missing/blank method or wrong `jsonrpc` version), `-32601` (unknown method), and `-32000` (handler failure). The request `id` is echoed back whenever it was parseable.
- Throw `RpcException(code, message)` for a structured error; anything else surfaces as `-32000`.
- Params arrive typed: the SDK deserializes the JSON-RPC `params` into your generated `Input` record, so you read strongly typed fields (`input.name()`) instead of fishing values out of a `Map`.
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

## Python and Go Worker SDKs

- `toolchain/sdk-python` provides `fengyu_plugin_sdk.Worker` for Python 3.12+. Register methods
  with `worker.on(name, handler)` and call `worker.run()`. It owns stdout, handles
  `$/fengyu/initialize`, cancellation, locale metadata, and structured JSON-RPC errors.
- `toolchain/sdk-go` provides package `fengyu` for Go 1.26+. Register handlers with
  `fengyu.New().On(name, handler)` and call `worker.Run()`; the SDK implements
  the same handshake, cancellation, and newline-delimited transport.

Both scaffold variants vendor the small runtime into the generated project, so third-party builds
do not depend on a locally checked-out FengYu repository. The host never executes a manifest
command: it launches only `backend/worker.py` or `backend/worker[.exe]`.

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

## `fengyu` CLI

Source: `toolchain/cli/src/cli.mjs`. Toolchain 2 uses flat, conventional commands:

| Command | Options | Description |
| --- | --- | --- |
| `init <path> --id <id>` | `--runtime java\|python\|go`, `--no-install`, `--ui-only` | Create a standard Vue + Worker project, or a UI-only project. |
| `dev [path]` | — | Run the UI's standard `npm run dev` simulator. Debug `PluginDevMain` separately for Java breakpoints. |
| `check [path]` | — | Validate the manifest (or compile a code-first project's merged manifest) and standard UI/Worker layout without packaging. |
| `generate [path]` | — | Code-first projects only: run the contract extraction (Maven `generate-resources`, `proc:only`), compile the merged manifest into `target/fengyu-manifest/`, and regenerate the typed RPC client + method constants. Never modifies sources. |
| `migrate manifest-codegen <path>` | — | One-shot draft from a manifest-first project: splits `manifest.base.json` / flow overlay / i18n and generates an annotated Contract whose DTOs keep the manifest-first naming. Never deletes `manifest.json` — the author reviews and switches manually. |
| `build [path]` | `--out <file>`, `--skip-tests` | Run npm/Maven lifecycle commands, validate staging, and atomically write the `.fyp` plus checksum. |
| `sign <file>` | `--key <private.pem>`, `--key-id <id>` | Create an Ed25519 `<file>.sig.json` sidecar for a catalog entry. |

The legacy per-plugin build-config file and arbitrary command arrays are not supported. The standard
layout uses `manifest.json` plus `ui-src/package.json` and `worker/pom.xml` (or a root `pom.xml`);
the Worker build must produce one `target/*-worker.jar`. Output defaults to
`dist/<id>-<version>.fyp`.

### Examples

```bash
# Scaffold (installs deps by default; add --no-install to skip)
fengyu init ./my-plugin --id com.example.my-plugin --runtime python

fengyu dev ./my-plugin
# Also debug PluginDevMain for a Java Worker.

# Package (runs the frontend build, validates staging, zips atomically)
fengyu check .
fengyu build . --out dist/com.example.my-plugin-1.0.0.fyp
fengyu sign dist/com.example.my-plugin-1.0.0.fyp --key publisher.pem --key-id example-2026
```

The scaffolded project depends on `@infinia/plugin-sdk` **and** [`@infinia/plugin-ui`](/en/plugins/ui-components); its `src/main.ts` calls `mountFengYuApp`, which owns environment synchronization, client injection, mount, and pagehide disposal.

## Next steps

- [Getting Started](/en/plugins/getting-started) — the create + IDE-debug loop in narrative form.
- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` Vuetify kit.
- [Worker (JSON-RPC)](/en/plugins/worker) — the protocol `JsonRpcWorker` implements.
- [Build & Deploy](/en/plugins/build-deploy) — the shaded-JAR + `.fyp` flow.
