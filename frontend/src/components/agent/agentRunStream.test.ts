import { describe, expect, it } from 'vitest'
import {
  agentGateIdFromData,
  agentStepRetryFromData,
  failActiveAgentSteps,
  isAgentEventReplayed,
  newAgentStreamSeqState,
} from './agentRunStream'
import type { AgentStep } from '@/api/types'

/**
 * The composable itself has no test harness (it needs an EventSource + vue-i18n
 * component context); these tests pin the replay-dedup decision it applies to
 * every parsed agent stream payload.
 */
describe('isAgentEventReplayed (agent stream replay dedup)', () => {
  it('skips the replayed prefix after a reconnect and dispatches only new events', () => {
    const state = newAgentStreamSeqState()
    // Live session sees seq 1 and seq 2…
    expect(isAgentEventReplayed({ seq: 1, delta: 'a' }, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: 2, delta: 'b' }, state)).toBe(false)
    // …the connection drops; the backend replays [seq1, seq2, seq3] on reconnect:
    // the prefix is skipped, only seq3 dispatches.
    expect(isAgentEventReplayed({ seq: 1, delta: 'a' }, state)).toBe(true)
    expect(isAgentEventReplayed({ seq: 2, delta: 'b' }, state)).toBe(true)
    expect(isAgentEventReplayed({ seq: 3, delta: 'c' }, state)).toBe(false)
  })

  it('never dedups payloads without a numeric seq (older backend / payloadless events)', () => {
    const state = newAgentStreamSeqState()
    expect(isAgentEventReplayed({ seq: 7 }, state)).toBe(false)
    expect(isAgentEventReplayed({}, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: '3' }, state)).toBe(false)
    expect(isAgentEventReplayed(null, state)).toBe(false)
    // Seq-less dispatches do not advance the high-water mark: seq 7 stays a replay.
    expect(isAgentEventReplayed({ seq: 7 }, state)).toBe(true)
  })

  it('resets per stream session — a NEW run replays from seq 1 and dispatches everything', () => {
    const state = newAgentStreamSeqState()
    expect(isAgentEventReplayed({ seq: 1 }, state)).toBe(false)
    expect(isAgentEventReplayed({ seq: 2 }, state)).toBe(false)
    // openStream() for a new run mints a fresh state; its replay is all-new.
    const fresh = newAgentStreamSeqState()
    expect(isAgentEventReplayed({ seq: 1 }, fresh)).toBe(false)
    expect(isAgentEventReplayed({ seq: 2 }, fresh)).toBe(false)
  })
})

describe('agentGateIdFromData (approval-gate credential capture)', () => {
  it('extracts the credential from plan and step approval-request payloads', () => {
    expect(agentGateIdFromData({ gateId: 'g-1', seq: 4 })).toBe('g-1')
    expect(agentGateIdFromData({ index: 2, gateId: 'g-2', seq: 5 })).toBe('g-2')
  })

  it('yields null for payloadless / empty credentials (legacy fallback)', () => {
    expect(agentGateIdFromData({})).toBeNull()
    expect(agentGateIdFromData({ gateId: '' })).toBeNull()
    expect(agentGateIdFromData({ gateId: null })).toBeNull()
    expect(agentGateIdFromData(null)).toBeNull()
    expect(agentGateIdFromData(undefined)).toBeNull()
  })
})

describe('agentStepRetryFromData', () => {
  it('normalizes live and persisted retry events', () => {
    expect(agentStepRetryFromData({
      index: 1,
      nextAttempt: 2,
      maxAttempts: 4,
      delayMs: 500,
      error: 'temporary outage',
    }, '2026-08-20T12:00:00Z')).toEqual({
      index: 1,
      retry: {
        nextAttempt: 2,
        maxAttempts: 4,
        delayMs: 500,
        error: 'temporary outage',
        createdAt: '2026-08-20T12:00:00Z',
      },
    })
  })

  it('rejects malformed or impossible retry payloads', () => {
    expect(agentStepRetryFromData({ index: -1, nextAttempt: 2, maxAttempts: 3, delayMs: 0 })).toBeNull()
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 4, maxAttempts: 3, delayMs: 0 })).toBeNull()
    expect(agentStepRetryFromData({ index: 0, nextAttempt: 2, maxAttempts: 3, delayMs: -1 })).toBeNull()
  })
})

describe('failActiveAgentSteps', () => {
  it('turns running and retrying steps into failed terminal states', () => {
    const steps = new Map<number, AgentStep>([
      [0, { index: 0, toolName: 'done', description: '', status: 'complete' }],
      [1, { index: 1, toolName: 'active', description: '', status: 'running' }],
      [2, { index: 2, toolName: 'retry', description: '', status: 'retrying' }],
      [3, { index: 3, toolName: 'later', description: '', status: 'pending' }],
    ])

    expect([...failActiveAgentSteps(steps).values()].map((step) => step.status))
      .toEqual(['complete', 'failed', 'failed', 'pending'])
  })

  it('keeps the same map when no spinner state needs settling', () => {
    const steps = new Map<number, AgentStep>([
      [0, { index: 0, toolName: 'done', description: '', status: 'complete' }],
    ])

    expect(failActiveAgentSteps(steps)).toBe(steps)
  })
})
