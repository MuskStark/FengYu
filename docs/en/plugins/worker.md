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

The `FengYu-Plugin-Sdk` artifact ships `JsonRpcWorker`, a tiny dependency-light runtime that reads requests from `stdin`, dispatches to registered handlers, and writes responses to `stdout`. Register handlers with `.on(method, handler)` and call `.run()`:

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
