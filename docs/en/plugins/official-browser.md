---
title: Built-in Browser Capability
description: Browser automation in Infinia 4.0.0 is a host-embedded capability, not a plugin — built into the desktop app, driven by Electron's native webContents and CDP over a loopback HTTP bridge. No Playwright, no separate Chromium download, not a plugin. Desktop-only. Nine approval-gated AI tools.
lang: en
---

# Built-in Browser Capability

Browser automation in Infinia is a **host-embedded capability**: it is built into the desktop
application and exposed by the backend `BrowserTool`, **not** a `.fyp` plugin. An Agent flow can
drive a live web page end to end through nine AI tools — navigate, click, type, read text, query
elements, screenshot, wait, eval JS, close — each gated by the host's approval flow for
external-effect actions.

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
- **Desktop-only.** This capability requires the Electron desktop shell. It is **unavailable in
  pure-web / headless mode** (a browser tab cannot drive another browser), so the `browser_*`
  tools are not registered when running without the desktop shell.
- **Approval-gated.** Every tool is an external-effect action; the host approval gate confirms
  each call before it runs, in both ordinary chat and the Plan-and-Execute Agent.

## The nine AI tools

`BrowserTool` registers nine AI tools. Each maps 1:1 to a host-side browser operation — there is
no plugin worker and no separate UI pipeline; the AI surface *is* the entire contract.

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
| `browser_close` | Close the browser window and release resources; the next `browser_*` call reopens it. |

Every tool returns the standard `{ success, summary, ... }` envelope. See [AI Tools](/en/plugins/ai-tools).

> **Screenshots are text, not pixels.** `browser_screenshot` saves the PNG *and* attaches the
> page's accessibility tree as YAML text, because the model cannot see images — it reads the a11y
> tree to understand the page.

## Why not a plugin

Driving a real browser window requires capabilities that only the desktop shell has (native
`webContents`, CDP access, window lifecycle). A sandboxed plugin worker cannot reach the shell, so
the previous Playwright-based plugin shipped its own Chromium download and an extra process tree.
Embedding the capability in the host removes that download, the Playwright dependency, and the
worker lifecycle, while keeping the same nine-tool AI surface.

## Availability

| Target | Browser capability |
| --- | --- |
| Desktop (Electron shell) | Available — nine AI tools registered. |
| Web / headless (no Electron shell) | **Unavailable** — the `browser_*` tools are not registered. |

## Next steps

- [AI Tools](/en/plugins/ai-tools) — how the nine `browser_*` tools are aggregated into Spring AI `ToolCallback[]`.
- [Desktop architecture](/en/architecture/desktop) — the Electron shell that provides `webContents` + CDP.
- [Plugin Overview](/en/plugins/overview) — the shipped official plugins (browser automation is not among them).
