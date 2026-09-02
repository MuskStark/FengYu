---
title: Built-in Browser Capability
description: Browser automation in Infinia 4.0.0 is a host-embedded capability, not a plugin — built into the desktop app, driven by Electron's native webContents and CDP over a loopback HTTP bridge. Isolated contexts, stateful tabs, multimodal screenshots, no Playwright. Desktop-only. Twenty-five effect-classified AI tools.
lang: en
---

# Built-in Browser Capability

Browser automation in Infinia is a **host-embedded capability**: it is built into the desktop
application and exposed by the backend `BrowserTool`, **not** a `.fyp` plugin. An Agent flow can
drive live web tabs end to end through 25 AI tools — navigate and traverse history, manage isolated
contexts, discover stable refs, inspect, click, hover, scroll, type, select options, press keys,
screenshot, batch, manage tabs, eval JS, and close. Inspection tools are classified as
reads; navigation, interaction, JavaScript evaluation, and closing are external effects governed
by the active approval profile.

::: tip What changed
The former official plugin `plugin-browser` (`fan.summer.browser`, Playwright-based) has been
**removed**. Browser automation is now host-embedded: it reuses the Electron shell's native
webContents and the Chrome DevTools Protocol (CDP) over a loopback HTTP bridge. There is **no
Playwright dependency** and **no separate Chromium download**.
:::

## How it works

- **Host-embedded, not a plugin.** The capability is provided by the backend `BrowserTool` (a
  Spring AI `ToolCallback`), which talks to the desktop shell — there is no `manifest.json`, no
  out-of-process worker, and no `.fyp` package.
- **Electron's native engine.** A real browser window is driven through Electron's native
  `webContents` plus CDP over a loopback HTTP bridge. No bundled Playwright and no separate
  Chromium binary are downloaded or launched.
- **Session and tab state.** The Java `BrowserSession` sends one logical session/context/tab id on
  every call and caches the latest URL, title, and `ref → element` identifiers per tab. Electron
  routes those ids to isolated windows that share cookies only within the same browser context.
  An unknown or stale ref fails on the Java side instead of silently targeting another element.
- **Pixels reach vision models.** Screenshots return base64 PNG bytes through the bridge. The
  backend removes those bytes from the textual tool envelope, preserves compact attachment
  metadata, and appends a Spring AI `Media(image/png)` part for the next model round. The DOM
  snapshot and accessibility tree remain available as a fallback for text-only models. PNGs up to
  20 MiB are inlined; larger captures remain at `imagePath` and use the text fallback.
- **Asynchronous bridge transport.** Java uses `HttpClient.sendAsync` and virtual-thread response
  processing, joining only at Spring AI's synchronous tool callback boundary. Electron serializes
  actions within the bridge so pointer/focus-sensitive operations cannot overlap.
- **Desktop-only.** This capability requires the Electron desktop shell. It is **unavailable in
  pure-web / headless mode** (a browser tab cannot drive another browser), so the `browser_*`
  tools are not registered when running without the desktop shell.
- **Effect-classified approval.** `find`, `snapshot`, tab listing, text/query inspection,
  screenshots, and waits are `read`; navigation/history, tab mutation, batch actions,
  click/hover/scroll/type/select/press, eval JS, and close are `external`. Ordinary chat and the
  Plan-and-Execute Agent use the same approval policy.

## The 25 AI tools

`BrowserTool` registers 25 AI tools. Each maps to a host-side browser operation — there is
no plugin worker and no separate UI pipeline; the AI surface *is* the entire contract.

| Tool | Purpose |
| --- | --- |
| `browser_navigate` | Open a URL; return the final URL and page title. Optional `waitUntil` (`load` \| `domcontentloaded` \| `networkidle`). |
| `browser_history` | Move back/forward in the active tab or reload it; stale refs are invalidated after success. |
| `browser_find` | Resolve a CSS selector to a stable ref for later calls. |
| `browser_snapshot` | Return visible structure and interactive elements with stable refs. |
| `browser_contexts` | List isolated contexts and their active tabs; contexts do not share cookies/local storage. |
| `browser_new_context` | Create and select a fresh isolated context. |
| `browser_select_context` | Switch to a context and restore its active tab/ref cache. |
| `browser_close_context` | Close every tab in one context and select another context. |
| `browser_tabs` | List tabs in the current context with id, URL, title, and active state. |
| `browser_new_tab` | Open and select a tab, optionally navigating it immediately. |
| `browser_select_tab` | Switch to an existing tab and restore that tab's cached refs/state. |
| `browser_close_tab` | Close one tab and select another remaining tab. |
| `browser_click` | Click an element matched by a CSS selector. |
| `browser_hover` | Hover a visible, stable element with a real CDP pointer event. |
| `browser_scroll` | Send a bounded CDP wheel event to the page or a selector/ref target, including nested scrollers. |
| `browser_type` | Clear-and-fill text into a selector (clears first by default). |
| `browser_press` | Send a key or shortcut to a selector/ref target or to the active page. |
| `browser_select` | Select a native `<select>` option by exact value/label and verify that it persisted. |
| `browser_get_text` | Read text of a selector (whole page if omitted), truncated to 64K. |
| `browser_query` | Count matches of a selector and return up to 5 sample innerTexts. |
| `browser_screenshot` | Capture viewport / full page / element to a PNG; attach its pixels to vision-capable models and return path, dimensions, DOM snapshot, and accessibility text. |
| `browser_wait_for` | Wait for an element to reach `attached` / `detached` / `visible` / `hidden`. |
| `browser_batch` | Capture one snapshot and immediately click, type, or press in the same serialized bridge request. |
| `browser_eval_js` | Evaluate a JS expression in the page and return the serialized result. |
| `browser_close` | Close the browser window and release resources; the next `browser_*` call reopens it. |

Every tool returns the standard `{ success, summary, ... }` envelope. See [AI Tools](/en/plugins/ai-tools).

> **Vision is capability-dependent.** A vision-capable provider receives the PNG as an image part.
> Text-only models still receive the actionable DOM snapshot and accessibility tree, so screenshot
> calls remain useful without multimodal support.

## Why not a plugin

Driving a real browser window requires capabilities that only the desktop shell has (native
`webContents`, CDP access, window lifecycle). A sandboxed plugin worker cannot reach the shell, so
the previous Playwright-based plugin shipped its own Chromium download and an extra process tree.
Embedding the capability in the host removes that download, the Playwright dependency, and the
worker lifecycle, while keeping the same browser AI surface.

## Availability

| Target | Browser capability |
| --- | --- |
| Desktop (Electron shell) | Available — 25 AI tools registered. |
| Web / headless (no Electron shell) | **Unavailable** — the `browser_*` tools are not registered. |

## Next steps

- [AI Tools](/en/plugins/ai-tools) — how built-in and plugin tools are aggregated into Spring AI `ToolCallback[]`.
- [Desktop architecture](/en/architecture/desktop) — the Electron shell that provides `webContents` + CDP.
- [Plugin Overview](/en/plugins/overview) — the shipped official plugins (browser automation is not among them).
