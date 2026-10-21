import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { services } from '@/services'
import type {
  AgentTool,
  FlowAuthoringContext,
  FlowAuthoringProposal,
} from '@/services/types'
import '@/styles/features/flow.css'
import { FLOW_CHAT_SEED_KEY, type FlowChatSeed } from '@/lib/flowSeed'
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
  const [consuming, setConsuming] = useState(true)
  const toolsRef = useRef<AgentTool[]>([])
  toolsRef.current = tools

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
      <div className="flow-copilot__body">
        <FlowChatPanel
          variant="page"
          workflowId={null}
          workflowTitle={t('flows.copilotTitle')}
          context={flowContext}
          seedPrompt={chatSeed}
          seedAutoSend={chatSeedAuto}
          prepare={async () => true}
          applyProposal={applyProposal}
          onClose={() => navigate('/flows')}
          onSeedConsumed={() => { setChatSeed(null); setChatSeedAuto(false) }}
        />
      </div>
    </div>
  )
}
