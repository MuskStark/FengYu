<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { FlowNodeInput } from '@/api/types'
import FlowVariableTree from './FlowVariableTree.vue'
import {
  ContextFeedController,
  contextFeedOptions,
  fetchCatalogOptions,
  runNodeContext,
  type CatalogOption,
} from './optionSource'
import {
  collectNodeReferences,
  flowTypeColor,
  formatNodeReference,
  humanizeWorkflowField,
  missingRequiredNodeInputs,
  normalizeFlowType,
  parseNodeReference,
  referencePathExists,
  workflowNodeTitle,
  workflowOutputTree,
  wouldCreateCycle,
  type FlowCanvasEdge,
  type FlowOutputField,
  type WorkflowFlowNode,
  type WorkflowSchemaProperty,
} from '@/components/agent/workflow'

/**
 * Flowise-style node configuration panel: opens on the right when a tool node
 * is selected. Every input has a three-state SOURCE control (descriptor v2):
 * manual entry, a reference picked from the upstream variable tree (which
 * auto-creates the edge), or a raw expression. Upstream data and this node's
 * outputs are previewable with declared → example → last-run degradation, and
 * a node's last run can be pinned so later runs serve it without executing.
 */
const props = defineProps<{
  node: WorkflowFlowNode
  nodes: WorkflowFlowNode[]
  edges: FlowCanvasEdge[]
  workflowSchemaFields: Array<[string, WorkflowSchemaProperty]>
  disabled?: boolean
}>()
const emit = defineEmits<{
  delete: []
  close: []
  /** Requests the parent to add the data-flow edge implied by a node-output binding. */
  link: [sourceId: string, targetId: string]
  /** Requests a single-step debug run of THIS node (upstream data from cache). */
  'run-node': []
}>()

const { t } = useI18n()

const retryAttempts = computed({
  get: () => props.node.data.retryPolicy?.maxAttempts ?? 1,
  set: (value: number) => {
    const maxAttempts = Math.max(1, Math.min(5, Number(value) || 1))
    props.node.data.retryPolicy = maxAttempts > 1
      ? { maxAttempts, backoffMs: props.node.data.retryPolicy?.backoffMs ?? 1_000 }
      : undefined
  },
})

const retryBackoffMs = computed({
  get: () => props.node.data.retryPolicy?.backoffMs ?? 1_000,
  set: (value: number) => {
    const backoffMs = Math.max(0, Math.min(30_000, Number(value) || 0))
    props.node.data.retryPolicy = {
      maxAttempts: Math.max(2, retryAttempts.value),
      backoffMs,
    }
  },
})

interface InputSchema {
  type?: string
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  required?: string[]
  properties?: Record<string, InputSchema>
  items?: InputSchema
  'x-fengyu-advanced'?: boolean
  /** Render as a plain multiline string textarea. */
  'x-fengyu-multiline'?: boolean
  /** `excel` — render an analyze button beside this input; results feed the row pickers. */
  'x-fengyu-analyze'?: string
  /** `workbook-sheets` / `workbook-columns` — datalist candidates from the analysis. */
  'x-fengyu-options-from'?: string
  /** Datalist candidates from a context feed (unified option-source standard). */
  'x-fengyu-options-from-context'?: { set: string; keyedBy?: string }
}

const inputSchema = computed<InputSchema>(() => {
  try {
    return JSON.parse(props.node.data.tool.inputSchema || '{}') as InputSchema
  } catch {
    return {}
  }
})
/**
 * Descriptor-first rendering: when the node carries an explicit flow-node
 * declaration its inputs (widget config) drive the form, mapped onto the same
 * editor machinery; only legacy nodes fall back to the tool's JSON Schema.
 */
const inputFields = computed<Array<[string, InputSchema, FlowNodeInput | undefined]>>(() => {
  const declared = props.node.data.descriptor?.inputs
  if (declared?.length) {
    return declared.map((input) => [input.name, widgetSchema(input), input])
  }
  return (Object.entries(inputSchema.value.properties ?? {})
    .filter(([, schema]) => !schema['x-fengyu-advanced']) as Array<[string, InputSchema]>)
    .map(([name, schema]) => [name, schema, undefined])
})

/** Maps a declared widget onto the editor schema vocabulary. */
function widgetSchema(input: FlowNodeInput): InputSchema {
  const base: InputSchema = { title: input.title, description: input.description, default: input.default }
  const typed = input.type && input.type !== 'any' ? input.type : undefined
  switch (input.widget) {
    case 'number':
      return { ...base, type: typed ?? 'number' }
    case 'select':
      return { ...base, type: typed ?? 'string', enum: input.options }
    case 'switch':
      return { ...base, type: 'boolean' }
    case 'textarea':
      return { ...base, type: typed ?? 'string', 'x-fengyu-multiline': true }
    case 'json':
      // mono JSON editor: parses on change, so array/object args stay typed.
      return { ...base, type: typed === 'string' ? 'string' : 'object' }
    case 'analyze':
      // Legacy alias: plain text — a `context` declaration drives the trigger now.
      return { ...base, type: typed ?? 'string' }
    case 'rows': {
      const properties: Record<string, InputSchema> = {}
      for (const field of input.fields ?? []) {
        properties[field.name] = field.widget === 'number'
          ? { type: 'number', title: field.title }
          : field.widget === 'switch'
            ? { type: 'boolean', title: field.title }
            : field.widget === 'select'
              ? { type: 'string', title: field.title }
              : { type: 'string', title: field.title, 'x-fengyu-options-from': field.optionsFrom,
                  'x-fengyu-options-from-context': field.optionsFromContext }
      }
      return { ...base, type: 'array', items: { type: 'object', properties } }
    }
    default:
      return { ...base, type: typed ?? 'string' }
  }
}

/** Declared input lookup — the source control reads required/type/examples/help from it. */
const declaredByName = computed(() =>
  new Map((props.node.data.descriptor?.inputs ?? []).map((input) => [input.name, input])))

/** Inputs folded behind "Advanced settings" (x-fengyu-advanced in the tool schema). */
const advancedInputFields = computed(() => Object.entries(inputSchema.value.properties ?? {})
  .filter(([, schema]) => schema['x-fengyu-advanced']))
const requiredInputs = computed(() => new Set([
  ...(inputSchema.value.required ?? []),
  ...(props.node.data.descriptor?.inputs ?? []).filter((input) => input.required).map((input) => input.name),
]))
const arguments_ = computed<Record<string, unknown>>(() => {
  try {
    const parsed = JSON.parse(props.node.data.argsText || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
})
const availableSourceNodes = computed(() =>
  props.nodes.filter((node) => node.id !== props.node.id
    && !wouldCreateCycle(props.edges, node.id, props.node.id)))
const downstreamNodes = computed(() => {
  const targetIds = new Set(props.edges.filter((edge) => edge.source === props.node.id).map((edge) => edge.target))
  return props.nodes.filter((node) => targetIds.has(node.id))
})
/**
 * Datasets produced by this node's context sources, keyed by feed name — driven by
 * a ContextFeedController so stale analyze responses can never overwrite newer
 * feeds, and a changed source value invalidates candidates immediately (§8.4).
 */
const contextController = new ContextFeedController((value, context) => runNodeContext({
  pluginId: props.node.data.tool.pluginId,
  nodeId: props.node.id,
  context,
  value,
}))
const contextFeeds = ref(contextController.state.feeds)
const contextRunning = ref(false)
const contextError = ref<string | null>(null)
contextController.onChange(() => {
  contextFeeds.value = contextController.state.feeds
  contextRunning.value = contextController.state.running
  contextError.value = contextController.state.error
})
/** Catalog options per source method, cached for the inspector's lifetime. */
const catalogCache = ref<Record<string, CatalogOption[]>>({})
/** Which input's variable picker is open. */
const openPicker = ref<string | null>(null)
/**
 * Inputs explicitly switched to expression mode whose value does not yet contain
 * `{{ }}` — the mode is otherwise derived from the value alone, so a fresh empty
 * expression could never stay in the expression editor.
 */
const expressionForced = ref(new Set<string>())

// Option sources and the picker are per node — reset when the inspector switches targets.
watch(() => props.node.id, () => {
  contextController.reset()
  lastContextSourceValues.value = {}
  catalogCache.value = {}
  openPicker.value = null
  expressionForced.value = new Set()
})

/**
 * A changed source value invalidates candidates immediately (§8.4.2): feeds clear
 * and dependent candidate values are marked as needing re-analysis — an old
 * workbook's sheet/column list must never stay pickable under a new path.
 */
const lastContextSourceValues = ref<Record<string, unknown>>({})
// arguments_ re-parses argsText on every change, so object/array values get fresh
// references even when unchanged — compare structurally or editing an unrelated
// input would spuriously clear freshly analyzed feeds.
function sameSourceValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false
  try { return JSON.stringify(a) === JSON.stringify(b) } catch { return false }
}
watch(arguments_, () => {
  for (const input of props.node.data.descriptor?.inputs ?? []) {
    if (!input.context) continue
    const value = arguments_.value[input.name]
    const previous = lastContextSourceValues.value[input.name]
    if (previous !== undefined && !sameSourceValue(previous, value)) {
      contextController.invalidate()
      break
    }
    if (previous === undefined) lastContextSourceValues.value[input.name] = value
  }
}, { deep: true })

/** Runs one input's context source (the unified analyze-style trigger) — always the
 *  triggering input's OWN context declaration, never the node's first one. */
async function runContext(input: FlowNodeInput) {
  const context = input.context
  if (!context || contextController.state.running) return
  const raw = arguments_.value[input.name]
  const value = typeof raw === 'string' ? raw.trim() : raw
  if (value === '' || value === undefined || value === null) return
  lastContextSourceValues.value[input.name] = raw
  await contextController.start(value, context)
}

/** Loads (and caches) one input's catalog options from its plugin. */
async function loadCatalogOptions(input: FlowNodeInput): Promise<CatalogOption[]> {
  const source = input.source
  if (!source) return []
  if (catalogCache.value[source.method]) return catalogCache.value[source.method]
  try {
    const options = await fetchCatalogOptions(props.node.data.tool.pluginId, source)
    catalogCache.value = { ...catalogCache.value, [source.method]: options }
    return options
  } catch {
    return []
  }
}

const catalogOptions = ref<Record<string, CatalogOption[]>>({})
async function ensureCatalog(input: FlowNodeInput) {
  if (!input.source || catalogOptions.value[input.name]) return
  catalogOptions.value = { ...catalogOptions.value, [input.name]: await loadCatalogOptions(input) }
}

function catalogSelected(input: FlowNodeInput, option: CatalogOption): boolean {
  const current = arguments_.value[input.name]
  return Array.isArray(current)
    ? current.some((item) => String(item) === String(option.value))
    : String(current) === String(option.value)
}

/** Declared select options accept plain values or {value,label} pairs (flow_if's
 *  localized operators) — normalize both shapes for the enum renderer. */
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

function toggleCatalogOption(input: FlowNodeInput, option: CatalogOption) {
  const source = input.source!
  const current = arguments_.value[input.name]
  if (source.multiple) {
    const list = Array.isArray(current) ? current : []
    const next = catalogSelected(input, option)
      ? list.filter((item) => String(item) !== String(option.value))
      : [...list, String(option.value)]
    setNodeArgument(input.name, next)
    return
  }
  setNodeArgument(input.name, option.value)
}

function selectCatalogOption(input: FlowNodeInput, event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const option = catalogOptions.value[input.name]?.find((item) => String(item.value) === value)
  setNodeArgument(input.name, option ? option.value : value)
}

function feedCount(): number {
  return Object.keys(contextFeeds.value).length
}

/** Row layout: the first non-boolean field shares its line with boolean switches. */
function rowParts(schema: InputSchema): {
  first: [string, InputSchema] | null
  rest: Array<[string, InputSchema]>
  booleans: Array<[string, InputSchema]>
} {
  const entries = Object.entries(schema.items?.properties ?? {})
    .filter(([, child]) => !child['x-fengyu-advanced']) as Array<[string, InputSchema]>
  const booleans = entries.filter(([, child]) => child.type === 'boolean')
  const others = entries.filter(([, child]) => child.type !== 'boolean')
  return { first: others[0] ?? null, rest: others.slice(1), booleans }
}

/** Datalist candidates from a context feed (legacy workbook-* annotations
 *  map onto the same resolution for old schema-fallback graphs). */
function rowOptions(source: string | undefined, rowSheet?: unknown): string[] {
  if (source === 'workbook-sheets') {
    return contextFeedOptions(contextFeeds.value, { set: Object.keys(contextFeeds.value)[0] }, rowSheet)
  }
  if (source === 'workbook-columns') {
    const sets = Object.keys(contextFeeds.value)
    const columnsSet = sets.find((name) => typeof contextFeeds.value[name] === 'object' && !Array.isArray(contextFeeds.value[name]))
    return contextFeedOptions(contextFeeds.value, columnsSet ? { set: columnsSet, keyedBy: 'sheetName' } : undefined, rowSheet)
  }
  return []
}

/** Unified: an optionsFromContext reference resolves against the node's feeds. */
function feedOptions(spec: { set: string; keyedBy?: string } | undefined, rowKeyValue?: unknown): string[] {
  return contextFeedOptions(contextFeeds.value, spec, rowKeyValue)
}

const missingInputs = computed(() => missingRequiredNodeInputs(props.node))
const outputTree = computed(() => workflowOutputTree(props.node))

// ── three-state source control (descriptor v2) ─────────────────────────────

type SourceKind = 'manual' | 'ref' | 'expression'

function fieldSourceKind(name: string): SourceKind {
  if (expressionForced.value.has(name)) return 'expression'
  const value = arguments_.value[name]
  if (typeof value !== 'string') return 'manual'
  if (parseNodeReference(value)) return 'ref'
  if (/^\{\{inputs\.[A-Za-z0-9_.-]+}}$/.test(value)) return 'ref'
  if (value.includes('{{')) return 'expression'
  return 'manual'
}

/** Title of one tree field by reference path, used to label bound chips. */
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

/** Human label of a bound reference: "节点 · 字段" via live node titles. */
function referenceLabel(value: string): string {
  const node = parseNodeReference(value)
  if (node) {
    const target = props.nodes.find((candidate) => candidate.id === node.nodeId)
    const fieldTitle = node.path && target
      ? findFieldTitle(workflowOutputTree(target), node.path)
      : null
    return fieldTitle
      ? `${workflowNodeTitle(target!)} · ${fieldTitle}`
      : (target ? workflowNodeTitle(target) : node.nodeId)
  }
  const input = /^\{\{inputs\.([A-Za-z0-9_.-]+)}}$/.exec(value)
  if (input) {
    const schemaField = props.workflowSchemaFields.find(([name]) => name === input[1].split('.')[0])
    return `${t('agent.workflowInputSource')} · ${schemaField?.[1].title || humanizeWorkflowField(input[1])}`
  }
  return value
}

function expectedType(name: string, schema: InputSchema): string | null {
  const declared = declaredByName.value.get(name)
  if (declared?.type && declared.type !== 'any') return declared.type
  if (schema.type === 'integer') return 'number'
  return schema.type ?? null
}

/** Binds a variable-tree selection (or a drag-dropped reference) into an input. */
function bindReference(name: string, selection: { kind: 'input' | 'node'; nodeId?: string; path?: string }) {
  // An explicitly picked reference replaces a forced expression mode.
  clearExpressionForced(name)
  if (selection.kind === 'input') {
    setNodeArgument(name, `{{inputs.${selection.path}}}`)
  } else {
    setNodeArgument(name, formatNodeReference({ nodeId: selection.nodeId!, path: selection.path ?? '' }))
    if (selection.nodeId
      && !props.edges.some((edge) => edge.source === selection.nodeId && edge.target === props.node.id)) {
      emit('link', selection.nodeId, props.node.id)
    }
  }
  openPicker.value = null
}

/** Warning shown when a bound reference cannot resolve against the target's outputs. */
function referenceTypeWarning(name: string): string | null {
  const value = arguments_.value[name]
  if (typeof value !== 'string') return null
  const node = parseNodeReference(value)
  if (!node) return null
  const target = props.nodes.find((candidate) => candidate.id === node.nodeId)
  if (!target) return t('agent.referenceMissingNode')
  if (!referencePathExists(workflowOutputTree(target), node.path)) return t('agent.referenceUnknownField')
  return null
}

/** Unknown references inside an expression string (save-time errors surfaced early). */
function expressionUnknownReferences(name: string): string[] {
  const value = arguments_.value[name]
  if (typeof value !== 'string') return []
  return collectNodeReferences(value)
    .filter((reference) => {
      const target = props.nodes.find((candidate) => candidate.id === reference.nodeId)
      return !target || !referencePathExists(workflowOutputTree(target), reference.path)
    })
    .map((reference) => formatNodeReference(reference))
}

function onArgumentDrop(name: string, event: DragEvent) {
  const raw = event.dataTransfer?.getData('application/x-fengyu-ref')
  if (!raw || props.disabled) return
  try {
    bindReference(name, JSON.parse(raw) as { kind: 'input' | 'node'; nodeId?: string; path?: string })
  } catch {
    // Not a reference payload — ignore (a tool drag is handled by the canvas).
  }
}

// ── upstream data preview + output viewer (declared → example → last run) ──

function parseLastRun(node: WorkflowFlowNode): unknown {
  const raw = node.data.lastRun
  if (typeof raw !== 'string' || !raw) return undefined
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/** Resolves `.a.b[0].c` against a parsed last-run value. */
function resolveJsonPath(value: unknown, path: string): unknown {
  let current = value
  for (const segment of path.split(/[.[\]]/).filter(Boolean)) {
    if (current === null || current === undefined) return undefined
    if (Array.isArray(current)) {
      const index = Number(segment)
      current = Number.isInteger(index) ? current[index] : undefined
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment]
    } else {
      return undefined
    }
  }
  return current
}

function previewText(value: unknown): string {
  if (value === undefined || value === null || value === '') return ''
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.length > 46 ? `${text.slice(0, 43)}…` : text
}

function fieldExample(field: FlowOutputField): string {
  return previewText(field.examples[0])
}

interface FlatFieldRow extends FlowOutputField { depth: number }

function flattenTree(fields: FlowOutputField[], depth = 0, out: FlatFieldRow[] = []): FlatFieldRow[] {
  for (const field of fields) {
    out.push({ ...field, depth })
    if (field.children) flattenTree(field.children, depth + 1, out)
  }
  return out
}

const upstreamPreview = computed(() => availableSourceNodes.value.map((node) => {
  const parsed = parseLastRun(node)
  return {
    node,
    hasRun: parsed !== undefined,
    rows: flattenTree(workflowOutputTree(node)).map((field) => ({
      field,
      runValue: parsed === undefined ? '' : previewText(resolveJsonPath(parsed, field.path)),
    })),
  }
}))

function copyReference(nodeId: string, path: string) {
  void navigator.clipboard?.writeText(formatNodeReference({ nodeId, path }))
}

const lastRunParsed = computed(() => parseLastRun(props.node))
const pinned = computed(() => props.node.data.pinnedOutput !== undefined)

function pinLastRun() {
  if (typeof props.node.data.lastRun !== 'string') return
  props.node.data.pinnedOutput = props.node.data.lastRun
}

function unpin() {
  delete props.node.data.pinnedOutput
}

function copyLastRun() {
  if (typeof props.node.data.lastRun === 'string') {
    void navigator.clipboard?.writeText(props.node.data.lastRun)
  }
}

function typeLabel(type?: string | null): string {
  return t(`agent.flowType.${normalizeFlowType(type)}`)
}

function fieldPlaceholder(name: string, schema: InputSchema): string {
  const declared = declaredByName.value.get(name)
  if (declared?.placeholder) return declared.placeholder
  const example = declared?.examples?.[0]
  if (example !== undefined && example !== null) {
    const text = typeof example === 'string' ? example : JSON.stringify(example)
    return text.length > 60 ? `${text.slice(0, 57)}…` : text
  }
  return schema.description || t('agent.enterValue')
}

function fieldHelp(name: string): string | null {
  return declaredByName.value.get(name)?.help ?? null
}

function setNodeArgument(name: string, value: unknown) {
  props.node.data.argsText = JSON.stringify({ ...arguments_.value, [name]: value }, null, 2)
}

/** Back to manual mode: clears a reference/expression only when one is set. */
function clearFieldSource(name: string, schema: InputSchema) {
  if (fieldSourceKind(name) === 'manual') return
  clearExpressionForced(name)
  setNodeArgument(name, schema.default ?? emptySchemaValue(schema))
  openPicker.value = null
}

function emptySchemaValue(schema: InputSchema): unknown {
  if (schema.type === 'array') return []
  if (schema.type === 'object') return {}
  if (schema.type === 'boolean') return false
  if (schema.type === 'integer' || schema.type === 'number') return 0
  return ''
}

/** Switches an input into expression mode, keeping any value already typed. */
function enableExpression(name: string) {
  if (fieldSourceKind(name) === 'expression') return
  const next = new Set(expressionForced.value)
  next.add(name)
  expressionForced.value = next
  if (arguments_.value[name] === undefined) setNodeArgument(name, '')
}

/** Drops an explicit expression-mode override (manual/reference selections own the value again). */
function clearExpressionForced(name: string) {
  if (!expressionForced.value.has(name)) return
  const next = new Set(expressionForced.value)
  next.delete(name)
  expressionForced.value = next
}

function removeNodeArgument(name: string) {
  const next = { ...arguments_.value }
  delete next[name]
  props.node.data.argsText = JSON.stringify(next, null, 2)
  clearExpressionForced(name)
  openPicker.value = null
}

function updateExpression(name: string, event: Event) {
  setNodeArgument(name, (event.target as HTMLTextAreaElement).value)
}

function valueFromInput(schema: InputSchema, event: Event): unknown {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (schema.type === 'boolean' && target instanceof HTMLInputElement) return target.checked
  if (schema.type === 'integer' || schema.type === 'number') {
    // Number('') === 0: clearing the field removes the key instead of writing a 0.
    if (target.value === '') return undefined
    const parsed = Number(target.value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (schema.enum?.length) return schema.enum.find((option) => String(option) === target.value) ?? target.value
  return target.value
}

function updateSimpleInput(name: string, schema: InputSchema, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (schema.type === 'array') {
    const items = target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
    setNodeArgument(name, schema.items?.type === 'integer' || schema.items?.type === 'number'
      ? items.map(Number)
      : items)
    return
  }
  setNodeArgument(name, valueFromInput(schema, event))
}

function updateObjectInput(name: string, event: Event) {
  try {
    setNodeArgument(name, JSON.parse((event.target as HTMLTextAreaElement).value || '{}'))
  } catch {
    // Keep the last valid value; the advanced JSON editor remains available for complex objects.
  }
}

function updateObjectField(name: string, childName: string, schema: InputSchema, event: Event) {
  const current = arguments_.value[name]
  const object = current && !Array.isArray(current) && typeof current === 'object'
    ? { ...current as Record<string, unknown> }
    : {}
  object[childName] = valueFromInput(schema, event)
  setNodeArgument(name, object)
}

function objectInputValue(name: string, childName: string): unknown {
  const value = arguments_.value[name]
  return value && !Array.isArray(value) && typeof value === 'object'
    ? (value as Record<string, unknown>)[childName] ?? ''
    : ''
}

function arrayObjectItems(name: string): Record<string, unknown>[] {
  const value = arguments_.value[name]
  return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') as Record<string, unknown>[] : []
}

function addArrayObjectItem(name: string) {
  setNodeArgument(name, [...arrayObjectItems(name), {}])
}

function removeArrayObjectItem(name: string, index: number) {
  setNodeArgument(name, arrayObjectItems(name).filter((_, itemIndex) => itemIndex !== index))
}

function updateArrayObjectField(name: string, index: number, childName: string, schema: InputSchema, event: Event) {
  const items = arrayObjectItems(name).map((item) => ({ ...item }))
  items[index] = { ...items[index], [childName]: valueFromInput(schema, event) }
  setNodeArgument(name, items)
}

function displayInputValue(name: string, schema: InputSchema): string | number {
  const value = arguments_.value[name]
  if (value === undefined || value === null) return ''
  if (schema.type === 'array') return Array.isArray(value) ? value.join(', ') : String(value)
  if (schema.type === 'object') return JSON.stringify(value, null, 2)
  return typeof value === 'number' ? value : String(value)
}
</script>

<template>
  <div class="flow-inspector">
    <div class="flow-inspector__title">
      {{ t('agent.nodeSettings') }}
      <span class="cx-row">
        <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteNode')" @click="emit('delete')"><i class="mdi mdi-delete-outline" /></button>
        <button class="cx-iconbtn cx-iconbtn--sm" :aria-label="t('flows.close')" @click="emit('close')"><i class="mdi mdi-close" /></button>
      </span>
    </div>
    <div v-if="node.data.tool.localizedDescription || node.data.tool.description" class="flow-inspector__intro">
      <i class="mdi mdi-information-outline" />
      <span>{{ node.data.tool.localizedDescription || node.data.tool.description }}</span>
    </div>
    <details v-if="node.data.descriptor?.help" class="flow-node-help">
      <summary><i class="mdi mdi-help-circle-outline" /> {{ t('agent.nodeHelp') }}</summary>
      <p>{{ node.data.descriptor.help }}</p>
      <a v-if="node.data.descriptor.docsUrl" :href="node.data.descriptor.docsUrl" target="_blank" rel="noopener">{{ t('agent.nodeDocs') }}</a>
    </details>
    <div v-if="!node.data.available" class="cx-alert cx-alert--error">
      <span class="cx-alert__body">{{ t('agent.toolUnavailable') }}</span>
    </div>

    <label class="flow-field flow-node-title">
      <span>{{ t('agent.nodeTitle') }}</span>
      <input
        class="cx-input"
        :value="node.data.title ?? ''"
        :placeholder="workflowNodeTitle(node)"
        :disabled="disabled"
        @input="node.data.title = ($event.target as HTMLInputElement).value"
      >
    </label>

    <section class="flow-config-section">
      <div class="flow-config-section__heading">
        <h3><i class="mdi mdi-login-variant" /> {{ t('agent.inputConfig') }}</h3>
        <span v-if="missingInputs.length" class="flow-completion flow-completion--warn">{{ t('agent.missingInputs', { count: missingInputs.length }) }}</span>
        <span v-else class="flow-completion flow-completion--ready"><i class="mdi mdi-check" /> {{ t('agent.ready') }}</span>
      </div>
      <div v-if="!inputFields.length" class="cx-muted flow-config-empty">
        {{ t('agent.noInputRequired') }}
      </div>
      <div
        v-for="([name, schema, declared]) in inputFields"
        :key="name"
        class="flow-argument"
        @dragover.prevent
        @drop.prevent="onArgumentDrop(name, $event)"
      >
        <div class="flow-argument__label">
          <span>{{ schema.title || humanizeWorkflowField(name) }}</span>
          <span v-if="requiredInputs.has(name) || declared?.required" class="flow-required">{{ t('agent.required') }}</span>
          <span class="flow-type-chip">{{ typeLabel(expectedType(name, schema) ?? (schema.type || 'string')) }}</span>
        </div>
        <small v-if="schema.description">{{ schema.description }}</small>
        <small v-if="fieldHelp(name)" class="flow-argument__help"><i class="mdi mdi-lightbulb-on-outline" /> {{ fieldHelp(name) }}</small>

        <!-- three-state source control: manual / reference / expression -->
        <div class="flow-source-bar">
          <button
            class="flow-source-bar__mode"
            :class="{ active: fieldSourceKind(name) === 'manual' }"
            :disabled="disabled"
            :title="t('agent.sourceManualHint')"
            @click="clearFieldSource(name, schema)"
          ><i class="mdi mdi-pencil-outline" /> {{ t('agent.sourceManual') }}</button>
          <button
            class="flow-source-bar__mode"
            :class="{ active: fieldSourceKind(name) === 'ref', open: openPicker === name }"
            :disabled="disabled"
            :title="t('agent.sourceReferenceHint')"
            @click="openPicker = openPicker === name ? null : name"
          ><i class="mdi mdi-link-variant" /> {{ t('agent.sourceReference') }}</button>
          <button
            class="flow-source-bar__mode"
            :class="{ active: fieldSourceKind(name) === 'expression' }"
            :disabled="disabled"
            :title="t('agent.sourceExpressionHint')"
            @click="enableExpression(name)"
          ><i class="mdi mdi-function-variant" /> {{ t('agent.sourceExpression') }}</button>
        </div>

        <FlowVariableTree
          v-if="openPicker === name"
          class="flow-argument__tree"
          :nodes="availableSourceNodes"
          :workflow-schema-fields="workflowSchemaFields"
          :expected-type="expectedType(name, schema)"
          :disabled="disabled"
          @select="(selection) => bindReference(name, selection)"
        />

        <template v-if="fieldSourceKind(name) === 'manual'">
          <select
            v-if="declared?.source && !declared.source.multiple"
            class="cx-input"
            :value="String(arguments_[name] ?? '')"
            :disabled="disabled"
            @focus="ensureCatalog(declared)"
            @change="selectCatalogOption(declared, $event)"
          >
            <option value="">{{ t('agent.notSet') }}</option>
            <option v-for="option in catalogOptions[name] ?? []" :key="String(option.value)" :value="String(option.value)">{{ option.label || String(option.value) }}</option>
          </select>
          <div v-else-if="declared?.source?.multiple" class="flow-enum-list">
            <label v-for="option in catalogOptions[name] ?? []" :key="String(option.value)">
              <input
                type="checkbox"
                :checked="catalogSelected(declared, option)"
                :disabled="disabled"
                @focus="ensureCatalog(declared)"
                @change="toggleCatalogOption(declared, option)"
              >
              <span>{{ option.label || String(option.value) }}</span>
            </label>
            <button v-if="!(catalogOptions[name] ?? []).length" class="flow-add-item" :disabled="disabled" @click="ensureCatalog(declared)">
              <i class="mdi mdi-refresh" /> {{ t('agent.loadingOptions') }}
            </button>
          </div>
          <select
            v-else-if="schema.enum?.length"
            class="cx-input"
            :value="arguments_[name] ?? ''"
            :disabled="disabled"
            @change="updateSimpleInput(name, schema, $event)"
          >
            <option v-if="!requiredInputs.has(name)" value="">{{ t('agent.notSet') }}</option>
            <option
              v-for="option in schema.enum"
              :key="enumValue(option)"
              :value="enumValue(option)"
            >{{ enumLabel(option) }}</option>
          </select>
          <label v-else-if="schema.type === 'boolean'" class="flow-boolean-input">
            <input
              type="checkbox"
              :checked="Boolean(arguments_[name])"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            >
            <span>{{ t('agent.enabled') }}</span>
          </label>
          <div v-else-if="schema.type === 'object' && schema.properties" class="flow-nested-fields">
            <label v-for="([childName, childSchema]) in Object.entries(schema.properties)" :key="childName">
              <span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
              <input class="cx-input" :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'" :value="objectInputValue(name, childName)" :placeholder="childSchema.description || t('agent.enterValue')" :disabled="disabled" @input="updateObjectField(name, childName, childSchema, $event)">
            </label>
          </div>
          <textarea
            v-else-if="schema.type === 'object'"
            class="cx-textarea mono flow-object-input"
            rows="3"
            :value="displayInputValue(name, schema)"
            :placeholder="fieldPlaceholder(name, schema)"
            :disabled="disabled"
            @change="updateObjectInput(name, $event)"
          />
          <div v-else-if="schema.type === 'array' && schema.items?.type === 'object' && schema.items.properties" class="flow-list-builder">
            <div v-for="(item, itemIndex) in arrayObjectItems(name)" :key="itemIndex" class="flow-list-item">
              <div class="flow-list-item__head"><strong>{{ t('agent.itemNumber', { n: itemIndex + 1 }) }}</strong><button class="cx-iconbtn cx-iconbtn--sm" :disabled="disabled" @click="removeArrayObjectItem(name, itemIndex)"><i class="mdi mdi-delete-outline" /></button></div>
              <div v-if="rowParts(schema).first || rowParts(schema).booleans.length" class="flow-list-item__inline">
                <label v-if="rowParts(schema).first" class="flow-list-item__grow">
                  <span>{{ rowParts(schema).first![1].title || humanizeWorkflowField(rowParts(schema).first![0]) }}</span>
                  <input
                    class="cx-input"
                    :type="rowParts(schema).first![1].type === 'number' || rowParts(schema).first![1].type === 'integer' ? 'number' : 'text'"
                    :value="item[rowParts(schema).first![0]] ?? ''"
                    :list="(feedOptions(rowParts(schema).first![1]['x-fengyu-options-from-context'], item[rowParts(schema).first![1]['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']).length
                      || rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName).length) ? `dl-${node.id}-${name}-${rowParts(schema).first![0]}` : undefined"
                    :disabled="disabled"
                    @input="updateArrayObjectField(name, itemIndex, rowParts(schema).first![0], rowParts(schema).first![1], $event)"
                  >
                  <datalist v-if="feedOptions(rowParts(schema).first![1]['x-fengyu-options-from-context'], item[rowParts(schema).first![1]['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']).length || rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName).length" :id="`dl-${node.id}-${name}-${rowParts(schema).first![0]}`">
                    <option v-for="option in [...feedOptions(rowParts(schema).first![1]['x-fengyu-options-from-context'], item[rowParts(schema).first![1]['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']), ...rowOptions(rowParts(schema).first![1]['x-fengyu-options-from'], item.sheetName)]" :key="option" :value="option" />
                  </datalist>
                </label>
                <label
                  v-for="([childName, childSchema]) in rowParts(schema).booleans"
                  :key="childName"
                  class="flow-switch"
                  :title="childSchema.description"
                >
                  <input type="checkbox" :checked="Boolean(item[childName])" :disabled="disabled" @change="updateArrayObjectField(name, itemIndex, childName, childSchema, $event)">
                  <span class="flow-switch__track" />
                  <span class="flow-switch__label">{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
                </label>
              </div>
              <label v-for="([childName, childSchema]) in rowParts(schema).rest" :key="childName">
                <span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
                <input
                  class="cx-input"
                  :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'"
                  :value="item[childName] ?? ''"
                  :list="(feedOptions(childSchema['x-fengyu-options-from-context'], item[childSchema['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']).length
                    || rowOptions(childSchema['x-fengyu-options-from'], item.sheetName).length) ? `dl-${node.id}-${name}-${childName}` : undefined"
                  :disabled="disabled"
                  @input="updateArrayObjectField(name, itemIndex, childName, childSchema, $event)"
                >
                <datalist v-if="feedOptions(childSchema['x-fengyu-options-from-context'], item[childSchema['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']).length || rowOptions(childSchema['x-fengyu-options-from'], item.sheetName).length" :id="`dl-${node.id}-${name}-${childName}`">
                  <option v-for="option in [...feedOptions(childSchema['x-fengyu-options-from-context'], item[childSchema['x-fengyu-options-from-context']?.keyedBy ?? 'sheetName']), ...rowOptions(childSchema['x-fengyu-options-from'], item.sheetName)]" :key="option" :value="option" />
                </datalist>
              </label>
            </div>
            <button class="flow-add-item" :disabled="disabled" @click="addArrayObjectItem(name)"><i class="mdi mdi-plus" /> {{ t('agent.addItem') }}</button>
          </div>
          <textarea
            v-else-if="schema.type === 'array'"
            class="cx-textarea flow-array-input"
            rows="2"
            :value="displayInputValue(name, schema)"
            :placeholder="fieldPlaceholder(name, schema)"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          />
          <div v-else-if="declared?.context" class="flow-analyze-input">
            <input
              class="cx-input"
              :value="displayInputValue(name, schema)"
              :placeholder="fieldPlaceholder(name, schema)"
              :disabled="disabled"
              @input="updateSimpleInput(name, schema, $event)"
            >
            <button
              class="flow-analyze-button"
              :disabled="disabled || contextRunning || !displayInputValue(name, schema)"
              :title="t('flows.analyzeWorkbook')"
              @click="runContext(declared)"
            ><span v-if="contextRunning" class="cx-spin" /><i v-else class="mdi mdi-magnify" /></button>
            <small v-if="contextError" class="flow-analyze-error">{{ contextError }}</small>
            <small v-else-if="feedCount()" class="flow-analyze-done">
              <i class="mdi mdi-check-circle-outline" /> {{ feedCount() }} set(s)
            </small>
          </div>
          <textarea
            v-else-if="schema['x-fengyu-multiline']"
            class="cx-textarea flow-array-input"
            rows="3"
            :value="String(displayInputValue(name, schema) ?? '')"
            :placeholder="fieldPlaceholder(name, schema)"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          />
          <input
            v-else
            class="cx-input"
            :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'"
            :value="displayInputValue(name, schema)"
            :list="feedOptions(declared?.optionsFromContext).length ? `dl-${node.id}-${name}` : undefined"
            :placeholder="fieldPlaceholder(name, schema)"
            :disabled="disabled"
            @input="updateSimpleInput(name, schema, $event)"
          >
          <datalist v-if="feedOptions(declared?.optionsFromContext).length" :id="`dl-${node.id}-${name}`">
            <option v-for="option in feedOptions(declared?.optionsFromContext)" :key="option" :value="option" />
          </datalist>
        </template>

        <!-- reference state: a labeled chip + rebind/clear -->
        <div v-else-if="fieldSourceKind(name) === 'ref'" class="flow-ref-chip">
          <i class="mdi mdi-link-variant" />
          <span class="flow-ref-chip__label">{{ referenceLabel(String(arguments_[name])) }}</span>
          <button :disabled="disabled" :title="t('agent.change')" @click="openPicker = name"><i class="mdi mdi-swap-horizontal" /></button>
          <button :disabled="disabled" :title="t('agent.clearReference')" @click="removeNodeArgument(name)"><i class="mdi mdi-close" /></button>
        </div>
        <small v-if="fieldSourceKind(name) === 'ref' && referenceTypeWarning(name)" class="flow-argument__warn-note">
          <i class="mdi mdi-alert-outline" /> {{ referenceTypeWarning(name) }}
        </small>

        <!-- expression state: raw text with {{ }} references; unknown ones flagged -->
        <template v-else>
          <textarea
            class="cx-textarea mono flow-expr-input"
            rows="2"
            spellcheck="false"
            :value="String(arguments_[name] ?? '')"
            :placeholder="t('agent.expressionPlaceholder')"
            :disabled="disabled"
            @change="updateExpression(name, $event)"
          />
          <small class="flow-argument__hint">{{ t('agent.expressionHint') }}</small>
          <small v-for="reference in expressionUnknownReferences(name)" :key="reference" class="flow-argument__warn-note">
            <i class="mdi mdi-alert-outline" /> {{ t('agent.unknownReference', { reference }) }}
          </small>
        </template>
      </div>
    </section>

    <section class="flow-config-section">
      <h3>
        <i class="mdi mdi-logout-variant" /> {{ t('agent.outputConfig') }}
        <!-- Single-step debug: run ONLY this node against its cached upstream data. -->
        <button
          class="flow-mini-button flow-output-card__run"
          :disabled="disabled || !node.data.available"
          :title="t('agent.runSingleStepHint')"
          @click="emit('run-node')"
        ><i class="mdi mdi-play-circle-outline" /> {{ t('agent.runSingleStep') }}</button>
      </h3>
      <div class="flow-output-card">
        <div class="flow-output-card__head">
          <strong>{{ t('agent.nodeResult') }}</strong>
          <span class="flow-output-card__badges">
            <span v-if="lastRunParsed !== undefined" class="flow-source-tag flow-source-tag--run">{{ t('agent.fromLastRun') }}</span>
            <span v-else class="flow-source-tag">{{ t('agent.fromDeclaration') }}</span>
            <span v-if="pinned" class="flow-source-tag flow-source-tag--pinned"><i class="mdi mdi-pin" /> {{ t('agent.pinnedResult') }}</span>
          </span>
        </div>
        <div v-if="outputTree.length" class="flow-output-fields">
          <div v-for="field in outputTree" :key="field.path" class="flow-output-row">
            <span class="flow-output-row__dot" :style="{ background: flowTypeColor(field.type) }" />
            <strong :title="field.path">{{ field.title }}</strong>
            <small>{{ typeLabel(field.type) }}</small>
            <span class="flow-output-row__value" :title="field.description">{{ fieldExample(field) || field.description || '' }}</span>
            <button
              class="flow-output-row__copy"
              :title="t('agent.copyReferencePath')"
              @click="copyReference(node.id, field.path)"
            ><i class="mdi mdi-content-copy" /></button>
          </div>
        </div>
        <div v-else class="cx-muted">{{ t('agent.outputsUndeclared') }}</div>
        <div v-if="lastRunParsed !== undefined" class="flow-output-lastrun">
          <small>{{ t('agent.lastRunValue') }}</small>
          <pre class="mono">{{ previewText(node.data.lastRun) }}</pre>
          <div class="flow-output-lastrun__actions">
            <button class="flow-mini-button" :disabled="disabled || pinned" :title="t('agent.pinResultHint')" @click="pinLastRun"><i class="mdi mdi-pin" /> {{ t('agent.pinLastRun') }}</button>
            <button class="flow-mini-button" :title="t('agent.copyJson')" @click="copyLastRun"><i class="mdi mdi-content-copy" /> {{ t('agent.copyJson') }}</button>
            <button v-if="pinned" class="flow-mini-button flow-mini-button--warn" :disabled="disabled" @click="unpin"><i class="mdi mdi-pin-off" /> {{ t('agent.unpinResult') }}</button>
          </div>
        </div>
        <span v-if="downstreamNodes.length">
          {{ t('agent.outputUsedBy', { count: downstreamNodes.length }) }}
        </span>
        <span v-else>{{ t('agent.outputConnectHint') }}</span>
      </div>
    </section>

    <details class="flow-upstream">
      <summary><i class="mdi mdi-source-branch" /> {{ t('agent.upstreamData') }} ({{ upstreamPreview.length }})</summary>
      <p class="flow-upstream__hint">{{ t('agent.upstreamDataHint') }}</p>
      <div v-for="group in upstreamPreview" :key="group.node.id" class="flow-upstream__group">
        <div class="flow-upstream__head">
          <strong>{{ workflowNodeTitle(group.node) }}</strong>
          <small v-if="group.hasRun">{{ t('agent.fromLastRun') }}</small>
          <small v-else>{{ t('agent.fromDeclaration') }}</small>
        </div>
        <div v-for="row in group.rows" :key="row.field.path" class="flow-output-row">
          <span class="flow-output-row__dot" :style="{ background: flowTypeColor(row.field.type) }" />
          <strong :title="row.field.path">{{ row.field.title }}</strong>
          <small>{{ typeLabel(row.field.type) }}</small>
          <span class="flow-output-row__value" :title="row.field.path">{{ row.runValue || fieldExample(row.field) || row.field.description || '' }}</span>
          <button
            class="flow-output-row__copy"
            :title="t('agent.copyReferencePath')"
            @click="copyReference(group.node.id, row.field.path)"
          ><i class="mdi mdi-content-copy" /></button>
        </div>
      </div>
      <div v-if="!upstreamPreview.length" class="cx-muted">{{ t('agent.upstreamEmpty') }}</div>
    </details>

    <details class="flow-advanced">
      <summary>{{ t('agent.advancedSettings') }}</summary>
      <div class="flow-advanced__body">
        <label class="flow-field">
          <span>{{ t('agent.description') }}</span>
          <input v-model="node.data.description" class="cx-input" :disabled="disabled">
        </label>
        <label class="flow-field">
          <span>{{ t('agent.argumentsJson') }}</span>
          <textarea
            v-model="node.data.argsText"
            class="cx-textarea mono"
            rows="8"
            spellcheck="false"
            :disabled="disabled"
          />
        </label>
        <div v-if="advancedInputFields.length" class="flow-advanced-inputs">
          <label v-for="([name, schema]) in advancedInputFields" :key="name" class="flow-field">
            <span>{{ schema.title || humanizeWorkflowField(name) }}</span>
            <select
              v-if="schema.enum?.length"
              class="cx-input"
              :value="arguments_[name] ?? ''"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            >
              <option v-if="!requiredInputs.has(name)" value="">{{ t('agent.notSet') }}</option>
              <option v-for="option in schema.enum" :key="String(option)" :value="option">{{ option }}</option>
            </select>
            <label v-else-if="schema.type === 'boolean'" class="flow-boolean-input">
              <input type="checkbox" :checked="Boolean(arguments_[name])" :disabled="disabled" @change="updateSimpleInput(name, schema, $event)">
              <span>{{ t('agent.enabled') }}</span>
            </label>
            <textarea
              v-else-if="schema.type === 'array' || schema.type === 'object'"
              class="cx-textarea mono"
              rows="3"
              :value="displayInputValue(name, schema)"
              :placeholder="schema.type === 'array' ? t('agent.arrayInputPlaceholder') : t('agent.objectInputPlaceholder')"
              :disabled="disabled"
              @change="updateSimpleInput(name, schema, $event)"
            />
            <input
              v-else
              class="cx-input"
              :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'"
              :value="displayInputValue(name, schema)"
              :placeholder="schema.description || t('agent.enterValue')"
              :disabled="disabled"
              @input="updateSimpleInput(name, schema, $event)"
            >
          </label>
        </div>
      </div>
    </details>
    <label class="flow-checkbox">
      <input v-model="node.data.requiresApproval" type="checkbox" :disabled="disabled">
      <span>{{ t('agent.requiresApproval') }}</span>
    </label>
    <section class="flow-config-section flow-retry-settings">
      <div class="flow-config-section__heading">
        <h3><i class="mdi mdi-refresh" /> {{ t('agent.retryPolicy') }}</h3>
        <span v-if="node.data.tool.retrySafe" class="flow-completion flow-completion--ready">
          <i class="mdi mdi-shield-check-outline" /> {{ t('agent.retrySafe') }}
        </span>
      </div>
      <template v-if="!node.data.tool.retrySafe">
        <p class="cx-muted">{{ t('agent.retryUnsafeHint') }}</p>
        <button
          v-if="node.data.retryPolicy"
          class="cx-btn cx-btn--outline cx-btn--sm"
          type="button"
          :disabled="disabled"
          @click="node.data.retryPolicy = undefined"
        >{{ t('agent.removeRetryPolicy') }}</button>
      </template>
      <template v-else>
        <label class="flow-field">
          <span>{{ t('agent.maxAttempts') }}</span>
          <input v-model.number="retryAttempts" class="cx-input" type="number" min="1" max="5" :disabled="disabled">
        </label>
        <label v-if="retryAttempts > 1" class="flow-field">
          <span>{{ t('agent.retryBackoffMs') }}</span>
          <input v-model.number="retryBackoffMs" class="cx-input" type="number" min="0" max="30000" step="100" :disabled="disabled">
        </label>
        <small v-if="retryAttempts > 1" class="cx-muted">{{ t('agent.retryBackoffHint') }}</small>
      </template>
    </section>
  </div>
</template>

<style scoped>
.flow-inspector {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.flow-inspector__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.flow-inspector__intro {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 16px;
  padding: 9px;
  color: rgba(var(--v-theme-on-surface), .72);
  font-size: 11px;
  line-height: 1.5;
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .08);
}

.flow-inspector__intro i { flex: 0 0 auto; color: rgb(var(--v-theme-primary)); font-size: 15px; }
.flow-inspector .cx-alert { margin-bottom: 14px; font-size: 11px; }

.flow-node-help {
  margin-bottom: 14px;
  padding: 8px 10px;
  border: 1px dashed rgba(var(--v-theme-primary), .45);
  border-radius: 8px;
  background: rgba(var(--v-theme-primary), .05);
}

.flow-node-help summary {
  display: flex;
  gap: 6px;
  align-items: center;
  color: rgb(var(--v-theme-primary));
  font-size: 11px;
  cursor: pointer;
  list-style: none;
}

.flow-node-help summary::-webkit-details-marker { display: none; }
.flow-node-help p { margin: 7px 0 4px; color: rgba(var(--v-theme-on-surface), .78); font-size: 11px; line-height: 1.55; white-space: pre-line; }
.flow-node-help a { color: rgb(var(--v-theme-primary)); font-size: 10px; }

.flow-node-title { margin-bottom: 14px; }

.flow-config-section { margin-bottom: 18px; }
.flow-config-section h3 { display: flex; gap: 6px; align-items: center; margin: 0 0 9px; font-size: 12px; }
.flow-config-section h3 i { color: rgb(var(--v-theme-primary)); font-size: 15px; }

.flow-config-section__heading { display: flex; gap: 8px; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.flow-config-section__heading h3 { margin: 0; flex: 1 1 auto; display: flex; align-items: center; gap: 6px; }
/* The single-step run button rides the output heading — nudge it to the right edge. */
.flow-output-card__run { margin-left: auto; }
.flow-completion { display: inline-flex; gap: 3px; align-items: center; padding: 3px 7px; font-size: 9px; font-weight: 650; border-radius: 10px; }
.flow-completion--ready { color: rgb(var(--v-theme-success)); background: rgba(var(--v-theme-success), .12); }
.flow-completion--warn { color: rgb(var(--v-theme-warning)); background: rgba(var(--v-theme-warning), .14); }

.flow-config-empty {
  padding: 10px;
  font-size: 11px;
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-argument {
  margin-bottom: 11px;
  padding: 9px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-argument__label {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 5px;
  font-size: 11px;
  font-weight: 650;
}

.flow-argument > small {
  display: block;
  margin: -1px 0 7px;
  color: rgba(var(--v-theme-on-surface), .6);
  font-size: 10px;
  line-height: 1.4;
}

.flow-argument__help { color: rgb(var(--v-theme-primary)); }
.flow-argument__hint { margin: 3px 0 0; }
.flow-argument__warn-note {
  display: flex;
  gap: 4px;
  align-items: center;
  color: rgb(var(--v-theme-error));
  font-size: 10px;
}

.flow-argument__tree { margin-bottom: 7px; }

.flow-required {
  padding: 1px 5px;
  color: rgb(var(--v-theme-primary));
  font-size: 9px;
  font-weight: 600;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-type-chip { margin-left: auto; color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; font-weight: 500; }

/* three-state source control */
.flow-source-bar {
  display: flex;
  gap: 0;
  margin-bottom: 7px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 7px;
  overflow: hidden;
}

.flow-source-bar__mode {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  justify-content: center;
  flex: 1;
  min-height: 28px;
  color: rgba(var(--v-theme-on-surface), .6);
  font: inherit;
  font-size: 10px;
  border: 0;
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
}

.flow-source-bar__mode + .flow-source-bar__mode { border-inline-start: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-source-bar__mode.active {
  color: rgb(var(--v-theme-primary));
  font-weight: 650;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-source-bar__mode.open { color: rgb(var(--v-theme-primary)); }
.flow-source-bar__mode:disabled { opacity: .45; cursor: not-allowed; }

.flow-ref-chip {
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 32px;
  padding: 4px 8px;
  color: rgb(var(--v-theme-primary));
  font-size: 10px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), .08);
}

.flow-ref-chip__label {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-ref-chip button {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 5px;
  color: inherit;
  background: rgba(var(--v-theme-primary), .1);
  cursor: pointer;
}

.flow-expr-input { width: 100%; resize: vertical; font-size: 11px; }

.flow-argument .cx-input,
.flow-argument .cx-textarea { width: 100%; font-size: 11px; }

.flow-boolean-input { display: flex; gap: 7px; align-items: center; min-height: 30px; font-size: 11px; }
.flow-array-input,
.flow-object-input { resize: vertical; }

.flow-nested-fields,
.flow-list-builder { display: flex; flex-direction: column; gap: 8px; }

.flow-analyze-input { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.flow-analyze-input .cx-input { flex: 1; min-width: 0; }
.flow-analyze-button {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 4px 9px;
  color: rgb(var(--v-theme-primary));
  font: inherit;
  font-size: 10px;
  border: 1px dashed rgba(var(--v-theme-primary), 0.6);
  border-radius: 7px;
  background: rgba(var(--v-theme-primary), 0.05);
  cursor: pointer;
}
.flow-analyze-button:disabled { opacity: 0.45; cursor: not-allowed; }
.flow-analyze-error { width: 100%; color: rgb(var(--v-theme-error)); font-size: 10px; }
.flow-analyze-done { display: inline-flex; align-items: center; gap: 4px; color: rgb(var(--v-theme-success)); font-size: 10px; }
.flow-nested-fields label,
.flow-list-item label { display: flex; flex-direction: column; gap: 4px; color: rgba(var(--v-theme-on-surface), .64); font-size: 9px; }
.flow-list-item { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 8px; background: rgb(var(--v-theme-surface-container)); }
.flow-list-item__head { display: flex; grid-column: 1 / -1; align-items: center; justify-content: space-between; font-size: 10px; }

.flow-list-item__inline { display: flex; grid-column: 1 / -1; gap: 10px; align-items: flex-end; }
.flow-list-item__grow { flex: 1; min-width: 0; }
.flow-list-item__inline > label { display: flex; flex-direction: column; gap: 4px; }

/* boolean rows render as a compact switch instead of a bare checkbox */
.flow-switch { display: inline-flex; gap: 7px; align-items: center; min-height: 32px; padding-bottom: 2px; color: rgba(var(--v-theme-on-surface), 0.8); font-size: 10px; cursor: pointer; user-select: none; }
.flow-switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.flow-switch__track { position: relative; flex: 0 0 auto; width: 30px; height: 16px; border-radius: 8px; background: rgba(var(--v-theme-on-surface), 0.25); transition: background 0.15s ease; }
.flow-switch__track::after { content: ''; position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3); transition: transform 0.15s ease; }
.flow-switch input:checked + .flow-switch__track { background: rgb(var(--v-theme-primary)); }
.flow-switch input:checked + .flow-switch__track::after { transform: translateX(14px); }
.flow-switch input:disabled + .flow-switch__track { opacity: 0.45; }
.flow-switch:has(input:focus-visible) .flow-switch__track { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.flow-add-item { display: inline-flex; gap: 5px; align-items: center; justify-content: center; padding: 6px 8px; color: rgb(var(--v-theme-primary)); font: inherit; font-size: 10px; border: 1px dashed rgba(var(--v-theme-primary), .6); border-radius: 7px; background: rgba(var(--v-theme-primary), .05); cursor: pointer; }

/* output viewer */
.flow-output-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px;
  font-size: 11px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.flow-output-card__head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.flow-output-card__badges { display: inline-flex; gap: 5px; align-items: center; }
.flow-output-card span { color: rgba(var(--v-theme-on-surface), .62); font-size: 10px; line-height: 1.4; }

.flow-source-tag {
  padding: 1px 6px;
  color: rgba(var(--v-theme-on-surface), .6);
  font-size: 9px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}
.flow-source-tag--run { color: rgb(var(--v-theme-success)); border-color: rgba(var(--v-theme-success), .5); }
.flow-source-tag--pinned { color: rgb(var(--v-theme-warning)); border-color: rgba(var(--v-theme-warning), .5); }

.flow-output-fields { display: flex; flex-direction: column; gap: 2px; padding: 5px 0; }

.flow-output-row {
  display: flex;
  gap: 5px;
  align-items: center;
  min-height: 24px;
  padding: 2px 0;
  font-size: 10px;
}

.flow-output-row__dot { flex: 0 0 auto; width: 8px; height: 8px; border-radius: 50%; }
.flow-output-row strong { flex: 0 0 auto; max-width: 40%; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.flow-output-row small { flex: 0 0 auto; color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; }
.flow-output-row__value {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: rgba(var(--v-theme-on-surface), .55);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-output-row__copy {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  color: rgba(var(--v-theme-on-surface), .45);
  background: transparent;
  cursor: pointer;
}
.flow-output-row__copy:hover { color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-primary), .1); }

.flow-output-lastrun { display: flex; flex-direction: column; gap: 4px; padding-top: 4px; border-top: 1px dashed rgb(var(--v-theme-outline-variant)); }
.flow-output-lastrun small { color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; }
.flow-output-lastrun pre {
  max-height: 120px;
  margin: 0;
  padding: 6px;
  overflow: auto;
  color: rgba(var(--v-theme-on-surface), .75);
  font-size: 9.5px;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 6px;
  background: rgb(var(--v-theme-surface-container));
}

.flow-output-lastrun__actions { display: flex; gap: 6px; flex-wrap: wrap; }

.flow-mini-button {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 8px;
  color: rgb(var(--v-theme-primary));
  font: inherit;
  font-size: 9.5px;
  border: 1px solid rgba(var(--v-theme-primary), .5);
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), .06);
  cursor: pointer;
}
.flow-mini-button:disabled { opacity: .45; cursor: not-allowed; }
.flow-mini-button--warn { color: rgb(var(--v-theme-warning)); border-color: rgba(var(--v-theme-warning), .5); background: rgba(var(--v-theme-warning), .06); }

/* upstream data preview */
.flow-upstream { margin-bottom: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-upstream summary { display: flex; gap: 6px; align-items: center; padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.flow-upstream summary::-webkit-details-marker { display: none; }
.flow-upstream summary i { color: rgb(var(--v-theme-primary)); font-size: 14px; }
.flow-upstream__hint { margin: 0 0 8px; color: rgba(var(--v-theme-on-surface), .5); font-size: 9.5px; line-height: 1.45; }
.flow-upstream__group { margin-bottom: 10px; padding: 6px 8px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 8px; }
.flow-upstream__head { display: flex; gap: 6px; align-items: center; justify-content: space-between; margin-bottom: 3px; }
.flow-upstream__head strong { font-size: 10.5px; }
.flow-upstream__head small { color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; }

.flow-advanced { margin-bottom: 14px; border-top: 1px solid rgb(var(--v-theme-outline-variant)); }
.flow-advanced summary { padding: 10px 0; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; cursor: pointer; }
.flow-advanced__body { padding-top: 3px; }

.flow-field { display: block; margin-bottom: 14px; }
.flow-field > span { display: block; margin-bottom: 6px; color: rgba(var(--v-theme-on-surface), .68); font-size: 11px; }
.flow-field .cx-textarea { width: 100%; resize: vertical; font-size: 11px; }

.flow-checkbox { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; font-size: 12px; }
</style>
