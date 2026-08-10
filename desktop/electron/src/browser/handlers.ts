import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { BrowserSession } from './session'
import { formatA11yTree, type CdpAxTree } from './a11y'

/** Attribute stamped on an element by browser_find so later ops target the exact node. */
export const REF_ATTR = 'data-fengyu-ref'

/**
 * Execute one browser_* operation against the session. Returns the envelope that the
 * backend BrowserTool forwards to the AI. Envelope keys mirror the former plugin-browser.
 *
 * Interaction model: {@link find}, {@link click}, {@link type} accept either a CSS
 * `selector` or a `ref` (from {@link find}); when both are given `ref` wins. `nth` selects
 * the Nth match (1-based) when a selector matches several. {@link click} and {@link type}
 * use real CDP input events (mouse press/release, insertText) — not JS-synthesised events —
 * so React/Vue controlled components and isTrusted-checked buttons behave as a human user.
 */
export async function handleBrowserOp(
  session: BrowserSession,
  method: string,
  params: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  try {
    switch (method) {
      case 'browser_navigate':
        return await navigate(session, str(params, 'url'), optStr(params, 'waitUntil'))
      case 'browser_find':
        return await find(session, str(params, 'selector'), optNum(params, 'nth', null))
      case 'browser_click':
        return await click(session, target(params))
      case 'browser_type':
        return await type(session, target(params), str(params, 'text'), params.clear !== false)
      case 'browser_get_text':
        return await getText(session, optTarget(params))
      case 'browser_query':
        return await query(session, str(params, 'selector'))
      case 'browser_screenshot':
        return await screenshot(session, params.fullPage === true, optTarget(params))
      case 'browser_wait_for':
        return await waitFor(session, target(params), optStr(params, 'state') ?? 'visible', num(params, 'timeout', 30))
      case 'browser_eval_js':
        return await evalJs(session, str(params, 'script'))
      case 'browser_close':
        session.close()
        return { success: true, summary: 'browser closed', closed: true }
      default:
        return { success: false, summary: 'unknown method: ' + method }
    }
  } catch (e) {
    return { success: false, summary: msg(e) }
  }
}

function requireWindow(session: BrowserSession): Electron.BrowserWindow {
  const w = session.window()
  if (!w) throw new Error('no browser session')
  return w
}

/** A resolved target descriptor: either a ref-backed selector or a plain selector (+nth). */
interface ResolvedTarget {
  /** CSS selector, the ref attribute selector, or null for "whole page" (get_text/screenshot). */
  selector: string | null
  /** Original ref id when the caller passed `ref`, surfaced in summaries for traceability. */
  ref: string | null
}

/**
 * Resolve {ref?, selector?, nth?} to a target descriptor. `ref` wins over `selector`.
 * `nth` (1-based) selects the Nth match when a selector matches several; ref lookups are
 * always unique so nth is nulled for them. Throws when neither is present.
 */
function target(params: Record<string, unknown>): ResolvedTarget & { nth: number | null } {
  const t = optTarget(params)
  if (t.selector == null && t.ref == null) {
    throw new Error("missing 'selector' or 'ref' parameter")
  }
  return t
}

/**
 * Like {@link target} but allows an empty target (no selector and no ref) — used by
 * get_text/screenshot where "whole page" is a valid argument.
 */
function optTarget(params: Record<string, unknown>): ResolvedTarget & { nth: number | null } {
  const ref = optStr(params, 'ref')
  if (ref) return { selector: refSelector(ref), ref, nth: null }
  const sel = optStr(params, 'selector')
  return { selector: sel, ref: null, nth: sel ? optNum(params, 'nth', null) : null }
}

function refSelector(ref: string): string {
  // Attribute selector with double-quoted value; ref ids are alphanumeric + underscore.
  return `[${REF_ATTR}="${ref.replace(/["\\]/g, '\\$&')}"]`
}

/** JS fragment (string) selecting the Nth match by index, or the first match if nth is null. */
function pickExpr(selector: string | null, nth: number | null): string {
  // querySelectorAll + positional pick — works for ANY selector (unlike :nth-of-type,
  // which only counts same-tag siblings). nth is 1-based; null/undefined means first.
  // A null selector resolves to null (callers gate on it before reaching here).
  if (!selector) return 'null'
  const idx = nth == null ? 0 : nth - 1
  return `(function(){ var all=document.querySelectorAll(${JSON.stringify(selector)}); return all[${idx}] || null; })()`
}

async function navigate(session: BrowserSession, url: string, waitUntil: string | null) {
  if (!/^https?:\/\//i.test(url)) return { success: false, summary: 'url must be absolute http(s)' }
  const win = session.ensureWindow()
  // loadURL() resolves on did-finish-load, which fires after DOMContentLoaded; so the
  // "load" (default) and "domcontentloaded" cases are both satisfied here (the distinction
  // is approximate in Electron — there is no separate dom-ready to await post-loadURL).
  await win.webContents.loadURL(url)
  if (waitUntil === 'networkidle') {
    // Electron has no exact networkidle equivalent; degrade to load + 500ms settle delay.
    await new Promise((r) => setTimeout(r, 500))
  }
  // Read the title from the live DOM rather than webContents.getTitle(), which may still
  // hold the previous page's title on redirects/slow pages before the renderer updates it.
  const title = String(await win.webContents.executeJavaScript('document.title') ?? '')
  return { success: true, summary: `navigated to ${url}`, url, title }
}

/**
 * Locate an element, stamp a `data-fengyu-ref` attribute on it, and return a stable ref id.
 * Later click/type/fill calls pass that ref instead of a selector, so a page re-render
 * between calls cannot retarget the operation to a different node.
 */
async function find(session: BrowserSession, selector: string, nth: number | null) {
  const w = requireWindow(session)
  const refId = session.nextRefId()
  // Match + pick logic runs in-page: returns a descriptive error envelope when 0 matches
  // or when a multi-match selector is used without nth, instead of throwing into the log.
  const res = await w.webContents.executeJavaScript(`(function(){
    var all = Array.from(document.querySelectorAll(${JSON.stringify(selector)}));
    if (all.length === 0) return { error: 'no element matches selector ' + ${JSON.stringify(selector)} };
    if (${JSON.stringify(nth)} === null && all.length > 1) return { error: 'selector matched ' + all.length + ' elements; pass nth (1-based) or refine the selector' };
    var idx = ${JSON.stringify(nth)} === null ? 0 : (${JSON.stringify(nth)} - 1);
    if (idx < 0 || idx >= all.length) return { error: 'nth=' + ${JSON.stringify(nth)} + ' out of range (matched ' + all.length + ')' };
    var el = all[idx];
    el.setAttribute(${JSON.stringify(REF_ATTR)}, ${JSON.stringify(refId)});
    var r = el.getBoundingClientRect();
    return {
      tag: el.tagName.toLowerCase(),
      role: el.getAttribute('role') || '',
      name: el.getAttribute('name') || '',
      id: el.id || '',
      type: el.getAttribute('type') || '',
      value: 'value' in el ? String(el.value) : '',
      placeholder: el.getAttribute('placeholder') || '',
      text: (el.innerText || '').slice(0, 200),
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
    };
  })()`)
  if (res && res.error) return { success: false, summary: res.error }
  return { success: true, summary: `found ${res.tag}`, ref: refId, ...res }
}

async function click(session: BrowserSession, t: ResolvedTarget & { nth: number | null }) {
  const w = requireWindow(session)
  // Scroll into view + read the element's centre point relative to the viewport.
  const notFoundTail = t.ref ? ` + ' for ref ' + ${JSON.stringify(t.ref)}` : ''
  const point = await w.webContents.executeJavaScript(`(function(){
    var el = ${pickExpr(t.selector, t.nth)};
    if (!el) throw new Error('element not found'${notFoundTail});
    el.scrollIntoView({ block: 'center', inline: 'center' });
    var r = el.getBoundingClientRect();
    return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  })()`)
  // Real mouse event sequence via CDP Input domain — equivalent to a human click, passes
  // isTrusted checks and triggers mousedown/mouseup/focus listeners (JS el.click() does not).
  const dbg = await session.cdp()
  const common = { x: point.x, y: point.y, button: 'left', buttons: 1 }
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', ...common, buttons: 0 })
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mousePressed', ...common, clickCount: 1 })
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common, clickCount: 1 })
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `clicked ${where}`, clicked: true }
}

async function type(session: BrowserSession, t: ResolvedTarget & { nth: number | null }, text: string, clear: boolean) {
  const w = requireWindow(session)
  // Focus the field and optionally clear it. We do NOT set el.value directly for the text
  // itself — that bypasses the framework's event pipeline. The actual text goes in via
  // CDP Input.insertText below, which fires a real input event React/Vue observe.
  const notFoundTail = t.ref ? ` + ' for ref ' + ${JSON.stringify(t.ref)}` : ''
  await w.webContents.executeJavaScript(`(function(){
    var el = ${pickExpr(t.selector, t.nth)};
    if (!el) throw new Error('element not found'${notFoundTail});
    el.focus();
    ${clear ? "if ('value' in el) { el.value = ''; el.dispatchEvent(new Event('input', { bubbles: true })); }" : ''}
  })()`)
  // CDP Input.insertText inserts text at the caret through the browser's real text-edit
  // pipeline (same path as paste / IME), so React/Vue controlled components observe a
  // proper input event and update their state — direct el.value assignment does not.
  const dbg = await session.cdp()
  await dbg.sendCommand('Input.insertText', { text })
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `typed into ${where}`, filled: true }
}

async function getText(session: BrowserSession, t: ResolvedTarget & { nth: number | null }) {
  const w = requireWindow(session)
  // No selector/ref → whole-page body text. Otherwise pick the Nth match.
  const expr = t.selector ? `(${pickExpr(t.selector, t.nth)}?.innerText) ?? ''` : `document.body.innerText`
  const text = String(await w.webContents.executeJavaScript(expr) ?? '')
  return { success: true, summary: 'read text', text, length: text.length }
}

async function query(session: BrowserSession, selector: string) {
  const w = requireWindow(session)
  const res = await w.webContents.executeJavaScript(
    `(() => { const els = Array.from(document.querySelectorAll(${JSON.stringify(selector)})); return { count: els.length, samples: els.slice(0,5).map(e => e.innerText) }; })()`,
  )
  return { success: true, summary: `matched ${res.count} element(s)`, count: res.count, samples: res.samples }
}

async function screenshot(session: BrowserSession, fullPage: boolean, t: ResolvedTarget & { nth: number | null }) {
  const w = requireWindow(session)
  const rect = t.selector
    ? await w.webContents.executeJavaScript(`(function(){ var e = ${pickExpr(t.selector, t.nth)}; if (!e) throw new Error('element not found'); var r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; })()`)
    : undefined
  let img: Electron.NativeImage
  if (rect) {
    img = await w.webContents.capturePage(rect)
  } else if (fullPage) {
    const dims = await w.webContents.executeJavaScript(`({w: document.body.scrollWidth, h: document.body.scrollHeight})`)
    // fullPage approximated by resizing once; for MVP capture the current viewport.
    img = await w.webContents.capturePage()
    void dims
  } else {
    img = await w.webContents.capturePage()
  }
  const file = join(session.screenshotsDir(), `shot-${Date.now()}.png`)
  writeFileSync(file, img.toPNG())
  const size = img.getSize()
  const a11yTree = await captureA11y(session)
  return { success: true, summary: 'screenshot saved', imagePath: file, width: size.width, height: size.height, a11yTree }
}

async function captureA11y(session: BrowserSession): Promise<string> {
  try {
    const dbg = await session.cdp()
    const res = await dbg.sendCommand('Accessibility.getFullAXTree')
    return formatA11yTree(res as CdpAxTree)
  } catch {
    return ''
  }
}

async function waitFor(session: BrowserSession, t: ResolvedTarget & { nth: number | null }, state: string, timeoutSec: number) {
  const w = requireWindow(session)
  const deadline = Date.now() + Math.min(600, Math.max(1, timeoutSec)) * 1000
  const pred = buildPredicate(t.selector, t.nth, state)
  while (Date.now() < deadline) {
    const ok = await w.webContents.executeJavaScript(pred)
    if (ok) return { success: true, summary: 'wait satisfied', ok: true }
    await new Promise((r) => setTimeout(r, 200))
  }
  return { success: true, summary: 'wait timed out', ok: false }
}

function buildPredicate(selector: string | null, nth: number | null, state: string): string {
  const pick = pickExpr(selector, nth)
  // If selector is null pickExpr yields 'null' and these evaluate sensibly (never attached).
  switch (state) {
    case 'attached': return `!!${pick}`
    case 'detached': return `!${pick}`
    case 'hidden': return `(${pick} === null || ${pick}.offsetParent === null)`
    default: return `(${pick} !== null && ${pick}.offsetParent !== null)` // visible
  }
}

async function evalJs(session: BrowserSession, script: string) {
  const w = requireWindow(session)
  const value = await w.webContents.executeJavaScript(script)
  return { success: true, summary: 'eval ok', value: String(value) }
}

function str(p: Record<string, unknown>, k: string): string {
  const v = p[k]
  if (typeof v !== 'string' || !v) throw new Error(`missing required parameter: ${k}`)
  return v
}
function optStr(p: Record<string, unknown>, k: string): string | null {
  const v = p[k]
  return typeof v === 'string' && v ? v : null
}
function num(p: Record<string, unknown>, k: string, dflt: number): number {
  const v = p[k]
  return typeof v === 'number' && Number.isFinite(v) ? v : dflt
}
function optNum(p: Record<string, unknown>, k: string, dflt: number | null): number | null {
  const v = p[k]
  return typeof v === 'number' && Number.isFinite(v) ? v : dflt
}
function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
