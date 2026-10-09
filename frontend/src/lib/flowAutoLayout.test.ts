import { describe, expect, it } from 'vitest'
import { makeFlowEdge } from './flowGraph'
import { layoutFlowGraph } from './flowAutoLayout'
import type { FlowCanvasNode } from './flowGraph'

const node = (id: string, x = 0, y = 0): FlowCanvasNode =>
  ({ id, type: 'tool', position: { x, y }, data: { toolName: 't', argsText: '{}', description: '', requiresApproval: false } })

describe('layoutFlowGraph', () => {
  it('places sources strictly left of their targets (LR direction)', () => {
    const nodes = [node('a'), node('b'), node('c')]
    const edges = [makeFlowEdge('a', 'b'), makeFlowEdge('b', 'c')]
    const positions = layoutFlowGraph(nodes, edges)
    expect(positions.b!.x).toBeGreaterThan(positions.a!.x)
    expect(positions.c!.x).toBeGreaterThan(positions.b!.x)
  })

  it('keeps parallel branches separated vertically and non-overlapping', () => {
    const nodes = [node('a'), node('b1'), node('b2')]
    const edges = [makeFlowEdge('a', 'b1'), makeFlowEdge('a', 'b2')]
    const positions = layoutFlowGraph(nodes, edges)
    expect(Math.abs(positions.b1!.y - positions.b2!.y)).toBeGreaterThanOrEqual(60)
    for (const one of nodes) {
      for (const other of nodes) {
        if (one === other) continue
        const p = positions[one.id]!
        const q = positions[other.id]!
        const separated = Math.abs(p.x - q.x) >= 200 || Math.abs(p.y - q.y) >= 40
        expect(separated).toBe(true)
      }
    }
  })

  it('is deterministic — identical graphs produce identical positions', () => {
    const nodes = [node('a'), node('b'), node('c')]
    const edges = [makeFlowEdge('a', 'c'), makeFlowEdge('b', 'c')]
    expect(layoutFlowGraph(nodes, edges)).toEqual(layoutFlowGraph(nodes, edges))
  })

  it('ignores edges whose endpoints are unknown and returns {} for no nodes', () => {
    expect(layoutFlowGraph([], [])).toEqual({})
    const positions = layoutFlowGraph([node('a')], [makeFlowEdge('a', 'ghost')])
    expect(positions.a).toBeTruthy()
  })
})
