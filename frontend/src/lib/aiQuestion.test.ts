import { describe, expect, it } from 'vitest'

import {
  questionAnswerable,
  questionCardFromEvent,
  submitQuestionAnswers,
  type QuestionCardState,
} from './aiQuestion'
import type { ChatStreamQuestion } from '@/services/impl/streams'

function event(overrides: Partial<ChatStreamQuestion> = {}): ChatStreamQuestion {
  return {
    questionId: 'q1',
    expiresAt: '2026-10-04T00:00:00Z',
    questions: [
      {
        question: 'Which database?',
        header: 'DB',
        multiSelect: false,
        options: [
          { label: 'H2' },
          { label: 'PostgreSQL', description: 'Production grade' },
        ],
      },
    ],
    ...overrides,
  }
}

describe('ask_user question cards', () => {
  it('builds a pending card with state arrays aligned to the questions', () => {
    const card = questionCardFromEvent(event())
    expect(card).toMatchObject({ questionId: 'q1', status: 'pending' })
    expect(card!.items.length).toBe(1)
    expect(card!.selected).toEqual([[]])
    expect(card!.other).toEqual([''])
  })

  it('rejects events without an id or without questions', () => {
    expect(questionCardFromEvent(event({ questionId: '' }))).toBeNull()
    expect(questionCardFromEvent(event({ questions: [] }))).toBeNull()
  })

  it('requires every question to have a selection or free text before answering', () => {
    const card = questionCardFromEvent(event()) as QuestionCardState
    expect(questionAnswerable(card)).toBe(false)
    card.selected[0] = ['PostgreSQL']
    expect(questionAnswerable(card)).toBe(true)
    card.selected[0] = []
    card.other[0] = 'SQLite please'
    expect(questionAnswerable(card)).toBe(true)
  })

  it('submits selections and flips status; a backend failure errors the card', async () => {
    const card = questionCardFromEvent(event()) as QuestionCardState
    card.selected[0] = ['PostgreSQL']

    const calls: Array<{ id: string, answers: unknown }> = []
    await submitQuestionAnswers(card, async (id, answers) => {
      calls.push({ id, answers })
      return { ok: true }
    })
    expect(card.status).toBe('answered')
    expect(calls).toEqual([{
      id: 'q1',
      answers: [{ header: 'DB', selected: ['PostgreSQL'], other: undefined }],
    }])

    const failing = questionCardFromEvent(event({ questionId: 'q2' })) as QuestionCardState
    failing.selected[0] = ['H2']
    await submitQuestionAnswers(failing, async () => ({ ok: false, error: 'expired' }))
    expect(failing.status).toBe('error')
    expect(failing.error).toBe('expired')
  })

  it('ignores cards that are no longer pending', async () => {
    const card = questionCardFromEvent(event()) as QuestionCardState
    card.status = 'answered'
    let called = false
    await submitQuestionAnswers(card, async () => {
      called = true
      return { ok: true }
    })
    expect(called).toBe(false)
  })
})

describe('question error-card recovery', () => {
  it('retry re-arms only error cards whose gate is still open', async () => {
    const { questionRetryable, retryQuestion } = await import('./aiQuestion')
    const card = questionCardFromEvent(event({ expiresAt: new Date(Date.now() + 60_000).toISOString() }))!
    card.status = 'error'
    card.error = 'gate closed'

    expect(retryQuestion(card)).toBe(true)
    expect(card.status).toBe('pending')
    expect(card.error).toBeUndefined()

    const expired = questionCardFromEvent(event({ expiresAt: new Date(Date.now() - 1_000).toISOString() }))!
    expired.status = 'error'
    expect(retryQuestion(expired)).toBe(false)
    expect(expired.status).toBe('error')
    expect(questionRetryable(expired)).toBe(false)
  })

  it('dismiss retires an error card only', async () => {
    const { dismissQuestion } = await import('./aiQuestion')
    const card = questionCardFromEvent(event())!
    card.status = 'error'
    expect(dismissQuestion(card)).toBe(true)
    expect(card.status).toBe('dismissed')

    const pending = questionCardFromEvent(event())!
    expect(dismissQuestion(pending)).toBe(false)
    expect(pending.status).toBe('pending')
  })
})
