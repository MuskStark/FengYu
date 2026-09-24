import { useTranslation } from 'react-i18next'
import { Braces, File as FileIcon, Folder, Pencil, Save, X } from 'lucide-react'
import { getPlatform } from '@/platform'
import { useAiSessionStore, type Conversation } from '@/stores/aiSession'
import '@/styles/chat.css'

function folderBasename(path: string): string {
  return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? path
}

/**
 * Context chips strip above the composer (React twin of ChatComposer.vue's strip):
 * committed resources, output target, coding workspace, and the attaching spinner.
 * Draft attachments live inside the composer card (ZCode topContent). Scope-limited
 * surface — only the workspace chip is interactive (change via the desktop picker /
 * page dialog, clear directly through the store); the other chips are read-only
 * previews of conversation state.
 */
export default function ResourceStrip({ onChooseWorkspace, hideWorkspace = false }: {
  /** Browser fallback for the workspace "change" gesture (desktop picks natively). */
  onChooseWorkspace: () => void
  /** Draft mode: the composer's context pill shows the workspace, so the chip would duplicate it. */
  hideWorkspace?: boolean
}) {
  const { t } = useTranslation()
  // Subscribe to the conversations ARRAY, not the found row: store actions mutate a
  // conversation in place and only replace the array, so a find()-based selector would
  // return a stable reference and zustand would never re-render the chips (the
  // "attach a file and nothing appears" bug).
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  const setWorkspace = useAiSessionStore(state => state.setWorkspace)
  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  if (!activeConv) return null

  // Hoisted function declarations below don't inherit the null guard's narrowing —
  // capture the narrowed conversation with an explicit type for the closures.
  const conversation: Conversation = activeConv

  const hasChips = activeConv.resources.length > 0
    || activeConv.outputTarget !== null
    || (!hideWorkspace && activeConv.workspaceRoot !== null)
    || activeConv.attaching > 0
  if (!hasChips) return null

  async function changeWorkspace(): Promise<void> {
    const platform = getPlatform()
    const desktop = platform.capabilities.nativeFileDialogs ? platform : null
    if (desktop) {
      const path = await desktop.pickDirectory()
      if (!path) return
      try {
        await setWorkspace(conversation, path)
      } catch {
        useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
      }
      return
    }
    onChooseWorkspace()
  }

  async function clearWorkspace(): Promise<void> {
    try {
      await setWorkspace(conversation, null)
    } catch {
      useAiSessionStore.setState({ error: t('aichat.workspaceSetFailed') })
    }
  }

  return (
    <div className="cx-conversation chat-resource-strip">
      <div className="chat-resource-strip__row">
        {activeConv.resources.map(resource => (
          <span
            key={resource.resourceId}
            className="cx-chip chat-chip"
            title={resource.displayPath ?? resource.name}
          >
            {resource.kind === 'directory' ? <Folder size={13} /> : <FileIcon size={13} />}
            {resource.name}
            <span className="cx-muted chat-chip__hint">{t('aichat.resourceReadOnly')}</span>
          </span>
        ))}

        {activeConv.attaching > 0 && (
          <span className="cx-chip chat-chip">
            <span className="cx-spin" />
            {t('aichat.attachInProgress')}
          </span>
        )}

        {activeConv.outputTarget && (
          <span className="cx-chip chat-chip" title={activeConv.outputTarget}>
            <Save size={13} />
            {t('aichat.saveToPrefix')}{folderBasename(activeConv.outputTarget)}
          </span>
        )}

        {!hideWorkspace && activeConv.workspaceRoot && (
          <span className="cx-chip chat-chip" title={activeConv.workspaceRoot}>
            <Braces size={13} />
            {t('aichat.workspacePrefix')}{folderBasename(activeConv.workspaceRoot)}
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('aichat.changeWorkspace')}
              onClick={() => void changeWorkspace()}
            >
              <Pencil size={13} />
            </button>
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('aichat.clearWorkspace')}
              onClick={() => void clearWorkspace()}
            >
              <X size={13} />
            </button>
          </span>
        )}
      </div>
    </div>
  )
}
