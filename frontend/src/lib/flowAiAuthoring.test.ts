import { describe, expect, it } from 'vitest'
import {
  blockedProposalExclusions,
  diffFlowProposalDetail,
  excludeProposalNodes,
  flowProposalGraphProblems,
  parseFlowProposal,
  proposalGraphNodeTitle,
} from './flowAiAuthoring'
import { flowSnapshotId } from './flowGraph'

const graph = {
  nodes: [{ id: 'n1', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 'json_format' } }],
  edges: [],
}

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
    const diff = diffFlowProposalDetail({
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
    }).counts
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

  it('defaults applicability to true and honors an explicit false', () => {
    const applicable = parseFlowProposal(JSON.stringify({
      kind: 'flow_proposal', name: 'N', goal: 'G',
      inputSchema: { type: 'object' }, graph, summary: 's',
    }))
    expect(applicable?.applicable).toBe(true)

    const blocked = parseFlowProposal(JSON.stringify({
      kind: 'flow_proposal', name: 'N', goal: 'G',
      inputSchema: { type: 'object' }, graph, summary: 's',
      diagnostics: [{ severity: 'error', code: 'unavailable_tool', message: 'missing' }],
      applicable: false,
    }))
    expect(blocked?.applicable).toBe(false)
  })

  it('flags duplicate ids, dangling edge endpoints, and multiple start nodes', () => {
    expect(flowProposalGraphProblems({
      nodes: [
        { id: 'a', type: 'start', position: { x: 0, y: 0 }, data: {} },
        { id: 'a', type: 'start', position: { x: 1, y: 0 }, data: {} },
      ],
      edges: [{ id: 'e1', source: 'ghost', target: 'a' }],
    })).toEqual([
      'duplicate node id: a',
      'expected at most one start node, found 2',
      'edge source does not exist: ghost',
    ])
  })
})

describe('proposal detail diff and selective apply', () => {
  const current = {
    nodes: [
      { id: 'keep', type: 'tool' as const, position: { x: 0, y: 0 }, data: { toolName: 'a', title: 'Keeper' } },
      { id: 'remove', type: 'tool' as const, position: { x: 0, y: 1 }, data: { toolName: 'b' } },
    ],
    edges: [{ id: 'old', source: 'keep', target: 'remove' }],
  }
  const proposed = {
    nodes: [
      { id: 'keep', type: 'tool' as const, position: { x: 1, y: 0 }, data: { toolName: 'a', title: 'Keeper' } },
      { id: 'add1', type: 'tool' as const, position: { x: 2, y: 0 }, data: { toolName: 'c' } },
      { id: 'add2', type: 'note' as const, position: { x: 2, y: 9 }, data: { content: 'watch this' } },
    ],
    edges: [
      { id: 'new', source: 'keep', target: 'add1' },
      { id: 'new2', source: 'add1', target: 'add2' },
    ],
  }

  it('lists added/removed/changed nodes with display titles', () => {
    const detail = diffFlowProposalDetail(current, proposed)
    expect(detail.counts).toEqual({
      addedNodes: 2, removedNodes: 1, changedNodes: 1, addedEdges: 2, removedEdges: 1,
    })
    const byId = new Map(detail.nodes.map((change) => [change.id, change]))
    expect(byId.get('keep')).toMatchObject({ kind: 'changed', title: 'Keeper' })
    expect(byId.get('add1')).toMatchObject({ kind: 'added', title: 'C' })
    expect(byId.get('add2')).toMatchObject({ kind: 'added', title: 'watch this' })
    expect(byId.get('remove')).toMatchObject({ kind: 'removed' })
  })

  it('derives node titles from author title, tool name, then content/type', () => {
    expect(proposalGraphNodeTitle({ id: 'x', type: 'tool', position: { x: 0, y: 0 }, data: { toolName: 'excel_read_sheet' } }))
      .toBe('Excel read sheet')
    expect(proposalGraphNodeTitle({ id: 'x', type: 'start', position: { x: 0, y: 0 }, data: {} }))
      .toBe('Start')
    expect(proposalGraphNodeTitle({ id: 'x', type: 'tool', position: { x: 0, y: 0 }, data: {} }))
      .toBe('x')
  })

  it('blocks exclusions that a kept node still references', () => {
    // A self-reference inside the excluded node disappears with it — not blocked.
    const wired = {
      nodes: [
        { id: 'keep', type: 'tool' as const, position: { x: 0, y: 0 }, data: { toolName: 'a', title: 'Keeper' } },
        { id: 'add1', type: 'tool' as const, position: { x: 1, y: 0 }, data: { toolName: 'c', argsText: '{"x":"{{node.add1.result.y}}"}' } },
      ],
      edges: [],
    }
    expect(blockedProposalExclusions(wired, new Set(['add1']))).toEqual([])
    const referencing = {
      nodes: [
        { id: 'keep', type: 'tool' as const, position: { x: 0, y: 0 }, data: { toolName: 'a', title: 'Keeper', argsText: '{"x":"{{node.add2.result.y}}"}' } },
        { id: 'add2', type: 'tool' as const, position: { x: 1, y: 0 }, data: { toolName: 'c' } },
      ],
      edges: [],
    }
    expect(blockedProposalExclusions(referencing, new Set(['add2'])))
      .toEqual([{ excludedId: 'add2', referencedBy: 'Keeper' }])
    expect(blockedProposalExclusions(referencing, new Set())).toEqual([])
  })

  it('drops excluded nodes and every edge touching them', () => {
    const proposal = {
      kind: 'flow_proposal' as const,
      baseWorkflowId: null,
      baseRevision: null,
      baseSnapshotId: null,
      name: 'N', description: '', goal: 'G',
      inputSchema: {},
      graph: proposed,
      summary: 's',
      diagnostics: [],
      applicable: true,
    }
    const partial = excludeProposalNodes(proposal, new Set(['add1']))
    expect(partial.graph.nodes.map((node) => node.id)).toEqual(['keep', 'add2'])
    expect(partial.graph.edges).toEqual([])
    // No exclusions → the same proposal object passes through.
    expect(excludeProposalNodes(proposal, new Set())).toBe(proposal)
  })
})
