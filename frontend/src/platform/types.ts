/**
 * Platform capability layer — the single seam between the UI and "where it runs".
 *
 * Desktop (Electron) and web get one implementation each; UI code never touches
 * `window.fengyu` directly and never branches on environment sniffing. Methods are
 * ALWAYS callable (the web implementation degrades gracefully); the capability
 * flags only decide whether UI affordances are shown at all.
 */

export type ThemeMode = 'dark' | 'light'
export type PlatformKind = 'desktop' | 'web'
export type PlatformOs = 'darwin' | 'win32' | 'linux' | 'unknown'

export interface FileFilter {
  name: string
  extensions: string[]
}

export interface UpdateCheck {
  /** False on web / when the running shell has no updater at all. */
  supported: boolean
  updateAvailable: boolean
  version: string | null
  releaseUrl: string | null
}

export interface UpdateProgress {
  percent: number
  transferred: number
  total: number
  bytesPerSecond: number
}

export interface PlatformCapabilities {
  /** pickFile/pickDirectory open OS dialogs and return local paths (web: null — callers fall back to `<input type=file>`). */
  nativeFileDialogs: boolean
  /** OS-native toast when the window is not visible. */
  nativeNotifications: boolean
  /** Electron auto-update cycle (settings entry visibility). */
  desktopUpdater: boolean
  /** revealArtifact/openArtifact exist (older preloads lack them — probed once at startup). */
  revealArtifacts: boolean
  /** First-launch SETUP mode (DB wizard reload entry, router guard). */
  setupWizard: boolean
}

export interface PlatformService {
  readonly kind: PlatformKind
  readonly os: PlatformOs
  readonly capabilities: PlatformCapabilities

  // ── Startup snapshots (desktop: preload; web: Vite env) ──
  apiBase(): string
  token(): string
  initialTheme(): ThemeMode | null
  setupMode(): boolean | null

  // ── Appearance ──
  setTheme(theme: ThemeMode): void

  // ── Dialogs & system interaction ──
  pickFile(filters?: FileFilter[]): Promise<string | null>
  pickDirectory(): Promise<string | null>
  /** In-app confirm dialog (lib/appDialogs) — the native/Electron message box was retired. */
  confirm(message: string, options?: { danger?: boolean }): Promise<boolean>
  /** Open a validated http(s) URL outside the app (desktop: system browser via IPC; web: window.open). */
  openExternal(url: string): Promise<void>
  showNotification(opts: { title: string; body?: string }): Promise<boolean>
  revealArtifact(artifactId: string): Promise<void>
  openArtifact(artifactId: string): Promise<void>

  // ── Desktop auto-update ──
  setUpdateApiBase(url: string): Promise<void>
  checkForUpdates(): Promise<UpdateCheck>
  downloadAndInstall(): Promise<{ action: 'restarting' } | { action: 'manual'; releaseUrl: string }>
  onUpdateProgress(cb: (info: UpdateProgress) => void): () => void
  onUpdateState(cb: (state: { state: string; message?: string }) => void): () => void
}
