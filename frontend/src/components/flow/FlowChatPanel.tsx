import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Send, Square, X } from 'lucide-react'
import { i18n } from '@/i18n'
import { services } from '@/services'
import type { StreamHandle } from '@/services/agent'
import type {
  AiPermissionMode,
  ChatMessage,
  FlowAuthoringContext,
  FlowAuthoringProposal,
} from '@/services/types'
import { diffFlowProposal, parseFlowProposal, type FlowProposalDiff } from '@/lib/flowAiAuthoring'

/**
 * Docked Flow authoring chat (core behaviors of the Vue FlowChatPanel): message
 * flow + composer; every turn carries the live canvas context and (when saved)
 * binds the workflow id, so the backend's inspect/diagnose/edit/run flow tools
 * operate on exactly what the user sees. Tool activity (including approval
 * requests) renders inline, and `edit_current_flow` previews render as proposal
 * cards with a canvas diff and Apply/Dismiss.
 */

interface ToolActivity {
  id: string
  name: string
  phase: 'call' | 'approval_required' | 'result'
  success?: boolean
  approvalId?: string
  resolved?: boolean
  output?: string
  proposal?: FlowAuthoringProposal
  proposalDiff?: FlowProposalDiff
  proposalState?: 'applying' | 'applied' | 'dismissed' | 'failed'
}

interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
  tools?: ToolActivity[]
}

/** Maps the chat stream's structured error codes onto localized copy. */
function chatStreamErrorText(err: { code?: string; message?: string }): string {
  if (err.code === 'ticket_failed') return i18n.global.t('agent.streamTicketFailed')
  if (err.code === 'stream_lost') return i18n.global.t('agent.streamLost')
  if (err.code === 'stream_ended') return i18n.global.t('agent.streamEnded')
  return err.message || i18n.global.t('agent.failed')
}

const SYSTEM_PROMPT: ChatMessage = {
  role: 'system',
  content: 'You are the FengYu Flow Builder assistant. Always call inspect_current_flow before '
    + 'reasoning about the canvas. For save/run failures call diagnose_current_flow. When the user '
    + 'asks to create or change the Flow, call edit_current_flow and return its preview; never claim '
    + 'the Flow changed until the user applies that proposal. Use run_current_flow only when it is '
    + 'available and the user explicitly wants to execute the clean saved Flow.',
}

export function FlowChatPanel(props: {
  workflowId: string | null
  workflowTitle: string
  context: FlowAuthoringContext
  /** One-shot prefill from the chat composer's "@ → flow" hand-off. */
  seedPrompt?: string | null
  /** Runs before a turn is sent (auto-save of a valid canvas). */
  prepare?: () => Promise<boolean>
  /** Applies an accepted AI proposal to the canvas (validates + persists). */
  applyProposal?: (proposal: FlowAuthoringProposal) => Promise<boolean>
  onClose: () => void
  onSeedConsumed?: () => void
}) {
  const { t } = useTranslation()
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [input, setInput] = useState('')
  const [permissionMode, setPermissionMode] = useState<AiPermissionMode>('ask-for-approval')
  const [busy, setBusy] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)
  const streamRef = useRef<StreamHandle | null>(null)
  const streamIdRef = useRef<string | null>(null)

  // Consume the "@ → flow" seed once: prefill the composer and focus it.
  useEffect(() => {
    if (!props.seedPrompt) return
    setInput(props.seedPrompt)
    props.onSeedConsumed?.()
    inputRef.current?.focus()
  }, [props.seedPrompt, props.onSeedConsumed])

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const scrollToBottom = () => {
    window.requestAnimationFrame(() => {
      if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
    })
  }

  /** Stateless history for the next request; the live Flow context rides beside it. */
  const toHistory = (): ChatMessage[] => [
    SYSTEM_PROMPT,
    ...turns
      .filter((turn) => turn.content.trim() || turn.role === 'user')
      .map((turn) => ({ role: turn.role, content: turn.content })),
  ]

  const patchLastAssistant = (mutate: (turn: ChatTurn) => ChatTurn) => {
    setTurns((current) => {
      if (!current.length) return current
      const last = current[current.length - 1]
      if (last.role !== 'assistant') return current
      return [...current.slice(0, -1), mutate(last)]
    })
  }

  const applyToolEvent = (payload: Record<string, unknown>) => {
    const id = String(payload.id ?? '')
    const name = String(payload.name ?? '')
    const phase = String(payload.phase ?? '')
    if (!id) return
    patchLastAssistant((last) => {
      const tools = [...(last.tools ?? [])]
      const index = tools.findIndex((activity) => activity.id === id)
      const activity: ToolActivity = index >= 0 ? tools[index] : { id, name, phase: 'call' }
      const next = { ...activity }
      if (phase === 'call') {
        next.phase = 'call'
      } else if (phase === 'approval_required') {
        next.phase = 'approval_required'
        next.approvalId = payload.approvalId ? String(payload.approvalId) : undefined
        next.resolved = false
      } else if (phase === 'result') {
        next.phase = 'result'
        next.success = payload.success !== false
        next.output = typeof payload.output === 'string' ? payload.output : undefined
        // edit_current_flow emits the canonical proposal envelope — surface it as a
        // card with a diff against the live canvas instead of the raw JSON preview.
        const proposal = parseFlowProposal(next.output)
        if (proposal) {
          next.proposal = proposal
          next.proposalDiff = diffFlowProposal(props.context.graph, proposal.graph)
        }
      }
      if (index >= 0) tools[index] = next
      else tools.push(next)
      return { ...last, tools }
    })
  }

  const resolveApproval = async (activity: ToolActivity, approved: boolean) => {
    if (!activity.approvalId || activity.resolved) return
    setTurns((current) => current.map((turn) => ({
      ...turn,
      tools: turn.tools?.map((item) => item.id === activity.id
        ? { ...item, resolved: true }
        : item),
    })))
    try {
      await services.chat.resolveToolApproval(activity.approvalId, approved)
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
    }
  }

  const applyProposal = async (activity: ToolActivity) => {
    if (!activity.proposal || activity.proposalState === 'applying') return
    patchActivity(activity.id, { proposalState: 'applying' })
    let applied = false
    try {
      applied = props.applyProposal ? await props.applyProposal(activity.proposal) : false
    } catch {
      applied = false
    }
    patchActivity(activity.id, { proposalState: applied ? 'applied' : 'failed' })
  }

  const patchActivity = (activityId: string, patch: Partial<ToolActivity>) => {
    setTurns((current) => current.map((turn) => ({
      ...turn,
      tools: turn.tools?.map((item) => (item.id === activityId ? { ...item, ...patch } : item)),
    })))
  }

  const stop = () => {
    if (streamIdRef.current) void services.chat.cancelGeneration(streamIdRef.current)
    streamRef.current?.close()
    streamRef.current = null
    streamIdRef.current = null
    setBusy(false)
  }

  // Closing the dock mid-generation cancels the backend generation too.
  useEffect(() => () => {
    if (streamRef.current) {
      if (streamIdRef.current) void services.chat.cancelGeneration(streamIdRef.current)
      streamRef.current.close()
    }
  }, [])

  const send = async () => {
    const text = input.trim()
    if (!text || busy) return
    // Busy flips BEFORE the (network-bound) prepare step: a second Enter during
    // that window must not start a second stream over the same turn list.
    setBusy(true)
    try {
      if (props.prepare && !await props.prepare()) {
        setBusy(false)
        return
      }
      setInput('')
      setErrorMsg(null)
      setTurns((current) => [
        ...current,
        { role: 'user', content: text },
        { role: 'assistant', content: '', tools: [] },
      ])
      scrollToBottom()
      const start = await services.chat.send(
        toHistory(), [], permissionMode, props.workflowId ?? undefined, props.context)
      streamIdRef.current = start.streamId
      streamRef.current = services.chat.openChatStream(start.streamId, {
        onToken: (token) => {
          patchLastAssistant((turn) => ({ ...turn, content: turn.content + token }))
          scrollToBottom()
        },
        onTool: (payload) => {
          applyToolEvent(payload)
          scrollToBottom()
        },
        onDone: () => {
          setBusy(false)
          streamRef.current = null
          streamIdRef.current = null
        },
        onError: (err) => {
          setErrorMsg(chatStreamErrorText(err))
          setBusy(false)
          streamRef.current = null
          streamIdRef.current = null
        },
      })
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : t('agent.failed'))
      setBusy(false)
    }
  }

  const clearConversation = () => {
    if (busy) stop()
    setTurns([])
    setErrorMsg(null)
  }

  const canSend = !busy && input.trim().length > 0

  return (
    <div className="flow-chat">
      <div className="flow-chat__head">
        <span className="flow-chat__title">
          {t('flows.chatTitle')}
          <small>{props.workflowTitle}</small>
        </span>
        <span className="cx-row">
          {turns.length > 0 && (
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('flows.chatClear')}
              disabled={busy}
              onClick={clearConversation}
            >
              <X size={15} />
            </button>
          )}
          <button className="cx-iconbtn cx-iconbtn--sm" aria-label={t('flows.close')} onClick={props.onClose}>
            <X size={16} />
          </button>
        </span>
      </div>

      <div className="flow-chat__hint flow-chat__hint--intro">
        {props.workflowId ? t('flows.chatIntro') : t('flows.chatCreateIntro')}
      </div>

      <div ref={listRef} className="flow-chat__list">
        {turns.map((turn, index) => (
          <div key={index} className={`flow-chat__turn flow-chat__turn--${turn.role}`}>
            {turn.role === 'user'
              ? <div className="flow-chat__bubble flow-chat__bubble--user">{turn.content}</div>
              : (
                <div className="flow-chat__bubble flow-chat__bubble--assistant">
                  {turn.tools?.map((activity) => (
                    <div key={activity.id} className="flow-chat__activity">
                      <div className={`flow-chat__tool${activity.phase === 'result' && activity.success === false ? ' flow-chat__tool--failed' : ''}`}>
                        <span className="flow-chat__tool-name">{activity.name}</span>
                        {activity.phase !== 'result' && activity.phase !== 'approval_required'
                          ? <span className="cx-spin flow-chat__tool-spinner" />
                          : null}
                        {activity.phase === 'result' && activity.output && !activity.proposal && (
                          <span className="flow-chat__tool-output" title={activity.output}>
                            {activity.output.slice(0, 140)}
                          </span>
                        )}
                        {activity.phase === 'approval_required' && !activity.resolved && activity.approvalId
                          ? (
                            <>
                              <button className="cx-btn cx-btn--primary" onClick={() => void resolveApproval(activity, true)}>
                                {t('flows.chatApprove')}
                              </button>
                              <button className="cx-btn cx-btn--outline" onClick={() => void resolveApproval(activity, false)}>
                                {t('flows.chatDeny')}
                              </button>
                            </>
                          )
                          : activity.phase === 'approval_required' && activity.resolved
                            ? <span className="flow-chat__tool-status">{t('flows.chatApprovalHandled')}</span>
                            : null}
                      </div>
                      {activity.proposal && (
                        <div className="flow-chat__proposal">
                          <strong>{activity.proposal.summary}</strong>
                          {activity.proposalDiff && (
                            <span className="flow-chat__proposal-diff">
                              {t('flows.chatProposalDiff', {
                                addedNodes: activity.proposalDiff.addedNodes,
                                removedNodes: activity.proposalDiff.removedNodes,
                                changedNodes: activity.proposalDiff.changedNodes,
                                addedEdges: activity.proposalDiff.addedEdges,
                                removedEdges: activity.proposalDiff.removedEdges,
                              })}
                            </span>
                          )}
                          {!!activity.proposal.diagnostics?.length && (
                            <span className="flow-chat__proposal-warning">
                              {t('flows.chatProposalIssues', { count: activity.proposal.diagnostics.length })}
                            </span>
                          )}
                          {activity.proposal.applicable === false && (
                            <span className="flow-chat__proposal-warning">{t('flows.chatProposalBlocked')}</span>
                          )}
                          <div className="flow-chat__proposal-actions">
                            {!activity.proposalState ? (
                              <>
                                <button
                                  className="cx-btn cx-btn--primary"
                                  disabled={activity.proposal.applicable === false}
                                  title={activity.proposal.applicable === false ? t('flows.chatProposalBlocked') : undefined}
                                  onClick={() => void applyProposal(activity)}
                                >
                                  {t('flows.chatProposalApply')}
                                </button>
                                <button
                                  className="cx-btn cx-btn--outline"
                                  onClick={() => patchActivity(activity.id, { proposalState: 'dismissed' })}
                                >
                                  {t('flows.chatProposalDismiss')}
                                </button>
                              </>
                            ) : activity.proposalState === 'applying' ? (
                              <span><span className="cx-spin" /> {t('flows.chatProposalApplying')}</span>
                            ) : activity.proposalState === 'applied' ? (
                              <span className="flow-chat__proposal-ok"><Check size={13} /> {t('flows.chatProposalApplied')}</span>
                            ) : activity.proposalState === 'failed' ? (
                              <span className="flow-chat__proposal-warning">{t('flows.chatProposalFailed')}</span>
                            ) : (
                              <span>{t('flows.chatProposalDismissed')}</span>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                  {turn.content
                    ? <div className="flow-chat__text">{turn.content}</div>
                    : busy && index === turns.length - 1
                      ? <div className="flow-chat__typing"><span className="cx-spin" /> {t('flows.chatThinking')}</div>
                      : null}
                </div>
              )}
          </div>
        ))}
      </div>

      {errorMsg && (
        <div className="cx-alert cx-alert--error flow-chat__error">
          <span className="cx-alert__body">{errorMsg}</span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setErrorMsg(null)}>
            <X size={15} />
          </button>
        </div>
      )}

      <div className="flow-chat__composer">
        <select
          className="cx-select"
          value={permissionMode}
          disabled={busy}
          onChange={(event) => setPermissionMode(event.target.value as AiPermissionMode)}
        >
          <option value="ask-for-approval">{t('aichat.permissionAsk')}</option>
          <option value="approve-for-me">{t('aichat.permissionAuto')}</option>
          <option value="full-access">{t('aichat.permissionFullAccess')}</option>
        </select>
        <textarea
          ref={inputRef}
          rows={1}
          className="flow-chat__input"
          aria-label={t('flows.chatPlaceholder')}
          placeholder={t('flows.chatPlaceholder')}
          value={input}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
            if (busy) return
            event.preventDefault()
            void send()
          }}
        />
        {busy
          ? <button className="cx-iconbtn cx-iconbtn--sm" title={t('common.cancel')} onClick={stop}><Square size={14} /></button>
          : (
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              disabled={!canSend}
              title={t('flows.chatPlaceholder')}
              onClick={() => void send()}
            >
              <Send size={14} />
            </button>
          )}
      </div>
    </div>
  )
}
