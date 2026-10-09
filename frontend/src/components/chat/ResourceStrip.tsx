import { useTranslation } from 'react-i18next'
import { File as FileIcon, Folder, Save } from 'lucide-react'
import { useAiSessionStore } from '@/stores/aiSession'
import '@/styles/chat.css'

function folderBasename(path: string): string {
  return path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? path
}

/**
 * Read-only context chips above the composer: committed resources, output target,
 * and the attaching spinner. The coding workspace and its branch selector moved
 * INTO the composer card (ZCode pattern — see ChatComposer's context pill row);
 * draft attachments also live inside the composer card.
 */
export default function ResourceStrip() {
  const { t } = useTranslation()
  // Subscribe to the conversations ARRAY, not the found row: store actions mutate a
  // conversation in place and only replace the array, so a find()-based selector would
  // return a stable reference and zustand would never re-render the chips (the
  // "attach a file and nothing appears" bug).
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  if (!activeConv) return null

  const hasChips = activeConv.resources.length > 0
    || activeConv.outputTarget !== null
    || activeConv.attaching > 0
  if (!hasChips) return null

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
      </div>
    </div>
  )
}
