import type { PluginContext } from './loader'

/** True when running inside the Electron desktop shell. */
export function isDesktop(): boolean {
  return typeof window !== 'undefined' && window.fengyu?.desktop === true
}

/** Build the native-dialog facade, or undefined when not under Electron. */
export function makeDesktop(): PluginContext['desktop'] {
  if (!isDesktop()) return undefined
  return {
    async pickFile(filters) {
      return (await window.fengyu!.pickFile(filters)) ?? null
    },
    async pickDirectory() {
      return (await window.fengyu!.pickDirectory()) ?? null
    },
  }
}
