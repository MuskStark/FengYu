/**
 * Generates the simulator shell served at `/__fengyu`: the development twin of the production
 * host (frontend `PluginPage.tsx`). The iframe runs the real plugin UI (served by Vite with
 * HMR); the shell around it is a full environment-simulation console:
 *
 *  - Environment panel — simulate the whole `HostEnvironment`: theme, locale, platform,
 *    per-permission grants, per-capability grants, and a deny-all chaos switch. Every change
 *    pushes an `environment` event exactly like the production host does on theme/locale changes.
 *  - Worker panel — live mode/endpoint/online status (from `GET /__fengyu/status`), a UI-SDK
 *    protocol-mismatch warning (the terminal diagnostic, now visible in-page), and a direct
 *    invoke composer that calls the dev worker through the same `/__fengyu/rpc` bridge.
 *  - File requests inbox — browser picker (temporary snapshot upload) or a manual absolute path
 *    (desktop-style in-place read/write), plus a recent-paths row.
 *  - Inspector — a structured message timeline (direction, method, duration, ok/err state,
 *    filters, expandable envelopes with copy) instead of a raw JSON dump.
 *  - Manifest panel — key facts plus the raw generated manifest.
 *
 * The postMessage envelope matches `@infinia/plugin-sdk`'s FengYuClient exactly
 * (`source: 'fengyu-host'` / `source: 'fengyu-plugin'`), so the plugin UI is identical between
 * development and production. The shell visual language is the Infinia design system shared with
 * `@infinia/plugin-ui` (warm-white + gold, `.dark` mirror); the shell's own theme is independent
 * of the simulated plugin theme it pushes into the iframe.
 */
export interface SimulatorHtmlOptions {
    /** Iframe src — the Vite dev server root (so the plugin UI is same-origin with HMR). */
    iframeSrc: string;
    /** Parsed manifest, surfaced for the inspector. */
    manifest: Record<string, unknown> | null;
}
export declare function simulatorHtml({ iframeSrc, manifest }: SimulatorHtmlOptions): string;
