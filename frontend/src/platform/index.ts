/**
 * Platform entry — resolves the implementation once at startup. The desktop
 * bridge is a read-only snapshot captured by the preload at launch, so a
 * module-level singleton preserves exactly those semantics.
 */
import { createDesktopPlatform } from './desktop'
import { createWebPlatform } from './web'
import type { PlatformService } from './types'

let instance: PlatformService | null = null

export function getPlatform(): PlatformService {
  if (!instance) {
    instance = typeof window !== 'undefined' && window.fengyu?.desktop === true
      ? createDesktopPlatform()
      : createWebPlatform()
  }
  return instance
}

export type { PlatformService, PlatformCapabilities, PlatformKind, PlatformOs, ThemeMode, FileFilter, UpdateCheck, UpdateProgress } from './types'
export { backendUrl, pluginAssetUrl, pluginAssetIsolated } from './url'
