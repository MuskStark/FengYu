import { promises as fs } from 'node:fs';
import path from 'node:path';
import { createWorkerClient, probeWorker } from './worker-client.js';
import { FileRefRegistry } from './file-refs.js';
import { DevFileStore } from './dev-files.js';
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
    const refs = new FileRefRegistry();
    const files = new DevFileStore(refs);
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
        if (!(await probeWorker(endpoint.host, endpoint.port))) {
            throw new Error(`dev worker unavailable at ${endpoint.host}:${endpoint.port}. ` +
                'Start PluginDevMain in your IDE, or set mockWorker:true only when stub responses are intentional.');
        }
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
                const url = new URL(req.url ?? '/', 'http://fengyu.dev');
                const pathname = url.pathname;
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
                            // Mocking is an explicit UI-only mode; configured Worker failures surface as errors.
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
                if (pathname === '/__fengyu/files/upload' && req.method === 'POST') {
                    try {
                        sendJson(res, 200, await files.uploadFile(url.searchParams.get('name') ?? '', req));
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
                    }
                    return;
                }
                if (pathname === '/__fengyu/files/directory/start' && req.method === 'POST') {
                    const body = await readJsonBody(req);
                    if (body === null) {
                        sendJson(res, 400, { error: 'invalid json body' });
                        return;
                    }
                    try {
                        const uploadId = await files.startDirectory(String(body.name ?? ''), body.access);
                        sendJson(res, 200, { uploadId });
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
                    }
                    return;
                }
                if (pathname === '/__fengyu/files/directory/file' && req.method === 'POST') {
                    try {
                        await files.uploadDirectoryFile(url.searchParams.get('uploadId') ?? '', url.searchParams.get('path') ?? '', req);
                        res.writeHead(204);
                        res.end();
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
                    }
                    return;
                }
                if (pathname === '/__fengyu/files/directory/finish' && req.method === 'POST') {
                    const body = await readJsonBody(req);
                    if (body === null) {
                        sendJson(res, 400, { error: 'invalid json body' });
                        return;
                    }
                    try {
                        sendJson(res, 200, files.finishDirectory(String(body.uploadId ?? '')));
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
                    }
                    return;
                }
                if (pathname === '/__fengyu/files/output' && req.method === 'POST') {
                    try {
                        sendJson(res, 200, await files.outputDirectory());
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
                    }
                    return;
                }
                if (pathname.startsWith('/__fengyu/files/export/') && req.method === 'GET') {
                    try {
                        const refId = decodeURIComponent(pathname.slice('/__fengyu/files/export/'.length));
                        const zip = await files.exportZip(refId);
                        res.writeHead(200, {
                            'Content-Type': 'application/zip',
                            'Content-Disposition': 'attachment; filename=plugin-output.zip',
                            'Content-Length': zip.length,
                        });
                        res.end(zip);
                    }
                    catch (err) {
                        sendJson(res, 400, { error: err.message });
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
                            ? (body.access === 'read-write' ? 'read-write' : body.access === 'read' ? 'read' : 'write')
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
        async closeBundle() {
            await files.cleanup();
        },
    };
}
function sendJson(res, status, value) {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(value));
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