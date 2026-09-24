import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router-dom'
import type { Edge } from '@xyflow/react'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import type {
  AgentRunConfig,
  AgentTool,
  FlowAuthoringDiagnostic,
  FlowAuthoringContext,
  FlowAuthoringProposal,
  WorkflowDefinition,
  WorkflowRevisionSummary,
} from '@/services/types'
import '@/styles/flow.css'
import { FLOW_CHAT_SEED_KEY, type FlowChatSeed } from '@/lib/flowSeed'
import {
  flowDraftRecoveryMode,
  loadFlowDraft,
  removeFlowDraft,
  saveFlowDraft,
  type LocalFlowDraft,
} from '@/lib/flowDraftStorage'
import { useAgentRunStream } from '@/lib/agentRunStream'
import { flowProposalGraphProblems } from '@/lib/flowAiAuthoring'
import { appConfirm } from '@/lib/appDialogs'
import { setNavigationGuard } from '@/lib/navGuard'
import {
  compileFlowPlan,
  ensureStartNode,
  flowSnapshotId,
  isToolNode,
  makeFlowEdge,
  missingRequiredNodeInputs,
  missingTool,
  parseJsonObject,
  rehydrateFlowGraph,
  serializeCanvasSnapshot,
  serializeFlowGraph,
  topologicallySortNodes,
  unknownNodeReferences,
  type FlowCanvasNode,
  type ToolCanvasNode,
} from '@/lib/flowGraph'
import { workflowDependencyClosure, workflowNodeTitle } from '@/lib/flowInspectorModel'
import { workflowNodeColor } from '@/lib/flowDisplay'
import {
  WORKFLOW_TEMPLATES,
  templateInputSchema,
  templateMissingTools,
  type WorkflowTemplate,
} from '@/components/flow/workflowTemplates'
import { FlowCanvas, type FlowCanvasApi } from '@/components/flow/FlowCanvas'
import { useFlowCanvasEditor } from '@/components/flow/useFlowCanvasEditor'
import { FlowInspector } from '@/components/flow/FlowInspector'
import { FlowPalette } from '@/components/flow/FlowPalette'
import { FlowExecutionPanel } from '@/components/flow/FlowExecutionPanel'
import { FlowRunDialog, type FlowRunStartPayload } from '@/components/flow/FlowRunDialog'
import { FlowChatPanel } from '@/components/flow/FlowChatPanel'
import {
  FlowCanvasActions,
  FlowEmptyState,
  FlowSettingsPanel,
  FlowStageAlert,
  FlowToolbar,
} from '@/components/flow/FlowBuilderChrome'

/**
 * FengyuFlow builder (React port of the Vue FlowBuilder): a full-height canvas with
 * palette / inspector / chat rails, localStorage drafts, undo/redo, save via
 * create/updateWorkflow, publish + version history, template initialization
 * (/flows/new?template=), and a run dialog streaming the plan-execute run. All
 * /flows routes render this page through FlowLibraryPage's dispatcher.
 */
export default function FlowBuilderPage(props: {
  routeWorkflowId: string | null
  onDirtyChange?: (dirty: boolean) => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()

  // ── editor state ─────────────────────────────────────────────────────────
  const [nodes, setNodes] = useState<FlowCanvasNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [tools, setTools] = useState<AgentTool[]>([])
  const [workflowId, setWorkflowId] = useState<string | null>(null)
  const [revision, setRevision] = useState<number | null>(null)
  const [published, setPublished] = useState(false)
  const [publishedRevision, setPublishedRevision] = useState<number | null>(null)
  const [hasUnpublishedChanges, setHasUnpublishedChanges] = useState(false)
  const [revisions, setRevisions] = useState<WorkflowRevisionSummary[]>([])
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [goal, setGoal] = useState('')
  const [inputSchemaText, setInputSchemaText] = useState('{\n  "type": "object",\n  "properties": {}\n}')
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)
  const [chatSeed, setChatSeed] = useState<string | null>(null)
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [dialogRunActive, setDialogRunActive] = useState(false)
  const [execPanelOpen, setExecPanelOpen] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [recoveryMsg, setRecoveryMsg] = useState<string | null>(null)
  const [savedSnapshot, setSavedSnapshot] = useState('')
  const [stepNodeIds, setStepNodeIds] = useState<string[]>([])
  const [initialized, setInitialized] = useState(false)

  const draftReady = useRef(false)
  const draftTimer = useRef<number | null>(null)
  const skipNextInit = useRef(false)
  const canvasApi = useRef<FlowCanvasApi | null>(null)

  const run = useAgentRunStream()

  // ── derived state ────────────────────────────────────────────────────────
  const toolNodes = useMemo(() => nodes.filter(isToolNode), [nodes])
  const startNode = useMemo(() => nodes.find((node) => node.type === 'start') ?? null, [nodes])
  const toolsByName = useMemo(() => new Map(tools.map((tool) => [tool.name, tool])), [tools])
  const selectedNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) ?? null,
    [nodes, selectedNodeId],
  )

  const currentSnapshot = useMemo(() => serializeCanvasSnapshot({
    name, description, goal, inputSchemaText, nodes, edges,
  }), [name, description, goal, inputSchemaText, nodes, edges])
  const dirty = currentSnapshot !== savedSnapshot

  useEffect(() => {
    props.onDirtyChange?.(dirty)
  }, [dirty, props.onDirtyChange])

  const nodeStatus = useMemo(() => {
    const status: Record<string, string> = {}
    run.stepList.forEach((step) => {
      const nodeId = stepNodeIds[step.index]
      if (nodeId && step.status !== 'pending') status[nodeId] = step.status
    })
    return status
  }, [run.stepList, stepNodeIds])

  // Capture per-node last-run results (≤16 KB) as step_complete events stream in —
  // the inspector's output viewer and upstream previews degrade to these values.
  const stepResultsKey = [...run.stepResults.keys()].join(',')
  useEffect(() => {
    if (!run.stepResults.size || !stepNodeIds.length) return
    setNodes((current) => current.map((node) => {
      if (!isToolNode(node)) return node
      const index = stepNodeIds.indexOf(node.id)
      if (index < 0) return node
      const result = run.stepResults.get(index)
      if (result === undefined || result === node.data.lastRun) return node
      return {
        ...node,
        data: { ...node.data, lastRun: result.slice(0, 16_384), lastRunAt: Date.now() },
      }
    }))
    // stepResultsKey re-fires when new step indexes report results.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run.stepResults, stepResultsKey, stepNodeIds])

  const flowContext = useMemo<FlowAuthoringContext>(() => {
    const diagnostics: FlowAuthoringDiagnostic[] = []
    if (!toolNodes.length) {
      diagnostics.push({ severity: 'warning', code: 'empty_flow', message: t('agent.canvasEmpty') })
    }
    for (const node of toolNodes) {
      if (node.data.available === false) {
        diagnostics.push({
          severity: 'error', code: 'unavailable_tool', nodeId: node.id,
          message: t('agent.canvasUnavailableTools', { names: node.data.toolName }),
        })
        continue
      }
      const tool = toolsByName.get(node.data.toolName)
      if (tool) {
        const missing = missingRequiredNodeInputs(tool, node.data.argsText)
        if (missing.length) {
          diagnostics.push({
            severity: 'error', code: 'missing_required_arguments', nodeId: node.id,
            message: t('agent.canvasMissingInputs', { names: missing.join(', ') }),
          })
        }
      }
    }
    for (const reference of unknownNodeReferences(toolNodes, new Set(nodes.map((node) => node.id)))) {
      diagnostics.push({
        severity: 'error', code: 'unknown_node_reference',
        message: t('agent.errUnknownNodeReference', { names: reference }),
      })
    }
    return {
      workflowId,
      revision,
      snapshotId: flowSnapshotId(currentSnapshot),
      dirty,
      name,
      description,
      goal,
      inputSchema: parseJsonObject(inputSchemaText) ?? {},
      graph: serializeFlowGraph(nodes, edges),
      diagnostics,
    }
  }, [toolNodes, toolsByName, nodes, edges, workflowId, revision, currentSnapshot, dirty, name, description, goal, inputSchemaText, t])

  const workflowTitle = name.trim() || t('agent.untitledWorkflow')

  // ── canvas editing engine (undo/redo + node/edge mutations) ──────────────
  const editor = useFlowCanvasEditor({
    nodes,
    edges,
    setNodes,
    setEdges,
    setSelectedNodeId,
    setPaletteOpen,
  })
  const {
    canvasRef, handleNodesChange, handleEdgesChange, handleConnect,
    patchToolData, patchStickyData, addTool, addStartNode, addStickyNote, removeNode,
    undoCanvas, redoCanvas, resetHistory, canUndo, canRedo, syncSequences,
  } = editor

  // ── localStorage draft persistence (Vue-parity keys and timing) ──────────
  const draftState = useRef({ workflowId: null as string | null, revision: null as number | null })
  draftState.current = { workflowId, revision }

  const persistLocalDraft = useCallback(() => {
    if (!draftReady.current || currentSnapshotRef.current === savedSnapshotRef.current) return
    const state = draftState.current
    saveFlowDraft({
      version: 1,
      workflowId: state.workflowId,
      baseRevision: state.revision,
      savedAt: new Date().toISOString(),
      name: metaRef.current.name,
      description: metaRef.current.description,
      goal: metaRef.current.goal,
      inputSchemaText: metaRef.current.inputSchemaText,
      graph: serializeFlowGraph(canvasRef.current.nodes, canvasRef.current.edges),
    }, window.localStorage)
  }, [])

  const currentSnapshotRef = useRef(currentSnapshot)
  currentSnapshotRef.current = currentSnapshot
  const savedSnapshotRef = useRef(savedSnapshot)
  savedSnapshotRef.current = savedSnapshot
  const metaRef = useRef({ name, description, goal, inputSchemaText })
  metaRef.current = { name, description, goal, inputSchemaText }

  useEffect(() => {
    if (!draftReady.current) return
    if (draftTimer.current !== null) window.clearTimeout(draftTimer.current)
    draftTimer.current = window.setTimeout(() => persistLocalDraft(), 500)
    return () => {
      if (draftTimer.current !== null) window.clearTimeout(draftTimer.current)
    }
  }, [currentSnapshot, persistLocalDraft])

  // ── load / save ──────────────────────────────────────────────────────────
  const toolsRef = useRef<AgentTool[]>([])
  toolsRef.current = tools
  const revisionRef = useRef<number | null>(null)
  revisionRef.current = revision

  const refreshTools = useCallback(async (): Promise<AgentTool[]> => {
    const fetchOnce = async (): Promise<AgentTool[] | null> => {
      try {
        const list = await services.agent.tools()
        return (list ?? []).filter((tool) => tool.pluginId !== 'workflow')
      } catch {
        return null // host briefly unreachable — keep the last known catalog
      }
    }
    let catalog = await fetchOnce()
    if (catalog === null || catalog.length === 0) {
      // A cold page load races many backend calls; one empty/failure round gets a retry
      // before the template gate treats tools as missing.
      catalog = await fetchOnce()
    }
    if (catalog === null) return toolsRef.current
    setTools(catalog)
    // Reconcile node availability/color against the fresh catalog.
    setNodes((current) => current.map((node) => {
      if (!isToolNode(node)) return node
      const tool = catalog?.find((item) => item.name === node.data.toolName)
      if (!tool) return node.data.available === false ? node : { ...node, data: { ...node.data, available: false } }
      return { ...node, data: { ...node.data, available: true, color: workflowNodeColor(tool) } }
    }))
    return catalog
  }, [])

  const loadWorkflow = useCallback((definition: WorkflowDefinition) => {
    resetHistory()
    setWorkflowId(definition.id)
    setRevision(definition.revision)
    setPublished(definition.published)
    setPublishedRevision(definition.publishedRevision ?? null)
    setHasUnpublishedChanges(!!definition.hasUnpublishedChanges)
    setRevisions([])
    setName(definition.name)
    setDescription(definition.description)
    setGoal(definition.plan.goal)
    const schemaText = JSON.stringify(definition.inputSchema, null, 2)
    setInputSchemaText(schemaText)
    const restored = rehydrateFlowGraph(definition.graph, toolsRef.current)
    let nextNodes: FlowCanvasNode[]
    let nextEdges: Edge[]
    if (restored && restored.nodes.length) {
      nextNodes = restored.nodes
      nextEdges = restored.edges
    } else {
      // Pre-graph definitions: reconstruct the canvas from the compiled plan.
      let planSequence = 0
      nextNodes = definition.plan.steps.map((step, index) => {
        const tool = toolsRef.current.find((item) => item.name === step.toolName)
        const resolved = tool ?? {
          id: `missing:${step.toolName}`,
          name: step.toolName,
          description: step.description,
          inputSchema: '{"type":"object","properties":{}}',
          revision: 'missing',
        }
        const saved = definition.layout?.[String(index)]
        return {
          id: `node_${++planSequence}`,
          type: 'tool' as const,
          position: saved
            ? { x: saved.x, y: saved.y }
            : { x: 48 + (index % 3) * 290, y: 48 + Math.floor(index / 3) * 150 },
          data: {
            toolName: step.toolName,
            argsText: JSON.stringify(step.args ?? {}, null, 2),
            description: step.description,
            requiresApproval: !!step.requiresApproval,
            available: !!tool,
            color: workflowNodeColor(resolved),
          },
        }
      })
      nextEdges = []
      definition.plan.steps.forEach((step) => {
        for (const dependency of step.dependsOn ?? []) {
          const source = nextNodes[dependency]
          const target = nextNodes[step.index]
          if (source && target) nextEdges.push(makeFlowEdge(source.id, target.id))
        }
      })
    }
    nextNodes = ensureStartNode(nextNodes)
    syncSequences(nextNodes)
    setNodes(nextNodes)
    setEdges(nextEdges)
    setSelectedNodeId(null)
    removeFlowDraft(definition.id, window.localStorage)
    setSavedSnapshot(serializeCanvasSnapshot({
      name: definition.name,
      description: definition.description,
      goal: definition.plan.goal,
      inputSchemaText: schemaText,
      nodes: nextNodes,
      edges: nextEdges,
    }))
    window.requestAnimationFrame(() => canvasApi.current?.fitView())
  }, [resetHistory])

  const offerLocalDraft = useCallback(async (draft: LocalFlowDraft | null) => {
    if (!draft) return
    const recoveryMode = flowDraftRecoveryMode(draft, revisionRef.current)
    const restore = await getPlatform().confirm(t(recoveryMode === 'stale-copy'
      ? 'agent.restoreStaleLocalDraftConfirm'
      : 'agent.restoreLocalDraftConfirm', { time: new Date(draft.savedAt).toLocaleString() }))
    if (!restore) {
      removeFlowDraft(draft.workflowId, window.localStorage)
      return
    }
    const restored = rehydrateFlowGraph(draft.graph, toolsRef.current)
    setName(draft.name)
    setDescription(draft.description)
    setInputSchemaText(draft.inputSchemaText)
    setGoal(draft.goal)
    if (recoveryMode === 'stale-copy') {
      removeFlowDraft(draft.workflowId, window.localStorage)
      setWorkflowId(null)
      setRevision(null)
      setPublished(false)
      setPublishedRevision(null)
      setHasUnpublishedChanges(false)
      setName(t('agent.localDraftRecoveredCopyName', { name: draft.name }))
    }
    if (restored) {
      const withStart = ensureStartNode(restored.nodes)
      syncSequences(withStart)
      setNodes(withStart)
      setEdges(restored.edges)
      setSavedSnapshot('__stale_local_draft_copy__')
    }
    resetHistory()
    setSelectedNodeId(null)
    setRecoveryMsg(t(recoveryMode === 'stale-copy'
      ? 'agent.staleLocalDraftRestoredAsCopy'
      : 'agent.localDraftRestored'))
    window.requestAnimationFrame(() => canvasApi.current?.fitView())
  }, [resetHistory, t])

  const resetNewWorkflow = useCallback(() => {
    resetHistory()
    setWorkflowId(null)
    setRevision(null)
    setPublished(false)
    setPublishedRevision(null)
    setHasUnpublishedChanges(false)
    setRevisions([])
    setName('')
    setDescription('')
    setGoal('')
    setInputSchemaText('{\n  "type": "object",\n  "properties": {}\n}')
    setNodes([])
    setEdges([])
    setSelectedNodeId(null)
    setRunDialogOpen(false)
    removeFlowDraft(null, window.localStorage)
    setSavedSnapshot(serializeCanvasSnapshot({
      name: '',
      description: '',
      goal: '',
      inputSchemaText: '{\n  "type": "object",\n  "properties": {}\n}',
      nodes: [],
      edges: [],
    }))
  }, [resetHistory])

  /** Applies a built-in template to the fresh canvas (Vue applyWorkflowTemplate port). */
  const applyWorkflowTemplate = useCallback((template: WorkflowTemplate, catalog: AgentTool[]) => {
    const missing = templateMissingTools(template, catalog)
    if (missing.length) {
      setErrorMsg(t('agent.templateMissingTools', { names: missing.join(', ') }))
      return
    }
    resetNewWorkflow()
    setName(t(template.titleKey))
    setDescription(t(template.descriptionKey))
    setInputSchemaText(JSON.stringify(templateInputSchema(template, (key) => t(key)), null, 2))
    setGoal(t(template.goalKey))
    const nodeId = new Map<string, string>()
    let nextNodes: FlowCanvasNode[] = template.nodes.map((spec) => {
      const tool = catalog.find((item) => item.name === spec.tool)
      if (!tool) {
        // The missing-tools gate above filters this out; keep types honest regardless.
        missing.push(spec.tool)
        return {
          id: `${template.id}_${spec.id}`,
          type: 'tool' as const,
          position: { x: spec.x, y: spec.y },
          data: {
            toolName: spec.tool,
            argsText: JSON.stringify(spec.args, null, 2),
            description: t(spec.descriptionKey),
            requiresApproval: !!spec.requiresApproval,
            available: false,
            color: workflowNodeColor(missingTool(spec.tool)),
          },
        }
      }
      const id = `${template.id}_${spec.id}`
      nodeId.set(spec.id, id)
      return {
        id,
        type: 'tool' as const,
        position: { x: spec.x, y: spec.y },
        data: {
          toolName: tool.name,
          argsText: JSON.stringify(spec.args, null, 2),
          description: t(spec.descriptionKey),
          requiresApproval: !!spec.requiresApproval,
          available: true,
          color: workflowNodeColor(tool),
        },
      }
    })
    if (missing.length) {
      setErrorMsg(t('agent.templateMissingTools', { names: missing.join(', ') }))
      return
    }
    nextNodes = ensureStartNode(nextNodes)
    const nextEdges = template.edges
      .filter(([source, target]) => nodeId.has(source) && nodeId.has(target))
      .map(([source, target, handle]) => makeFlowEdge(nodeId.get(source)!, nodeId.get(target)!, handle ?? null))
    // Node-reference placeholders inside template args point at the template's short
    // ids — rewrite them to the canvas ids just minted so the graph is immediately valid.
    for (const node of nextNodes) {
      if (!isToolNode(node)) continue
      node.data = {
        ...node.data,
        argsText: node.data.argsText.replace(
          /\{\{node\.([A-Za-z0-9_-]+)\.(result|input)/g,
          (reference, id: string, source: 'result' | 'input') =>
            (nodeId.has(id) ? `{{node.${nodeId.get(id)}.${source}` : reference),
        ),
      }
    }
    syncSequences(nextNodes)
    setNodes(nextNodes)
    setEdges(nextEdges)
    setSelectedNodeId(null)
    setSettingsOpen(false)
    window.requestAnimationFrame(() => canvasApi.current?.fitView())
  }, [resetNewWorkflow, syncSequences, t])

  const initialize = useCallback(async () => {
    const routeId = props.routeWorkflowId
    const pendingDraft = loadFlowDraft(routeId, window.localStorage)
    draftReady.current = false
    try {
      const catalog = await refreshTools()
      if (routeId) {
        try {
          loadWorkflow(await services.workflow.get(routeId))
          await offerLocalDraft(pendingDraft)
        } catch (e) {
          setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
        }
        return
      }
      resetNewWorkflow()
      // Library template cards land here with ?template=<id> pre-wiring the canvas.
      const templateId = new URLSearchParams(location.search).get('template')
      if (templateId) {
        const template = WORKFLOW_TEMPLATES.find((item) => item.id === templateId)
        if (template) applyWorkflowTemplate(template, catalog)
      }
      await offerLocalDraft(pendingDraft)
    } finally {
      draftReady.current = true
      setInitialized(true)
    }
  }, [props.routeWorkflowId, location.search, refreshTools, loadWorkflow, applyWorkflowTemplate, offerLocalDraft, resetNewWorkflow, t])

  const initializeRef = useRef(initialize)
  initializeRef.current = initialize

  // Re-initialize only when the route switches flows (not on every edit). The
  // initRouteRef guard also absorbs StrictMode's dev double-invoke, which would
  // otherwise race two initializes (double draft-restore confirms, ghost canvas).
  const initRouteRef = useRef<string | null | undefined>(undefined)
  useEffect(() => {
    if (skipNextInit.current) {
      skipNextInit.current = false
      initRouteRef.current = props.routeWorkflowId
      return
    }
    if (initRouteRef.current === props.routeWorkflowId) return
    initRouteRef.current = props.routeWorkflowId
    void initializeRef.current()
  }, [props.routeWorkflowId])

  const markCanvasClean = useCallback((id: string | null) => {
    setSavedSnapshot(serializeCanvasSnapshot({
      name: metaRef.current.name,
      description: metaRef.current.description,
      goal: metaRef.current.goal,
      inputSchemaText: metaRef.current.inputSchemaText,
      nodes: canvasRef.current.nodes,
      edges: canvasRef.current.edges,
    }))
    removeFlowDraft(id, window.localStorage)
    removeFlowDraft(null, window.localStorage)
  }, [])

  const persistWorkflow = useCallback(async (): Promise<boolean> => {
    try {
      const compiled = compileFlowPlan(toolNodesRef.current, canvasRef.current.edges, {
        goal: metaRef.current.goal,
        defaultGoal: t('agent.canvasDefaultGoal'),
      })
      const inputSchema = parseJsonObject(metaRef.current.inputSchemaText)
      if (!inputSchema) throw new Error(t('agent.invalidWorkflowJson', { label: t('agent.inputSchema') }))
      const currentId = draftState.current.workflowId
      const draft = {
        name: metaRef.current.name.trim(),
        description: metaRef.current.description.trim(),
        inputSchema,
        plan: compiled.plan,
        layout: compiled.layout,
        graph: serializeFlowGraph(canvasRef.current.nodes, canvasRef.current.edges),
        expectedRevision: draftState.current.revision ?? undefined,
      }
      const saved = currentId
        ? await services.workflow.update(currentId, draft)
        : await services.workflow.create(draft)
      if (!currentId) {
        setWorkflowId(saved.id)
        // Keep the address bar in sync without re-running initialization.
        skipNextInit.current = true
        navigate(`/flows/${saved.id}`, { replace: true })
      }
      setPublished(saved.published)
      setRevision(saved.revision)
      markCanvasClean(saved.id)
      setErrorMsg(null)
      return true
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
      return false
    }
  }, [markCanvasClean, navigate, t])

  const toolNodesRef = useRef(toolNodes)
  toolNodesRef.current = toolNodes

  /** Guides the next step: after the start node, the palette is the natural move. */
  const addStartAndOpenPalette = useCallback(() => {
    addStartNode()
    setPaletteOpen(true)
  }, [addStartNode])

  /** Selects and opens the first node with missing inputs (chip click / publish gate). */
  const focusFirstIncomplete = useCallback(() => {
    const first = toolNodes.find((node) => {
      const tool = toolsByName.get(node.data.toolName)
      return tool ? missingRequiredNodeInputs(tool, node.data.argsText).length > 0 : false
    })
    if (!first) return
    setSettingsOpen(false)
    setExecPanelOpen(false)
    setSelectedNodeId(first.id)
  }, [toolNodes, toolsByName])

  /** Adds the data-flow edge implied by a variable-tree reference binding. */
  const linkNodes = useCallback((sourceId: string, targetId: string) => {
    editor.pushHistory()
    setEdges((current) => current.some((edge) => edge.source === sourceId && edge.target === targetId)
      ? current
      : [...current, makeFlowEdge(sourceId, targetId)])
  }, [editor])

  /**
   * Single-step debug run: executes this node plus its dependency closure in ONE
   * ordinary run so stateful plugin chains (configure → execute) share session
   * state. Missing run-input bindings surface as the compiler's localized error.
   */
  const runSingleStep = useCallback((nodeId: string) => {
    const node = toolNodesRef.current.find((candidate) => candidate.id === nodeId)
    if (!node || run.busy || node.data.available === false) return
    try {
      const included = workflowDependencyClosure(nodeId, toolNodesRef.current, canvasRef.current.edges)
      const subNodes = toolNodesRef.current.filter((candidate) => included.has(candidate.id))
      const subEdges = canvasRef.current.edges.filter((edge) => included.has(edge.source) && included.has(edge.target))
      const compiled = compileFlowPlan(subNodes, subEdges, {
        goal: t('agent.singleStepGoal', { name: workflowNodeTitle(node.data, toolsRef.current.find((tool) => tool.name === node.data.toolName)) }),
        bindInputs: true,
        inputs: {},
      })
      const ordered = topologicallySortNodes(subNodes, subEdges) ?? []
      setStepNodeIds(ordered.map((candidate) => candidate.id))
      setErrorMsg(null)
      setExecPanelOpen(true)
      setDialogRunActive(true)
      const config: AgentRunConfig = {
        requirePlanApproval: false,
        // Debug semantics: no extra step gate (the user explicitly invoked this node),
        // but the ASK permission mode still guards destructive tools.
        requireStepApproval: false,
        replanOnFailure: false,
        maxReplans: 0,
        permissionMode: 'ask-for-approval',
      }
      void services.agent.run({ goal: compiled.plan.goal, config, workflow: compiled.plan })
        .then((response) => {
          run.attachRun(response.runId, compiled.plan)
        })
        .catch((e: unknown) => {
          setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
        })
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }, [run, t])

  // ── run ──────────────────────────────────────────────────────────────────
  const requestRun = useCallback(() => {
    try {
      // Compile once up front so configuration errors surface before the modal opens.
      compileFlowPlan(toolNodesRef.current, canvasRef.current.edges, {
        goal: metaRef.current.goal,
        defaultGoal: t('agent.canvasDefaultGoal'),
      })
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
      return
    }
    setRunDialogOpen(true)
  }, [t])

  const startRun = useCallback(async (payload: FlowRunStartPayload) => {
    try {
      setErrorMsg(null)
      const runConfig: AgentRunConfig = {
        requirePlanApproval: false,
        requireStepApproval: true,
        replanOnFailure: false,
        maxReplans: 0,
        permissionMode: payload.permissionMode,
      }
      const compiled = compileFlowPlan(toolNodesRef.current, canvasRef.current.edges, {
        goal: metaRef.current.goal,
        defaultGoal: t('agent.canvasDefaultGoal'),
      })
      setStepNodeIds(compiled.orderedIds)
      if (workflowId) {
        // A failed auto-save must not silently run the previous server revision.
        if (dirty && !await persistWorkflow()) return
      const response = await services.workflow.run(workflowId, { inputs: payload.inputs, config: runConfig })
      run.attachRun(response.runId, compiled.plan)
      setExecPanelOpen(true)
      setDialogRunActive(true)
      } else {
        const bound = compileFlowPlan(toolNodesRef.current, canvasRef.current.edges, {
          goal: metaRef.current.goal,
          defaultGoal: t('agent.canvasDefaultGoal'),
          bindInputs: true,
          inputs: payload.inputs,
        })
        const response = await services.agent.run({
          goal: bound.plan.goal,
          config: runConfig,
          workflow: bound.plan,
        })
        run.attachRun(response.runId, bound.plan)
        setExecPanelOpen(true)
        setDialogRunActive(true)
      }
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }, [workflowId, dirty, persistWorkflow, run, t])

  const prepareChatTurn = useCallback(async (): Promise<boolean> => {
    // Save valid work first so run_current_flow targets exactly what the user
    // sees; an invalid canvas stays live for inspect/diagnose tooling.
    if (dirtyRef.current && toolNodesRef.current.length) await persistWorkflow()
    return true
  }, [persistWorkflow])

  const dirtyRef = useRef(dirty)
  dirtyRef.current = dirty

  // ── publish / version history / delete (Vue settings-drawer surface) ─────
  const publishedRef = useRef(published)
  publishedRef.current = published
  const hasUnpublishedRef = useRef(hasUnpublishedChanges)
  hasUnpublishedRef.current = hasUnpublishedChanges
  const incompleteRef = useRef(0)

  const toErrorMessage = (e: unknown): string =>
    e instanceof Error && e.message ? e.message : t('agent.failed')

  /** Publish exposes the flow as an AI tool; publish-changes re-publishes the draft. */
  const togglePublish = useCallback(async () => {
    const id = workflowIdRef.current
    if (!id) return
    if (!publishedRef.current && incompleteRef.current > 0) {
      // Point the user at the fix instead of only naming the problem.
      focusFirstIncomplete()
      setErrorMsg(t('agent.canvasMissingInputs'))
      return
    }
    try {
      // Publishing the draft's current look: save dirty work first so the published
      // revision is exactly what the user sees.
      if (dirtyRef.current && !await persistWorkflow()) return
      const nextPublished = !publishedRef.current || hasUnpublishedRef.current
      const saved = await services.workflow.publish(id, nextPublished, revisionRef.current ?? undefined)
      setPublished(saved.published)
      setRevision(saved.revision)
      setPublishedRevision(saved.publishedRevision ?? null)
      setHasUnpublishedChanges(!!saved.hasUnpublishedChanges)
      setErrorMsg(null)
      // Publishing/unpublishing changes the tool catalog this canvas draws from.
      void refreshTools()
    } catch (e) {
      setErrorMsg(toErrorMessage(e))
    }
  }, [persistWorkflow, refreshTools, t])

  // Revision list follows the settings panel (and refreshes after save/publish).
  useEffect(() => {
    if (!settingsOpen || !workflowId) return
    let cancelled = false
    services.workflow.revisions(workflowId)
      .then((list) => { if (!cancelled) setRevisions(list) })
      .catch(() => { if (!cancelled) setRevisions([]) })
    return () => { cancelled = true }
  }, [settingsOpen, workflowId, revision])

  const restoreRevision = useCallback(async (target: number) => {
    const id = workflowIdRef.current
    if (!id) return
    if (!await getPlatform().confirm(t('agent.restoreVersionConfirm', { revision: target }), { danger: true })) return
    try {
      loadWorkflow(await services.workflow.restoreRevision(id, target, revisionRef.current ?? undefined))
      setRecoveryMsg(null)
    } catch (e) {
      setErrorMsg(toErrorMessage(e))
    }
  }, [loadWorkflow, t])

  const deleteWorkflow = useCallback(async () => {
    const id = workflowIdRef.current
    if (!id) return
    if (!await getPlatform().confirm(t('agent.deleteWorkflowConfirm'), { danger: true })) return
    try {
      await services.workflow.delete(id)
      // Clean the snapshot so the dispatcher's dirty guard lets the exit through.
      markCanvasClean(id)
      void navigate('/flows')
    } catch (e) {
      setErrorMsg(toErrorMessage(e))
    }
  }, [markCanvasClean, navigate, t])

  // ── AI authoring proposals (edit_current_flow → canvas) ──────────────────
  const applyFlowProposal = useCallback(async (proposal: FlowAuthoringProposal): Promise<boolean> => {
    if (proposal.baseWorkflowId !== workflowIdRef.current
      || (proposal.baseSnapshotId && proposal.baseSnapshotId !== flowSnapshotId(currentSnapshotRef.current))
      || (proposal.baseRevision !== null && proposal.baseRevision !== revisionRef.current)) {
      setErrorMsg(t('flows.chatProposalStale'))
      return false
    }
    // Structural gates BEFORE any history/canvas mutation: unique node ids, resolvable
    // edge endpoints, at most one Start. A malformed proposal leaves the canvas untouched.
    if (flowProposalGraphProblems(proposal.graph).length) {
      setErrorMsg(t('flows.chatProposalInvalid'))
      return false
    }
    const restored = rehydrateFlowGraph(proposal.graph, toolsRef.current)
    if (!restored) {
      setErrorMsg(t('flows.chatProposalInvalid'))
      return false
    }
    const unavailable = restored.nodes.filter((node): node is ToolCanvasNode =>
      isToolNode(node) && !node.data.available)
    if (unavailable.length) {
      setErrorMsg(t('agent.canvasUnavailableTools', {
        names: unavailable.map((node) => node.data.toolName).join(', '),
      }))
      return false
    }
    editor.pushHistory()
    setName(proposal.name)
    setDescription(proposal.description)
    setGoal(proposal.goal)
    setInputSchemaText(JSON.stringify(proposal.inputSchema, null, 2))
    const withStart = ensureStartNode(restored.nodes)
    syncSequences(withStart)
    setNodes(withStart)
    setEdges(restored.edges)
    setSelectedNodeId(null)
    setSettingsOpen(false)
    setErrorMsg(null)
    window.requestAnimationFrame(() => canvasApi.current?.fitView())
    const saved = await persistWorkflow()
    if (saved) setRecoveryMsg(t('flows.chatProposalSaved'))
    return saved
  }, [editor, persistWorkflow, syncSequences, t])

  // ── leaving guards ───────────────────────────────────────────────────────
  // Reload/close guard.
  useEffect(() => {
    if (!dirty || !nodes.length) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [dirty, nodes.length])

  /**
   * Leaving the /flows section is confirmed at the navigation source: shell nav
   * surfaces (sidebar, shortcuts, notifications) consult the single-slot
   * navigation guard before calling navigate(). Flow-internal switches are
   * confirmed by FlowLibraryPage's dispatcher instead. This replaces the old
   * history.pushState patch, whose synchronous window.confirm could not cancel
   * a React Router transition (the view swaps even when the pushState is
   * swallowed) and popped a native/system dialog.
   */
  useEffect(() => {
    if (!dirty || !nodes.length) return
    return setNavigationGuard(() => appConfirm(t('agent.discardConfirm'), { danger: true }))
  }, [dirty, nodes.length, t])

  // The dispatcher's discard confirmation covers the dirty case — going back
  // must never ask twice.
  const backToLibrary = useCallback(() => {
    void navigate('/flows')
  }, [navigate])

  // ── keyboard shortcuts (⌘Z / ⌘⇧Z / N) ────────────────────────────────────
  useEffect(() => {
    const onKeydown = (event: KeyboardEvent) => {
      if (runDialogOpen || run.busy) return
      const target = event.target as HTMLElement | null
      const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA'
        || target.tagName === 'SELECT' || target.isContentEditable)
      if (typing) return
      const meta = event.metaKey || event.ctrlKey
      if (meta && event.key.toLowerCase() === 'z') {
        event.preventDefault()
        if (event.shiftKey) redoCanvas()
        else undoCanvas()
        return
      }
      if (event.key.toLowerCase() === 'n') {
        event.preventDefault()
        setPaletteOpen(true)
      }
    }
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  }, [runDialogOpen, run.busy, undoCanvas, redoCanvas])

  // ── mount: catalog refresh on focus, seed, cleanup ───────────────────────
  useEffect(() => {
    const onFocus = () => void refreshTools()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [refreshTools])

  // Consume the chat composer's "@ → flow" hand-off once initialization has
  // resolved the workflow id.
  const workflowIdRef = useRef<string | null>(null)
  workflowIdRef.current = workflowId
  useEffect(() => {
    if (!initialized) return
    try {
      const raw = sessionStorage.getItem(FLOW_CHAT_SEED_KEY)
      if (!raw) return
      sessionStorage.removeItem(FLOW_CHAT_SEED_KEY)
      const seed = JSON.parse(raw) as FlowChatSeed
      if (seed.workflowId === workflowIdRef.current && seed.text?.trim()) {
        setChatSeed(seed.text)
        setChatOpen(true)
      }
    } catch {
      /* malformed seed — drop it */
    }
  }, [initialized])

  const runCloseRef = useRef(run.closeStream)
  runCloseRef.current = run.closeStream
  useEffect(() => () => {
    if (draftTimer.current !== null) window.clearTimeout(draftTimer.current)
    persistLocalDraft()
    runCloseRef.current()
  }, [persistLocalDraft])

  // ── render ───────────────────────────────────────────────────────────────
  const canSave = name.trim().length > 0 && toolNodes.length > 0 && !run.busy
  const incompleteCount = toolNodes.filter((node) => {
    const tool = toolsByName.get(node.data.toolName)
    return tool ? missingRequiredNodeInputs(tool, node.data.argsText).length > 0 : false
  }).length
  incompleteRef.current = incompleteCount

  return (
    <div className="flow-builder">
      <FlowToolbar
        title={workflowTitle}
        nodeCount={toolNodes.length}
        published={published}
        hasUnpublishedChanges={hasUnpublishedChanges}
        dirty={dirty}
        busy={run.busy}
        chatOpen={chatOpen}
        settingsOpen={settingsOpen}
        execOpen={execPanelOpen}
        canSave={canSave}
        runDisabled={!run.busy && (!toolNodes.length || toolNodes.some((node) => node.data.available === false))}
        onBack={() => void backToLibrary()}
        onToggleChat={() => setChatOpen(!chatOpen)}
        onToggleSettings={() => setSettingsOpen(!settingsOpen)}
        onToggleExec={() => setExecPanelOpen(!execPanelOpen)}
        onSave={() => void persistWorkflow()}
        onRunClick={() => (run.busy ? void run.cancel() : requestRun())}
      />

      <div className="flow-workspace">
        {paletteOpen && (
          <aside className="flow-panel flow-panel--left">
            <FlowPalette
              tools={tools}
              hasStart={!!startNode}
              disabled={run.busy}
              onAdd={(tool) => addTool(tool)}
              onAddStart={addStartNode}
              onDragStart={(event, tool) => {
                event.dataTransfer.setData('application/x-fengyu-tool', tool.name)
                if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
              }}
              onClose={() => setPaletteOpen(false)}
            />
          </aside>
        )}

        <div className="flow-stage-wrap">
          <FlowCanvas
            nodes={nodes}
            edges={edges}
            nodeStatus={nodeStatus}
            toolsByName={toolsByName}
            interactive={!run.busy}
            onNodesChange={handleNodesChange}
            onEdgesChange={handleEdgesChange}
            onConnect={handleConnect}
            onNodeClick={(nodeId) => {
              setSelectedNodeId(nodeId || null)
              if (nodeId) setPaletteOpen(false)
            }}
            onNodeDragStart={editor.onNodeDragStart}
            onNodeDragStop={editor.onNodeDragStop}
            onDropTool={(toolName, position) => {
              const tool = toolsByName.get(toolName)
              if (tool) addTool(tool, position)
            }}
            onApi={(instance) => {
              canvasApi.current = instance
            }}
          />

          {!startNode && !toolNodes.length && (
            <FlowEmptyState
              templates={WORKFLOW_TEMPLATES.map((template) => ({
                template,
                missing: templateMissingTools(template, tools),
              }))}
              onAddStart={addStartAndOpenPalette}
              onApplyTemplate={(template) => applyWorkflowTemplate(template, tools)}
            />
          )}

          <FlowCanvasActions
            paletteOpen={paletteOpen}
            canUndo={canUndo}
            canRedo={canRedo}
            incompleteCount={incompleteCount}
            onTogglePalette={() => setPaletteOpen(!paletteOpen)}
            onUndo={undoCanvas}
            onRedo={redoCanvas}
            onAddNote={addStickyNote}
            onFitView={() => canvasApi.current?.fitView()}
            onFocusIncomplete={focusFirstIncomplete}
          />

          <FlowStageAlert
            errorMsg={errorMsg}
            recoveryMsg={recoveryMsg}
            onClearError={() => setErrorMsg(null)}
            onClearRecovery={() => setRecoveryMsg(null)}
          />

          {settingsOpen && (
            <FlowSettingsPanel
              name={name}
              description={description}
              goal={goal}
              canSave={canSave}
              hasId={!!workflowId}
              published={published}
              hasUnpublishedChanges={hasUnpublishedChanges}
              publishedRevision={publishedRevision}
              revisions={revisions}
              onName={setName}
              onDescription={setDescription}
              onGoal={setGoal}
              onSave={() => void persistWorkflow()}
              onTogglePublish={() => void togglePublish()}
              onRestoreRevision={(target) => void restoreRevision(target)}
              onDelete={() => void deleteWorkflow()}
              onClose={() => setSettingsOpen(false)}
            />
          )}

          {chatOpen && (
            <aside className="flow-chat-dock">
              <FlowChatPanel
                workflowId={workflowId}
                workflowTitle={workflowTitle}
                context={flowContext}
                seedPrompt={chatSeed}
                prepare={prepareChatTurn}
                applyProposal={applyFlowProposal}
                onClose={() => setChatOpen(false)}
                onSeedConsumed={() => setChatSeed(null)}
              />
            </aside>
          )}
        </div>

        {selectedNode && !settingsOpen && !execPanelOpen && (
          <aside className="flow-panel flow-panel--right">
            <FlowInspector
              node={selectedNode}
              nodes={nodes}
              edges={edges}
              toolsByName={toolsByName}
              inputSchemaText={inputSchemaText}
              disabled={run.busy}
              onPatchTool={patchToolData}
              onPatchSticky={patchStickyData}
              onPatchInputSchema={setInputSchemaText}
              onLink={linkNodes}
              onRunNode={(nodeId) => runSingleStep(nodeId)}
              onDelete={removeNode}
              onClose={() => setSelectedNodeId(null)}
            />
          </aside>
        )}

        {execPanelOpen && (
          <FlowExecutionPanel run={run} onClose={() => setExecPanelOpen(false)} />
        )}
      </div>

      <FlowRunDialog
        open={runDialogOpen}
        workflowTitle={workflowTitle}
        nodeCount={toolNodes.length}
        inputSchemaText={inputSchemaText}
        run={dialogRunActive ? run : null}
        onClose={() => {
          setRunDialogOpen(false)
          setDialogRunActive(false)
        }}
        onRun={(payload) => void startRun(payload)}
        onCancel={() => void run.cancel()}
        onApprove={() => void run.approve()}
      />
    </div>
  )
}
