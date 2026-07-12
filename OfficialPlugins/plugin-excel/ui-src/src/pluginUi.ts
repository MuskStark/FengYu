import type { Plugin } from 'vue';

/**
 * Micro-frontend contract shared with the FengYu host shell.
 *
 * The built bundle's default export is `{ mount(el, ctx) => unmount }`.
 * `ctx` is supplied by the host — every field except `api` is optional, so
 * consumers must code defensively.
 *
 * This is the excel plugin's own copy of the context type (mirrors
 * plugin-markdown's `pluginUi.ts`), extended with the fields the Excel
 * Splitter wizard (Task 14) needs: direct API base/token access for
 * multipart upload/download, and an optional desktop file-picker bridge.
 */

/** Response envelope from the plugin's server-side actions. */
export interface RenderResult {
  success: boolean;
  summary?: string;
  html?: string;
  error?: string;
  [key: string]: unknown;
}

export type PluginTheme = 'dark' | 'light';

export interface PluginUiContext {
  /** Already scoped to this plugin's /invoke endpoint; returns parsed JSON. */
  api: {
    invoke(action: string, args: Record<string, unknown>): Promise<unknown>;
  };
  /** Base URL for the host's REST API (used for direct upload/download calls). */
  apiBase: string;
  /** Per-launch auth token, sent as `X-FengYu-Token` on direct API calls. */
  token: string;
  /** Present only when running inside the Tauri desktop shell. */
  desktop?: {
    pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>;
    pickDirectory(): Promise<string | null>;
  };
  theme?: PluginTheme;
  /** Subscribe to host theme changes; returns an unsubscribe fn. */
  onThemeChange?: (cb: (theme: PluginTheme) => void) => (() => void);
  t?: (key: string) => string;
  notify?: (msg: string) => void;
  /** Host's shared Vuetify (MD3) instance; plugins call app.use(ctx.vuetify) in mount(). */
  vuetify?: Plugin;
}

/** Shape of the module default export the host mounts. */
export interface PluginUiModule {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void;
}
