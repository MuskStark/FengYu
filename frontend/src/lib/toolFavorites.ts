/**
 * Shared favorites persistence for the installed-plugin grids (Tools page and
 * the store's Installed tab render the same cards, so both must read and write
 * the same local-persistence slot the Vue ToolGrid used).
 */
const FAVORITES_STORAGE_KEY = 'fengyu:tools-grid:favorite-plugins'

export function loadFavorites(): Set<string> {
  try {
    const raw = localStorage.getItem(FAVORITES_STORAGE_KEY)
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch {
    return new Set<string>()
  }
}

export function persistFavorites(favorites: Set<string>): void {
  try {
    localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify([...favorites]))
  } catch {
    /* private mode — favorites are cosmetic */
  }
}
