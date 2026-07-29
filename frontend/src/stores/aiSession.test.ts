import { describe, expect, it } from 'vitest'
import { toChatHistory, type ChatTurn } from './aiSession'

function turn(role: ChatTurn['role'], content: string, streaming = false): ChatTurn {
  return {
    id: 1,
    role,
    content,
    thinking: '',
    streaming,
    confirmations: [],
  }
}

describe('AI session history', () => {
  it('does not send the streaming assistant placeholder to the model', () => {
    expect(toChatHistory([
      turn('user', 'previous'),
      turn('assistant', 'answer'),
      turn('user', 'current'),
      turn('assistant', '', true),
    ])).toEqual([
      { role: 'user', content: 'previous' },
      { role: 'assistant', content: 'answer' },
      { role: 'user', content: 'current' },
    ])
  })
})
