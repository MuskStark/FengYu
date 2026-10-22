import { describe, expect, it } from 'vitest'
import { flowHasDrawableGraph } from './FlowMicroGraph'

describe('flowHasDrawableGraph', () => {
  it('accepts a graph with executable nodes', () => {
    expect(flowHasDrawableGraph({
      nodes: [{ id: 'start', type: 'start' } as never],
      edges: [],
    })).toBe(true)
  })

  it('rejects note-only graphs', () => {
    expect(flowHasDrawableGraph({
      nodes: [{ id: 'n', type: 'note' } as never],
      edges: [],
    })).toBe(false)
  })

  it('rejects null/undefined and the backend-normalized empty object', () => {
    // Pre-graph definitions come back from the API as {} (readMapOrEmpty): truthy,
    // no nodes key — the guard must be shape-based or the list page crashes.
    expect(flowHasDrawableGraph(null)).toBe(false)
    expect(flowHasDrawableGraph(undefined)).toBe(false)
    expect(flowHasDrawableGraph({} as never)).toBe(false)
  })
})
