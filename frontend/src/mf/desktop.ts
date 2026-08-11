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
