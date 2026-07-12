import type { Plugin } from 'vue';

/**
 * Micro-frontend contract shared with the FengYu host shell.
 *
 * The built bundle's default export is `{ mount(el, ctx) => unmount }`.
 * `ctx` is supplied by the host — every field except `api` is optional, so
 * consumers must code defensively.
 */

/** Response envelope from the plugin's server-side `render` action. */
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
  theme?: PluginTheme;
  /** Subscribe to host theme changes; returns an unsubscribe fn. */
  onThemeChange?: (cb: (theme: PluginTheme) => void) => (() => void);
  i18n?: (key: string) => string;
  notify?: (msg: string) => void;
  /** Host's shared Vuetify (MD3) instance; plugins call app.use(ctx.vuetify) in mount(). */
  vuetify?: Plugin;
}

/** Shape of the module default export the host mounts. */
export interface PluginUiModule {
  mount(el: HTMLElement, ctx: PluginUiContext): () => void;
}
