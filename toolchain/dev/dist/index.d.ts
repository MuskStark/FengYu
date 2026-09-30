import type { Plugin } from 'vite';
import { createWorkerClient, probeWorker } from './worker-client.js';
import { FileRefRegistry } from './file-refs.js';
export { createWorkerClient, probeWorker };
export type { WorkerClient, WorkerClientOptions } from './worker-client.js';
export { FileRefRegistry };
export type { DevFileRef } from './file-refs.js';
/**
 * Options for {@link fengyuPluginDev}.
 */
export interface FengYuDevOptions {
    /**
     * Plugin manifest, either a path (resolved relative to the Vite root) or an already-parsed
     * object. Surfaced in the simulator's inspector panel.
     */
    manifest: string | Record<string, unknown>;
    /**
     * Loopback endpoint of a FengYu development worker (Java, Python, or Go).
     * When configured, connection failures are returned to the UI and never replaced by mock data.
     */
    workerEndpoint?: {
        host: string;
        port: number;
    };
    /**
     * When true (or when no {@link workerEndpoint} is given), the plugin never talks to a real
     * worker — `/__fengyu/rpc` returns `{success:true,devMock:true,method,params}`. Useful for
     * UI-only plugins and for iterating the UI before the worker exists.
     */
    mockWorker?: boolean;
    /**
     * Per-call timeout when forwarding to the dev worker. Defaults to 30s.
     */
    workerTimeoutMs?: number;
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
export declare function fengyuPluginDev(options: FengYuDevOptions): Plugin;
