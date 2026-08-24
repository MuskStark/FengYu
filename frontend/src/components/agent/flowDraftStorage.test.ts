import { describe, expect, it, vi } from 'vitest'
import {
  flowDraftRecoveryMode,
  loadFlowDraft,
  removeFlowDraft,
  saveFlowDraft,
  type LocalFlowDraft,
} from './flowDraftStorage'

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() { return values.size },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => { values.delete(key) },
    setItem: (key, value) => { values.set(key, value) },
  }
}

function draft(): LocalFlowDraft {
  return {
    version: 1,
    workflowId: 'flow-1',
    baseRevision: 4,
    savedAt: new Date().toISOString(),
    name: 'Recover me',
    description: '',
    goal: 'finish',
    inputSchemaText: '{"type":"object"}',
    graph: { nodes: [], edges: [] },
  }
}

describe('flow draft storage', () => {
  it('round-trips and removes a workflow-scoped draft', () => {
    const storage = memoryStorage()
    saveFlowDraft(draft(), storage)
    expect(loadFlowDraft('flow-1', storage)?.baseRevision).toBe(4)
    expect(loadFlowDraft(null, storage)).toBeNull()
    removeFlowDraft('flow-1', storage)
    expect(loadFlowDraft('flow-1', storage)).toBeNull()
  })

  it('expires stale drafts without throwing', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-24T00:00:00Z'))
    const storage = memoryStorage()
    const stale = draft()
    stale.savedAt = '2026-07-01T00:00:00Z'
    saveFlowDraft(stale, storage)
    expect(loadFlowDraft('flow-1', storage)).toBeNull()
    vi.useRealTimers()
  })

  it('restores only a matching server revision in place', () => {
    expect(flowDraftRecoveryMode(draft(), 4)).toBe('current')
    expect(flowDraftRecoveryMode(draft(), 5)).toBe('stale-copy')
    expect(flowDraftRecoveryMode({ ...draft(), baseRevision: null }, 5)).toBe('stale-copy')
  })

  it('treats a new unsaved workflow draft as current', () => {
    expect(flowDraftRecoveryMode({ ...draft(), workflowId: null, baseRevision: null }, null)).toBe('current')
  })
})
