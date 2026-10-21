/**
 * History building for the docked Flow authoring chat. Extracted because the send path
 * MUST carry the outgoing user message explicitly: calling this with the CURRENT state
 * right after setTurns() reads the stale pre-update turns (React state updates are
 * async), which once produced a first turn that never reached the model at all.
 */
import type { ChatMessage } from '@/services/types'

export interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
}

/**
 * System prompt first, then every turn worth sending (user turns always; assistant turns
 * only once they carry content — the empty placeholder streaming in the UI is dropped),
 * and — crucially — the outgoing user message even though it is not in `turns` yet.
 */
export function flowChatHistory(
  systemPrompt: ChatMessage,
  turns: ChatTurn[],
  outgoingText?: string,
): ChatMessage[] {
  const history: ChatMessage[] = [systemPrompt]
  for (const turn of turns) {
    if (turn.role === 'user' || turn.content.trim()) history.push(turn)
  }
  if (outgoingText !== undefined && outgoingText.trim()) {
    history.push({ role: 'user', content: outgoingText })
  }
  return history
}
