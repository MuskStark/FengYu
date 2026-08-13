<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  MarkerType,
  VueFlow,
  useVueFlow,
  type Connection,
  type Edge,
  type EdgeMouseEvent,
  type NodeMouseEvent,
  type ValidConnectionFunc,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { api } from '@/api/client'
import { backendUrl, getToken } from '@/api/config'
import type {
  AgentPlan,
  AgentRunConfig,
  AgentRunDetail,
  AgentRunSummary,
  AgentStep,
  AgentTool,
  AiPermissionMode,
  WorkflowDefinition,
  WorkflowDraft,
} from '@/api/types'
import WorkflowToolNode from '@/components/agent/WorkflowToolNode.vue'
import {
  humanizeWorkflowField,
  humanizeWorkflowToolName,
  missingRequiredWorkflowInputs,
  parseWorkflowArguments,
  parseWorkflowSchema,
  reconcileWorkflowArguments,
  topologicallySortWorkflowNodes,
  wouldCreateCycle,
  type WorkflowFlowNode,
  type WorkflowNodeData,
  type WorkflowSchemaProperty,
  workflowToolCategory,
} from '@/components/agent/workflow'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

/**
 * Plan-and-Execute agent UI with AI planning and a deterministic visual workflow canvas.
 *
 * Flow: goal textarea → POST /api/agent/run → open EventSource on
 * /api/agent/stream?runId=… → parse the backend's named SSE events
 * (plan_token / plan_ready / plan_approval_requested / step_start /
 * step_complete / step_approval_requested / complete / error) and update
 * reactive plan/steps/status. The canvas compiles connected tool nodes into
 * the same AgentPlan contract used by AI planning.
 *
 * The SSE wiring mirrors src/api/sse.ts: EventSource can't set headers, so the
 * token (when present) rides as a `token` query param alongside `runId`.
 */

const { t } = useI18n()

// ── reactive state ───────────────────────────────────────────────────────
type ComposerMode = 'ai' | 'canvas'

const composerMode = ref<ComposerMode>('canvas')
const goal = ref('')
const planTokens = ref('') // streamed planner deltas (plan_token)
const plan = ref<AgentPlan | null>(null)
/** Per-index step bookkeeping. Keyed by step.index (from step_start/step_complete). */
const steps = ref<Map<number, AgentStep>>(new Map())
const tools = ref<AgentTool[]>([])
const runId = ref<string | null>(null)
type Status = 'idle' | 'planning' | 'awaiting-plan' | 'running' | 'awaiting-step' | 'complete' | 'error' | 'cancelled'
const status = ref<Status>('idle')
const summary = ref<string | null>(null)
const errorMsg = ref<string | null>(null)
const canvasNodes = shallowRef<WorkflowFlowNode[]>([])
const canvasEdges = shallowRef<Edge[]>([])
const selectedNodeId = ref<string | null>(null)
const paletteOpen = ref(false)
const inspectorOpen = ref(false)
const workflowListOpen = ref(false)
const workflowSettingsOpen = ref(false)
const runDialogOpen = ref(false)
const executionPanelOpen = ref(false)
const toolSearch = ref('')
const runHistory = ref<AgentRunSummary[]>([])
const workflows = ref<WorkflowDefinition[]>([])
const selectedWorkflowId = ref<string | null>(null)
const workflowName = ref('')
const workflowDescription = ref('')
const workflowInputSchemaText = ref('{\n  "type": "object",\n  "properties": {}\n}')
const workflowInputsText = ref('{}')
const workflowPublished = ref(false)
const selectedHistoryId = ref<string | null>(null)
let es: EventSource | null = null
let toolRefreshTimer: ReturnType<typeof setInterval> | null = null
let nodeSequence = 0
const {
  addEdges,
  fitView,
  removeNodes,
  removeEdges,
  screenToFlowCoordinate,
} = useVueFlow('agent-workflow')

// Default AI-planning approval/recovery config. Canvas workflows override this
// per run: the authored plan is already approved, while flagged steps still pause.
const config: AgentRunConfig = {
  requirePlanApproval: true,
  requireStepApproval: false,
  replanOnFailure: false,
  maxReplans: 0,
  permissionMode: 'ask-for-approval',
}
const permissionMode = ref<AiPermissionMode>('ask-for-approval')
const currentRequirePlanApproval = ref(config.requirePlanApproval)

const busy = computed(
  () =>
    status.value === 'planning' ||
    status.value === 'awaiting-plan' ||
    status.value === 'running' ||
    status.value === 'awaiting-step',
)

// Ordered step list (steps Map → array for the template).
const stepList = computed(() => Array.from(steps.value.values()).sort((a, b) => a.index - b.index))
const selectedNode = computed(
  () => canvasNodes.value.find((node) => node.id === selectedNodeId.value) ?? null,
)

interface WorkflowInputSchema {
  type?: string
  title?: string
  description?: string
  default?: unknown
  enum?: unknown[]
  properties?: Record<string, WorkflowInputSchema>
  required?: string[]
  items?: WorkflowInputSchema
  format?: string
  minimum?: number
  maximum?: number
}

const selectedInputSchema = computed<WorkflowInputSchema>(() => {
  try {
    return JSON.parse(selectedNode.value?.data.tool.inputSchema || '{}') as WorkflowInputSchema
  } catch {
    return {}
  }
})
const selectedInputFields = computed(() => Object.entries(selectedInputSchema.value.properties ?? {}))
const selectedRequiredInputs = computed(() => new Set(selectedInputSchema.value.required ?? []))
const selectedArguments = computed<Record<string, unknown>>(() => {
  try {
    const parsed = JSON.parse(selectedNode.value?.data.argsText || '{}')
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
})
const availableSourceNodes = computed(() => {
  if (!selectedNode.value) return []
  return canvasNodes.value.filter((node) => node.id !== selectedNode.value?.id
    && !wouldCreateCycle(canvasEdges.value, node.id, selectedNode.value!.id))
})
const downstreamNodes = computed(() => {
  if (!selectedNode.value) return []
  const targetIds = new Set(
    canvasEdges.value
      .filter((edge) => edge.source === selectedNode.value?.id)
      .map((edge) => edge.target),
  )
  return canvasNodes.value.filter((node) => targetIds.has(node.id))
})
const unavailableNodes = computed(() => canvasNodes.value.filter((node) => !node.data.available))
const filteredTools = computed(() => {
  const query = toolSearch.value.trim().toLocaleLowerCase()
  if (!query) return tools.value
  return tools.value.filter((tool) => `${tool.name} ${tool.localizedDescription || tool.description}`
    .toLocaleLowerCase()
    .includes(query))
})
const groupedTools = computed(() => {
  const groups = new Map<string, AgentTool[]>()
  for (const tool of filteredTools.value) {
    const category = workflowToolCategory(tool)
    groups.set(category, [...(groups.get(category) ?? []), tool])
  }
  return [...groups.entries()]
})
const workflowTitle = computed(() => workflowName.value.trim() || t('agent.untitledWorkflow'))
const selectedOutputFields = computed(() => {
  try {
    const schema = JSON.parse(selectedNode.value?.data.tool.outputSchema || '{}') as WorkflowInputSchema
    return Object.entries(schema.properties ?? {}).filter(([name]) => name !== 'success' && name !== 'summary')
  } catch {
    return []
  }
})
const selectedMissingInputs = computed(() => selectedNode.value
  ? missingRequiredWorkflowInputs(selectedNode.value.data.tool.inputSchema, selectedNode.value.data.argsText)
  : [])
const incompleteNodes = computed(() => canvasNodes.value.filter((node) =>
  missingRequiredWorkflowInputs(node.data.tool.inputSchema, node.data.argsText).length > 0))
const workflowSchema = computed(() => parseWorkflowSchema(workflowInputSchemaText.value))
const workflowSchemaFields = computed(() => Object.entries(workflowSchema.value.properties ?? {}))
const workflowRequiredInputs = computed(() => new Set(workflowSchema.value.required ?? []))
const workflowRunInputs = computed(() => parseWorkflowArguments(workflowInputsText.value) ?? {})

// ── lifecycle ────────────────────────────────────────────────────────────
// Load the tool list once on mount for the "Available tools" hint.
void refreshTools()
onMounted(() => {
  void loadRunHistory()
  void loadWorkflows()
  window.addEventListener('focus', refreshTools)
  toolRefreshTimer = setInterval(() => void refreshTools(), 10_000)
})

onBeforeUnmount(() => {
  closeStream()
  window.removeEventListener('focus', refreshTools)
  if (toolRefreshTimer) clearInterval(toolRefreshTimer)
})

async function refreshTools() {
  try {
    const list = await api.agentTools()
    tools.value = (list ?? []).filter((tool) => tool.pluginId !== 'workflow')
    const byId = new Map(tools.value.map((tool) => [tool.id, tool]))
    const byName = new Map(tools.value.map((tool) => [tool.name, tool]))
    let changed = false
    const reconciled = canvasNodes.value.map((node) => {
      const current = byId.get(node.data.tool.id) ?? byName.get(node.data.tool.name)
      if (!current) {
        if (!node.data.available) return node
        changed = true
        return { ...node, data: { ...node.data, available: false } }
      }
      if (node.data.available && current.revision === node.data.tool.revision) return node
      changed = true
      const argsText = current.revision !== node.data.tool.revision
        ? reconcileWorkflowArguments(node.data.argsText, current.inputSchema)
        : node.data.argsText
      return { ...node, data: { ...node.data, tool: current, argsText, available: true } }
    })
    if (changed) canvasNodes.value = reconciled
  } catch {
    // Keep the last known catalog and node state when the host is temporarily unreachable.
  }
}

async function loadWorkflows() {
  try {
    workflows.value = await api.workflows()
  } catch {
    // The canvas remains usable as an unsaved workflow when definitions cannot be loaded.
  }
}

function newWorkflow() {
  selectedWorkflowId.value = null
  workflowName.value = ''
  workflowDescription.value = ''
  workflowInputSchemaText.value = '{\n  "type": "object",\n  "properties": {}\n}'
  workflowInputsText.value = '{}'
  workflowPublished.value = false
  goal.value = ''
  canvasNodes.value = []
  canvasEdges.value = []
  selectedNodeId.value = null
  workflowListOpen.value = false
  workflowSettingsOpen.value = true
}

function loadWorkflow(definition: WorkflowDefinition) {
  selectedWorkflowId.value = definition.id
  workflowName.value = definition.name
  workflowDescription.value = definition.description
  workflowInputSchemaText.value = JSON.stringify(definition.inputSchema, null, 2)
  workflowPublished.value = definition.published
  goal.value = definition.plan.goal
  const restoredEdges: Edge[] = []
  canvasNodes.value = definition.plan.steps.map((step, index) => {
    const tool = tools.value.find((item) => item.name === step.toolName) ?? {
      id: `missing:${step.toolName}`,
      name: step.toolName,
      description: step.description,
      inputSchema: '{"type":"object","properties":{}}',
      revision: 'missing',
    }
    const id = `node_${++nodeSequence}`
    return {
      id,
      type: 'tool',
      position: { x: 48 + (index % 3) * 290, y: 48 + Math.floor(index / 3) * 150 },
      data: {
        tool,
        argsText: JSON.stringify(step.args ?? {}, null, 2),
        description: step.description,
        requiresApproval: !!step.requiresApproval,
        available: !tool.id.startsWith('missing:'),
      },
    } as WorkflowFlowNode
  })
  for (const step of definition.plan.steps) {
    for (const dependency of step.dependsOn ?? []) {
      const source = canvasNodes.value[dependency]
      const target = canvasNodes.value[step.index]
      if (source && target) restoredEdges.push({
        id: `edge_${source.id}_${target.id}`,
        source: source.id,
        target: target.id,
        type: 'smoothstep',
        markerEnd: MarkerType.ArrowClosed,
      })
    }
  }
  canvasEdges.value = restoredEdges
  workflowListOpen.value = false
  inspectorOpen.value = false
  void nextTick(() => fitCanvas())
}

function parseWorkflowJson(text: string, label: string): Record<string, unknown> {
  try {
    const value = JSON.parse(text || '{}')
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error()
    return value as Record<string, unknown>
  } catch {
    throw new Error(t('agent.invalidWorkflowJson', { label }))
  }
}

async function saveWorkflow() {
  try {
    const draft: WorkflowDraft = {
      name: workflowName.value.trim(),
      description: workflowDescription.value.trim(),
      inputSchema: parseWorkflowJson(workflowInputSchemaText.value, t('agent.inputSchema')),
      plan: compileCanvasWorkflow(),
    }
    const saved = selectedWorkflowId.value
      ? await api.updateWorkflow(selectedWorkflowId.value, draft)
      : await api.createWorkflow(draft)
    selectedWorkflowId.value = saved.id
    workflowPublished.value = saved.published
    await loadWorkflows()
    errorMsg.value = null
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function toggleWorkflowPublication() {
  if (!selectedWorkflowId.value) return
  if (!workflowPublished.value && incompleteNodes.value.length) {
    selectedNodeId.value = incompleteNodes.value[0].id
    inspectorOpen.value = true
    workflowSettingsOpen.value = false
    errorMsg.value = t('agent.publishIncomplete')
    return
  }
  try {
    const saved = await api.publishWorkflow(selectedWorkflowId.value, !workflowPublished.value)
    workflowPublished.value = saved.published
    await Promise.all([loadWorkflows(), refreshTools()])
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function deleteSelectedWorkflow() {
  if (!selectedWorkflowId.value || !window.confirm(t('agent.deleteWorkflowConfirm'))) return
  try {
    await api.deleteWorkflow(selectedWorkflowId.value)
    newWorkflow()
    await Promise.all([loadWorkflows(), refreshTools()])
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

function workflowHasChanges(workflow: AgentPlan, inputSchema: Record<string, unknown>): boolean {
  const saved = workflows.value.find((item) => item.id === selectedWorkflowId.value)
  if (!saved) return true
  return saved.name !== workflowName.value.trim()
    || saved.description !== workflowDescription.value.trim()
    || JSON.stringify(saved.inputSchema) !== JSON.stringify(inputSchema)
    || JSON.stringify(saved.plan) !== JSON.stringify(workflow)
}

async function loadRunHistory() {
  try {
    runHistory.value = await api.agentRuns()
  } catch {
    // History is auxiliary; a current run must remain usable when it cannot be loaded.
  }
}

function executionStatus(statusValue: string): string {
  if (statusValue === 'COMPLETED') return 'complete'
  if (statusValue === 'FAILED') return 'failed'
  if (statusValue === 'RUNNING') return 'running'
  return statusValue.toLowerCase().replaceAll('_', '-')
}

async function showPersistedRun(item: AgentRunSummary): Promise<AgentRunDetail | null> {
  try {
    const detail = await api.agentRunDetail(item.id)
    selectedHistoryId.value = detail.id
    runId.value = detail.id
    goal.value = detail.goal
    plan.value = detail.plan ?? null
    summary.value = detail.summary ?? null
    errorMsg.value = detail.error ?? null
    const restored = new Map<number, AgentStep>()
    for (const step of detail.plan?.steps ?? []) restored.set(step.index, { ...step })
    for (const execution of detail.executions) {
      const step = restored.get(execution.index)
      if (step) step.status = executionStatus(execution.status)
    }
    steps.value = restored
    if (detail.status === 'COMPLETED') status.value = 'complete'
    else if (detail.status === 'CANCELLED') status.value = 'cancelled'
    else if (detail.status === 'FAILED') status.value = 'error'
    return detail
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    return null
  }
}

async function resumePersisted(item: AgentRunSummary) {
  const detail = await showPersistedRun(item)
  if (!detail || !detail.plan || busy.value) return
  errorMsg.value = null
  summary.value = null
  currentRequirePlanApproval.value = true
  status.value = 'planning'
  try {
    const { runId: id } = await api.agentResume(detail.id)
    runId.value = id
    selectedHistoryId.value = id
    openStream(id)
    await loadRunHistory()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    status.value = 'error'
  }
}

// ── visual workflow canvas ───────────────────────────────────────────────

function defaultArgs(tool: AgentTool): Record<string, unknown> {
  try {
    const schema = JSON.parse(tool.inputSchema) as {
      properties?: Record<string, { type?: string; default?: unknown }>
      required?: string[]
    }
    const args: Record<string, unknown> = {}
    for (const name of schema.required ?? []) {
      const property = schema.properties?.[name]
      if (property && 'default' in property) args[name] = property.default
      else if (property?.type === 'array') args[name] = []
      else if (property?.type === 'object') args[name] = {}
      else if (property?.type === 'boolean') args[name] = false
      else if (property?.type === 'number' || property?.type === 'integer') args[name] = 0
      else args[name] = ''
    }
    return args
  } catch {
    return {}
  }
}

function setNodeArgument(name: string, value: unknown) {
  if (!selectedNode.value) return
  selectedNode.value.data.argsText = JSON.stringify({
    ...selectedArguments.value,
    [name]: value,
  }, null, 2)
}

function removeNodeArgument(name: string) {
  if (!selectedNode.value) return
  const next = { ...selectedArguments.value }
  delete next[name]
  selectedNode.value.data.argsText = JSON.stringify(next, null, 2)
}

function inputSource(name: string): string {
  const value = selectedArguments.value[name]
  if (typeof value !== 'string') return 'manual'
  const match = /^\{\{node\.([A-Za-z0-9_-]+)\.result(?:\.([A-Za-z0-9_-]+))?}}$/.exec(value)
  if (match) return `node::${match[1]}${match[2] ? `::${match[2]}` : ''}`
  const workflowInput = /^\{\{inputs\.([A-Za-z0-9_-]+)}}$/.exec(value)
  return workflowInput ? `input::${workflowInput[1]}` : 'manual'
}

function changeInputSource(name: string, schema: WorkflowInputSchema, event: Event) {
  const source = (event.target as HTMLSelectElement).value
  if (source.startsWith('input::')) {
    setNodeArgument(name, `{{inputs.${source.slice(7)}}}`)
    return
  }
  if (source.startsWith('node::')) {
    const [, nodeId, output] = source.split('::')
    setNodeArgument(name, `{{node.${nodeId}.result${output ? `.${output}` : ''}}}`)
    if (selectedNode.value && !canvasEdges.value.some((edge) => edge.source === nodeId && edge.target === selectedNode.value?.id)) {
      addEdges({
        id: `edge_${nodeId}_${selectedNode.value.id}`,
        source: nodeId,
        target: selectedNode.value.id,
        type: 'smoothstep',
        markerEnd: MarkerType.ArrowClosed,
      })
    }
    return
  }
  const current = selectedArguments.value[name]
  if (typeof current === 'string' && /^\{\{node\.[A-Za-z0-9_-]+\.result(?:\.[A-Za-z0-9_-]+)?}}$/.test(current)) {
    setNodeArgument(name, schema.default ?? emptySchemaValue(schema))
  }
}

function updateObjectField(name: string, childName: string, schema: WorkflowInputSchema, event: Event) {
  const current = selectedArguments.value[name]
  const object = current && !Array.isArray(current) && typeof current === 'object'
    ? { ...current as Record<string, unknown> }
    : {}
  object[childName] = valueFromInput(schema, event)
  setNodeArgument(name, object)
}

function valueFromInput(schema: WorkflowInputSchema, event: Event): unknown {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  if (schema.type === 'boolean' && target instanceof HTMLInputElement) return target.checked
  if (schema.type === 'integer' || schema.type === 'number') {
    const parsed = Number(target.value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  if (schema.enum?.length) return schema.enum.find((option) => String(option) === target.value) ?? target.value
  return target.value
}

function objectInputValue(name: string, childName: string): unknown {
  const value = selectedArguments.value[name]
  return value && !Array.isArray(value) && typeof value === 'object'
    ? (value as Record<string, unknown>)[childName] ?? ''
    : ''
}

function arrayObjectItems(name: string): Record<string, unknown>[] {
  const value = selectedArguments.value[name]
  return Array.isArray(value) ? value.filter((item) => item && typeof item === 'object') as Record<string, unknown>[] : []
}

function addArrayObjectItem(name: string) {
  setNodeArgument(name, [...arrayObjectItems(name), {}])
}

function removeArrayObjectItem(name: string, index: number) {
  setNodeArgument(name, arrayObjectItems(name).filter((_, itemIndex) => itemIndex !== index))
}

function updateArrayObjectField(name: string, index: number, childName: string, schema: WorkflowInputSchema, event: Event) {
  const items = arrayObjectItems(name).map((item) => ({ ...item }))
  items[index] = { ...items[index], [childName]: valueFromInput(schema, event) }
  setNodeArgument(name, items)
}

function workflowInputValue(name: string): unknown {
  return workflowRunInputs.value[name] ?? ''
}

function displayWorkflowInputValue(name: string, schema: WorkflowSchemaProperty): string | number {
  const value = workflowInputValue(name)
  if (schema.type === 'array') return Array.isArray(value) ? value.join(', ') : String(value ?? '')
  if (schema.type === 'object') return JSON.stringify(value || {}, null, 2)
  return typeof value === 'number' ? value : String(value ?? '')
}

function setWorkflowInputValue(name: string, schema: WorkflowSchemaProperty, event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  let value: unknown
  if (schema.type === 'array') {
    const items = target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
    value = schema.items?.type === 'integer' || schema.items?.type === 'number' ? items.map(Number) : items
  } else if (schema.type === 'object') {
    try {
      value = JSON.parse(target.value || '{}')
    } catch {
      return
    }
  } else {
    value = valueFromInput(schema as WorkflowInputSchema, event)
  }
  const next = { ...workflowRunInputs.value, [name]: value }
  workflowInputsText.value = JSON.stringify(next, null, 2)
}

function writeWorkflowSchema(schema: Record<string, unknown>) {
  workflowInputSchemaText.value = JSON.stringify(schema, null, 2)
}

function addWorkflowInput() {
  const current = parseWorkflowSchema(workflowInputSchemaText.value)
  const properties = { ...(current.properties ?? {}) }
  let sequence = Object.keys(properties).length + 1
  let name = `input${sequence}`
  while (properties[name]) name = `input${++sequence}`
  properties[name] = { type: 'string', title: t('agent.newInput') }
  writeWorkflowSchema({ ...current, type: 'object', properties })
}

function renameWorkflowInput(oldName: string, event: Event) {
  const nextName = (event.target as HTMLInputElement).value.trim().replace(/[^A-Za-z0-9_-]/g, '')
  if (!nextName || nextName === oldName || workflowSchema.value.properties?.[nextName]) return
  const properties = { ...(workflowSchema.value.properties ?? {}) }
  const property = properties[oldName]
  delete properties[oldName]
  properties[nextName] = property
  const required = (workflowSchema.value.required ?? []).map((name) => name === oldName ? nextName : name)
  const inputs = { ...workflowRunInputs.value }
  if (oldName in inputs) {
    inputs[nextName] = inputs[oldName]
    delete inputs[oldName]
    workflowInputsText.value = JSON.stringify(inputs, null, 2)
  }
  writeWorkflowSchema({ ...workflowSchema.value, properties, required })
}

function updateWorkflowInputProperty(name: string, key: 'title' | 'description' | 'type', event: Event) {
  const properties = { ...(workflowSchema.value.properties ?? {}) }
  const value = (event.target as HTMLInputElement | HTMLSelectElement).value
  properties[name] = { ...properties[name], [key]: value }
  writeWorkflowSchema({ ...workflowSchema.value, properties })
}

function toggleWorkflowInputRequired(name: string, event: Event) {
  const required = new Set(workflowSchema.value.required ?? [])
  if ((event.target as HTMLInputElement).checked) required.add(name)
  else required.delete(name)
  writeWorkflowSchema({ ...workflowSchema.value, required: [...required] })
}

function removeWorkflowInput(name: string) {
  const properties = { ...(workflowSchema.value.properties ?? {}) }
  delete properties[name]
  const required = (workflowSchema.value.required ?? []).filter((item) => item !== name)
  const inputs = { ...workflowRunInputs.value }
  delete inputs[name]
  workflowInputsText.value = JSON.stringify(inputs, null, 2)
  writeWorkflowSchema({ ...workflowSchema.value, properties, required })
}

function toolOutputFields(tool: AgentTool): Array<[string, WorkflowInputSchema]> {
  try {
    const schema = JSON.parse(tool.outputSchema || '{}') as WorkflowInputSchema
    return Object.entries(schema.properties ?? {}).filter(([name]) => name !== 'success' && name !== 'summary')
  } catch {
    return []
  }
}

function emptySchemaValue(schema: WorkflowInputSchema): unknown {
  if (schema.type === 'array') return []
  if (schema.type === 'object') return {}
  if (schema.type === 'boolean') return false
  if (schema.type === 'integer' || schema.type === 'number') return 0
  return ''
}

function displayInputValue(name: string, schema: WorkflowInputSchema): string | number {
  const value = selectedArguments.value[name]
  if (value === undefined || value === null) return ''
  if (schema.type === 'array') return Array.isArray(value) ? value.join(', ') : String(value)
  if (schema.type === 'object') return JSON.stringify(value, null, 2)
  return typeof value === 'number' ? value : String(value)
}

function updateSimpleInput(name: string, schema: WorkflowInputSchema, event: Event) {
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

function addTool(tool: AgentTool, x?: number, y?: number, fitAfterAdd = false) {
  const order = canvasNodes.value.length
  const previous = selectedNode.value
  const node: WorkflowFlowNode = {
    id: `node_${++nodeSequence}`,
    type: 'tool',
    position: {
      x: x ?? (previous ? previous.position.x + 320 : 48 + (order % 3) * 290),
      y: y ?? (previous ? previous.position.y : 48 + Math.floor(order / 3) * 180),
    },
    data: {
      tool,
      argsText: JSON.stringify(defaultArgs(tool), null, 2),
      description: tool.description || tool.name,
      requiresApproval: false,
      available: true,
    },
  }
  canvasNodes.value = [...canvasNodes.value, node]
  if (fitAfterAdd && previous) {
    canvasEdges.value = [...canvasEdges.value, {
      id: `edge_${previous.id}_${node.id}`,
      source: previous.id,
      target: node.id,
      type: 'smoothstep',
      markerEnd: MarkerType.ArrowClosed,
    }]
  }
  selectedNodeId.value = node.id
  inspectorOpen.value = true
  if (fitAfterAdd) {
    void nextTick(() => fitView({ padding: 0.16, duration: 220, maxZoom: 1 }))
  }
}

function onToolDragStart(event: DragEvent, tool: AgentTool) {
  event.dataTransfer?.setData('application/x-fengyu-tool', tool.name)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

function onCanvasDrop(event: DragEvent) {
  const name = event.dataTransfer?.getData('application/x-fengyu-tool')
  const tool = tools.value.find((item) => item.name === name)
  if (!tool) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addTool(tool, position.x - 120, position.y - 54)
}

function removeSelectedNode() {
  const id = selectedNodeId.value
  if (!id) return
  removeNodes(id, true, true)
  selectedNodeId.value = null
  inspectorOpen.value = false
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  inspectorOpen.value = true
  workflowSettingsOpen.value = false
  executionPanelOpen.value = false
}

function onPaneClick() {
  selectedNodeId.value = null
  inspectorOpen.value = false
}

function togglePalette() {
  paletteOpen.value = !paletteOpen.value
  if (paletteOpen.value) {
    workflowListOpen.value = false
    workflowSettingsOpen.value = false
  }
}

function openWorkflowSettings() {
  workflowSettingsOpen.value = true
  workflowListOpen.value = false
  paletteOpen.value = false
  inspectorOpen.value = false
  executionPanelOpen.value = false
}

function openWorkflowLibrary() {
  workflowListOpen.value = true
  workflowSettingsOpen.value = false
  paletteOpen.value = false
  inspectorOpen.value = false
  executionPanelOpen.value = false
}

function toggleExecutionPanel() {
  executionPanelOpen.value = !executionPanelOpen.value
  if (executionPanelOpen.value) {
    workflowSettingsOpen.value = false
    workflowListOpen.value = false
    inspectorOpen.value = false
  }
}

function requestRun() {
  if (composerMode.value === 'canvas') {
    if (incompleteNodes.value.length) {
      selectedNodeId.value = incompleteNodes.value[0].id
      inspectorOpen.value = true
      workflowSettingsOpen.value = false
      executionPanelOpen.value = false
      errorMsg.value = t('agent.incompleteNodes', { count: incompleteNodes.value.length })
      return
    }
    runDialogOpen.value = true
    return
  }
  void run()
}

function fitCanvas() {
  if (canvasNodes.value.length) {
    void fitView({ padding: 0.14, duration: 220, maxZoom: 1 })
  }
}

function onEdgeDoubleClick({ edge }: EdgeMouseEvent) {
  removeEdges(edge.id)
}

function canConnect(connection: Connection): boolean {
  if (busy.value || connection.source === connection.target) return false
  if (canvasEdges.value.some(
    (edge) => edge.source === connection.source && edge.target === connection.target,
  )) return false
  return !wouldCreateCycle(canvasEdges.value, connection.source, connection.target)
}
const isValidConnection: ValidConnectionFunc = (connection) => canConnect(connection)

function onConnect(connection: Connection) {
  if (!canConnect(connection)) return
  addEdges({
    ...connection,
    id: `edge_${connection.source}_${connection.target}`,
    type: 'smoothstep',
    markerEnd: MarkerType.ArrowClosed,
  })
}

function topologicalNodes(): WorkflowFlowNode[] {
  const ordered = topologicallySortWorkflowNodes(canvasNodes.value, canvasEdges.value)
  if (!ordered) throw new Error(t('agent.canvasCycle'))
  return ordered
}

function replaceNodeReferences(
  value: unknown,
  indexes: Map<string, number>,
  currentIndex: number,
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => replaceNodeReferences(item, indexes, currentIndex))
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        replaceNodeReferences(item, indexes, currentIndex),
      ]),
    )
  }
  if (typeof value !== 'string') return value
  return value.replace(/\{\{node\.([A-Za-z0-9_-]+)\.result((?:\.[A-Za-z0-9_-]+)*)}}/g, (_match, id: string, path: string) => {
    const index = indexes.get(id)
    if (index === undefined) throw new Error(t('agent.canvasUnknownReference', { id }))
    if (index >= currentIndex) throw new Error(t('agent.canvasFutureReference', { id }))
    return `{{steps.${index}.result${path}}}`
  })
}

function compileCanvasWorkflow(): AgentPlan {
  if (!canvasNodes.value.length) throw new Error(t('agent.canvasEmpty'))
  if (unavailableNodes.value.length) {
    throw new Error(t('agent.canvasUnavailableTools', {
      names: unavailableNodes.value.map((node) => node.data.tool.name).join(', '),
    }))
  }
  const ordered = topologicalNodes()
  const indexes = new Map(ordered.map((node, index) => [node.id, index]))
  const incoming = new Map<string, number[]>()
  for (const edge of canvasEdges.value) {
    const source = indexes.get(edge.source)
    if (source === undefined || !indexes.has(edge.target)) continue
    const prerequisites = incoming.get(edge.target) ?? []
    prerequisites.push(source)
    incoming.set(edge.target, prerequisites)
  }
  const workflowGoal = goal.value.trim() || t('agent.canvasDefaultGoal')
  const compiledSteps: AgentStep[] = ordered.map((node, index) => {
    const data = node.data as WorkflowNodeData
    let args: unknown
    try {
      args = JSON.parse(data.argsText || '{}')
    } catch {
      throw new Error(t('agent.canvasInvalidArgs', { name: data.tool.name }))
    }
    if (!args || Array.isArray(args) || typeof args !== 'object') {
      throw new Error(t('agent.canvasInvalidArgs', { name: data.tool.name }))
    }
    return {
      index,
      toolName: data.tool.name,
      args: replaceNodeReferences(args, indexes, index) as Record<string, unknown>,
      description: data.description || data.tool.description || data.tool.name,
      requiresApproval: data.requiresApproval,
      dependsOn: [...new Set(incoming.get(node.id) ?? [])].sort((a, b) => a - b),
      status: 'pending',
    }
  })
  return {
    goal: workflowGoal,
    steps: compiledSteps,
    reasoning: t('agent.canvasReasoning'),
  }
}

// ── SSE wiring ───────────────────────────────────────────────────────────

/** Open the SSE stream for a runId and dispatch the backend's named events. */
function openStream(id: string) {
  closeStream()
  const params = new URLSearchParams({ runId: id })
  const token = getToken()
  if (token) params.set('token', token)
  const url = backendUrl(`/api/agent/stream?${params.toString()}`)

  es = new EventSource(url)

  const parse = <T>(ev: Event): T | null => {
    try {
      return JSON.parse((ev as MessageEvent).data) as T
    } catch {
      return null
    }
  }

  // plan_token: a streamed planner delta — append to the live plan preview.
  es.addEventListener('plan_token', (ev) => {
    const d = parse<{ delta: string }>(ev)
    if (d) planTokens.value += d.delta
  })

  // plan_ready: the structured plan arrives; clear the token preview.
  es.addEventListener('plan_ready', (ev) => {
    const d = parse<{ goal: string; steps?: AgentStep[]; reasoning: string }>(ev)
    if (!d) return
    const ps = Array.isArray(d.steps) ? d.steps : []
    plan.value = { goal: d.goal, steps: ps, reasoning: d.reasoning ?? '' }
    // Seed step bookkeeping so the UI can show pending steps immediately.
    for (const s of ps) steps.value.set(s.index, { ...s, status: s.status || 'pending' })
    planTokens.value = ''
    if (currentRequirePlanApproval.value) status.value = 'awaiting-plan'
    else status.value = 'running'
  })

  es.addEventListener('plan_approval_requested', () => {
    status.value = 'awaiting-plan'
  })

  es.addEventListener('step_start', (ev) => {
    const d = parse<{ index: number }>(ev)
    if (!d) return
    const existing = steps.value.get(d.index)
    if (existing) existing.status = 'running'
    else steps.value.set(d.index, { index: d.index, toolName: '', description: '', status: 'running' })
    status.value = 'running'
  })

  es.addEventListener('step_complete', (ev) => {
    const d = parse<{ index: number; result: string }>(ev)
    if (!d) return
    const existing = steps.value.get(d.index)
    if (existing) existing.status = 'complete'
    else steps.value.set(d.index, { index: d.index, toolName: '', description: d.result ?? '', status: 'complete' })
    status.value = 'running'
  })

  es.addEventListener('step_approval_requested', (ev) => {
    const d = parse<{ index: number }>(ev)
    if (d) status.value = 'awaiting-step'
  })

  es.addEventListener('complete', (ev) => {
    const d = parse<{ summary: string }>(ev)
    summary.value = d?.summary ?? ''
    status.value = 'complete'
    closeStream()
    void loadRunHistory()
  })

  // Named "error" event from the backend carries a JSON message; the native
  // EventSource error (connection drop) has no parseable data.
  es.addEventListener('error', (ev) => {
    const d = parse<{ message: string }>(ev)
    if (d?.message) {
      errorMsg.value = d.message
      status.value = 'error'
      closeStream()
      void loadRunHistory()
    } else if (status.value !== 'complete' && status.value !== 'cancelled' && status.value !== 'error') {
      // Native connection drop — surface a generic message but don't necessarily fail the run.
      errorMsg.value = t('agent.failed')
    }
  })

  es.addEventListener('open', () => {
    if (status.value === 'idle') status.value = 'planning'
  })
}

function closeStream() {
  if (es) {
    es.close()
    es = null
  }
}

// ── actions ──────────────────────────────────────────────────────────────

async function run() {
  const g = goal.value.trim()
  if (busy.value || (composerMode.value === 'ai' && !g)) return
  if (composerMode.value === 'canvas' && incompleteNodes.value.length) {
    selectedNodeId.value = incompleteNodes.value[0].id
    inspectorOpen.value = true
    runDialogOpen.value = false
    errorMsg.value = t('agent.incompleteNodes', { count: incompleteNodes.value.length })
    return
  }
  // Reset for a fresh run.
  errorMsg.value = null
  summary.value = null
  plan.value = null
  planTokens.value = ''
  steps.value = new Map()
  status.value = 'planning'
  runDialogOpen.value = false
  executionPanelOpen.value = true

  try {
    const workflow = composerMode.value === 'canvas' ? compileCanvasWorkflow() : undefined
    if (workflow) {
      plan.value = workflow
      for (const step of workflow.steps) steps.value.set(step.index, step)
    }
    const runConfig: AgentRunConfig = workflow
      ? { ...config, requirePlanApproval: false, requireStepApproval: true }
      : { ...config }
    runConfig.permissionMode = permissionMode.value
    currentRequirePlanApproval.value = runConfig.requirePlanApproval
    if (workflow && selectedWorkflowId.value) {
      const inputSchema = parseWorkflowJson(workflowInputSchemaText.value, t('agent.inputSchema'))
      if (workflowHasChanges(workflow, inputSchema)) {
        const saved = await api.updateWorkflow(selectedWorkflowId.value, {
          name: workflowName.value.trim(),
          description: workflowDescription.value.trim(),
          inputSchema,
          plan: workflow,
        })
        workflowPublished.value = saved.published
        await loadWorkflows()
      }
    }
    const response = workflow && selectedWorkflowId.value
      ? await api.runWorkflow(selectedWorkflowId.value, {
          inputs: parseWorkflowJson(workflowInputsText.value, t('agent.workflowInputs')),
          config: runConfig,
        })
      : await api.agentRun({
          goal: workflow?.goal ?? g,
          config: runConfig,
          workflow,
        })
    const id = response.runId
    runId.value = id
    selectedHistoryId.value = id
    openStream(id)
    await loadRunHistory()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
    status.value = 'error'
  }
}

async function approve() {
  if (!runId.value) return
  try {
    // Release the current plan/step gate without replacing the workflow.
    await api.agentApprove(runId.value)
    if (status.value === 'awaiting-plan' || status.value === 'awaiting-step') status.value = 'running'
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function cancel() {
  if (!runId.value) {
    status.value = 'cancelled'
    return
  }
  try {
    await api.agentCancel(runId.value)
  } finally {
    status.value = 'cancelled'
    closeStream()
    void loadRunHistory()
  }
}

// ── status → i18n label ─────────────────────────────────────────────────
const statusLabel = computed(() => {
  switch (status.value) {
    case 'planning':
      return t('agent.planning')
    case 'awaiting-plan':
      return t('agent.waitingPlanApproval')
    case 'awaiting-step':
      return t('agent.waitingStepApproval')
    case 'running':
      return t('agent.running')
    case 'complete':
      return t('agent.completed')
    case 'error':
      return t('agent.failed')
    case 'cancelled':
      return t('agent.cancelled')
    default:
      return ''
  }
})

// Codex chip class per run status / per step status.
const statusChipClass = computed(() => {
  switch (status.value) {
    case 'planning':
    case 'running':
      return 'cx-chip--primary'
    case 'awaiting-plan':
    case 'awaiting-step':
      return 'cx-chip--warn'
    case 'complete':
      return 'cx-chip--success'
    case 'error':
      return 'cx-chip--error'
    default:
      return ''
  }
})
function stepChipClass(s: string): string {
  if (s === 'running') return 'cx-chip--primary'
  if (s === 'complete') return 'cx-chip--success'
  return ''
}
</script>

<template>
  <div class="agent-page" :class="{ 'agent-page--canvas': composerMode === 'canvas' }">
    <div class="cx-page" :class="{ 'cx-page--canvas': composerMode === 'canvas' }">
      <h1 v-if="composerMode === 'ai'" class="cx-page-title">{{ t('agent.title') }}</h1>

      <div v-if="composerMode === 'ai'" class="cx-segment agent-mode" role="tablist">
        <button
          class="active"
          role="tab"
          aria-selected="true"
          :disabled="busy"
          @click="composerMode = 'ai'"
        ><i class="mdi mdi-auto-fix" /> {{ t('agent.aiMode') }}</button>
        <button
          role="tab"
          aria-selected="false"
          :disabled="busy"
          @click="composerMode = 'canvas'"
        ><i class="mdi mdi-vector-polyline" /> {{ t('agent.canvasMode') }}</button>
      </div>

      <details v-if="composerMode === 'ai'" class="cx-details" style="margin-bottom: 12px">
        <summary>{{ t('agent.history') }} ({{ runHistory.length }})</summary>
        <div class="cx-details__body">
          <div v-if="!runHistory.length" class="cx-muted">{{ t('agent.historyEmpty') }}</div>
          <div
            v-for="item in runHistory"
            :key="item.id"
            class="cx-row"
            :style="{
              padding: '7px 0',
              opacity: selectedHistoryId === item.id ? 1 : 0.82,
              borderTop: '1px solid rgb(var(--v-theme-outline-variant))',
            }"
          >
            <button class="cx-grow history-run" :disabled="busy" @click="showPersistedRun(item)">
              <span>{{ item.goal }}</span>
              <small>{{ item.status }} · {{ new Date(item.updatedAt).toLocaleString() }}</small>
            </button>
            <button
              v-if="item.status === 'FAILED' || item.status === 'CANCELLED'"
              class="cx-btn cx-btn--outline"
              :disabled="busy"
              @click="resumePersisted(item)"
            >{{ t('agent.resume') }}</button>
          </div>
        </div>
      </details>

      <!-- Banners -->
      <div v-if="errorMsg && composerMode === 'ai'" class="cx-alert cx-alert--error" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ errorMsg }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
      </div>
      <div v-else-if="summary && status === 'complete' && composerMode === 'ai'" class="cx-alert cx-alert--success" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ summary }}</span>
      </div>

      <!-- Goal composer shared by AI planning and visual workflows -->
      <div v-if="composerMode === 'ai'" class="cx-composer" style="display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px">
        <select v-model="permissionMode" class="cx-select" style="width: 190px" :disabled="busy">
          <option value="ask-for-approval">{{ t('aichat.permissionAsk') }}</option>
          <option value="approve-for-me">{{ t('aichat.permissionAuto') }}</option>
          <option value="full-access">{{ t('aichat.permissionFullAccess') }}</option>
        </select>
        <textarea
          v-model="goal"
          rows="2"
          class="cx-grow"
          style="padding: 8px 0; min-height: 52px"
          :placeholder="t('agent.goalPlaceholder')"
          :disabled="busy"
        />
        <button
          v-if="busy"
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :title="t('agent.cancel')"
          @click="cancel"
        ><i class="mdi mdi-stop" /></button>
        <button
          v-else
          class="cx-iconbtn cx-iconbtn--primary cx-iconbtn--round"
          :disabled="!goal.trim()"
          :title="t('agent.run')"
          @click="requestRun"
        ><i class="mdi mdi-play" /></button>
      </div>

      <!-- Status line -->
      <div v-if="statusLabel && composerMode === 'ai'" class="cx-row" style="margin-bottom: 12px">
        <span v-if="busy" class="cx-spin" />
        <span class="cx-chip" :class="statusChipClass">{{ statusLabel }}</span>
      </div>

      <!-- Visual workflow canvas -->
      <template v-if="composerMode === 'canvas'">
        <div class="workflow-toolbar">
          <button class="workflow-brand" :title="t('agent.openFlows')" @click="openWorkflowLibrary">
            <span class="workflow-brand__mark"><i class="mdi mdi-vector-polyline" /></span>
            <span>
              <strong>{{ workflowTitle }}</strong>
              <small>{{ canvasNodes.length }} {{ t('agent.nodes') }} · {{ workflowPublished ? t('agent.published') : t('agent.draft') }}</small>
            </span>
            <i class="mdi mdi-chevron-down" />
          </button>
          <div class="cx-segment workflow-mode" role="tablist">
            <button class="active" role="tab" aria-selected="true"><i class="mdi mdi-vector-polyline" /> {{ t('agent.canvasMode') }}</button>
            <button role="tab" aria-selected="false" :disabled="busy" @click="composerMode = 'ai'"><i class="mdi mdi-auto-fix" /> {{ t('agent.aiMode') }}</button>
          </div>
          <div class="workflow-toolbar-spacer" />
          <button
            class="workflow-toolbar-button"
            :class="{ active: executionPanelOpen }"
            @click="toggleExecutionPanel"
          ><i class="mdi mdi-history" /> {{ t('agent.runPanel') }}</button>
          <button
            class="workflow-toolbar-button"
            :class="{ active: workflowSettingsOpen }"
            @click="openWorkflowSettings"
          ><i class="mdi mdi-cog-outline" /> {{ t('agent.workflowSettings') }}</button>
          <button
            class="workflow-toolbar-button"
            :disabled="busy || !workflowName.trim() || !canvasNodes.length"
            @click="saveWorkflow"
          ><i class="mdi mdi-content-save-outline" /> {{ t('agent.saveWorkflow') }}</button>
          <button
            class="workflow-run-button"
            :disabled="!busy && (!canvasNodes.length || !!unavailableNodes.length)"
            @click="busy ? cancel() : requestRun()"
          ><i class="mdi" :class="busy ? 'mdi-stop' : 'mdi-play'" /> {{ busy ? t('agent.cancel') : t('agent.testRun') }}</button>
        </div>
        <div
          class="workflow-editor"
          :class="{
            'workflow-editor--palette-closed': !paletteOpen,
            'workflow-editor--inspector-closed': !inspectorOpen,
          }"
        >
        <aside v-show="paletteOpen" class="workflow-palette workflow-overlay-panel">
          <div class="workflow-pane-title">
            {{ t('agent.addNode') }}
            <button class="cx-iconbtn cx-iconbtn--sm" @click="paletteOpen = false"><i class="mdi mdi-close" /></button>
          </div>
          <div class="workflow-search"><i class="mdi mdi-magnify" /><input v-model="toolSearch" :placeholder="t('agent.searchNodes')"></div>
          <div class="cx-muted workflow-help">{{ t('agent.canvasDragHint') }}</div>
          <section v-for="([category, categoryTools]) in groupedTools" :key="category" class="workflow-tool-group">
            <h3><i class="mdi" :class="category === 'browser' ? 'mdi-web' : category === 'email' ? 'mdi-email-outline' : category === 'excel' ? 'mdi-table' : category === 'python' ? 'mdi-language-python' : category === 'skills' ? 'mdi-lightbulb-outline' : 'mdi-puzzle-outline'" /> {{ t(`agent.toolCategory.${category}`) }}</h3>
            <button
              v-for="tool in categoryTools"
              :key="tool.name"
              class="workflow-tool"
              draggable="true"
              :disabled="busy"
              @dragstart="onToolDragStart($event, tool)"
              @click="addTool(tool, undefined, undefined, true); paletteOpen = false"
            >
              <i class="mdi mdi-hammer-wrench" />
              <span>
                <strong>{{ humanizeWorkflowToolName(tool.name) }}</strong>
                <small>{{ tool.localizedDescription || tool.description }}</small>
              </span>
            </button>
          </section>
          <div v-if="!filteredTools.length" class="cx-muted workflow-empty">{{ t('agent.noTools') }}</div>
        </aside>

        <div class="workflow-stage-wrap">
          <VueFlow
            id="agent-workflow"
            v-model:nodes="canvasNodes"
            v-model:edges="canvasEdges"
            class="workflow-stage"
            :min-zoom="0.2"
            :max-zoom="2"
            :fit-view-on-init="true"
            :nodes-draggable="!busy"
            :nodes-connectable="!busy"
            :elements-selectable="!busy"
            :is-valid-connection="isValidConnection"
            :default-edge-options="{
              type: 'smoothstep',
              markerEnd: MarkerType.ArrowClosed,
            }"
            @dragover.prevent
            @drop.prevent="onCanvasDrop"
            @connect="onConnect"
            @node-click="onNodeClick"
            @edge-double-click="onEdgeDoubleClick"
            @pane-click="onPaneClick"
            @nodes-delete="selectedNodeId = null"
          >
            <template #node-tool="nodeProps">
              <WorkflowToolNode v-bind="nodeProps" />
            </template>

            <div v-if="!canvasNodes.length" class="workflow-stage-empty">
              <span class="workflow-stage-empty__icon"><i class="mdi mdi-vector-polyline" /></span>
              <strong>{{ t('agent.canvasHintTitle') }}</strong>
              <span>{{ t('agent.canvasHintBody') }}</span>
              <button class="workflow-run-button" @click.stop="togglePalette"><i class="mdi mdi-plus" /> {{ t('agent.addNode') }}</button>
            </div>
            <Background pattern-color="rgba(255, 255, 255, .14)" :gap="20" />
            <MiniMap
              class="workflow-minimap"
              node-color="rgb(var(--v-theme-primary))"
              mask-color="rgba(0, 0, 0, .55)"
              pannable
              zoomable
            />
            <Controls :show-interactive="false" />
          </VueFlow>
          <div class="workflow-canvas-actions">
            <button :class="{ active: paletteOpen }" @click="togglePalette"><i class="mdi mdi-plus" /> {{ t('agent.addNode') }}</button>
            <button @click="fitCanvas"><i class="mdi mdi-fit-to-screen-outline" /></button>
            <span v-if="incompleteNodes.length" class="workflow-canvas-warning"><i class="mdi mdi-alert-outline" /> {{ t('agent.incompleteNodes', { count: incompleteNodes.length }) }}</span>
          </div>
        </div>

        <aside v-show="inspectorOpen" class="workflow-inspector workflow-right-drawer">
          <template v-if="selectedNode">
            <div class="workflow-pane-title">
              {{ t('agent.nodeSettings') }}
              <span class="cx-row">
                <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteNode')" @click="removeSelectedNode"><i class="mdi mdi-delete-outline" /></button>
                <button class="cx-iconbtn cx-iconbtn--sm" @click="inspectorOpen = false"><i class="mdi mdi-close" /></button>
              </span>
            </div>
            <div v-if="selectedNode.data.tool.localizedDescription || selectedNode.data.tool.description" class="workflow-node-intro">
              <i class="mdi mdi-information-outline" />
              <span>{{ selectedNode.data.tool.localizedDescription || selectedNode.data.tool.description }}</span>
            </div>
            <div v-if="!selectedNode.data.available" class="cx-alert cx-alert--error workflow-tool-unavailable">
              <span class="cx-alert__body">{{ t('agent.toolUnavailable') }}</span>
            </div>

            <section class="workflow-config-section">
              <div class="workflow-section-heading">
                <h3><i class="mdi mdi-login-variant" /> {{ t('agent.inputConfig') }}</h3>
                <span v-if="selectedMissingInputs.length" class="workflow-completion workflow-completion--warn">{{ t('agent.missingInputs', { count: selectedMissingInputs.length }) }}</span>
                <span v-else class="workflow-completion workflow-completion--ready"><i class="mdi mdi-check" /> {{ t('agent.ready') }}</span>
              </div>
              <div v-if="!selectedInputFields.length" class="cx-muted workflow-config-empty">
                {{ t('agent.noInputRequired') }}
              </div>
              <div v-for="([name, schema]) in selectedInputFields" :key="name" class="workflow-argument">
                <div class="workflow-argument__label">
                  <span>{{ schema.title || humanizeWorkflowField(name) }}</span>
                  <span v-if="selectedRequiredInputs.has(name)" class="workflow-required">{{ t('agent.required') }}</span>
                  <span class="workflow-type-chip">{{ t(`agent.fieldType.${schema.type || 'string'}`) }}</span>
                </div>
                <small v-if="schema.description">{{ schema.description }}</small>
                <select
                  v-if="workflowSchemaFields.length || availableSourceNodes.length"
                  class="cx-input workflow-source-select"
                  :value="inputSource(name)"
                  :disabled="busy"
                  @change="changeInputSource(name, schema, $event)"
                >
                  <option value="manual">{{ t('agent.manualInput') }}</option>
                  <optgroup v-if="workflowSchemaFields.length" :label="t('agent.workflowInputSource')">
                    <option v-for="([inputName, inputSchema]) in workflowSchemaFields" :key="`input-${inputName}`" :value="`input::${inputName}`">{{ inputSchema.title || humanizeWorkflowField(inputName) }}</option>
                  </optgroup>
                  <optgroup
                    v-for="node in availableSourceNodes"
                    :key="`${node.id}-fields`"
                    :label="t('agent.nodeOutputFields', { name: node.data.tool.name })"
                  >
                    <option :value="`node::${node.id}`">{{ t('agent.completeResult') }}</option>
                    <option
                      v-for="([outputName, outputSchema]) in toolOutputFields(node.data.tool)"
                      :key="`${node.id}-${outputName}`"
                      :value="`node::${node.id}::${outputName}`"
                    >
                      {{ outputSchema.title || humanizeWorkflowField(outputName) }}
                    </option>
                  </optgroup>
                </select>

                <template v-if="inputSource(name) === 'manual'">
                  <select
                    v-if="schema.enum?.length"
                    class="cx-input"
                    :value="selectedArguments[name] ?? ''"
                    :disabled="busy"
                    @change="updateSimpleInput(name, schema, $event)"
                  >
                    <option v-if="!selectedRequiredInputs.has(name)" value="">{{ t('agent.notSet') }}</option>
                    <option v-for="option in schema.enum" :key="String(option)" :value="option">{{ option }}</option>
                  </select>
                  <label v-else-if="schema.type === 'boolean'" class="workflow-boolean-input">
                    <input
                      type="checkbox"
                      :checked="Boolean(selectedArguments[name])"
                      :disabled="busy"
                      @change="updateSimpleInput(name, schema, $event)"
                    >
                    <span>{{ t('agent.enabled') }}</span>
                  </label>
                  <div v-else-if="schema.type === 'object' && schema.properties" class="workflow-nested-fields">
                    <label v-for="([childName, childSchema]) in Object.entries(schema.properties)" :key="childName">
                      <span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span>
                      <input class="cx-input" :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'" :value="objectInputValue(name, childName)" :placeholder="childSchema.description || t('agent.enterValue')" :disabled="busy" @input="updateObjectField(name, childName, childSchema, $event)">
                    </label>
                  </div>
                  <textarea
                    v-else-if="schema.type === 'object'"
                    class="cx-textarea mono workflow-object-input"
                    rows="3"
                    :value="displayInputValue(name, schema)"
                    :placeholder="t('agent.objectInputPlaceholder')"
                    :disabled="busy"
                    @change="updateObjectInput(name, $event)"
                  />
                  <div v-else-if="schema.type === 'array' && schema.items?.type === 'object' && schema.items.properties" class="workflow-list-builder">
                    <div v-for="(item, itemIndex) in arrayObjectItems(name)" :key="itemIndex" class="workflow-list-item">
                      <div class="workflow-list-item__head"><strong>{{ t('agent.itemNumber', { n: itemIndex + 1 }) }}</strong><button class="cx-iconbtn cx-iconbtn--sm" @click="removeArrayObjectItem(name, itemIndex)"><i class="mdi mdi-delete-outline" /></button></div>
                      <label v-for="([childName, childSchema]) in Object.entries(schema.items.properties)" :key="childName"><span>{{ childSchema.title || humanizeWorkflowField(childName) }}</span><input class="cx-input" :type="childSchema.type === 'number' || childSchema.type === 'integer' ? 'number' : 'text'" :value="item[childName] ?? ''" :disabled="busy" @input="updateArrayObjectField(name, itemIndex, childName, childSchema, $event)"></label>
                    </div>
                    <button class="workflow-add-item" :disabled="busy" @click="addArrayObjectItem(name)"><i class="mdi mdi-plus" /> {{ t('agent.addItem') }}</button>
                  </div>
                  <textarea
                    v-else-if="schema.type === 'array'"
                    class="cx-textarea workflow-array-input"
                    rows="2"
                    :value="displayInputValue(name, schema)"
                    :placeholder="t('agent.arrayInputPlaceholder')"
                    :disabled="busy"
                    @input="updateSimpleInput(name, schema, $event)"
                  />
                  <input
                    v-else
                    class="cx-input"
                    :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'"
                    :value="displayInputValue(name, schema)"
                    :placeholder="schema.description || t('agent.enterValue')"
                    :disabled="busy"
                    @input="updateSimpleInput(name, schema, $event)"
                  >
                </template>
                <div v-else class="workflow-linked-input">
                  <i class="mdi" :class="inputSource(name).startsWith('input::') ? 'mdi-form-textbox' : 'mdi-link-variant'" />
                  <span>{{ inputSource(name).startsWith('input::') ? t('agent.usesWorkflowInput') : t('agent.usesNodeOutput') }}</span>
                  <button class="workflow-clear-link" @click="removeNodeArgument(name)">{{ t('agent.change') }}</button>
                </div>
              </div>
            </section>

            <section class="workflow-config-section">
              <h3><i class="mdi mdi-logout-variant" /> {{ t('agent.outputConfig') }}</h3>
              <div class="workflow-output-card">
                <strong>{{ t('agent.nodeResult') }}</strong>
                <div v-if="selectedOutputFields.length" class="workflow-output-fields">
                  <span v-for="([name, schema]) in selectedOutputFields" :key="name">
                    <i class="mdi mdi-circle-medium" /><strong>{{ schema.title || humanizeWorkflowField(name) }}</strong><small>{{ t(`agent.fieldType.${schema.type || 'string'}`) }}{{ schema.description ? ` · ${schema.description}` : '' }}</small>
                  </span>
                </div>
                <span v-if="downstreamNodes.length">
                  {{ t('agent.outputUsedBy', { count: downstreamNodes.length }) }}
                </span>
                <span v-else>{{ t('agent.outputConnectHint') }}</span>
              </div>
            </section>

            <details class="workflow-advanced">
              <summary>{{ t('agent.advancedSettings') }}</summary>
              <div class="workflow-advanced__body">
                <label class="workflow-field">
                  <span>{{ t('agent.description') }}</span>
                  <input v-model="selectedNode.data.description" class="cx-input" :disabled="busy">
                </label>
                <label class="workflow-field">
                  <span>{{ t('agent.argumentsJson') }}</span>
                  <textarea
                    v-model="selectedNode.data.argsText"
                    class="cx-textarea mono"
                    rows="8"
                    spellcheck="false"
                    :disabled="busy"
                  />
                </label>
              </div>
            </details>
            <label class="workflow-checkbox">
              <input v-model="selectedNode.data.requiresApproval" type="checkbox" :disabled="busy">
              <span>{{ t('agent.requiresApproval') }}</span>
            </label>
          </template>
          <div v-else class="cx-muted workflow-empty">{{ t('agent.selectNode') }}</div>
        </aside>

        <aside v-show="workflowListOpen" class="workflow-library workflow-overlay-panel">
          <div class="workflow-pane-title">
            {{ t('agent.savedWorkflows') }}
            <button class="cx-iconbtn cx-iconbtn--sm" @click="workflowListOpen = false"><i class="mdi mdi-close" /></button>
          </div>
          <button class="workflow-new-card" :disabled="busy" @click="newWorkflow">
            <i class="mdi mdi-plus" />
            <span><strong>{{ t('agent.newWorkflow') }}</strong><small>{{ t('agent.newWorkflowHint') }}</small></span>
          </button>
          <button
            v-for="definition in workflows"
            :key="definition.id"
            class="workflow-definition-item"
            :class="{ active: definition.id === selectedWorkflowId }"
            :disabled="busy"
            @click="loadWorkflow(definition)"
          >
            <span><strong>{{ definition.name }}</strong><small>{{ definition.description || t('agent.noDescription') }}</small></span>
            <span class="cx-chip" :class="definition.published ? 'cx-chip--success' : ''">{{ definition.published ? t('agent.published') : t('agent.draft') }}</span>
          </button>
          <div v-if="!workflows.length" class="cx-muted workflow-empty">{{ t('agent.noSavedWorkflows') }}</div>
        </aside>

        <aside v-show="workflowSettingsOpen" class="workflow-settings workflow-right-drawer">
          <div class="workflow-pane-title">
            {{ t('agent.workflowSettings') }}
            <button class="cx-iconbtn cx-iconbtn--sm" @click="workflowSettingsOpen = false"><i class="mdi mdi-close" /></button>
          </div>
          <label class="workflow-field"><span>{{ t('agent.workflowName') }}</span><input v-model="workflowName" class="cx-input" :disabled="busy"></label>
          <label class="workflow-field"><span>{{ t('agent.workflowDescription') }}</span><textarea v-model="workflowDescription" class="cx-textarea" rows="3" :disabled="busy" /></label>
          <label class="workflow-field"><span>{{ t('agent.canvasGoalPlaceholder') }}</span><textarea v-model="goal" class="cx-textarea" rows="3" :disabled="busy" /></label>
          <section class="workflow-input-designer">
            <div class="workflow-section-heading"><h3><i class="mdi mdi-form-textbox" /> {{ t('agent.workflowInputs') }}</h3><button class="workflow-add-item" :disabled="busy" @click="addWorkflowInput"><i class="mdi mdi-plus" /> {{ t('agent.addInput') }}</button></div>
            <p class="cx-muted">{{ t('agent.workflowInputDesignerHint') }}</p>
            <div v-for="([name, schema]) in workflowSchemaFields" :key="name" class="workflow-input-definition">
              <div class="workflow-input-definition__head">
                <input class="cx-input" :value="name" :disabled="busy" :aria-label="t('agent.variableName')" @change="renameWorkflowInput(name, $event)">
                <select class="cx-select" :value="schema.type || 'string'" :disabled="busy" @change="updateWorkflowInputProperty(name, 'type', $event)"><option value="string">{{ t('agent.fieldType.string') }}</option><option value="number">{{ t('agent.fieldType.number') }}</option><option value="integer">{{ t('agent.fieldType.integer') }}</option><option value="boolean">{{ t('agent.fieldType.boolean') }}</option><option value="array">{{ t('agent.fieldType.array') }}</option><option value="object">{{ t('agent.fieldType.object') }}</option></select>
                <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteWorkflowInput')" @click="removeWorkflowInput(name)"><i class="mdi mdi-delete-outline" /></button>
              </div>
              <input class="cx-input" :value="schema.title || ''" :placeholder="t('agent.inputDisplayName')" :disabled="busy" @input="updateWorkflowInputProperty(name, 'title', $event)">
              <input class="cx-input" :value="schema.description || ''" :placeholder="t('agent.inputHelpText')" :disabled="busy" @input="updateWorkflowInputProperty(name, 'description', $event)">
              <label class="workflow-checkbox"><input type="checkbox" :checked="workflowRequiredInputs.has(name)" :disabled="busy" @change="toggleWorkflowInputRequired(name, $event)"><span>{{ t('agent.requiredAtRun') }}</span></label>
            </div>
            <div v-if="!workflowSchemaFields.length" class="workflow-config-empty">{{ t('agent.noWorkflowInputs') }}</div>
          </section>
          <small class="cx-muted workflow-settings-hint">{{ t('agent.workflowTemplateHint') }}</small>
          <details class="workflow-advanced"><summary>{{ t('agent.advancedSchema') }}</summary><div class="workflow-advanced__body"><textarea v-model="workflowInputSchemaText" class="cx-textarea mono" rows="9" :disabled="busy" /></div></details>
          <div class="workflow-settings-actions">
            <button class="cx-btn cx-btn--primary" :disabled="busy || !workflowName.trim() || !canvasNodes.length" @click="saveWorkflow"><i class="mdi mdi-content-save-outline" /> {{ t('agent.saveWorkflow') }}</button>
            <button v-if="selectedWorkflowId" class="cx-btn cx-btn--outline" :disabled="busy" @click="toggleWorkflowPublication"><i class="mdi" :class="workflowPublished ? 'mdi-eye-off-outline' : 'mdi-robot-outline'" /> {{ workflowPublished ? t('agent.unpublish') : t('agent.publishForAi') }}</button>
            <button v-if="selectedWorkflowId" class="cx-btn cx-btn--outline workflow-delete" :disabled="busy" @click="deleteSelectedWorkflow"><i class="mdi mdi-delete-outline" /> {{ t('agent.deleteWorkflow') }}</button>
          </div>
        </aside>

        <aside v-show="executionPanelOpen" class="workflow-execution workflow-right-drawer">
          <div class="workflow-pane-title">
            {{ t('agent.runPanel') }}
            <button class="cx-iconbtn cx-iconbtn--sm" @click="executionPanelOpen = false"><i class="mdi mdi-close" /></button>
          </div>
          <div v-if="statusLabel" class="workflow-run-status">
            <span v-if="busy" class="cx-spin" />
            <span class="cx-chip" :class="statusChipClass">{{ statusLabel }}</span>
          </div>
          <div v-if="errorMsg" class="cx-alert cx-alert--error"><span class="cx-alert__body">{{ errorMsg }}</span></div>
          <div v-if="summary && status === 'complete'" class="cx-alert cx-alert--success"><span class="cx-alert__body">{{ summary }}</span></div>
          <div v-if="plan" class="workflow-run-steps">
            <div v-for="s in stepList.length ? stepList : plan.steps" :key="s.index" class="workflow-run-step">
              <span class="workflow-step-index">{{ s.index + 1 }}</span>
              <span><strong>{{ s.toolName }}</strong><small>{{ s.description }}</small></span>
              <span class="cx-chip" :class="stepChipClass(s.status)">{{ s.status }}</span>
            </div>
          </div>
          <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="cx-row">
            <button class="cx-btn cx-btn--primary" @click="approve">{{ t('agent.approve') }}</button>
            <button class="cx-btn cx-btn--outline" @click="cancel">{{ t('agent.cancel') }}</button>
          </div>
          <div class="workflow-history-title">{{ t('agent.history') }}</div>
          <div v-if="!runHistory.length" class="cx-muted workflow-empty">{{ t('agent.historyEmpty') }}</div>
          <button v-for="item in runHistory" :key="item.id" class="workflow-history-item" @click="showPersistedRun(item)">
            <span>{{ item.goal }}</span><small>{{ item.status }} · {{ new Date(item.updatedAt).toLocaleString() }}</small>
          </button>
        </aside>
        </div>

        <div v-if="runDialogOpen" class="workflow-dialog-backdrop" @click.self="runDialogOpen = false">
          <section class="workflow-dialog" role="dialog" aria-modal="true">
            <div class="workflow-dialog__icon"><i class="mdi mdi-play" /></div>
            <div class="workflow-dialog__heading"><h2>{{ t('agent.testRun') }}</h2><p>{{ workflowTitle }} · {{ canvasNodes.length }} {{ t('agent.nodes') }}</p></div>
            <button class="cx-iconbtn cx-iconbtn--sm workflow-dialog__close" @click="runDialogOpen = false"><i class="mdi mdi-close" /></button>
            <div v-if="workflowSchemaFields.length" class="workflow-run-form">
              <label v-for="([name, schema]) in workflowSchemaFields" :key="name" class="workflow-field">
                <span>{{ schema.title || humanizeWorkflowField(name) }} <em v-if="workflowRequiredInputs.has(name)">*</em></span>
                <small v-if="schema.description">{{ schema.description }}</small>
                <label v-if="schema.type === 'boolean'" class="workflow-boolean-input"><input type="checkbox" :checked="Boolean(workflowInputValue(name))" :disabled="busy" @change="setWorkflowInputValue(name, schema, $event)"><span>{{ t('agent.enabled') }}</span></label>
                <textarea v-else-if="schema.type === 'array' || schema.type === 'object'" class="cx-textarea" rows="3" :value="displayWorkflowInputValue(name, schema)" :placeholder="schema.type === 'array' ? t('agent.arrayInputPlaceholder') : t('agent.objectInputPlaceholder')" :disabled="busy" @change="setWorkflowInputValue(name, schema, $event)" />
                <input v-else class="cx-input" :type="schema.type === 'integer' || schema.type === 'number' ? 'number' : 'text'" :value="displayWorkflowInputValue(name, schema)" :placeholder="schema.description || t('agent.enterValue')" :disabled="busy" @input="setWorkflowInputValue(name, schema, $event)">
              </label>
            </div>
            <div v-else class="workflow-config-empty">{{ t('agent.noRunInputs') }}</div>
            <label class="workflow-field"><span>{{ t('agent.permissionMode') }}</span><select v-model="permissionMode" class="cx-select" :disabled="busy"><option value="ask-for-approval">{{ t('aichat.permissionAsk') }}</option><option value="approve-for-me">{{ t('aichat.permissionAuto') }}</option><option value="full-access">{{ t('aichat.permissionFullAccess') }}</option></select></label>
            <small class="cx-muted">{{ t('agent.runInputsHint') }}</small>
            <details class="workflow-advanced"><summary>{{ t('agent.advancedJsonInput') }}</summary><div class="workflow-advanced__body"><textarea v-model="workflowInputsText" class="cx-textarea mono" rows="6" :disabled="busy" /></div></details>
            <div v-if="incompleteNodes.length" class="cx-alert cx-alert--error"><span class="cx-alert__body">{{ t('agent.incompleteNodes', { count: incompleteNodes.length }) }}</span></div>
            <div class="workflow-dialog__actions"><button class="cx-btn cx-btn--outline" @click="runDialogOpen = false">{{ t('common.cancel') }}</button><button class="workflow-run-button" :disabled="busy || !!incompleteNodes.length" @click="run"><i class="mdi mdi-play" /> {{ t('agent.startRun') }}</button></div>
          </section>
        </div>
      </template>

      <!-- Available tools in AI-planning mode -->
      <details v-else-if="tools.length" class="cx-details" style="margin-bottom: 12px">
        <summary>{{ t('agent.tools') }} ({{ tools.length }})</summary>
        <div class="cx-details__body">
          <div v-for="tool in tools" :key="tool.name" style="padding: 6px 0">
            <code>{{ tool.name }}</code>
            <div v-if="tool.localizedDescription || tool.description" class="cx-muted" style="font-size: 12px">{{ tool.localizedDescription || tool.description }}</div>
          </div>
        </div>
      </details>

      <!-- Live planner token stream -->
      <div v-if="planTokens && !plan" class="cx-card" style="margin-bottom: 12px">
        <pre class="mono" style="white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; max-height: 240px; overflow-y: auto; font-size: 12px">{{ planTokens }}</pre>
      </div>

      <!-- Plan display -->
      <div v-if="plan" class="cx-card" style="margin-bottom: 12px">
        <div v-if="plan.reasoning" class="cx-muted" style="margin-bottom: 14px; font-size: 13px; overflow-wrap: anywhere">
          {{ plan.reasoning }}
        </div>
        <div
          v-for="s in stepList.length ? stepList : plan.steps"
          :key="s.index"
          class="cx-row"
          style="align-items: flex-start; padding: 7px 0; border-top: 1px solid rgb(var(--v-theme-outline-variant))"
        >
          <span class="cx-muted" style="min-width: 56px; font-size: 12px">{{ t('agent.step', { n: s.index + 1 }) }}</span>
          <div class="cx-grow">
            <span v-if="s.toolName" style="font-weight: 600; margin-right: 8px">{{ s.toolName }}</span>
            <span>{{ s.description }}</span>
          </div>
          <span class="cx-chip" :class="stepChipClass(s.status)">{{ s.status }}</span>
        </div>
      </div>

      <!-- Approval controls -->
      <div v-if="status === 'awaiting-plan' || status === 'awaiting-step'" class="cx-row" style="margin-bottom: 12px">
        <button class="cx-btn cx-btn--primary" @click="approve">{{ t('agent.approve') }}</button>
        <button class="cx-btn cx-btn--outline" @click="cancel">{{ t('agent.cancel') }}</button>
      </div>

      <!-- Empty hint -->
      <div v-if="composerMode === 'ai' && status === 'idle' && !plan" class="cx-muted" style="text-align: center; margin-top: 24px">
        {{ t('agent.empty') }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.cx-page {
  max-width: 1480px;
  padding: 18px 18px 36px;
}

.agent-mode {
  width: fit-content;
  margin-bottom: 12px;
}

.agent-mode button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.history-run {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 0;
  color: inherit;
  text-align: left;
}

.history-run span,
.history-run small {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-toolbar {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 7px;
}

.workflow-toolbar-spacer {
  flex: 1 1 auto;
}

.workflow-toolbar-button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  min-height: 30px;
  padding: 4px 9px;
  color: rgba(var(--v-theme-on-surface), .72);
  font: inherit;
  font-size: 11px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 7px;
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
}

.workflow-toolbar-button:hover,
.workflow-toolbar-button.active {
  color: rgb(var(--v-theme-on-surface));
  border-color: rgba(var(--v-theme-primary), .7);
  background: rgba(var(--v-theme-primary), .1);
}

.workflow-editor {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) 320px;
  height: clamp(560px, calc(100vh - 220px), 760px);
  min-height: 560px;
  margin-bottom: 16px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 12px;
  overflow: hidden;
  background: rgb(var(--v-theme-surface));
}

.workflow-editor--palette-closed {
  grid-template-columns: 0 minmax(0, 1fr) 320px;
}

.workflow-editor--inspector-closed {
  grid-template-columns: 168px minmax(0, 1fr) 0;
}

.workflow-editor--palette-closed.workflow-editor--inspector-closed {
  grid-template-columns: 0 minmax(0, 1fr) 0;
}

.workflow-palette,
.workflow-inspector {
  min-width: 0;
  padding: 11px;
  background: rgb(var(--v-theme-surface));
  overflow-y: auto;
}

.workflow-palette {
  grid-column: 1;
  border-right: 1px solid rgb(var(--v-theme-outline-variant));
}

.workflow-inspector {
  grid-column: 3;
  border-left: 1px solid rgb(var(--v-theme-outline-variant));
}

.workflow-pane-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 30px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 700;
}

.workflow-help,
.workflow-reference-help {
  margin-bottom: 12px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 11px;
  line-height: 1.5;
}

.workflow-tool {
  display: flex;
  width: 100%;
  gap: 7px;
  align-items: flex-start;
  margin-bottom: 6px;
  padding: 8px;
  color: rgb(var(--v-theme-on-surface));
  text-align: left;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 9px;
  background: rgb(var(--v-theme-surface-variant));
  cursor: grab;
}

.workflow-tool:hover {
  border-color: rgb(var(--v-theme-primary));
}

.workflow-tool span,
.workflow-tool small {
  display: block;
  min-width: 0;
}

.workflow-tool strong {
  font-size: 12px;
  overflow-wrap: anywhere;
}

.workflow-tool small {
  margin-top: 3px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 10px;
  line-height: 1.35;
}

.workflow-stage-wrap {
  grid-column: 2;
  min-width: 0;
  min-height: 0;
  background-color: rgb(var(--v-theme-background));
}

.workflow-stage {
  width: 100%;
  height: 100%;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-background));
}

.workflow-stage :deep(.vue-flow__node-tool) {
  width: 168px;
  padding: 0;
  border: 0;
  background: transparent;
}

.workflow-stage :deep(.vue-flow__edge-path),
.workflow-stage :deep(.vue-flow__connection-path) {
  stroke: rgb(var(--v-theme-primary));
  stroke-width: 2;
}

.workflow-stage :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke-width: 3;
}

.workflow-stage :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  box-shadow: none;
}

.workflow-stage :deep(.vue-flow__controls-button) {
  color: rgb(var(--v-theme-on-surface));
  border-color: rgb(var(--v-theme-outline-variant));
  background: rgb(var(--v-theme-surface));
  fill: currentColor;
}

.workflow-stage :deep(.vue-flow__controls-button:hover) {
  background: rgb(var(--v-theme-surface-variant));
}

.workflow-stage :deep(.vue-flow__minimap) {
  width: 132px;
  height: 84px;
  overflow: hidden;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  background: rgb(var(--v-theme-surface));
}

.workflow-stage-empty {
  position: absolute;
  inset: 0;
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: center;
  color: rgba(var(--v-theme-on-surface), .68);
  pointer-events: none;
}

.workflow-stage-empty i {
  font-size: 24px;
}

.workflow-node-intro {
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

.workflow-node-intro i {
  flex: 0 0 auto;
  color: rgb(var(--v-theme-primary));
  font-size: 15px;
}

.workflow-tool-unavailable {
  margin-bottom: 14px;
  font-size: 11px;
}

.workflow-config-section {
  margin-bottom: 18px;
}

.workflow-config-section h3 {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 0 0 9px;
  font-size: 12px;
}

.workflow-config-section h3 i {
  color: rgb(var(--v-theme-primary));
  font-size: 15px;
}

.workflow-section-heading {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 9px;
}
.workflow-section-heading h3 { margin: 0; }
.workflow-completion { display: inline-flex; gap: 3px; align-items: center; padding: 3px 7px; font-size: 9px; font-weight: 650; border-radius: 10px; }
.workflow-completion--ready { color: rgb(var(--v-theme-success)); background: rgba(var(--v-theme-success), .12); }
.workflow-completion--warn { color: rgb(var(--v-theme-warning)); background: rgba(var(--v-theme-warning), .14); }

.workflow-config-empty {
  padding: 10px;
  font-size: 11px;
  border: 1px dashed rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.workflow-argument {
  margin-bottom: 11px;
  padding: 9px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.workflow-argument__label {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 5px;
  font-size: 11px;
  font-weight: 650;
}

.workflow-argument > small {
  display: block;
  margin: -1px 0 7px;
  color: rgba(var(--v-theme-on-surface), .6);
  font-size: 10px;
  line-height: 1.4;
}

.workflow-required {
  padding: 1px 5px;
  color: rgb(var(--v-theme-primary));
  font-size: 9px;
  font-weight: 600;
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.workflow-type-chip { margin-left: auto; color: rgba(var(--v-theme-on-surface), .5); font-size: 9px; font-weight: 500; }

.workflow-source-select {
  margin-bottom: 7px;
}

.workflow-argument .cx-input,
.workflow-argument .cx-textarea {
  width: 100%;
  font-size: 11px;
}

.workflow-boolean-input {
  display: flex;
  gap: 7px;
  align-items: center;
  min-height: 30px;
  font-size: 11px;
}

.workflow-array-input,
.workflow-object-input {
  resize: vertical;
}

.workflow-linked-input {
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 30px;
  padding: 6px 8px;
  color: rgb(var(--v-theme-primary));
  font-size: 10px;
  border-radius: 6px;
  background: rgba(var(--v-theme-primary), .08);
}
.workflow-linked-input span { flex: 1; }
.workflow-clear-link { padding: 2px 5px; color: inherit; font: inherit; font-size: 9px; border: 0; border-radius: 5px; background: rgba(var(--v-theme-primary), .1); cursor: pointer; }

.workflow-nested-fields,
.workflow-list-builder { display: flex; flex-direction: column; gap: 8px; }
.workflow-nested-fields label,
.workflow-list-item label { display: flex; flex-direction: column; gap: 4px; color: rgba(var(--v-theme-on-surface), .64); font-size: 9px; }
.workflow-list-item { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 8px; background: rgb(var(--v-theme-surface-container)); }
.workflow-list-item__head { display: flex; grid-column: 1 / -1; align-items: center; justify-content: space-between; font-size: 10px; }
.workflow-add-item { display: inline-flex; gap: 5px; align-items: center; justify-content: center; padding: 6px 8px; color: rgb(var(--v-theme-primary)); font: inherit; font-size: 10px; border: 1px dashed rgba(var(--v-theme-primary), .6); border-radius: 7px; background: rgba(var(--v-theme-primary), .05); cursor: pointer; }

.workflow-output-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px;
  font-size: 11px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
}

.workflow-output-card span {
  color: rgba(var(--v-theme-on-surface), .62);
  font-size: 10px;
  line-height: 1.4;
}

.workflow-output-fields {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 5px 0;
}
.workflow-output-fields span { display: grid; grid-template-columns: auto auto 1fr; gap: 3px; align-items: center; padding: 5px 0; }
.workflow-output-fields i { color: rgb(var(--v-theme-primary)); }
.workflow-output-fields strong { color: rgb(var(--v-theme-on-surface)); font-size: 10px; }
.workflow-output-fields small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.workflow-definition-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: rgb(var(--v-theme-surface-container));
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.workflow-definition-item.active {
  border-color: rgb(var(--v-theme-primary));
}

.workflow-definition-item small {
  color: rgba(var(--v-theme-on-surface), .62);
}

.workflow-advanced {
  margin-bottom: 14px;
  border-top: 1px solid rgb(var(--v-theme-outline-variant));
}

.workflow-advanced summary {
  padding: 10px 0;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 11px;
  cursor: pointer;
}

.workflow-advanced__body {
  padding-top: 3px;
}

.workflow-field {
  display: block;
  margin-bottom: 14px;
}

.workflow-field > span {
  display: block;
  margin-bottom: 6px;
  color: rgba(var(--v-theme-on-surface), .68);
  font-size: 11px;
}

.workflow-field .cx-textarea {
  width: 100%;
  resize: vertical;
  font-size: 11px;
}

.workflow-checkbox {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 14px;
  font-size: 12px;
}

.workflow-empty {
  padding: 20px 4px;
  text-align: center;
  font-size: 12px;
}

@media (max-width: 1050px) {
  .workflow-editor {
    grid-template-columns: 150px minmax(0, 1fr) 280px;
  }

  .workflow-editor--palette-closed {
    grid-template-columns: 0 minmax(0, 1fr) 280px;
  }

  .workflow-editor--inspector-closed {
    grid-template-columns: 150px minmax(0, 1fr) 0;
  }

  .workflow-editor--palette-closed.workflow-editor--inspector-closed {
    grid-template-columns: 0 minmax(0, 1fr) 0;
  }
}

/* Flowise-inspired workbench: a persistent canvas with contextual overlays. */
.agent-page {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}

.agent-page--canvas {
  overflow: hidden;
  background: rgb(var(--v-theme-background));
}

.cx-page--canvas {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: none;
  height: 100%;
  padding: 0;
}

.cx-page--canvas .workflow-toolbar {
  z-index: 20;
  min-height: 66px;
  margin: 0;
  padding: 9px 14px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 2px 10px rgba(0, 0, 0, .07);
}

.workflow-brand {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 230px;
  padding: 5px 8px;
  color: inherit;
  text-align: left;
  border: 0;
  border-radius: 9px;
  background: transparent;
  cursor: pointer;
}

.workflow-brand:hover { background: rgba(var(--v-theme-on-surface), .055); }
.workflow-brand > span:not(.workflow-brand__mark) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.workflow-brand strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-brand small { color: rgba(var(--v-theme-on-surface), .56); font-size: 10px; }

.workflow-brand__mark,
.workflow-stage-empty__icon,
.workflow-dialog__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  color: rgb(var(--v-theme-primary));
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.workflow-mode { margin-left: 12px; }
.workflow-mode button { display: inline-flex; gap: 5px; align-items: center; }

.workflow-run-button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 6px 14px;
  color: rgb(var(--v-theme-on-primary));
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  border: 0;
  border-radius: 8px;
  background: rgb(var(--v-theme-primary));
  box-shadow: 0 4px 12px rgba(var(--v-theme-primary), .2);
  cursor: pointer;
}

.workflow-run-button:disabled,
.workflow-toolbar-button:disabled { opacity: .45; cursor: not-allowed; }

.cx-page--canvas .workflow-editor,
.cx-page--canvas .workflow-editor--palette-closed,
.cx-page--canvas .workflow-editor--inspector-closed,
.cx-page--canvas .workflow-editor--palette-closed.workflow-editor--inspector-closed {
  position: relative;
  display: block;
  flex: 1 1 auto;
  width: 100%;
  height: auto;
  min-height: 0;
  margin: 0;
  border: 0;
  border-radius: 0;
  overflow: hidden;
}

.cx-page--canvas .workflow-stage-wrap {
  position: absolute;
  inset: 0;
  background: rgb(var(--v-theme-background));
}

.workflow-stage :deep(.vue-flow__node-tool) { width: 240px; }
.workflow-stage :deep(.vue-flow__background) { opacity: .75; }

.workflow-overlay-panel,
.workflow-right-drawer {
  position: absolute;
  z-index: 15;
  top: 14px;
  bottom: 14px;
  width: min(360px, calc(100% - 28px));
  padding: 16px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 12px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 18px 48px rgba(0, 0, 0, .22);
  overflow-y: auto;
}

.workflow-overlay-panel { left: 14px; }
.workflow-right-drawer { right: 14px; }
.workflow-palette,
.workflow-inspector { grid-column: auto; border: 1px solid rgb(var(--v-theme-outline-variant)); }

.workflow-library { display: flex; flex-direction: column; gap: 8px; }
.workflow-library .workflow-pane-title { margin-bottom: 2px; }

.workflow-new-card,
.workflow-library .workflow-definition-item {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 11px;
  color: inherit;
  text-align: left;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 10px;
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
}

.workflow-new-card:hover,
.workflow-library .workflow-definition-item:hover,
.workflow-library .workflow-definition-item.active { border-color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-primary), .06); }
.workflow-new-card > i { display: grid; place-items: center; width: 32px; height: 32px; color: rgb(var(--v-theme-primary)); border-radius: 9px; background: rgba(var(--v-theme-primary), .12); }
.workflow-new-card span,
.workflow-library .workflow-definition-item > span:first-child { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.workflow-new-card small,
.workflow-library .workflow-definition-item small { overflow: hidden; color: rgba(var(--v-theme-on-surface), .58); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }

.workflow-search {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-bottom: 10px;
  padding: 0 10px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  background: rgb(var(--v-theme-surface-container));
}

.workflow-search input { width: 100%; min-height: 36px; color: inherit; border: 0; outline: 0; background: transparent; }
.workflow-tool { margin-bottom: 7px; padding: 10px; background: rgb(var(--v-theme-surface)); }
.workflow-tool-group { margin-bottom: 14px; }
.workflow-tool-group h3 { display: flex; gap: 6px; align-items: center; margin: 0 0 6px; color: rgba(var(--v-theme-on-surface), .58); font-size: 9px; text-transform: uppercase; letter-spacing: .065em; }
.workflow-tool-group h3 i { color: rgb(var(--v-theme-primary)); font-size: 13px; }
.workflow-tool > i { display: grid; place-items: center; width: 30px; height: 30px; color: rgb(var(--v-theme-primary)); border-radius: 8px; background: rgba(var(--v-theme-primary), .1); }
.workflow-tool strong { font-size: 12px; }
.workflow-tool small { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }

.workflow-canvas-actions {
  position: absolute;
  z-index: 7;
  top: 16px;
  left: 16px;
  display: flex;
  gap: 6px;
}

.workflow-canvas-actions button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  min-height: 36px;
  padding: 7px 11px;
  color: inherit;
  font: inherit;
  font-size: 11px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 9px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 5px 16px rgba(0, 0, 0, .12);
  cursor: pointer;
}

.workflow-canvas-actions button.active,
.workflow-canvas-actions button:hover { color: rgb(var(--v-theme-primary)); border-color: rgb(var(--v-theme-primary)); }
.workflow-canvas-warning { display: inline-flex; gap: 5px; align-items: center; min-height: 36px; padding: 7px 10px; color: rgb(var(--v-theme-warning)); font-size: 10px; border: 1px solid rgba(var(--v-theme-warning), .45); border-radius: 9px; background: rgb(var(--v-theme-surface)); box-shadow: 0 5px 16px rgba(0, 0, 0, .1); }

.workflow-stage-empty { flex-direction: column; gap: 8px; text-align: center; }
.workflow-stage-empty strong { color: rgb(var(--v-theme-on-surface)); font-size: 16px; }
.workflow-stage-empty > span:not(.workflow-stage-empty__icon) { max-width: 310px; font-size: 12px; line-height: 1.5; }
.workflow-stage-empty__icon { width: 52px; height: 52px; font-size: 23px; }
.workflow-stage-empty .workflow-run-button { margin-top: 5px; pointer-events: auto; }

.workflow-settings-hint { display: block; margin: -4px 0 16px; line-height: 1.5; }
.workflow-input-designer { margin-bottom: 14px; }
.workflow-input-designer > p { margin: -3px 0 10px; font-size: 10px; line-height: 1.45; }
.workflow-input-definition { display: flex; flex-direction: column; gap: 7px; margin-bottom: 8px; padding: 10px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 9px; background: rgb(var(--v-theme-surface-container)); }
.workflow-input-definition__head { display: grid; grid-template-columns: minmax(0, 1fr) 110px auto; gap: 6px; }
.workflow-input-definition .cx-input,
.workflow-input-definition .cx-select { width: 100%; font-size: 10px; }
.workflow-input-definition .workflow-checkbox { margin: 0; }
.workflow-settings-actions { display: flex; flex-direction: column; gap: 8px; }
.workflow-settings-actions .cx-btn { justify-content: center; }
.workflow-delete { color: rgb(var(--v-theme-error)); }

.workflow-run-status { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.workflow-run-steps { display: flex; flex-direction: column; gap: 7px; margin: 12px 0 18px; }
.workflow-run-step { display: flex; gap: 8px; align-items: center; padding: 9px; border: 1px solid rgb(var(--v-theme-outline-variant)); border-radius: 9px; }
.workflow-run-step > span:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.workflow-run-step small { overflow: hidden; color: rgba(var(--v-theme-on-surface), .6); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-step-index { display: grid; place-items: center; width: 23px; height: 23px; color: rgb(var(--v-theme-primary)); font-size: 10px; border-radius: 50%; background: rgba(var(--v-theme-primary), .12); }
.workflow-history-title { margin: 18px 0 7px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .06em; }
.workflow-history-item { display: flex; width: 100%; flex-direction: column; gap: 2px; padding: 8px 0; color: inherit; text-align: left; border: 0; border-top: 1px solid rgb(var(--v-theme-outline-variant)); background: transparent; cursor: pointer; }
.workflow-history-item span,
.workflow-history-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-history-item small { color: rgba(var(--v-theme-on-surface), .55); font-size: 10px; }

.workflow-dialog-backdrop {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(0, 0, 0, .48);
  backdrop-filter: blur(3px);
}

.workflow-dialog {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 14px;
  width: min(560px, 100%);
  max-height: calc(100vh - 40px);
  padding: 22px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 14px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 24px 72px rgba(0, 0, 0, .35);
  overflow-y: auto;
}

.workflow-dialog__heading h2 { margin: 0 0 2px; font-size: 18px; }
.workflow-dialog__heading p { margin: 0; color: rgba(var(--v-theme-on-surface), .58); font-size: 11px; }
.workflow-dialog__close { position: absolute; top: 14px; right: 14px; }
.workflow-dialog > .workflow-field,
.workflow-dialog > .workflow-run-form,
.workflow-dialog > .workflow-config-empty,
.workflow-dialog > .workflow-advanced,
.workflow-dialog > .cx-alert,
.workflow-dialog > small,
.workflow-dialog__actions { grid-column: 1 / -1; }
.workflow-run-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.workflow-run-form .workflow-field { margin: 0; }
.workflow-run-form .workflow-field > small { display: block; margin: -2px 0 5px; color: rgba(var(--v-theme-on-surface), .55); font-size: 9px; }
.workflow-run-form em { color: rgb(var(--v-theme-error)); font-style: normal; }
.workflow-dialog__actions { display: flex; gap: 8px; justify-content: flex-end; }
.workflow-dialog .cx-select { width: 100%; }

@media (max-width: 850px) {
  .workflow-mode,
  .workflow-toolbar-button { display: none; }
  .workflow-brand { min-width: 0; flex: 1; }
  .workflow-brand > span:not(.workflow-brand__mark) { max-width: 180px; }
  .workflow-run-button { padding-inline: 11px; }
  .workflow-overlay-panel,
  .workflow-right-drawer { inset: 8px; width: auto; }
  .workflow-stage :deep(.vue-flow__minimap) { display: none; }
  .workflow-run-form { grid-template-columns: 1fr; }
}
</style>
