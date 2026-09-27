/**
 * Keyboard-shortcut registry (scoped to FengYu's
 * surface): every shell-level binding is declared once here with its default combo and an
 * i18n description key, and the global listener matches through {@link matchesShortcut} —
 * `event.code`-based for keyboard-layout independence, with IME composition (keyCode 229)
 * and auto-repeat filtered. The composer keeps its own Lexical-level bindings (Enter/Esc/
 * arrows); this registry owns the WINDOW-level chords only.
 */

export interface ShortcutDefinition {
  id: string
  /** Physical key code (KeyboardEvent.code), e.g. 'KeyK' — layout independent. */
  code: string
  /** Exactly one of meta (mac ⌘) / ctrl must be held. */
  mod: boolean
  shift?: boolean
  alt?: boolean
  /** i18n key for the human description (settings + tooltips). */
  labelKey: string
}

export const SHORTCUTS: ShortcutDefinition[] = [
  { id: 'command-palette', code: 'KeyK', mod: true, labelKey: 'shortcuts.commandPalette' },
  { id: 'toggle-sidebar', code: 'KeyB', mod: true, labelKey: 'shortcuts.toggleSidebar' },
  { id: 'new-task', code: 'KeyN', mod: true, labelKey: 'shortcuts.newTask' },
  { id: 'settings', code: 'Comma', mod: true, labelKey: 'shortcuts.settings' },
  { id: 'find-in-task', code: 'KeyF', mod: true, labelKey: 'shortcuts.findInTask' },
]

export interface ShortcutEventLike {
  code: string
  metaKey: boolean
  ctrlKey: boolean
  shiftKey: boolean
  altKey: boolean
  repeat: boolean
  /** 229 while an IME composes — those events never drive shortcuts. */
  keyCode?: number
}

/** True when the event fires this shortcut's exact chord (IME and repeats excluded). */
export function matchesShortcut(shortcut: ShortcutDefinition, event: ShortcutEventLike): boolean {
  if (event.repeat) return false
  if (event.keyCode === 229) return false
  if (event.code !== shortcut.code) return false
  const modHeld = event.metaKey || event.ctrlKey
  if (!modHeld) return false
  if (event.metaKey === event.ctrlKey) return false // both/neither — not the mod chord
  if (Boolean(shortcut.shift) !== event.shiftKey) return false
  if (Boolean(shortcut.alt) !== event.altKey) return false
  return true
}

/** The definition registered under {@code id}, or null. */
export function shortcutById(id: string): ShortcutDefinition | null {
  return SHORTCUTS.find(shortcut => shortcut.id === id) ?? null
}

/** Display hint for a shortcut, e.g. "⌘K" / "Ctrl+K". */
export function shortcutHint(id: string, isMac: boolean): string {
  const shortcut = shortcutById(id)
  if (!shortcut) return ''
  const mod = isMac ? '⌘' : 'Ctrl+'
  const shift = shortcut.shift ? (isMac ? '⇧' : 'Shift+') : ''
  const key = shortcut.code.startsWith('Key') ? shortcut.code.slice(3) : shortcut.code
  return `${mod}${shift}${key}`
}
