import {
  COMMAND_PRIORITY_CRITICAL,
  KEY_ARROW_DOWN_COMMAND,
  KEY_ARROW_UP_COMMAND,
  KEY_ENTER_COMMAND,
  KEY_ESCAPE_COMMAND,
  KEY_TAB_COMMAND,
  type LexicalEditor,
} from 'lexical'

/**
 * The composer's CRITICAL-priority keyboard commands, extracted from the component
 * so they are immune to closure staleness: every handler resolves the CURRENT
 * command object through {@link getCommands} at event time, never a render-time
 * capture.
 *
 * Regression this shape pins: Enter-to-send used to close over the render's
 * `submit`, whose `activeEntry` was still null while the model registry loaded
 * asynchronously — a late registry resolve left the old closure in place, so Enter
 * kept erroring "no configured models" even though the send button worked.
 */
export interface ComposerKeyCommands {
  /** The live mention-panel state (null panel → optionCount 0). */
  getMentionState: () => { active: boolean; optionCount: number; selectedIndex: number }
  /** Move the panel selection by delta (wraps); only called while a panel is active. */
  moveSelection: (delta: number) => void
  /** Accept the option at the flat index; only called with a valid index. */
  insertMentionAt: (index: number) => void
  /** Always-current Enter-to-send — the module never caches this. */
  getSubmit: () => () => void
  /** Dismiss the open panel (Escape). */
  dismissMention: () => void
  /** Escape with no panel: stop the ACTIVE conversation's stream; true when handled. */
  stopActiveStream: () => boolean
}

/** Registers the command handlers on the editor; returns the unregister function. */
export function registerComposerKeyCommands(
  editor: Pick<LexicalEditor, 'registerCommand'>,
  getCommands: () => ComposerKeyCommands,
): () => void {
  const unregister = [
    editor.registerCommand(KEY_ARROW_DOWN_COMMAND, () => {
      const { active, optionCount } = getCommands().getMentionState()
      if (!active || optionCount === 0) return false
      getCommands().moveSelection(1)
      return true
    }, COMMAND_PRIORITY_CRITICAL),
    editor.registerCommand(KEY_ARROW_UP_COMMAND, () => {
      const { active, optionCount } = getCommands().getMentionState()
      if (!active || optionCount === 0) return false
      getCommands().moveSelection(-1)
      return true
    }, COMMAND_PRIORITY_CRITICAL),
    editor.registerCommand(KEY_ENTER_COMMAND, event => {
      const commands = getCommands()
      const { active, optionCount, selectedIndex } = commands.getMentionState()
      if (active && optionCount > 0) {
        event?.preventDefault()
        commands.insertMentionAt(selectedIndex)
        return true
      }
      if (!event?.shiftKey) {
        event?.preventDefault()
        commands.getSubmit()()
        return true
      }
      return false
    }, COMMAND_PRIORITY_CRITICAL),
    editor.registerCommand(KEY_TAB_COMMAND, event => {
      const commands = getCommands()
      const { active, optionCount, selectedIndex } = commands.getMentionState()
      if (active && optionCount > 0) {
        event?.preventDefault()
        commands.insertMentionAt(selectedIndex)
        return true
      }
      return false
    }, COMMAND_PRIORITY_CRITICAL),
    editor.registerCommand(KEY_ESCAPE_COMMAND, () => {
      const commands = getCommands()
      if (commands.getMentionState().active) {
        commands.dismissMention()
        return true
      }
      // Esc with no panel while THIS conversation streams stops its generation.
      return commands.stopActiveStream()
    }, COMMAND_PRIORITY_CRITICAL),
  ]
  return () => unregister.forEach(fn => fn())
}
