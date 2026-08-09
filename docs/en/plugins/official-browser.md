---
title: Official Plugin — Browser Agent
description: Walkthrough of fan.summer.browser (v4.0.0-beta.1) — an automation-category plugin with network/files.write permissions and nine aiTools that drive a real Chromium via Playwright (Java) — navigate, click, type, scrape, screenshot, eval JS. Headed by default, Chromium auto-downloaded to the plugin's data dir on first use.
lang: en
---

# Official Plugin — Browser Agent

`fan.summer.browser` is the official browser-automation plugin. It launches a real Chromium and exposes nine AI tools — navigate, click, type, read text, query elements, screenshot, wait, eval JS, close — so an Agent flow can drive a live web page end to end. It is the canonical example of a plugin that combines an **external-effect** engine (a browser), the **network** permission, and an out-of-process Playwright worker.

## What it does

- Launches a persistent Chromium (Playwright, Java) in its own process tree and drives one browsing session across sequential tool calls.
- Lets the AI navigate, click, type, read and query DOM, take screenshots, wait for state, and evaluate arbitrary JavaScript.
- Keeps a real user profile (cookies, login state) under the plugin's data dir so the session survives worker restarts.
- Auto-downloads Chromium into the plugin's data dir on first use, or reuses a user-configured system Chrome/Edge.
- Runs **headed by default** so a human can watch the AI drive a visible browser.

## The manifest

```json
{
  "schemaVersion": 1,
  "id": "fan.summer.browser",
  "name": "Browser Agent",
  "description": "AI-driven browser automation: navigate, click, type, scrape, screenshot, eval JS",
  "version": "4.0.0-beta.1",
  "author": "FengYu",
  "icon": "browser",
  "category": "automation",
  "ui": { "entry": "ui/index.html" },
  "backend": { "command": "java -jar backend/worker.jar", "protocol": "json-rpc-2.0", "callTimeoutSeconds": 120 },
  "permissions": ["network", "files.write"],
  "homepage": "https://github.com/MuskStark/FengYu",
  "official": true,
  "aiTools": [ /* nine tools — see below */ ]
}
```

Key points:

- **`category: "automation"`** — an automation / drive-a-real-app plugin.
- **`permissions: ["network", "files.write"]`** — `network` is required because every tool drives a live browser that opens URLs; `files.write` is required because `browser_screenshot` saves PNGs into the plugin's data dir.
- **`backend.command: "java -jar backend/worker.jar"`** with **`protocol: "json-rpc-2.0"`** and **`callTimeoutSeconds: 120`** — the worker is allowed a longer single-RPC budget than the default because navigation/loading can be slow.
- **`aiTools`** has nine entries, so `supportsAi` is `true`. Each `{name, method, effect: "external", ...}` maps a model-facing tool to a worker JSON-RPC method; every tool is marked `external` because it mutates the outside world.

See [Manifest](/en/plugins/manifest) for every field.

## The nine AI tools

The worker (`BrowserWorkerMain`) registers nine JSON-RPC methods. Each maps 1:1 to an `aiTools` entry — there are no extra UI-only actions; the AI surface *is* the entire backend contract.

| Tool | Purpose |
| --- | --- |
| `browser_navigate` | Open a URL; return the final URL and page title. Optional `waitUntil` (`load` \| `domcontentloaded` \| `networkidle`). |
| `browser_click` | Click an element matched by a CSS selector. |
| `browser_type` | Clear-and-fill text into a selector (clears first by default). |
| `browser_get_text` | Read text of a selector (whole page if omitted), truncated to 64K. |
| `browser_query` | Count matches of a selector and return up to 5 sample innerTexts. |
| `browser_screenshot` | Capture viewport / full page / element to a PNG; returns the path, dimensions, and the page accessibility tree as text. |
| `browser_wait_for` | Wait for an element to reach `attached` / `detached` / `visible` / `hidden`. |
| `browser_eval_js` | Evaluate a JS expression in the page and return the serialized result. |
| `browser_close` | Close the browser and release resources; the next `browser_*` call relaunches. |

Every tool returns the standard `{ success, summary, ... }` envelope. The AI tools are the same methods the UI would call — there is no separate UI-side pipeline. See [AI Tools](/en/plugins/ai-tools) and [Worker (JSON-RPC)](/en/plugins/worker).

> **Screenshots are text, not pixels.** `browser_screenshot` saves the PNG to the data dir *and* attaches the page's accessibility tree as YAML text, because the model cannot see images — it reads the a11y tree to understand the page.

## The Playwright engine

The worker is a Playwright (Java) process. `BrowserSession` owns the full lifecycle:

- One `Playwright` instance (which spawns Playwright's bundled Node driver subprocess), one persistent `BrowserContext`, and one `Page` — lazily started on first tool call and reused so the AI's `navigate → click → type` sequence shares one browsing session.
- Launched via `launchPersistentContext(userDataDir, ...)` so the profile (cookies, login) survives worker restarts. Closing the context terminates the Chromium process tree; closing the `Playwright` instance terminates the bundled Node driver subprocess.
- `browser_close` and a JVM shutdown hook both reap the whole three-level tree (Chromium children → Chromium → Node driver), so a killed worker cannot leak browsers.

### Chromium resolution (three-tier)

`ChromiumResolver` picks the executable in priority order:

1. **User-configured path** (a system Chrome/Edge), if it is executable.
2. **Already-downloaded Chromium** under `<dataDir>/chromium/`.
3. **Auto-download** into `<dataDir>/chromium/` via `com.microsoft.playwright.CLI install chromium`.

If all three fail it falls back to Playwright's bundled/installed browser. The network-touching install lives behind a seam, so the resolution logic is unit-testable without a network.

### Configuration (environment variables)

| Variable | Default | Purpose |
| --- | --- | --- |
| `FENGYU_PLUGIN_DATA_DIR` | `~/.fengyu/plugins/fan.summer.browser` | Root data dir: profile, screenshots, downloaded Chromium. |
| `FENGYU_BROWSER_HEADLESS` | `false` | `true`/`false`; defaults to **headed** so a human can watch. |

## Windows: unsandboxed plugins

Chromium's native sandbox relies on OS features that the plugin worker cannot use under FengYu's sandbox on Windows. On Windows the plugin therefore requires the host's **`unsandboxedPlugins`** toggle (Settings → Runtime & Security); without it the worker cannot launch Chromium. On macOS/Linux the sandboxed worker runs as normal.

## The UI

The UI is a minimal micro-frontend (a landing/config panel) — the real capability is the nine AI tools, not a rich UI. It loads in the sandboxed iframe under `/plugin-runtime/fan.summer.browser/**` and bridges to the host through `@infinia/plugin-sdk`. See [UI Micro-frontend](/en/plugins/ui-microfrontend).

## Next steps

- [Manifest](/en/plugins/manifest) — the full schema, including `aiTools` and `permissions`.
- [AI Tools](/en/plugins/ai-tools) — how the nine `browser_*` tools are aggregated into Spring AI `ToolCallback[]`.
- [Worker (JSON-RPC)](/en/plugins/worker) — the `browser_*` registration pattern.
- [Official Plugin — Excel](/en/plugins/official-excel) — a sibling plugin with a wizard UI and file I/O.
