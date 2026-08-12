---
title: Official Plugin — Markdown
description: Walkthrough of fan.summer.markdown (v4.0.0) — a text-category plugin with no permissions and no AI tools, exposing a single render method backed by MarkdownWorkerMain, and a Vuetify split-pane editor UI with live preview.
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
  "schemaVersion": 2,
  "id": "fan.summer.markdown",
  "name": "Markdown Editor",
  "description": "Split-pane Markdown editor with isolated server-side rendering",
  "version": "4.0.0",
  "author": "FengYu",
  "icon": "language-markdown",
  "category": "text",
  "ui": { "entry": "ui/index.html" },
  "backend": { "callTimeoutSeconds": 30 },
  "permissions": [],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "rpc": {
    "methods": {
      "render": {
        "description": "Render Markdown source to sanitized HTML via commonmark (server-side).",
        "inputSchema": {
          "type": "object",
          "properties": {
            "markdown": { "type": "string", "description": "The Markdown source to render." }
          },
          "required": ["markdown"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "success": { "type": "boolean" },
            "summary": { "type": "string" },
            "html": { "type": "string", "nullable": true, "description": "The rendered, sanitized HTML." }
          },
          "required": ["success", "summary"]
        }
      }
    }
  },
  "aiTools": []
}
```

Key points:

- **`category: "text"`** — a text editing / rendering plugin.
- **`permissions: []`** — it never touches the filesystem; no file I/O grants.
- **`aiTools: []`** — `supportsAi` is `false`; it is a pure UI tool, not callable from chat.
- **`backend.callTimeoutSeconds: 30`** — the host spawns the shaded jar and drives it over JSON-RPC on stdio.

See [Manifest](/en/plugins/manifest) for every field.

## The `render` action

`MarkdownWorkerMain` registers a **single** JSON-RPC method: `render`.

```java
new JsonRpcWorker().method(
        PluginMethods.RENDER, RenderInput.class, RenderOutput.class,
        (RenderInput input, RpcContext ctx) -> plugin.render(input, ctx)).run()
```

The UI invokes it on every keystroke (debounced):

```js
import { createPluginRpc } from './generated/fengyu-rpc'
const rpc = createPluginRpc(fengyu)
const { html } = await rpc.render({ markdown: source })
```

| Method | Params | Returns |
| --- | --- | --- |
| `render` | `{ markdown: string }` | `{ success: true, html: string }` — commonmark-rendered HTML |

Because there is no file I/O and no AI surface, this is the entire backend contract: one method in, one HTML string out. See [Worker (JSON-RPC)](/en/plugins/worker) for the protocol.

## The UI

The UI is a Vuetify split-pane MF:

- **Left pane** — a Markdown source editor (`<v-textarea>` or equivalent).
- **Right pane** — the live HTML preview, repainted from each `render` result.
- Built with its iframe-local `@infinia/plugin-ui` Vuetify instance (MD3), bound to host theme and locale changes through the SDK environment bridge.

It loads in the sandboxed iframe under `/plugin-runtime/fan.summer.markdown/**` and bridges to the host through `@infinia/plugin-sdk`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema reference.
- [Worker (JSON-RPC)](/en/plugins/worker) — the `render` registration pattern.
- [Official Plugin — Excel](/en/plugins/official-excel) — the more complex sibling with permissions, file I/O, and six AI tools.
