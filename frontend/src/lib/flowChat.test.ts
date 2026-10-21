import { describe, expect, it } from 'vitest'
import { flowChatHistory } from './flowChat'

const SYSTEM = { role: 'system' as const, content: 'sys' }

describe('flowChatHistory', () => {
  it('puts the system prompt first and keeps substantive turns in order', () => {
    expect(flowChatHistory(SYSTEM, [
      { role: 'user', content: 'first' },
      { role: 'assistant', content: 'answer' },
    ])).toEqual([
      { role: 'system', content: 'sys' },
      { role: 'user', content: 'first' },
      { role: 'assistant', content: 'answer' },
    ])
  })

  it('drops the empty streaming assistant placeholder but keeps user turns', () => {
    // User turns ride along even when empty (original toHistory semantics); only the
    // empty assistant placeholder streaming in the UI is filtered out.
    expect(flowChatHistory(SYSTEM, [
      { role: 'assistant', content: '' },
      { role: 'user', content: '' },
    ])).toEqual([
      SYSTEM,
      { role: 'user', content: '' },
    ])
  })

  it('carries the outgoing message even though it is not in turns yet (stale-closure guard)', () => {
    // The regression: send() read `turns` right after setTurns() — the new user message
    // was never included and the model replied "you have not stated a task".
    expect(flowChatHistory(SYSTEM, [], '帮我每天汇总并发邮件')).toEqual([
      SYSTEM,
      { role: 'user', content: '帮我每天汇总并发邮件' },
    ])
  })

  it('ignores a blank outgoing message', () => {
    expect(flowChatHistory(SYSTEM, [], '   ')).toEqual([SYSTEM])
  })
})
