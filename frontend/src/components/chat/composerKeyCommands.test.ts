import { describe, expect, it, vi } from 'vitest'
import { KEY_ENTER_COMMAND, KEY_ESCAPE_COMMAND } from 'lexical'
import { registerComposerKeyCommands, type ComposerKeyCommands } from './composerKeyCommands'

type Handler = (event?: { shiftKey?: boolean; preventDefault?: () => void }) => boolean

/** Captures registerCommand handlers by command id — enough editor for these tests. */
function fakeEditor() {
  const handlers = new Map<unknown, Handler>()
  const editor = {
    registerCommand: vi.fn((command: unknown, handler: Handler) => {
      handlers.set(command, handler)
      return () => handlers.delete(command)
    }),
  }
  return { editor, handlers }
}

function commands(overrides: Partial<ComposerKeyCommands> = {}): ComposerKeyCommands {
  return {
    getMentionState: () => ({ active: false, optionCount: 0, selectedIndex: 0 }),
    moveSelection: vi.fn(),
    insertMentionAt: vi.fn(),
    getSubmit: () => vi.fn(),
    dismissMention: vi.fn(),
    stopActiveStream: () => false,
    ...overrides,
  }
}

describe('registerComposerKeyCommands (stale-closure-free Enter-to-send)', () => {
  it('Enter runs the CURRENT submit — a late registry load cannot leave a stale closure behind', () => {
    // The regression this pins: Enter used to capture the render-time submit, whose
    // activeEntry was null while the model registry loaded; after the registry
    // resolved, Enter STILL errored "no configured models".
    const { editor, handlers } = fakeEditor()
    // The commands object is replaced every render — the handler must read through.
    let current = commands()
    const unregister = registerComposerKeyCommands(
      editor as never, () => current)
    const staleSubmit = vi.fn()
    current = commands({ getSubmit: () => staleSubmit })

    const freshSubmit = vi.fn()
    current = commands({ getSubmit: () => freshSubmit }) // "registry resolved late"
    handlers.get(KEY_ENTER_COMMAND)!(shiftEvent(false))

    expect(freshSubmit).toHaveBeenCalledOnce()
    expect(staleSubmit).not.toHaveBeenCalled()
    unregister()
  })

  it('Enter with an open mention panel accepts the selection instead of sending', () => {
    const { editor, handlers } = fakeEditor()
    const insertMentionAt = vi.fn()
    const submit = vi.fn()
    registerComposerKeyCommands(editor as never, () =>
      commands({ getMentionState: () => ({ active: true, optionCount: 3, selectedIndex: 1 }), insertMentionAt, getSubmit: () => submit }))

    handlers.get(KEY_ENTER_COMMAND)!(shiftEvent(false))
    expect(insertMentionAt).toHaveBeenCalledWith(1)
    expect(submit).not.toHaveBeenCalled()
  })

  it('Shift+Enter falls through to the editor (newline), plain Enter sends', () => {
    const { editor, handlers } = fakeEditor()
    const submit = vi.fn()
    registerComposerKeyCommands(editor as never, () => commands({ getSubmit: () => submit }))

    expect(handlers.get(KEY_ENTER_COMMAND)!(shiftEvent(true))).toBe(false)
    expect(submit).not.toHaveBeenCalled()
    expect(handlers.get(KEY_ENTER_COMMAND)!(shiftEvent(false))).toBe(true)
    expect(submit).toHaveBeenCalledOnce()
  })

  it('Escape dismisses an open panel; without one, it consults the stop handler', () => {
    const { editor, handlers } = fakeEditor()
    const dismissMention = vi.fn()
    const stopActiveStream = vi.fn(() => true)
    let current = commands({ dismissMention, stopActiveStream })
    registerComposerKeyCommands(editor as never, () => current)

    current = commands({ getMentionState: () => ({ active: true, optionCount: 2, selectedIndex: 0 }), dismissMention })
    expect(handlers.get(KEY_ESCAPE_COMMAND)!()).toBe(true)
    expect(dismissMention).toHaveBeenCalledOnce()
    expect(stopActiveStream).not.toHaveBeenCalled()

    current = commands({ stopActiveStream })
    expect(handlers.get(KEY_ESCAPE_COMMAND)!()).toBe(true)
    expect(stopActiveStream).toHaveBeenCalledOnce()
  })
})

function shiftEvent(shiftKey: boolean) {
  return { shiftKey, preventDefault: () => {} }
}
