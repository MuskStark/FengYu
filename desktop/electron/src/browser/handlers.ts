import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { BrowserSession } from './session'
import { formatA11yTree, type CdpAxTree } from './a11y'

/**
 * Execute one browser_* operation against the session. Returns the envelope that the
 * backend BrowserTool forwards to the AI. Envelope keys mirror the former plugin-browser.
 */
export async function handleBrowserOp(
  session: BrowserSession,
  method: string,
  params: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  try {
    switch (method) {
      case 'browser_navigate':
        return await navigate(session, str(params, 'url'))
      case 'browser_click':
        return await click(session, str(params, 'selector'))
      case 'browser_type':
        return await type(session, str(params, 'selector'), str(params, 'text'), params.clear !== false)
      case 'browser_get_text':
        return await getText(session, optStr(params, 'selector'))
      case 'browser_query':
        return await query(session, str(params, 'selector'))
      case 'browser_screenshot':
        return await screenshot(session, params.fullPage === true, optStr(params, 'selector'))
      case 'browser_wait_for':
        return await waitFor(session, str(params, 'selector'), optStr(params, 'state') ?? 'visible', num(params, 'timeout', 30))
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

async function navigate(session: BrowserSession, url: string) {
  if (!/^https?:\/\//i.test(url)) return { success: false, summary: 'url must be absolute http(s)' }
  const win = session.ensureWindow()
  await win.webContents.loadURL(url)
  return { success: true, summary: `navigated to ${url}`, url, title: win.webContents.getTitle() }
}

async function click(session: BrowserSession, selector: string) {
  const w = requireWindow(session)
  await w.webContents.executeJavaScript(
    `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) throw new Error('element not found'); el.scrollIntoView(); el.click(); })()`,
  )
  return { success: true, summary: `clicked ${selector}`, clicked: true }
}

async function type(session: BrowserSession, selector: string, text: string, clear: boolean) {
  const w = requireWindow(session)
  await w.webContents.executeJavaScript(
    `(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) throw new Error('element not found'); ${clear ? 'el.value = "";' : ''} el.value = ${JSON.stringify(text)}; el.dispatchEvent(new Event('input', {bubbles:true})); el.dispatchEvent(new Event('change', {bubbles:true})); })()`,
  )
  return { success: true, summary: `filled ${selector}`, filled: true }
}

async function getText(session: BrowserSession, selector: string | null) {
  const w = requireWindow(session)
  const expr = selector
    ? `document.querySelector(${JSON.stringify(selector)})?.innerText ?? ''`
    : `document.body.innerText`
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

async function screenshot(session: BrowserSession, fullPage: boolean, selector: string | null) {
  const w = requireWindow(session)
  const rect = selector
    ? await w.webContents.executeJavaScript(`(() => { const e = document.querySelector(${JSON.stringify(selector)}); if (!e) throw new Error('element not found'); const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; })()`)
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
  const a11yTree = await captureA11y(w)
  return { success: true, summary: 'screenshot saved', imagePath: file, width: size.width, height: size.height, a11yTree }
}

async function captureA11y(w: Electron.BrowserWindow): Promise<string> {
  try {
    if (!w.webContents.debugger.isAttached()) w.webContents.debugger.attach('1.3')
    const res = await w.webContents.debugger.sendCommand('Accessibility.getFullAXTree')
    w.webContents.debugger.detach()
    return formatA11yTree(res as CdpAxTree)
  } catch {
    try { w.webContents.debugger.detach() } catch { /* ignore */ }
    return ''
  }
}

async function waitFor(session: BrowserSession, selector: string, state: string, timeoutSec: number) {
  const w = requireWindow(session)
  const deadline = Date.now() + Math.min(600, Math.max(1, timeoutSec)) * 1000
  const pred = buildPredicate(selector, state)
  while (Date.now() < deadline) {
    const ok = await w.webContents.executeJavaScript(pred)
    if (ok) return { success: true, summary: 'wait satisfied', ok: true }
    await new Promise((r) => setTimeout(r, 200))
  }
  return { success: true, summary: 'wait timed out', ok: false }
}

function buildPredicate(selector: string, state: string): string {
  const sel = JSON.stringify(selector)
  switch (state) {
    case 'attached': return `!!document.querySelector(${sel})`
    case 'detached': return `!document.querySelector(${sel})`
    case 'hidden': return `(() => { const e = document.querySelector(${sel}); return !e || e.offsetParent === null; })()`
    default: return `(() => { const e = document.querySelector(${sel}); return !!e && e.offsetParent !== null; })()` // visible
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
function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
