import type {
  FlowAuthoringProposal,
  FlowGraph,
} from '@/api/types'

export interface FlowProposalDiff {
  addedNodes: number
  removedNodes: number
  changedNodes: number
  addedEdges: number
  removedEdges: number
}

/** Stable lightweight fingerprint used to reject proposals after the live canvas changes. */
export function flowSnapshotId(serializedCanvas: string): string {
  let hash = 0x811c9dc5
  for (let index = 0; index < serializedCanvas.length; index += 1) {
    hash ^= serializedCanvas.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return `v1-${(hash >>> 0).toString(16).padStart(8, '0')}`
}

/** Parse only the canonical preview envelope emitted by edit_current_flow. */
export function parseFlowProposal(output: string | undefined): FlowAuthoringProposal | null {
  if (!output) return null
  try {
    const value = JSON.parse(output) as Partial<FlowAuthoringProposal>
    if (value.kind !== 'flow_proposal'
      || typeof value.name !== 'string'
      || typeof value.goal !== 'string'
      || !value.inputSchema || typeof value.inputSchema !== 'object' || Array.isArray(value.inputSchema)
      || !validGraph(value.graph)) return null
    return {
      kind: 'flow_proposal',
      baseWorkflowId: typeof value.baseWorkflowId === 'string' ? value.baseWorkflowId : null,
      baseRevision: typeof value.baseRevision === 'number' ? value.baseRevision : null,
      baseSnapshotId: typeof value.baseSnapshotId === 'string' ? value.baseSnapshotId : null,
      name: value.name,
      description: typeof value.description === 'string' ? value.description : '',
      goal: value.goal,
      inputSchema: value.inputSchema as Record<string, unknown>,
      graph: value.graph,
      summary: typeof value.summary === 'string' && value.summary.trim()
        ? value.summary : 'AI Flow proposal',
      diagnostics: Array.isArray(value.diagnostics) ? value.diagnostics : [],
      applicable: value.applicable !== false,
    }
  } catch {
    return null
  }
}

export function diffFlowProposal(current: FlowGraph, proposed: FlowGraph): FlowProposalDiff {
  const currentNodes = new Map(current.nodes.map((node) => [node.id, stable(node)]))
  const proposedNodes = new Map(proposed.nodes.map((node) => [node.id, stable(node)]))
  const currentEdges = new Set(current.edges.map(edgeKey))
  const proposedEdges = new Set(proposed.edges.map(edgeKey))
  let changedNodes = 0
  for (const [id, value] of proposedNodes) {
    if (currentNodes.has(id) && currentNodes.get(id) !== value) changedNodes += 1
  }
  return {
    addedNodes: countMissing(proposedNodes.keys(), currentNodes),
    removedNodes: countMissing(currentNodes.keys(), proposedNodes),
    changedNodes,
    addedEdges: countMissing(proposedEdges, currentEdges),
    removedEdges: countMissing(currentEdges, proposedEdges),
  }
}

/**
 * Structural gates an AI proposal must pass before the builder may touch history or canvas:
 * globally unique node ids (Vue Flow keys nodes by id), edges whose endpoints exist, and at
 * most one Start node. Returns the problems; an empty list means the graph is mountable.
 */
export function flowProposalGraphProblems(graph: FlowGraph): string[] {
  const problems: string[] = []
  const ids = new Set<string>()
  let starts = 0
  for (const node of graph.nodes) {
    if (ids.has(node.id)) problems.push(`duplicate node id: ${node.id}`)
    else ids.add(node.id)
    if (node.type === 'start') starts += 1
  }
  if (starts > 1) problems.push(`expected at most one start node, found ${starts}`)
  for (const edge of graph.edges) {
    if (!ids.has(edge.source)) problems.push(`edge source does not exist: ${edge.source}`)
    if (!ids.has(edge.target)) problems.push(`edge target does not exist: ${edge.target}`)
  }
  return problems
}

function validGraph(value: unknown): value is FlowGraph {
  if (!value || typeof value !== 'object') return false
  const graph = value as Partial<FlowGraph>
  if (!Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) return false
  return graph.nodes.every((node) => !!node && typeof node.id === 'string'
      && typeof node.type === 'string' && !!node.position
      && Number.isFinite(node.position.x) && Number.isFinite(node.position.y))
    && graph.edges.every((edge) => !!edge && typeof edge.source === 'string'
      && typeof edge.target === 'string')
}

function edgeKey(edge: FlowGraph['edges'][number]): string {
  return `${edge.source}\u0000${edge.target}\u0000${edge.sourceHandle ?? ''}`
}

function countMissing(values: Iterable<string>, target: Map<string, unknown> | Set<string>): number {
  let count = 0
  for (const value of values) if (!target.has(value)) count += 1
  return count
}

function stable(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`
  if (value && typeof value === 'object') {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, item]) => `${JSON.stringify(key)}:${stable(item)}`).join(',')}}`
  }
  return JSON.stringify(value)
}
