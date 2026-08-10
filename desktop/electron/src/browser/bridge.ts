import { createServer, type Server } from 'node:http'
import { genToken } from '../util/token'
import type { BrowserSession } from './session'
import { handleBrowserOp } from './handlers'

export interface BrowserBridge {
  port: number
  token: string
  close(): void
}

/** Start the loopback browser bridge. Port is OS-assigned; token is per-launch. */
export function startBrowserBridge(session: BrowserSession): Promise<BrowserBridge> {
  return new Promise((resolve, reject) => {
    const token = genToken()
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
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address()
      if (addr && typeof addr === 'object') {
        resolve({ port: addr.port, token, close: () => server.close() })
      } else {
        reject(new Error('failed to bind browser bridge'))
      }
    })
  })
}
