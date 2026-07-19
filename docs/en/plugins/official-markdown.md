---
title: Official Plugin — Markdown
description: Walkthrough of fan.summer.markdown (v4.0.0-alpha.1) — a text-category plugin with no permissions and no AI tools, exposing a single render method backed by MarkdownWorkerMain, and a Vuetify split-pane editor UI with live preview.
lang: en
---

# Official Plugin — Markdown

`fan.summer.markdown` is the simpler of the two shipped official plugins. It is a split-pane Markdown editor whose only backend capability is server-side rendering to HTML via commonmark. It declares no permissions and no AI tools — a minimal, canonical example of the plugin contract.

## What it does

- Renders a Vuetify split-pane editor: Markdown source on the left, live HTML preview on the right.
- On every edit, the UI calls the worker's `render` method, which parses the Markdown server-side with commonmark and returns HTML.
- The rendering happens **out-of-process** in the worker — the host never parses Markdown itself.

## The manifest

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0-alpha.1",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0" },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": []
}
```

Key points:

- **`category: "text"`** — a text editing / rendering plugin.
- **`permissions: []`** — it never touches the filesystem; no file I/O grants.
- **`aiTools: []`** — `supportsAi` is `false`; it is a pure UI tool, not callable from chat.
- **`backend.command: "java -jar backend/worker.jar"`** with **`protocol: "json-rpc-2.0"`** — the host spawns the shaded jar and drives it over JSON-RPC on stdio.

See [Manifest](/en/plugins/manifest) for every field.

## The `render` action

`MarkdownWorkerMain` registers a **single** JSON-RPC method: `render`.

```java
new JsonRpcWorker().on("render", params -> plugin.invoke("render", params)).run()
```

The UI invokes it on every keystroke (debounced):

```js
const { html } = await fengyu.invoke('render', { markdown: source })
```

| Method | Params | Returns |
| --- | --- | --- |
| `render` | `{ markdown: string }` | `{ success: true, html: string }` — commonmark-rendered HTML |

Because there is no file I/O and no AI surface, this is the entire backend contract: one method in, one HTML string out. See [Worker (JSON-RPC)](/en/plugins/worker) for the protocol.

## The UI

The UI is a Vuetify split-pane MF:

- **Left pane** — a Markdown source editor (`<v-textarea>` or equivalent).
- **Right pane** — the live HTML preview, repainted from each `render` result.
- Built with the host's Vuetify instance (MD3) via the shared-host `PluginContext.vuetify` — it does **not** bundle its own copy. See [Pitfalls](/en/plugins/pitfalls) for the Vue/Vuetify dedupe rule.

It loads in the sandboxed iframe under `/plugin-runtime/fan.summer.markdown/**` and bridges to the host through `@infinia/plugin-sdk`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema reference.
- [Worker (JSON-RPC)](/en/plugins/worker) — the `render` registration pattern.
- [Official Plugin — Excel](/en/plugins/official-excel) — the more complex sibling with permissions, file I/O, and six AI tools.
