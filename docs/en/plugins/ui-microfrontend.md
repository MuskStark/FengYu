---
title: UI Micro-frontend
description: The plugin UI runs in a sandboxed iframe under a strict Content Security Policy and bridges to the host via the @infinia/plugin-sdk FengYuClient postMessage API — ready, invoke, files, notify, on.
lang: en
---

# UI Micro-frontend

The plugin's `ui/` directory is a self-contained micro-frontend (MF) that the host serves as static assets under `/plugin-runtime/{id}/**` and loads into a **sandboxed iframe** under a strict Content Security Policy. Inside the iframe, the `@infinia/plugin-sdk` package provides a `FengYuClient` that bridges to the host over `postMessage`. The plugin never talks to the OS directly — every privileged action goes through the client.

## The default entry: a Vue/Vuetify app

`fengyu init` scaffolds a Vue 3 + Vuetify app that builds into `ui/`. The generated `src/main.ts` delegates the complete bootstrap and teardown lifecycle to the shared UI kit:

```ts
import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the
// top-level `await` below — TS widens imported `const` bindings across await.
const client = fengyu
await mountFengYuApp({ root: App, client })
```

`mountFengYuApp` owns the complete UI lifecycle: it negotiates the environment once, synchronizes
Vuetify theme/locale, injects the client, mounts the app, and unmounts/unsubscribes/disposes on
`pagehide`. Pass `plugins: [pinia, i18n]` for extra Vue plugins and `onEnvironment` to synchronize
plugin-specific message tables. Inside components, call `useFengYuClient()` instead of importing
the singleton directly. The full component kit is documented on the [UI Components](/en/plugins/ui-components) page.

Legacy static plugins — a hand-written `ui/index.html` + `ui/app.js` that `import { fengyu } from './sdk.js'` — are still accepted. The rest of this page describes the SDK API that **both** entry styles share.

## Sandboxed iframe + CSP

The host loads the entry HTML and applies a Content Security Policy that restricts what the MF may do. Consequences you will hit:

- **No inline scripts.** All JS must come from external `<script src>` files (the scaffolder writes `<script type="module" src="app.js">`). See [Pitfalls](/en/plugins/pitfalls).
- **No direct `fetch` to arbitrary origins.** Talk to the backend through `FengYuClient`, or use the `apiBase` + `token` provided in `PluginContext` for raw multipart calls.
- **Fonts must be packaged with the plugin.** `font-src 'self' data:` supports both current
  same-origin font assets and fonts embedded by older toolchain releases; remote font origins remain
  blocked. `@infinia/plugin-ui` handles MDI font packaging automatically.
- Asset paths under `/plugin-runtime/{id}/**` are the only plugin URLs that bypass the token filter, so the UI can bootstrap without a credential.

## The `FengYuClient` API

`FengYuClient` (exported as the `fengyu` singleton) is a `postMessage` bridge to the host. Every method returns a `Promise` correlated by id; requests time out after 30s by default.

```ts
import { fengyu } from './sdk.js'

// 1. Negotiate protocol 2.0.0 and receive the host Environment.
const env = await fengyu.ready()
//   → { protocolVersion, theme, locale, platform, capabilities }
//   Throws unless the host speaks the exact protocol version.

// 2. Call the plugin's own worker (host forwards as JSON-RPC).
const out = await fengyu.invoke('render', { markdown: '# hi' })

// 3. Ask the user for files / directories.
const file: FileRef | null    = await fengyu.files.open({ extensions: ['xlsx'] })
const inDir: FileRef | null   = await fengyu.files.inputDirectory()
const outDir: FileRef | null  = await fengyu.files.outputDirectory()
const exported: boolean       = await fengyu.files.export(outDir)  // zip + download

// 4. Surface a toast in the host.
await fengyu.notify('Split complete')

// 5. Subscribe to host events. Returns an unsubscribe function.
const off = fengyu.on('environment', (e) => applyTheme(e.theme))
// ...later
off()
```

### Method reference

| Method | Returns | Notes |
| --- | --- | --- |
| `ready(options?)` | `Promise<Environment>` | Negotiates exact protocol `2.0.0`; concurrent calls share one handshake. Applies and caches `theme` + `locale`. |
| `currentEnvironment()` | `Environment \| undefined` | Latest merged ready/event state without another host round-trip. |
| `invoke(method, params?, options?)` | `Promise<T>` | RPC to the plugin worker. `options:{signal?, timeoutMs?}`. |
| `notify(message)` | `Promise<boolean>` | Shows a host toast. |
| `files.open({extensions?, filters?}, req?)` | `Promise<FileRef \| null>` | Open a single file. `null` if the user cancels. Perm `files.read`. |
| `files.inputDirectory(req?)` | `Promise<FileRef \| null>` | Pick an input directory. Perm `files.read`. |
| `files.outputDirectory(req?)` | `Promise<FileRef \| null>` | Allocate a writable output directory. Perm `files.write`. |
| `files.export(ref, req?)` | `Promise<boolean>` | Zip an output dir and trigger a download. Perm `files.write`. |
| `on(event, handler)` | `() => void` | Subscribe; returns an unsubscribe function. |
| `dispose()` | `void` | Tear down listeners and reject pending requests. |

The supporting types:

```ts
type Theme = 'dark' | 'light'
type FileAccess = 'read' | 'write' | 'read-write'

interface FileRef { id: string; name: string; kind: 'file'|'directory'; access: FileAccess; size: number }
interface FileFilter { name: string; extensions: string[] }
interface Environment { protocolVersion: string; theme: Theme; locale: string; platform: 'web'|'desktop'; capabilities: HostMethod[] }
```

## Minimal `ui/index.html`

The scaffolder produces something close to this — an entry HTML with no inline script that hands off to `app.js`:

```html
<!doctype html>
<html>
  <body>
    <h1>FengYu Plugin</h1>
    <button id="hello">Call host</button>
    <pre id="out"></pre>
    <script type="module" src="app.js"></script>
  </body>
</html>
```

And the matching `ui/app.js`:

```js
import { fengyu } from './sdk.js'

await fengyu.ready()  // negotiate before any other call

document.querySelector('#hello').onclick = async () => {
  const result = await fengyu.invoke('hello', {})
  document.querySelector('#out').textContent = JSON.stringify(result, null, 2)
}
```

## Next steps

- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` Vuetify kit the scaffolded app uses.
- [File I/O](/en/plugins/file-io) — what each `files.*` method authorizes.
- [SDK & CLI](/en/plugins/sdk-cli) — full TypeScript + Java SDK reference.
- [Pitfalls](/en/plugins/pitfalls) — CSP, MF Vue dedupe, and FileRef timing.
