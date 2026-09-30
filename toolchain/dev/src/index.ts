import type { Plugin, ViteDevServer } from 'vite'
import { promises as fs } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { PROTOCOL_VERSION } from '@infinia/plugin-sdk/protocol'
import { createWorkerClient, probeWorker, type WorkerClient } from './worker-client.js'
import { FileRefRegistry } from './file-refs.js'
import { DevFileStore } from './dev-files.js'
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
   * Loopback endpoint of a FengYu development worker (Java, Python, or Go).
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
 * `fengyu dev` runs the `npm run dev` script that loads this Vite plugin. The plugin UI renders at
 * the Vite dev server root with full HMR, and the
 * simulator shell at `/__fengyu` bridges `postMessage` to the dev worker.
 *
 * Start the language runtime's development entry point separately: Java `PluginDevMain`, Python
 * `python3 worker.py --dev`, or Go `go run . --dev`. The simulator forwards `rpc.invoke` to the
 * same authenticated loopback protocol in every runtime.
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
 *       manifest: '../target/fengyu-manifest/manifest.json',
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
  const files = new DevFileStore(refs)

  const resolveManifest = async (viteRoot: string): Promise<Record<string, unknown>> => {
    if (typeof options.manifest !== 'string') return options.manifest
    try {
      const file = path.isAbsolute(options.manifest) ? options.manifest : path.resolve(viteRoot, options.manifest)
      return JSON.parse(await fs.readFile(file, 'utf8'))
    } catch (error) {
      throw new Error(
        `cannot load generated plugin manifest ${options.manifest}: ${(error as Error).message}. ` +
        'Run `fengyu generate` (or `fengyu dev`, which generates before Vite starts).',
      )
    }
  }

  const ensureWorkerClient = async (): Promise<WorkerClient | null> => {
    if (mockMode) return null
    if (workerClient) return workerClient
    if (!(await probeWorker(endpoint.host, endpoint.port))) {
      throw new Error(
        `dev worker unavailable at ${endpoint.host}:${endpoint.port}. ` +
        'Start the runtime development worker, or set mockWorker:true only when stub responses are intentional.'
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
        const url = new URL(req.url ?? '/', 'http://fengyu.dev')
        const pathname = url.pathname

        if (pathname === '/__fengyu') {
          let manifest: Record<string, unknown>
          try {
            manifest = await resolveManifest(server.config.root)
          } catch (error) {
            sendJson(res, 500, { error: (error as Error).message })
            return
          }
          // Iframe points at the Vite dev server root — the plugin UI is the index served there,
          // same-origin, with full HMR. No separate process, no port wait. shellOrigin pins the
          // plugin SDK's postMessage bridge to this dev server (the SDK refuses a wildcard).
          const devOrigin = `http://${req.headers.host ?? '127.0.0.1:5173'}`
          const iframeSrc = `/?shellOrigin=${encodeURIComponent(devOrigin)}`
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

        if (pathname === '/__fengyu/files/upload' && req.method === 'POST') {
          try {
            sendJson(res, 200, await files.uploadFile(url.searchParams.get('name') ?? '', req))
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
          }
          return
        }

        if (pathname === '/__fengyu/files/directory/start' && req.method === 'POST') {
          const body = await readJsonBody(req)
          if (body === null) {
            sendJson(res, 400, { error: 'invalid json body' })
            return
          }
          try {
            const uploadId = await files.startDirectory(String(body.name ?? ''), body.access)
            sendJson(res, 200, { uploadId })
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
          }
          return
        }

        if (pathname === '/__fengyu/files/directory/file' && req.method === 'POST') {
          try {
            await files.uploadDirectoryFile(
              url.searchParams.get('uploadId') ?? '',
              url.searchParams.get('path') ?? '',
              req,
            )
            res.writeHead(204)
            res.end()
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
          }
          return
        }

        if (pathname === '/__fengyu/files/directory/finish' && req.method === 'POST') {
          const body = await readJsonBody(req)
          if (body === null) {
            sendJson(res, 400, { error: 'invalid json body' })
            return
          }
          try {
            sendJson(res, 200, files.finishDirectory(String(body.uploadId ?? '')))
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
          }
          return
        }

        if (pathname === '/__fengyu/files/output' && req.method === 'POST') {
          try {
            sendJson(res, 200, await files.outputDirectory())
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
          }
          return
        }

        if (pathname.startsWith('/__fengyu/files/export/') && req.method === 'GET') {
          try {
            const refId = decodeURIComponent(pathname.slice('/__fengyu/files/export/'.length))
            const zip = await files.exportZip(refId)
            res.writeHead(200, {
              'Content-Type': 'application/zip',
              'Content-Disposition': 'attachment; filename=plugin-output.zip',
              'Content-Length': zip.length,
            })
            res.end(zip)
          } catch (err) {
            sendJson(res, 400, { error: (err as Error).message })
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
                ? (body.access === 'read-write' ? 'read-write' : body.access === 'read' ? 'read' : 'write')
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
        const uiUrl = `http://127.0.0.1:${port}/__fengyu`
        // `fengyu dev` output: the UI simulator URL, the worker dev-server status, and a
        // protocol-mismatch diagnostic. This is diagnostic text only — the CLI does not spawn or
        // manage the worker process; it is started separately in the developer's debugger.
        console.log(`[fengyu-dev] UI simulator:   ${uiUrl}`)
        if (mockMode) {
          console.log(`[fengyu-dev] Worker:         mock mode (no real worker). Set workerEndpoint to forward rpc.invoke.`)
        } else {
          console.log(`[fengyu-dev] Worker:         start the runtime dev entry point on ${endpoint.host}:${endpoint.port}`)
        }
        // Best-effort protocol-mismatch check: the plugin UI bundles its own @infinia/plugin-sdk,
        // whose PROTOCOL_VERSION may lag the simulator's. A mismatch rejects the ready() handshake
        // with INCOMPATIBLE_PROTOCOL at runtime; surface it up front so the cause is obvious.
        void detectPluginUiProtocolVersion(server.config.root).then((uiProtocol) => {
          if (!uiProtocol) return // SDK not resolvable yet (UI-only plugin / not installed) — stay quiet
          if (uiProtocol !== PROTOCOL_VERSION) {
            console.warn(`[fengyu-dev] ⚠ protocol mismatch: plugin UI @infinia/plugin-sdk v${uiProtocol} ≠ simulator v${PROTOCOL_VERSION}.`)
            console.warn(`[fengyu-dev]   ready() will reject with INCOMPATIBLE_PROTOCOL. Align the plugin UI's @infinia/plugin-sdk dependency.`)
          } else {
            console.log(`[fengyu-dev] protocol:      v${PROTOCOL_VERSION} (plugin UI SDK matches)`)
          }
        })
      })
    },
    async closeBundle() {
      await files.cleanup()
    },
  }
}

interface JsonBody {
  id?: unknown
  method?: unknown
  params?: unknown
  name?: unknown
  uploadId?: unknown
  path?: unknown
  kind?: unknown
  access?: unknown
}

function sendJson(
  res: import('node:http').ServerResponse,
  status: number,
  value: unknown,
): void {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(value))
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

/**
 * Resolve the plugin UI's bundled `@infinia/plugin-sdk` protocol version (best-effort, diagnostic
 * only). Returns `null` when the SDK cannot be resolved from the Vite root (UI-only plugin, deps
 * not installed, …) — callers stay silent in that case. This reads the SHARED protocol constant
 * the plugin UI itself consumes, so it never duplicates protocol logic.
 */
async function detectPluginUiProtocolVersion(viteRoot: string): Promise<string | null> {
  try {
    // createResolve from the Vite root honors Node's normal resolution (symlinks, hoisting,
    // workspace/file: links), so a plugin whose SDK is hoisted to a parent node_modules is found.
    const requireFromRoot = createRequire(path.resolve(viteRoot) + '/')
    const resolved = requireFromRoot.resolve('@infinia/plugin-sdk/protocol')
    const src = await fs.readFile(resolved, 'utf8')
    const match = src.match(/PROTOCOL_VERSION\s*=\s*['"]([^'"]+)['"]/)
    return match ? match[1] : null
  } catch {
    return null
  }
}
