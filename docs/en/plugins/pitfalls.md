---
title: Pitfalls
description: Common plugin traps — iframe CSP, FileRef resolution timing, environment subscription races, permission gating, and worker stdio framing — each as problem, cause, and fix.
lang: en
---

# Pitfalls

These are the traps plugin authors hit most often. Each is laid out as **problem → cause → fix**.

## 1. Inline scripts don't load in the iframe

**Problem.** Your plugin UI's inline `<script>` (or an inline event handler) silently fails to run; resources blocked.

**Cause.** The host enforces a strict Content-Security-Policy on the plugin iframe. Inline scripts and disallowed resources are refused by the CSP — they never execute.

**Fix.** Put all JavaScript in separate files loaded via `<script src>` (the scaffolder writes `<script type="module" src="app.js">`), and load every asset from the plugin's own `/plugin-runtime/{id}/**` tree. If you truly need an inline script, use the nonces the CSP allows. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## 2. The worker receives a path string, not the FileRef

**Problem.** You try to resolve a `ref_*` FileRef in the UI, or you hardcode a temp path you saw in the grant — and it breaks.

**Cause.** The host rewrites `ref_*` FileRefs to absolute filesystem paths **before** it dispatches the RPC. By the time the worker sees the params, every FileRef has been replaced with a real path string. The worker never receives the FileRef object. Likewise, the temp path under `${java.io.tmpdir}/fengyu/runtime-files/...` is an implementation detail — grant ids do not survive a host restart, and the layout is not stable.

**Fix.** Pass the FileRef straight through from UI to worker and let the host rewrite it; in the worker, treat the value as an ordinary path string. Never resolve refs or hardcode temp paths in the UI. See [File I/O](/en/plugins/file-io) and [Worker (JSON-RPC)](/en/plugins/worker).

```js
// UI — pass the ref through; do NOT try to read .id or build a path
import { createPluginRpc } from './generated/fengyu-rpc'
const rpc = createPluginRpc(fengyu)
const file = await fengyu.files.open({ extensions: ['xlsx'] })
await rpc.analyze({ filePath: file as unknown as string })   // host rewrites ref → path
```

## 3. The plugin never completes the host handshake

**Problem.** The iframe renders partially, but host RPC calls time out and theme or locale changes never arrive.

**Cause.** The plugin and host do not use the same `@infinia/plugin-sdk/protocol` version, or the UI starts invoking methods before `fengyu.ready()` completes. Toolchain 2 intentionally requires an exact protocol match.

**Fix.** Keep `@infinia/plugin-sdk`, `@infinia/plugin-ui`, CLI, and host on one toolchain release. Await `fengyu.ready()` directly, or use `bindFengYuEnvironment`, before the first host call. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## 4. The plugin stays dark/English while the host is light/Chinese

**Problem.** The plugin loads and RPC works, but its theme and language do not match the host or do
not react to later host changes.

**Cause.** Custom bootstrap code subscribes to `environment` only after awaiting `ready()`. The host
can send the initial event during iframe load, so that ordering creates a lost-event window. A local
`file:` dependency or installed `.fyp` can also retain the old UI-toolchain bundle after source is
fixed.

**Fix.** Use `mountFengYuApp`. For a custom binding, subscribe before calling/awaiting `ready()`,
merge partial environment updates, and update HTML attributes plus Vuetify and plugin i18n state.
Test an event while the ready promise is pending. Rebuild `@infinia/plugin-ui`, refresh the plugin's
copied dependency, rebuild/reinstall the `.fyp`, and inspect the installed asset rather than assuming
the source edit reached runtime.

## 5. A file operation returns 403

**Problem.** Calling an output or export operation (`files.outputDirectory()`, `files.export(ref)`, or the underlying `POST .../files/output`, `GET .../files/export/{ref}`) returns `403`.

**Cause.** Every file endpoint is gated by a permission declared in the manifest. A `files.write` operation attempted without `files.write` in the manifest `permissions` is rejected. The same applies to `files.read` for upload/native-read endpoints.

**Fix.** Declare **every** permission you use. If you read an uploaded file and also write split results + export a zip, you need both: `"permissions": ["files.read", "files.write"]`. See [Manifest](/en/plugins/manifest) and [File I/O](/en/plugins/file-io).

## 6. Logging to stdout corrupts the RPC stream

**Problem.** You add a `System.out.println(...)` (or a logger that writes to stdout) in the worker, and the host starts failing to parse responses — RPC calls hang or error.

**Cause.** The JSON-RPC worker communicates over **newline-delimited JSON on stdio**. `stdout` is the protocol channel: one JSON-RPC message per line. A log line on stdout is not valid JSON-RPC, so it desynchronizes the framing.

**Fix.** Log to **stderr only**. The Worker SDK enforces this for you by redirecting `System.out` to `System.err` for the duration of the run loop — but if you capture or bypass that, keep all diagnostic output on `stderr`. See [Worker (JSON-RPC)](/en/plugins/worker).

## Next steps

- [UI Micro-frontend](/en/plugins/ui-microfrontend) — CSP and the Vue/Vuetify contract.
- [Worker (JSON-RPC)](/en/plugins/worker) — stdio discipline and FileRef resolution.
- [File I/O](/en/plugins/file-io) — the permission model.
