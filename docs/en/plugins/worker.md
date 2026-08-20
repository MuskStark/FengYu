---
title: Worker (JSON-RPC)
description: The FengYu plugin worker is an out-of-process executable that speaks newline-delimited JSON-RPC 2.0 over stdio, with FileRefs resolved to absolute paths by the host before dispatch.
lang: en
---

# Worker (JSON-RPC)

The worker is the plugin's backend. It is a Java 21 shaded JAR, Python 3.12+ script, or Go 1.26+
native executable that the host spawns as its **own OS process** and drives over **JSON-RPC 2.0**
using newline-delimited messages on stdio. The worker never lives in the host Spring context.

## Protocol

The host sends a request and reads one response per line:

```jsonc
// host → worker (one line on stdin)
{"jsonrpc":"2.0","id":"req-1","method":"render","params":{"markdown":"# hi"},"_fengyu":{"locale":"zh"}}

// worker → host (one line on stdout)
{"jsonrpc":"2.0","id":"req-1","result":{"success":true,"html":"<h1>hi</h1>"}}
```

| Message | Shape |
| --- | --- |
| Request | `{jsonrpc:"2.0", id, method, params, _fengyu?}` |
| Response (success) | `{jsonrpc:"2.0", id, result}` |
| Response (error) | `{jsonrpc:"2.0", id, error:{code, message}}` |

Messages are **newline-delimited**: one JSON object per line on `stdin`, one per line on `stdout`. The `id` correlates the response to its request.

The optional top-level `_fengyu` object is a **reserved, host-owned metadata envelope** — see [Reserved metadata channel](#reserved-metadata-channel). Plugins must treat any frame-root key beginning with `_fengyu` as host-owned and never declare it as a method input.

## Startup handshake and operations

New manifests set `backend.protocolVersion: 1`. Before any plugin method is eligible for use, the
host calls reserved method `$/fengyu/initialize` with host/plugin versions and capabilities. The
SDK returns its protocol and runtime (`java`, `python`, or `go`); a mismatch fails startup and an
update is rolled back to the previous healthy package.

Runtime state is available at `GET /api/plugin-runtime/status` and
`GET /api/plugin-runtime/{id}/status`. States are `STOPPED`, `STARTING`, `HEALTHY`, `DEGRADED`,
`BACKOFF`, `FAILED`, `UPDATING`, and `DISABLED`; failures are categorized (compatibility,
integrity/signature, spawn/handshake/protocol, timeout/crash, sandbox, resource, permission).
Three rapid startup crashes engage exponential lazy-restart backoff: 30, 60, 120, 240, then at
most 300 seconds.

`backend.resources.memoryMb` and `maxProcesses` bound the complete worker tree. Linux/macOS use a
host watchdog; Windows uses Job Object memory/process limits in the kernel. Crossing a ceiling
terminates the tree and records `RESOURCE_LIMIT`.

## Reserved metadata channel

Besides the standard JSON-RPC 2.0 fields (`jsonrpc`, `id`, `method`, `params`), the host may attach a top-level `_fengyu` object carrying host-owned, transport-level metadata. It is **never** part of `params`, so it cannot collide with a plugin method's own input fields. The Worker SDK reads it and binds it to the per-call context; a plugin reads it through `RpcContext`, never directly from the frame.

| Field | Meaning |
| --- | --- |
| `_fengyu.locale` | The request locale (e.g. `"zh"`, `"en"`), bound to `RpcContext.locale()` and `WorkerLocale` so message-bundle resolution honours the caller's language. Omitted when the host has no locale for the call (the worker then defaults to English). |

> **Reserved key.** Any frame-root key beginning with `_fengyu` is host-owned. A plugin method may freely declare a parameter named `locale` (or any other non-reserved name) in its `inputSchema`; it deserializes from `params` and is never overwritten by the request locale. The Worker SDK still accepts the legacy `params.locale` key as a fallback so a host that has not yet adopted the `_fengyu` envelope keeps working across the rollout.

> **Logs go to stderr.** `stdout` is reserved for protocol messages. The Worker SDK enforces this by redirecting `System.out` to `System.err` for the duration of the run loop — see [Pitfalls](/en/plugins/pitfalls).

## Logging

Use the ordinary SLF4J API in Java plugin code:

```java
private static final Logger log = LoggerFactory.getLogger(MyHandler.class);

log.debug("Loaded {} rows", rowCount);
log.error("Export failed", exception);
```

The SDK bundles a Worker-specific SLF4J provider. Each event is written as one structured line on
`stderr`, preserving `TRACE`, `DEBUG`, `INFO`, `WARN`, and `ERROR`, plus the logger name, thread,
formatted message, and exception stack. The host parses the event, redacts injected secrets,
forwards it at the same level into the host log, and publishes it through the existing plugin-log
REST/SSE surface. Direct free-form `System.err` output from older Workers remains compatible and
defaults to `INFO` when no level token can be recognized.

Forwarded events are also persisted to their own rolling file at `<LOG_DIR>/plugin-<pluginId>.log` (10 MB and daily rotation, 7-day history, 50 MB cap per plugin), so recent plugin output survives a host restart. The shared `fengyu.log` still contains every event as well.

The Settings page controls one threshold shared by the main application and every Worker:
`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`. A new process receives it as
`FENGYU_LOG_LEVEL`; a running SDK Worker receives `$/fengyu/logging/setLevel` as an internal
JSON-RPC notification and updates existing logger instances immediately. Plugins must not register
that reserved method themselves.

## Per-call timeout

Every invoke is bounded by a timeout. The host (`PluginProcessManager`) waits that many seconds for the worker's response; when it elapses, **the worker process is killed** and the next call lazily restarts it. This is deliberate: the SDK dispatch loop is single-threaded, so a stuck handler cannot be cancelled any other way — the only recovery is to tear the process down.

| Source | Priority |
| --- | --- |
| Caller-supplied timeout (host-internal) | overrides the default for a specific call |
| `backend.callTimeoutSeconds` (manifest) | plugin-wide default |
| Built-in default | `60` |

All declared values are clamped to `[1, 600]` seconds (the cap protects against a malicious manifest pinning a worker indefinitely). Declare a longer timeout only for methods that genuinely need it, and prefer job mode (below) when the work is unbounded.

## Worker lifecycle and host exit

A worker is an out-of-process JVM that must never outlive its host. Since SDK 1.2.0 the production
`run()` entry point installs two complementary watchdogs so a worker terminates the moment the host
goes away, regardless of how the host exits:

- **stdin EOF (primary).** When the host closes the worker's stdin pipe — which the OS does
  automatically when the host JVM dies, gracefully or by signal — the dispatch loop returns and the
  worker calls `System.exit(0)`.
- **Parent-process liveness (auxiliary).** A daemon thread polls the snapshot of the parent
  `ProcessHandle`; if the parent disappears while the dispatch loop is still blocked on stdin, the
  worker exits. This covers the rare case where a pipe is held open by an intermediate launcher.

Both paths converge on an explicit `System.exit(0)` so that non-daemon threads a plugin may have
created (a HikariCP pool, a scheduled executor) cannot keep the JVM alive and hold embedded-database
file locks after the host has gone. SDK 2.0.0 adds `JsonRpcWorker.onClose(AutoCloseable)`: registered
resources close once in reverse registration order before the forced exit. Register handler-owned
job registries, pools, and stores there instead of relying only on process termination. The host also
installs its own JVM shutdown hook that calls
`PluginProcessManager.close()` — which `destroy()`/`destroyForcibly()` every tracked worker and
recursively kills worker descendants (e.g. a `pip` subprocess) — as a belt-and-braces backstop
alongside Spring's `@PreDestroy`. The desktop shell tree-kills the entire backend process tree on
quit. On Linux, `bwrap --die-with-parent --new-session` provides the same guarantee at the kernel
level; on Windows the host assigns each worker to a Win32 **Job Object** with `KILL_ON_JOB_CLOSE`,
so closing the job handle (or `TerminateJobObject`) reliably tears down the whole tree — the
process-layer isolation backend described in [Plugin System → Process isolation backends](/en/architecture/plugin-system#process-isolation-backends).
On macOS the watchdog + tree-kill layers provide the same guarantee.

## Long tasks (job mode)

Any operation that may exceed its declared timeout must be split into a **start / status / cancel** triple rather than a single blocking method. The launcher returns a `jobId` immediately; the UI or AI polls `*_status` with a cursor to drain streamed logs; `*_cancel` aborts. This is the only supported pattern for unbounded work — `pip download`, large-workbook splits, batch sends, etc.

The SDK ships a `Jobs` registry (`fan.summer.fengyu.sdk.Jobs`) that implements this in three lines:

```java
import fan.summer.fengyu.sdk.Jobs;

public final class MyWorkerMain {
    public static void main(String[] args) {
        Jobs jobs = new Jobs();
        // ... handlers hold the Jobs reference
    }
}

// Launch handler — validate up front, then hand off to a virtual thread.
Jobs.Job job = jobs.start("EXPORT", handle -> {
    handle.onCancel(() -> pool.shutdownNow());            // cooperative cancel hook
    Result res = doExpensiveWork(handle::log, handle::isCancelled);
    if (handle.isCancelled()) throw new Jobs.CancellationException();
    handle.setSummary(Map.of("fileCount", res.files()));  // surfaced by status polling
});
return Map.of("success", true, "jobId", job.id);

// Status handler — drain logs from cursor; result appears when done.
return jobs.snapshot(jobId, cursor);

// Cancel handler — fires the onCancel hook registered at start.
return jobs.cancel(jobId);
```

Key properties of `Jobs`:

- **Bounded retention.** Completed jobs are retained for 30 minutes (configurable) and capped at 200 entries; oldest completed jobs are evicted first. This prevents unbounded memory growth in workers that fan out many jobs.
- **Cooperative cancellation.** `Cancellable.onCancel(Runnable)` runs once when the host calls `*_cancel`; the body should also poll `Cancellable.isCancelled()` between long steps and throw `Jobs.CancellationException` to signal a clean abort.
- **Streaming logs.** `Cancellable.log(String)` appends to a per-job queue; `snapshot(jobId, cursor)` returns the tail from `cursor` plus the next cursor, so the UI can poll incrementally.
- **Locale and teardown safety.** A virtual job inherits the locale of its start request, so deferred
  summaries/logs use the same language. `Jobs.close()` cancels every running job and rejects new
  starts; cancellation hooks remain once-only even when registration races cancellation.

Reference implementations:

- **`plugin-offlinepython`** — `build.start` / `build.status` / `build.cancel` (and the `deploy.*` triplet) wrap `pip download`, which routinely exceeds 60s. The launcher registers `ProcessRunner::cancel` as the cancel hook.
- **`plugin-excel`** — `split_start` / `split_status` / `split_cancel` (UI) and `excel_execute_start` / `excel_execute_status` (AI) wrap large-workbook splits; the `ExcelSplitter` engine takes a `Supplier<Boolean> shouldCancel` probe that is wired to `handle::isCancelled`.

## Error object

A failed call returns an `error` object instead of `result`:

```json
{"jsonrpc":"2.0","id":"req-2","error":{"code":-32601,"message":"Unknown method: frobnicate"}}
```

| Code | Meaning |
| --- | --- |
| `-32601` | Unknown / unregistered method |
| `-32000` | Uncaught exception in the handler |
| (custom) | Any code you raise via `RpcException` |

## FileRef resolution

When a UI passes a file picked through the SDK (see [File I/O](/en/plugins/file-io)), it arrives as a **FileRef** — an opaque `{id, name, kind, access, size}` object whose `id` begins with `ref_`. The worker never receives raw upload bytes. Instead:

1. The UI invokes the worker's `analyze` method, passing `{ filePath: <FileRef> }`.
2. The host's `PluginProcessManager` walks the params, finds any value shaped like a FileRef (`{id:"ref_..."}`), and **rewrites it to an absolute filesystem path** using its in-memory grant table.
3. The worker receives `{filePath: "/tmp/fengyu/runtime-files/.../in/data.xlsx"}` — a real path it can open directly.

The worker treats these as ordinary string paths; it does not need to know the FileRef shape.

## Worker SDKs

The canonical runtimes live in `toolchain/sdk-java`, `toolchain/sdk-python`, and
`toolchain/sdk-go`. Each owns stdout, handles the startup/control methods, and exposes method
registration plus a blocking run loop. The examples below show Java; generated Python and Go
projects use their equivalent `Worker.on(...)/run()` and `fengyu.New().On(...).Run()` APIs.

### Java

The `toolchain/sdk-java` artifact ships `JsonRpcWorker`, a tiny dependency-light runtime that reads requests from `stdin`, dispatches to registered handlers, and writes responses to `stdout`. Register handlers with the typed `.method(...)` API and call `.run()`:

```java
package com.example.myplugin;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import fan.summer.fengyu.sdk.RpcContext;
import com.example.myplugin.generated.PluginMethods;
import com.example.myplugin.generated.HelloInput;
import com.example.myplugin.generated.HelloOutput;

public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        MyHandlers handlers = new MyHandlers();
        new JsonRpcWorker()
            .method(PluginMethods.HELLO, HelloInput.class, HelloOutput.class,
                    (HelloInput input, RpcContext ctx) -> handlers.hello(input, ctx))
            .run();
    }
}
```

Handlers are typed: `(Input input, RpcContext ctx) -> Output`. `Input` and `Output` are the record classes generated from `manifest.json`'s `rpc.methods` (e.g. `HelloInput`, `HelloOutput`), and `PluginMethods` holds a constant for each method name. The SDK deserializes the incoming params into your `Input` record, binds an `RpcContext` (cancellation token + logger) to the handler thread, and serializes the returned `Output` back into the response. Throw `RpcException(code, message)` for a structured error; any other exception surfaces as `-32000`.

### Reference implementations

The two official plugins are the canonical examples:

- **`MarkdownWorkerMain`** registers a single method:

  ```java
  new JsonRpcWorker().method(
          PluginMethods.RENDER, RenderInput.class, RenderOutput.class,
          (RenderInput input, RpcContext ctx) -> plugin.render(input, ctx)).run();
  ```

- **`ExcelWorkerMain`** registers three action methods plus six AI-tool methods:

  ```java
  return new JsonRpcWorker()
      .method(PluginMethods.ANALYZE, AnalyzeInput.class, AnalyzeOutput.class,
              (AnalyzeInput input, RpcContext ctx) -> plugin.analyze(input, ctx))
      .method(PluginMethods.CONFIGURE, ConfigureInput.class, ConfigureOutput.class,
              (ConfigureInput input, RpcContext ctx) -> plugin.configure(input, ctx))
      .method(PluginMethods.SPLIT, SplitInput.class, SplitOutput.class,
              (SplitInput input, RpcContext ctx) -> plugin.split(input, ctx))
      .method(PluginMethods.EXCEL_ANALYZE, ExcelAnalyzeInput.class, ExcelAnalyzeOutput.class,
              (ExcelAnalyzeInput input, RpcContext ctx) -> analyze.analyze(input, ctx));
      // ... excel_configure, excel_complex_config, excel_execute, excel_query, excel_cancel
  ```

See [Official Plugin — Markdown](/en/plugins/official-markdown) and [Official Plugin — Excel](/en/plugins/official-excel) for full walkthroughs.

## Packaging the worker

Build the worker as a shaded fat JAR with `maven-shade-plugin` and set the `mainClass` to your `*WorkerMain`; `fengyu build` discovers the unique `target/*-worker.jar` and stages it as `backend/worker.jar`. See [Build & Deploy](/en/plugins/build-deploy).

## Next steps

- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the UI side that calls the worker via the generated typed client.
- [File I/O](/en/plugins/file-io) — how FileRefs are minted and resolved.
- [Pitfalls](/en/plugins/pitfalls) — stdio discipline, FileRef timing, and more.
