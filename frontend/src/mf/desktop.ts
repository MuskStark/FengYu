import type { PluginContext } from './loader'

/** True when running inside the Tauri webview. */
export function isTauri(): boolean {
  return typeof window !== 'undefined' &&
    ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
}

/** Build the native-dialog facade, or undefined when not under Tauri. */
export function makeDesktop(): PluginContext['desktop'] {
  if (!isTauri()) return undefined
  return {
    async pickFile(filters) {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const res = await open({ multiple: false, directory: false, filters })
      return typeof res === 'string' ? res : null
    },
    async pickDirectory() {
      const { open } = await import('@tauri-apps/plugin-dialog')
      const res = await open({ multiple: false, directory: true })
      return typeof res === 'string' ? res : null
    },
  }
}
