import { createContext, useContext, type ComponentType, type CSSProperties } from 'react'
import { useTranslation } from 'react-i18next'
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import { Cog, Play, StickyNote, Wrench } from 'lucide-react'
import type { AgentTool } from '@/services/types'
import { flowNodeTitle } from '@/lib/flowDisplay'

/**
 * Custom canvas nodes for the flow builder: start (run-form inputs), tool
 * (name + argument JSON), sticky (annotation note). Display-only runtime state
 * (live run badges, tool catalog) rides React contexts so the persisted node
 * data stays wire-shaped. Dynamic node tints travel as a CSS custom property —
 * all layout lives in flow.css classes.
 */

/** Live per-node run status (running / retrying / complete / failed / skipped). */
export const FlowRunStatusContext = createContext<Record<string, string>>({})

/** Live tool catalog by name — resolves labels/colors for tool nodes. */
export const FlowToolCatalogContext = createContext<Map<string, AgentTool>>(new Map())

export function useFlowRunStatus(nodeId: string): string | null {
  return useContext(FlowRunStatusContext)[nodeId] ?? null
}

export function useFlowTool(name: string): AgentTool | null {
  return useContext(FlowToolCatalogContext).get(name) ?? null
}

function StatusBadge({ status }: { status: string }) {
  return <span className={`flow-node-badge flow-node-badge--${status}`} aria-label={status} />
}

export type StartNodeData = { title?: string }
export type ToolNodeData = {
  toolName: string
  argsText: string
  description: string
  requiresApproval: boolean
  title?: string
  available?: boolean
  color?: string
}
export type StickyNodeData = { content: string; color: string }

export function StartNodeCard({ data, selected }: NodeProps<Node<StartNodeData>>) {
  const { t } = useTranslation()
  return (
    <div className={`flow-start-node${selected ? ' flow-node--selected' : ''}`}>
      <Handle type="source" position={Position.Right} className="flow-handle" />
      <span className="flow-start-node__icon"><Play size={14} strokeWidth={2.4} /></span>
      <span className="flow-start-node__body">
        <strong>{data.title || t('agent.startNodeTitle')}</strong>
        <small className="cx-muted">{t('agent.startDesignerTitle')}</small>
      </span>
    </div>
  )
}

export function ToolNodeCard({ id, data, selected }: NodeProps<Node<ToolNodeData>>) {
  const status = useFlowRunStatus(id)
  const tool = useFlowTool(data.toolName)
  const unavailable = data.available === false
  return (
    <div
      className={`flow-tool-node${selected ? ' flow-node--selected' : ''}${unavailable ? ' flow-tool-node--missing' : ''}`}
      style={data.color ? ({ '--flow-node-tint': data.color } as CSSProperties) : undefined}
    >
      <Handle type="target" position={Position.Left} className="flow-handle" />
      <Handle type="source" position={Position.Right} className="flow-handle" />
      {status ? <StatusBadge status={status} /> : null}
      <span className="flow-tool-node__icon">
        {tool?.flowNode?.kind === 'control'
          ? <Cog size={14} strokeWidth={2.2} />
          : <Wrench size={14} strokeWidth={2.2} />}
      </span>
      <span className="flow-tool-node__body">
        <strong>{flowNodeTitle(data, tool)}</strong>
        <small className="cx-muted" title={data.toolName}>{data.toolName}</small>
      </span>
      {unavailable ? <span className="flow-tool-node__missing-tag" title={data.toolName}>!</span> : null}
    </div>
  )
}

export function StickyNoteCard({ id, data, selected }: NodeProps<Node<StickyNodeData>>) {
  const status = useFlowRunStatus(id)
  return (
    <div className={`flow-sticky flow-sticky--${data.color}${selected ? ' flow-node--selected' : ''}`}>
      {status ? <StatusBadge status={status} /> : null}
      <span className="flow-sticky__icon"><StickyNote size={13} strokeWidth={2.2} /></span>
      <p className="flow-sticky__content">{data.content || ' '}</p>
    </div>
  )
}

/** The nodeTypes map handed to ReactFlow. */
export const flowNodeTypes: Record<string, ComponentType<NodeProps>> = {
  start: StartNodeCard as ComponentType<NodeProps>,
  tool: ToolNodeCard as ComponentType<NodeProps>,
  sticky: StickyNoteCard as ComponentType<NodeProps>,
}
