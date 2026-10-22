import { useMemo } from 'react'
import type { AgentTool, FlowGraph } from '@/services/types'
import {
  WORKFLOW_NODE_DEFAULT_COLOR,
  WORKFLOW_TOOL_NODE_COLORS,
  workflowToolCategory,
} from '@/lib/flowDisplay'

/**
 * The spine-card micro-topology: a saved flow's REAL persisted graph redrawn
 * as a honey-thread diagram on a hive chip (structure at a glance — the shape
 * is the author's own layout, scaled to fit). Falls back to nothing when the
 * definition has no graph; the caller renders a plain icon instead.
 */

const W = 106
const H = 66
const PAD = 7

interface MicroNode {
  id: string
  x: number
  y: number
  start: boolean
  color: string
}

export function flowHasDrawableGraph(graph: FlowGraph | null | undefined): graph is FlowGraph {
  // Pre-graph definitions come back from the backend as `{}` (readMapOrEmpty normalizes
  // null) — truthy with NO nodes key, so the nodes check must be shape-based.
  return Array.isArray(graph?.nodes) && graph.nodes.some((node) => node.type !== 'note')
}

export function FlowMicroGraph(props: { graph: FlowGraph; toolsByName: Map<string, AgentTool> }) {
  const drawn = useMemo(() => {
    const kept = (props.graph.nodes ?? []).filter((node) => node.type !== 'note')
    const nodes: MicroNode[] = kept.map((node) => {
      const data = (node.data ?? {}) as { toolName?: unknown }
      const toolName = typeof data.toolName === 'string' ? data.toolName : undefined
      const tool = toolName ? props.toolsByName.get(toolName) : undefined
      const color = node.type === 'start' ? ''
        : tool
          ? (tool.flowNode?.color
            ?? WORKFLOW_TOOL_NODE_COLORS[workflowToolCategory(tool)]
            ?? WORKFLOW_NODE_DEFAULT_COLOR)
          : WORKFLOW_NODE_DEFAULT_COLOR
      return {
        id: node.id,
        x: Number(node.position?.x) || 0,
        y: Number(node.position?.y) || 0,
        start: node.type === 'start',
        color,
      }
    })
    const byId = new Map(nodes.map((node) => [node.id, node]))
    const edges = (props.graph.edges ?? [])
      .map((edge) => ({ from: byId.get(edge.source), to: byId.get(edge.target) }))
      .filter((edge): edge is { from: MicroNode; to: MicroNode } => !!edge.from && !!edge.to)
    // Scale the author's coordinates into the chip, preserving the shape.
    // Scale is capped at 1 (never amplified) so degenerate 1-node graphs
    // center instead of blowing up to the reserved extent.
    const xs = nodes.map((node) => node.x)
    const ys = nodes.map((node) => node.y)
    const minX = Math.min(...xs, 0)
    const minY = Math.min(...ys, 0)
    const spanX = Math.max(...xs, minX) - minX || 1
    const spanY = Math.max(...ys, minY) - minY || 1
    const scale = Math.min(1, (W - PAD * 2 - 14) / spanX, (H - PAD * 2 - 10) / spanY)
    const offsetX = (W - spanX * scale - 14) / 2
    const offsetY = (H - spanY * scale - 10) / 2
    const place = (node: MicroNode) => ({
      ...node,
      cx: offsetX + PAD + (node.x - minX) * scale,
      cy: offsetY + PAD + (node.y - minY) * scale,
    })
    return { nodes: nodes.map(place), edges: edges.map((edge) => ({ from: place(edge.from), to: place(edge.to) })) }
  }, [props.graph, props.toolsByName])

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} aria-hidden="true">
      {drawn.edges.map((edge, index) => (
        <line
          key={index}
          x1={edge.from.cx + 7}
          y1={edge.from.cy}
          x2={edge.to.cx - 7}
          y2={edge.to.cy}
          stroke="var(--fy-hive-honey)"
          strokeOpacity={0.5}
          strokeWidth={1.2}
        />
      ))}
      {drawn.nodes.map((node) => node.start ? (
        <rect
          key={node.id}
          x={node.cx - 4}
          y={node.cy - 4}
          width={8}
          height={8}
          rx={2.5}
          fill="none"
          stroke="var(--fy-hive-ink-faint)"
          strokeWidth={1.2}
        />
      ) : (
        <rect
          key={node.id}
          x={node.cx - 7}
          y={node.cy - 5}
          width={14}
          height={10}
          rx={3}
          fill="none"
          stroke={node.color}
          strokeWidth={1.2}
        />
      ))}
    </svg>
  )
}
