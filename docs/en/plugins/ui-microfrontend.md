---
title: UI Micro-frontend
description: The plugin UI runs in a sandboxed iframe under a strict Content Security Policy and bridges to the host via the @infinia/plugin-sdk FengYuClient postMessage API — ready, invoke, files, notify, on.
lang: en
---

# UI Micro-frontend

The plugin's `ui/` directory is a self-contained micro-frontend (MF) that the host serves as static assets under `/plugin-runtime/{id}/**` and loads into a **sandboxed iframe** under a strict Content Security Policy. Inside the iframe, the `@infinia/plugin-sdk` package provides a `FengYuClient` that bridges to the host over `postMessage`. The plugin never talks to the OS directly — every privileged action goes through the client.

## The default entry: a React app

`fengyu init` scaffolds a React app (the `react-java` / `react-python` / `react-go` templates) that builds into `ui/`. The generated `src/main.tsx` delegates the complete bootstrap and teardown lifecycle to the shared UI kit:

```tsx
import { fengyu } from '@infinia/plugin-sdk'
import { mountFengYuApp } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the
// top-level `await` below — TS widens imported `const` bindings across await.
const client = fengyu
await mountFengYuApp({ root: App, client })
```

`mountFengYuApp` owns the complete UI lifecycle: it binds the host environment (theme class and locale), wraps your root in `FengYuClientProvider` + `NotifyProvider` (plus `FengYuI18nProvider` when `messages` is passed), creates the React root, and unmounts/unsubscribes/disposes on `pagehide`. Pass `messages` for i18n tables, `wrap` for plugin-specific providers, and `onEnvironment` to react to updates beyond theme/locale. Inside components, call `useFengYuClient()` instead of importing the singleton directly. The full component kit is documented on the [UI Components](/en/plugins/ui-components) page.

Legacy static plugins — a hand-written `ui/index.html` + `ui/app.js` that `import { fengyu } from './sdk.js'` — are still accepted. The rest of this page describes the SDK API that **both** entry styles share.

## Sandboxed iframe + CSP

The host loads the entry HTML and applies a Content Security Policy that restricts what the MF may do. Consequences you will hit:

- **No inline scripts.** All JS must come from external `<script src>` files (the scaffolder writes `<script type="module" src="app.js">`). See [Pitfalls](/en/plugins/pitfalls).
- **No direct `fetch` to arbitrary origins.** Talk to the backend through `FengYuClient`, or use the `apiBase` + `token` provided in `PluginContext` for raw multipart calls.
- **Fonts must be packaged with the plugin.** `font-src 'self' data:` supports both current
  same-origin font assets and fonts embedded by older toolchain releases; remote font origins remain
  blocked. The React kit ships SVG icon components (`@tabler/icons-react`), so it loads no icon font.
- Asset paths under `/plugin-runtime/{id}/**` are the only plugin URLs that bypass the token filter, so the UI can bootstrap without a credential.

## The `FengYuClient` API

`FengYuClient` (exported as the `fengyu` singleton) is a `postMessage` bridge to the host. Every method returns a `Promise` correlated by id; requests time out after 30s by default.

```ts
import { fengyu } from './sdk.js'

// 1. Negotiate protocol 4.0.0 and receive the host Environment.
const env = await fengyu.ready()
//   → { protocolVersion, pluginId, pluginVersion, permissions, theme, locale,
//       platform, capabilities }
//   Throws unless the host speaks the exact protocol version.

// 2. Call the plugin's own worker (host forwards as JSON-RPC). A React plugin
//    normally wraps this with the generated typed client:
//      const rpc = createPluginRpc(fengyu); await rpc.render({ markdown: '# hi' })
const out = await fengyu.invoke('render', { markdown: '# hi' })

// 3. Ask the user for files / directories.
const file: FileRef | null    = await fengyu.files.open({ extensions: ['xlsx'] })
const inDir: FileRef | null   = await fengyu.files.inputDirectory()
const outDir: FileRef | null  = await fengyu.files.outputDirectory()
const exported: boolean       = await fengyu.files.export(outDir)  // zip + download

// 4. Surface a host notification (unified toast + native OS notification + history).
await fengyu.notify('Split complete')

// 5. Subscribe to host events. Returns an unsubscribe function.
const off = fengyu.on('environment', (e) => applyTheme(e.theme))
// ...later
off()
```

### Method reference

| Method | Returns | Notes |
| --- | --- | --- |
| `ready(options?)` | `Promise<Environment>` | Negotiates the protocol window (`4.0.0`; the wire-identical legacy `3.0.0` is accepted — hosts answer each plugin in its own version); concurrent calls share one handshake. Applies and caches `theme` + `locale`. Fails fast with `INCOMPATIBLE_PROTOCOL` outside the window. |
| `currentEnvironment()` | `Environment \| undefined` | Latest merged ready/event state without another host round-trip. |
| `invoke(method, params?, options?)` | `Promise<T>` | RPC to the plugin worker. `options:{signal?, timeoutMs?}`. |
| `notify(message)` | `Promise<boolean>` | Creates a unified host notification (in-app toast, native OS notification when the window is hidden, and the persisted notification center) — no manifest permission required. Resolves `false` only when host delivery fails; the React kit's `useFengYuNotify` mirrors such rejected messages into its local fallback queue. |
| `files.open({extensions?, filters?}, req?)` | `Promise<FileRef \| null>` | Open a single file. `null` if the user cancels. Perm `files.read`. |
| `files.inputDirectory(req?)` | `Promise<FileRef \| null>` | Pick an input directory (read grant). Perm `files.read`. |
| `files.workspaceDirectory(req?)` | `Promise<FileRef \| null>` | Pick a read-write working directory (native path grant, or a browser folder upload with `access=read-write`). Perm `files.read` + `files.write`. |
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
interface Environment { protocolVersion: string; pluginId: string; pluginVersion: string; permissions: string[]; theme: Theme; locale: string; platform: 'web'|'desktop'; capabilities: HostMethod[] }
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

- [UI Components](/en/plugins/ui-components) — the `@infinia/plugin-ui` React kit the scaffolded app uses.
- [File I/O](/en/plugins/file-io) — what each `files.*` method authorizes.
- [SDK & CLI](/en/plugins/sdk-cli) — full TypeScript + Java SDK reference.
- [Pitfalls](/en/plugins/pitfalls) — CSP, handshake races, and FileRef timing.
