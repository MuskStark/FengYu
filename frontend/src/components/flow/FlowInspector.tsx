import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Braces,
  ChevronDown,
  ClipboardPaste,
  Copy,
  Eye,
  EyeOff,
  FileSearch,
  FolderSearch,
  Play,
  Pin,
  PinOff,
  Plus,
  RotateCcw,
  Sparkles,
  Trash2,
  X,
} from 'lucide-react'
import type { Edge } from '@xyflow/react'
import type { AgentTool, FlowNodeInput } from '@/services/types'
import { services } from '@/services'
import { getPlatform } from '@/platform'
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
  isStickyNode,
  isToolNode,
  missingRequiredNodeInputs,
  parseJsonObject,
  STICKY_COLORS,
  type FlowCanvasNode,
  type FlowStickyColor,
  type FlowToolData,
} from '@/lib/flowGraph'
import { FlowVariableTree, type VariableTreeSelection } from './FlowVariableTree'
import {
  arrayItemParseError,
  parseManualNumber,
  validateManualField,
  type FieldValidationSchema,
} from '@/lib/flowFieldValidation'
import {
  ContextFeedController,
  contextFeedOptions,
  contextRowFieldOptions,
  fetchCatalogOptions,
  runNodeContext,
  type CatalogOption,
  type ContextFeedValue,
} from '@/lib/flowOptionSource'
import { resolveArgsPreview } from '@/lib/flowResolvedPreview'
import {
  buildDesignerSchemaText,
  DESIGNER_TYPES,
  DESIGNER_TYPE_KEYS,
  designerNameProblem,
  parseDesignerFields,
  type DesignerField,
} from '@/lib/flowInputDesigner'

/**
 * Right-rail inspector for the selected node: every tool input has a three-state
 * SOURCE control — manual entry, a reference picked from the upstream variable
 * tree (which auto-creates the edge), or a raw expression. The manual editor is
 * schema-driven with live field validation, declared defaults/examples one tap
 * away, dynamic options (catalog list methods and edit-time context analysis),
 * structured rows editing, native file/directory pickers, and masked sensitive
 * entry. Outputs and upstream data are previewable with declared → example →
 * last-run degradation, the resolved effective args preview before a run, the
 * last run can be pinned (compiled to `pinnedResult`), and retry-safe tools
 * take a bounded retry policy. The Start panel edits the run-input schema
 * through the lossless designer model.
 */

/** Long option lists switch from a <select> to a searchable combobox at this size. */
const SEARCHABLE_THRESHOLD = 12

const SENSITIVE_NAME = /(?:password|passwd|secret|token|credential|api[_-]?key)/i

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
  minimum?: number
  maximum?: number
  exclusiveMinimum?: number
  exclusiveMaximum?: number
  multipleOf?: number
  maxLength?: number
  pattern?: string
  'x-fengyu-advanced'?: boolean
  'x-fengyu-multiline'?: boolean
  'x-fengyu-json-editor'?: boolean
  'x-fengyu-file-access'?: 'read' | 'read-write'
  'x-fengyu-sensitive'?: boolean
  'x-fengyu-options-from'?: string
  'x-fengyu-options-from-context'?: { set: string; keyedBy?: string }
  [key: string]: unknown
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
    <div
      className="flow-inspector"
      onKeyDown={(event) => {
        // Esc closes the rail (the drawer form on narrow screens) when focus
        // lives inside it — and must not ALSO clear the canvas selection in
        // the page's window handler, hence the propagation stop.
        if (event.key === 'Escape' && !event.defaultPrevented) {
          event.preventDefault()
          event.stopPropagation()
          props.onClose()
        }
      }}
    >
      <div className="flow-inspector__head">
        <span className="flow-inspector__title">{title}</span>
        <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
          <X size={16} />
        </button>
      </div>
      {isToolNode(node) && (
        <ToolInspector
          key={node.id}
          node={node}
          nodes={props.nodes}
          edges={props.edges}
          toolsByName={props.toolsByName}
          inputSchemaText={props.inputSchemaText}
          disabled={props.disabled}
          onPatchTool={props.onPatchTool}
          onPatchInputSchema={props.onPatchInputSchema}
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

/** Enum raw value matching the wire form ({value,label} unwrap). */
function enumRaw(option: unknown): unknown {
  return typeof option === 'object' && option !== null && !Array.isArray(option) && 'value' in option
    ? (option as { value: unknown }).value
    : option
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
  onPatchInputSchema: (schemaText: string) => void
  onLink: (sourceId: string, targetId: string) => void
  onRunNode: (nodeId: string) => void
  onDelete: (nodeId: string) => void
}) {
  const { t } = useTranslation()
  const { node, toolsByName } = props
  const data = node.data
  const tool = toolsByName.get(data.toolName)
  const descriptor = tool?.flowNode ?? null
  const pluginId = tool?.pluginId ?? null

  const [argsText, setArgsText] = useState(data.argsText)
  /** Which input's variable picker is open. */
  const [openPicker, setOpenPicker] = useState<string | null>(null)
  /** Inputs forced into expression mode whose value has no `{{ }}` yet. */
  const [expressionForced, setExpressionForced] = useState<Set<string>>(new Set())
  /** Per-node config-clipboard feedback. */
  const [configCopied, setConfigCopied] = useState(false)

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

  // ── dynamic options: catalog list methods + edit-time context analysis ────
  const invokePlugin = useMemo(() =>
    (plugin: string, method: string, params?: Record<string, unknown>) =>
      services.plugin.invokeMethod(plugin, method, params), [])

  /** One controller per context-declaring input; feeds merge for consumers. */
  const feedControllers = useRef(new Map<string, ContextFeedController>())
  const [feedRevision, setFeedRevision] = useState(0)

  const contextInputNames = useMemo(() =>
    [...declaredByName.entries()].filter(([, input]) => input.context).map(([name]) => name),
    [declaredByName])

  /** Last-seen source values per context input (change detection + node guard). */
  const previousContextValues = useRef<Record<string, unknown>>({})

  // One lifecycle for the context controllers, in the only safe order:
  // node switch → clear stale controllers; then create missing ones; then
  // invalidate on changed source values. (Splitting creation and invalidation
  // into two effects made the invalidation pass wipe freshly created
  // controllers on every mount/node switch.)
  useEffect(() => {
    if (previousContextValues.current.__node !== node.id) {
      feedControllers.current.clear()
      previousContextValues.current = { __node: node.id }
    }
    let created = false
    for (const name of contextInputNames) {
      if (!feedControllers.current.has(name)) {
        const controller = new ContextFeedController((value, context) =>
          runNodeContext({ invoke: invokePlugin, pluginId, nodeId: node.id, context, value }))
        controller.onChange(() => setFeedRevision((current) => current + 1))
        feedControllers.current.set(name, controller)
        created = true
      }
    }
    if (created) setFeedRevision((current) => current + 1)
    // A changed source value invalidates its feeds immediately (no stale
    // options stay pickable); the fresh node's values seed without invalidating.
    for (const name of contextInputNames) {
      const value = args[name]
      if (name in previousContextValues.current
        && previousContextValues.current[name] !== value) {
        feedControllers.current.get(name)?.invalidate()
      }
      previousContextValues.current[name] = value
    }
    // args participates through the value-diff above, not as a dep.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contextInputNames, invokePlugin, pluginId, node.id, data.argsText])

  const mergedFeeds = useMemo(() => {
    const merged: Record<string, ContextFeedValue> = {}
    for (const controller of feedControllers.current.values()) {
      Object.assign(merged, controller.state.feeds)
    }
    return merged
    // feedRevision is the merge's real input — the controllers mutate in place.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedRevision])

  const catalogOptions = useRef(new Map<string, CatalogOption[]>())
  const [catalogRevision, setCatalogRevision] = useState(0)
  const catalogSources = useMemo(() =>
    [...declaredByName.entries()].filter(([, input]) => input.source).map(([name]) => name),
    [declaredByName])
  useEffect(() => {
    let cancelled = false
    for (const name of catalogSources) {
      const key = `${node.id}:${name}`
      if (catalogOptions.current.has(key)) continue
      const source = declaredByName.get(name)?.source
      if (!source) continue
      catalogOptions.current.set(key, [])
      void fetchCatalogOptions(invokePlugin, pluginId, source)
        .then((options) => {
          if (cancelled) return
          catalogOptions.current.set(key, options)
          setCatalogRevision((current) => current + 1)
        })
        .catch(() => {
          if (cancelled) return
          catalogOptions.current.set(key, [])
        })
    }
    return () => { cancelled = true }
  }, [catalogSources, declaredByName, invokePlugin, pluginId, node.id])

  /** Catalog options per input, re-derived when a fetch lands (render-safe).
   *  Entries are cached per `${node.id}:${name}` — only the selected node's
   *  slice is exposed, so a shared field name never bleeds across nodes. */
  const catalogOptionsByField = useMemo(() => {
    void catalogRevision
    const prefix = `${node.id}:`
    const byField = new Map<string, CatalogOption[]>()
    for (const [key, options] of catalogOptions.current) {
      if (key.startsWith(prefix)) byField.set(key.slice(prefix.length), options)
    }
    return byField
  }, [catalogRevision, node.id])

  // ── resolved-args preview (upstream last-run / declared examples) ─────────
  const resolvedPreview = useMemo(() => resolveArgsPreview(args, upstreamNodes,
    workflowSchemaFields.map(([name, schema]) => ({
      name,
      default: schema.default,
      example: Array.isArray(schema.examples) ? schema.examples[0] : undefined,
    }))), [args, upstreamNodes, workflowSchemaFields])

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

  /** Mints a Start run-input of the file/directory kind and binds this field to it. */
  const addRuntimeFileInput = (name: string, schema: InputSchema) => {
    const format = schema.format === 'fengyu-directory' ? 'directory' : 'file'
    const existing = parseDesignerFields(props.inputSchemaText)
    const base = format === 'directory' ? 'dir' : 'file'
    const taken = new Set(existing.map((field) => field.name))
    let suffix = existing.length + 1
    while (taken.has(`${base}${suffix}`)) suffix += 1
    const minted = `${base}${suffix}`
    const field: DesignerField = {
      name: minted,
      originalName: minted,
      title: String(schema.title ?? name),
      designerType: format,
      fileAccess: schema['x-fengyu-file-access'] === 'read-write' ? 'read-write' : 'read',
      required: requiredInputs.has(name),
      options: [],
      example: '',
      helpText: '',
      hasDefault: false,
      defaultText: '',
    }
    props.onPatchInputSchema(buildDesignerSchemaText(props.inputSchemaText, [...existing, field]))
    setArgument(name, `{{inputs.${minted}}}`)
  }

  /** Copies this node's tool config; pastes onto nodes of the same tool. */
  const copyNodeConfig = () => {
    void navigator.clipboard?.writeText(JSON.stringify({
      kind: 'fengyu-node-config', toolName: data.toolName, argsText: data.argsText,
    })).then(() => {
      setConfigCopied(true)
      window.setTimeout(() => setConfigCopied(false), 1600)
    }).catch(() => {
      // Clipboard write denied — the copy simply doesn't land.
    })
  }

  const pasteNodeConfig = async () => {
    let raw: string | null = null
    try {
      raw = await navigator.clipboard?.readText() ?? null
    } catch {
      raw = null
    }
    if (!raw) return
    try {
      const payload = JSON.parse(raw) as { kind?: string; toolName?: string; argsText?: string }
      if (payload.kind !== 'fengyu-node-config' || payload.toolName !== data.toolName
        || typeof payload.argsText !== 'string' || parseJsonObject(payload.argsText) === null) return
      props.onPatchTool(node.id, { argsText: payload.argsText })
    } catch {
      // Foreign clipboard content — ignore.
    }
  }

  const argsValid = parseJsonObject(argsText) !== null

  /** First input element per field name — the missing-inputs chip focuses it. */
  const fieldElements = useRef(new Map<string, HTMLElement>())
  const focusFirstMissing = () => {
    const first = missingInputs[0]
    if (!first) return
    const element = fieldElements.current.get(first)
    if (element) {
      element.scrollIntoView({ block: 'center', behavior: 'smooth' })
      const focusable = element.querySelector<HTMLElement>('input, textarea, select, button')
      focusable?.focus()
    }
  }

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
          placeholder={descriptor?.label || humanizeToolName(data.toolName)}
          onChange={(event) => props.onPatchTool(node.id, { description: event.target.value })}
        />
      </label>
      <div className="flow-inspector__config-actions">
        <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={copyNodeConfig}>
          <Copy size={13} /> {configCopied ? t('flows.configCopied') : t('flows.copyConfig')}
        </button>
        <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={() => void pasteNodeConfig()}>
          <ClipboardPaste size={13} /> {t('flows.pasteConfig')}
        </button>
      </div>
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
        <div className="flow-inspector__section-head flow-inspector__section-head--sticky">
          <span>{t('agent.inputConfig')}</span>
          {missingInputs.length
            ? (
              <button
                className="cx-chip flow-chip--warn flow-chip--action"
                title={missingInputs.join(', ')}
                onClick={focusFirstMissing}
              >
                {t('agent.missingInputs', { count: missingInputs.length })}
              </button>
            )
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
            declared={declaredByName.get(name)}
            declaredPlaceholder={declaredByName.get(name)?.placeholder}
            catalogOptions={catalogOptionsByField.get(name) ?? []}
            feeds={mergedFeeds}
            feedController={contextInputNames.includes(name) ? feedControllers.current.get(name) : undefined}
            workflowInputs={workflowSchemaFields.map(([wfName, wfSchema]) => ({
              path: `.${wfName}`,
              name: wfName,
              title: String(wfSchema.title ?? wfName),
              type: expectedType(wfSchema),
              examples: [],
            }) satisfies FlowOutputField)}
            upstreamNodes={upstreamNodes}
            registerElement={(element) => {
              if (element) fieldElements.current.set(name, element)
              else fieldElements.current.delete(name)
            }}
            referenceLabel={referenceLabel}
            referenceTypeWarning={referenceTypeWarning}
            expressionUnknownReferences={expressionUnknownReferences}
            onSet={(value) => setArgument(name, value)}
            onClear={() => clearFieldSource(name, rawSchema as InputSchema)}
            onForceExpression={() => enableExpression(name)}
            onOpenPicker={() => setOpenPicker(openPicker === name ? null : name)}
            onBind={(selection) => bindReference(name, selection)}
            onDrop={onArgumentDrop(name)}
            onAddRuntimeFileInput={() => addRuntimeFileInput(name, schema as InputSchema)}
          />
        ))}
        {!inputFields.length && (
          <p className="cx-muted flow-inspector__empty">{t('agent.noInputRequired')}</p>
        )}
      </div>

      {/* ── resolved effective args (pre-run confirmation) ── */}
      <details className="flow-inspector__schema-details">
        <summary className="cx-muted">
          {t('flows.resolvedArgsTitle')}
          {resolvedPreview.unresolved.length
            ? <span className="flow-chip--warn flow-inspector__resolved-warn">{resolvedPreview.unresolved.length}</span>
            : null}
        </summary>
        <p className="cx-muted flow-inspector__intro">{t('flows.resolvedArgsHint')}</p>
        <pre className="flow-inspector__schema mono">
          {JSON.stringify(resolvedPreview.value, null, 2)}
        </pre>
        {resolvedPreview.unresolved.map((reference) => (
          <span key={reference} className="flow-input-field__warn">{reference}</span>
        ))}
      </details>

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
        <div className="flow-inspector__json-tools">
          <button
            className="cx-btn cx-btn--outline"
            disabled={props.disabled || !argsValid}
            onClick={() => editArgs(JSON.stringify(parseJsonObject(argsText) ?? {}, null, 2))}
          >
            <Braces size={13} /> {t('flows.formatJson')}
          </button>
        </div>
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
  declared?: FlowNodeInput
  declaredPlaceholder?: string
  catalogOptions: CatalogOption[]
  feeds: Record<string, ContextFeedValue>
  feedController?: ContextFeedController
  workflowInputs: FlowOutputField[]
  upstreamNodes: UpstreamNodeInfo[]
  registerElement: (element: HTMLElement | null) => void
  referenceLabel: (value: string) => string
  referenceTypeWarning: (name: string) => string | null
  expressionUnknownReferences: (name: string) => string[]
  onSet: (value: unknown) => void
  onClear: () => void
  onForceExpression: () => void
  onOpenPicker: () => void
  onBind: (selection: VariableTreeSelection) => void
  onDrop: (event: React.DragEvent) => void
  onAddRuntimeFileInput: () => void
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

  // Manual-entry validation (bounds/pattern/typing — required-ness is node-level).
  const validation = props.source === 'manual'
    ? validateManualField(schema as FieldValidationSchema, props.value)
    : null

  const defaultValue = schema.default
  const example = Array.isArray(schema.examples) && schema.examples[0] !== undefined
    ? schema.examples[0]
    : undefined

  const feedCount = useMemo(() => {
    const sets = new Set(Object.keys(props.feeds))
    if (!sets.size) return 0
    let count = 0
    for (const feed of Object.values(props.feeds)) {
      count += Array.isArray(feed) ? feed.length : Object.keys(feed).length
    }
    return count
  }, [props.feeds])

  return (
    <div
      className="flow-input-field"
      ref={props.registerElement}
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
      {defaultValue !== undefined && defaultValue !== '' && (
        <p className="flow-input-field__default">
          {t('flows.fieldDefault', { value: previewText(defaultValue) })}
          {props.source === 'manual' && props.value !== defaultValue && (
            <button
              className="flow-input-field__linkbtn"
              disabled={props.disabled}
              onClick={() => props.onSet(defaultValue)}
            >
              <RotateCcw size={10} /> {t('flows.resetToDefault')}
            </button>
          )}
        </p>
      )}
      {example !== undefined && props.source === 'manual' && props.value !== example && (
        <button
          className="flow-input-field__linkbtn flow-input-field__example"
          disabled={props.disabled}
          title={String(example)}
          onClick={() => props.onSet(example)}
        >
          <Sparkles size={10} /> {t('flows.fillExample')}
        </button>
      )}
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
        <>
          <ManualFieldEditor
            name={name}
            schema={schema}
            value={props.value}
            disabled={props.disabled}
            declared={props.declared}
            declaredPlaceholder={props.declaredPlaceholder}
            catalogOptions={props.catalogOptions}
            feeds={props.feeds}
            onSet={props.onSet}
            onAddRuntimeFileInput={props.onAddRuntimeFileInput}
          />
          {props.declared?.context && (
            <ContextAnalyzeRow
              controller={props.feedController}
              context={props.declared.context}
              value={props.value}
              feedCount={feedCount}
              disabled={props.disabled}
            />
          )}
        </>
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

      {validation && <span className="flow-input-field__error">{validation}</span>}
    </div>
  )
}

/** The analyze trigger beside a context-declaring input (edit-time datasets). */
function ContextAnalyzeRow(props: {
  controller?: ContextFeedController
  context: NonNullable<FlowNodeInput['context']>
  value: unknown
  feedCount: number
  disabled?: boolean
}) {
  const { t } = useTranslation()
  const controller = props.controller
  const state = controller?.state
  const [, forceRender] = useState(0)
  useEffect(() => (controller ? controller.onChange(() => forceRender((n) => n + 1)) : undefined),
    [controller])
  if (!controller) return null
  return (
    <div className="flow-analyze">
      <button
        className="cx-btn cx-btn--outline flow-analyze__button"
        disabled={props.disabled || state?.running || props.value === undefined || props.value === ''}
        title={t('flows.analyzeWorkbook')}
        onClick={() => void controller.start(props.value, props.context)}
      >
        {state?.running ? <span className="cx-spin flow-analyze__spin" /> : <Sparkles size={12} />}
        {t('flows.analyzeWorkbook')}
      </button>
      {state?.error && <small className="flow-analyze__error">{state.error}</small>}
      {!state?.error && !state?.running && props.feedCount > 0 && (
        <small className="flow-analyze__done">{t('flows.analyzeReady', { count: props.feedCount })}</small>
      )}
      {state?.stale && !state?.running && props.feedCount === 0 && (
        <small className="cx-muted">{t('flows.analyzeStale')}</small>
      )}
    </div>
  )
}

/**
 * Manual-entry renderer: picks the widget per schema/declaration. Handles file
 * pickers, masked entry, dynamic options (catalog/context), searchable long
 * lists, structured rows, and JSON editing with format + inline errors.
 */
function ManualFieldEditor(props: {
  name: string
  schema: InputSchema
  value: unknown
  disabled?: boolean
  declared?: FlowNodeInput
  declaredPlaceholder?: string
  catalogOptions: CatalogOption[]
  feeds: Record<string, ContextFeedValue>
  onSet: (value: unknown) => void
  onAddRuntimeFileInput: () => void
}) {
  const { t } = useTranslation()
  const { schema, declared } = props

  // File/directory fields: native picker (desktop) + run-time-grant affordance.
  if (schema.format === 'fengyu-file' || schema.format === 'fengyu-directory') {
    return (
      <FileField
        schema={schema}
        value={props.value}
        disabled={props.disabled}
        placeholder={props.declaredPlaceholder ?? fieldPlaceholderText(schema, t('agent.enterValue'))}
        onSet={props.onSet}
        onAddRuntimeFileInput={props.onAddRuntimeFileInput}
      />
    )
  }

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

  // Dynamic candidates: declared static options, catalog list methods, then
  // context feeds (optionsFromContext) — the declaration is authoritative.
  const feedCandidates = declared?.optionsFromContext
    ? contextFeedOptions(props.feeds, declared.optionsFromContext)
    : []

  if (schema.enum?.length || props.catalogOptions.length || feedCandidates.length) {
    const enumOptions = schema.enum?.length
      ? schema.enum.map((option) => ({ value: enumRaw(option), label: enumLabel(option) }))
      : []
    const options: Array<{ value: unknown; label: string }> = enumOptions.length
      ? enumOptions
      : [...props.catalogOptions, ...feedCandidates.map((value) => ({ value, label: value }))]
    const searchable = options.length > SEARCHABLE_THRESHOLD
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        {searchable ? (
          <SearchableSelect
            options={options}
            value={props.value}
            disabled={props.disabled}
            onChange={props.onSet}
          />
        ) : (
          <select
            className="cx-select"
            value={props.value === undefined || props.value === null ? '' : String(props.value)}
            disabled={props.disabled}
            onChange={(event) => {
              if (enumOptions.length) {
                const match = schema.enum?.find((option) => enumValue(option) === event.target.value)
                props.onSet(match !== undefined ? enumRaw(match) : event.target.value)
                return
              }
              const match = options.find((option) => String(option.value) === event.target.value)
              props.onSet(match !== undefined ? match.value : event.target.value)
            }}
          >
            <option value="">{t('agent.notSet')}</option>
            {options.map((option, index) => (
              <option key={`${String(option.value)}:${index}`} value={String(option.value)}>{option.label}</option>
            ))}
          </select>
        )}
      </label>
    )
  }

  // Structured rows: array-of-objects rendered as per-row field groups.
  if (schema.type === 'array' && schema.items?.properties
    && Object.keys(schema.items.properties).length) {
    return (
      <RowsFieldEditor
        name={props.name}
        schema={schema}
        value={Array.isArray(props.value) ? props.value : []}
        disabled={props.disabled}
        feeds={props.feeds}
        onSet={props.onSet}
      />
    )
  }

  return (
    <SimpleField
      name={props.name}
      schema={schema}
      value={props.value}
      disabled={props.disabled}
      placeholder={props.declaredPlaceholder}
      sensitive={isSensitiveField(props.name, schema)}
      onSet={props.onSet}
    />
  )
}

function isSensitiveField(name: string, schema: InputSchema): boolean {
  if (schema['x-fengyu-sensitive'] === true) return true
  if (schema['x-fengyu-sensitive'] === false) return false
  return SENSITIVE_NAME.test(name)
}

/** File/directory input: native OS picker on desktop, grant hint everywhere. */
function FileField(props: {
  schema: InputSchema
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
  onAddRuntimeFileInput: () => void
}) {
  const { t } = useTranslation()
  const desktop = getPlatform().kind === 'desktop'
  const directory = props.schema.format === 'fengyu-directory'
  const pick = async () => {
    const platform = getPlatform()
    const path = directory ? await platform.pickDirectory() : await platform.pickFile()
    if (path) props.onSet(path)
  }
  const text = typeof props.value === 'string' ? props.value : ''
  return (
    <div className="flow-file-field">
      <div className="flow-file-field__row">
        <input
          className="cx-input mono"
          value={text}
          disabled={props.disabled}
          spellCheck={false}
          placeholder={props.placeholder}
          onChange={(event) => props.onSet(event.target.value)}
        />
        {desktop && (
          <button
            className="cx-iconbtn"
            title={directory ? t('flows.browseDirectory') : t('flows.browseFile')}
            disabled={props.disabled}
            onClick={() => void pick()}
          >
            {directory ? <FolderSearch size={15} /> : <FileSearch size={15} />}
          </button>
        )}
      </div>
      <div className="flow-file-field__hint">
        <small className="cx-muted">
          {directory ? t('agent.directoryPickerRequired') : t('agent.filePickerRequired')}
        </small>
        <button className="flow-input-field__linkbtn" disabled={props.disabled} onClick={props.onAddRuntimeFileInput}>
          {directory ? t('agent.addDirectoryPicker') : t('agent.addFilePicker')}
        </button>
      </div>
    </div>
  )
}

/** Structured array rows: each row edits its declared child fields in place. */
function RowsFieldEditor(props: {
  name: string
  schema: InputSchema
  value: unknown[]
  disabled?: boolean
  feeds: Record<string, ContextFeedValue>
  onSet: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const items = props.schema.items ?? {}
  const childSchemas = useMemo(() => {
    const entries = orderedInputEntries(items.properties ?? {}, undefined) as Array<[string, InputSchema]>
    return entries
  }, [items])
  const patchRow = (index: number, key: string, value: unknown) => {
    const next = props.value.map((row, rowIndex) => rowIndex === index
      ? { ...(row as Record<string, unknown>), [key]: value }
      : row)
    props.onSet(next)
  }
  const removeRow = (index: number) => {
    props.onSet(props.value.filter((_, rowIndex) => rowIndex !== index))
  }
  const addRow = () => {
    const seed: Record<string, unknown> = {}
    for (const [childName, childSchema] of childSchemas) {
      const required = (items.required ?? []).includes(childName)
      if (required) seed[childName] = emptySchemaValue(childSchema)
    }
    props.onSet([...props.value, seed])
  }
  return (
    <div className="flow-rows">
      {props.value.map((row, index) => (
        <div key={index} className="flow-rows__row">
          <div className="flow-rows__row-head">
            <span className="cx-muted">#{index + 1}</span>
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('flows.removeRow')}
              disabled={props.disabled}
              onClick={() => removeRow(index)}
            >
              <X size={12} />
            </button>
          </div>
          {childSchemas.map(([childName, childSchema]) => {
            const fromContext = childSchema['x-fengyu-options-from-context']
            const legacySource = childSchema['x-fengyu-options-from']
            const candidates = fromContext || legacySource
              ? contextRowFieldOptions(props.feeds, {
                fromContext,
                legacySource,
                row: (row ?? {}) as Record<string, unknown>,
              })
              : []
            const childValue = (row as Record<string, unknown> | null)?.[childName]
            if (candidates.length) {
              const datalistId = `dl-${props.name}-${index}-${childName}`
              return (
                <label className="flow-field" key={childName}>
                  <span>{String(childSchema.title ?? childName)}</span>
                  <input
                    className="cx-input"
                    list={datalistId}
                    value={childValue === undefined || childValue === null ? '' : String(childValue)}
                    disabled={props.disabled}
                    onChange={(event) => patchRow(index, childName, event.target.value)}
                  />
                  <datalist id={datalistId}>
                    {candidates.map((candidate) => <option key={candidate} value={candidate} />)}
                  </datalist>
                </label>
              )
            }
            return (
              <SimpleField
                key={childName}
                name={childName}
                schema={childSchema}
                value={childValue}
                disabled={props.disabled}
                onSet={(value) => patchRow(index, childName, value)}
              />
            )
          })}
        </div>
      ))}
      <button className="cx-btn cx-btn--outline flow-rows__add" disabled={props.disabled} onClick={addRow}>
        <Plus size={13} /> {t('flows.addRow')}
      </button>
    </div>
  )
}

/** Searchable combobox for long option lists (keyboard + ARIA combobox). */
function SearchableSelect(props: {
  options: Array<{ value: unknown; label: string }>
  value: unknown
  disabled?: boolean
  onChange: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const selectedLabel = props.options.find((option) => option.value === props.value)?.label

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false)
    }
    window.addEventListener('mousedown', onPointerDown)
    return () => window.removeEventListener('mousedown', onPointerDown)
  }, [open])

  const filtered = query.trim()
    ? props.options.filter((option) => option.label.toLowerCase().includes(query.trim().toLowerCase()))
    : props.options

  return (
    <div className="flow-combobox" ref={wrapRef}>
      <input
        className="cx-input"
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        value={open ? query : selectedLabel ?? ''}
        disabled={props.disabled}
        placeholder={t('flows.searchOptions')}
        onFocus={() => setOpen(true)}
        onChange={(event) => { setQuery(event.target.value); setOpen(true) }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            // Close the dropdown only — not the whole inspector rail.
            event.stopPropagation()
            setOpen(false)
            setQuery('')
          } else if (event.key === 'Enter' && open && filtered.length) {
            event.preventDefault()
            props.onChange(filtered[0]!.value)
            setOpen(false)
            setQuery('')
          }
        }}
      />
      <ChevronDown size={14} className="flow-combobox__chevron" />
      {open && (
        <ul className="flow-combobox__list" role="listbox">
          {filtered.map((option, index) => (
            <li key={`${String(option.value)}:${index}`}>
              <button
                type="button"
                role="option"
                aria-selected={option.value === props.value}
                onClick={() => {
                  props.onChange(option.value)
                  setOpen(false)
                  setQuery('')
                }}
              >
                {option.label}
              </button>
            </li>
          ))}
          {!filtered.length && <li className="cx-muted flow-combobox__empty">{t('flows.noOptions')}</li>}
        </ul>
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

/** Number draft state: garbage input stays visible + flagged, never coerced to 0. */
function NumberField(props: {
  schema: InputSchema
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState<string | null>(null)
  const shown = draft ?? (props.value === undefined || props.value === null || typeof props.value === 'object'
    ? ''
    : String(props.value))
  const invalid = draft !== null && !parseManualNumber(draft).ok
  const commit = (raw: string) => {
    setDraft(raw)
    if (raw === '') {
      props.onSet(undefined)
      return
    }
    const parsed = parseManualNumber(raw)
    if (parsed.ok) props.onSet(parsed.value)
  }
  return (
    <>
      <input
        className={`cx-input${invalid ? ' cx-input--invalid' : ''}`}
        type="number"
        min={props.schema.minimum}
        max={props.schema.maximum}
        step={props.schema.multipleOf ?? (props.schema.type === 'integer' ? 1 : 'any')}
        value={shown}
        disabled={props.disabled}
        placeholder={props.placeholder}
        onChange={(event) => commit(event.target.value)}
        onBlur={() => { if (!invalid) setDraft(null) }}
      />
      {invalid && <span className="flow-input-field__error">{t('flows.validationNotANumber')}</span>}
    </>
  )
}

/** JSON-entry textarea: invalid JSON stays editable with an inline error + format. */
function JsonField(props: {
  schema: InputSchema
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const isString = props.schema.type === 'string'
  const [draft, setDraft] = useState<string | null>(null)
  const valueText = props.value === undefined
    ? ''
    : isString && typeof props.value === 'string'
      ? props.value
      : JSON.stringify(props.value, null, 2)
  const shown = draft ?? valueText
  const parsed = (() => {
    if (!draft) return { ok: true as const, value: props.value }
    if (isString) return { ok: true as const, value: draft }
    try {
      return { ok: true as const, value: JSON.parse(draft || (props.schema.type === 'array' ? '[]' : '{}')) }
    } catch {
      return { ok: false as const, value: undefined }
    }
  })()
  const rows = Math.min(10, Math.max(3, Math.ceil(shown.length / 60) + 1))
  return (
    <>
      <textarea
        className="cx-textarea mono"
        rows={rows}
        value={shown}
        disabled={props.disabled}
        spellCheck={false}
        placeholder={props.placeholder}
        onChange={(event) => {
          const raw = event.target.value
          setDraft(raw)
          if (isString) {
            props.onSet(raw)
            return
          }
          if (raw === '') {
            props.onSet(undefined)
            setDraft(null)
            return
          }
          try {
            props.onSet(JSON.parse(raw))
            setDraft(null)
          } catch {
            // Keep the raw text editable; the inline error flags it.
          }
        }}
        onBlur={() => { if (parsed.ok) setDraft(null) }}
      />
      <div className="flow-input-field__json-tools">
        {!parsed.ok && <span className="flow-input-field__error">{t('flows.validationInvalidJson')}</span>}
        {parsed.ok && !isString && shown.trim() && (
          <button
            className="flow-input-field__linkbtn"
            disabled={props.disabled}
            onClick={() => {
              const formatted = JSON.stringify(parsed.value, null, 2)
              setDraft(formatted)
            }}
          >
            <Braces size={10} /> {t('flows.formatJson')}
          </button>
        )}
      </div>
    </>
  )
}

/**
 * Array entry with a draft: string-typed arrays commit live; numeric arrays
 * keep invalid text editable (garbage items never coerce to NaN/null — the
 * commit waits until every item parses).
 */
function ArrayField(props: {
  schema: InputSchema
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
}) {
  const itemType = (props.schema.items as InputSchema | undefined)?.type
  const numeric = itemType === 'integer' || itemType === 'number'
  const [draft, setDraft] = useState<string | null>(null)
  const itemsText = Array.isArray(props.value)
    ? (props.value as unknown[]).map((item) => String(item)).join(', ')
    : ''
  const shown = draft ?? itemsText
  const itemError = numeric ? arrayItemParseError(props.schema as FieldValidationSchema, shown) : null
  return (
    <>
      <textarea
        className="cx-textarea"
        rows={2}
        value={shown}
        disabled={props.disabled}
        placeholder={props.placeholder}
        spellCheck={false}
        onChange={(event) => {
          const raw = event.target.value
          const items = raw.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
          if (!numeric) {
            setDraft(null)
            props.onSet(items)
            return
          }
          setDraft(raw)
          if (!arrayItemParseError(props.schema as FieldValidationSchema, raw)) {
            props.onSet(items.map(Number))
            setDraft(null)
          }
        }}
        onBlur={() => { if (!itemError) setDraft(null) }}
      />
      {itemError && <span className="flow-input-field__error">{itemError}</span>}
    </>
  )
}

/** Masked entry for sensitive fields with a show/hide toggle. */
function SensitiveField(props: {
  value: unknown
  disabled?: boolean
  placeholder?: string
  onSet: (value: unknown) => void
}) {
  const { t } = useTranslation()
  const [visible, setVisible] = useState(false)
  const text = typeof props.value === 'string' ? props.value
    : props.value === undefined || props.value === null ? '' : String(props.value)
  return (
    <div className="flow-sensitive">
      <input
        className="cx-input mono"
        type={visible ? 'text' : 'password'}
        value={text}
        disabled={props.disabled}
        spellCheck={false}
        autoComplete="off"
        placeholder={props.placeholder}
        onChange={(event) => props.onSet(event.target.value)}
      />
      <button
        className="cx-iconbtn cx-iconbtn--sm"
        type="button"
        title={visible ? t('flows.hideValue') : t('flows.showValue')}
        aria-pressed={visible}
        onClick={() => setVisible(!visible)}
      >
        {visible ? <EyeOff size={13} /> : <Eye size={13} />}
      </button>
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
  sensitive?: boolean
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
  const isNumber = schema.type === 'integer' || schema.type === 'number'
  const multiline = schema['x-fengyu-multiline']
  const jsonEditor = schema['x-fengyu-json-editor'] || schema.type === 'object'
  const placeholder = props.placeholder
    ?? (schema.type === 'array' ? t('agent.arrayInputPlaceholder')
      : schema.type === 'object' ? t('agent.objectInputPlaceholder')
        : fieldPlaceholderText(schema, t('agent.enterValue')))

  // Sensitive manual strings mask on entry.
  if (!isNumber && !multiline && !jsonEditor && schema.type !== 'array' && props.sensitive) {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <SensitiveField
          value={props.value}
          disabled={props.disabled}
          placeholder={placeholder}
          onSet={props.onSet}
        />
      </label>
    )
  }

  if (isNumber) {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <NumberField
          schema={schema}
          value={props.value}
          disabled={props.disabled}
          placeholder={placeholder}
          onSet={props.onSet}
        />
      </label>
    )
  }

  if (jsonEditor) {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <JsonField
          schema={schema}
          value={props.value}
          disabled={props.disabled}
          placeholder={placeholder}
          onSet={props.onSet}
        />
      </label>
    )
  }

  if (schema.type === 'array') {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <ArrayField
          schema={schema}
          value={props.value}
          disabled={props.disabled}
          placeholder={placeholder}
          onSet={props.onSet}
        />
      </label>
    )
  }

  if (multiline) {
    return (
      <label className="flow-field">
        <span>{String(schema.title ?? props.name)}</span>
        <textarea
          className="cx-textarea"
          rows={2}
          value={typeof props.value === 'string' ? props.value : ''}
          disabled={props.disabled}
          placeholder={placeholder}
          spellCheck={false}
          onChange={(event) => props.onSet(event.target.value)}
        />
      </label>
    )
  }

  const textError = typeof props.value === 'string'
    ? validateManualField(schema as FieldValidationSchema, props.value)
    : null
  return (
    <label className="flow-field">
      <span>{String(schema.title ?? props.name)}</span>
      <input
        className="cx-input"
        type="text"
        value={props.value === undefined || props.value === null || typeof props.value === 'object'
          ? ''
          : String(props.value)}
        disabled={props.disabled}
        placeholder={placeholder}
        onChange={(event) => props.onSet(event.target.value)}
      />
      {textError && <span className="flow-input-field__error">{textError}</span>}
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

/** The Start node's run-input designer — edits the schema through the lossless model. */
function StartInspector(props: {
  inputSchemaText: string
  disabled?: boolean
  onChange: (schemaText: string) => void
}) {
  const { t } = useTranslation()
  const fields = parseDesignerFields(props.inputSchemaText)
  const [rawOpen, setRawOpen] = useState(false)

  const patch = (index: number, partial: Partial<DesignerField>) => {
    props.onChange(buildDesignerSchemaText(props.inputSchemaText,
      fields.map((field, i) => (i === index ? { ...field, ...partial } : field))))
  }
  const remove = (index: number) => {
    props.onChange(buildDesignerSchemaText(props.inputSchemaText,
      fields.filter((_, i) => i !== index)))
  }
  const move = (index: number, delta: number) => {
    const target = index + delta
    if (target < 0 || target >= fields.length) return
    const next = [...fields]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved!)
    props.onChange(buildDesignerSchemaText(props.inputSchemaText, next))
  }
  const add = () => {
    const taken = new Set(fields.map((field) => field.name))
    let suffix = fields.length + 1
    while (taken.has(`input${suffix}`)) suffix += 1
    const minted = `input${suffix}`
    props.onChange(buildDesignerSchemaText(props.inputSchemaText, [...fields, {
      name: minted,
      originalName: minted,
      title: '',
      designerType: 'string',
      fileAccess: 'read',
      required: false,
      options: [],
      example: '',
      helpText: '',
      hasDefault: false,
      defaultText: '',
    }]))
  }

  /** Rename commits a normalized name on blur (invalid states only flag inline). */
  const rename = (index: number, raw: string) => {
    const name = raw.trim().replace(/[^A-Za-z0-9_-]/g, '')
    if (!name || fields.some((field, i) => i !== index && field.name === name)) return
    patch(index, { name })
  }

  const pickDefaultPath = async (index: number, field: DesignerField) => {
    const platform = getPlatform()
    const path = field.designerType === 'file'
      ? await platform.pickFile()
      : await platform.pickDirectory()
    if (!path) return
    patch(index, { hasDefault: true, defaultText: path })
  }

  return (
    <div className="flow-inspector__start">
      <p className="cx-muted flow-inspector__intro">{t('agent.startDesignerIntro')}</p>
      {fields.map((field, index) => {
        const nameProblem = designerNameProblem(fields, index, field.name)
        return (
          <div key={`${field.originalName}-${index}`} className="flow-input-row flow-input-row--designer">
            <div className="flow-input-row__head">
              <span className="flow-vtree__dot" style={{ background: designerTypeColor(field) }} />
              <input
                className={`cx-input mono flow-input-row__name${nameProblem ? ' cx-input--invalid' : ''}`}
                defaultValue={field.name}
                spellCheck={false}
                disabled={props.disabled}
                title={`{{inputs.${field.name}}}`}
                onBlur={(event) => rename(index, event.target.value)}
              />
              {nameProblem && (
                <span className="flow-input-field__error">
                  {t(nameProblem === 'duplicate' ? 'flows.startNameDuplicate' : 'flows.startNameInvalid')}
                </span>
              )}
              <label className="flow-switch flow-input-row__required" title={t('agent.requiredAtRun')}>
                <input
                  type="checkbox"
                  checked={field.required}
                  disabled={props.disabled}
                  onChange={(event) => patch(index, { required: event.target.checked })}
                />
                <span>{t('agent.required')}</span>
              </label>
              <span className="flow-input-row__order">
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('flows.moveUp')}
                  disabled={props.disabled || index === 0}
                  onClick={() => move(index, -1)}
                >
                  <ArrowUp size={12} />
                </button>
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('flows.moveDown')}
                  disabled={props.disabled || index === fields.length - 1}
                  onClick={() => move(index, 1)}
                >
                  <ArrowDown size={12} />
                </button>
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('agent.deleteWorkflowInput')}
                  disabled={props.disabled}
                  onClick={() => remove(index)}
                >
                  <Trash2 size={13} />
                </button>
              </span>
            </div>
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
                value={field.designerType}
                disabled={props.disabled}
                onChange={(event) => patch(index, { designerType: event.target.value as DesignerField['designerType'] })}
              >
                {DESIGNER_TYPES.map((type) => (
                  <option key={type} value={type}>{t(DESIGNER_TYPE_KEYS[type])}</option>
                ))}
              </select>
            </label>
            {field.designerType === 'select' && (
              <label className="flow-input-row__full">
                <span>{t('agent.inputOptions')}</span>
                <input
                  className="cx-input"
                  value={field.options.join(', ')}
                  placeholder={t('agent.inputOptionsPlaceholder')}
                  disabled={props.disabled}
                  onChange={(event) => patch(index, {
                    options: event.target.value.split(',').map((item) => item.trim()).filter(Boolean),
                  })}
                />
              </label>
            )}
            {field.designerType === 'directory' && (
              <label className="flow-input-row__type">
                <span>{t('agent.directoryAccess')}</span>
                <select
                  className="cx-select"
                  value={field.fileAccess}
                  disabled={props.disabled}
                  onChange={(event) => patch(index, { fileAccess: event.target.value as 'read' | 'read-write' })}
                >
                  <option value="read">{t('agent.directoryAccessRead')}</option>
                  <option value="read-write">{t('agent.directoryAccessReadWrite')}</option>
                </select>
              </label>
            )}
            <label className="flow-input-row__title">
              <span>{t('agent.inputExample')}</span>
              <input
                className="cx-input"
                value={field.example}
                placeholder={t('agent.inputExamplePlaceholder')}
                disabled={props.disabled}
                onChange={(event) => patch(index, { example: event.target.value })}
              />
            </label>
            <label className="flow-input-row__full">
              <span>{t('agent.inputHelpText')}</span>
              <input
                className="cx-input"
                value={field.helpText}
                placeholder={t('agent.inputHelpText')}
                disabled={props.disabled}
                onChange={(event) => patch(index, { helpText: event.target.value })}
              />
            </label>
            <div className="flow-input-row__full flow-input-row__default">
              <span>{t('agent.inputDefaultValue')}</span>
              <span className="flow-input-row__default-control">
                <input
                  type="checkbox"
                  checked={field.hasDefault}
                  disabled={props.disabled}
                  title={t('agent.inputDefaultEnabled')}
                  onChange={(event) => patch(index, { hasDefault: event.target.checked })}
                />
                {field.designerType === 'file' || field.designerType === 'directory' ? (
                  <button
                    type="button"
                    className="cx-btn cx-btn--outline flow-input-row__default-picker"
                    disabled={props.disabled}
                    onClick={() => void pickDefaultPath(index, field)}
                  >
                    {field.designerType === 'file' ? <FileSearch size={12} /> : <FolderSearch size={12} />}
                    {field.designerType === 'file' ? t('agent.chooseDefaultFile') : t('agent.chooseDefaultDirectory')}
                  </button>
                ) : field.designerType === 'boolean' ? (
                  <select
                    className="cx-select"
                    value={field.defaultText || 'false'}
                    disabled={props.disabled || !field.hasDefault}
                    onChange={(event) => patch(index, { defaultText: event.target.value })}
                  >
                    <option value="false">false</option>
                    <option value="true">true</option>
                  </select>
                ) : (
                  <input
                    className={`cx-input${field.designerType === 'object' || field.designerType === 'array' ? ' mono' : ''}`}
                    type={field.designerType === 'number' ? 'number' : 'text'}
                    value={field.defaultText}
                    placeholder={field.designerType === 'array' ? '[]' : field.designerType === 'object' ? '{}' : t('agent.inputDefaultPlaceholder')}
                    disabled={props.disabled || !field.hasDefault}
                    onChange={(event) => patch(index, { defaultText: event.target.value })}
                  />
                )}
              </span>
              {(field.designerType === 'file' || field.designerType === 'directory') && field.defaultText && (
                <small className="flow-input-row__default-path" title={field.defaultText}>{field.defaultText}</small>
              )}
            </div>
            <small
              className="flow-input-row__ref mono"
              title={t('agent.copyReferencePath')}
              onClick={() => void navigator.clipboard?.writeText(`{{inputs.${field.name}}}`)}
            >
              {'{{inputs.' + field.name + '}}'}
            </small>
          </div>
        )
      })}
      {!fields.length && <p className="cx-muted flow-inspector__empty">{t('agent.noWorkflowInputs')}</p>}
      <button className="cx-btn cx-btn--outline" disabled={props.disabled} onClick={add}>
        <Plus size={13} /> {t('agent.addInput')}
      </button>

      <details className="flow-inspector__schema-details" open={rawOpen} onToggle={(event) => setRawOpen(event.currentTarget.open)}>
        <summary className="cx-muted">{t('agent.advancedJsonInput')}</summary>
        <textarea
          className="cx-textarea mono"
          rows={8}
          spellCheck={false}
          value={props.inputSchemaText}
          disabled={props.disabled}
          onChange={(event) => props.onChange(event.target.value)}
        />
      </details>
    </div>
  )
}

function designerTypeColor(field: DesignerField): string {
  switch (field.designerType) {
    case 'number': return flowTypeColor('number')
    case 'boolean': return flowTypeColor('boolean')
    case 'array': return flowTypeColor('array')
    case 'object': return flowTypeColor('object')
    case 'file':
    case 'directory': return flowTypeColor('file')
    default: return flowTypeColor('string')
  }
}
