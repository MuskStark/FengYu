import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Check, Copy, ExternalLink, File as FileIcon, FileCheck2, Folder,
  Pencil, RefreshCw, Search, X,
} from 'lucide-react'
import { services } from '@/services'
import {
  getPlatform,
} from '@/platform'
import type { ChatArtifact } from '@/services/types'
import type { ToolConfirmation } from '@/lib/aiConfirmation'
import { cn } from '@/lib/utils'
import { useAiSessionStore, type ChatTurn, type Conversation } from '@/stores/aiSession'
import '@/styles/chat.css'
import Markdown from './Markdown'
import ToolCard from './ToolCard'
import ThinkingBlock from './ThinkingBlock'
import ImageAttachmentCard from './ImageAttachmentCard'

/**
 * Read-only half of the AI chat view: the scroll region with the empty hero and the turn
 * timeline. The timeline renders a WINDOW of the most recent turns (long coding sessions
 * stay responsive; "show earlier" extends it), tool calls render as expandable cards
 * (ToolCard), thinking as the shimmer ThinkingBlock, and settled turns carry copy /
 * regenerate / edit actions. Scrolling follows the stream only while the user sits near
 * the bottom (stick-to-bottom). Conversation mutations go through the session store.
 */

/** Initial + incremental window sizes for the turn timeline. */
const INITIAL_WINDOW = 60
const WINDOW_STEP = 60

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

/** Republish the conversations array so in-place object edits re-render (store pattern). */
function publishConversations(): void {
  useAiSessionStore.setState(state => ({ conversations: [...state.conversations] }))
}

/** Patch one artifact row (by turn + artifact id) on the live conversation objects. */
function patchArtifact(turnId: number, artifactId: string, patch: Partial<ChatArtifact>): void {
  for (const conversation of useAiSessionStore.getState().conversations) {
    for (const turn of conversation.turns) {
      if (turn.id !== turnId) continue
      const artifact = turn.artifacts.find(item => item.artifactId === artifactId)
      if (artifact) Object.assign(artifact, patch)
    }
  }
  publishConversations()
}

export default function Transcript({ onOpenWorkspaceFile }: {
  /** Open a workspace file in the side panel (the activity row's open-file gesture). */
  onOpenWorkspaceFile: (path: string) => void
}) {
  const { t } = useTranslation()
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  // Primitive selector over the replaced array: recomputes on every store change but
  // only re-renders when the ACTIVE conversation's streaming flag actually flips.
  const busy = useAiSessionStore(
    state => state.conversations.find(conversation => conversation.id === state.activeId)?.streaming === true)
  const regenerate = useAiSessionStore(state => state.regenerate)
  const editFromTurn = useAiSessionStore(state => state.editFromTurn)
  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  const turns = activeConv?.turns ?? []

  const scrollerRef = useRef<HTMLDivElement | null>(null)
  /** Turn id whose copy action flashed "Copied" (cleared after 1.5s). */
  const [copiedId, setCopiedId] = useState<number | null>(null)
  /** Artifact ids with a save/download action in flight (per-card spinner state). */
  const [busyArtifactIds, setBusyArtifactIds] = useState<Set<string>>(new Set())
  /** Windowing: how many of the most recent turns render. */
  const [visibleCount, setVisibleCount] = useState(INITIAL_WINDOW)
  /** In-conversation find (Cmd/Ctrl+F): query, matches, current index. */
  const [findOpen, setFindOpen] = useState(false)
  const [findQuery, setFindQuery] = useState('')
  const [findIndex, setFindIndex] = useState(0)
  const turnRefs = useRef(new Map<number, HTMLElement>())

  const platform = getPlatform()
  const artifactActionsAvailable = platform.capabilities.revealArtifacts
  const revealLabel = platform.os === 'darwin'
    ? t('aichat.revealInFinder')
    : t('aichat.revealInFolder')

  const visibleTurns = useMemo(() =>
    turns.length > visibleCount ? turns.slice(turns.length - visibleCount) : turns,
    [turns, visibleCount])
  const hiddenCount = Math.max(0, turns.length - visibleTurns.length)

  // Reset the window and close find when switching conversations.
  useEffect(() => {
    setVisibleCount(INITIAL_WINDOW)
    setFindOpen(false)
    setFindQuery('')
  }, [activeId])

  async function copyMessage(turn: ChatTurn): Promise<void> {
    try {
      await navigator.clipboard.writeText(turn.content)
    } catch {
      /* clipboard unavailable in this context — still flash for consistency */
    }
    setCopiedId(turn.id)
    window.setTimeout(() => {
      setCopiedId(current => (current === turn.id ? null : current))
    }, 1500)
  }

  // ── in-conversation find ────────────────────────────────────────────────────

  const findMatches = useMemo(() => {
    if (!findQuery.trim()) return [] as Array<{ turnId: number }>
    const needle = findQuery.toLowerCase()
    return turns
      .filter(turn => turn.content.toLowerCase().includes(needle)
        || turn.thinking.toLowerCase().includes(needle)
        || turn.activities.some(activity => activity.label.toLowerCase().includes(needle)))
      .map(turn => ({ turnId: turn.id }))
  }, [turns, findQuery])

  function jumpToMatch(index: number): void {
    if (findMatches.length === 0) return
    const bounded = ((index % findMatches.length) + findMatches.length) % findMatches.length
    setFindIndex(bounded)
    const match = findMatches[bounded]
    // A match in the windowed-out prefix must be rendered before it can scroll into view.
    const matchTurnIndex = turns.findIndex(turn => turn.id === match.turnId)
    const needed = turns.length - matchTurnIndex
    if (matchTurnIndex >= 0 && needed > visibleCount) {
      setVisibleCount(needed)
    }
    // Two rAFs: the first lands after the enlarged window commits, the second after the
    // browser lays the new turns out — scrollIntoView then sees the real geometry (a
    // plain setTimeout(0) can fire before a concurrent render commits the window).
    requestAnimationFrame(() => requestAnimationFrame(() => {
      turnRefs.current.get(match.turnId)
        ?.scrollIntoView({ block: 'start', behavior: 'smooth' })
    }))
  }

  useEffect(() => {
    const onKeydown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'f') {
        const inEditor = (event.target as HTMLElement | null)
          ?.closest('.composer-editor, input, textarea') != null
        if (inEditor) return // let the composer/editor own its own find/caret behavior
        event.preventDefault()
        setFindOpen(true)
      }
    }
    document.addEventListener('keydown', onKeydown)
    return () => document.removeEventListener('keydown', onKeydown)
  }, [])

  // ── scroll: follow the live conversation only while the user is near the bottom ──

  /** Content + thinking + confirmation/tool states — the same signature the Vue
   * watch used, derived from COUNTERS and string LENGTHS instead of concatenating
   * the turn bodies: building the old signature allocated O(total chars) per
   * render, this is O(turns) with O(1) reads and changes on the same edits
   * (append-only content, status flips, activity additions/output growth). */
  const scrollSignature = turns
    .map(turn => [
      turn.id,
      turn.content.length,
      turn.thinking.length,
      turn.streaming ? 1 : 0,
      turn.confirmations.map(item => `${(item as ToolConfirmation).confirmationId}:${(item as ToolConfirmation).status}`).join(','),
      turn.activities.map(item => `${item.id}:${item.status}:${item.output.length}`).join(','),
      turn.questions?.map(item => `${(item as { questionId: string }).questionId}:${(item as { status: string }).status}`).join(','),
      turn.artifacts.length,
    ].join(':'))
    .join('|')

  const stickToBottom = useRef(true)
  useEffect(() => {
    const el = scrollerRef.current
    if (!el) return
    if (stickToBottom.current) el.scrollTop = el.scrollHeight
  }, [scrollSignature, activeId, visibleCount])

  function onScroll(): void {
    const el = scrollerRef.current
    if (!el) return
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  // ── artifacts: the host-side save closure (store keeps no save action yet) ────────

  function markBusy(artifactId: string, busy: boolean): void {
    setBusyArtifactIds(current => {
      const next = new Set(current)
      if (busy) next.add(artifactId)
      else next.delete(artifactId)
      return next
    })
  }

  function scopeOfActiveConversation(): Conversation | null {
    const conversation = useAiSessionStore.getState().active()
    return conversation?.scopeId ? conversation : null
  }

  function surfaceError(error: unknown, fallbackKey: string): void {
    useAiSessionStore.setState({ error: errorMessage(error, t(fallbackKey)) })
  }

  /** Save into the conversation's registered output target (also the retry path). */
  async function saveArtifactToTarget(turnId: number, artifact: ChatArtifact): Promise<void> {
    const conversation = scopeOfActiveConversation()
    const target = conversation?.outputTarget
    if (!conversation?.scopeId || !target) return
    markBusy(artifact.artifactId, true)
    patchArtifact(turnId, artifact.artifactId, { state: 'saving', error: undefined })
    try {
      const saved = await services.chat.saveChatArtifact(conversation.scopeId, artifact.artifactId, target)
      patchArtifact(turnId, artifact.artifactId, saved)
    } catch (error) {
      patchArtifact(turnId, artifact.artifactId, {
        state: 'save-failed', error: errorMessage(error, t('aichat.artifactSaveFailed')),
      })
    } finally {
      markBusy(artifact.artifactId, false)
    }
  }

  /** Pick a location with a user gesture, register it as the target, then save. */
  async function saveArtifactToChosenLocation(turnId: number, artifact: ChatArtifact): Promise<void> {
    const conversation = useAiSessionStore.getState().active()
    if (!conversation) return
    const platform = getPlatform()
    const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (!desktop) return
    const path = await desktop.pickDirectory()
    if (!path) return // cancelling the picker never destroys the result
    markBusy(artifact.artifactId, true)
    try {
      if (!conversation.scopeId) throw new Error(t('aichat.artifactSaveFailed'))
      const outputTarget = await services.chat.setChatOutputTarget(conversation.scopeId, path)
      conversation.outputTarget = outputTarget
      publishConversations()
      patchArtifact(turnId, artifact.artifactId, { state: 'saving', error: undefined })
      const saved = await services.chat.saveChatArtifact(conversation.scopeId, artifact.artifactId, path)
      patchArtifact(turnId, artifact.artifactId, saved)
    } catch (error) {
      patchArtifact(turnId, artifact.artifactId, {
        state: 'save-failed', error: errorMessage(error, t('aichat.artifactSaveFailed')),
      })
    } finally {
      markBusy(artifact.artifactId, false)
    }
  }

  /** The browser's save path: download the retained pending copy. */
  async function downloadArtifact(artifact: ChatArtifact): Promise<void> {
    markBusy(artifact.artifactId, true)
    try {
      await services.chat.downloadChatArtifact(artifact.artifactId)
    } catch (error) {
      surfaceError(error, 'aichat.artifactSaveFailed')
    } finally {
      markBusy(artifact.artifactId, false)
    }
  }

  async function openSavedArtifact(artifact: ChatArtifact): Promise<void> {
    try {
      await platform.openArtifact(artifact.artifactId)
    } catch (error) {
      surfaceError(error, 'aichat.artifactOpenFailed')
    }
  }

  async function revealSavedArtifact(artifact: ChatArtifact): Promise<void> {
    try {
      await platform.revealArtifact(artifact.artifactId)
    } catch (error) {
      surfaceError(error, 'aichat.artifactOpenFailed')
    }
  }

  function artifactStateLabel(artifact: ChatArtifact): string {
    switch (artifact.state) {
      case 'saved': return t('aichat.artifactSaved')
      case 'saving': return t('aichat.artifactSaving')
      case 'save-failed': return t('aichat.artifactSaveFailedShort')
      default: return t('aichat.artifactPending')
    }
  }

  const lastAssistantTurn = [...turns].reverse().find(turn => turn.role === 'assistant')

  return (
    <div ref={scrollerRef} className="chat-scroller" onScroll={onScroll}>
      {findOpen && (
        <div className="chat-find cx-card">
          <Search size={14} />
          <input
            className="chat-find__input"
            value={findQuery}
            placeholder={t('aichat.findPlaceholder')}
            autoFocus
            onChange={event => { setFindQuery(event.target.value); setFindIndex(0) }}
            onKeyDown={event => {
              if (event.key === 'Enter') jumpToMatch(event.shiftKey ? findIndex - 1 : findIndex + 1)
              if (event.key === 'Escape') setFindOpen(false)
            }}
          />
          <span className="cx-muted chat-find__count">
            {findQuery.trim() ? `${findMatches.length === 0 ? 0 : findIndex + 1}/${findMatches.length}` : ''}
          </span>
          <button className="cx-iconbtn cx-iconbtn--sm" onClick={() => setFindOpen(false)} aria-label={t('common.close')}>
            <X size={13} />
          </button>
        </div>
      )}
      {turns.length === 0 ? (
        <div className="cx-conversation chat-empty" />
      ) : (
        <div className="cx-conversation chat-transcript">
          {hiddenCount > 0 && (
            <button className="cx-btn cx-btn--text cx-btn--sm chat-earlier" onClick={() => setVisibleCount(count => count + WINDOW_STEP)}>
              {t('aichat.showEarlier', { count: hiddenCount })}
            </button>
          )}
          {visibleTurns.map(turn => {
            const isFindTarget = findQuery.trim() !== ''
              && findMatches[findIndex]?.turnId === turn.id
            return (
              <div
                key={turn.id}
                ref={element => {
                  if (element) turnRefs.current.set(turn.id, element)
                  else turnRefs.current.delete(turn.id)
                }}
                className={cn('cx-msg', turn.role === 'user' && 'cx-msg--user',
                  isFindTarget && 'cx-msg--find-target')}
              >
                {turn.role === 'user' ? (
                  <>
                    <div className="cx-msg-body">{turn.content}</div>
                    {turn.images && turn.images.length > 0 && (
                      <div className="chat-images">
                        {turn.images.map((image, index) => (
                          <ImageAttachmentCard
                            key={index}
                            name={image.name}
                            mimeType={image.mimeType}
                            base64Data={image.base64Data}
                          />
                        ))}
                      </div>
                    )}
                    {turn.attachments.length > 0 && (
                      <div className="chat-attachments">
                        {turn.attachments.map((item, index) => (
                          <span key={index} className="cx-chip chat-attachment-chip">
                            {item.kind === 'directory' ? <Folder size={13} /> : <FileIcon size={13} />}
                            {item.name}
                          </span>
                        ))}
                      </div>
                    )}
                    <div className="cx-msg-actions">
                      <button className="cx-msg-action" onClick={() => void copyMessage(turn)}>
                        {copiedId === turn.id ? <Check size={15} /> : <Copy size={15} />}
                        {copiedId === turn.id ? t('aichat.copied') : t('aichat.copy')}
                      </button>
                      {!busy && (
                        <button
                          className="cx-msg-action"
                          title={t('aichat.editResend')}
                          onClick={() => activeConv && editFromTurn(activeConv, turn.id)}
                        >
                          <Pencil size={15} />
                          {t('aichat.edit')}
                        </button>
                      )}
                    </div>
                  </>
                ) : (
                  <>
                    <div className="cx-msg-role">{t('aichat.assistant')}</div>

                    {turn.thinking && (
                      <ThinkingBlock text={turn.thinking} streaming={turn.streaming && turn.thinkingActive === true} />
                    )}

                    {turn.activities.length > 0 && (
                      <div className="chat-activities">
                        {turn.activities.map(activity => (
                          <ToolCard
                            key={activity.id}
                            activity={activity}
                            onOpenWorkspaceFile={onOpenWorkspaceFile}
                          />
                        ))}
                      </div>
                    )}

                    {/* Approval prompts live in the composer; the transcript keeps only compact rows. */}
                    <Markdown source={turn.content} onOpenFile={onOpenWorkspaceFile} />

                    {turn.artifacts.length > 0 && (
                      <div className="chat-artifacts">
                        {turn.artifacts.map(artifact => (
                          <div key={artifact.artifactId} className="cx-card chat-artifact">
                            <div className="chat-artifact__head">
                              <FileCheck2 size={16} />
                              <span className="chat-artifact__name">{artifact.name}</span>
                              <span
                                className="cx-muted chat-artifact__state"
                                style={artifact.state === 'save-failed'
                                  ? { color: 'rgb(var(--v-theme-error))' }
                                  : undefined}
                              >
                                {artifactStateLabel(artifact)}
                              </span>
                            </div>
                            {artifact.savedPath && (
                              <div className="cx-muted chat-artifact__path">{artifact.savedPath}</div>
                            )}
                            {!artifact.savedPath && artifact.state === 'save-failed' && artifact.error && (
                              <div className="cx-muted chat-artifact__path">{artifact.error}</div>
                            )}
                            <div className="chat-artifact__actions">
                              {artifact.state === 'saved' && artifactActionsAvailable && (
                                <>
                                  <button
                                    className="cx-btn cx-btn--text cx-btn--sm"
                                    onClick={() => void openSavedArtifact(artifact)}
                                  >
                                    <ExternalLink size={14} />
                                    {t('aichat.openArtifact')}
                                  </button>
                                  <button
                                    className="cx-btn cx-btn--text cx-btn--sm"
                                    onClick={() => void revealSavedArtifact(artifact)}
                                  >
                                    <Folder size={14} />
                                    {revealLabel}
                                  </button>
                                </>
                              )}
                              {(artifact.state === 'ready-to-save' || artifact.state === 'save-failed') && (
                                <>
                                  {activeConv?.outputTarget && (
                                    <button
                                      className="cx-btn cx-btn--primary cx-btn--sm"
                                      disabled={busyArtifactIds.has(artifact.artifactId)}
                                      onClick={() => void saveArtifactToTarget(turn.id, artifact)}
                                    >
                                      {artifact.state === 'save-failed' ? t('aichat.retrySave') : t('aichat.saveToTarget')}
                                    </button>
                                  )}
                                  {platform.capabilities.nativeFileDialogs && (
                                    <button
                                      className="cx-btn cx-btn--text cx-btn--sm"
                                      disabled={busyArtifactIds.has(artifact.artifactId)}
                                      onClick={() => void saveArtifactToChosenLocation(turn.id, artifact)}
                                    >
                                      {t('aichat.chooseSaveLocation')}
                                    </button>
                                  )}
                                  {!platform.capabilities.nativeFileDialogs && (
                                    <button
                                      className="cx-btn cx-btn--text cx-btn--sm"
                                      disabled={busyArtifactIds.has(artifact.artifactId)}
                                      onClick={() => void downloadArtifact(artifact)}
                                    >
                                      {t('aichat.downloadArtifact')}
                                    </button>
                                  )}
                                </>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {turn.streaming && !turn.content && !turn.activities.length && !turn.thinking && (
                      <div className="chat-streaming chat-shimmer">{t('aichat.thinkingLive')}</div>
                    )}
                    {!turn.streaming && turn.content && (
                      <div className="cx-msg-actions">
                        <button className="cx-msg-action" onClick={() => void copyMessage(turn)}>
                          {copiedId === turn.id ? <Check size={15} /> : <Copy size={15} />}
                          {copiedId === turn.id ? t('aichat.copied') : t('aichat.copy')}
                        </button>
                        {!busy && turn.id === lastAssistantTurn?.id && (
                          <button
                            className="cx-msg-action"
                            title={t('aichat.regenerateHint')}
                            onClick={() => void regenerate()}
                          >
                            <RefreshCw size={15} />
                            {t('aichat.regenerate')}
                          </button>
                        )}
                      </div>
                    )}
                  </>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
