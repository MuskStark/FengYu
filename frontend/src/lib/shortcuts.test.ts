import { describe, expect, it } from 'vitest'
import { SHORTCUTS, matchesShortcut, shortcutById, shortcutHint } from './shortcuts'

function keyEvent(code: string, opts: Partial<{ meta: boolean; ctrl: boolean; shift: boolean; alt: boolean; repeat: boolean; keyCode: number }> = {}) {
  return {
    code,
    metaKey: opts.meta ?? false,
    ctrlKey: opts.ctrl ?? false,
    shiftKey: opts.shift ?? false,
    altKey: opts.alt ?? false,
    repeat: opts.repeat ?? false,
    keyCode: opts.keyCode ?? 75,
  }
}

describe('shortcut registry', () => {
  const palette = shortcutById('command-palette')!

  it('matches the palette chord on either platform modifier', () => {
    expect(matchesShortcut(palette, keyEvent('KeyK', { meta: true }))).toBe(true)
    expect(matchesShortcut(palette, keyEvent('KeyK', { ctrl: true }))).toBe(true)
  })

  it('rejects plain keys, both modifiers, repeats, and IME composition', () => {
    expect(matchesShortcut(palette, keyEvent('KeyK'))).toBe(false)
    expect(matchesShortcut(palette, keyEvent('KeyK', { meta: true, ctrl: true }))).toBe(false)
    expect(matchesShortcut(palette, keyEvent('KeyK', { meta: true, repeat: true }))).toBe(false)
    expect(matchesShortcut(palette, keyEvent('KeyK', { meta: true, keyCode: 229 }))).toBe(false)
  })

  it('shift/alt must match exactly', () => {
    const withShift = { ...palette, shift: true }
    expect(matchesShortcut(withShift, keyEvent('KeyK', { meta: true, shift: true }))).toBe(true)
    expect(matchesShortcut(withShift, keyEvent('KeyK', { meta: true }))).toBe(false)
  })

  it('every registered shortcut is matchable and hinted', () => {
    for (const shortcut of SHORTCUTS) {
      expect(shortcutById(shortcut.id)).toBe(shortcut)
      expect(shortcutHint(shortcut.id, true)).toBeTruthy()
      expect(shortcutHint(shortcut.id, false)).toContain('Ctrl')
      expect(matchesShortcut(shortcut, keyEvent(shortcut.code, { meta: true }))).toBe(true)
    }
  })
})
