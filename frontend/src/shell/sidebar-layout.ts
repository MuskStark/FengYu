// Sidebar collapse/resize model, mirroring the ZCode desktop shell: the collapsed sidebar
// fully retracts (width 0 + fade) instead of becoming an icon rail, the width is remembered
// across sessions, and the whole interaction stays keyboard-operable.

export const SIDEBAR_MIN_WIDTH = 264
export const SIDEBAR_DEFAULT_WIDTH = 264
/** The expanded sidebar never claims more than half the viewport (ZCode keeps the same cap). */
export const SIDEBAR_MAX_WIDTH_RATIO = 0.5
/** Below this viewport width the browser shell auto-collapses the sidebar. */
export const SIDEBAR_AUTO_COLLAPSE_VIEWPORT = 900
export const SIDEBAR_WIDTH_STORAGE_KEY = 'fengyu:workspace-shell:sidebar-width-px'

export function clampSidebarWidth(width: number, viewportWidth: number): number {
  const max = Math.max(SIDEBAR_MIN_WIDTH, viewportWidth * SIDEBAR_MAX_WIDTH_RATIO)
  return Math.round(Math.max(SIDEBAR_MIN_WIDTH, Math.min(width, max)))
}

export function readPersistedSidebarWidth(): number | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)
    if (!raw) return null
    const value = Number(raw)
    return Number.isFinite(value) && value > 0 ? clampSidebarWidth(value, window.innerWidth) : null
  } catch {
    return null
  }
}

export function persistSidebarWidth(width: number): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, String(Math.round(width)))
  } catch {
    // Storage can be unavailable (private mode, hardened Electron); the width is cosmetic.
  }
}

/**
 * ⌘B on macOS, Ctrl+B elsewhere — exactly one of the two modifiers, no shift/alt. Both `key`
 * and `code` are accepted so the shortcut survives non-Latin keyboard layouts.
 */
export function isToggleSidebarShortcut(event: {
  metaKey: boolean
  ctrlKey: boolean
  shiftKey: boolean
  altKey: boolean
  key: string
  code?: string
}): boolean {
  if (event.shiftKey || event.altKey) return false
  if (event.metaKey === event.ctrlKey) return false
  return event.key.toLowerCase() === 'b' || event.code === 'KeyB'
}
