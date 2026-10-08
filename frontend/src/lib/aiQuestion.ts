import { i18n } from '@/i18n'
import { services } from '@/services'
import type { ChatStreamQuestion } from '@/services/impl/streams'

export type QuestionStatus = 'pending' | 'submitting' | 'answered' | 'expired' | 'error' | 'dismissed'

export interface QuestionCardState {
  questionId: string
  expiresAt: string
  status: QuestionStatus
  items: ChatStreamQuestion['questions']
  /** Per-question selection state, index-aligned with {@code items}. */
  selected: string[][]
  /** Per-question free-text "Other" values, index-aligned. */
  other: string[]
  error?: string
}

/** Builds a pending card from the SSE question event. */
export function questionCardFromEvent(event: ChatStreamQuestion): QuestionCardState | null {
  if (!event.questionId || event.questions.length === 0) return null
  return {
    questionId: event.questionId,
    expiresAt: event.expiresAt,
    status: 'pending',
    items: event.questions,
    selected: event.questions.map(() => []),
    other: event.questions.map(() => ''),
  }
}

/** Whether a card is ready to submit: every question has a selection or free-text. */
export function questionAnswerable(card: QuestionCardState): boolean {
  // multiSelect only changes HOW selections accumulate, not the readiness bar — every
  // question needs at least one picked option or a written answer either way.
  return card.items.every((_, index) =>
    (card.selected[index] ?? []).length > 0 || Boolean((card.other[index] ?? '').trim()))
}

/** Submits the user's answers; flips the card's status from pending to a terminal state. */
export async function submitQuestionAnswers(card: QuestionCardState,
    resolve: (questionId: string, answers: Array<{ header?: string; selected: string[]; other?: string }>) =>
      Promise<{ ok: boolean; error?: string }> =
      (id, answers) => services.chat.answerQuestion(id, answers)): Promise<void> {
  if (card.status !== 'pending') return
  card.status = 'submitting'
  try {
    const answers = card.items.map((item, index) => ({
      header: item.header,
      selected: card.selected[index] ?? [],
      other: (card.other[index] ?? '').trim() || undefined,
    }))
    const result = await resolve(card.questionId, answers)
    if (result.ok === false) throw new Error(result.error ?? i18n.global.t('aichat.questionAnswerFailed'))
    card.status = 'answered'
  } catch (error) {
    card.status = 'error'
    card.error = error instanceof Error ? error.message : String(error)
  }
}

// ── error-card recovery (the composer's failure affordances) ─────────────────

/** Whether a failed card's gate is still open (a retry can still land). */
export function questionRetryable(card: QuestionCardState, now = Date.now()): boolean {
  const expiresAt = Date.parse(card.expiresAt)
  return Number.isFinite(expiresAt) && now < expiresAt
}

/**
 * Re-arm a failed question card for another submit: only valid from the error
 * state and only while the gate has not expired (an expired gate can only ever
 * fail again). Returns whether the card changed; the caller republishes the store.
 */
export function retryQuestion(card: QuestionCardState, now = Date.now()): boolean {
  if (card.status !== 'error' || !questionRetryable(card, now)) return false
  card.status = 'pending'
  card.error = undefined
  return true
}

/** Retire a failed question card entirely (dismissed cards leave the composer). */
export function dismissQuestion(card: QuestionCardState): boolean {
  if (card.status !== 'error') return false
  card.status = 'dismissed'
  return true
}
