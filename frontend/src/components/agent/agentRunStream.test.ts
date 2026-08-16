import { describe, expect, it } from 'vitest'
import { isAgentEventReplayed, newAgentStreamSeqState } from './agentRunStream'

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
