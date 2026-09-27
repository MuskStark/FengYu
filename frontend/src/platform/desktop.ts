/**
 * Desktop implementation — the ONLY module allowed to touch `window.fengyu`
 * (the Electron preload bridge). Everything else goes through getPlatform().
 */
import { appConfirm } from '@/lib/appDialogs'
import type {
  FileFilter,
  PlatformCapabilities,
  PlatformOs,
  PlatformService,
  UpdateCheck,
} from './types'

function detectOs(raw: string): PlatformOs {
  if (raw.startsWith('darwin')) return 'darwin'
  if (raw.startsWith('win')) return 'win32'
  if (raw.startsWith('linux')) return 'linux'
  return 'unknown'
}

export function createDesktopPlatform(): PlatformService {
  const bridge = window.fengyu!
  const os = detectOs(bridge.platform)

  // Live backend endpoint. The main window loads BEFORE the backend spawn (its
  // startup screen covers the JVM cold start), so the preload's env snapshot can
  // be empty on the first page load — the shell pushes the endpoint
  // (endpoint:ready, ipc/endpoint.ts) once the spawn resolves the port. Older
  // shells (and reloads after boot, whose env snapshot is already set) keep the
  // snapshot path. Subscribe first, then pull once: a push racing this page's
  // own load is dropped, not queued.
  let liveEndpoint: { apiBase: string; token: string } | null = null
  if (typeof bridge.onEndpoint === 'function') {
    bridge.onEndpoint((state) => {
      liveEndpoint = state
    })
    void bridge.getEndpoint?.().then((state) => {
      if (state) liveEndpoint = state
    }).catch(() => {
      // The invoke is best-effort; the push subscription above stays authoritative.
    })
  }

  const capabilities: PlatformCapabilities = {
    nativeFileDialogs: true,
    nativeNotifications: typeof bridge.showNotification === 'function',
    desktopUpdater: typeof bridge.checkForUpdates === 'function',
    // Older preloads lack the artifact bridge — probe with typeof before calling.
    revealArtifacts: typeof bridge.revealArtifact === 'function' && typeof bridge.openArtifact === 'function',
    setupWizard: typeof bridge.setupMode === 'function',
    // Older shells predate the boot-recovery IPC (ipc/boot.ts) — probe likewise.
    bootRecovery: typeof bridge.retryBoot === 'function' && typeof bridge.onBootState === 'function',
    logFolder: typeof bridge.openLogsFolder === 'function',
  }

  return {
    kind: 'desktop',
    os,
    capabilities,

    apiBase: () => liveEndpoint?.apiBase ?? bridge.apiBase(),
    token: () => liveEndpoint?.token ?? bridge.token(),
    initialTheme: () => bridge.initialTheme(),
    setupMode: () => (typeof bridge.setupMode === 'function' ? bridge.setupMode() : null),

    setTheme: (theme) => bridge.setTheme(theme),

    pickFile: (filters?: FileFilter[]) => bridge.pickFile(filters),
    pickDirectory: () => bridge.pickDirectory(),
    // Confirms render in-app (Zai-styled dialog) on both platforms; the preload's
    // native message box stays available to older SPA bundles only.
    confirm: (message, options) => appConfirm(message, options),
    openExternal: (url) => bridge.openExternal(url),
    showNotification: (opts) => bridge.showNotification(opts),
    revealArtifact: (artifactId) => bridge.revealArtifact(artifactId),
    openArtifact: (artifactId) => bridge.openArtifact(artifactId),

    setUpdateApiBase: (url) => bridge.setUpdateApiBase(url),
    checkForUpdates: async (): Promise<UpdateCheck> => {
      const r = await bridge.checkForUpdates()
      return {
        supported: true,
        updateAvailable: r.updateAvailable,
        version: r.version,
        releaseUrl: r.releaseUrl,
      }
    },
    downloadAndInstall: () => bridge.downloadAndInstall(),
    onUpdateProgress: (cb) => bridge.onUpdateProgress(cb),
    onUpdateState: (cb) => bridge.onUpdateState(cb),

    reportLaunchPerf: (marks) => {
      bridge.reportLaunchPerf?.(marks)
    },
    reportLog: (level, message) => {
      bridge.reportLog?.(level, message)
    },
    onBootState: (cb) => bridge.onBootState(cb),
    getBootState: () => bridge.getBootState(),
    ackBootFailure: () => {
      bridge.ackBootFailure?.()
    },
    retryBoot: () => bridge.retryBoot(),
    openLogsFolder: () => bridge.openLogsFolder(),
    copyText: (text) => bridge.copyText(text),
    quitApp: () => bridge.quitApp(),
  }
}
