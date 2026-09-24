import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Check, Copy, ExternalLink, File as FileIcon, FileCheck2, Folder,
  Loader2, Shield, X,
} from 'lucide-react'
import { services } from '@/services'
import {
  getPlatform,
} from '@/platform'
import type { ChatArtifact } from '@/services/types'
import type { ToolConfirmation } from '@/lib/aiConfirmation'
import { diffLines, type ToolActivity } from '@/lib/toolActivity'
import { cn } from '@/lib/utils'
import { useAiSessionStore, type ChatTurn, type Conversation } from '@/stores/aiSession'
import '@/styles/chat.css'
import Markdown from './Markdown'

/**
 * Read-only half of the AI chat view (React twin of ChatTranscript.vue): the scroll
 * region with the empty hero and the turn timeline (markdown, thinking, tool
 * activities with expandable diffs, artifact cards). Conversation mutations go
 * through the session store; this component owns only view-local state (copy
 * flash, diff expansion, per-artifact busy spinners) and the scroll behavior.
 */

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

function ActivityStatusIcon({ status }: { status: ToolActivity['status'] }) {
  if (status === 'completed') return <Check size={15} />
  if (status === 'failed' || status === 'rejected') return <X size={15} />
  if (status === 'waiting') return <Shield size={15} />
  return <Loader2 size={15} style={{ animation: 'cx-spin 0.7s linear infinite' }} />
}

export default function Transcript({ onOpenWorkspaceFile }: {
  /** Open a workspace file in the side panel (the activity row's open-file gesture). */
  onOpenWorkspaceFile: (path: string) => void
}) {
  const { t } = useTranslation()
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  const turns = activeConv?.turns ?? []

  const scrollerRef = useRef<HTMLDivElement | null>(null)
  /** Turn id whose copy action flashed "Copied" (cleared after 1.5s). */
  const [copiedId, setCopiedId] = useState<number | null>(null)
  /** Activity ids whose unified diff is expanded in the timeline. */
  const [expandedDiffs, setExpandedDiffs] = useState<Set<string>>(new Set())
  /** Artifact ids with a save/download action in flight (per-card spinner state). */
  const [busyArtifactIds, setBusyArtifactIds] = useState<Set<string>>(new Set())

  const platform = getPlatform()
  const artifactActionsAvailable = platform.capabilities.revealArtifacts
  const revealLabel = platform.os === 'darwin'
    ? t('aichat.revealInFinder')
    : t('aichat.revealInFolder')

  function artifactStateLabel(artifact: ChatArtifact): string {
    switch (artifact.state) {
      case 'saved': return t('aichat.artifactSaved')
      case 'saving': return t('aichat.artifactSaving')
      case 'save-failed': return t('aichat.artifactSaveFailedShort')
      default: return t('aichat.artifactPending')
    }
  }

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

  function toggleActivityDiff(id: string): void {
    setExpandedDiffs(current => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
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

  // ── scroll: follow the live conversation's content signature ─────────────────────

  /** Content + thinking + confirmation/tool states — the same signature the Vue watch used. */
  const scrollSignature = turns
    .map(turn => turn.content + turn.thinking
      + turn.confirmations.map(item => {
        const confirmation = item as ToolConfirmation
        return `${confirmation.confirmationId}:${confirmation.status}`
      }).join(',')
      + turn.activities.map(item => `${item.id}:${item.status}`).join(','))
    .join('|')

  useEffect(() => {
    const el = scrollerRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [scrollSignature, activeId])

  return (
    <div ref={scrollerRef} className="chat-scroller">
      {turns.length === 0 ? (
        <div className="cx-conversation chat-empty" />
      ) : (
        <div className="cx-conversation chat-transcript">
          {turns.map(turn => (
            <div key={turn.id} className={cn('cx-msg', turn.role === 'user' && 'cx-msg--user')}>
              {turn.role === 'user' ? (
                <>
                  <div className="cx-msg-body">{turn.content}</div>
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
                  </div>
                </>
              ) : (
                <>
                  <div className="cx-msg-role">{t('aichat.assistant')}</div>

                  {turn.thinking && (
                    <details className="cx-details chat-thinking">
                      <summary>{t('aichat.thinking')}</summary>
                      <div className="cx-details__body cx-muted">
                        <Markdown source={turn.thinking} />
                      </div>
                    </details>
                  )}

                  {turn.activities.length > 0 && (
                    <div className="chat-activities">
                      {turn.activities.map(activity => (
                        <div key={activity.id} className="chat-activity">
                          <div className="cx-muted chat-activity__line">
                            <ActivityStatusIcon status={activity.status} />
                            <span className="chat-activity__label">{activity.label}</span>
                            {activity.detail && <span className="chat-activity__detail">{activity.detail}</span>}
                            {activity.status === 'waiting' && <span>{t('aichat.awaitingApproval')}</span>}
                            {activity.status === 'failed' && <span>{t('aichat.toolFailed')}</span>}
                            {(activity.diff || activity.path) && (
                              <span className="chat-activity__actions">
                                {activity.diff && (
                                  <button
                                    className="cx-btn cx-btn--text cx-btn--sm chat-activity__action"
                                    onClick={() => toggleActivityDiff(activity.id)}
                                  >
                                    {expandedDiffs.has(activity.id) ? t('aichat.diffHide') : t('aichat.diffShow')}
                                  </button>
                                )}
                                {activity.path && (
                                  <button
                                    className="cx-btn cx-btn--text cx-btn--sm chat-activity__action"
                                    onClick={() => onOpenWorkspaceFile(activity.path!)}
                                  >
                                    <ExternalLink size={13} />
                                    {t('aichat.openInWorkspacePanel')}
                                  </button>
                                )}
                              </span>
                            )}
                          </div>
                          {activity.diff && expandedDiffs.has(activity.id) && (
                            <div className="cx-diff">
                              {diffLines(activity.diff).map((line, index) => (
                                <div key={index} className={`cx-diff__${line.kind}`}>{line.text}</div>
                              ))}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Approval prompts live in the composer; the transcript keeps only compact rows. */}
                  <Markdown source={turn.content} />

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

                  {turn.streaming && !turn.content && (
                    <div className="chat-streaming"><span className="cx-spin" /></div>
                  )}
                  {!turn.streaming && turn.content && (
                    <div className="cx-msg-actions">
                      <button className="cx-msg-action" onClick={() => void copyMessage(turn)}>
                        {copiedId === turn.id ? <Check size={15} /> : <Copy size={15} />}
                        {copiedId === turn.id ? t('aichat.copied') : t('aichat.copy')}
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
