import dagre from '@dagrejs/dagre'
import type { Edge, Node } from '@xyflow/react'

/**
 * Auto-layout for the flow canvas (@dagrejs/dagre, left-to-right layered
 * ranking). Node sizes are estimated from the rendered card bounds — exact
 * pixel fidelity is unnecessary for ranking, only separation matters. Sticky
 * notes participate as free nodes so they stay out of the executable lanes'
 * way; positions come back as top-left coordinates like xyflow expects.
 */

export interface AutoLayoutOptions {
  direction?: 'LR' | 'TB'
  rankSep?: number
  nodeSep?: number
}

/** Estimated card bounds per node type (see flow.css node card metrics). */
function nodeSize(node: Node): { width: number; height: number } {
  if (node.type === 'sticky') return { width: 200, height: 100 }
  if (node.type === 'start') return { width: 200, height: 48 }
  // Tool cards carry branch-port labels on control nodes — extra vertical room.
  return { width: 230, height: 72 }
}

/**
 * Lays out every node (tool/start/sticky) and returns new positions keyed by
 * node id. Nodes without a computed position keep their current one.
 */
export function layoutFlowGraph(
  nodes: Node[],
  edges: Edge[],
  options: AutoLayoutOptions = {},
): Record<string, { x: number; y: number }> {
  if (!nodes.length) return {}
  const graph = new dagre.graphlib.Graph()
  graph.setGraph({
    rankdir: options.direction ?? 'LR',
    ranksep: options.rankSep ?? 90,
    nodesep: options.nodeSep ?? 46,
    marginx: 24,
    marginy: 24,
    // Stable authoring: same graph → same picture.
    deterministic: true,
  })
  graph.setDefaultEdgeLabel(() => ({}))
  for (const node of nodes) {
    const size = nodeSize(node)
    graph.setNode(node.id, { ...size })
  }
  const known = new Set(nodes.map((node) => node.id))
  for (const edge of edges) {
    if (!known.has(edge.source) || !known.has(edge.target)) continue
    graph.setEdge(edge.source, edge.target)
  }
  dagre.layout(graph)
  const positions: Record<string, { x: number; y: number }> = {}
  for (const node of nodes) {
    const placed = graph.node(node.id)
    if (!placed || typeof placed.x !== 'number' || typeof placed.y !== 'number') continue
    const size = nodeSize(node)
    // dagre reports node centers; xyflow positions are top-left.
    positions[node.id] = {
      x: Math.round(placed.x - size.width / 2),
      y: Math.round(placed.y - size.height / 2),
    }
  }
  return positions
}
