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
  /** Boot-failure recovery exists (in-app failure screen; web degrades to no-ops). */
  bootRecovery: boolean
  /** openLogsFolder exists (the settings log panel's folder button; web hides it). */
  logFolder: boolean
}

/**
 * Startup stages the desktop shell pushes while the backend boots (ipc/boot.ts
 * BootStage). Mirrors the shell-side union; the startup screen maps each stage
 * to a localized label.
 */
export type BootStage = 'spawning' | 'port-ready' | 'health-ready' | 'loading-ui'

/** Boot-phase event pushed by the desktop shell (ipc/boot.ts). Web never fires it. */
export interface BootStateEvent {
  phase: 'booting' | 'failed' | 'ready'
  /** Fine-grained stage while phase is 'booting' (the startup screen's label). */
  stage?: BootStage
  reason?: 'backend-exited' | 'health-deadline' | 'setup-probe-failed' | 'retry-spawn-failed' | 'port-changed'
  exitCode?: number | null
  detail?: string
  attempt?: number
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

  // ── Launch perf & boot failure recovery (desktop; web degrades to no-ops) ──
  /** One-way launch-perf report (renderer T4–T6); the shell logs the merged T0–T6 line. */
  reportLaunchPerf(marks: { rendererStart: number; reactCommit: number; inputReady: number }): void
  /** Renderer log forwarding into desktop.log; a no-op on the web platform. */
  reportLog(level: 'info' | 'warn' | 'error', message: string): void
  /** Subscribe to boot-phase pushes; returns the unsubscribe fn. Never fires on web. */
  onBootState(cb: (state: BootStateEvent) => void): () => void
  /** Pull the current boot phase once (covers pushes that raced this page's load). */
  getBootState(): Promise<BootStateEvent | null>
  /** Tell the shell the in-app failure screen is visible (disarms the native fallback). */
  ackBootFailure(): void
  /** Ask the shell to respawn the backend (throws/`ok:false` on failure). */
  retryBoot(): Promise<{ ok: boolean; error?: string }>
  /** Open the shell's log directory in the OS file manager; null when it failed. */
  openLogsFolder(): Promise<string | null>
  /** Write to the OS clipboard (diagnostics copy; sandboxed renderers lack a reliable one). */
  copyText(text: string): Promise<void>
  /** Quit the whole app (backend teardown runs through the shell's quit chain). */
  quitApp(): void
}
