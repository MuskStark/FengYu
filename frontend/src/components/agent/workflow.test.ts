import { describe, expect, it } from 'vitest'
import { reconcileWorkflowArguments, topologicallySortWorkflowNodes, wouldCreateCycle } from './workflow'

describe('workflow graph helpers', () => {
  const nodes = [
    { id: 'b', position: { x: 300, y: 0 } },
    { id: 'a', position: { x: 0, y: 0 } },
    { id: 'c', position: { x: 600, y: 0 } },
  ]

  it('sorts connected nodes in dependency order', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ])

    expect(sorted?.map((node) => node.id)).toEqual(['a', 'b', 'c'])
  })

  it('returns null for a cyclic workflow', () => {
    const sorted = topologicallySortWorkflowNodes(nodes, [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'a' },
    ])

    expect(sorted).toBeNull()
  })

  it('rejects an edge that would close a cycle', () => {
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ], 'c', 'a')).toBe(true)
    expect(wouldCreateCycle([
      { source: 'a', target: 'b' },
    ], 'b', 'c')).toBe(false)
  })
})

describe('reconcileWorkflowArguments', () => {
  it('preserves authored values and seeds newly required inputs', () => {
    const result = JSON.parse(reconcileWorkflowArguments(
      '{"existing":"kept","legacy":true}',
      '{"type":"object","properties":{"existing":{"type":"string"},"count":{"type":"integer","default":3}},"required":["existing","count"]}',
    ))
    expect(result).toEqual({ existing: 'kept', legacy: true, count: 3 })
  })

  it('does not destroy invalid advanced JSON during a catalog refresh', () => {
    expect(reconcileWorkflowArguments('{unfinished', '{"type":"object"}')).toBe('{unfinished')
  })
})
