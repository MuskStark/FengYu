<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  MarkerType,
  VueFlow,
  useVueFlow,
  type Connection,
  type GraphEdge,
  type NodeMouseEvent,
  type ValidConnectionFunc,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { api } from '@/api/client'
import type {
  AgentPlan,
  AgentRunConfig,
  AgentRunFile,
  AgentRunSummary,
  AgentScheduleSummary,
  AgentStep,
  AgentTaskSummary,
  AgentTool,
  AiPermissionMode,
  WorkflowDefinition,
  WorkflowDraft,
} from '@/api/types'
import FlowChatPanel from '@/components/agent/FlowChatPanel.vue'
import FlowGradientEdge from '@/components/agent/FlowGradientEdge.vue'
import FlowStickyNote from '@/components/agent/FlowStickyNote.vue'
import FlowExecutionPanel from '@/components/agent/FlowExecutionPanel.vue'
import FlowNodeInspector from '@/components/agent/FlowNodeInspector.vue'
import FlowPalette from '@/components/agent/FlowPalette.vue'
import FlowRunDialog from '@/components/agent/FlowRunDialog.vue'
import FlowSettingsDrawer from '@/components/agent/FlowSettingsDrawer.vue'
import WorkflowToolNode from '@/components/agent/WorkflowToolNode.vue'
import { useAgentRunStream } from '@/components/agent/agentRunStream'
import {
  bindWorkflowInputReferences,
  canvasLayoutByStepIndex,
  isWorkflowNoteNode,
  maxCanvasIdSequences,
  rehydrateFlowGraph,
  reconcileWorkflowArguments,
  serializeCanvasState,
  serializeFlowGraph,
  topologicallySortWorkflowNodes,
  undeclaredWorkflowInputReferences,
  wouldCreateCycle,
  missingRequiredWorkflowInputs,
  workflowNodeColor,
  type CanvasFlowNode,
  type FlowCanvasEdge,
  type WorkflowFlowNode,
  type WorkflowNoteNode,
  type WorkflowSchemaProperty,
} from '@/components/agent/workflow'
import {
  templateInputSchema,
  WORKFLOW_TEMPLATES,
  type WorkflowTemplate,
} from '@/components/agent/workflowTemplates'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

/**
 * Flowise-style flow builder: a full-workspace canvas with a categorized node
 * palette on the left, a node configuration panel on the right, sticky notes,
 * and a run/execution surface. The graph is persisted verbatim (`graph`) while
 * `plan` + `layout` stay the compiled contract executed by the backend and
 * exposed to the AI as `run_workflow_*` tools.
 */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()

// ── reactive state ───────────────────────────────────────────────────────
const canvasNodes = shallowRef<CanvasFlowNode[]>([])
const canvasEdges = shallowRef<FlowCanvasEdge[]>([])
/** Agentflow canvas toggles (Controls extras): snap-to-grid + dotted background. */
const snapEnabled = ref(false)
const backgroundEnabled = ref(true)

const {
  fitView,
  screenToFlowCoordinate,
} = useVueFlow('flow-builder')

// MiniMap follows the app theme (Flowise ships dark/light variants of the same tokens)
const appTheme = useTheme()
const isDarkTheme = computed(() => appTheme.current.value.dark)
const minimapNodeColor = computed(() => (isDarkTheme.value ? '#2d2d2d' : '#e2e2e2'))
const minimapNodeStroke = computed(() => (isDarkTheme.value ? '#525252' : '#ffffff'))
const minimapMask = computed(() => (isDarkTheme.value ? 'rgba(45, 45, 45, 0.6)' : 'rgba(240, 240, 240, 0.6)'))
const selectedNodeId = ref<string | null>(null)
const paletteOpen = ref(true)
const inspectorOpen = ref(false)
const settingsOpen = ref(false)
const runDialogOpen = ref(false)
const executionPanelOpen = ref(false)
const chatOpen = ref(false)
const tools = ref<AgentTool[]>([])
const workflowId = ref<string | null>(null)
const workflowName = ref('')
const workflowDescription = ref('')
const workflowInputSchemaText = ref('{\n  "type": "object",\n  "properties": {}\n}')
const workflowInputsText = ref('{}')
const workflowPublished = ref(false)
const goal = ref('')
const runHistory = ref<AgentRunSummary[]>([])
const backgroundTasks = ref<AgentTaskSummary[]>([])
const schedules = ref<AgentScheduleSummary[]>([])
const errorMsg = ref<string | null>(null)
/** Snapshot of the editor state at the last load/save — drives the unsaved-changes guards. */
const savedCanvasSnapshot = ref('')
let toolRefreshTimer: ReturnType<typeof setInterval> | null = null
let nodeSequence = 0
let noteSequence = 0

const run = useAgentRunStream({ onSettled: () => {
  void loadRunHistory()
  void loadBackgroundTasks()
} })

// ── computed state ───────────────────────────────────────────────────────
const toolNodes = computed(() => canvasNodes.value.filter((node): node is WorkflowFlowNode => !isWorkflowNoteNode(node)))
const noteNodes = computed(() => canvasNodes.value.filter(isWorkflowNoteNode))
const selectedToolNode = computed(() =>
  toolNodes.value.find((node) => node.id === selectedNodeId.value) ?? null)
const unavailableNodes = computed(() => toolNodes.value.filter((node) => !node.data.available))
const incompleteNodes = computed(() => toolNodes.value.filter((node) =>
  missingRequiredWorkflowInputs(node.data.tool.inputSchema, node.data.argsText).length > 0))
const workflowTitle = computed(() => workflowName.value.trim() || t('agent.untitledWorkflow'))
const workflowSchemaFields = computed<Array<[string, WorkflowSchemaProperty]>>(() => {
  try {
    const schema = JSON.parse(workflowInputSchemaText.value) as { properties?: Record<string, WorkflowSchemaProperty> }
    return Object.entries(schema.properties ?? {})
  } catch {
    return []
  }
})

// ── unsaved-changes tracking ─────────────────────────────────────────────
const currentCanvasSnapshot = computed(() => serializeCanvasState({
  name: workflowName.value,
  description: workflowDescription.value,
  goal: goal.value,
  inputSchemaText: workflowInputSchemaText.value,
  nodes: toolNodes.value,
  edges: canvasEdges.value,
  notes: noteNodes.value.map((node) => ({
    content: node.data.content,
    color: node.data.color,
    position: node.position,
  })),
}))
const hasUnsavedChanges = computed(() =>
  currentCanvasSnapshot.value !== savedCanvasSnapshot.value)

function markCanvasClean() {
  savedCanvasSnapshot.value = currentCanvasSnapshot.value
}

// ── undo / redo (canvas structure: nodes, edges, positions) ──────────────
/**
 * History entries keep nodes/edges as unknown JSON so the undo stack never
 * re-instantiates the renderer's generics, and
 * the entries are opaque clones anyway — only applyHistoryEntry casts them back.
 */
interface CanvasHistoryEntry {
  nodes: unknown[]
  edges: unknown[]
}


const undoStack = ref<CanvasHistoryEntry[]>([])
const redoStack = ref<CanvasHistoryEntry[]>([])
const HISTORY_LIMIT = 50
let applyingHistory = false
/** Pre-drag snapshot: node drags only commit to history when the drag ends. */
let dragStartSnapshot: CanvasHistoryEntry | null = null

function cloneCanvas(): CanvasHistoryEntry {
  // Widened before stringify to keep the deep type instantiation flat.
  const snapshot: unknown = { nodes: canvasNodes.value, edges: canvasEdges.value }
  return JSON.parse(JSON.stringify(snapshot)) as CanvasHistoryEntry
}

/** Captures the CURRENT canvas before a mutation; the undo stack holds past states. */
function pushHistory() {
  if (applyingHistory) return
  undoStack.value.push(cloneCanvas())
  if (undoStack.value.length > HISTORY_LIMIT) undoStack.value.shift()
  redoStack.value = []
}

function applyHistoryEntry(entry: CanvasHistoryEntry) {
  applyingHistory = true
  try {
    canvasNodes.value = entry.nodes as typeof canvasNodes.value
    canvasEdges.value = entry.edges as typeof canvasEdges.value
  } finally {
    applyingHistory = false
  }
}

function undoCanvas() {
  if (!undoStack.value.length) return
  redoStack.value.push(cloneCanvas())
  applyHistoryEntry(undoStack.value.pop()!)
}

function redoCanvas() {
  if (!redoStack.value.length) return
  undoStack.value.push(cloneCanvas())
  applyHistoryEntry(redoStack.value.pop()!)
}

function resetHistory() {
  undoStack.value = []
  redoStack.value = []
  dragStartSnapshot = null
}

function onPaneClick() {
  selectedNodeId.value = null
  inspectorOpen.value = false
}

function openNodeEditor(id: string) {
  selectedNodeId.value = id
  inspectorOpen.value = true
  settingsOpen.value = false
  executionPanelOpen.value = false
}

function openInspectorForSelected() {
  inspectorOpen.value = true
}

function deleteEdge(id: string) {
  pushHistory()
  canvasEdges.value = canvasEdges.value.filter((edge) => edge.id !== id)
}

function onNodeDragStart() {
  if (applyingHistory) return
  dragStartSnapshot = cloneCanvas()
}

function onNodeDragStop() {
  if (dragStartSnapshot && !applyingHistory) {
    undoStack.value.push(dragStartSnapshot)
    if (undoStack.value.length > HISTORY_LIMIT) undoStack.value.shift()
    redoStack.value = []
  }
  dragStartSnapshot = null
}

function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function onCanvasKeydown(event: KeyboardEvent) {
  if (run.busy.value || isTypingTarget(event.target)) return
  const meta = event.metaKey || event.ctrlKey
  if (meta && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    if (event.shiftKey) redoCanvas()
    else undoCanvas()
    return
  }
  // Delete/Backspace: remove the SELECTED nodes + edges through the history-aware
  // path (vue-flow's built-in deleteKeyCode bypasses the undo stack).
  if (event.key === 'Delete' || event.key === 'Backspace') {
    const selectedNodes = canvasNodes.value.filter((node) => node.selected)
    // vue-flow stores selection on its internal GraphEdge; the user-facing Edge type omits it.
    const selectedEdges = canvasEdges.value.filter((edge) => (edge as GraphEdge).selected)
    if (!selectedNodes.length && !selectedEdges.length) return
    event.preventDefault()
    pushHistory()
    const removedIds = new Set(selectedNodes.map((node) => node.id))
    canvasNodes.value = canvasNodes.value.filter((node) => !node.selected)
    canvasEdges.value = canvasEdges.value.filter(
      (edge) => !(edge as GraphEdge).selected && !removedIds.has(edge.source) && !removedIds.has(edge.target))
    if (selectedNodeId.value && removedIds.has(selectedNodeId.value)) {
      selectedNodeId.value = null
      inspectorOpen.value = false
    }
  }
}

/** True when the editor holds unsaved work worth protecting (any node ⇒ real effort). */
function confirmDiscardUnsaved(): boolean {
  if (run.busy.value || !hasUnsavedChanges.value || !canvasNodes.value.length) return true
  return window.confirm(t('agent.discardConfirm'))
}

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (hasUnsavedChanges.value && canvasNodes.value.length) {
    event.preventDefault()
    event.returnValue = ''
  }
}

// Leaving the builder unmounts the canvas and drops unsaved edits with it.
onBeforeRouteLeave(() => confirmDiscardUnsaved())

function backToLibrary() {
  void router.push('/flows')
}

// ── error mapping ────────────────────────────────────────────────────────
/**
 * Maps the host's validation messages onto localized, user-actionable text. Unknown
 * messages pass through unchanged — the host remains the source of truth.
 */
function friendlyWorkflowError(message: string): string {
  const map: Array<[RegExp, (m: RegExpExecArray) => string]> = [
    [/^Workflow name is required/, () => t('agent.errNameRequired')],
    [/exceed 160 characters/, () => t('agent.errNameTooLong')],
    [/references undeclared input\(s\): (.+)/, (m) => t('agent.errUndeclaredInputs', { names: m[1] })],
    [/Missing required workflow input: (.+)/, (m) => t('agent.errMissingInput', { name: m[1] })],
    [/No workflow input is available for/, () => t('agent.errUnknownInput')],
    [/^Unknown workflow/, () => t('agent.errUnknownWorkflow')],
    [/Workflow is not published/, () => t('agent.errNotPublished')],
    [/Nested workflow tools/, () => t('agent.errNested')],
    [/must not exceed 64 steps/, () => t('agent.errTooManySteps')],
    [/step indexes must be contiguous/, () => t('agent.errInvalidSteps')],
  ]
  for (const [pattern, translate] of map) {
    const match = pattern.exec(message)
    if (match) return translate(match)
  }
  return message
}

function toErrorMessage(e: unknown): string {
  return friendlyWorkflowError(e instanceof Error ? e.message : t('agent.failed'))
}

// ── lifecycle ────────────────────────────────────────────────────────────
onMounted(async () => {
  window.addEventListener('focus', refreshTools)
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('keydown', onCanvasKeydown)
  toolRefreshTimer = setInterval(() => void refreshTools(), 10_000)
  void loadRunHistory()
  await refreshTools()
  await initializeFromRoute()
})

onBeforeUnmount(() => {
  run.closeStream()
  window.removeEventListener('focus', refreshTools)
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('keydown', onCanvasKeydown)
  if (toolRefreshTimer) clearInterval(toolRefreshTimer)
})

async function initializeFromRoute() {
  const id = route.params.id
  const templateId = typeof route.query.template === 'string' ? route.query.template : null
  if (typeof id === 'string' && id !== 'new') {
    try {
      loadWorkflow(await api.workflow(id), { skipConfirm: true })
    } catch (e) {
      errorMsg.value = toErrorMessage(e)
    }
    return
  }
  if (templateId) {
    const template = WORKFLOW_TEMPLATES.find((item) => item.id === templateId)
    if (template) {
      applyWorkflowTemplate(template)
      return
    }
  }
  settingsOpen.value = true
  markCanvasClean()
}

async function refreshTools() {
  try {
    const list = await api.agentTools()
    // Canvas nodes render from explicit flow-node declarations only — plugin
    // manifests or the host's builtin registry provide them; tools without one
    // stay available to the model but never appear as canvas nodes.
    tools.value = (list ?? []).filter((tool) => tool.pluginId !== 'workflow' && tool.flowNode)
    const byId = new Map(tools.value.map((tool) => [tool.id, tool]))
    const byName = new Map(tools.value.map((tool) => [tool.name, tool]))
    let changed = false
    const reconciled = canvasNodes.value.map((node) => {
      if (isWorkflowNoteNode(node)) return node
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

// ── workflow templates ───────────────────────────────────────────────────

/** Tools of one template the canvas currently lacks (plugin missing or disabled). */
function templateMissingTools(template: WorkflowTemplate): string[] {
  return template.requiredTools.filter((name) => !tools.value.some((tool) => tool.name === name))
}

/**
 * Applies a built-in template: pre-wired nodes + edges, a run-form input schema with
 * file/enum annotations, and a localized goal. The user only fills the run form.
 */
function applyWorkflowTemplate(template: WorkflowTemplate) {
  if (run.busy.value || !confirmDiscardUnsaved()) return
  const missing = templateMissingTools(template)
  if (missing.length) {
    errorMsg.value = t('agent.templateMissingTools', { names: missing.join(', ') })
    return
  }
  workflowId.value = null
  workflowName.value = t(template.titleKey)
  workflowDescription.value = t(template.descriptionKey)
  workflowInputSchemaText.value = JSON.stringify(
    templateInputSchema(template, (key) => t(key)), null, 2)
  workflowInputsText.value = '{}'
  workflowPublished.value = false
  goal.value = t(template.goalKey)
  const nodeId = new Map<string, string>()
  canvasNodes.value = template.nodes.map((spec) => {
    const tool = tools.value.find((item) => item.name === spec.tool)
    if (!tool) throw new Error(t('agent.templateMissingTools', { names: spec.tool }))
    const id = `${template.id}_${spec.id}`
    nodeId.set(spec.id, id)
    return {
      id,
      type: 'tool',
      position: { x: spec.x, y: spec.y },
      data: {
        tool,
        argsText: JSON.stringify(spec.args, null, 2),
        description: t(spec.descriptionKey),
        requiresApproval: !!spec.requiresApproval,
        available: true,
        color: workflowNodeColor(tool),
        descriptor: tool.flowNode ?? undefined,
      },
    } as WorkflowFlowNode
  })
  canvasEdges.value = template.edges
    .filter(([source, target]) => nodeId.has(source) && nodeId.has(target))
    .map(([source, target]) => newEdge(nodeId.get(source)!, nodeId.get(target)!))
  // Node-reference placeholders inside template args point at the template's short ids —
  // rewrite them to the canvas ids just minted so the graph is immediately valid.
  for (const node of canvasNodes.value) {
    if (isWorkflowNoteNode(node)) continue
    node.data.argsText = node.data.argsText.replace(
      /\{\{node\.([A-Za-z0-9_-]+)\.result/g,
      (reference, id: string) => (nodeId.has(id) ? `{{node.${nodeId.get(id)}.result` : reference),
    )
  }
  selectedNodeId.value = null
  inspectorOpen.value = false
  settingsOpen.value = false
  executionPanelOpen.value = false
  runDialogOpen.value = false
  void nextTick(() => fitCanvas())
  markCanvasClean()
}

// ── workflow load / save / publish / delete ──────────────────────────────

function loadWorkflow(definition: WorkflowDefinition, options?: { skipConfirm?: boolean }) {
  if (!(options?.skipConfirm || confirmDiscardUnsaved())) return
  resetHistory()
  workflowId.value = definition.id
  workflowName.value = definition.name
  workflowDescription.value = definition.description
  workflowInputSchemaText.value = JSON.stringify(definition.inputSchema, null, 2)
  workflowPublished.value = definition.published
  goal.value = definition.plan.goal
  // Prefer the persisted canvas graph — it round-trips node ids (and sticky notes)
  // exactly as authored. Definitions saved before graph persistence (or with an
  // empty graph) reconstruct the canvas from the compiled plan + layout.
  const rehydrated = rehydrateFlowGraph(definition.graph, tools.value)
  if (rehydrated && rehydrated.nodes.length) {
    canvasNodes.value = rehydrated.nodes
    // Rehydration keeps the authored node_N/note_N ids — advance the id sequences
    // past them so addTool/addStickyNote never mint a colliding id.
    const sequences = maxCanvasIdSequences(rehydrated.nodes)
    nodeSequence = sequences.node
    noteSequence = sequences.note
    canvasEdges.value = rehydrated.edges.map((edge) => ({
      ...edge,
      type: 'agentflow',
      markerEnd: { type: MarkerType.ArrowClosed },
    }))
  } else {
    const restoredEdges: FlowCanvasEdge[] = []
    canvasNodes.value = definition.plan.steps.map((step, index) => {
      const tool = tools.value.find((item) => item.name === step.toolName) ?? {
        id: `missing:${step.toolName}`,
        name: step.toolName,
        description: step.description,
        inputSchema: '{"type":"object","properties":{}}',
        revision: 'missing',
      }
      const id = `node_${++nodeSequence}`
      const savedPosition = definition.layout?.[String(index)]
      return {
        id,
        type: 'tool',
        position: savedPosition
          ? { x: savedPosition.x, y: savedPosition.y }
          : { x: 48 + (index % 3) * 290, y: 48 + Math.floor(index / 3) * 150 },
        data: {
          tool,
          argsText: JSON.stringify(step.args ?? {}, null, 2),
          description: step.description,
          requiresApproval: !!step.requiresApproval,
          available: !tool.id.startsWith('missing:'),
          color: workflowNodeColor(tool),
          descriptor: tool.flowNode ?? undefined,
        },
      } as WorkflowFlowNode
    })
    for (const step of definition.plan.steps) {
      for (const dependency of step.dependsOn ?? []) {
        const source = canvasNodes.value[dependency]
        const target = canvasNodes.value[step.index]
        if (source && target) restoredEdges.push(newEdge(source.id, target.id))
      }
    }
    canvasEdges.value = restoredEdges
  }
  selectedNodeId.value = null
  inspectorOpen.value = false
  void nextTick(() => fitCanvas())
  markCanvasClean()
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
  await persistWorkflow()
}

/** Saves and reports success, so the chat dock can decide whether to send the turn. */
async function persistWorkflow(): Promise<boolean> {
  try {
    const compiled = compileCanvasWorkflow()
    const inputSchema = parseWorkflowJson(workflowInputSchemaText.value, t('agent.inputSchema'))
    const undeclared = undeclaredWorkflowInputReferences(
      workflowInputSchemaText.value, compiled.plan.goal, compiled.plan.steps)
    if (undeclared.length) {
      throw new Error(t('agent.errUndeclaredInputs', { names: undeclared.join(', ') }))
    }
    const draft: WorkflowDraft = {
      name: workflowName.value.trim(),
      description: workflowDescription.value.trim(),
      inputSchema,
      plan: compiled.plan,
      layout: compiled.layout,
      graph: serializeFlowGraph(canvasNodes.value, canvasEdges.value),
    }
    const saved = workflowId.value
      ? await api.updateWorkflow(workflowId.value, draft)
      : await api.createWorkflow(draft)
    if (!workflowId.value) {
      workflowId.value = saved.id
      // Keep the address bar in sync without re-running the component's init.
      void router.replace({ path: `/flows/${saved.id}`, query: {} })
    }
    workflowPublished.value = saved.published
    markCanvasClean()
    errorMsg.value = null
    return true
  } catch (e) {
    errorMsg.value = toErrorMessage(e)
    return false
  }
}

/**
 * Chat-dock gate: a conversation turn must run against a SAVED graph (the
 * backend binds the tool to a workflow id). Auto-save pending edits first —
 * the Flowise build → chat → iterate loop always talks to the current state.
 */
async function prepareChatTurn(): Promise<boolean> {
  if (!workflowId.value) {
    errorMsg.value = t('flows.chatNeedSave')
    return false
  }
  if (hasUnsavedChanges.value && toolNodes.value.length) {
    return await persistWorkflow()
  }
  return true
}

async function toggleWorkflowPublication() {
  if (!workflowId.value) return
  if (!workflowPublished.value && incompleteNodes.value.length) {
    selectedNodeId.value = incompleteNodes.value[0].id
    inspectorOpen.value = true
    settingsOpen.value = false
    errorMsg.value = t('agent.publishIncomplete')
    return
  }
  try {
    const saved = await api.publishWorkflow(workflowId.value, !workflowPublished.value)
    workflowPublished.value = saved.published
    await refreshTools()
  } catch (e) {
    errorMsg.value = toErrorMessage(e)
  }
}

async function deleteWorkflowAndExit() {
  if (!workflowId.value || !window.confirm(t('agent.deleteWorkflowConfirm'))) return
  try {
    await api.deleteWorkflow(workflowId.value)
    await refreshTools()
    savedCanvasSnapshot.value = currentCanvasSnapshot.value // the guard must not fire now
    void router.push('/flows')
  } catch (e) {
    errorMsg.value = toErrorMessage(e)
  }
}

// ── canvas editing ───────────────────────────────────────────────────────

/** Flowise's edge shape: type buttonedge + arrow marker. */
function newEdge(source: string, target: string): FlowCanvasEdge {
  return {
    id: `edge_${source}_${target}`,
    source,
    target,
    type: 'agentflow',
    markerEnd: { type: MarkerType.ArrowClosed },
  }
}

function defaultArgs(tool: AgentTool): Record<string, unknown> {
  try {
    const schema = JSON.parse(tool.inputSchema) as {
      properties?: Record<string, { type?: string; default?: unknown }>
      required?: string[]
    }
    const args: Record<string, unknown> = {}
    // Declared defaults from the flow-node descriptor apply first (e.g. action=add)
    // and win over the schema's required-field seeding below.
    for (const input of tool.flowNode?.inputs ?? []) {
      if (input.default !== undefined) args[input.name] = input.default
    }
    for (const name of schema.required ?? []) {
      if (args[name] !== undefined) continue
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

function addTool(tool: AgentTool, x?: number, y?: number, fitAfterAdd = false) {
  pushHistory()
  const order = canvasNodes.value.length
  const previous = selectedToolNode.value
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
      color: workflowNodeColor(tool),
      descriptor: tool.flowNode ?? undefined,
    },
  }
  canvasNodes.value = [...canvasNodes.value, node]
  if (fitAfterAdd && previous) {
    canvasEdges.value = [...canvasEdges.value, newEdge(previous.id, node.id)]
  }
  selectedNodeId.value = node.id
  inspectorOpen.value = true
  if (fitAfterAdd) {
    void nextTick(() => fitView({ padding: 0.16, duration: 220, maxZoom: 1 }))
  }
}

/** Adds a Flowise-style sticky note near the canvas center. */
function addStickyNote() {
  pushHistory()
  const node: WorkflowNoteNode = {
    id: `note_${++noteSequence}`,
    type: 'note',
    position: { x: 140 + (noteSequence % 5) * 36, y: 120 + (noteSequence % 5) * 30 },
    data: { content: '', color: 'yellow' },
  }
  canvasNodes.value = [...canvasNodes.value, node]
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
  addTool(tool, position.x - 90, position.y - 36)
}

function removeNodeById(id: string) {
  pushHistory()
  canvasNodes.value = canvasNodes.value.filter((node) => node.id !== id)
  canvasEdges.value = canvasEdges.value.filter((edge) => edge.source !== id && edge.target !== id)
  if (selectedNodeId.value === id) {
    selectedNodeId.value = null
    inspectorOpen.value = false
  }
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  if (node.type !== 'note') inspectorOpen.value = true
  settingsOpen.value = false
  executionPanelOpen.value = false
}

function toggleExecutionPanel() {
  executionPanelOpen.value = !executionPanelOpen.value
  if (executionPanelOpen.value) {
    settingsOpen.value = false
    void loadBackgroundTasks()
  }
}

function openSettings() {
  settingsOpen.value = true
  executionPanelOpen.value = false
}

function fitCanvas() {
  if (canvasNodes.value.length) {
    void fitView({ padding: 0.14, duration: 220, maxZoom: 1 })
  }
}

/**
 * Duplicate/cycle validation against the PRE-UPDATE edge list: validating against a
 * list that already contains the new edge would always flag it as a duplicate.
 */
function canConnect(
  connection: { source: string; target: string },
  edgeList: Array<{ source: string; target: string }> = canvasEdges.value,
): boolean {
  if (run.busy.value || connection.source === connection.target) return false
  if (edgeList.some(
    (edge) => edge.source === connection.source && edge.target === connection.target,
  )) return false
  return !wouldCreateCycle(edgeList, connection.source, connection.target)
}
/**
 * vue-flow re-validates programmatic edge assignments through isValidConnection
 * AFTER the parent state was assigned — validating against the parent list would
 * always see the new edge as a duplicate and silently drop it, so the
 * store-supplied pre-update list is what must be checked.
 */
const isValidConnection: ValidConnectionFunc = (connection, context) =>
  canConnect(connection, context?.edges)

function onConnect(connection: Connection) {
  if (!canConnect(connection)) return
  pushHistory()
  canvasEdges.value = [...canvasEdges.value, newEdge(connection.source, connection.target)]
}

/** Auto-edge requested by the inspector when an input is bound to a node output. */
function linkNodes(sourceId: string, targetId: string) {
  pushHistory()
  canvasEdges.value = [...canvasEdges.value, newEdge(sourceId, targetId)]
}

// ── graph → AgentPlan compilation ────────────────────────────────────────

function topologicalNodes(): WorkflowFlowNode[] {
  const ordered = topologicallySortWorkflowNodes(toolNodes.value, canvasEdges.value)
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

function compileCanvasWorkflow(options?: { bindInputs?: boolean }): { plan: AgentPlan; layout: Record<string, { x: number; y: number }> } {
  if (!toolNodes.value.length) throw new Error(t('agent.canvasEmpty'))
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
  // Saving keeps {{inputs.x}} placeholders for re-binding at run time; a test run
  // substitutes the current run-form values into the plan it posts.
  const runInputs = options?.bindInputs ? parseWorkflowJson(workflowInputsText.value, t('agent.workflowInputs')) : {}
  const workflowGoalBind = bindWorkflowInputReferences(
    goal.value.trim() || t('agent.canvasDefaultGoal'), runInputs)
  const workflowGoal = String(workflowGoalBind.value)
  const compiledSteps: AgentStep[] = ordered.map((node, index) => {
    const data = node.data
    let args: unknown
    try {
      args = JSON.parse(data.argsText || '{}')
    } catch {
      throw new Error(t('agent.canvasInvalidArgs', { name: data.tool.name }))
    }
    if (!args || Array.isArray(args) || typeof args !== 'object') {
      throw new Error(t('agent.canvasInvalidArgs', { name: data.tool.name }))
    }
    const bound = options?.bindInputs
      ? bindWorkflowInputReferences(args, runInputs)
      : { value: args, missing: [] as string[] }
    if (bound.missing.length) {
      throw new Error(t('agent.canvasMissingInputs', { names: bound.missing.join(', ') }))
    }
    return {
      index,
      toolName: data.tool.name,
      args: replaceNodeReferences(bound.value, indexes, index) as Record<string, unknown>,
      description: data.description || data.tool.description || data.tool.name,
      requiresApproval: data.requiresApproval,
      dependsOn: [...new Set(incoming.get(node.id) ?? [])].sort((a, b) => a - b),
      status: 'pending',
    }
  })
  return {
    plan: {
      goal: workflowGoal,
      steps: compiledSteps,
      reasoning: t('agent.canvasReasoning'),
    },
    layout: canvasLayoutByStepIndex(ordered),
  }
}

// ── run flow ─────────────────────────────────────────────────────────────

function requestRun() {
  if (incompleteNodes.value.length) {
    selectedNodeId.value = incompleteNodes.value[0].id
    inspectorOpen.value = true
    settingsOpen.value = false
    executionPanelOpen.value = false
    errorMsg.value = t('agent.incompleteNodes', { count: incompleteNodes.value.length })
    return
  }
  runDialogOpen.value = true
  executionPanelOpen.value = false
}

async function startRun(payload: {
  inputs: Record<string, unknown>
  permissionMode: AiPermissionMode
  files: AgentRunFile[]
}) {
  try {
    // Reset for a fresh run.
    errorMsg.value = null
    run.resetRunState()
    run.status.value = 'planning'
    runDialogOpen.value = false
    executionPanelOpen.value = true

    // Saving (and the step preview below) keeps {{inputs.x}} placeholders; the direct
    // canvas run re-compiles WITH bindings because /api/agent/run posts a final plan.
    const compiled = compileCanvasWorkflow()
    run.plan.value = compiled.plan
    for (const step of compiled.plan.steps) run.steps.value.set(step.index, step)

    const runConfig: AgentRunConfig = {
      requirePlanApproval: false,
      requireStepApproval: true,
      replanOnFailure: false,
      maxReplans: 0,
      permissionMode: payload.permissionMode,
    }
    run.requirePlanApproval.value = runConfig.requirePlanApproval

    if (workflowId.value) {
      // The preview binds the run-form values locally — the payload below stays the
      // workflow id + inputs, and the backend compiler rebinds from the persisted
      // placeholder plan, exactly as before.
      try {
        const boundPreview = compileCanvasWorkflow({ bindInputs: true }).plan
        run.plan.value = boundPreview
        for (const step of boundPreview.steps) run.steps.value.set(step.index, step)
      } catch {
        // An optional referenced input left unfilled degrades the preview to the
        // placeholder plan; the backend still decides the real binding.
      }
      if (hasUnsavedChanges.value) {
        const saved = await api.updateWorkflow(workflowId.value, {
          name: workflowName.value.trim(),
          description: workflowDescription.value.trim(),
          inputSchema: parseWorkflowJson(workflowInputSchemaText.value, t('agent.inputSchema')),
          plan: compiled.plan,
          layout: compiled.layout,
          graph: serializeFlowGraph(canvasNodes.value, canvasEdges.value),
        })
        workflowPublished.value = saved.published
        markCanvasClean()
      }
      // Run against the SAVED definition so the backend compiler re-binds inputs
      // (the saved plan keeps {{inputs.x}} placeholders) with the granted files attached.
      const savedResponse = await api.runWorkflow(workflowId.value, {
        inputs: payload.inputs,
        config: runConfig,
        files: payload.files.length ? payload.files : undefined,
      })
      run.runId.value = savedResponse.runId
    } else {
      const boundPlan = compileCanvasWorkflow({ bindInputs: true }).plan
      const response = await api.agentRun({
        goal: boundPlan.goal,
        config: runConfig,
        workflow: boundPlan,
        files: payload.files.length ? payload.files : undefined,
      })
      run.runId.value = response.runId
    }
    run.selectedHistoryId.value = run.runId.value
    run.openStream(run.runId.value)
    await loadRunHistory()
  } catch (e) {
    errorMsg.value = toErrorMessage(e)
    run.status.value = 'error'
  }
}

// ── history / background tasks / schedules ───────────────────────────────

async function loadRunHistory() {
  try {
    runHistory.value = await api.agentRuns()
  } catch {
    // History is auxiliary; a current run must remain usable when it cannot be loaded.
  }
}

async function searchHistory(query: string) {
  try {
    runHistory.value = await api.agentRunsQuery(query, 50)
  } catch {
    // Keep the last history on a failed search.
  }
}

async function loadBackgroundTasks() {
  try {
    [backgroundTasks.value, schedules.value] = await Promise.all([
      api.agentTasks(), api.agentSchedules()])
  } catch {
    // Task panels are auxiliary.
  }
}

async function killTask(taskId: string) {
  try {
    await api.agentKillTask(taskId)
    await loadBackgroundTasks()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}

async function removeSchedule(scheduleId: string) {
  try {
    await api.agentDeleteSchedule(scheduleId)
    await loadBackgroundTasks()
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : t('agent.failed')
  }
}
</script>

<template>
  <div class="flow-builder">
    <header class="flow-toolbar">
      <button class="flow-brand" :title="t('flows.backToLibrary')" @click="backToLibrary">
        <span class="flow-brand__mark"><i class="mdi mdi-vector-polyline" /></span>
        <span>
          <strong>{{ workflowTitle }}</strong>
          <small>
            {{ toolNodes.length }} {{ t('agent.nodes') }} · {{ workflowPublished ? t('agent.published') : t('agent.draft') }}
            <em v-if="hasUnsavedChanges" class="flow-unsaved" :title="t('agent.unsavedChanges')">●</em>
          </small>
        </span>
        <i class="mdi mdi-chevron-down" />
      </button>
      <div class="flow-toolbar-spacer" />
      <button
        class="flow-toolbar-button"
        :class="{ active: executionPanelOpen }"
        @click="toggleExecutionPanel"
      ><i class="mdi mdi-history" /> {{ t('agent.runPanel') }}</button>
      <button
        class="flow-toolbar-button"
        :class="{ active: settingsOpen }"
        @click="openSettings"
      ><i class="mdi mdi-cog-outline" /> {{ t('agent.workflowSettings') }}</button>
      <button
        class="flow-toolbar-button"
        :disabled="run.busy.value || !workflowName.trim() || !toolNodes.length"
        @click="saveWorkflow"
      ><i class="mdi mdi-content-save-outline" /> {{ t('agent.saveWorkflow') }}</button>
      <button
        class="flow-run-button"
        :disabled="!run.busy.value && (!toolNodes.length || !!unavailableNodes.length)"
        @click="run.busy.value ? run.cancel() : requestRun()"
      ><i class="mdi" :class="run.busy.value ? 'mdi-stop' : 'mdi-play'" /> {{ run.busy.value ? t('agent.cancel') : t('agent.testRun') }}</button>
    </header>

    <div class="flow-workspace">
      <aside v-show="paletteOpen" class="flow-panel flow-panel--left">
        <FlowPalette
          :tools="tools"
          :disabled="run.busy.value"
          @add="(tool) => { addTool(tool, undefined, undefined, true); paletteOpen = false }"
          @dragstart="onToolDragStart"
          @close="paletteOpen = false"
        />
      </aside>

      <div class="flow-stage-wrap">
        <VueFlow
          id="flow-builder"
          v-model:nodes="canvasNodes"
          v-model:edges="canvasEdges"
          class="flow-stage af-canvas"
          :min-zoom="0.5"
          :fit-view-on-init="true"
          :delete-key-code="null"
          :snap-to-grid="snapEnabled"
          :snap-grid="[25, 25]"
          :nodes-draggable="!run.busy.value"
          :nodes-connectable="!run.busy.value"
          :elements-selectable="!run.busy.value"
          :is-valid-connection="isValidConnection"
          :default-edge-options="{
            type: 'agentflow',
            markerEnd: MarkerType.ArrowClosed,
          }"
          @dragover.prevent
          @drop.prevent="onCanvasDrop"
          @connect="onConnect"
          @node-click="onNodeClick"
          @node-dblclick="openInspectorForSelected"
          @node-drag-start="onNodeDragStart"
          @node-drag-stop="onNodeDragStop"
          @pane-click="onPaneClick"
          @nodes-delete="selectedNodeId = null"
        >
          <template #node-tool="nodeProps">
            <WorkflowToolNode v-bind="nodeProps" @open-editor="openNodeEditor(nodeProps.id)" />
          </template>
          <template #node-note="nodeProps">
            <FlowStickyNote v-bind="nodeProps" @delete="removeNodeById(nodeProps.id)" />
          </template>
          <template #edge-agentflow="edgeProps">
            <FlowGradientEdge v-bind="edgeProps" @delete="deleteEdge" />
          </template>

          <Background v-if="backgroundEnabled" :gap="16" pattern-color="#aaa" />
          <MiniMap
            position="bottom-right"
            :node-stroke-width="3"
            :node-color="minimapNodeColor"
            :node-stroke-color="minimapNodeStroke"
            :mask-color="minimapMask"
            :style="{ backgroundColor: 'rgb(var(--v-theme-background))' }"
          />
          <Controls position="bottom-left" :show-interactive="false">
            <button
              class="react-flow__controls-button react-flow__controls-interactive"
              :title="t('flows.toggleSnap')"
              @click="snapEnabled = !snapEnabled"
            ><i class="mdi" :class="snapEnabled ? 'mdi-magnet' : 'mdi-magnet-off'" /></button>
            <button
              class="react-flow__controls-button react-flow__controls-interactive"
              :title="t('flows.toggleBackground')"
              @click="backgroundEnabled = !backgroundEnabled"
            ><i class="mdi" :class="backgroundEnabled ? 'mdi-dots-grid' : 'mdi-dots-grid-off'" /></button>
          </Controls>
        </VueFlow>
        <!-- Empty-state overlay sits above the canvas; pointer-events stay off except
            its buttons, so the canvas beneath remains interactive. -->
        <div v-if="!canvasNodes.length" class="flow-stage-empty">
          <span class="flow-stage-empty__icon"><i class="mdi mdi-vector-polyline" /></span>
          <strong>{{ t('agent.canvasHintTitle') }}</strong>
          <span>{{ t('agent.canvasHintBody') }}</span>
          <div v-if="WORKFLOW_TEMPLATES.length" class="flow-templates-row">
            <button
              v-for="template in WORKFLOW_TEMPLATES"
              :key="template.id"
              class="flow-template-card"
              :disabled="run.busy.value || !!templateMissingTools(template).length"
              :title="templateMissingTools(template).length
                ? t('agent.templateMissingTools', { names: templateMissingTools(template).join(', ') })
                : t(template.descriptionKey)"
              @click.stop="applyWorkflowTemplate(template)"
            >
              <i class="mdi" :class="template.icon" />
              <span><strong>{{ t(template.titleKey) }}</strong><small>{{ t(template.descriptionKey) }}</small></span>
            </button>
          </div>
          <button class="flow-run-button" @click.stop="paletteOpen = true"><i class="mdi mdi-plus" /> {{ t('agent.addNode') }}</button>
        </div>
        <div v-if="errorMsg" class="flow-stage-alert">
          <div class="cx-alert cx-alert--error">
            <span class="cx-alert__body">{{ errorMsg }}</span>
            <button class="cx-iconbtn cx-iconbtn--sm" @click="errorMsg = null"><i class="mdi mdi-close" /></button>
          </div>
        </div>
        <transition name="flow-chat-slide">
          <aside v-if="chatOpen" class="flow-chat-dock">
            <FlowChatPanel
              :workflow-id="workflowId"
              :workflow-title="workflowTitle"
              :prepare="prepareChatTurn"
              @close="chatOpen = false"
            />
          </aside>
        </transition>
        <button
          class="flow-chat-launcher"
          :class="{ active: chatOpen }"
          :title="t('flows.chatTitle')"
          @click="chatOpen = !chatOpen"
        ><i class="mdi" :class="chatOpen ? 'mdi-close' : 'mdi-comment-processing-outline'" /></button>
        <div class="flow-canvas-actions">
          <button :class="{ active: paletteOpen }" @click="paletteOpen = !paletteOpen"><i class="mdi mdi-plus" /> {{ t('agent.addNode') }}</button>
          <button :title="t('flows.undo')" :disabled="!undoStack.length" @click="undoCanvas"><i class="mdi mdi-undo-variant" /></button>
          <button :title="t('flows.redo')" :disabled="!redoStack.length" @click="redoCanvas"><i class="mdi mdi-redo-variant" /></button>
          <button :title="t('flows.addNote')" @click="addStickyNote"><i class="mdi mdi-note-plus-outline" /></button>
          <button :title="t('agent.canvasFitView')" @click="fitCanvas"><i class="mdi mdi-fit-to-screen-outline" /></button>
          <span v-if="incompleteNodes.length" class="flow-canvas-warning"><i class="mdi mdi-alert-outline" /> {{ t('agent.incompleteNodes', { count: incompleteNodes.length }) }}</span>
        </div>
      </div>

      <aside v-show="inspectorOpen && selectedToolNode" class="flow-panel flow-panel--right">
        <FlowNodeInspector
          v-if="selectedToolNode"
          :node="selectedToolNode"
          :nodes="toolNodes"
          :edges="canvasEdges"
          :workflow-schema-fields="workflowSchemaFields"
          :disabled="run.busy.value"
          @delete="removeNodeById(selectedToolNode!.id)"
          @close="inspectorOpen = false"
          @link="linkNodes"
        />
      </aside>

      <aside v-show="settingsOpen" class="flow-panel flow-panel--right">
        <FlowSettingsDrawer
          v-model:name="workflowName"
          v-model:description="workflowDescription"
          v-model:goal="goal"
          v-model:schema-text="workflowInputSchemaText"
          :workflow-id="workflowId"
          :can-save="!!workflowName.trim() && !!toolNodes.length"
          :published="workflowPublished"
          :disabled="run.busy.value"
          @close="settingsOpen = false"
          @save="saveWorkflow"
          @toggle-publication="toggleWorkflowPublication"
          @delete="deleteWorkflowAndExit"
        />
      </aside>

      <aside v-show="executionPanelOpen" class="flow-panel flow-panel--right">
        <FlowExecutionPanel
          :status="run.status.value"
          :run-id="run.runId.value"
          :plan="run.plan.value"
          :step-list="run.stepList.value"
          :step-results="run.stepResults.value"
          :summary="run.summary.value"
          :error-msg="errorMsg ?? run.errorMsg.value"
          :busy="run.busy.value"
          :run-history="runHistory"
          :selected-history-id="run.selectedHistoryId.value"
          :background-tasks="backgroundTasks"
          :schedules="schedules"
          @close="executionPanelOpen = false"
          @approve="run.approve"
          @cancel="run.cancel"
          @show-run="(item) => run.showPersistedRun(item)"
          @fork="run.forkRun"
          @rewind="run.rewindToStep"
          @search="searchHistory"
          @refresh-tasks="loadBackgroundTasks"
          @kill="killTask"
          @remove-schedule="removeSchedule"
        />
      </aside>
    </div>

    <FlowRunDialog
      v-model:inputs-text="workflowInputsText"
      :open="runDialogOpen"
      :workflow-title="workflowTitle"
      :node-count="toolNodes.length"
      :input-schema-text="workflowInputSchemaText"
      :busy="run.busy.value"
      @close="runDialogOpen = false"
      @run="startRun"
    />
  </div>
</template>

<style scoped>
.flow-builder {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: rgb(var(--v-theme-background));
}

.flow-toolbar {
  z-index: 20;
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 66px;
  padding: 9px 14px;
  border-bottom: 1px solid rgb(var(--v-theme-outline-variant));
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 2px 10px rgba(0, 0, 0, .07);
}

.flow-toolbar-spacer { flex: 1 1 auto; }

.flow-brand {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 230px;
  max-width: 340px;
  padding: 5px 8px;
  color: inherit;
  text-align: left;
  border: 0;
  border-radius: 9px;
  background: transparent;
  cursor: pointer;
}

.flow-brand:hover { background: rgba(var(--v-theme-on-surface), .055); }
.flow-brand > span:not(.flow-brand__mark) { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.flow-brand strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.flow-brand small { color: rgba(var(--v-theme-on-surface), .56); font-size: 10px; }

.flow-brand__mark,
.flow-stage-empty__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  color: rgb(var(--v-theme-primary));
  border-radius: 10px;
  background: rgba(var(--v-theme-primary), .12);
}

.flow-toolbar-button {
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

.flow-toolbar-button:hover,
.flow-toolbar-button.active {
  color: rgb(var(--v-theme-on-surface));
  border-color: rgba(var(--v-theme-primary), .7);
  background: rgba(var(--v-theme-primary), .1);
}

.flow-run-button {
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

.flow-run-button:disabled,
.flow-toolbar-button:disabled { opacity: .45; cursor: not-allowed; }

.flow-workspace { position: relative; display: block; flex: 1 1 auto; min-height: 0; overflow: hidden; }

.flow-stage-wrap {
  position: absolute;
  inset: 0;
  background: rgb(var(--v-theme-background));
}

/* agentflow canvas (canvas.css port) on the app theme surface */
.flow-stage.af-canvas {
  width: 100%;
  height: 100%;
  color: rgb(var(--v-theme-on-surface));
  background: rgb(var(--v-theme-background));
}

/* overflow visible so hover badges / toolbar affordances render above the card */
.flow-stage.af-canvas :deep(.vue-flow__node) {
  overflow: visible;
}

/* content-based sizing for card nodes (mirrors the !important rule in canvas.css) */
.flow-stage.af-canvas :deep(.vue-flow__node-tool),
.flow-stage.af-canvas :deep(.vue-flow__node-note) {
  width: max-content !important;
  height: max-content !important;
}

.flow-stage.af-canvas :deep(.vue-flow__edge-path) {
  stroke-width: 2;
}

.flow-stage.af-canvas :deep(.vue-flow__controls) {
  overflow: hidden;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  background-color: rgb(var(--v-theme-surface));
}

.flow-stage.af-canvas :deep(.vue-flow__controls-button) {
  color: rgb(var(--v-theme-on-surface));
  border-color: rgb(var(--v-theme-outline-variant));
  background-color: rgb(var(--v-theme-surface));
  transition: all 0.2s ease;
}

.flow-stage.af-canvas :deep(.vue-flow__controls-button:hover:not(:disabled)) {
  background-color: rgb(var(--v-theme-surface-variant));
}

.flow-stage.af-canvas :deep(.vue-flow__controls-button svg) {
  fill: currentColor;
}

.flow-stage.af-canvas :deep(.vue-flow__controls-button i.mdi) {
  font-size: 16px;
  line-height: 1;
}

.flow-stage.af-canvas :deep(.vue-flow__minimap) {
  overflow: hidden;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}






.flow-panel {
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

.flow-panel--left { left: 14px; }
.flow-panel--right { right: 14px; }

.flow-stage-empty {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  color: rgba(var(--v-theme-on-surface), .68);
  text-align: center;
  pointer-events: none;
}

.flow-stage-empty strong { color: rgb(var(--v-theme-on-surface)); font-size: 16px; }
.flow-stage-empty > span:not(.flow-stage-empty__icon) { max-width: 310px; font-size: 12px; line-height: 1.5; }
.flow-stage-empty__icon { width: 52px; height: 52px; font-size: 23px; }
.flow-stage-empty .flow-run-button { margin-top: 5px; pointer-events: auto; }

.flow-templates-row { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; margin: 4px 0 2px; }
.flow-template-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  color: inherit;
  text-align: left;
  pointer-events: auto;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 10px;
  background: rgb(var(--v-theme-surface));
  cursor: pointer;
}
.flow-template-card:hover { border-color: rgb(var(--v-theme-primary)); }
.flow-template-card:disabled { opacity: .5; cursor: not-allowed; }
.flow-template-card i { font-size: 20px; color: rgb(var(--v-theme-primary)); }
.flow-template-card strong { display: block; font-size: 12px; }
.flow-template-card small { display: block; max-width: 210px; color: rgba(var(--v-theme-on-surface), .55); font-size: 10px; }

.flow-stage-alert {
  position: absolute;
  z-index: 8;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100% - 120px));
}

.flow-canvas-actions {
  position: absolute;
  z-index: 7;
  top: 16px;
  left: 16px;
  display: flex;
  gap: 6px;
}

.flow-canvas-actions button {
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

.flow-canvas-actions button.active,
.flow-canvas-actions button:hover { color: rgb(var(--v-theme-primary)); border-color: rgb(var(--v-theme-primary)); }

.flow-canvas-warning {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  min-height: 36px;
  padding: 7px 10px;
  color: rgb(var(--v-theme-warning));
  font-size: 10px;
  border: 1px solid rgba(var(--v-theme-warning), .45);
  border-radius: 9px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 5px 16px rgba(0, 0, 0, .1);
}

.flow-unsaved { margin-left: 4px; color: rgb(var(--v-theme-warning)); font-style: normal; }

/* Flowise-style chat dock: floating launcher bottom-right, docked panel above it */
.flow-chat-launcher {
  position: absolute;
  z-index: 8;
  right: 18px;
  bottom: 18px;
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  color: rgb(var(--v-theme-on-primary));
  border: 0;
  border-radius: 50%;
  background: rgb(var(--v-theme-primary));
  box-shadow: 0 8px 22px rgba(var(--v-theme-primary), .4);
  cursor: pointer;
}

.flow-chat-launcher i { font-size: 22px; }
.flow-chat-launcher:hover { filter: brightness(1.08); }

.flow-chat-dock {
  position: absolute;
  z-index: 14;
  right: 18px;
  bottom: 18px;
  display: flex;
  flex-direction: column;
  width: min(400px, calc(100% - 36px));
  max-height: min(560px, calc(100% - 36px));
  padding: 14px;
  border: 1px solid rgb(var(--v-theme-outline-variant));
  border-radius: 14px;
  background: rgb(var(--v-theme-surface));
  box-shadow: 0 18px 48px rgba(0, 0, 0, .25);
  overflow: hidden;
}

.flow-chat-dock .flow-chat { flex: 1 1 auto; min-height: 0; }

.flow-chat-slide-enter-active,
.flow-chat-slide-leave-active { transition: transform .16s ease, opacity .16s ease; }
.flow-chat-slide-enter-from,
.flow-chat-slide-leave-to { transform: translateY(12px); opacity: 0; }

@media (max-width: 850px) {
  .flow-chat-dock { inset: 8px; width: auto; max-height: calc(100% - 16px); }
}

@media (max-width: 850px) {
  .flow-toolbar-button { display: none; }
  .flow-brand { min-width: 0; flex: 1; }
  .flow-brand > span:not(.flow-brand__mark) { max-width: 180px; }
  .flow-run-button { padding-inline: 11px; }
  .flow-panel { inset: 8px; width: auto; }
}
</style>
