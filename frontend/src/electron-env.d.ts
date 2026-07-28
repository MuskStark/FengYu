/** The shape exposed by the Electron preload via contextBridge. Undefined in web mode. */
export interface FengyuBridge {
  apiBase(): string
  token(): string
  initialTheme(): 'dark' | 'light'
  setupMode(): boolean | null
  setTheme(theme: 'dark' | 'light'): void
  pickFile(filters?: { name: string; extensions: string[] }[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
  desktop: true
}

declare global {
  interface Window {
    fengyu?: FengyuBridge
  }
}
