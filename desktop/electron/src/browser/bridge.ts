import { createServer, type Server } from 'node:http'
import { timingSafeEqual } from 'node:crypto'
import { genToken } from '../util/token'
import type { BrowserSession } from './session'
import { handleBrowserOp } from './handlers'
import { BrowserSessionHub } from './session-hub'

export interface BrowserBridge {
  port: number
  token: string
  close(): void
}

/**
 * Optional fixed address for the bridge. In dev connect mode (the shell connects to an
 * IDE-started backend), the developer must tell the IDE-launched JVM where the bridge is
 * listening via `FENGYU_BROWSER_BRIDGE_PORT/TOKEN`. A random OS port is unknowable there,
 * so the caller passes a fixed port + token it has already put in the IDE run config.
 * When omitted, the bridge keeps its default behaviour: OS-assigned port, per-launch token.
 */
export interface BrowserBridgeOptions {
  port?: number
  token?: string
}

/**
 * Start the loopback browser bridge. Port is OS-assigned and token per-launch unless the
 * caller pins them via {@link BrowserBridgeOptions}. The resolved {@link BrowserBridge}
 * always carries the *effective* port/token (pinned or generated).
 */
export function startBrowserBridge(session: BrowserSession, opts: BrowserBridgeOptions = {}): Promise<BrowserBridge> {
  return new Promise((resolve, reject) => {
    const token = opts.token && opts.token.length > 0 ? opts.token : genToken()
    const listenPort = opts.port && opts.port > 0 ? opts.port : 0
    // Browser input is stateful (focus, mouse position, navigation). Serialize requests so
    // overlapping model/tool calls cannot steal focus or type into the element being clicked
    // by a different operation. Keep the queue alive after an individual failure.
    let operationTail: Promise<void> = Promise.resolve()
    const sessions = new BrowserSessionHub(session)
    const server: Server = createServer((req, res) => {
      // CORS not needed (loopback only). Keep handlers tiny.
      // Same rebinding firewall as the backend's TokenAuthFilter: a website that rebinds its
      // domain to 127.0.0.1 addresses us with its own Host header — reject before anything else.
      const host = String(req.headers.host ?? '')
      if (!/^(\[::1\]|127\.0\.0\.1|localhost)(:\d+)?$/i.test(host)) {
        res.writeHead(403).end()
        return
      }
      if (req.method !== 'POST' || req.url !== '/invoke') {
        res.writeHead(404).end()
        return
      }
      if (!tokenMatches(req.headers['x-browser-token'], token)) {
        res.writeHead(401).end()
        return
      }
      let body = ''
      req.on('data', (c) => { body += c; if (body.length > 1_000_000) req.destroy() })
      req.on('end', async () => {
        try {
          const { method, params } = JSON.parse(body || '{}')
          const safeParams = params && typeof params === 'object' ? params as Record<string, unknown> : {}
          const operation = operationTail.then(async () => {
            const op = String(method)
            if (op === 'browser_contexts') return sessions.listContexts(safeParams)
            if (op === 'browser_new_context') return sessions.newContext(safeParams)
            if (op === 'browser_select_context') return sessions.selectContext(safeParams)
            if (op === 'browser_close_context') return sessions.closeContext(safeParams)
            if (op === 'browser_tabs') return sessions.list(safeParams)
            if (op === 'browser_new_tab') {
              return sessions.newTab(safeParams,
                (tab, url) => handleBrowserOp(tab, 'browser_navigate', { url }))
            }
            if (op === 'browser_select_tab') return sessions.selectTab(safeParams)
            if (op === 'browser_close_tab') return sessions.closeTab(safeParams)
            const route = sessions.resolve(safeParams)
            const result = await handleBrowserOp(route.session, op, safeParams)
            return sessions.decorate(route, result)
          })
          operationTail = operation.then(() => undefined, () => undefined)
          const envelope = await operation
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify(envelope))
        } catch (e) {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ success: false, summary: e instanceof Error ? e.message : String(e) }))
        }
      })
    })
    server.on('error', reject)
    server.listen(listenPort, '127.0.0.1', () => {
      const addr = server.address()
      if (addr && typeof addr === 'object') {
        resolve({ port: addr.port, token, close: () => { server.close(); sessions.closeAll() } })
      } else {
        reject(new Error('failed to bind browser bridge'))
      }
    })
  })
}

/** Constant-time bearer comparison; node:http folds duplicate headers into one comma string. */
function tokenMatches(provided: unknown, token: string): boolean {
  if (typeof provided !== 'string') return false
  const got = Buffer.from(provided, 'utf8')
  const want = Buffer.from(token, 'utf8')
  return got.length === want.length && timingSafeEqual(got, want)
}
