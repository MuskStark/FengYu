import { describe, expect, it } from 'vitest'
import {
  diffFlowProposal,
  flowSnapshotId,
  parseFlowProposal,
} from './flowAiAuthoring'

describe('Flow AI authoring proposal contract', () => {
  it('parses only canonical proposal envelopes', () => {
    const proposal = parseFlowProposal(JSON.stringify({
      kind: 'flow_proposal',
      baseWorkflowId: null,
      baseRevision: null,
      baseSnapshotId: 'v1-a',
      name: 'Generated',
      description: '',
      goal: 'Format JSON',
      inputSchema: { type: 'object', properties: {} },
      graph: {
        nodes: [{ id: 'n1', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 'json_format' } }],
        edges: [],
      },
      summary: 'Add JSON formatting',
    }))
    expect(proposal?.name).toBe('Generated')
    expect(parseFlowProposal('{"kind":"flow_proposal_error"}')).toBeNull()
    expect(parseFlowProposal('{bad')).toBeNull()
  })

  it('summarizes node and edge changes by stable ids', () => {
    const diff = diffFlowProposal({
      nodes: [
        { id: 'keep', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 'a' } },
        { id: 'remove', type: 'tool', position: { x: 0, y: 1 }, data: { toolName: 'b' } },
      ],
      edges: [{ id: 'old', source: 'keep', target: 'remove' }],
    }, {
      nodes: [
        { id: 'keep', type: 'tool', position: { x: 1, y: 0 }, data: { toolName: 'a' } },
        { id: 'add', type: 'tool', position: { x: 2, y: 0 }, data: { toolName: 'c' } },
      ],
      edges: [{ id: 'new', source: 'keep', target: 'add' }],
    })
    expect(diff).toEqual({
      addedNodes: 1,
      removedNodes: 1,
      changedNodes: 1,
      addedEdges: 1,
      removedEdges: 1,
    })
  })

  it('fingerprints the same canvas deterministically', () => {
    expect(flowSnapshotId('canvas')).toBe(flowSnapshotId('canvas'))
    expect(flowSnapshotId('canvas')).not.toBe(flowSnapshotId('canvas-2'))
  })
})
