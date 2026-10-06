import { describe, expect, it } from 'vitest'
import { conversationStatus } from './conversationStatus'
import type { ChatTurn, Conversation } from '@/stores/aiSession'

function turn(overrides: Partial<ChatTurn> = {}): ChatTurn {
  return {
    id: 1, role: 'assistant', content: '', thinking: '', streaming: false,
    confirmations: [], activities: [], attachments: [], artifacts: [],
    ...overrides,
  }
}

function conv(turns: ChatTurn[]): Pick<Conversation, 'turns'> {
  return { turns }
}

describe('conversationStatus', () => {
  it('leaves blank, idle, and history conversations unmarked', () => {
    expect(conversationStatus(conv([]))).toBeNull()
    expect(conversationStatus(conv([turn(), turn()]))).toBeNull()
  })

  it('marks the streaming assistant turn as running', () => {
    expect(conversationStatus(conv([turn({ streaming: true })]))).toBe('running')
  })

  it('flips to needs-input while an approval is pending or submitting', () => {
    expect(conversationStatus(conv([
      turn({ streaming: true, confirmations: [{ status: 'pending' }] }),
    ]))).toBe('needs-input')
    expect(conversationStatus(conv([
      turn({ streaming: true, confirmations: [{ status: 'submitting' }] }),
    ]))).toBe('needs-input')
  })

  it('flips to needs-input while a question card is pending or submitting', () => {
    expect(conversationStatus(conv([
      turn({ streaming: true, questions: [{ status: 'pending' }] }),
    ]))).toBe('needs-input')
    expect(conversationStatus(conv([
      turn({ streaming: true, questions: [{ status: 'submitting' }] }),
    ]))).toBe('needs-input')
  })

  it('falls back to running once the gates resolve', () => {
    expect(conversationStatus(conv([
      turn({
        streaming: true,
        confirmations: [{ status: 'approved' }],
        questions: [{ status: 'answered' }],
      }),
    ]))).toBe('running')
  })
})
