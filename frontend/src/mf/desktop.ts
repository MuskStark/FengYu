export interface DesktopFileDialogs {
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
}

/** True when running inside the Electron desktop shell. */
export function isDesktop(): boolean {
  return typeof window !== 'undefined' && window.fengyu?.desktop === true
}

/** Open a trusted web URL outside the app without granting arbitrary scheme access. */
export async function openExternalUrl(url: string): Promise<void> {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    throw new Error('Cannot open an invalid external URL')
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`Cannot open external URL scheme: ${parsed.protocol}`)
  }

  // New desktop shells use explicit IPC. During a hot upgrade the renderer can be
  // newer than the still-running preload; window.open then reaches the shell's existing
  // setWindowOpenHandler, which applies the same http(s)-only policy.
  if (isDesktop() && typeof window.fengyu!.openExternal === 'function') {
    await window.fengyu!.openExternal(parsed.toString())
    return
  }
  const opened = window.open(parsed.toString(), '_blank', 'noopener,noreferrer')
  if (opened === null && !isDesktop()) {
    throw new Error('The sign-in window was blocked by the browser')
  }
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
