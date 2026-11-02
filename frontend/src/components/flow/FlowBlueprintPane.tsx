import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Play, X } from 'lucide-react'
import type { FlowAuthoringProposal, FlowGraph } from '@/services/types'
import { proposalGraphNodeTitle } from '@/lib/flowAiAuthoring'

/**
 * Hive drafting board for the copilot page: the latest AI proposal's
 * wire graph as a static honey-path diagram (left-to-right layers by reachability
 * from the roots). Nodes the user unchecked in the chat diff dim out and dash —
 * the pane mirrors exactly what "apply" would create. Nodes are clickable: the
 * detail card under the stage shows that node's configuration (args with their
 * {{...}} references; the start node shows the declared inputs). Applying stays
 * in the conversation where the diff lives; a trial run (inline plan, nothing
 * saved) can be fired from the header.
 */

const COL_W = 178
const ROW_H = 74
const BOX_H = 42
const BOX_W_MIN = 96
const BOX_W_MAX = 168
const PAD = 14

/** Rough CJK/fullwidth test — those glyphs advance ~2x a Latin one at the same size. */
const CJK_RE = /[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]/

function textWidth(text: string, latinW: number, cjkW: number): number {
  let width = 0
  for (const ch of text) width += CJK_RE.test(ch) ? cjkW : latinW
  return width
}

/** SVG text has no ellipsis — slice by the same width model and add "…". */
function fitText(text: string, maxW: number, latinW: number, cjkW: number): string {
  if (textWidth(text, latinW, cjkW) <= maxW) return text
  let out = ''
  let width = 0
  for (const ch of text) {
    const charW = CJK_RE.test(ch) ? cjkW : latinW
    if (width + charW + cjkW > maxW) break
    out += ch
    width += charW
  }
  return `${out}…`
}

interface PlacedNode {
  id: string
  title: string
  kind: string
  start: boolean
  x: number
  y: number
  w: number
}

interface BlueprintLayout {
  nodes: PlacedNode[]
  edges: Array<{ from: PlacedNode; to: PlacedNode; excluded: boolean }>
  width: number
  height: number
  branches: number
}

function layoutBlueprint(graph: FlowGraph, excluded: Set<string> | undefined): BlueprintLayout {
  const kept = graph.nodes.filter((node) => node.type !== 'note')
  const keptIds = new Set(kept.map((node) => node.id))
  const edges = graph.edges.filter((edge) => keptIds.has(edge.source) && keptIds.has(edge.target))

  // Depth = BFS from roots (no incoming edge); cycles fall back to max depth + 1.
  const incoming = new Map<string, number>()
  const outgoing = new Map<string, string[]>()
  for (const edge of edges) {
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1)
    const list = outgoing.get(edge.source) ?? []
    list.push(edge.target)
    outgoing.set(edge.source, list)
  }
  const depth = new Map<string, number>()
  const queue = kept.filter((node) => !incoming.has(node.id)).map((node) => node.id)
  // Pure-cycle graphs have no roots: seed BFS from one node so its members
  // still lay out as reachable layers instead of all falling to the last column.
  if (!queue.length && kept.length) queue.push(kept[0].id)
  const seeds = [...queue]
  seeds.forEach((id) => depth.set(id, 0))
  for (let cursor = 0; cursor < queue.length; cursor++) {
    const id = queue[cursor]
    for (const next of outgoing.get(id) ?? []) {
      if (depth.has(next)) continue
      depth.set(next, (depth.get(id) ?? 0) + 1)
      queue.push(next)
    }
  }
  let columns = 0
  for (const node of kept) {
    const d = depth.get(node.id)
    if (d === undefined) depth.set(node.id, kept.length) // cycle member: last column
    columns = Math.max(columns, depth.get(node.id) ?? 0)
  }
  columns += 1

  const byColumn = new Map<number, string[]>()
  for (const node of kept) {
    const d = depth.get(node.id) ?? 0
    const list = byColumn.get(d) ?? []
    list.push(node.id)
    byColumn.set(d, list)
  }
  const maxRows = Math.max(1, ...[...byColumn.values()].map((list) => list.length))

  const placed = new Map<string, PlacedNode>()
  for (const [d, ids] of byColumn) {
    ids.forEach((id, index) => {
      const node = kept.find((item) => item.id === id)!
      const title = proposalGraphNodeTitle(node)
      const kind = String(((node.data ?? {}) as { toolName?: unknown }).toolName ?? node.type)
      // Box width fits BOTH lines under a CJK-aware width model (labels ~11px,
      // mono kind ~9px); long lines ellipsize like the canvas cards do.
      const titleW = textWidth(title, 6.4, 11)
      const kindW = textWidth(kind, 5.5, 9)
      const w = Math.max(BOX_W_MIN, Math.min(BOX_W_MAX, Math.round(Math.max(titleW, kindW) + 24)))
      placed.set(id, {
        id,
        title: fitText(title, w - 22, 6.4, 11),
        kind: fitText(kind, w - 22, 5.5, 9),
        start: node.type === 'start',
        x: PAD + d * COL_W,
        y: PAD + index * ROW_H + (maxRows - (byColumn.get(d)?.length ?? 1)) * (ROW_H / 2),
        w,
      })
    })
  }

  const width = PAD * 2 + columns * COL_W
  const height = PAD * 2 + maxRows * ROW_H
  const drawnEdges = edges
    .map((edge) => {
      const from = placed.get(edge.source)
      const to = placed.get(edge.target)
      return from && to
        ? { from, to, excluded: !!(excluded?.has(edge.source) || excluded?.has(edge.target)) }
        : null
    })
    .filter((edge): edge is NonNullable<typeof edge> => edge !== null)
  const branches = [...outgoing.values()].filter((targets) => targets.length > 1).length
  return { nodes: [...placed.values()], edges: drawnEdges, width, height, branches }
}

export function FlowBlueprintPane(props: {
  proposal: FlowAuthoringProposal | null
  excluded?: Set<string>
  /** Opens the trial-run dialog (inline plan execution; nothing is saved). */
  onTrialRun?: () => void
}) {
  const { t } = useTranslation()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const layout = useMemo(
    () => (props.proposal ? layoutBlueprint(props.proposal.graph, props.excluded) : null),
    [props.proposal, props.excluded],
  )
  const excludedSet = props.excluded
  // "Compiles" is only claimed when the proposal itself carries no blocking
  // diagnostics (applicable === false or any diagnostic → review count instead).
  const issues = props.proposal?.diagnostics?.length ?? 0
  const compiles = !!props.proposal && props.proposal.applicable !== false && issues === 0
  // A notes-only/empty proposal renders as the empty board, chips included.
  const hasNodes = !!layout && layout.nodes.length > 0
  // A new proposal invalidates the old selection (ids rarely survive a re-draft).
  const selectedNode = useMemo(
    () => (selectedId && props.proposal
      ? props.proposal.graph.nodes.find((node) => node.id === selectedId) ?? null
      : null),
    [selectedId, props.proposal],
  )
  const select = (id: string) => setSelectedId((current) => (current === id ? null : id))

  return (
    <aside className="flow-blueprint" aria-label={t('flows.blueprintTitle')}>
      <header className="flow-blueprint__head">
        <strong>{t('flows.blueprintTitle')}</strong>
        {hasNodes && (
          compiles ? (
            <span className="flow-blueprint__ok">
              {t('flows.blueprintShape', { nodes: layout!.nodes.length, branches: layout!.branches })}
            </span>
          ) : (
            <span className="flow-blueprint__ok flow-blueprint__ok--issues">
              {t('flows.blueprintIssues', { count: Math.max(issues, 1), nodes: layout!.nodes.length })}
            </span>
          )
        )}
        {hasNodes && props.onTrialRun && (
          <button
            className="flow-blueprint__run"
            disabled={!compiles}
            title={compiles ? t('flows.blueprintRunHint') : t('flows.blueprintRunBlocked')}
            onClick={props.onTrialRun}
          >
            <Play size={12.5} /> {t('agent.testRun')}
          </button>
        )}
        {hasNodes && (
          <span className="flow-blueprint__hint">
            <span className="flow-blueprint__dash" aria-hidden="true" />
            {t('flows.blueprintLegend')}
          </span>
        )}
      </header>
      <div className="flow-blueprint__stage">
        {hasNodes ? (
          <svg
            width={Math.max(layout.width, 240)}
            height={layout.height}
            viewBox={`0 0 ${Math.max(layout.width, 240)} ${layout.height}`}
            role="img"
            aria-label={t('flows.blueprintTitle')}
            onClick={(event) => {
              if (event.target === event.currentTarget) setSelectedId(null)
            }}
          >
            {layout.edges.map((edge, index) => {
              const x1 = edge.from.x + edge.from.w
              const y1 = edge.from.y + BOX_H / 2
              const x2 = edge.to.x
              const y2 = edge.to.y + BOX_H / 2
              const midX = (x1 + x2) / 2
              return (
                <path
                  key={index}
                  d={`M${x1} ${y1} C${midX} ${y1} ${midX} ${y2} ${x2} ${y2}`}
                  className={`flow-blueprint__edge${edge.excluded ? ' flow-blueprint__edge--excluded' : ''}`}
                />
              )
            })}
            {layout.nodes.map((node) => {
              const isExcluded = !!excludedSet?.has(node.id)
              const isSelected = node.id === selectedId
              return (
                <g
                  key={node.id}
                  opacity={isExcluded ? 0.38 : 1}
                  tabIndex={0}
                  role="button"
                  aria-label={node.start ? t('agent.startNodeTitle') : node.title}
                  className="flow-blueprint__hit"
                  onClick={(event) => {
                    event.stopPropagation()
                    select(node.id)
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      select(node.id)
                    }
                  }}
                >
                  <rect
                    x={node.x}
                    y={node.y}
                    width={node.w}
                    height={BOX_H}
                    rx={9}
                    className={`flow-blueprint__node${isExcluded ? ' flow-blueprint__node--excluded' : ''}${isSelected ? ' flow-blueprint__node--selected' : ''}`}
                  />
                  <text x={node.x + 11} y={node.y + 19} className="flow-blueprint__nodelabel">
                    {node.start ? t('agent.startNodeTitle') : node.title}
                  </text>
                  <text x={node.x + 11} y={node.y + 33} className="flow-blueprint__nodekind">
                    {node.start ? 'input' : node.kind}
                  </text>
                </g>
              )
            })}
          </svg>
        ) : (
          <div className="flow-blueprint__empty">{t('flows.blueprintEmpty')}</div>
        )}
      </div>
      {selectedNode && (
        <NodeConfigCard
          node={selectedNode}
          inputSchema={props.proposal?.inputSchema}
          excluded={!!excludedSet?.has(selectedNode.id)}
          onClose={() => setSelectedId(null)}
        />
      )}
    </aside>
  )
}

/** One config row value: {{...}} references render in the honey ref style. */
function formatArgValue(value: unknown): { text: string; isRef: boolean } {
  if (typeof value === 'string') return { text: value, isRef: value.includes('{{') }
  if (value === null || value === undefined) return { text: '—', isRef: false }
  if (typeof value === 'object') return { text: JSON.stringify(value), isRef: false }
  return { text: String(value), isRef: false }
}

/** Read-only configuration card for the selected blueprint node. */
function NodeConfigCard(props: {
  node: FlowGraph['nodes'][number]
  inputSchema: Record<string, unknown> | undefined
  excluded: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const data = (props.node.data ?? {}) as {
    toolName?: string
    argsText?: string
    description?: string
    requiresApproval?: boolean
    title?: string
  }
  const args = useMemo(() => {
    if (!data.argsText) return []
    try {
      const parsed = JSON.parse(data.argsText) as Record<string, unknown>
      return Object.entries(parsed).map(([name, value]) => ({ name, ...formatArgValue(value) }))
    } catch {
      return []
    }
  }, [data.argsText])
  const inputFields = useMemo(() => {
    if (props.node.type !== 'start') return []
    const properties = (props.inputSchema?.properties ?? {}) as Record<string, Record<string, unknown>>
    const required = new Set(Array.isArray(props.inputSchema?.required)
      ? (props.inputSchema.required as string[])
      : [])
    return Object.entries(properties).map(([name, property]) => ({
      name,
      type: typeof property.type === 'string' ? property.type : 'any',
      required: required.has(name),
      description: typeof property.description === 'string' ? property.description : '',
    }))
  }, [props.node.type, props.inputSchema])

  return (
    <section className={`flow-blueprint__detail${props.excluded ? ' flow-blueprint__detail--excluded' : ''}`} aria-label={t('flows.blueprintConfig')}>
      <header className="flow-blueprint__detail-head">
        <strong>{props.node.type === 'start' ? t('agent.startNodeTitle') : proposalGraphNodeTitle(props.node)}</strong>
        {props.node.type === 'tool' && data.toolName && (
          <code className="flow-blueprint__tool">{data.toolName}</code>
        )}
        {data.requiresApproval && (
          <span className="flow-blueprint__flag">{t('agent.requiresApproval')}</span>
        )}
        <button
          className="cx-iconbtn cx-iconbtn--sm"
          aria-label={t('flows.close')}
          onClick={props.onClose}
        >
          <X size={14} />
        </button>
      </header>
      {data.description && <p className="flow-blueprint__detail-desc">{data.description}</p>}
      {inputFields.length > 0 && (
        <div className="flow-blueprint__cfg">
          <p className="flow-blueprint__cfg-title">{t('flows.blueprintInputs')}</p>
          {inputFields.map((field) => (
            <div key={field.name} className="flow-blueprint__cfg-row">
              <span className="flow-blueprint__cfg-name">
                {field.name}{field.required ? ' *' : ''}
              </span>
              <span className="flow-blueprint__cfg-value">
                {field.type}
                {field.description ? <small> — {field.description}</small> : null}
              </span>
            </div>
          ))}
        </div>
      )}
      {props.node.type === 'tool' && (
        <div className="flow-blueprint__cfg">
          <p className="flow-blueprint__cfg-title">{t('flows.blueprintConfig')}</p>
          {args.length === 0 && (
            <p className="flow-blueprint__cfg-empty">{t('flows.blueprintNoArgs')}</p>
          )}
          {args.map((arg) => (
            <div key={arg.name} className="flow-blueprint__cfg-row">
              <span className="flow-blueprint__cfg-name">{arg.name}</span>
              <span className={`flow-blueprint__cfg-value${arg.isRef ? ' flow-blueprint__cfg-ref' : ''}`}>
                {arg.text}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
