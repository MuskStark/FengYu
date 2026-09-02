import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { nativeImage } from 'electron'
import type { BrowserSession } from './session'
import { formatA11yTree, type CdpAxTree } from './a11y'

/** Attribute stamped on an element by browser_find so later ops target the exact node. */
export const REF_ATTR = 'data-fengyu-ref'

/** Match locator-based browser tools: actions auto-wait briefly for a usable target. */
const ACTION_TIMEOUT_MS = 10_000
/** Keep the loopback JSON envelope bounded; larger PNGs remain available at imagePath. */
const MAX_INLINE_IMAGE_BYTES = 20 * 1024 * 1024

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
      case 'browser_history':
        return await history(session, str(params, 'action'))
      case 'browser_find':
        return await find(session, str(params, 'selector'), optNum(params, 'nth', null))
      case 'browser_snapshot':
        return await snapshot(session)
      case 'browser_click':
        return await click(session, target(params))
      case 'browser_hover':
        return await hover(session, target(params))
      case 'browser_scroll':
        return await scroll(session, optTarget(params), num(params, 'deltaX', 0), num(params, 'deltaY', 600))
      case 'browser_type':
        return await type(session, target(params), str(params, 'text'), params.clear !== false)
      case 'browser_press':
        return await press(session, optTarget(params), str(params, 'key'))
      case 'browser_select':
        return await select(session, target(params), str(params, 'option'))
      case 'browser_get_text':
        return await getText(session, optTarget(params))
      case 'browser_query':
        return await query(session, str(params, 'selector'))
      case 'browser_screenshot':
        return await screenshot(session, params.fullPage === true, optTarget(params))
      case 'browser_wait_for':
        return await waitFor(session, target(params), optStr(params, 'state') ?? 'visible', num(params, 'timeout', 30))
      case 'browser_batch':
        return await batch(session, params)
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
  const finalUrl = win.webContents.getURL()
  return { success: true, summary: `navigated to ${finalUrl}`, url: finalUrl, title }
}

async function history(session: BrowserSession, requestedAction: string) {
  const w = requireWindow(session)
  const action = requestedAction.toLowerCase()
  const nav = w.webContents.navigationHistory
  if (action === 'back') {
    if (!nav.canGoBack()) return { success: false, summary: 'no back history entry' }
    nav.goBack()
  } else if (action === 'forward') {
    if (!nav.canGoForward()) return { success: false, summary: 'no forward history entry' }
    nav.goForward()
  } else if (action === 'reload') {
    w.webContents.reload()
  } else {
    return { success: false, summary: "action must be 'back', 'forward', or 'reload'" }
  }
  await settleAfterAction(w)
  session.resetRefs()
  return { success: true, summary: `${action} completed`, action, ...await pageState(w) }
}

/**
 * Locate an element, stamp a `data-fengyu-ref` attribute on it, and return a stable ref id.
 * Later click/type/fill calls pass that ref instead of a selector, so a page re-render
 * between calls cannot retarget the operation to a different node.
 */
async function find(session: BrowserSession, selector: string, nth: number | null) {
  const w = requireWindow(session)
  const refId = session.nextRefId()
  // Auto-wait for dynamically rendered elements. Ambiguous selectors still fail strictly
  // instead of silently returning whichever match happened to render first.
  const res = await w.webContents.executeJavaScript(`(async function(){
    const deadline = Date.now() + ${ACTION_TIMEOUT_MS};
    var all = [];
    while (Date.now() < deadline) {
      all = Array.from(document.querySelectorAll(${JSON.stringify(selector)}));
      if (all.length > 0) break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    if (all.length === 0) return { error: 'timed out waiting for selector ' + ${JSON.stringify(selector)} };
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

/**
 * Return the model-facing equivalent of Codex's DOM snapshot: only rendered controls receive
 * stable refs, accompanied by their semantic role/name and a bounded visible-text excerpt.
 * This removes the need to guess CSS selectors or probe the page repeatedly with eval_js.
 */
async function snapshot(session: BrowserSession) {
  const w = requireWindow(session)
  const result = await w.webContents.executeJavaScript(`(function(){
    const refAttr = ${JSON.stringify(REF_ATTR)};
    const prefix = 'snap_' + Date.now().toString(36) + '_';
    let sequence = 0;
    const clean = (value, limit=180) => String(value || '').replace(/\\s+/g, ' ').trim().slice(0, limit);
    const visible = (el) => {
      if (!el || !el.isConnected) return false;
      const style = getComputedStyle(el);
      const rect = el.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) !== 0 && rect.width > 0 && rect.height > 0;
    };
    const roleOf = (el) => {
      const explicit = el.getAttribute('role');
      if (explicit) return explicit;
      const tag = el.tagName.toLowerCase();
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (tag === 'a' && el.hasAttribute('href')) return 'link';
      if (tag === 'button' || (tag === 'input' && ['button','submit','reset','image'].includes(type))) return 'button';
      if (tag === 'textarea' || el.isContentEditable) return 'textbox';
      if (tag === 'input' && type === 'search') return 'searchbox';
      if (tag === 'input' && type === 'checkbox') return 'checkbox';
      if (tag === 'input' && type === 'radio') return 'radio';
      if (tag === 'input' && type === 'range') return 'slider';
      if (tag === 'input') return 'textbox';
      if (tag === 'select') return el.multiple ? 'listbox' : 'combobox';
      if (tag === 'summary') return 'button';
      return 'control';
    };
    const nameOf = (el) => {
      const labelledBy = el.getAttribute('aria-labelledby');
      if (labelledBy) {
        const text = labelledBy.split(/\\s+/).map(id => document.getElementById(id)?.innerText || '').join(' ');
        if (clean(text)) return clean(text);
      }
      const aria = clean(el.getAttribute('aria-label'));
      if (aria) return aria;
      if (el.id) {
        try { const label = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (label && clean(label.innerText)) return clean(label.innerText); } catch {}
      }
      const wrappingLabel = el.closest('label');
      if (wrappingLabel && clean(wrappingLabel.innerText)) return clean(wrappingLabel.innerText);
      return clean(el.getAttribute('alt') || el.getAttribute('title') || el.getAttribute('placeholder') || el.innerText || (['button','submit','reset'].includes((el.type || '').toLowerCase()) ? el.value : ''));
    };
    const selector = 'a[href],button,input:not([type="hidden"]),textarea,select,summary,[role],[contenteditable="true"],[tabindex]:not([tabindex="-1"])';
    const controls = Array.from(document.querySelectorAll(selector)).filter(visible).slice(0, 300);
    const lines = controls.map((el) => {
      let ref = el.getAttribute(refAttr);
      if (!ref) { ref = prefix + (++sequence); el.setAttribute(refAttr, ref); }
      const role = roleOf(el);
      const name = nameOf(el);
      const attrs = [];
      const placeholder = clean(el.getAttribute('placeholder'), 100);
      if (placeholder && placeholder !== name) attrs.push('placeholder=' + JSON.stringify(placeholder));
      if ('value' in el && el.type !== 'password' && clean(el.value, 120)) attrs.push('value=' + JSON.stringify(clean(el.value, 120)));
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') attrs.push('disabled');
      if (el.readOnly || el.getAttribute('aria-readonly') === 'true') attrs.push('readonly');
      return '[' + ref + '] ' + role + (name ? ' ' + JSON.stringify(name) : '') + (attrs.length ? ' ' + attrs.join(' ') : '');
    });
    const bodyText = clean(document.body?.innerText || '', 12000);
    const header = 'URL: ' + location.href + '\\nTitle: ' + document.title;
    const controlsText = lines.length ? 'Interactive elements:\\n' + lines.join('\\n') : 'Interactive elements: none';
    return { url: location.href, title: document.title, count: lines.length, snapshot: header + '\\n' + controlsText + '\\nVisible text:\\n' + bodyText };
  })()`)
  return { success: true, summary: `captured ${result.count} interactive element(s)`, ...result }
}

async function click(session: BrowserSession, t: ResolvedTarget & { nth: number | null }) {
  const w = requireWindow(session)
  const point = await waitForActionable(session, t, 'click')
  // Real mouse event sequence via CDP Input domain — equivalent to a human click, passes
  // isTrusted checks and triggers mousedown/mouseup/focus listeners (JS el.click() does not).
  const dbg = await session.cdp()
  await dispatchClick(dbg, point)
  await settleAfterAction(w)
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `clicked ${where}`, clicked: true, ...await pageState(w) }
}

async function hover(session: BrowserSession, t: ResolvedTarget & { nth: number | null }) {
  const w = requireWindow(session)
  const point = await waitForActionable(session, t, 'hover')
  const dbg = await session.cdp()
  await dbg.sendCommand('Input.dispatchMouseEvent', {
    type: 'mouseMoved', x: point.x, y: point.y, button: 'none', buttons: 0,
  })
  await new Promise((resolve) => setTimeout(resolve, 100))
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `hovered ${where}`, hovered: true, point, ...await pageState(w) }
}

async function scroll(
  session: BrowserSession,
  t: ResolvedTarget & { nth: number | null },
  requestedDeltaX: number,
  requestedDeltaY: number,
) {
  const w = requireWindow(session)
  const deltaX = Math.max(-10_000, Math.min(10_000, Math.trunc(requestedDeltaX)))
  const deltaY = Math.max(-10_000, Math.min(10_000, Math.trunc(requestedDeltaY)))
  if (deltaX === 0 && deltaY === 0) {
    return { success: false, summary: 'deltaX and deltaY cannot both be zero' }
  }
  const point = t.selector
    ? await waitForActionable(session, t, 'hover')
    : await w.webContents.executeJavaScript('({ x: Math.round(innerWidth / 2), y: Math.round(innerHeight / 2) })')
  const dbg = await session.cdp()
  await dbg.sendCommand('Input.dispatchMouseEvent', {
    type: 'mouseMoved', x: point.x, y: point.y, button: 'none', buttons: 0,
  })
  await dbg.sendCommand('Input.dispatchMouseEvent', {
    type: 'mouseWheel', x: point.x, y: point.y, deltaX, deltaY,
  })
  await new Promise((resolve) => setTimeout(resolve, 100))
  return { success: true, summary: `scrolled by (${deltaX}, ${deltaY})`,
    scrolled: true, deltaX, deltaY, point, ...await pageState(w) }
}

async function type(session: BrowserSession, t: ResolvedTarget & { nth: number | null }, text: string, clear: boolean) {
  const w = requireWindow(session)
  const point = await waitForActionable(session, t, 'type')
  const dbg = await session.cdp()

  // Focus through a real pointer click. This catches overlays and focus-stealing handlers in
  // the same way a user sees them, instead of focusing an otherwise unreachable field via JS.
  await dispatchClick(dbg, point)
  await assertTargetFocused(w, t)

  if (clear) {
    // Select-all + Backspace goes through the browser's keyboard editing pipeline. Directly
    // assigning el.value='' desynchronises React's value tracker and is a common cause of text
    // reappearing on the next render.
    const modifier = process.platform === 'darwin' ? 4 : 2 // CDP Meta=4, Control=2
    await dispatchKey(dbg, { key: 'a', code: 'KeyA', keyCode: 65, modifiers: modifier })
    await dispatchKey(dbg, { key: 'Backspace', code: 'Backspace', keyCode: 8 })
  }
  // CDP Input.insertText inserts text at the caret through the browser's real text-edit
  // pipeline (same path as paste / IME), so React/Vue controlled components observe a
  // proper input event and update their state — direct el.value assignment does not.
  await dbg.sendCommand('Input.insertText', { text })

  // A framework may synchronously restore a rejected value. Verify clear-and-fill calls so
  // the model never receives a false positive and can recover with a different target.
  let value: string | undefined
  if (clear) {
    value = await readEditableValue(w, t)
    if (value !== text) {
      throw new Error(`typed text did not persist (expected ${text.length} chars, found ${value.length})`)
    }
  }
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `typed into ${where}`, filled: true, ...(value === undefined ? {} : { value }) }
}

async function press(session: BrowserSession, t: ResolvedTarget & { nth: number | null }, key: string) {
  const w = requireWindow(session)
  const dbg = await session.cdp()
  if (t.selector) {
    const point = await waitForActionable(session, t, 'press')
    await dispatchClick(dbg, point)
    await assertTargetFocused(w, t)
  }
  await dispatchNamedKey(dbg, key)
  await settleAfterAction(w)
  const where = t.ref ?? t.selector ?? 'the active page'
  return { success: true, summary: `pressed ${key} on ${where}`, pressed: true, ...await pageState(w) }
}

async function select(
  session: BrowserSession,
  t: ResolvedTarget & { nth: number | null },
  option: string,
) {
  const w = requireWindow(session)
  await waitForActionable(session, t, 'select')
  const result = await w.webContents.executeJavaScript(`(async function(){
    const el = ${pickExpr(t.selector, t.nth)};
    if (!(el instanceof HTMLSelectElement)) throw new Error('target is not a native select element');
    const options = Array.from(el.options);
    const wanted = ${JSON.stringify(option)};
    const match = options.find((item) => item.value === wanted)
      || options.find((item) => String(item.textContent || '').trim() === wanted);
    if (!match) throw new Error('option not found by exact value or label: ' + wanted);
    if (match.disabled) throw new Error('option is disabled: ' + wanted);
    if (!el.multiple) {
      for (const item of options) item.selected = item === match;
    } else {
      match.selected = true;
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    if (!el.isConnected) throw new Error('select element detached after change');
    if (!match.selected || (!el.multiple && el.value !== match.value)) {
      throw new Error('selected option did not persist');
    }
    return { value: el.value, label: String(match.textContent || '').trim(), index: match.index };
  })()`)
  const where = t.ref ?? t.selector ?? '(unknown)'
  return { success: true, summary: `selected ${JSON.stringify(result.label)} in ${where}`,
    selected: true, ...result, ...await pageState(w) }
}

interface ActionPoint {
  x: number
  y: number
}

/**
 * Wait for locator actionability using the same core contract as modern browser drivers:
 * attached, visible, enabled/editable, geometrically stable, and able to receive pointer
 * events. The hit-test uses several points within the visible intersection, which avoids a
 * small badge/icon obscuring only the exact centre of an otherwise clickable control.
 */
async function waitForActionable(
  session: BrowserSession,
  t: ResolvedTarget & { nth: number | null },
  action: 'click' | 'hover' | 'press' | 'select' | 'type',
): Promise<ActionPoint> {
  const w = requireWindow(session)
  const refLabel = t.ref ? ` for ref ${t.ref}` : ''
  const refLabelLiteral = JSON.stringify(refLabel)
  const selectorLiteral = JSON.stringify(t.selector)
  const nthLiteral = JSON.stringify(t.nth)
  return await w.webContents.executeJavaScript(`(async function(){
    const deadline = Date.now() + ${ACTION_TIMEOUT_MS};
    let reason = 'element not found' + ${refLabelLiteral};
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    const frame = () => new Promise((resolve) => requestAnimationFrame(resolve));
    const sameRect = (a, b) => a && Math.abs(a.x-b.x)<0.5 && Math.abs(a.y-b.y)<0.5 && Math.abs(a.width-b.width)<0.5 && Math.abs(a.height-b.height)<0.5;
    while (Date.now() < deadline) {
      const all = Array.from(document.querySelectorAll(${selectorLiteral}));
      if (${nthLiteral} === null && all.length > 1) {
        throw new Error('selector matched ' + all.length + ' elements; pass nth (1-based), a ref, or refine the selector');
      }
      const idx = ${nthLiteral} === null ? 0 : (${nthLiteral} - 1);
      const el = all[idx] || null;
      if (!el || !el.isConnected) { reason = 'element is detached' + ${refLabelLiteral}; await sleep(100); continue; }
      const style = getComputedStyle(el);
      const r0 = el.getBoundingClientRect();
      if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0 || r0.width <= 0 || r0.height <= 0) {
        reason = 'element is not visible' + ${refLabelLiteral}; await sleep(100); continue;
      }
      if (${JSON.stringify(action)} !== 'hover' && (el.disabled || el.getAttribute('aria-disabled') === 'true')) {
        reason = 'element is disabled' + ${refLabelLiteral}; await sleep(100); continue;
      }
      if (${JSON.stringify(action)} === 'type') {
        const tag = el.tagName.toLowerCase();
        const editable = tag === 'textarea' || (tag === 'input' && !['button','checkbox','color','file','hidden','image','radio','range','reset','submit'].includes((el.type || '').toLowerCase())) || el.isContentEditable;
        if (!editable) { reason = 'element is not editable' + ${refLabelLiteral}; await sleep(100); continue; }
        if (el.readOnly || el.getAttribute('aria-readonly') === 'true') { reason = 'element is read-only' + ${refLabelLiteral}; await sleep(100); continue; }
      }
      if (${JSON.stringify(action)} === 'select' && el.tagName.toLowerCase() !== 'select') {
        reason = 'element is not a native select' + ${refLabelLiteral}; await sleep(100); continue;
      }
      el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
      await frame(); await frame();
      if (!el.isConnected) { reason = 'element detached while scrolling' + ${refLabelLiteral}; continue; }
      const r1 = el.getBoundingClientRect();
      await frame();
      const r2 = el.getBoundingClientRect();
      if (!sameRect(r1, r2)) { reason = 'element is moving' + ${refLabelLiteral}; continue; }
      const left = Math.max(0, r2.left), top = Math.max(0, r2.top);
      const right = Math.min(innerWidth, r2.right), bottom = Math.min(innerHeight, r2.bottom);
      if (right <= left || bottom <= top) { reason = 'element is outside the viewport' + ${refLabelLiteral}; await sleep(100); continue; }
      const points = [
        [(left+right)/2, (top+bottom)/2],
        [left+(right-left)*0.25, top+(bottom-top)*0.5],
        [left+(right-left)*0.75, top+(bottom-top)*0.5],
        [left+(right-left)*0.5, top+(bottom-top)*0.25],
        [left+(right-left)*0.5, top+(bottom-top)*0.75],
      ];
      for (const [x, y] of points) {
        const hit = document.elementFromPoint(x, y);
        if (hit && (hit === el || el.contains(hit))) {
          return { x: Math.round(x), y: Math.round(y) };
        }
      }
      reason = 'element is covered by another element' + ${refLabelLiteral};
      await sleep(100);
    }
    throw new Error('element is not actionable: ' + reason);
  })()`)
}

async function dispatchClick(dbg: Electron.Debugger, point: ActionPoint): Promise<void> {
  const common = { x: point.x, y: point.y, button: 'left' as const }
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mouseMoved', ...common, buttons: 0 })
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mousePressed', ...common, buttons: 1, clickCount: 1 })
  await dbg.sendCommand('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common, buttons: 0, clickCount: 1 })
}

async function dispatchKey(
  dbg: Electron.Debugger,
  key: { key: string; code: string; keyCode: number; modifiers?: number; text?: string },
): Promise<void> {
  const params = {
    key: key.key,
    code: key.code,
    windowsVirtualKeyCode: key.keyCode,
    nativeVirtualKeyCode: key.keyCode,
    modifiers: key.modifiers ?? 0,
  }
  const textParams = key.text == null ? {} : { text: key.text, unmodifiedText: key.text }
  await dbg.sendCommand('Input.dispatchKeyEvent', {
    type: key.text == null ? 'rawKeyDown' : 'keyDown', ...params, ...textParams,
  })
  await dbg.sendCommand('Input.dispatchKeyEvent', { type: 'keyUp', ...params })
}

async function dispatchNamedKey(dbg: Electron.Debugger, shortcut: string): Promise<void> {
  const parts = shortcut.split('+').map((part) => part.trim()).filter(Boolean)
  const keyName = parts.pop()
  if (!keyName) throw new Error('missing key')
  let modifiers = 0
  for (const modifier of parts) {
    switch (modifier.toLowerCase()) {
      case 'alt': modifiers |= 1; break
      case 'control': case 'ctrl': modifiers |= 2; break
      case 'meta': case 'command': modifiers |= 4; break
      case 'shift': modifiers |= 8; break
      case 'controlormeta': modifiers |= process.platform === 'darwin' ? 4 : 2; break
      default: throw new Error(`unsupported modifier: ${modifier}`)
    }
  }
  const named: Record<string, { key: string; code: string; keyCode: number; text?: string }> = {
    enter: { key: 'Enter', code: 'Enter', keyCode: 13, text: '\r' },
    tab: { key: 'Tab', code: 'Tab', keyCode: 9 },
    escape: { key: 'Escape', code: 'Escape', keyCode: 27 },
    backspace: { key: 'Backspace', code: 'Backspace', keyCode: 8 },
    delete: { key: 'Delete', code: 'Delete', keyCode: 46 },
    space: { key: ' ', code: 'Space', keyCode: 32, text: ' ' },
    arrowup: { key: 'ArrowUp', code: 'ArrowUp', keyCode: 38 },
    arrowdown: { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40 },
    arrowleft: { key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37 },
    arrowright: { key: 'ArrowRight', code: 'ArrowRight', keyCode: 39 },
    home: { key: 'Home', code: 'Home', keyCode: 36 },
    end: { key: 'End', code: 'End', keyCode: 35 },
    pageup: { key: 'PageUp', code: 'PageUp', keyCode: 33 },
    pagedown: { key: 'PageDown', code: 'PageDown', keyCode: 34 },
  }
  const lower = keyName.toLowerCase()
  const descriptor = named[lower] ?? (/^[a-z0-9]$/i.test(keyName)
    ? { key: keyName, code: /^[a-z]$/i.test(keyName) ? `Key${keyName.toUpperCase()}` : `Digit${keyName}`, keyCode: keyName.toUpperCase().charCodeAt(0), ...(modifiers === 0 ? { text: keyName } : {}) }
    : null)
  if (!descriptor) throw new Error(`unsupported key: ${keyName}`)
  await dispatchKey(dbg, { ...descriptor, modifiers })
}

/** Wait for a navigation started by click/Enter, while keeping non-navigation actions fast. */
async function settleAfterAction(w: Electron.BrowserWindow): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 100))
  if (!w.webContents.isLoading()) return
  await Promise.race([
    new Promise<void>((resolve) => w.webContents.once('did-stop-loading', () => resolve())),
    new Promise<void>((resolve) => setTimeout(resolve, 10_000)),
  ])
}

async function pageState(w: Electron.BrowserWindow): Promise<{ url: string; title: string }> {
  const url = w.webContents.getURL()
  try {
    const title = String(await w.webContents.executeJavaScript('document.title') ?? '')
    return { url, title }
  } catch {
    return { url, title: w.webContents.getTitle() }
  }
}

async function readEditableValue(
  w: Electron.BrowserWindow,
  t: ResolvedTarget & { nth: number | null },
): Promise<string> {
  return String(await w.webContents.executeJavaScript(`(function(){
    return new Promise((resolve, reject) => requestAnimationFrame(() => requestAnimationFrame(() => {
      try {
        const el = ${pickExpr(t.selector, t.nth)};
        if (!el || !el.isConnected) throw new Error('element detached after typing');
        resolve('value' in el ? String(el.value) : String(el.innerText || el.textContent || ''));
      } catch (error) { reject(error); }
    })));
  })()`))
}

async function assertTargetFocused(
  w: Electron.BrowserWindow,
  t: ResolvedTarget & { nth: number | null },
): Promise<void> {
  const focused = await w.webContents.executeJavaScript(`(function(){
    const el = ${pickExpr(t.selector, t.nth)};
    const active = document.activeElement;
    return !!el && !!active && (active === el || el.contains(active));
  })()`)
  if (!focused) throw new Error('target did not receive focus; typing was cancelled')
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
    const cdp = await session.cdp()
    const metrics = await cdp.sendCommand('Page.getLayoutMetrics') as {
      cssContentSize?: { width: number; height: number }
      contentSize?: { width: number; height: number }
    }
    const size = metrics.cssContentSize ?? metrics.contentSize
    if (!size || !Number.isFinite(size.width) || !Number.isFinite(size.height)
      || size.width <= 0 || size.height <= 0) {
      throw new Error('could not determine full-page dimensions')
    }
    const result = await cdp.sendCommand('Page.captureScreenshot', {
      format: 'png',
      fromSurface: true,
      captureBeyondViewport: true,
      clip: { x: 0, y: 0, width: Math.ceil(size.width), height: Math.ceil(size.height), scale: 1 },
    }) as { data?: string }
    if (!result.data) throw new Error('full-page screenshot returned no image data')
    img = nativeImage.createFromBuffer(Buffer.from(result.data, 'base64'))
    if (img.isEmpty()) throw new Error('full-page screenshot returned an empty image')
  } else {
    img = await w.webContents.capturePage()
  }
  const png = img.toPNG()
  const file = join(session.screenshotsDir(), `shot-${Date.now()}.png`)
  writeFileSync(file, png)
  const size = img.getSize()
  const a11yTree = await captureA11y(session)
  // Always pair pixels with an actionable snapshot. Even models that choose screenshot as
  // their inspection primitive receive refs they can use directly instead of falling back to
  // repeated eval_js probes.
  const dom = await snapshot(session)
  return {
    success: true, summary: 'screenshot saved', imagePath: file,
    ...(png.length <= MAX_INLINE_IMAGE_BYTES
      ? { imageBase64: png.toString('base64'), mimeType: 'image/png', imageInline: true }
      : { mimeType: 'image/png', imageInline: false, imageBytes: png.length }),
    width: size.width, height: size.height, a11yTree,
    url: dom.url, title: dom.title, domSnapshot: dom.snapshot,
  }
}

/** One serialized snapshot + action round trip for latency-sensitive browser turns. */
async function batch(session: BrowserSession, params: Record<string, unknown>) {
  const inspected = await snapshot(session)
  if (inspected.success !== true) return inspected
  const action = str(params, 'action').toLowerCase()
  let actionResult: Record<string, unknown>
  switch (action) {
    case 'click':
      actionResult = await click(session, target(params))
      break
    case 'type':
      actionResult = await type(session, target(params), str(params, 'text'), params.clear !== false)
      break
    case 'press':
      actionResult = await press(session, target(params), str(params, 'key'))
      break
    default:
      return { success: false, summary: "action must be 'click', 'type', or 'press'", snapshot: inspected.snapshot }
  }
  return {
    success: actionResult.success === true,
    summary: actionResult.success === true
      ? `snapshot + ${action} completed`
      : `snapshot captured; ${action} failed: ${String(actionResult.summary ?? 'unknown error')}`,
    snapshot: inspected.snapshot,
    count: inspected.count,
    url: actionResult.url ?? inspected.url,
    title: actionResult.title ?? inspected.title,
    results: [inspected, actionResult],
  }
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
  return { success: false, summary: 'wait timed out', ok: false }
}

function buildPredicate(selector: string | null, nth: number | null, state: string): string {
  const pick = pickExpr(selector, nth)
  // getClientRects + computed style handles fixed-position elements correctly; offsetParent
  // does not (a visible position:fixed element may have no offset parent).
  switch (state) {
    case 'attached': return `!!${pick}`
    case 'detached': return `!${pick}`
    case 'hidden': return `(() => { const e=${pick}; if(!e) return true; const s=getComputedStyle(e); const r=e.getBoundingClientRect(); return s.display==='none'||s.visibility==='hidden'||Number(s.opacity)===0||r.width<=0||r.height<=0; })()`
    default: return `(() => { const e=${pick}; if(!e) return false; const s=getComputedStyle(e); const r=e.getBoundingClientRect(); return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)!==0&&r.width>0&&r.height>0; })()`
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
