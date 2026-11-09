# MCP Apps (rendered UI for MCP server tools)

FengYu implements the host side of the [MCP Apps](https://github.com/modelcontextprotocol/ext-apps)
open standard (the `io.modelcontextprotocol/ui` extension): an MCP server tool can declare a
`ui://` HTML resource as its rendered interface. When the model calls such a tool, the tool
card in the chat timeline can host that page directly — no plugin, no extra install. Any
spec-conformant MCP server (and existing widgets built for the OpenAI Apps SDK aliases) gets
a rendered UI for free.

## Declaring UI on the server side

A tool names its resource in `tools/list` `_meta.ui`, with optional presentation and security
declarations:

```json
{
  "name": "paint",
  "inputSchema": { "type": "object" },
  "_meta": {
    "ui": {
      "resourceUri": "ui://paint/main.html",
      "visibility": ["model", "app"],
      "csp": { "connectDomains": ["wss://example.com"] },
      "prefersBorder": true
    }
  }
}
```

- **resourceUri**: points at a resource served by the same server — a single-file HTML
  document with mimeType `text/html;profile=mcp-app` (or plain `text/html`), capped at
  16 MiB. The deprecated flat key `_meta["ui/resourceUri"]` and OpenAI's
  `openai/outputTemplate` are accepted for compatibility.
- **visibility**: a combination of `model` (enters the model's tool table) and `app`
  (callable by the UI page only); both by default. An **app-only** tool joins the model's
  tool table exactly while a rendered page for its server is open (claiming semantics).
- **csp**: the four domain allowlists (`connectDomains` / `resourceDomains` /
  `frameDomains` / `baseUriDomains`). The `resources/list` entry's own `_meta.ui.csp`
  wins over the tool-level declaration. Only `https:`/`wss:` origins of any host and
  `http://127.0.0.1` loopback are accepted.
- **Sizes and permissions**: the resource-level `_meta` may also declare `heightHint` /
  `minFrameHeight` (initial/minimum card height), `showInline` (expand the result
  automatically), and the four browser permission keys (camera / microphone / geolocation /
  clipboardWrite — anything undeclared is denied).

## How the page is rendered

When a card expands, the backend fetches the HTML through that server's MCP connection,
registers it under a random 128-bit sandbox id, and serves it on a **dedicated sandbox
port** (a loopback port next to the app port — a distinct browser origin). The page ships
with a strict CSP response header (`default-src 'none'` plus declared domains) and the
iframe carries the `sandbox` attribute (no `allow-same-origin`), so the page runs under an
opaque origin — fully isolated from the host DOM, storage, and auth token. `/sandbox/*`
always answers 404 on the app port.

Page-to-host communication uses the official `@modelcontextprotocol/ext-apps` bridge. A
page can:

- read resources of its own server (8 MiB per read, MIME allowlist) and list resources/templates;
- call tools of its own server — **every call requires explicit confirmation on the card**;
- request external links to open (http/https only, validated by the host), request card
  height changes, and request its own teardown;
- download file contents;
- subscribe to resources of its own server: the page sends the standard
  `resources/subscribe`, the host registers it (reference-counted per server+uri), and
  server-pushed updates come back as the standard `notifications/resources/updated`
  notification (a page's own `ui://` interface resource is subscribed automatically);
- commit widget state: via `ui/set-widget-state` the page hands UI state to host memory
  (scoped per server::logical page, re-delivered on remount of the same scope through the
  `ui/notifications/widget-state` notification, empty after an app restart). This is a
  host extension keyed `experimental["fengyu/widgetState"]` for feature detection;
- receive the spec data notifications: after the handshake the host delivers
  `ui/notifications/tool-input` (the tool arguments) followed by
  `ui/notifications/tool-result` (the captured result), and
  `ui/notifications/tool-cancelled` when the owning tool call is interrupted;
- inject a user message into the conversation (`ui/message`) and park context for the
  model's next turn (`ui/update-model-context`) — declared only for cards hosted inside
  a conversation; panel/preview surfaces omit the capabilities so pages degrade;
- switch display modes (`ui/request-display-mode`): inline and fullscreen are applied as
  a live overlay (the page keeps running through the switch), `pip` resolves to inline;
- react to host changes: theme and locale switches reach live pages as
  `ui/notifications/host-context-changed`, and the initial host context carries theme,
  locale, time zone, platform, container dimensions, available display modes, and a
  FengYu-token-derived style variable set for pages that follow host styling;
- request model sampling once enabled in the configuration (off by default:
  `fengyu.mcp.ui.sampling=true` or env `FENGYU_MCP_UI_SAMPLING=1`, with hard caps on
  message count, per-message length, and max tokens). With `sampling.tools` the page may
  declare its own tools: a sample can end with `stopReason: "toolUse"` + `tool_use`
  blocks, the page executes them locally and re-samples with the appended
  `tool_use`/`tool_result` history — an in-app agent loop with the page driving.

## Known limitations

- Sandbox pages run under an opaque origin: no localStorage/IndexedDB or other persistent
  storage (storage-dependent pages degrade; use widget state for UI state that must
  survive remounts).
- Remote (non-loopback) deployments have no sandbox port; UI-capable tools fall back to
  plain result cards.
- Web/mobile and desktop behave identically; the `pip` display mode is answered as inline
  (fullscreen is applied as an in-place overlay).
- Host-declared style variables cover the subset of theme tokens that resolve to literal
  values; indirect tokens (computed color mixes) are omitted — every key is optional per
  the schema.
