import { promises as fs } from 'node:fs';
import path from 'node:path';
import { createWorkerClient, probeWorker } from './worker-client.js';
import { FileRefRegistry } from './file-refs.js';
import { simulatorHtml } from './simulator-html.js';
export { createWorkerClient, probeWorker };
export { FileRefRegistry };
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
export function fengyuPluginDev(options) {
    const endpoint = options.workerEndpoint ?? { host: '127.0.0.1', port: 24057 };
    const mockExplicit = options.mockWorker === true;
    const noEndpoint = !options.workerEndpoint;
    // Mock when explicitly requested, OR when no endpoint was configured (UI-only default).
    const mockMode = mockExplicit || noEndpoint;
    let workerClient = null;
    let workerReachable = null;
    const refs = new FileRefRegistry();
    const resolveManifest = async (viteRoot) => {
        if (typeof options.manifest !== 'string')
            return options.manifest;
        try {
            const file = path.isAbsolute(options.manifest) ? options.manifest : path.resolve(viteRoot, options.manifest);
            return JSON.parse(await fs.readFile(file, 'utf8'));
        }
        catch {
            return null;
        }
    };
    const ensureWorkerClient = async () => {
        if (mockMode)
            return null;
        if (workerClient)
            return workerClient;
        if (workerReachable === null) {
            workerReachable = await probeWorker(endpoint.host, endpoint.port);
            if (!workerReachable) {
                console.warn(`[fengyu-dev] no worker at ${endpoint.host}:${endpoint.port} — ` +
                    (noEndpoint
                        ? 'set workerEndpoint to forward rpc.invoke to a real worker, or keep mockWorker:true for UI-only dev.'
                        : 'is PluginDevMain running in your IDE? Falling back to devMock for this session.'));
            }
        }
        if (!workerReachable)
            return null;
        workerClient = createWorkerClient({ ...endpoint, timeoutMs: options.workerTimeoutMs });
        return workerClient;
    };
    return {
        name: 'fengyu:plugin-dev',
        apply: 'serve', // dev only — never affects the production UI build
        configureServer(server) {
            // Match Vite's middleware style: return a function to run BEFORE Vite's own middleware so
            // /__fengyu/* never collides with a route the user's UI might define.
            server.middlewares.use(async (req, res, next) => {
                const url = req.url ?? '/';
                const pathname = url.split('?')[0];
                if (pathname === '/__fengyu') {
                    const manifest = await resolveManifest(server.config.root);
                    // Iframe points at the Vite dev server root — the plugin UI is the index served there,
                    // same-origin, with full HMR. No separate process, no port wait.
                    const iframeSrc = '/';
                    res.setHeader('Content-Type', 'text/html; charset=utf-8');
                    res.end(simulatorHtml({ iframeSrc, manifest }));
                    return;
                }
                if (pathname === '/__fengyu/rpc' && req.method === 'POST') {
                    const body = await readJsonBody(req);
                    if (body === null) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: 'invalid json body' }));
                        return;
                    }
                    const method = String(body.method ?? '');
                    const params = refs.resolve(body.params ?? {});
                    try {
                        const client = await ensureWorkerClient();
                        let result;
                        if (client) {
                            result = await client.invoke(method, params, { timeoutMs: options.workerTimeoutMs });
                        }
                        else {
                            // Mock fallback (UI-only or dev server unreachable): echo a devMock envelope so the
                            // UI can render against a deterministic stub.
                            result = { success: true, devMock: true, method, params };
                        }
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ id: body.id, result }));
                    }
                    catch (err) {
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ id: body.id, error: err.message }));
                    }
                    return;
                }
                if (pathname === '/__fengyu/ref' && req.method === 'POST') {
                    const body = await readJsonBody(req);
                    if (body === null) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: 'invalid json body' }));
                        return;
                    }
                    try {
                        const refPath = String(body.path ?? '').trim();
                        const kind = body.kind === 'directory' ? 'directory' : 'file';
                        const access = kind === 'directory'
                            ? (body.access === 'read' ? 'read' : 'write')
                            : 'read';
                        const ref = refs.register(refPath, kind, access);
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify(ref));
                    }
                    catch (err) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ error: err.message }));
                    }
                    return;
                }
                next();
            });
            server.httpServer?.once('listening', () => {
                const addr = server.httpServer?.address();
                const port = typeof addr === 'object' && addr ? addr.port : '?';
                const target = mockMode ? '(mock worker)' : `${endpoint.host}:${endpoint.port}`;
                console.log(`[fengyu-dev] simulator at http://127.0.0.1:${port}/__fengyu  →  worker ${target}`);
            });
        },
    };
}
async function readJsonBody(req) {
    const chunks = [];
    for await (const chunk of req) {
        if (typeof chunk === 'string')
            chunks.push(Buffer.from(chunk));
        else
            chunks.push(chunk);
    }
    try {
        return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    }
    catch {
        return null;
    }
}
//# sourceMappingURL=index.js.map