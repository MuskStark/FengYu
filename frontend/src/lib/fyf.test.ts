import { assert, describe, expect, it } from 'vitest'
import {
  FYF_FORMAT,
  FYF_SCHEMA_VERSION,
  fyfFilename,
  fyfHasGraph,
  fyfHasPlan,
  parseFyfDocument,
  takePendingFyfImport,
  stashPendingFyfImport,
  FLOW_FYF_IMPORT_KEY,
} from './fyf'

function doc(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    format: FYF_FORMAT,
    schemaVersion: FYF_SCHEMA_VERSION,
    name: 'Digest',
    goal: 'Summarize',
    inputSchema: { type: 'object', properties: {} },
    graph: { nodes: [{ id: 'start', type: 'start' }], edges: [] },
    ...overrides,
  })
}

describe('parseFyfDocument', () => {
  it('accepts a valid envelope', () => {
    const result = parseFyfDocument(doc())
    expect(result.ok).toBe(true)
  })

  it('rejects non-JSON bodies', () => {
    expect(parseFyfDocument('{oops')).toEqual({ ok: false, error: 'not-json' })
  })

  it('rejects foreign formats and non-object roots', () => {
    expect(parseFyfDocument('[]')).toEqual({ ok: false, error: 'not-fyf' })
    expect(parseFyfDocument(doc({ format: 'n8n' }))).toEqual({ ok: false, error: 'not-fyf' })
  })

  it('rejects unsupported schema versions', () => {
    expect(parseFyfDocument(doc({ schemaVersion: 99 }))).toEqual({ ok: false, error: 'bad-version' })
  })
})

describe('shape probes', () => {
  it('detects graph documents', () => {
    const graphDoc = parseFyfDocument(doc())
    expect(graphDoc.ok && fyfHasGraph(graphDoc.doc)).toBe(true)
    const broken = parseFyfDocument(doc({ graph: { nodes: 'x' } }))
    expect(broken.ok && fyfHasGraph(broken.doc)).toBe(false)
  })

  it('detects plan documents', () => {
    const plan = parseFyfDocument(doc({ plan: { goal: 'g', steps: [] }, graph: undefined }))
    expect(plan.ok && fyfHasPlan(plan.doc)).toBe(true)
    const graphOnly = parseFyfDocument(doc())
    expect(graphOnly.ok && fyfHasPlan(graphOnly.doc)).toBe(false)
  })
})

describe('fyfFilename', () => {
  it('slugs the flow name', () => {
    expect(fyfFilename('Weekly Digest  v2!')).toBe('weekly-digest-v2.fyf')
  })

  it('falls back for slug-hostile names', () => {
    expect(fyfFilename('   ')).toBe('flow.fyf')
    expect(fyfFilename('周报')).toBe('flow.fyf')
  })
})

describe('pending import hand-off', () => {
  it('round-trips a graph document exactly once', () => {
    const parsed = parseFyfDocument(doc())
    assert.ok(parsed.ok)
    stashPendingFyfImport(parsed.doc)
    expect(takePendingFyfImport()?.name).toBe('Digest')
    expect(sessionStorage.getItem(FLOW_FYF_IMPORT_KEY)).toBeNull()
    expect(takePendingFyfImport()).toBeNull()
  })

  it('drops a stash whose graph is not mountable', () => {
    sessionStorage.setItem(FLOW_FYF_IMPORT_KEY,
      JSON.stringify({ at: 1, doc: { format: FYF_FORMAT, schemaVersion: FYF_SCHEMA_VERSION } }))
    expect(takePendingFyfImport()).toBeNull()
  })
})
