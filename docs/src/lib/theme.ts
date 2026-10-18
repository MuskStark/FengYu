export type Theme = 'dark' | 'light'

const STORAGE_KEY = 'fengyu-docs-theme'

function root(): HTMLElement {
  return document.documentElement
}

export function applyTheme(theme: Theme) {
  const el = root()
  el.classList.toggle('theme-fengyu-dark', theme === 'dark')
  el.classList.toggle('theme-fengyu-light', theme === 'light')
  el.classList.toggle('dark', theme === 'dark')
  el.style.colorScheme = theme
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    /* storage unavailable — session-only theme */
  }
}

export function currentTheme(): Theme {
  return root().classList.contains('dark') ? 'dark' : 'light'
}

export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}
