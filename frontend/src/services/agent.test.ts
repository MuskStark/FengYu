import { describe, expect, it } from 'vitest'
import {
  agentGateIdFromData,
  agentStepRetryFromData,
  failActiveAgentSteps,
  isAgentEventReplayed,
  newAgentStreamSeqState,
} from './agent'
import type { AgentStep } from './types'

describe('isAgentEventReplayed (seq high-water mark)', () => {
  it('dispatches fresh seqs and suppresses replays at or below the mark', () => {
    const state = newAgentStreamSeqState()
    expect(isAgentEventReplayed({ seq: 1 }, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: 2 }, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: 2 }, state)).toBe(true)
    expect(isAgentEventReplayed({ seq: 1 }, state)).toBe(true)
    expect(isAgentEventReplayed({ seq: 3 }, state)).toBe(false)
  })

  it('never dedups payloads without a numeric seq (older backend / payloadless events)', () => {
    const state = newAgentStreamSeqState()
    expect(isAgentEventReplayed(null, state)).toBe(false)
    expect(isAgentEventReplayed({}, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: '2' }, state)).toBe(false)
  })

  it('a fresh session state resets the mark', () => {
    const first = newAgentStreamSeqState()
    isAgentEventReplayed({ seq: 7 }, first)
    const second = newAgentStreamSeqState()
    expect(isAgentEventReplayed({ seq: 1 }, second)).toBe(false)
  })
})

describe('agentStepRetryFromData (strict payload normalization)', () => {
  it('normalizes a well-formed retry payload', () => {
    const parsed = agentStepRetryFromData({
      index: 2, nextAttempt: 2, maxAttempts: 3, delayMs: 500, error: 'boom',
    }, '2026-09-23T00:00:00Z')
    expect(parsed).toEqual({
      index: 2,
      retry: {
        nextAttempt: 2,
        maxAttempts: 3,
        delayMs: 500,
        error: 'boom',
        createdAt: '2026-09-23T00:00:00Z',
      },
    })
  })

  it('rejects malformed payloads (no partial output)', () => {
    expect(agentStepRetryFromData({ index: 'x', nextAttempt: 2, maxAttempts: 3, delayMs: 1 })).toBeNull()
    // nextAttempt must be >= 2 (a first attempt is not a retry)
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 1, maxAttempts: 3, delayMs: 1 })).toBeNull()
    // maxAttempts below nextAttempt is contradictory
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 3, maxAttempts: 2, delayMs: 1 })).toBeNull()
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 2, maxAttempts: 3, delayMs: -1 })).toBeNull()
    // missing error degrades to '' rather than rejecting
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 2, maxAttempts: 3, delayMs: 0 })?.retry.error).toBe('')
  })
})

describe('agentGateIdFromData', () => {
  it('extracts a non-empty gateId and yields null otherwise (older backend)', () => {
    expect(agentGateIdFromData({ gateId: 'g-1' })).toBe('g-1')
    expect(agentGateIdFromData({ gateId: '' })).toBeNull()
    expect(agentGateIdFromData({})).toBeNull()
    expect(agentGateIdFromData(null)).toBeNull()
    expect(agentGateIdFromData({ gateId: 42 })).toBeNull()
  })
})

describe('failActiveAgentSteps', () => {
  const step = (index: number, status: AgentStep['status']): AgentStep =>
    ({ index, toolName: '', description: '', status })

  it('marks running/retrying steps failed and returns a fresh map', () => {
    const current = new Map<number, AgentStep>([
      [0, step(0, 'complete')],
      [1, step(1, 'running')],
      [2, step(2, 'retrying')],
      [3, step(3, 'pending')],
    ])
    const next = failActiveAgentSteps(current)
    expect(next.get(1)?.status).toBe('failed')
    expect(next.get(2)?.status).toBe('failed')
    expect(next.get(0)?.status).toBe('complete')
    expect(next.get(3)?.status).toBe('pending')
    expect(next).not.toBe(current)
  })

  it('returns the same map instance when nothing changes', () => {
    const current = new Map<number, AgentStep>([[0, step(0, 'complete')]])
    expect(failActiveAgentSteps(current)).toBe(current)
  })
})
