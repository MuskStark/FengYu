import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { services } from '@/services'
import type {
  AgentRunConfig,
  AgentRunFile,
  AgentTool,
  AiPermissionMode,
  FlowAuthoringContext,
  FlowAuthoringProposal,
} from '@/services/types'
import '@/styles/features/flow.css'
import { FLOW_CHAT_SEED_KEY, type FlowChatSeed } from '@/lib/flowSeed'
import { useAgentRunStream } from '@/lib/agentRunStream'
import {
  blockedProposalExclusions,
  excludeProposalNodes,
  flowProposalGraphProblems,
} from '@/lib/flowAiAuthoring'
import {
  compileFlowPlan,
  isToolNode,
  rehydrateFlowGraph,
  serializeFlowGraph,
  type ToolCanvasNode,
} from '@/lib/flowGraph'
import { FlowChatPanel } from '@/components/flow/FlowChatPanel'
import { FlowBlueprintPane } from '@/components/flow/FlowBlueprintPane'
import { FlowRunDialog, type FlowRunStartPayload } from '@/components/flow/FlowRunDialog'

/**
 * Conversation-first flow creation (`/flows/create`): FlowHome's describe box hands its
 * intent here, the copilot chat proposes the flow, and applying a proposal CREATES the
 * workflow and opens the builder with real content on the canvas. Novices never face the
 * empty builder chrome while they are still talking — the canvas appears only once there
 * is something to show.
 */
export default function FlowCopilotPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [tools, setTools] = useState<AgentTool[]>([])
  const [chatSeed, setChatSeed] = useState<string | null>(null)
  const [chatSeedAuto, setChatSeedAuto] = useState(false)
  const [chatSeedPermission, setChatSeedPermission] = useState<AiPermissionMode | null>(null)
  const [consuming, setConsuming] = useState(true)
  /** Mirrors the newest live proposal for the blueprint pane (null = drafting board empty). */
  const [blueprint, setBlueprint] = useState<{ proposal: FlowAuthoringProposal | null; excluded?: Set<string> }>({ proposal: null })
  /** Trial-run dialog (inline plan execution — the proposal is never saved). */
  const [runDialogOpen, setRunDialogOpen] = useState(false)
  const [dialogRunActive, setDialogRunActive] = useState(false)
  const [trialError, setTrialError] = useState<string | null>(null)
  const run = useAgentRunStream()
  const toolsRef = useRef<AgentTool[]>([])
  toolsRef.current = tools

  // Stable identity: FlowChatPanel reports proposal changes, this stores them.
  const onProposalChange = useCallback(
    (proposal: FlowAuthoringProposal | null, excluded?: Set<string>) => setBlueprint({ proposal, excluded }),
    [],
  )

  // The catalog gates tool availability inside the headless apply; same retry-once
  // tolerance as the builder's refreshTools for a cold boot racing many calls.
  useEffect(() => {
    let cancelled = false
    const load = async () => {
      const fetchOnce = async (): Promise<AgentTool[] | null> => {
        try {
          return (await services.agent.tools())
            ?.filter((tool) => tool.pluginId !== 'workflow') ?? []
        } catch {
          return null
        }
      }
      let catalog = await fetchOnce()
      if (catalog === null || catalog.length === 0) catalog = await fetchOnce()
      if (!cancelled && catalog !== null) setTools(catalog)
    }
    void load().finally(() => { if (!cancelled) setConsuming(false) })
    return () => { cancelled = true }
  }, [])

  // Consume FlowHome's describe-box seed (auto-send) once the catalog is in.
  useEffect(() => {
    if (consuming) return
    try {
      const raw = sessionStorage.getItem(FLOW_CHAT_SEED_KEY)
      if (!raw) return
      sessionStorage.removeItem(FLOW_CHAT_SEED_KEY)
      const seed = JSON.parse(raw) as FlowChatSeed
      if (seed.workflowId === null && seed.text?.trim()) {
        setChatSeedAuto(seed.autoSend === true)
        setChatSeed(seed.text)
        // Only the modes the flow chat offers — a malformed seed never reaches the send.
        const mode = seed.permissionMode
        setChatSeedPermission(
          mode === 'full-access' || mode === 'approve-for-me' || mode === 'ask-for-approval' ? mode : null)
      }
    } catch {
      /* malformed seed — drop it */
    }
  }, [consuming])

  const flowContext = useMemo<FlowAuthoringContext>(() => ({
    workflowId: null,
    revision: null,
    snapshotId: '',
    dirty: false,
    name: '',
    description: '',
    goal: '',
    inputSchema: {},
    graph: { nodes: [], edges: [] },
    diagnostics: [],
  }), [])

  /**
   * Headless apply: validate the proposed graph against the live catalog, compile
   * graph→plan (the same pure compiler the canvas uses), create the workflow, and open
   * the builder — which now shows a real flow instead of its empty state.
   */
  const applyProposal = useCallback(async (
    proposal: FlowAuthoringProposal,
    excludedIds?: Set<string>,
  ): Promise<boolean> => {
    let effective = proposal
    if (excludedIds?.size) {
      const blocked = blockedProposalExclusions(proposal.graph, excludedIds)
      if (blocked.length) return false
      effective = excludeProposalNodes(proposal, excludedIds)
    }
    if (flowProposalGraphProblems(effective.graph).length) return false
    const restored = rehydrateFlowGraph(effective.graph, toolsRef.current)
    if (!restored || !restored.nodes.length) return false
    const unavailable = restored.nodes.filter((node): node is ToolCanvasNode =>
      isToolNode(node) && !node.data.available)
    if (unavailable.length) return false
    try {
      const toolNodes = restored.nodes.filter(isToolNode)
      const compiled = compileFlowPlan(toolNodes, restored.edges, {
        goal: effective.goal || effective.name,
        defaultGoal: t('agent.canvasDefaultGoal'),
      })
      const saved = await services.workflow.create({
        name: effective.name.trim(),
        description: effective.description ?? '',
        inputSchema: effective.inputSchema ?? {},
        plan: compiled.plan,
        layout: compiled.layout,
        graph: serializeFlowGraph(restored.nodes, restored.edges),
      })
      navigate(`/flows/${saved.id}`)
      return true
    } catch {
      return false
    }
  }, [navigate, t])

  /**
   * Trial run: execute the live proposal INLINE (the same POST /api/agent/run
   * path the builder's unsaved canvas uses) — no workflow is created, nothing
   * enters the library. Validation mirrors applyProposal so the run matches
   * exactly what "apply" would create.
   */
  const validateForRun = useCallback(():
    | { toolNodes: ToolCanvasNode[]; edges: NonNullable<ReturnType<typeof rehydrateFlowGraph>>['edges'] }
    | { error: string } => {
    const proposal = blueprint.proposal
    if (!proposal) return { error: t('agent.failed') }
    let effective = proposal
    if (blueprint.excluded?.size) {
      const blocked = blockedProposalExclusions(proposal.graph, blueprint.excluded)
      if (blocked.length) return { error: t('flows.blueprintRunBlocked') }
      effective = excludeProposalNodes(proposal, blueprint.excluded)
    }
    if (flowProposalGraphProblems(effective.graph).length) return { error: t('flows.blueprintRunBlocked') }
    const restored = rehydrateFlowGraph(effective.graph, toolsRef.current)
    if (!restored || !restored.nodes.length) return { error: t('flows.blueprintRunBlocked') }
    const unavailable = restored.nodes.filter((node): node is ToolCanvasNode =>
      isToolNode(node) && !node.data.available)
    if (unavailable.length) {
      return { error: t('flows.blueprintRunMissingTools', { names: unavailable.map((node) => node.data.toolName).join(', ') }) }
    }
    return { toolNodes: restored.nodes.filter(isToolNode), edges: restored.edges }
  }, [blueprint, t])

  const openTrialRun = useCallback(() => {
    const check = validateForRun()
    if ('error' in check) {
      setTrialError(check.error)
      return
    }
    setTrialError(null)
    setRunDialogOpen(true)
  }, [validateForRun])

  /** No run came of this start attempt — nobody terminal-cleans the picked
   *  grants, so revoke them here (the dialog already handed ownership over). */
  const revokeRunFiles = useCallback((files: AgentRunFile[] | undefined) => {
    for (const file of files ?? []) {
      for (const entry of file.refs ?? []) {
        void services.chat.revokeAiFile(entry.pluginId, entry.ref.id).catch(() => {
          // Already-gone grants just 404; nothing to recover.
        })
      }
    }
  }, [])

  const startTrialRun = useCallback(async (payload: FlowRunStartPayload) => {
    try {
      setTrialError(null)
      const check = validateForRun()
      if ('error' in check) throw new Error(check.error)
      const runConfig: AgentRunConfig = {
        requirePlanApproval: false,
        requireStepApproval: true,
        replanOnFailure: false,
        maxReplans: 0,
        permissionMode: payload.permissionMode,
      }
      // Inline plans get no server-side {{inputs.*}} binding — the client binds
      // them here (the builder's unsaved-canvas run does the same).
      const bound = compileFlowPlan(check.toolNodes, check.edges, {
        goal: blueprint.proposal?.goal || blueprint.proposal?.name || '',
        defaultGoal: t('agent.canvasDefaultGoal'),
        bindInputs: true,
        inputs: payload.inputs,
      })
      const response = await services.agent.run({
        goal: bound.plan.goal,
        config: runConfig,
        workflow: bound.plan,
        files: payload.files,
      })
      run.attachRun(response.runId, bound.plan)
      setDialogRunActive(true)
    } catch (e) {
      revokeRunFiles(payload.files)
      // The picks are revoked, so the still-open form would resubmit dead
      // grants — close it and let the page alert carry the error.
      setRunDialogOpen(false)
      setTrialError(e instanceof Error && e.message ? e.message : t('agent.failed'))
    }
  }, [blueprint.proposal, run, revokeRunFiles, t, validateForRun])

  return (
    <div className="flow-copilot">
      <header className="flow-copilot__bar">
        <button
          className="cx-iconbtn cx-iconbtn--sm"
          title={t('flows.backToLibrary')}
          onClick={() => navigate('/flows')}
        >
          <ArrowLeft size={15} />
        </button>
        <strong>{t('flows.copilotTitle')}</strong>
        <span className="cx-muted">{t('flows.copilotHint')}</span>
      </header>
      {trialError && (
        <div className="cx-alert cx-alert--error flow-copilot__alert">
          <span className="cx-alert__body">{trialError}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setTrialError(null)}>×</button>
        </div>
      )}
      <div className="flow-copilot__body">
        <FlowChatPanel
          variant="page"
          workflowId={null}
          workflowTitle={t('flows.copilotTitle')}
          context={flowContext}
          seedPrompt={chatSeed}
          seedAutoSend={chatSeedAuto}
          seedPermissionMode={chatSeedPermission ?? undefined}
          prepare={async () => true}
          applyProposal={applyProposal}
          onProposalChange={onProposalChange}
          onClose={() => navigate('/flows')}
          onSeedConsumed={() => {
            setChatSeed(null)
            setChatSeedAuto(false)
            setChatSeedPermission(null)
          }}
        />
        <FlowBlueprintPane
          proposal={blueprint.proposal}
          excluded={blueprint.excluded}
          onTrialRun={openTrialRun}
        />
      </div>
      {blueprint.proposal && (
        <FlowRunDialog
          open={runDialogOpen}
          workflowTitle={blueprint.proposal.name}
          nodeCount={blueprint.proposal.graph.nodes.filter(
            (node) => node.type === 'tool' && !blueprint.excluded?.has(node.id)).length}
          inputSchemaText={JSON.stringify(blueprint.proposal.inputSchema ?? {})}
          run={dialogRunActive ? run : null}
          onClose={() => {
            setRunDialogOpen(false)
            setDialogRunActive(false)
          }}
          onRun={(payload) => void startTrialRun(payload)}
          onCancel={() => void run.cancel()}
          onApprove={() => void run.approve()}
        />
      )}
    </div>
  )
}
