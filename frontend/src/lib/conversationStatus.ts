import type { Conversation } from '@/stores/aiSession'
import type { ToolConfirmation } from '@/lib/aiConfirmation'
import type { QuestionCardState } from '@/lib/aiQuestion'

/** Sidebar conversation activity states (ZCode-style: marks only while a turn is live). */
export type ConversationStatus = 'running' | 'needs-input'

/**
 * The sidebar marks a row only while its turn is LIVE (ZCode behavior): the
 * conversation with a streaming assistant turn is `running` — or `needs-input`
 * the moment that turn carries an unresolved approval or question card (the
 * backend gate holds the stream open until the user acts). Idle, blank, and
 * history conversations carry no mark at all; the timestamp is their state.
 */
export function conversationStatus(
  conv: Pick<Conversation, 'turns'>,
): ConversationStatus | null {
  const streaming = conv.turns.find(turn => turn.role === 'assistant' && turn.streaming)
  if (!streaming) return null
  const gated = (streaming.confirmations as ToolConfirmation[])
      .some(item => item.status === 'pending' || item.status === 'submitting')
    || (streaming.questions as QuestionCardState[] | undefined)
      ?.some(item => item.status === 'pending' || item.status === 'submitting')
  return gated ? 'needs-input' : 'running'
}
