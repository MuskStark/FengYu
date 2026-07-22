import type { Plugin, ViteDevServer } from 'vite'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { createWorkerClient, probeWorker, type WorkerClient } from './worker-client.js'
import { FileRefRegistry } from './file-refs.js'
import { simulatorHtml } from './simulator-html.js'

export { createWorkerClient, probeWorker }
export type { WorkerClient, WorkerClientOptions } from './worker-client.js'
export { FileRefRegistry }
export type { DevFileRef } from './file-refs.js'

/**
 * Options for {@link fengyuPluginDev}.
 */
export interface FengYuDevOptions {
  /**
   * Plugin manifest, either a path (resolved relative to the Vite root) or an already-parsed
   * object. Surfaced in the simulator's inspector panel.
   */
  manifest: string | Record<string, unknown>
  /**
   * Loopback endpoint of the `fengyu-plugin-devkit` dev server (run `PluginDevMain` in your IDE).
   * When configured, connection failures are returned to the UI and never replaced by mock data.
   */
  workerEndpoint?: { host: string; port: number }
  /**
   * When true (or when no {@link workerEndpoint} is given), the plugin never talks to a real
   * worker — `/__fengyu/rpc` returns `{success:true,devMock:true,method,params}`. Useful for
   * UI-only plugins and for iterating the UI before the worker exists.
   */
  mockWorker?: boolean
  /**
   * Per-call timeout when forwarding to the dev worker. Defaults to 30s.
   */
  workerTimeoutMs?: number
}

/**
 * A Vite plugin that turns the dev server into a FengYu plugin host simulator.
 *
 * Replace `fengyu plugin dev` with this: add it to your `vite.config.ts`, then run `npm run dev`
 * in `ui-src/`. The plugin UI renders at the Vite dev server root with full HMR, and the
 * simulator shell at `/__fengyu` bridges `postMessage` to the dev worker.
 *
 * The Java side is debugged separately: run `PluginDevMain.main()` in your IDE — it starts the
 * `fengyu-plugin-devkit` loopback TCP server that this plugin forwards `rpc.invoke` to. Set
 * breakpoints in your handlers; they fire directly, no JDWP remote attach.
 *
 * @example
 * ```ts
 * import { defineConfig } from 'vite'
 * import vue from '@vitejs/plugin-vue'
 * import { fengyuPluginDev } from '@infinia/plugin-dev'
 *
 * export default defineConfig({
 *   plugins: [
 *     vue(),
 *     fengyuPluginDev({
 *       manifest: '../manifest.json',
 *       workerEndpoint: { host: '127.0.0.1', port: 24057 },
 *     }),
 *   ],
 * })
 * ```
 */
export function fengyuPluginDev(options: FengYuDevOptions): Plugin {
  const endpoint = options.workerEndpoint ?? { host: '127.0.0.1', port: 24057 }
  const mockExplicit = options.mockWorker === true
  const noEndpoint = !options.workerEndpoint
  // Mock when explicitly requested, OR when no endpoint was configured (UI-only default).
  const mockMode = mockExplicit || noEndpoint

  let workerClient: WorkerClient | null = null
  const refs = new FileRefRegistry()

  const resolveManifest = async (viteRoot: string): Promise<Record<string, unknown> | null> => {
    if (typeof options.manifest !== 'string') return options.manifest
    try {
      const file = path.isAbsolute(options.manifest) ? options.manifest : path.resolve(viteRoot, options.manifest)
      return JSON.parse(await fs.readFile(file, 'utf8'))
    } catch {
      return null
    }
  }

  const ensureWorkerClient = async (): Promise<WorkerClient | null> => {
    if (mockMode) return null
    if (workerClient) return workerClient
    if (!(await probeWorker(endpoint.host, endpoint.port))) {
      throw new Error(
        `dev worker unavailable at ${endpoint.host}:${endpoint.port}. ` +
        'Start PluginDevMain in your IDE, or set mockWorker:true only when stub responses are intentional.'
      )
    }
    workerClient = createWorkerClient({ ...endpoint, timeoutMs: options.workerTimeoutMs })
    return workerClient
  }

  return {
    name: 'fengyu:plugin-dev',
    apply: 'serve', // dev only — never affects the production UI build

    configureServer(server: ViteDevServer) {
      // Match Vite's middleware style: return a function to run BEFORE Vite's own middleware so
      // /__fengyu/* never collides with a route the user's UI might define.
      server.middlewares.use(async (req, res, next) => {
        const url = req.url ?? '/'
        const pathname = url.split('?')[0]

        if (pathname === '/__fengyu') {
          const manifest = await resolveManifest(server.config.root)
          // Iframe points at the Vite dev server root — the plugin UI is the index served there,
          // same-origin, with full HMR. No separate process, no port wait.
          const iframeSrc = '/'
          res.setHeader('Content-Type', 'text/html; charset=utf-8')
          res.end(simulatorHtml({ iframeSrc, manifest }))
          return
        }

        if (pathname === '/__fengyu/rpc' && req.method === 'POST') {
          const body = await readJsonBody(req)
          if (body === null) {
            res.writeHead(400, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ error: 'invalid json body' }))
            return
          }
          const method = String(body.method ?? '')
          const params = refs.resolve(body.params ?? {}) as Record<string, unknown>
          try {
            const client = await ensureWorkerClient()
            let result: unknown
            if (client) {
              result = await client.invoke(method, params, { timeoutMs: options.workerTimeoutMs })
            } else {
              // Mocking is an explicit UI-only mode; configured Worker failures surface as errors.
              result = { success: true, devMock: true, method, params }
            }
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ id: body.id, result }))
          } catch (err) {
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ id: body.id, error: (err as Error).message }))
          }
          return
        }

        if (pathname === '/__fengyu/ref' && req.method === 'POST') {
          const body = await readJsonBody(req)
          if (body === null) {
            res.writeHead(400, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ error: 'invalid json body' }))
            return
          }
          try {
            const refPath = String(body.path ?? '').trim()
            const kind: 'file' | 'directory' = body.kind === 'directory' ? 'directory' : 'file'
            const access: 'read' | 'write' | 'read-write' =
              kind === 'directory'
                ? (body.access === 'read' ? 'read' : 'write')
                : 'read'
            const ref = refs.register(refPath, kind, access)
            res.writeHead(200, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify(ref))
          } catch (err) {
            res.writeHead(400, { 'Content-Type': 'application/json' })
            res.end(JSON.stringify({ error: (err as Error).message }))
          }
          return
        }

        next()
      })

      server.httpServer?.once('listening', () => {
        const addr = server.httpServer?.address()
        const port = typeof addr === 'object' && addr ? addr.port : '?'
        const target = mockMode ? '(mock worker)' : `${endpoint.host}:${endpoint.port}`
        console.log(`[fengyu-dev] simulator at http://127.0.0.1:${port}/__fengyu  →  worker ${target}`)
      })
    },
  }
}

interface JsonBody {
  id?: unknown
  method?: unknown
  params?: unknown
  path?: unknown
  kind?: unknown
  access?: unknown
}

async function readJsonBody(req: import('node:http').IncomingMessage): Promise<JsonBody | null> {
  const chunks: Buffer[] = []
  for await (const chunk of req) {
    if (typeof chunk === 'string') chunks.push(Buffer.from(chunk))
    else chunks.push(chunk as Buffer)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    return null
  }
}
