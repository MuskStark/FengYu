import { createServer, type Server } from 'node:http'
import { genToken } from '../util/token'
import type { BrowserSession } from './session'
import { handleBrowserOp } from './handlers'

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
    const server: Server = createServer((req, res) => {
      // CORS not needed (loopback only). Keep handlers tiny.
      if (req.method !== 'POST' || req.url !== '/invoke') {
        res.writeHead(404).end()
        return
      }
      if (req.headers['x-browser-token'] !== token) {
        res.writeHead(401).end()
        return
      }
      let body = ''
      req.on('data', (c) => { body += c; if (body.length > 1_000_000) req.destroy() })
      req.on('end', async () => {
        try {
          const { method, params } = JSON.parse(body || '{}')
          const envelope = await handleBrowserOp(session, String(method), params ?? {})
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
        resolve({ port: addr.port, token, close: () => server.close() })
      } else {
        reject(new Error('failed to bind browser bridge'))
      }
    })
  })
}
