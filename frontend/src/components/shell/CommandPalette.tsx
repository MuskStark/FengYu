import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Command } from 'cmdk'
import {
  CalendarClock, Command as CommandIcon, Folder, MessageSquarePlus, Puzzle,
  Search, Settings, Store, Workflow, FileCode,
} from 'lucide-react'
import { useAiSessionStore } from '@/stores/aiSession'
import { services } from '@/services'
import { getPlatform } from '@/platform'
import '@/styles/command-palette.css'

/**
 * Global command palette (cmdk-based): page navigation, new chat,
 * conversation search/switch, and workspace file quick-open for the active conversation.
 * Opens on ⌘K/Ctrl+K (shortcuts registry); Enter runs the highlighted item.
 */
export default function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const conversations = useAiSessionStore(state => state.conversations)
  const activeId = useAiSessionStore(state => state.activeId)
  const newChat = useAiSessionStore(state => state.newChat)
  const [files, setFiles] = useState<Array<{ path: string; name: string }>>([])

  const activeConv = conversations.find(conversation => conversation.id === activeId) ?? null
  const canQuickOpen = activeConv?.workspaceRoot != null && activeConv.backendId != null

  // Workspace file quick-open candidates load once per palette open (bounded server walk).
  useEffect(() => {
    if (!open || !canQuickOpen || activeConv?.backendId == null) return
    let cancelled = false
    void services.workspace.tree(activeConv.backendId)
      .then(tree => {
        if (!cancelled) {
          setFiles(tree.nodes
            .filter(node => !node.dir)
            .slice(0, 500)
            .map(node => ({ path: node.path, name: node.name })))
        }
      })
      .catch(() => { /* quick-open simply stays empty */ })
    return () => { cancelled = true }
  }, [open, canQuickOpen, activeConv?.backendId])

  const navigateItems = useMemo(() => ([
    { id: 'nav-chat', icon: <MessageSquarePlus size={15} />, label: t('palette.newChat'), run: () => { newChat(); navigate('/') } },
    { id: 'nav-flows', icon: <Workflow size={15} />, label: t('flows.title'), run: () => navigate('/flows') },
    { id: 'nav-schedules', icon: <CalendarClock size={15} />, label: t('schedules.title'), run: () => navigate('/schedules') },
    { id: 'nav-tools', icon: <Puzzle size={15} />, label: t('sidebar.all'), run: () => navigate('/tools') },
    { id: 'nav-store', icon: <Store size={15} />, label: t('sidebar.store'), run: () => navigate('/store') },
    { id: 'nav-settings', icon: <Settings size={15} />, label: t('sidebar.settings'), run: () => navigate('/settings') },
  ]), [t, navigate, newChat])

  const conversationItems = useMemo(() => conversations
    .filter(conversation => conversation.backendId != null && conversation.title)
    .slice(0, 30)
    .map(conversation => ({
      id: `conv-${conversation.id}`,
      icon: <MessageSquarePlus size={15} />,
      label: conversation.title,
      hint: conversation.workspaceRoot
        ? (conversation.workspaceRoot.replace(/[\\/]+$/, '').split(/[\\/]/).pop() ?? '')
        : '',
      run: () => {
        void useAiSessionStore.getState().select(conversation.id)
        navigate('/')
      },
    })), [conversations])

  function openWorkspaceFile(path: string): void {
    navigate('/')
    // After navigation: the chat page's listener must be mounted before the event fires.
    window.setTimeout(() => {
      window.dispatchEvent(new CustomEvent('fengyu:open-workspace-file', { detail: { path } }))
    }, 0)
  }

  if (!open) return null
  return (
    <div className="palette-root" onPointerDown={event => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <Command className="palette cx-card" loop>
        <div className="palette__search">
          <Search size={15} />
          <Command.Input autoFocus placeholder={t('palette.placeholder')} />
          <span className="cx-muted palette__kbd">Esc</span>
        </div>
        <Command.List className="palette__list">
          <Command.Empty className="cx-muted palette__empty">{t('palette.empty')}</Command.Empty>

          <Command.Group heading={t('palette.groupActions')} className="palette__group">
            {navigateItems.map(item => (
              <Command.Item
                key={item.id}
                value={`${item.label} ${item.id}`}
                onSelect={item.run}
                className="palette__item"
              >
                {item.icon}
                <span>{item.label}</span>
              </Command.Item>
            ))}
          </Command.Group>

          {conversationItems.length > 0 && (
            <Command.Group heading={t('palette.groupConversations')} className="palette__group">
              {conversationItems.map(item => (
                <Command.Item
                  key={item.id}
                  value={`${item.label} ${item.id}`}
                  onSelect={item.run}
                  className="palette__item"
                >
                  {item.icon}
                  <span className="palette__item-label">{item.label}</span>
                  {item.hint && <span className="cx-muted palette__item-hint"><Folder size={12} /> {item.hint}</span>}
                </Command.Item>
              ))}
            </Command.Group>
          )}

          {files.length > 0 && (
            <Command.Group heading={t('palette.groupFiles')} className="palette__group">
              {files.map(file => (
                <Command.Item
                  key={file.path}
                  value={file.path}
                  onSelect={() => openWorkspaceFile(file.path)}
                  className="palette__item"
                >
                  <FileCode size={15} />
                  <span className="palette__item-label">{file.name}</span>
                  <span className="cx-muted palette__item-hint">{file.path}</span>
                </Command.Item>
              ))}
            </Command.Group>
          )}
        </Command.List>
        <div className="cx-muted palette__footer">
          <CommandIcon size={12} />
          {getPlatform().os === 'darwin' ? '⌘K' : 'Ctrl+K'} · {t('palette.footerHint')}
        </div>
      </Command>
    </div>
  )
}
