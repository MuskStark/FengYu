---
title: Worker (JSON-RPC)
description: The FengYu plugin worker is an out-of-process executable that speaks newline-delimited JSON-RPC 2.0 over stdio, with FileRefs resolved to absolute paths by the host before dispatch.
lang: en
---

# Worker (JSON-RPC)

The worker is the plugin's backend. It is an ordinary executable — typically a shaded JAR launched by `java -jar backend/worker.jar` — that the host spawns as its **own OS process** and drives over **JSON-RPC 2.0** using newline-delimited messages on stdio. The worker never lives in the host Spring context.

## Protocol

The host sends a request and reads one response per line:

```jsonc
// host → worker (one line on stdin)
{"jsonrpc":"2.0","id":"req-1","method":"render","params":{"markdown":"# hi"}}

// worker → host (one line on stdout)
{"jsonrpc":"2.0","id":"req-1","result":{"success":true,"html":"<h1>hi</h1>"}}
```

| Message | Shape |
| --- | --- |
| Request | `{jsonrpc:"2.0", id, method, params}` |
| Response (success) | `{jsonrpc:"2.0", id, result}` |
| Response (error) | `{jsonrpc:"2.0", id, error:{code, message}}` |

Messages are **newline-delimited**: one JSON object per line on `stdin`, one per line on `stdout`. The `id` correlates the response to its request.

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

The Settings page controls one threshold shared by the main application and every Worker:
`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`. A new process receives it as
`FENGYU_LOG_LEVEL`; a running SDK Worker receives `$/fengyu/logging/setLevel` as an internal
JSON-RPC notification and updates existing logger instances immediately. Plugins must not register
that reserved method themselves.

## Per-call timeout

Every invoke is bounded by a timeout. The host (`PluginProcessManager`) waits that many seconds for the worker's response; when it elapses, **the worker process is killed** and the next call lazily restarts it. This is deliberate: the SDK dispatch loop is single-threaded, so a stuck handler cannot be cancelled any other way — the only recovery is to tear the process down.

| Source | Priority |
| --- | --- |
| `aiTools[].timeoutSeconds` (manifest) | highest — used when the AI tool path invokes the method |
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
file locks after the host has gone. The host also installs its own JVM shutdown hook that calls
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

1. The UI calls `client.invoke("analyze", { filePath: <FileRef> })`.
2. The host's `PluginProcessManager` walks the params, finds any value shaped like a FileRef (`{id:"ref_..."}`), and **rewrites it to an absolute filesystem path** using its in-memory grant table.
3. The worker receives `{filePath: "/tmp/fengyu/runtime-files/.../in/data.xlsx"}` — a real path it can open directly.

The worker treats these as ordinary string paths; it does not need to know the FileRef shape.

## Worker SDK (Java)

The `toolchain/sdk-java` artifact ships `JsonRpcWorker`, a tiny dependency-light runtime that reads requests from `stdin`, dispatches to registered handlers, and writes responses to `stdout`. Register handlers with `.on(method, handler)` and call `.run()`:

```java
package com.example.myplugin;

import fan.summer.fengyu.sdk.JsonRpcWorker;
import java.util.Map;

public final class MyWorkerMain {
    private MyWorkerMain() {}
    public static void main(String[] args) throws Exception {
        new JsonRpcWorker()
            .on("hello", MyWorkerMain::hello)
            .run();
    }

    static Object hello(Map<String, Object> params) {
        return Map.of("success", true, "echo", params.get("name"));
    }
}
```

`PluginHandler` is a `@FunctionalInterface` — `Object handle(Map<String,Object> params) throws Exception`. Throw `JsonRpcWorker.RpcException(code, message)` for a structured error; any other exception surfaces as `-32000`. Helper accessors `JsonRpcWorker.string(params, key)` and `JsonRpcWorker.integer(params, key, fallback)` read params safely.

### Reference implementations

The two official plugins are the canonical examples:

- **`MarkdownWorkerMain`** registers a single method:

  ```java
  new JsonRpcWorker().on("render", params -> plugin.invoke("render", params)).run();
  ```

- **`ExcelWorkerMain`** registers three action methods plus six AI-tool methods:

  ```java
  return new JsonRpcWorker()
      .on("analyze",       p -> plugin.invoke("analyze", p))
      .on("configure",     p -> plugin.invoke("configure", p))
      .on("split",         p -> plugin.invoke("split", p))
      .on("excel_analyze", p -> analyze.analyze(JsonRpcWorker.string(p, "filePath")))
      // ... excel_configure, excel_complex_config, excel_execute, excel_query, excel_cancel
      ;
  ```

See [Official Plugin — Markdown](/en/plugins/official-markdown) and [Official Plugin — Excel](/en/plugins/official-excel) for full walkthroughs.

## Packaging the worker

Build the worker as a shaded fat JAR with `maven-shade-plugin`, set the `mainClass` to your `*WorkerMain`, and copy the result to `backend/worker.jar`. See [Build & Deploy](/en/plugins/build-deploy) for the official build flow and the equivalent single-plugin `fengyu plugin build`.

## Next steps

- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the UI side that calls `client.invoke`.
- [File I/O](/en/plugins/file-io) — how FileRefs are minted and resolved.
- [Pitfalls](/en/plugins/pitfalls) — stdio discipline, FileRef timing, and more.
