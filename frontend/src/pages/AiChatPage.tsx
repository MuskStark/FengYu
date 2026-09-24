import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Ellipsis, Folder, FolderOpen } from 'lucide-react'
import '@/styles/chat.css'
import ResourceStrip from '@/components/chat/ResourceStrip'
import Transcript from '@/components/chat/Transcript'
import WorkspacePanel from '@/components/chat/WorkspacePanel'
import DraftHome, { DraftPrompts } from '@/components/chat/DraftHome'
import { getPlatform } from '@/platform'
import { cn } from '@/lib/utils'
import { useAiSessionStore } from '@/stores/aiSession'
import ChatComposer from '@/components/chat/ChatComposer'

/**
 * AI chat view (React twin of AiChat.vue): layout shell, top bar, the workspace side
 * panel, the read-only transcript, and the bottom zone (context chips strip + the
 * composer slot — ReactLexicalComposer lands in the parallel batch and replaces the
 * slot div). Workspace attach has both a desktop path (native directory picker) and
 * a browser path (typed absolute path dialog — the backend runs on this machine).
 */
export default function AiChatPage() {
  const { t } = useTranslation()
  // Array-based subscription — actions mutate conversation rows in place and only
  // replace the array, so a find()-based selector would never re-render this page.
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  const clear = useAiSessionStore(state => state.clear)
  const setWorkspace = useAiSessionStore(state => state.setWorkspace)

  const turns = activeConv?.turns ?? []
  const empty = turns.length === 0

  /** Workspace browsing needs both a root and a persisted conversation id to query. */
  const workspaceBinding = activeConv?.workspaceRoot && activeConv.backendId != null
    ? { conversationId: activeConv.backendId, root: activeConv.workspaceRoot }
    : null

  const [panelOpen, setPanelOpen] = useState(false)
  /** Pending open-file request for the panel; seq re-fires repeated requests for one path. */
  const [workspaceFocus, setWorkspaceFocus] = useState<{ path: string; seq: number } | null>(null)
  const focusSeq = useRef(0)
  /** Browser-only workspace attach dialog (desktop uses the native directory picker). */
  const [workspaceDialogOpen, setWorkspaceDialogOpen] = useState(false)
  const [workspacePathInput, setWorkspacePathInput] = useState('')
  /** Header "…" menu + its rename dialog (ZCode WorkspaceHeader task-menu surface). */
  const [taskMenuOpen, setTaskMenuOpen] = useState(false)
  const taskMenuRef = useRef<HTMLDivElement | null>(null)
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameInput, setRenameInput] = useState('')
  const renameConversation = useAiSessionStore(state => state.renameConversation)

  useEffect(() => {
    // Detaching (or switching to a conversation without) a workspace closes the panel.
    if (!workspaceBinding) setPanelOpen(false)
  }, [workspaceBinding])

  // Header "…" menu closes on outside pointer-down and Escape (ZCode dropdown behavior).
  useEffect(() => {
    if (!taskMenuOpen) return
    const onPointerDown = (event: PointerEvent) => {
      if (!taskMenuRef.current?.contains(event.target as Node)) setTaskMenuOpen(false)
    }
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setTaskMenuOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeydown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeydown)
    }
  }, [taskMenuOpen])

  function openWorkspaceFile(path: string): void {
    setWorkspaceFocus({ path, seq: ++focusSeq.current })
    setPanelOpen(true)
  }

  /** The broom deletes the whole conversation — irreversible, so it confirms first. */
  async function clearConversation(): Promise<void> {
    setTaskMenuOpen(false)
    if (!await getPlatform().confirm(t('aichat.clearConfirm'), { danger: true })) return
    await clear()
  }

  /** Attach to the conversation that originated the gesture (a switch mid-picker must not redirect). */
  async function applyWorkspace(conversationId: number, path: string): Promise<void> {
    const live = useAiSessionStore.getState().conversations.find(item => item.id === conversationId)
    if (!live) return // deleted while the picker/dialog was open — drop the request
    try {
      await setWorkspace(live, path)
    } catch (error) {
      const message = error instanceof Error && error.message ? error.message : t('aichat.workspaceSetFailed')
      useAiSessionStore.setState({ error: message })
    }
  }

  /** Shared attach gesture (hero button): desktop picks natively, browser opens the dialog. */
  async function attachWorkspace(): Promise<void> {
    const conversation = useAiSessionStore.getState().ensureConversation()
    const platform = getPlatform()
    const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (desktop) {
      const path = await desktop.pickDirectory()
      if (!path) return
      await applyWorkspace(conversation.id, path)
      return
    }
    setWorkspacePathInput(conversation.workspaceRoot ?? '')
    setWorkspaceDialogOpen(true)
  }

  async function confirmWorkspacePath(): Promise<void> {
    const path = workspacePathInput.trim()
    if (!path) return
    setWorkspaceDialogOpen(false)
    const conversation = useAiSessionStore.getState().active()
    if (conversation) await applyWorkspace(conversation.id, path)
  }

  function startRename(): void {
    setTaskMenuOpen(false)
    setRenameInput(useAiSessionStore.getState().active()?.title ?? '')
    setRenameOpen(true)
  }

  async function confirmRename(): Promise<void> {
    const title = renameInput.trim()
    const conversation = useAiSessionStore.getState().active()
    if (!title || !conversation) return
    setRenameOpen(false)
    try {
      await renameConversation(conversation, title)
    } catch (error) {
      const message = error instanceof Error && error.message ? error.message : t('aichat.renameFailed')
      useAiSessionStore.setState({ error: message })
    }
  }

  async function copyWorkspacePath(): Promise<void> {
    setTaskMenuOpen(false)
    const root = useAiSessionStore.getState().active()?.workspaceRoot
    if (root) await navigator.clipboard.writeText(root)
  }

  /** Browser typed-path dialog — rendered on the draft screen too (the pill lives there). */
  const workspaceDialog = workspaceDialogOpen && (
    <div className="cx-conversation">
      <div className="cx-card chat-workspace-dialog">
        <div className="chat-workspace-dialog__title">{t('aichat.workspacePathPrompt')}</div>
        <input
          className="chat-workspace-dialog__input"
          value={workspacePathInput}
          placeholder={t('aichat.workspacePathPlaceholder')}
          autoFocus
          onChange={event => setWorkspacePathInput(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter') void confirmWorkspacePath()
          }}
        />
        <div className="chat-workspace-dialog__actions">
          <button
            className="cx-btn cx-btn--text cx-btn--sm"
            onClick={() => setWorkspaceDialogOpen(false)}
          >
            {t('common.cancel')}
          </button>
          <button
            className="cx-btn cx-btn--primary cx-btn--sm"
            disabled={!workspacePathInput.trim()}
            onClick={() => void confirmWorkspacePath()}
          >
            {t('aichat.workspaceAttach')}
          </button>
        </div>
      </div>
    </div>
  )

  /** Rename dialog (ZCode TaskRenameDialog mapping: dialog + name input + cancel/confirm). */
  const renameDialog = renameOpen && (
    <div className="cx-conversation">
      <div className="cx-card chat-workspace-dialog">
        <div className="chat-workspace-dialog__title">{t('aichat.renameConversation')}</div>
        <input
          className="chat-workspace-dialog__input"
          value={renameInput}
          placeholder={t('aichat.renamePlaceholder')}
          autoFocus
          onChange={event => setRenameInput(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter') void confirmRename()
          }}
        />
        <div className="chat-workspace-dialog__actions">
          <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => setRenameOpen(false)}>
            {t('common.cancel')}
          </button>
          <button
            className="cx-btn cx-btn--primary cx-btn--sm"
            disabled={!renameInput.trim()}
            onClick={() => void confirmRename()}
          >
            {t('common.confirm')}
          </button>
        </div>
      </div>
    </div>
  )

  return (
    <div className={cn('chat-view', empty && 'chat-draft-view')}>
      {/* Draft (ZCode ConversationDraftEmptyState): no header — greeting + centered composer.
          The workspace attach affordance is the pill in the composer's context header. */}
      {empty ? (
        <div className="chat-draft">
          <DraftHome />
          <div className="chat-draft__composer">
            {workspaceDialog}
            <ResourceStrip
              hideWorkspace
              onChooseWorkspace={() => {
                setWorkspacePathInput(useAiSessionStore.getState().active()?.workspaceRoot ?? '')
                setWorkspaceDialogOpen(true)
              }}
            />
            <ChatComposer centered onAttachWorkspace={() => void attachWorkspace()} />
            <DraftPrompts />
          </div>
        </div>
      ) : (
        <>
          {/* WorkspaceHeader port (ZCode WorkspaceHeader.tsx): 48px bar, hairline bottom
              border — folder button (workspace context: attach/change), h1 title, "…" task
              menu on the left; the side-pane toggle stays on the right. Draft renders none. */}
          <header className="chat-header">
            <div className="chat-header__left">
              <button
                className="cx-iconbtn cx-iconbtn--sm"
                title={workspaceBinding ? t('aichat.changeWorkspace') : t('aichat.setWorkspace')}
                onClick={() => void attachWorkspace()}
              >
                <Folder size={16} />
              </button>
              <h1 className="chat-header__title" title={activeConv?.title || t('sidebar.untitled')}>
                <span>{activeConv?.title || t('sidebar.untitled')}</span>
              </h1>
              <div className="chat-header__menu-anchor" ref={taskMenuRef}>
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  aria-label={t('common.more')}
                  title={t('common.more')}
                  onClick={() => setTaskMenuOpen(open => !open)}
                >
                  <Ellipsis size={16} />
                </button>
                {taskMenuOpen && (
                  <div className="cx-card chat-header-menu">
                    <button className="chat-header-menu__item" onClick={startRename}>
                      {t('aichat.renameConversation')}
                    </button>
                    {activeConv?.workspaceRoot && (
                      <button className="chat-header-menu__item" onClick={() => void copyWorkspacePath()}>
                        {t('aichat.copyWorkspacePath')}
                      </button>
                    )}
                    <div className="chat-header-menu__sep" />
                    <button className="chat-header-menu__item" onClick={() => void clearConversation()}>
                      {t('aichat.clear')}
                    </button>
                  </div>
                )}
              </div>
            </div>
            <div className="chat-header__right">
              {workspaceBinding && (
                <button
                  className={cn('cx-iconbtn cx-iconbtn--sm', panelOpen && 'chat-header__toggle--active')}
                  title={t('aichat.workspacePanel')}
                  onClick={() => setPanelOpen(open => !open)}
                >
                  <FolderOpen size={16} />
                </button>
              )}
            </div>
          </header>

          {/* Transcript + optional workspace side panel */}
          <div className="chat-main">
            <Transcript onOpenWorkspaceFile={openWorkspaceFile} />
            {panelOpen && workspaceBinding && (
              <WorkspacePanel
                conversationId={workspaceBinding.conversationId}
                root={workspaceBinding.root}
                focus={workspaceFocus}
                onClose={() => setPanelOpen(false)}
              />
            )}
          </div>

          {/* Bottom zone: context chips strip + the composer */}
          <div className="chat-bottom">
            {renameDialog}
            {workspaceDialog}
            <ResourceStrip
              onChooseWorkspace={() => {
                setWorkspacePathInput(useAiSessionStore.getState().active()?.workspaceRoot ?? '')
                setWorkspaceDialogOpen(true)
              }}
            />
            <ChatComposer onAttachWorkspace={() => void attachWorkspace()} />
          </div>
        </>
      )}
    </div>
  )
}
