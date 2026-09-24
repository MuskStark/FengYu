/**
 * Web implementation — every desktop capability degrades to a browser fallback
 * so callers never need their own environment branch.
 */
import { appConfirm } from '@/lib/appDialogs'
import type { PlatformOs, PlatformService } from './types'

function detectOsFromUserAgent(): PlatformOs {
  if (typeof navigator === 'undefined') return 'unknown'
  const ua = navigator.userAgent
  if (ua.includes('Mac')) return 'darwin'
  if (ua.includes('Win')) return 'win32'
  if (ua.includes('Linux') || ua.includes('X11')) return 'linux'
  return 'unknown'
}

/** http(s)-only guard shared with the old api/desktop.ts semantics. */
function assertHttpUrl(url: string): URL {
  const parsed = new URL(url)
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`Cannot open external URL scheme: ${parsed.protocol}`)
  }
  return parsed
}

export function createWebPlatform(): PlatformService {
  return {
    kind: 'web',
    os: detectOsFromUserAgent(),
    capabilities: {
      nativeFileDialogs: false,
      nativeNotifications: false,
      desktopUpdater: false,
      revealArtifacts: false,
      setupWizard: false,
    },

    apiBase: () => import.meta.env.VITE_FENGYU_API_BASE ?? '',
    token: () => import.meta.env.VITE_FENGYU_TOKEN ?? '',
    initialTheme: () => null,
    setupMode: () => null,

    setTheme: () => {
      // Theme is a settings-store concern on web (no OS chrome to sync).
    },

    pickFile: async () => null,
    pickDirectory: async () => null,
    confirm: (message, options) => appConfirm(message, options),
    openExternal: async (url) => {
      const parsed = assertHttpUrl(url)
      const opened = window.open(parsed.toString(), '_blank', 'noopener,noreferrer')
      if (opened === null) throw new Error('The sign-in window was blocked by the browser')
    },
    showNotification: async () => false,
    revealArtifact: async () => {
      // No filesystem on web — the UI hides the affordance via capabilities.
    },
    openArtifact: async () => {
      // Same as revealArtifact.
    },

    setUpdateApiBase: async () => {
      // No desktop updater on web; the backend-driven portable check lives in
      // services.appUpdate instead.
    },
    checkForUpdates: async () => ({
      supported: false,
      updateAvailable: false,
      version: null,
      releaseUrl: null,
    }),
    downloadAndInstall: async () => {
      throw new Error('Desktop updates are not supported in web mode')
    },
    onUpdateProgress: () => () => {
      // Never fires — checkForUpdates reports unsupported.
    },
    onUpdateState: () => () => {
      // Never fires — checkForUpdates reports unsupported.
    },
  }
}
