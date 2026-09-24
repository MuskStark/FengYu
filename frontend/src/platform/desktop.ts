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
  const capabilities: PlatformCapabilities = {
    nativeFileDialogs: true,
    nativeNotifications: typeof bridge.showNotification === 'function',
    desktopUpdater: typeof bridge.checkForUpdates === 'function',
    // Older preloads lack the artifact bridge — probe with typeof before calling.
    revealArtifacts: typeof bridge.revealArtifact === 'function' && typeof bridge.openArtifact === 'function',
    setupWizard: typeof bridge.setupMode === 'function',
  }

  return {
    kind: 'desktop',
    os,
    capabilities,

    apiBase: () => bridge.apiBase(),
    token: () => bridge.token(),
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
  }
}
