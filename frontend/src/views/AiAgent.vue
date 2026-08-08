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
} from '@/api/types'
import WorkflowToolNode from '@/components/agent/WorkflowToolNode.vue'
import {
  reconcileWorkflowArguments,
  topologicallySortWorkflowNodes,
  wouldCreateCycle,
  type WorkflowFlowNode,
  type WorkflowNodeData,
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

const composerMode = ref<ComposerMode>('ai')
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
const paletteOpen = ref(true)
const inspectorOpen = ref(false)
const runHistory = ref<AgentRunSummary[]>([])
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
const upstreamNodes = computed(() => {
  if (!selectedNode.value) return []
  const sourceIds = new Set(
    canvasEdges.value
      .filter((edge) => edge.target === selectedNode.value?.id)
      .map((edge) => edge.source),
  )
  return canvasNodes.value.filter((node) => sourceIds.has(node.id))
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
const selectedOutputFields = computed(() => {
  try {
    const schema = JSON.parse(selectedNode.value?.data.tool.outputSchema || '{}') as WorkflowInputSchema
    return Object.entries(schema.properties ?? {}).filter(([name]) => name !== 'success' && name !== 'summary')
  } catch {
    return []
  }
})

// ── lifecycle ────────────────────────────────────────────────────────────
// Load the tool list once on mount for the "Available tools" hint.
void refreshTools()
onMounted(() => {
  void loadRunHistory()
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
    tools.value = list ?? []
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

function inputSource(name: string): string {
  const value = selectedArguments.value[name]
  if (typeof value !== 'string') return 'manual'
  const match = /^\{\{node\.([A-Za-z0-9_-]+)\.result(?:\.([A-Za-z0-9_-]+))?}}$/.exec(value)
  return match ? `${match[1]}${match[2] ? `::${match[2]}` : ''}` : 'manual'
}

function changeInputSource(name: string, schema: WorkflowInputSchema, event: Event) {
  const source = (event.target as HTMLSelectElement).value
  if (source !== 'manual') {
    const [nodeId, output] = source.split('::')
    setNodeArgument(name, `{{node.${nodeId}.result${output ? `.${output}` : ''}}}`)
    return
  }
  const current = selectedArguments.value[name]
  if (typeof current === 'string' && /^\{\{node\.[A-Za-z0-9_-]+\.result(?:\.[A-Za-z0-9_-]+)?}}$/.test(current)) {
    setNodeArgument(name, schema.default ?? emptySchemaValue(schema))
  }
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
  if (schema.type === 'boolean' && target instanceof HTMLInputElement) {
    setNodeArgument(name, target.checked)
    return
  }
  if (schema.type === 'integer' || schema.type === 'number') {
    const parsed = Number(target.value)
    setNodeArgument(name, Number.isFinite(parsed) ? parsed : 0)
    return
  }
  if (schema.enum?.length) {
    setNodeArgument(name, schema.enum.find((option) => String(option) === target.value) ?? target.value)
    return
  }
  if (schema.type === 'array') {
    const items = target.value.split(/[,\n]/).map((item) => item.trim()).filter(Boolean)
    setNodeArgument(name, schema.items?.type === 'integer' || schema.items?.type === 'number'
      ? items.map(Number)
      : items)
    return
  }
  setNodeArgument(name, target.value)
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
  const node: WorkflowFlowNode = {
    id: `node_${++nodeSequence}`,
    type: 'tool',
    position: {
      x: x ?? 36 + (order % 3) * 210,
      y: y ?? 36 + Math.floor(order / 3) * 110,
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
  selectedNodeId.value = node.id
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
  addTool(tool, position.x - 84, position.y - 36)
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
}

function onPaneClick() {
  selectedNodeId.value = null
  inspectorOpen.value = false
}

function togglePalette() {
  paletteOpen.value = !paletteOpen.value
  void nextTick(() => fitCanvas())
}

function toggleInspector() {
  inspectorOpen.value = !inspectorOpen.value
  void nextTick(() => fitCanvas())
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
  // Reset for a fresh run.
  errorMsg.value = null
  summary.value = null
  plan.value = null
  planTokens.value = ''
  steps.value = new Map()
  status.value = 'planning'

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
    const { runId: id } = await api.agentRun({
      goal: workflow?.goal ?? g,
      config: runConfig,
      workflow,
    })
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
  <div style="flex: 1 1 auto; min-height: 0; overflow-y: auto">
    <div class="cx-page">
      <h1 class="cx-page-title">{{ t('agent.title') }}</h1>

      <div class="cx-segment agent-mode" role="tablist">
        <button
          :class="{ active: composerMode === 'ai' }"
          role="tab"
          :aria-selected="composerMode === 'ai'"
          :disabled="busy"
          @click="composerMode = 'ai'"
        ><i class="mdi mdi-auto-fix" /> {{ t('agent.aiMode') }}</button>
        <button
          :class="{ active: composerMode === 'canvas' }"
          role="tab"
          :aria-selected="composerMode === 'canvas'"
          :disabled="busy"
          @click="composerMode = 'canvas'"
        ><i class="mdi mdi-vector-polyline" /> {{ t('agent.canvasMode') }}</button>
      </div>

      <details class="cx-details" style="margin-bottom: 12px">
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
      <div v-if="errorMsg" class="cx-alert cx-alert--error" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ errorMsg }}</span>
        <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
      </div>
      <div v-else-if="summary && status === 'complete'" class="cx-alert cx-alert--success" style="margin-bottom: 12px">
        <span class="cx-alert__body">{{ summary }}</span>
      </div>

      <!-- Goal composer shared by AI planning and visual workflows -->
      <div class="cx-composer" style="display: flex; align-items: flex-end; gap: 8px; margin-bottom: 12px">
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
          :placeholder="composerMode === 'canvas' ? t('agent.canvasGoalPlaceholder') : t('agent.goalPlaceholder')"
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
          :disabled="composerMode === 'ai' ? !goal.trim() : !canvasNodes.length || !!unavailableNodes.length"
          :title="composerMode === 'canvas' ? t('agent.runWorkflow') : t('agent.run')"
          @click="run"
        ><i class="mdi mdi-play" /></button>
      </div>

      <!-- Status line -->
      <div v-if="statusLabel" class="cx-row" style="margin-bottom: 12px">
        <span v-if="busy" class="cx-spin" />
        <span class="cx-chip" :class="statusChipClass">{{ statusLabel }}</span>
      </div>

      <!-- Visual workflow canvas -->
      <template v-if="composerMode === 'canvas'">
        <div class="workflow-toolbar">
          <button
            class="workflow-toolbar-button"
            :class="{ active: paletteOpen }"
            :title="t('agent.canvasToggleTools')"
            :aria-pressed="paletteOpen"
            @click="togglePalette"
          ><i class="mdi mdi-toolbox-outline" /> {{ t('agent.tools') }}</button>
          <div class="workflow-toolbar-spacer" />
          <button
            class="workflow-toolbar-button"
            :title="t('agent.canvasFitView')"
            @click="fitCanvas"
          ><i class="mdi mdi-fit-to-screen-outline" /> {{ t('agent.canvasFitView') }}</button>
          <button
            class="workflow-toolbar-button"
            :class="{ active: inspectorOpen }"
            :title="t('agent.canvasToggleInspector')"
            :aria-pressed="inspectorOpen"
            @click="toggleInspector"
          ><i class="mdi mdi-tune-variant" /> {{ t('agent.nodeSettings') }}</button>
        </div>
        <div
          class="workflow-editor"
          :class="{
            'workflow-editor--palette-closed': !paletteOpen,
            'workflow-editor--inspector-closed': !inspectorOpen,
          }"
        >
        <aside v-show="paletteOpen" class="workflow-palette">
          <div class="workflow-pane-title">{{ t('agent.tools') }}</div>
          <div class="cx-muted workflow-help">{{ t('agent.canvasDragHint') }}</div>
          <button
            v-for="tool in tools"
            :key="tool.name"
            class="workflow-tool"
            draggable="true"
            :disabled="busy"
            @dragstart="onToolDragStart($event, tool)"
            @dblclick="addTool(tool, undefined, undefined, true)"
          >
            <i class="mdi mdi-hammer-wrench" />
            <span>
              <strong>{{ tool.name }}</strong>
              <small>{{ tool.localizedDescription || tool.description }}</small>
            </span>
          </button>
          <div v-if="!tools.length" class="cx-muted workflow-empty">{{ t('agent.noTools') }}</div>
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
              <i class="mdi mdi-vector-polyline" />
              <span>{{ t('agent.canvasEmptyHint') }}</span>
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
        </div>

        <aside v-show="inspectorOpen" class="workflow-inspector">
          <template v-if="selectedNode">
            <div class="workflow-pane-title">
              {{ t('agent.nodeSettings') }}
              <button class="cx-iconbtn cx-iconbtn--sm" :title="t('agent.deleteNode')" @click="removeSelectedNode">
                <i class="mdi mdi-delete-outline" />
              </button>
            </div>
            <div v-if="selectedNode.data.tool.localizedDescription || selectedNode.data.tool.description" class="workflow-node-intro">
              <i class="mdi mdi-information-outline" />
              <span>{{ selectedNode.data.tool.localizedDescription || selectedNode.data.tool.description }}</span>
            </div>
            <div v-if="!selectedNode.data.available" class="cx-alert cx-alert--error workflow-tool-unavailable">
              <span class="cx-alert__body">{{ t('agent.toolUnavailable') }}</span>
            </div>

            <section class="workflow-config-section">
              <h3><i class="mdi mdi-login-variant" /> {{ t('agent.inputConfig') }}</h3>
              <div v-if="!selectedInputFields.length" class="cx-muted workflow-config-empty">
                {{ t('agent.noInputRequired') }}
              </div>
              <div v-for="([name, schema]) in selectedInputFields" :key="name" class="workflow-argument">
                <div class="workflow-argument__label">
                  <span>{{ schema.title || name }}</span>
                  <span v-if="selectedRequiredInputs.has(name)" class="workflow-required">{{ t('agent.required') }}</span>
                </div>
                <small v-if="schema.description">{{ schema.description }}</small>
                <select
                  v-if="upstreamNodes.length"
                  class="cx-input workflow-source-select"
                  :value="inputSource(name)"
                  :disabled="busy"
                  @change="changeInputSource(name, schema, $event)"
                >
                  <option value="manual">{{ t('agent.manualInput') }}</option>
                  <option v-for="node in upstreamNodes" :key="node.id" :value="node.id">
                    {{ t('agent.fromNodeOutput', { name: node.data.tool.name, id: node.id }) }}
                  </option>
                  <optgroup
                    v-for="node in upstreamNodes.filter((item) => toolOutputFields(item.data.tool).length)"
                    :key="`${node.id}-fields`"
                    :label="t('agent.nodeOutputFields', { name: node.data.tool.name })"
                  >
                    <option
                      v-for="([outputName, outputSchema]) in toolOutputFields(node.data.tool)"
                      :key="`${node.id}-${outputName}`"
                      :value="`${node.id}::${outputName}`"
                    >
                      {{ outputSchema.title || outputName }}
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
                  <textarea
                    v-else-if="schema.type === 'object'"
                    class="cx-textarea mono workflow-object-input"
                    rows="3"
                    :value="displayInputValue(name, schema)"
                    :placeholder="t('agent.objectInputPlaceholder')"
                    :disabled="busy"
                    @change="updateObjectInput(name, $event)"
                  />
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
                  <i class="mdi mdi-link-variant" /> {{ t('agent.usesNodeOutput') }}
                </div>
              </div>
            </section>

            <section class="workflow-config-section">
              <h3><i class="mdi mdi-logout-variant" /> {{ t('agent.outputConfig') }}</h3>
              <div class="workflow-output-card">
                <strong>{{ t('agent.nodeResult') }}</strong>
                <div v-if="selectedOutputFields.length" class="workflow-output-fields">
                  <span v-for="([name, schema]) in selectedOutputFields" :key="name">
                    <code>{{ name }}</code>{{ schema.description ? ` · ${schema.description}` : '' }}
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

.workflow-output-fields code {
  color: rgb(var(--v-theme-primary));
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
</style>
