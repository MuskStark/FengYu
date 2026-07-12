import type { Plugin } from 'vue'
import { backendUrl } from '@/api/config'

/** The lifecycle object a plugin ESM module default-exports. */
export interface PluginModule {
  mount: (el: HTMLElement, ctx: PluginContext) => () => void
}

/** Context object handed to every plugin's mount(). */
export interface PluginContext {
  api: {
    invoke: (action: string, args?: Record<string, unknown>) => Promise<unknown>
  }
  theme: 'dark' | 'light'
  onThemeChange: (cb: (t: 'dark' | 'light') => void) => () => void
  // NEW — locale follows the host; plugins must NOT ship a language switcher
  locale: string
  t: (key: string) => string
  onLocaleChange: (cb: (locale: string) => void) => () => void
  notify: (msg: string) => void
  /**
   * The host's Vuetify (MD3) app-plugin instance. Plugins MUST call
   * `app.use(ctx.vuetify)` in mount() so they share the same MD3 theme +
   * components as the shell, without bundling Vuetify themselves.
   */
  vuetify: Plugin
  /** Backend base URL (empty string = same-origin). For raw fetch (multipart/download). */
  apiBase: string
  /** Auth token to send as X-FengYu-Token on raw fetch calls. */
  token: string
  /** Native desktop file dialogs — present ONLY under Tauri; undefined in the browser. */
  desktop?: {
    pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
    pickDirectory(): Promise<string | null>
  }
}

/**
 * Dynamically import a plugin UI bundle and validate its shape.
 *
 * `uiEntry` is a backend path like "/plugin-ui/markdown/index.js"; it is
 * prefixed with the backend base URL before import. The plugin bundle is
 * built to mark `vue` external, so it resolves the shell's Vue via the
 * index.html import map — one shared Vue instance.
 */
export async function loadPlugin(uiEntry: string): Promise<PluginModule> {
  const url = backendUrl(uiEntry)
  let mod: { default?: unknown }
  try {
    mod = (await import(/* @vite-ignore */ url)) as { default?: unknown }
  } catch (e) {
    throw new Error(
      `Failed to load plugin bundle from ${url}: ${
        e instanceof Error ? e.message : String(e)
      }`,
    )
  }

  const def = mod.default as Partial<PluginModule> | undefined
  if (!def || typeof def.mount !== 'function') {
    throw new Error(
      `Plugin bundle at ${url} does not default-export an object with a mount(el, ctx) function`,
    )
  }
  return def as PluginModule
}
