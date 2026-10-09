import type { FlowAuthoringProposal, FlowGraph } from '@/services/types'
import { humanizeWorkflowField } from '@/lib/flowDisplay'

/**
 * AI flow-authoring proposal plumbing (port of the Vue flowAiAuthoring.ts): parse the
 * canonical `edit_current_flow` preview envelope, diff it against the live canvas, and
 * gate it structurally before the builder may touch history or canvas.
 */

export interface FlowProposalDiff {
  addedNodes: number
  removedNodes: number
  changedNodes: number
  addedEdges: number
  removedEdges: number
}

export interface ProposalNodeChange {
  id: string
  title: string
  kind: 'added' | 'removed' | 'changed'
}

/** Node-level diff of a proposal against the live canvas: counts + titled changes. */
export interface FlowProposalDiffDetail {
  counts: FlowProposalDiff
  /** Added/removed/changed nodes with display titles, stable-ordered by id. */
  nodes: ProposalNodeChange[]
}

/** Best-effort display title of a wire-graph node (author title > tool > type). */
export function proposalGraphNodeTitle(node: FlowGraph['nodes'][number]): string {
  const data = (node.data ?? {}) as { title?: unknown; toolName?: unknown; content?: unknown }
  if (typeof data.title === 'string' && data.title) return data.title
  if (typeof data.toolName === 'string' && data.toolName) return humanizeWorkflowField(data.toolName)
  if (typeof data.content === 'string' && data.content.trim()) {
    return data.content.trim().slice(0, 24)
  }
  return node.type === 'start' ? 'Start' : node.type === 'note' ? 'Note' : node.id
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

/** Node-level detail diff: which nodes changed, with titles. */
export function diffFlowProposalDetail(current: FlowGraph, proposed: FlowGraph): FlowProposalDiffDetail {
  const currentNodes = new Map(current.nodes.map((node) => [node.id, stable(node)]))
  const proposedEdges = new Set(proposed.edges.map(edgeKey))
  const currentEdges = new Set(current.edges.map(edgeKey))
  const nodes: ProposalNodeChange[] = []
  const ordered = [...proposed.nodes].sort((left, right) => left.id.localeCompare(right.id))
  for (const node of ordered) {
    if (!currentNodes.has(node.id)) {
      nodes.push({ id: node.id, title: proposalGraphNodeTitle(node), kind: 'added' })
    } else if (currentNodes.get(node.id) !== stable(node)) {
      nodes.push({ id: node.id, title: proposalGraphNodeTitle(node), kind: 'changed' })
    }
  }
  for (const node of [...current.nodes].sort((left, right) => left.id.localeCompare(right.id))) {
    if (!proposed.nodes.some((candidate) => candidate.id === node.id)) {
      nodes.push({ id: node.id, title: proposalGraphNodeTitle(node), kind: 'removed' })
    }
  }
  const count = (kind: ProposalNodeChange['kind']) => nodes.filter((change) => change.kind === kind).length
  return {
    counts: {
      addedNodes: count('added'),
      removedNodes: count('removed'),
      changedNodes: count('changed'),
      addedEdges: countMissing(proposedEdges, currentEdges),
      removedEdges: countMissing(currentEdges, proposedEdges),
    },
    nodes,
  }
}

export interface BlockedExclusion {
  /** The added node the user wants to exclude. */
  excludedId: string
  /** Title of the kept node whose args still reference it. */
  referencedBy: string
}

/**
 * Which requested exclusions are unsafe: a KEPT node's args reference the
 * excluded node through `{{node.<id>…}}`. The compiler throws on unknown ids,
 * so those exclusions must be refused (or the reference manually re-pointed)
 * before a partial apply.
 */
export function blockedProposalExclusions(
  proposed: FlowGraph,
  excludedIds: Set<string>,
): BlockedExclusion[] {
  if (!excludedIds.size) return []
  const blocked: BlockedExclusion[] = []
  const NODE_REFERENCE = /\{\{node\.([A-Za-z0-9_-]+)\.(?:result|input)(?:\.[A-Za-z0-9_-]+|\[\d+])*\}\}/g
  for (const node of proposed.nodes) {
    if (excludedIds.has(node.id)) continue
    const argsText = (node.data as { argsText?: unknown } | undefined)?.argsText
    if (typeof argsText !== 'string' || !argsText) continue
    for (const match of argsText.matchAll(NODE_REFERENCE)) {
      if (excludedIds.has(match[1]!)) {
        blocked.push({ excludedId: match[1]!, referencedBy: proposalGraphNodeTitle(node) })
        break
      }
    }
  }
  return blocked
}

/**
 * Drops the excluded ADDED nodes (and every edge touching them) from a
 * proposal, producing the partial graph a selective apply mounts. Metadata,
 * kept nodes, and their edges pass through untouched.
 */
export function excludeProposalNodes(
  proposal: FlowAuthoringProposal,
  excludedIds: Set<string>,
): FlowAuthoringProposal {
  if (!excludedIds.size) return proposal
  return {
    ...proposal,
    graph: {
      ...proposal.graph,
      nodes: proposal.graph.nodes.filter((node) => !excludedIds.has(node.id)),
      edges: proposal.graph.edges.filter((edge) =>
        !excludedIds.has(edge.source) && !excludedIds.has(edge.target)),
    },
  }
}

/**
 * Structural gates an AI proposal must pass before the builder may touch history or canvas:
 * globally unique node ids (the flow canvas keys nodes by id), edges whose endpoints exist,
 * and at most one Start node. Returns the problems; an empty list means the graph is
 * mountable.
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
