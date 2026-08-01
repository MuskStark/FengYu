---
title: File I/O
description: The grant model and five endpoints under /api/plugin-runtime/{id}/files that let a sandboxed plugin read and write files via opaque FileRefs, with temp storage wiped on host shutdown.
lang: en
---

# File I/O

A plugin runs sandboxed and cannot touch the filesystem directly. Instead it requests file access through `FengYuClient.files.*`, which the host translates into calls under `/api/plugin-runtime/{id}/files/**`. The host mints an opaque **FileRef** (id `ref_<uuid>`) for each granted path, snapshots uploads into a temp tree, and rewrites FileRefs to absolute paths only at the moment it dispatches a worker RPC. Permissions gate every endpoint.

## The grant model

A FileRef is an opaque handle:

```ts
interface FileRef { id: string; name: string; kind: 'file'|'directory'; access: 'read'|'write'|'read-write'; size: number }
```

- The `id` (e.g. `ref_3f2a...`) is the only value the UI ever sees.
- Grants live in an in-memory `ConcurrentHashMap` keyed by id, scoped to the plugin id.
- At RPC dispatch time, the host walks the `params`, finds any value shaped `{id:"ref_..."}`, resolves it via the grant map, and **rewrites it to an absolute filesystem path** before sending to the worker. The worker receives real paths, never raw upload bytes and never another plugin's refs.

## Endpoints

All five endpoints live under base `/api/plugin-runtime/{id}/files`. Each is gated by a permission declared in the plugin manifest.

| Method + path | Body | Permission | Returns |
| --- | --- | --- | --- |
| `POST /upload` | multipart `file` | `files.read` | `FileRef` (a single uploaded file, snapshotted into temp) |
| `POST /upload-directory` | multipart `files` + `paths[]`; optional `access=read-write` | `files.read`; both `files.read` + `files.write` for `read-write` | `FileRef` (a directory rebuilt in temp from the uploaded tree) |
| `POST /native` | JSON `{path, kind, access}` | `files.read` **and/or** `files.write` | `FileRef` (desktop only — the Electron native dialog returns a native path the host wraps as a ref) |
| `POST /output` | (none) | `files.write` | `FileRef` (a freshly allocated writable output directory) |
| `GET /export/{ref}` | — | `files.write` | A zip of the granted directory, streamed for download |

`/native` is meaningful only under the Electron desktop shell, where `ctx.desktop.pickFile` / `pickDirectory` yield real OS paths; in the browser, use `/upload` and `/upload-directory` instead. A workspace request uses native `read-write` access on desktop and an uploaded `read-write` working copy on the web. The requested access must match a permission the plugin actually holds.

A request that needs a permission the plugin did not declare returns `403`. See [Pitfalls](/en/plugins/pitfalls).

> The AI chat's "attach file for this conversation" affordance grants a file through these same endpoints (`/api/plugin-runtime/{pluginId}/files/native` on desktop, `/files/upload` in the browser). The resulting FileRef is scoped to the chosen plugin and lives for the chat session (it is not persisted; restart clears it).

## Temp storage and cleanup

Uploads and output directories live under a per-plugin temp root:

```
${java.io.tmpdir}/fengyu/runtime-files/<pluginId>/<uuid>/{in|out}/...
```

- The host snapshots every upload into a fresh `<uuid>/in/` tree. Symlinks inside an uploaded tree are rejected, and traversal outside the snapshot root is blocked.
- `POST /output` allocates a fresh `<uuid>/out/` directory.
- Grants are held in memory only — they do not survive a host restart.

### Cleanup

- The whole `runtime-files` tree is deleted in a `@PreDestroy` hook when the host shuts down.
- **There is no scheduled sweep.** A long-running host accumulates granted files until process exit. Do not rely on individual files being reclaimed mid-session.

## How the UI uses it

The `FengYuClient.files.*` helpers wrap these endpoints so the UI never builds multipart itself:

```js
const file   = await fengyu.files.open({ extensions: ['xlsx'] })      // → POST /upload under the hood
const inDir  = await fengyu.files.inputDirectory()                    // → POST /upload-directory
const project = await fengyu.files.workspaceDirectory()               // → writable selected project / working copy
const outDir = await fengyu.files.outputDirectory()                   // → POST /output  (needs files.write)
await fengyu.files.export(outDir)                                     // → GET  /export/{ref}
```

`workspaceDirectory()` requires `files.write`; it returns the selected native directory on desktop and a writable uploaded working copy in a browser. Pass every FileRef straight into an RPC — do not extract its `id` — so the host can recognize and rewrite the complete `{id, kind, access}` object before the worker sees it:

```js
const analysis = await fengyu.invoke('analyze', { filePath: file })
await fengyu.invoke('excel_execute', { outputDir: outDir, filePrefix: 'q3-' })
```

See [Official Plugin — Excel](/en/plugins/official-excel) for this flow end to end.

## Next steps

- [Worker (JSON-RPC)](/en/plugins/worker) — how the host rewrites FileRefs before dispatch.
- [UI Micro-frontend](/en/plugins/ui-microfrontend) — the `files.*` client API.
- [Manifest](/en/plugins/manifest) — declaring `permissions`.
