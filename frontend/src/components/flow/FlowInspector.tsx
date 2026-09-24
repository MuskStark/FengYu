import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Copy, Play, Pin, PinOff, Trash2, X } from 'lucide-react'
import type { Edge } from '@xyflow/react'
import type { AgentTool } from '@/services/types'
import { humanizeToolName } from '@/lib/flowDisplay'
import {
  flowTypeColor,
  formatNodeReference,
  collectNodeReferences,
  effectiveFlowInputSchema,
  normalizeFlowType,
  orderedInputEntries,
  parseNodeReference,
  referencePathExists,
  resolveOutputPath,
  workflowInputTree,
  workflowNodeTitle,
  workflowOutputTree,
  type FlowOutputField,
  type SchemaProperty,
} from '@/lib/flowInspectorModel'
import {
  buildInputSchemaText,
  isStickyNode,
  isToolNode,
  missingRequiredNodeInputs,
  parseInputSchemaFields,
  parseJsonObject,
  STICKY_COLORS,
  type FlowCanvasNode,
  type FlowInputField,
  type FlowStickyColor,
  type FlowToolData,
} from '@/lib/flowGraph'
import { FlowVariableTree, type VariableTreeSelection } from './FlowVariableTree'

/**
 * Right-rail inspector for the selected node (schema-driven tool editing is the
 * Vue FlowNodeInspector port): every input has a three-state SOURCE control —
 * manual entry, a reference picked from the upstream variable tree (which
 * auto-creates the edge), or a raw expression. Outputs and upstream data are
 * previewable with declared → example → last-run degradation, the last run can
 * be pinned (compiled to `pinnedResult`), and retry-safe tools take a bounded
 * retry policy.
 */

const FIELD_TYPE_OPTIONS: Array<FlowInputField['type']> = ['string', 'number', 'boolean', 'object', 'array']

const FIELD_TYPE_KEYS: Record<FlowInputField['type'], string> = {
  string: 'agent.inputTypeString',
  number: 'agent.inputTypeNumber',
  boolean: 'agent.inputTypeBoolean',
  object: 'agent.inputTypeObject',
  array: 'agent.inputTypeArray',
}

interface InputSchema {
  type?: string
  title?: string
  description?: string
  default?: unknown
  format?: string
  enum?: unknown[]
  required?: string[]
  properties?: Record<string, InputSchema>
  items?: InputSchema
  examples?: unknown[]
  'x-fengyu-advanced'?: boolean
  'x-fengyu-multiline'?: boolean
  'x-fengyu-json-editor'?: boolean
  'x-fengyu-file-access'?: 'read' | 'read-write'
}

export function FlowInspector(props: {
  node: FlowCanvasNode
  nodes: FlowCanvasNode[]
  edges: Edge[]
  toolsByName: Map<string, AgentTool>
  inputSchemaText: string
  disabled?: boolean
  onPatchTool: (nodeId: string, patch: Partial<FlowToolData>) => void
  onPatchSticky: (nodeId: string, patch: { content?: string; color?: FlowStickyColor }) => void
  onPatchInputSchema: (schemaText: string) => void
  onLink: (sourceId: string, targetId: string) => void
  onRunNode: (nodeId: string) => void
  onDelete: (nodeId: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { node } = props

  const title = isToolNode(node)
    ? workflowNodeTitle(node.data, props.toolsByName.get(node.data.toolName))
    : isStickyNode(node)
      ? t('agent.toolCategory.content')
      : t('agent.startNodeTitle')

  return (
    <div className="flow-inspector">
      <div className="flow-inspector__head">
        <span className="flow-inspector__title">{title}</span>
        <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>
      </div>
      {isToolNode(node) && (
        <ToolInspector
          node={node}
          nodes={props.nodes}
          edges={props.edges}
          toolsByName={props.toolsByName}
          inputSchemaText={props.inputSchemaText}
          disabled={props.disabled}
          onPatchTool={props.onPatchTool}
          onLink={props.onLink}
          onRunNode={props.onRunNode}
          onDelete={props.onDelete}
        />
      )}
      {isStickyNode(node) && (
        <StickyInspector
          node={node}
          disabled={props.disabled}
          onPatchSticky={props.onPatchSticky}
          onDelete={props.onDelete}
        />
      )}
      {!isToolNode(node) && !isStickyNode(node) && (
        <StartInspector
          inputSchemaText={props.inputSchemaText}
          disabled={props.disabled}
          onChange={props.onPatchInputSchema}
        />
      )}
    </div>
  )
}

type SourceKind = 'manual' | 'ref' | 'expression'

/** Derives the input's source mode from its value (expression can be forced). */
function fieldSourceKind(value: unknown, forced: boolean): SourceKind {
  if (forced) return 'expression'
  if (typeof value !== 'string') return 'manual'
  if (parseNodeReference(value)) return 'ref'
  if (/^\{\{inputs\.[A-Za-z0-9_.-]+}}$/.test(value)) return 'ref'
  if (value.includes('{{')) return 'expression'
  return 'manual'
}

function emptySchemaValue(schema: InputSchema): unknown {
  if (schema.type === 'array') return []
  if (schema.type === 'object') return {}
  if (schema.type === 'boolean') return false
  if (schema.type === 'integer' || schema.type === 'number') return 0
  return ''
}

function expectedType(schema: InputSchema): FlowOutputField['type'] {
  if (schema.format === 'fengyu-file' || schema.format === 'fengyu-directory') return 'file'
  return normalizeFlowType(schema.type)
}

function enumValue(option: unknown): string {
  return typeof option === 'object' && option !== null && 'value' in option
    ? String((option as { value: unknown }).value)
    : String(option)
}

function enumLabel(option: unknown): string {
  if (typeof option === 'object' && option !== null && 'label' in option) {
    const label = (option as { label?: unknown }).label
    if (typeof label === 'string' && label) return label
  }
  return enumValue(option)
}

function previewText(value: unknown, cap = 46): string {
  if (value === undefined || value === null || value === '') return ''
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.length > cap ? `${text.slice(0, cap - 3)}…` : text
}

interface FlatFieldRow extends FlowOutputField { depth: number }

function flattenTree(fields: FlowOutputField[], depth = 0, out: FlatFieldRow[] = []): FlatFieldRow[] {
  for (const field of fields) {
    out.push({ ...field, depth })
    if (field.children) flattenTree(field.children, depth + 1, out)
  }
  return out
}

function parseLastRun(raw: string | undefined): unknown {
  if (typeof raw !== 'string' || !raw) return undefined
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

function findFieldTitle(fields: FlowOutputField[], path: string): string | null {
  let segments = path.split(/[.[\]]/).filter(Boolean)
  let current = fields
  while (segments.length) {
    const segment = segments[0]
    segments = segments.slice(1)
    const match = current.find((field) => field.name === segment
      || (/^\d+$/.test(segment) && field.name === `[${segment}]`))
    if (!match) return null
    if (!segments.length) return match.title
    current = match.children ?? []
  }
  return null
}

function safeDocsUrl(url: string | null | undefined): string | undefined {
  return url && /^(https?:|mailto:)/i.test(url) ? url : undefined
}

function missingTool(toolName: string): AgentTool {
  return {
    id: `missing:${toolName}`,
    name: toolName,
    description: '',
    inputSchema: '{"type":"object","properties":{}}',
    revision: 'missing',
  }
}

interface UpstreamNodeInfo {
  id: string
  title: string
  args: Record<string, unknown>
  lastRun: unknown
  inputs: FlowOutputField[]
  outputs: FlowOutputField[]
}

function ToolInspector(props: {
  node: FlowCanvasNode & { type: 'tool'; data: FlowToolData }
  nodes: FlowCanvasNode[]
  edges: Edge[]
  toolsByName: Map<string, AgentTool>
  inputSchemaText: string
  disabled?: boolean
  onPatchTool: (nodeId: string, patch: Partial<FlowToolData>) => void
  onLink: (sourceId: string, targetId: string) => void
  onRunNode: (nodeId: string) => void
  onDelete: (nodeId: string) => void
}) {
  const { t } = useTranslation()
  const { node, toolsByName } = props
  const data = node.data
  const tool = toolsByName.get(data.toolName)
  const descriptor = tool?.flowNode ?? null

  const [argsText, setArgsText] = useState(data.argsText)
  /** Which input's variable picker is open. */
  const [openPicker, setOpenPicker] = useState<string | null>(null)
  /** Inputs forced into expression mode whose value has no `{{ }}` yet. */
  const [expressionForced, setExpressionForced] = useState<Set<string>>(new Set())

  useEffect(() => {
    setArgsText(data.argsText)
  }, [data.argsText, node.id])

  const args = useMemo<Record<string, unknown>>(() => parseJsonObject(data.argsText) ?? {}, [data.argsText])

  const editArgs = (text: string) => {
    setArgsText(text)
    if (parseJsonObject(text) !== null) {
      props.onPatchTool(node.id, { argsText: text })
    }
  }

  /** Writes one argument back into the node's args JSON. */
  const setArgument = (name: string, value: unknown) => {
    props.onPatchTool(node.id, { argsText: JSON.stringify({ ...args, [name]: value }, null, 2) })
  }

  // ── schema fields (RPC schema owns behavior; descriptor overlays display) ─
  const toolSchema = useMemo<InputSchema>(() => {
    try {
      return JSON.parse(tool?.inputSchema || '{}') as InputSchema
    } catch {
      return {}
    }
  }, [tool?.inputSchema])
  const declaredByName = useMemo(
    () => new Map((descriptor?.inputs ?? []).map((input) => [input.name, input])),
    [descriptor],
  )
  const inputFields = useMemo(() => {
    const declared = new Map((descriptor?.inputs ?? []).map((input) => [input.name, input]))
    return orderedInputEntries(toolSchema.properties ?? {}, descriptor?.inputs)
      .filter(([name, schema]) => !schema['x-fengyu-advanced'] && !declared.get(name)?.advanced)
      .map(([name, schema]) => {
        const overlay = declared.get(name)
        return [name, effectiveFlowInputSchema(schema as SchemaProperty, overlay), schema] as const
      })
  }, [toolSchema, descriptor])
  const advancedFields = useMemo(() => {
    return (Object.entries(toolSchema.properties ?? {}) as Array<[string, InputSchema]>)
      .filter(([name, schema]) => schema['x-fengyu-advanced'] || declaredByName.get(name)?.advanced)
      .map(([name, schema]) => [name, effectiveFlowInputSchema(schema as SchemaProperty, declaredByName.get(name))] as const)
  }, [toolSchema, declaredByName])
  const requiredInputs = useMemo(() => new Set(toolSchema.required ?? []), [toolSchema])

  const workflowSchemaFields = useMemo<Array<[string, InputSchema]>>(() => {
    const parsed = parseJsonObject(props.inputSchemaText)
    const properties = (parsed?.properties ?? {}) as Record<string, InputSchema>
    return Object.entries(properties)
  }, [props.inputSchemaText])

  const missingInputs = useMemo(
    () => (tool ? missingRequiredNodeInputs(tool, data.argsText) : []),
    [tool, data.argsText],
  )

  /** Cycle-free upstream tool nodes with their trees and last-run values. */
  const upstreamNodes = useMemo<UpstreamNodeInfo[]>(() => props.nodes
    .filter((candidate): candidate is FlowCanvasNode & { type: 'tool'; data: FlowToolData } =>
      isToolNode(candidate) && candidate.id !== node.id)
    .map((candidate) => {
      const upstreamTool = props.toolsByName.get(candidate.data.toolName) ?? missingTool(candidate.data.toolName)
      return {
        id: candidate.id,
        title: workflowNodeTitle(candidate.data, props.toolsByName.get(candidate.data.toolName)),
        args: parseJsonObject(candidate.data.argsText) ?? {},
        lastRun: parseLastRun(candidate.data.lastRun),
        inputs: workflowInputTree(upstreamTool),
        outputs: workflowOutputTree(upstreamTool),
      }
    }), [props.nodes, props.toolsByName])

  const lastRunParsed = useMemo(() => parseLastRun(data.lastRun), [data.lastRun])
  const pinned = data.pinnedOutput !== undefined

  // ── source control helpers ────────────────────────────────────────────────
  const clearExpressionForced = (name: string) => {
    setExpressionForced((current) => {
      if (!current.has(name)) return current
      const next = new Set(current)
      next.delete(name)
      return next
    })
  }

  const clearFieldSource = (name: string, schema: InputSchema) => {
    if (fieldSourceKind(args[name], expressionForced.has(name)) === 'manual') return
    clearExpressionForced(name)
    setArgument(name, schema.default ?? emptySchemaValue(schema))
    setOpenPicker(null)
  }

  const enableExpression = (name: string) => {
    if (fieldSourceKind(args[name], expressionForced.has(name)) === 'expression') return
    setExpressionForced((current) => new Set(current).add(name))
    if (args[name] === undefined) setArgument(name, '')
  }

  const bindReference = (name: string, selection: VariableTreeSelection) => {
    clearExpressionForced(name)
    if (selection.kind === 'input') {
      setArgument(name, `{{inputs.${(selection.path ?? '').replace(/^\./, '')}}}`)
    } else {
      setArgument(name, formatNodeReference({
        nodeId: selection.nodeId!,
        source: selection.source ?? 'result',
        path: selection.path ?? '',
      }))
      if (selection.nodeId && !props.edges.some((edge) => edge.source === selection.nodeId && edge.target === node.id)) {
        props.onLink(selection.nodeId, node.id)
      }
    }
    setOpenPicker(null)
  }

  const onArgumentDrop = (name: string) => (event: React.DragEvent) => {
    const raw = event.dataTransfer.getData('application/x-fengyu-ref')
    if (!raw || props.disabled) return
    try {
      bindReference(name, JSON.parse(raw) as VariableTreeSelection)
    } catch {
      // Not a reference payload — ignore (a tool drag is handled by the canvas).
    }
  }

  const nodeTitleOf = (candidate: FlowCanvasNode | undefined, fallbackId: string): string => {
    if (candidate && isToolNode(candidate)) {
      return workflowNodeTitle(candidate.data, toolsByName.get(candidate.data.toolName))
    }
    return fallbackId
  }

  /** Title of one bound reference resolved through live node titles. */
  const referenceLabel = (value: string): string => {
    const reference = parseNodeReference(value)
    if (reference) {
      const sourceNode = props.nodes.find((candidate) => candidate.id === reference.nodeId)
      const sourceTool = sourceNode && isToolNode(sourceNode)
        ? toolsByName.get(sourceNode.data.toolName) ?? missingTool(reference.nodeId)
        : missingTool(reference.nodeId)
      const tree = reference.source === 'input' ? workflowInputTree(sourceTool) : workflowOutputTree(sourceTool)
      const fieldTitle = findFieldTitle(tree, reference.path)
      const title = nodeTitleOf(sourceNode, reference.nodeId)
      return fieldTitle
        ? `${title} · ${t(reference.source === 'input' ? 'agent.nodeInputSource' : 'agent.nodeOutputSource')} · ${fieldTitle}`
        : title
    }
    const input = /^\{\{inputs\.([A-Za-z0-9_.-]+)}}$/.exec(value)
    if (input) {
      const schemaField = workflowSchemaFields.find(([name]) => name === input[1].split('.')[0])
      return `${t('agent.workflowInputSource')} · ${String(schemaField?.[1].title ?? input[1])}`
    }
    return value
  }

  /** Warning when a bound reference cannot resolve against the target's tree. */
  const referenceTypeWarning = (name: string): string | null => {
    const value = args[name]
    if (typeof value !== 'string') return null
    const reference = parseNodeReference(value)
    if (!reference) return null
    const sourceNode = props.nodes.find((candidate) => candidate.id === reference.nodeId)
    if (!sourceNode) return t('agent.referenceMissingNode')
    const sourceTool = isToolNode(sourceNode)
      ? toolsByName.get(sourceNode.data.toolName) ?? missingTool(reference.nodeId)
      : missingTool(reference.nodeId)
    const tree = reference.source === 'input' ? workflowInputTree(sourceTool) : workflowOutputTree(sourceTool)
    if (!referencePathExists(tree, reference.path)) return t('agent.referenceUnknownField')
    return null
  }

  /** Unknown references inside an expression string (save-time errors surfaced early). */
  const expressionUnknownReferences = (name: string): string[] => {
    const value = args[name]
    if (typeof value !== 'string') return []
    return collectNodeReferences(value)
      .filter((reference) => {
        const sourceNode = props.nodes.find((candidate) => candidate.id === reference.nodeId)
        const sourceTool = sourceNode && isToolNode(sourceNode)
          ? toolsByName.get(sourceNode.data.toolName) ?? missingTool(reference.nodeId)
          : null
        const tree = reference.source === 'input'
          ? workflowInputTree(sourceTool ?? missingTool(reference.nodeId))
          : sourceTool ? workflowOutputTree(sourceTool) : []
        return !sourceTool || !referencePathExists(tree, reference.path)
      })
      .map((reference) => formatNodeReference(reference))
  }

  const argsValid = parseJsonObject(argsText) !== null

  return (
    <>
      {data.available === false && (
        <div className="cx-alert cx-alert--warn flow-inspector__alert">
          <AlertTriangle size={16} />
          <span className="cx-alert__body">{t('agent.toolUnavailable')}</span>
        </div>
      )}
      <label className="flow-field">
        <span>{t('agent.nodeTitle')}</span>
        <input
          className="cx-input"
          value={data.title ?? ''}
          disabled={props.disabled}
          placeholder={descriptor?.label || humanizeToolName(data.toolName)}
          onChange={(event) => props.onPatchTool(node.id, { title: event.target.value })}
        />
      </label>
      <label className="flow-field">
        <span>{t('agent.description')}</span>
        <input
          className="cx-input"
          value={data.description}
          disabled={props.disabled}
          onChange={(event) => props.onPatchTool(node.id, { description: event.target.value })}
        />
      </label>
      {descriptor?.help && (
        <details className="flow-inspector__schema-details">
          <summary className="cx-muted">{t('agent.nodeHelp')}</summary>
          <p className="flow-inspector__intro">{descriptor.help}</p>
          {safeDocsUrl(descriptor.docsUrl) && (
            <a className="flow-inspector__docs" href={safeDocsUrl(descriptor.docsUrl)} target="_blank" rel="noreferrer">
              {t('agent.nodeDocs')}
            </a>
          )}
        </details>
      )}

      {/* ── input configuration (three-state source per field) ── */}
      <div className="flow-inspector__section">
        <div className="flow-inspector__section-head">
          <span>{t('agent.inputConfig')}</span>
          {missingInputs.length
            ? <span className="cx-chip flow-chip--warn" title={missingInputs.join(', ')}>{t('agent.missingInputs', { count: missingInputs.length })}</span>
            : <span className="cx-chip cx-chip--success">{t('agent.ready')}</span>}
        </div>
        {inputFields.map(([name, schema, rawSchema]) => (
          <InputFieldEditor
            key={name}
            name={name}
            schema={schema as InputSchema}
            required={requiredInputs.has(name)}
            value={args[name]}
            source={fieldSourceKind(args[name], expressionForced.has(name))}
            pickerOpen={openPicker === name}
            disabled={props.disabled}
            declaredPlaceholder={declaredByName.get(name)?.placeholder}
            workflowInputs={workflowSchemaFields.map(([wfName, wfSchema]) => ({
              path: `.${wfName}`,
              name: wfName,
              title: String(wfSchema.title ?? wfName),
              type: expectedType(wfSchema),
              examples: [],
            } satisfies FlowOutputField))}
            upstreamNodes={upstreamNodes}
            referenceLabel={referenceLabel}
            referenceTypeWarning={referenceTypeWarning}
            expressionUnknownReferences={expressionUnknownReferences}
            onSet={(value) => setArgument(name, value)}
            onClear={() => clearFieldSource(name, rawSchema as InputSchema)}
            onForceExpression={() => enableExpression(name)}
            onOpenPicker={() => setOpenPicker(openPicker === name ? null : name)}
            onBind={(selection) => bindReference(name, selection)}
            onDrop={onArgumentDrop(name)}
          />
        ))}
        {!inputFields.length && (
          <p className="cx-muted flow-inspector__empty">{t('agent.noInputRequired')}</p>
        )}
      </div>

      {/* ── output viewer + single-step debug run ── */}
      <details className="flow-inspector__schema-details" open>
        <summary className="cx-muted">{t('agent.outputConfig')}</summary>
        <button
          className="cx-btn cx-btn--outline flow-inspector__run-step"
          disabled={props.disabled || data.available === false}
          title={t('agent.runSingleStepHint')}
          onClick={() => props.onRunNode(node.id)}
        >
          <Play size={13} /> {t('agent.runSingleStep')}
        </button>
        <OutputTreeRows tool={tool} lastRunParsed={lastRunParsed} nodeId={node.id} />
        {typeof data.lastRun === 'string' && data.lastRun && (
          <div className="flow-inspector__pin-row">
            <span className="cx-muted" title={data.lastRun}>{previewText(data.lastRun, 120)}</span>
            {pinned ? (
              <button
                className="cx-iconbtn cx-iconbtn--sm"
                title={t('agent.unpinResult')}
                onClick={() => props.onPatchTool(node.id, { pinnedOutput: undefined })}
              >
                <PinOff size={13} />
              </button>
            ) : (
              <button
                className="cx-iconbtn cx-iconbtn--sm"
                title={t('agent.pinLastRun')}
                onClick={() => props.onPatchTool(node.id, { pinnedOutput: data.lastRun })}
              >
                <Pin size={13} />
              </button>
            )}
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('agent.copyJson')}
              onClick={() => void navigator.clipboard?.writeText(data.lastRun ?? '')}
            >
              <Copy size={13} />
            </button>
          </div>
        )}
        {pinned && (
          <>
            <span className="cx-chip cx-chip--success">{t('agent.pinnedResult')}</span>
            {data.pinnedOutput && (
              <pre className="flow-inspector__schema mono">{previewText(data.pinnedOutput, 200)}</pre>
            )}
          </>
        )}
      </details>

      {/* ── upstream data preview ── */}
      {upstreamNodes.length > 0 && (
        <details className="flow-inspector__schema-details">
          <summary className="cx-muted">{t('agent.upstreamData')}</summary>
          {upstreamNodes.map((upstream) => (
            <div key={upstream.id} className="flow-inspector__upstream">
              <strong>{upstream.title}</strong>
              {flattenTree(upstream.inputs).map((field) => (
                <UpstreamRow
                  key={`in:${field.path}`}
                  label={`${t('agent.nodeInputSource')} · ${field.title}`}
                  value={previewText(resolveOutputPath(upstream.args, field.path))}
                  onCopy={() => void navigator.clipboard?.writeText(
                    `{{node.${upstream.id}.input${field.path}}}`)}
                />
              ))}
              {flattenTree(upstream.outputs).map((field) => (
                <UpstreamRow
                  key={`out:${field.path}`}
                  label={`${t('agent.nodeOutputSource')} · ${field.title}`}
                  value={upstream.lastRun === undefined ? '' : previewText(resolveOutputPath(upstream.lastRun, field.path))}
                  onCopy={() => void navigator.clipboard?.writeText(
                    `{{node.${upstream.id}.result${field.path}}}`)}
                />
              ))}
            </div>
          ))}
        </details>
      )}

      {/* ── advanced: raw args JSON + retry policy ── */}
      <details className="flow-inspector__schema-details">
        <summary className="cx-muted">{t('agent.advancedSettings')}</summary>
        <label className="flow-field">
          <span>{t('agent.argumentsJson')} · {data.toolName}</span>
          <textarea
            className="cx-textarea mono"
            rows={8}
            value={argsText}
            disabled={props.disabled}
            spellCheck={false}
            onChange={(event) => editArgs(event.target.value)}
          />
        </label>
        {!argsValid && (
          <div className="cx-alert cx-alert--error">
            <span className="cx-alert__body">
              {t('agent.canvasInvalidArgs', { name: data.toolName })}
            </span>
          </div>
        )}
        {advancedFields.map(([name, schema]) => (
          <SimpleField
            key={name}
            name={name}
            schema={schema}
            value={args[name]}
            disabled={props.disabled}
            placeholder={declaredByName.get(name)?.placeholder}
            onSet={(value) => setArgument(name, value)}
          />
        ))}
        <RetryPolicyEditor
          policy={data.retryPolicy}
          retrySafe={tool?.retrySafe ?? true}
          disabled={props.disabled}
          onChange={(policy) => props.onPatchTool(node.id, { retryPolicy: policy })}
        />
      </details>

      <label className="flow-switch">
        <input
          type="checkbox"
          checked={data.requiresApproval}
          disabled={props.disabled}
          onChange={(event) => props.onPatchTool(node.id, { requiresApproval: event.target.checked })}
        />
        <span>{t('aichat.permissionAsk')}</span>
      </label>
      <button
        className="cx-btn cx-btn--outline flow-inspector__delete"
        disabled={props.disabled}
        onClick={() => props.onDelete(node.id)}
      >
        <Trash2 size={15} /> {t('agent.deleteNode')}
      </button>
    </>
  )
}

/** One input row: header + source bar + mode-specific editor + validation hints. */
function InputFieldEditor(props: {
  name: string
  schema: InputSchema
  required: boolean
  value: unknown
  source: SourceKind
  pickerOpen: boolean
  disabled?: boolean
  declaredPlaceholder?: string
  workflowInputs: FlowOutputField[]
  upstreamNodes: UpstreamNodeInfo[]
  referenceLabel: (value: string) => string
  referenceTypeWarning: (name: string) => string | null
  expressionUnknownReferences: (name: string) => string[]
  onSet: (value: unknown) => void
  onClear: () => void
  onForceExpression: () => void
  onOpenPicker: () => void
  onBind: (selection: VariableTreeSelection) => void
  onDrop: (event: React.DragEvent) => void
}) {
  const { t } = useTranslation()
  const { name, schema } = props
  const sources: Array<{ kind: SourceKind; label: string; hint: string }> = [
    { kind: 'manual', label: t('agent.sourceManual'), hint: t('agent.sourceManualHint') },
    { kind: 'ref', label: t('agent.sourceReference'), hint: t('agent.sourceReferenceHint') },
    { kind: 'expression', label: t('agent.sourceExpression'), hint: t('agent.sourceExpressionHint') },
  ]
  const typeWarning = props.referenceTypeWarning(name)
  const unknownRefs = props.source === 'expression' ? props.expressionUnknownReferences(name) : []

  return (
    <div
      className="flow-input-field"
      onDragOver={(event) => event.preventDefault()}
      onDrop={props.onDrop}
    >
      <div className="flow-input-field__head">
        <span className="flow-input-field__title">
          {String(schema.title ?? name)}
          {props.required && <em className="flow-input-field__req">{t('agent.required')}</em>}
        </span>
        <span className="flow-input-field__type">
          <span className="flow-vtree__dot" style={{ background: flowTypeColor(expectedType(schema)) }} />
          {t(`agent.flowType.${expectedType(schema)}`)}
        </span>
      </div>
      {schema.description && <p className="flow-input-field__desc">{schema.description}</p>}
      <div className="flow-source-bar" role="tablist">
        {sources.map((source) => (
          <button
            key={source.kind}
            role="tab"
            aria-selected={props.source === source.kind}
            title={source.hint}
            className={`flow-source-bar__option${props.source === source.kind ? ' active' : ''}`}
            disabled={props.disabled}
            onClick={() => {
              if (source.kind === 'manual') props.onClear()
              else if (source.kind === 'expression') props.onForceExpression()
              else props.onOpenPicker()
            }}
          >
            {source.label}
          </button>
        ))}
      </div>

      {props.source === 'manual' && (
        <SimpleField
          name={name}
          schema={schema}
          value={props.value}
          disabled={props.disabled}
          placeholder={props.declaredPlaceholder ?? fieldPlaceholderText(schema, t('agent.enterValue'))}
          onSet={props.onSet}
        />
      )}

      {props.source === 'ref' && (
        <div className="flow-input-field__ref">
          {typeof props.value === 'string' && props.value ? (
            <span className="cx-chip flow-chip--ref" title={props.value}>
              {props.referenceLabel(props.value)}
              <button
                className="cx-iconbtn cx-iconbtn--sm"
                title={t('agent.clearReference')}
                disabled={props.disabled}
                onClick={props.onClear}
              >
                <X size={12} />
              </button>
            </span>
          ) : (
            <span className="cx-muted flow-input-field__notset">{t('agent.notSet')}</span>
          )}
          <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={props.onOpenPicker}>
            {t('agent.change')}
          </button>
          {typeWarning && <span className="flow-input-field__warn">{typeWarning}</span>}
          {props.pickerOpen && (
            <FlowVariableTree
              targetType={expectedType(schema)}
              workflowInputs={props.workflowInputs}
              upstreamNodes={props.upstreamNodes}
              onPick={props.onBind}
            />
          )}
        </div>
      )}

      {props.source === 'expression' && (
        <div className="flow-input-field__expression">
          <textarea
            className="cx-textarea mono"
            rows={2}
            value={typeof props.value === 'string' ? props.value : ''}
            disabled={props.disabled}
            placeholder={t('agent.expressionPlaceholder')}
            onChange={(event) => props.onSet(event.target.value)}
          />
          <p className="cx-muted flow-input-field__hint">{t('agent.expressionHint')}</p>
          {unknownRefs.map((reference) => (
            <span key={reference} className="flow-input-field__warn">{reference}</span>
          ))}
        </div>
      )}
    </div>
  )
}

function UpstreamRow(props: { label: string; value: string; onCopy: () => void }) {
  const { t } = useTranslation()
  return (
    <div className="flow-inspector__upstream-row">
      <span className="flow-inspector__upstream-label">{props.label}</span>
      <span className="flow-inspector__upstream-value" title={props.value}>{props.value || '—'}</span>
      <button className="cx-iconbtn cx-iconbtn--sm" title={t('agent.copyReferencePath')} onClick={props.onCopy}>
        <Copy size={12} />
      </button>
    </div>
  )
}

function OutputTreeRows(props: {
  tool: AgentTool | undefined
  lastRunParsed: unknown
  nodeId: string
}) {
  const { t } = useTranslation()
  const outputs = props.tool ? workflowOutputTree(props.tool) : []
  if (!outputs.length) return <p className="cx-muted flow-inspector__empty">{t('agent.outputsUndeclared')}</p>
  return (
    <div className="flow-inspector__outputs">
      {flattenTree(outputs).map((field) => {
        const runValue = props.lastRunParsed === undefined
          ? undefined
          : resolveOutputPath(props.lastRunParsed, field.path)
        return (
          <div key={field.path} className="flow-inspector__output-row">
            <span className="flow-vtree__dot" style={{ background: flowTypeColor(field.type) }} />
            <span className="flow-inspector__output-title" title={field.description ?? undefined}>
              {field.title}
            </span>
            <span className="flow-inspector__output-value">
              {runValue !== undefined
                ? <span title={String(runValue)}>{t('agent.fromLastRun')} · {previewText(runValue)}</span>
                : field.examples[0] !== undefined
                  ? <span className="cx-muted" title={String(field.examples[0])}>{t('agent.fromDeclaration')} · {previewText(field.examples[0])}</span>
                  : <span className="cx-muted">—</span>}
            </span>
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('agent.copyReferencePath')}
              onClick={() => void navigator.clipboard?.writeText(
                `{{node.${props.nodeId}.result${field.path}}}`)}
            >
              <Copy size={12} />
            </button>
          </div>
        )
      })}
    </div>
  )
}

function RetryPolicyEditor(props: {
  policy?: { maxAttempts: number; backoffMs: number }
  retrySafe: boolean
  disabled?: boolean
  onChange: (policy: { maxAttempts: number; backoffMs: number } | undefined) => void
}) {
  const { t } = useTranslation()
  if (!props.retrySafe) {
    return (
      <div className="flow-input-field">
        <span className="flow-input-field__title">{t('agent.retryPolicy')}</span>
        <p className="cx-muted flow-input-field__hint">{t('agent.retryUnsafeHint')}</p>
        {props.policy && (
          <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={() => props.onChange(undefined)}>
            {t('agent.removeRetryPolicy')}
          </button>
        )}
      </div>
    )
  }
  const maxAttempts = props.policy?.maxAttempts ?? 1
  const backoffMs = props.policy?.backoffMs ?? 1_000
  const clampAttempts = (value: number) => Math.max(1, Math.min(5, Number(value) || 1))
  const clampBackoff = (value: number) => Math.max(0, Math.min(30_000, Number(value) || 0))
  return (
    <div className="flow-input-field">
      <span className="flow-input-field__title">
        {t('agent.retryPolicy')} · <span className="cx-muted">{t('agent.retrySafe')}</span>
      </span>
      <div className="flow-retry-row">
        <label className="flow-field">
          <span>{t('agent.maxAttempts')}</span>
          <input
            className="cx-input"
            type="number"
            min={1}
            max={5}
            value={maxAttempts}
            disabled={props.disabled}
            onChange={(event) => {
              const attempts = clampAttempts(Number(event.target.value))
              props.onChange(attempts > 1
                ? { maxAttempts: attempts, backoffMs: clampBackoff(props.policy?.backoffMs ?? 1_000) }
                : undefined)
            }}
          />
        </label>
        <label className="flow-field">
          <span>{t('agent.retryBackoffMs')}</span>
          <input
            className="cx-input"
            type="number"
            min={0}
            max={30000}
            step={100}
            value={backoffMs}
            disabled={props.disabled || maxAttempts < 2}
            onChange={(event) => props.onChange({
              maxAttempts: Math.max(2, maxAttempts),
              backoffMs: clampBackoff(Number(event.target.value)),
            })}
          />
        </label>
      </div>
      <p className="cx-muted flow-input-field__hint">{t('agent.retryBackoffHint')}</p>
    </div>
  )
}

/** Manual renderer shared by simple fields and advanced-flagged inputs. */
function SimpleField(props: {
  name: string
  schema: InputSchema
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const { schema } = props
  if (schema.type === 'boolean') {
    return (
      <label className="flow-switch">
        <input
          type="checkbox"
          checked={props.value === true}
          disabled={props.disabled}
          onChange={(event) => props.onSet(event.target.checked)}
        />
        <span>{String(schema.title ?? props.name)}</span>
      </label>
    )
  }
  if (schema.enum?.length) {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <select
          className="cx-select"
          value={props.value === undefined || props.value === null ? '' : String(props.value)}
          disabled={props.disabled}
          onChange={(event) => {
            const match = schema.enum?.find((option) => enumValue(option) === event.target.value)
            props.onSet(match !== undefined
              ? (typeof match === 'object' && match !== null && !Array.isArray(match)
                ? (match as { value: unknown }).value
                : match)
              : event.target.value)
          }}
        >
          <option value="">{t('agent.notSet')}</option>
          {schema.enum.map((option, index) => (
            <option key={index} value={enumValue(option)}>{enumLabel(option)}</option>
          ))}
        </select>
      </label>
    )
  }
  const isNumber = schema.type === 'integer' || schema.type === 'number'
  const multiline = schema['x-fengyu-multiline']
  const jsonEditor = schema['x-fengyu-json-editor'] || schema.type === 'object'
  const placeholder = props.placeholder
    ?? (schema.type === 'array' ? t('agent.arrayInputPlaceholder')
      : schema.type === 'object' ? t('agent.objectInputPlaceholder')
        : fieldPlaceholderText(schema, t('agent.enterValue')))
  return (
    <label className="flow-field">
      <span>{String(schema.title ?? props.name)}</span>
      {multiline || jsonEditor || schema.type === 'array' ? (
        <textarea
          className={`cx-textarea${jsonEditor ? ' mono' : ''}`}
          rows={jsonEditor ? 4 : 2}
          value={schema.type === 'array' && Array.isArray(props.value)
            ? (props.value as unknown[]).map((item) => String(item)).join(', ')
            : jsonEditor || schema.type === 'object'
              ? (props.value === undefined ? '' : JSON.stringify(props.value, null, 2))
              : typeof props.value === 'string' ? props.value : ''}
          disabled={props.disabled}
          placeholder={placeholder}
          spellCheck={false}
          onChange={(event) => {
            if (schema.type === 'array') {
              const items = event.target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
              const itemType = (schema.items as InputSchema | undefined)?.type
              props.onSet(itemType === 'integer' || itemType === 'number' ? items.map(Number) : items)
              return
            }
            // A json-editor field typed `string` keeps the raw text — the editor is a
            // nicer surface, not a type change (Vue updateJsonInput semantics).
            if (jsonEditor && schema.type !== 'string') {
              try {
                props.onSet(JSON.parse(event.target.value || '{}'))
              } catch {
                // Keep the last valid value; the raw JSON stays editable.
              }
              return
            }
            props.onSet(event.target.value)
          }}
        />
      ) : (
        <input
          className="cx-input"
          type={isNumber ? 'number' : 'text'}
          value={props.value === undefined || props.value === null
            ? ''
            : typeof props.value === 'object' ? JSON.stringify(props.value) : String(props.value)}
          disabled={props.disabled}
          placeholder={placeholder}
          onChange={(event) => {
            if (isNumber) {
              if (event.target.value === '') {
                props.onSet(undefined)
                return
              }
              const parsed = Number(event.target.value)
              props.onSet(Number.isFinite(parsed) ? parsed : 0)
              return
            }
            if (schema.enum?.length) {
              const match = schema.enum.find((option) => String(option) === event.target.value)
              props.onSet(match ?? event.target.value)
              return
            }
            props.onSet(event.target.value)
          }}
        />
      )}
    </label>
  )
}

function fieldPlaceholderText(schema: InputSchema, fallback: string): string {
  const example = schema.examples?.[0]
  if (example !== undefined && example !== null) {
    const text = typeof example === 'string' ? example : JSON.stringify(example)
    return text.length > 60 ? `${text.slice(0, 57)}…` : text
  }
  return schema.description || fallback
}

function StickyInspector(props: {
  node: FlowCanvasNode & { type: 'sticky'; data: { content: string; color: FlowStickyColor } }
  disabled?: boolean
  onPatchSticky: (nodeId: string, patch: { content?: string; color?: FlowStickyColor }) => void
  onDelete: (nodeId: string) => void
}) {
  const { t } = useTranslation()
  const { node } = props
  return (
    <>
      <label className="flow-field">
        <span>{t('flows.notePlaceholder')}</span>
        <textarea
          className="cx-textarea"
          rows={6}
          value={node.data.content}
          disabled={props.disabled}
          placeholder={t('flows.notePlaceholder')}
          onChange={(event) => props.onPatchSticky(node.id, { content: event.target.value })}
        />
      </label>
      <div className="flow-sticky-colors">
        {STICKY_COLORS.map((color) => (
          <button
            key={color}
            className={`flow-sticky-color flow-sticky-color--${color}${node.data.color === color ? ' flow-sticky-color--active' : ''}`}
            aria-label={color}
            disabled={props.disabled}
            onClick={() => props.onPatchSticky(node.id, { color })}
          />
        ))}
      </div>
      <button
        className="cx-btn cx-btn--outline flow-inspector__delete"
        disabled={props.disabled}
        onClick={() => props.onDelete(node.id)}
      >
        <Trash2 size={15} /> {t('flows.deleteNote')}
      </button>
    </>
  )
}

function StartInspector(props: {
  inputSchemaText: string
  disabled?: boolean
  onChange: (schemaText: string) => void
}) {
  const { t } = useTranslation()
  const fields = parseInputSchemaFields(props.inputSchemaText)

  const patch = (index: number, partial: Partial<FlowInputField>) => {
    const next = fields.map((field, i) => (i === index ? { ...field, ...partial } : field))
    props.onChange(buildInputSchemaText(next))
  }
  const remove = (index: number) => {
    props.onChange(buildInputSchemaText(fields.filter((_, i) => i !== index)))
  }
  const add = () => {
    const used = new Set(fields.map((field) => field.name))
    let suffix = fields.length + 1
    while (used.has(`input${suffix}`)) suffix += 1
    props.onChange(buildInputSchemaText([...fields, {
      name: `input${suffix}`,
      title: '',
      type: 'string',
      required: false,
      defaultValue: '',
    }]))
  }

  return (
    <div className="flow-inspector__start">
      <p className="cx-muted flow-inspector__intro">{t('agent.startDesignerIntro')}</p>
      {fields.map((field, index) => (
        <div key={`${field.name}-${index}`} className="flow-input-row">
          <label className="flow-input-row__name">
            <span>{t('agent.variableName')}</span>
            <input
              className="cx-input mono"
              value={field.name}
              disabled={props.disabled}
              onChange={(event) => patch(index, { name: event.target.value })}
            />
          </label>
          <label className="flow-input-row__title">
            <span>{t('agent.inputDisplayName')}</span>
            <input
              className="cx-input"
              value={field.title}
              disabled={props.disabled}
              onChange={(event) => patch(index, { title: event.target.value })}
            />
          </label>
          <label className="flow-input-row__type">
            <span>{t('agent.inputDesignerType')}</span>
            <select
              className="cx-select"
              value={field.type}
              disabled={props.disabled}
              onChange={(event) => patch(index, { type: event.target.value as FlowInputField['type'] })}
            >
              {FIELD_TYPE_OPTIONS.map((type) => (
                <option key={type} value={type}>{t(FIELD_TYPE_KEYS[type])}</option>
              ))}
            </select>
          </label>
          <label className="flow-input-row__default">
            <span>{t('agent.inputDefaultValue')}</span>
            <input
              className="cx-input"
              value={field.defaultValue}
              disabled={props.disabled}
              onChange={(event) => patch(index, { defaultValue: event.target.value })}
            />
          </label>
          <label className="flow-switch flow-input-row__required" title={t('agent.requiredAtRun')}>
            <input
              type="checkbox"
              checked={field.required}
              disabled={props.disabled}
              onChange={(event) => patch(index, { required: event.target.checked })}
            />
            <span>{t('agent.required')}</span>
          </label>
          <button
            className="cx-iconbtn cx-iconbtn--sm flow-input-row__delete"
            title={t('agent.deleteWorkflowInput')}
            disabled={props.disabled}
            onClick={() => remove(index)}
          >
            <Trash2 size={15} />
          </button>
        </div>
      ))}
      {!fields.length && <p className="cx-muted flow-inspector__empty">{t('agent.noWorkflowInputs')}</p>}
      <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={add}>
        {t('agent.addInput')}
      </button>
    </div>
  )
}
