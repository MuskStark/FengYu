/**
 * Generates the simulator HTML shell that hosts the plugin iframe and bridges `postMessage`
 * (from the @infinia/plugin-sdk FengYuClient) to the Vite dev server's middleware.
 *
 * This is the development twin of the production host's plugin shell. The iframe runs the real
 * plugin UI (served by Vite with HMR); the shell:
 *  - answers `host.ready` with a mock Environment (theme/locale/capabilities)
 *  - forwards `rpc.invoke` to `POST /__fengyu/rpc`, which the Vite middleware proxies to the
 *    loopback dev worker (or returns a devMock when `mockWorker` is set)
 *  - renders a path prompt for `files.open` / `files.inputDirectory` / `files.outputDirectory`
 *    (browsers can't pop a native picker), registers the typed path via `POST /__fengyu/ref`,
 *    and replies to the iframe with the resulting FileRef
 *
 * The postMessage envelope matches `@infinia/plugin-sdk`'s FengYuClient exactly
 * (`source: 'fengyu-host'` / `source: 'fengyu-plugin'`), so the plugin UI is identical between
 * development and production.
 *
 * Migrated from `plugin-cli/src/dev.mjs` (rpcSimulatorHtml + simulatorHtml, merged).
 */
export interface SimulatorHtmlOptions {
    /** Iframe src — the Vite dev server root (so the plugin UI is same-origin with HMR). */
    iframeSrc: string;
    /** Parsed manifest, surfaced for the inspector. */
    manifest: Record<string, unknown> | null;
}
export declare function simulatorHtml({ iframeSrc, manifest }: SimulatorHtmlOptions): string;
