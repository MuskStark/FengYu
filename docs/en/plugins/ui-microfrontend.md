---
title: UI Micro-frontend
description: The plugin UI runs in a sandboxed iframe under a strict Content Security Policy and bridges to the host via the @infinia/plugin-sdk FengYuClient postMessage API — ready, invoke, files, notify, on.
lang: en
---

# UI Micro-frontend

The plugin's `ui/` directory is a self-contained micro-frontend (MF) that the host serves as static assets under `/plugin-runtime/{id}/**` and loads into a **sandboxed iframe** under a strict Content Security Policy. Inside the iframe, the `@infinia/plugin-sdk` package provides a `FengYuClient` that bridges to the host over `postMessage`. The plugin never talks to the OS directly — every privileged action goes through the client.

## The default entry: a Vue/Vuetify app

Since 4.0, `fengyu plugin create` scaffolds a Vue 3 + Vuetify app that builds into `ui/`. The generated `src/main.ts` is the whole bootstrap — it creates the Vuetify instance, binds the host theme/locale, provides the SDK client, and mounts the app:

```ts
import { createApp } from 'vue'
import { fengyu } from '@infinia/plugin-sdk'
import { bindFengYuEnvironment, createFengYuVuetify, provideFengYuClient } from '@infinia/plugin-ui'
import '@infinia/plugin-ui/style.css'
import App from './App.vue'

if (!fengyu) throw new Error('FengYu SDK requires a browser environment')
// Bind to a local so the narrowed (non-undefined) type survives the
// top-level `await` below — TS widens imported `const` bindings across await.
const client = fengyu
const vuetify = createFengYuVuetify()
const disposeEnvironment = await bindFengYuEnvironment(vuetify, client)
const app = createApp(App)
provideFengYuClient(app, client)
app.use(vuetify)
app.mount('#app')
window.addEventListener('pagehide', () => { disposeEnvironment(); client.dispose() }, { once: true })
```

`bindFengYuEnvironment` calls `fengyu.ready()` once and then subscribes to `environment` events, so theme and locale propagate from the host into Vuetify automatically. Inside your components, call `useFengYuClient()` to get the `FengYuClient` instead of importing the `fengyu` singleton directly. The full component kit is documented on the [UI Components](/en/plugins/ui-components) page.

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

// 1. Negotiate the SDK version and receive the host Environment.
const env = await fengyu.ready()
//   → { sdkVersion, theme:'dark'|'light', locale, platform?, capabilities? }
//   Throws on major-version mismatch between plugin SDK and host.

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
| `ready()` | `Promise<Environment>` | Sends `sdkVersion`; throws on major-version mismatch. Applies `theme` + `locale` to the document. |
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
interface Environment { sdkVersion?: string; theme: Theme; locale: string; platform?: 'web'|'desktop'; capabilities?: string[] }
```

## Shared-host integration: `default.mount(el, ctx)`

In addition to the iframe path, the host's MF loader can load a plugin ESM bundle directly and call its default export:

```ts
// What the plugin bundle default-exports for shared-host mounting.
export default {
  mount(el: HTMLElement, ctx: PluginContext): () => void { /* ... */ return () => {} }
}
```

The `PluginContext` the host passes gives the MF access to shared infrastructure so it does not have to bundle its own:

| Field | Purpose |
| --- | --- |
| `ctx.vuetify` | The host's Vuetify (MD3) instance — call `app.use(ctx.vuetify)` so the plugin shares theme + components. **Do not bundle Vuetify.** |
| `ctx.theme`, `ctx.onThemeChange(cb)` | Current theme + change subscription. |
| `ctx.locale`, `ctx.t(key)`, `ctx.onLocaleChange(cb)` | Host locale, a translator, and a change subscription. Plugins must **not** ship a language switcher. |
| `ctx.api.invoke(action, args?)` | Convenience RPC to the worker. |
| `ctx.notify(msg)` | Show a host toast. |
| `ctx.apiBase`, `ctx.token` | Backend base URL and `X-FengYu-Token` for raw `fetch`. |
| `ctx.desktop?` | Native Electron file dialogs (`pickFile`, `pickDirectory`) — present **only** under the Electron desktop shell, `undefined` in the browser. |

See [i18n](/en/plugins/i18n) for the locale contract and [Pitfalls](/en/plugins/pitfalls) for the Vue/Vuetify dedupe rule.

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
