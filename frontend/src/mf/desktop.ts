export interface DesktopFileDialogs {
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
}

/** True when running inside the Electron desktop shell. */
export function isDesktop(): boolean {
  return typeof window !== 'undefined' && window.fengyu?.desktop === true
}

/** Build the native-dialog facade, or undefined when not under Electron. */
export function makeDesktop(): DesktopFileDialogs | undefined {
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

/**
 * Confirmation prompt that works everywhere: in the desktop shell the sandboxed renderer
 * silently drops the synchronous `window.confirm` (electron#7472 — the call returns false
 * without showing anything), so destructive actions never ran there; route the desktop
 * case through the preload's native message box instead. Callers must await.
 */
export async function confirmAction(message: string): Promise<boolean> {
  // The confirm bridge ships with the shell that loads this code, but during an upgrade
  // the SPA can hot-reload ahead of a still-running old shell (preload without `confirm`)
  // — degrade to window.confirm there instead of throwing on the missing method.
  if (isDesktop() && typeof window.fengyu!.confirm === 'function') {
    return window.fengyu!.confirm(message)
  }
  return window.confirm(message)
}
