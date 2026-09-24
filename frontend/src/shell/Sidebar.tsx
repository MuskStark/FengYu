import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Bell, BellRing, CalendarClock, Check, ChevronRight, ChevronDown, Cog, Folder, FolderOpen, FolderPlus,
  Info, LayoutGrid, ListFilter, Maximize2, MessageCircle, MessageCirclePlus, Minimize2, Plus,
  Spline, Store, X,
} from 'lucide-react'
import { useSettingsStore } from '@/stores/settings'
import { useAiSessionStore } from '@/stores/aiSession'
import { useNotificationsStore } from '@/stores/notifications'
import { useUpdateStore } from '@/stores/update'
import NotificationCenter from './NotificationCenter'
import { groupConversations, formatRelativeTime, sortForView, type SidebarConversation } from '@/lib/sidebarProjects'
import { cn } from '@/lib/utils'
import { getPlatform } from '@/platform'
import { appConfirm } from '@/lib/appDialogs'
import { checkNavigationGuard } from '@/lib/navGuard'
import logoUrl from '@/assets/infinia-logo.svg'

type HistoryView = 'chats' | 'projects'

/**
 * Left rail: brand, primary nav, conversation history with the ZCode-style project grouping
 * (sliding-pill view switch + collapsible project headers + sort menu), and the account menu.
 * Functional port of the Vue Sidebar; visuals ride the cx-* kit. The shell owns the width/
 * collapse model and the resizer — this component only renders the tree; `collapsed` fully
 * retracts it (cx-sidebar.collapsed: width 0 + fade, no icon rail).
 */
export default function Sidebar({ collapsed, width, resizing, macTitleBar }: {
  collapsed: boolean
  width: number
  resizing: boolean
  macTitleBar: boolean
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const settings = useSettingsStore()
  const ai = useAiSessionStore()
  const unreadCount = useNotificationsStore(state => state.unreadCount)
  const updateAvailable = useUpdateStore(state => state.updateAvailable)
  const latestVersion = useUpdateStore(state => state.latestVersion)
  const [notificationOpen, setNotificationOpen] = useState(false)

  const [viewMode, setViewMode] = useState<HistoryView>(readHistoryView())
  const [taskSortBy, setTaskSortBy] = useState<'updated' | 'created'>(readTaskSort())
  const [collapsedProjects, setCollapsedProjects] = useState<Set<string>>(loadCollapsedProjects())
  const [accountMenuOpen, setAccountMenuOpen] = useState(false)
  const [sortMenuOpen, setSortMenuOpen] = useState(false)
  const accountRef = useRef<HTMLDivElement | null>(null)
  const sortRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => { void ai.loadHistory() }, [ai])

  // Relative timestamps ("刚刚"/"3天") stay honest: re-render the labels once a minute.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  const relativeTime = (conversation: SidebarConversation) => {
    const label = formatRelativeTime(conversation.updatedAt ?? conversation.createdAt, now)
    return t(label.key, label.values ? { ...label.values } : undefined)
  }

  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (!accountRef.current?.contains(event.target as Node)) setAccountMenuOpen(false)
      if (!sortRef.current?.contains(event.target as Node)) setSortMenuOpen(false)
    }
    const onEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setAccountMenuOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onEscape)
    }
  }, [])

  const grouping = groupConversations(ai.conversations as SidebarConversation[], taskSortBy)
  const flatConversations = sortForView(ai.conversations as SidebarConversation[], taskSortBy)

  const allProjectsCollapsed =
    grouping.projects.length > 0
    && grouping.projects.every(group => collapsedProjects.has(group.root))

  const projectExpanded = (root: string, hasActive: boolean) =>
    !collapsedProjects.has(root) || hasActive

  const macShortcut = getPlatform().os === 'darwin'
  const newTaskHint = macShortcut ? '⌘N' : 'Ctrl+N'

  const primaryNav = [
    { key: 'chat', to: '/', label: t('sidebar.newChat'), icon: <MessageCircle size={18} />, hint: newTaskHint },
    { key: 'agent', to: '/flows/new', label: t('sidebar.agent'), icon: <Spline size={18} /> },
    { key: 'schedules', to: '/schedules', label: t('schedules.title'), icon: <CalendarClock size={18} /> },
    { key: 'tools', to: '/tools', label: t('sidebar.all'), icon: <LayoutGrid size={18} /> },
    { key: 'store', to: '/store', label: t('sidebar.store'), icon: <Store size={18} /> },
  ]

  /** Runs a nav action only after the unsaved-work guard allows it (in-app dialog). */
  const guarded = (action: () => void) => {
    void (async () => {
      if (await checkNavigationGuard()) action()
    })()
  }

  const openConversation = (id: number) => {
    guarded(() => {
      void ai.select(id)
      if (location.pathname !== '/') navigate('/')
    })
  }

  const removeConversation = async (id: number) => {
    const ok = await appConfirm(t('aichat.deleteConversationConfirm'), { danger: true })
    if (ok) void ai.removeConversation(id)
  }

  return (
    <aside
      className={cn('cx-sidebar', collapsed && 'collapsed', resizing && 'resizing')}
      style={{ '--cx-sidebar-width': `${width}px` } as React.CSSProperties}
    >
      <div className={cn('sidebar-inner', { 'sidebar-inner--mac': macTitleBar })}>
        {/* macOS: the AppShell's 48px .fx-windowbar overlay (traffic lights + toggle) owns
            the strip above this row; the brand sits as its own row right above 新对话. */}

        <div className={cn('sidebar-brand', { 'mac-titlebar-brand': macTitleBar })}>
          <img className="brand-logo" src={logoUrl} alt="" />
          <span className="sidebar-brand-name">{t('brand')}</span>
          {!macTitleBar && (
            <button
              className="cx-iconbtn cx-iconbtn--sm"
              title={t('sidebar.collapse')}
              onClick={() => void settings.setSidebarCollapsed(true)}
            ><Minimize2 size={15} /></button>
          )}
        </div>

        <nav className="sidebar-primary-nav" aria-label={t('sidebar.primaryNavigation')}>
          {primaryNav.map(item => (
            <button
              key={item.key}
              className={cn('cx-nav-item sidebar-nav-button', {
                active: location.pathname === item.to
                  || (item.key === 'agent' && location.pathname.startsWith('/flows/')),
              })}
              onClick={() => item.key === 'chat'
                ? guarded(() => { ai.newChat(); navigate('/') })
                : guarded(() => navigate(item.to))}
            >
              {item.icon}
              <span className="cx-nav-label">{item.label}</span>
              {item.hint && <span className="sidebar-nav-shortcut" aria-hidden="true">{item.hint}</span>}
            </button>
          ))}
        </nav>

        <div className="sidebar-history">
          {ai.conversations.length > 0 && (
            <div className="sidebar-history-toolbar">
              <ViewSwitch value={viewMode} onChange={view => { setViewMode(view); persistHistoryView(view) }} />
              {viewMode === 'projects' && grouping.projects.length > 0 && (
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={allProjectsCollapsed ? t('sidebar.expandAllProjects') : t('sidebar.collapseAllProjects')}
                  onClick={() => {
                    const fold = !allProjectsCollapsed
                    const next = fold ? new Set(grouping.projects.map(group => group.root)) : new Set<string>()
                    setCollapsedProjects(next)
                    persistCollapsedProjects(next)
                  }}
                >
                  {allProjectsCollapsed ? <Maximize2 size={13} /> : <Minimize2 size={13} />}
                </button>
              )}
              <div ref={sortRef} style={{ position: 'relative' }}>
                <button
                  className="cx-iconbtn cx-iconbtn--sm"
                  title={t('sidebar.taskViewOptions')}
                  onClick={() => setSortMenuOpen(open => !open)}
                ><ListFilter size={14} /></button>
                {sortMenuOpen && (
                  <div className="sidebar-filter-menu" role="menu">
                    <div className="cx-muted sidebar-filter-menu__label">{t('sidebar.sortBy')}</div>
                    {(['updated', 'created'] as const).map(id => (
                      <button
                        key={id}
                        className="cx-btn cx-btn--text sidebar-filter-menu__item"
                        role="menuitemradio"
                        aria-checked={taskSortBy === id}
                        onClick={() => { setTaskSortBy(id); setSortMenuOpen(false); persistTaskSort(id) }}
                      >
                        {id === 'updated' ? <MessageCircle size={15} /> : <MessageCirclePlus size={15} />}
                        <span style={{ flex: 1 }}>{id === 'updated' ? t('sidebar.sortByUpdated') : t('sidebar.sortByCreated')}</span>
                        {taskSortBy === id && <Check size={14} />}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

          {viewMode === 'projects' ? (
            <>
              {grouping.projects.map(group => {
                const hasActive = group.conversations.some(conversation => conversation.id === ai.activeId)
                const expanded = projectExpanded(group.root, hasActive)
                return (
                  <div key={group.root}>
                    <ProjectHeader
                      name={group.name}
                      root={group.root}
                      expanded={expanded}
                      onToggle={() => {
                        const next = new Set(collapsedProjects)
                        if (next.has(group.root)) next.delete(group.root)
                        else next.add(group.root)
                        setCollapsedProjects(next)
                        persistCollapsedProjects(next)
                      }}
                      onNewChat={() => guarded(() => { void ai.newProjectConversation(group.root); navigate('/') })}
                    />
                    {expanded && group.conversations.map(conversation => (
                      <ConversationRow
                        key={conversation.id}
                        title={conversation.title || t('sidebar.untitled')}
                        active={conversation.id === ai.activeId}
                        project
                        time={relativeTime(conversation)}
                        onOpen={() => openConversation(conversation.id)}
                        onRemove={() => void removeConversation(conversation.id)}
                      />
                    ))}
                  </div>
                )
              })}
              {grouping.projects.length === 0 && (
                <div className="sidebar-projects-empty">
                  <FolderPlus size={20} />
                  <span>{t('sidebar.noProjects')}</span>
                </div>
              )}
            </>
          ) : (
            <>
              <div className="cx-subheader">{t('sidebar.history')}</div>
              {flatConversations.map(conversation => (
                <ConversationRow
                  key={conversation.id}
                  title={conversation.title || t('sidebar.untitled')}
                  active={conversation.id === ai.activeId}
                  time={relativeTime(conversation)}
                  onOpen={() => openConversation(conversation.id)}
                  onRemove={() => void removeConversation(conversation.id)}
                />
              ))}
            </>
          )}
        </div>

        <div ref={accountRef} className="sidebar-account">
          {accountMenuOpen && (
            <div className="sidebar-account-menu" role="menu">
              <button className="sidebar-account-menu-item" role="menuitem" onClick={() => guarded(() => { setAccountMenuOpen(false); navigate('/account') })}>
                <Info size={16} /><span>{t('account.details')}</span>
              </button>
              <button className="sidebar-account-menu-item" role="menuitem" onClick={() => guarded(() => { setAccountMenuOpen(false); navigate('/settings') })}>
                <Cog size={16} /><span>{t('sidebar.settings')}</span>
              </button>
              <button className="sidebar-account-menu-item" role="menuitem" onClick={() => { setAccountMenuOpen(false); setNotificationOpen(true) }}>
                {unreadCount > 0 ? <BellRing size={16} /> : <Bell size={16} />}
                <span>{t('notifications.title')}</span>
                {unreadCount > 0 && (
                  <span className="sidebar-account-menu-count">{unreadCount}</span>
                )}
              </button>
            </div>
          )}
          <button className="sidebar-user-button" onClick={() => setAccountMenuOpen(open => !open)} aria-haspopup="menu">
            <span className="sidebar-avatar">
              F
              {/* Unread beacon: the bell lives in the account menu now, so the avatar dot is
                  what makes new notifications discoverable without opening it. */}
              {unreadCount > 0 && <span className="sidebar-user-badge" aria-hidden="true" />}
            </span>
            <span className="cx-nav-label">{t('account.localAccount')}</span>
          </button>
          <NotificationCenter open={notificationOpen} rail={collapsed} onClose={() => setNotificationOpen(false)} />
          <button
            className={cn('cx-iconbtn cx-iconbtn--sm sidebar-about-button', { active: location.pathname === '/about' })}
            title={updateAvailable ? t('update.available', { version: latestVersion }) : t('sidebar.about')}
            aria-label={updateAvailable ? t('update.available', { version: latestVersion }) : t('sidebar.about')}
            onClick={() => guarded(() => navigate('/about'))}
          ><Info size={16} />
            {/* Update beacon: red dot until the newer release is installed (the update lives on About). */}
            {updateAvailable && <span className="sidebar-about-badge" aria-hidden="true" />}
          </button>
        </div>
      </div>
    </aside>
  )
}

/** ZCode-style sliding pill: the indicator tracks the active tab's measured box. */
function ViewSwitch({ value, onChange }: { value: HistoryView; onChange: (view: HistoryView) => void }) {
  const { t } = useTranslation()
  const chatsRef = useRef<HTMLButtonElement | null>(null)
  const projectsRef = useRef<HTMLButtonElement | null>(null)
  const [indicator, setIndicator] = useState({ width: 0, x: 0 })

  const measure = () => {
    const el = value === 'chats' ? chatsRef.current : projectsRef.current
    if (el) setIndicator({ width: el.offsetWidth, x: el.offsetLeft })
  }
  useEffect(measure, [value])
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined' || !chatsRef.current) return
    const observer = new ResizeObserver(measure)
    observer.observe(chatsRef.current)
    if (projectsRef.current) observer.observe(projectsRef.current)
    return () => observer.disconnect()
  }, [])

  return (
    <div className="sidebar-view-tabs" role="tablist" aria-label={t('sidebar.historyView')}>
      <span
        className="sidebar-view-tabs__indicator"
        aria-hidden="true"
        style={{ width: indicator.width ? `${indicator.width}px` : undefined, transform: `translateX(${indicator.x}px)`, opacity: indicator.width ? 1 : 0 }}
      />
      <button ref={chatsRef} role="tab" aria-selected={value === 'chats'} className={cn('sidebar-view-tab', { 'sidebar-view-tab--active': value === 'chats' })} onClick={() => onChange('chats')}>
        <MessageCircle size={12} />{t('sidebar.viewChats')}
      </button>
      <button ref={projectsRef} role="tab" aria-selected={value === 'projects'} className={cn('sidebar-view-tab', { 'sidebar-view-tab--active': value === 'projects' })} onClick={() => onChange('projects')}>
        <Folder size={12} />{t('sidebar.viewProjects')}
      </button>
    </div>
  )
}

function ProjectHeader({ name, root, expanded, onToggle, onNewChat }: {
  name: string; root: string; expanded: boolean; onToggle: () => void; onNewChat: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      className="sidebar-project"
      role="button"
      tabIndex={0}
      title={root}
      aria-expanded={expanded}
      onClick={onToggle}
      onKeyDown={event => {
        if (event.key === 'Enter') onToggle()
        if (event.key === ' ') { event.preventDefault(); onToggle() }
      }}
    >
      {expanded ? <ChevronDown size={13} className="sidebar-project__chevron" /> : <ChevronRight size={13} className="sidebar-project__chevron" />}
      {expanded ? <FolderOpen size={15} /> : <Folder size={15} />}
      <span className="cx-nav-label">{name}</span>
      <button
        className="cx-iconbtn sidebar-project__add"
        title={t('sidebar.newProjectConversation')}
        onClick={event => { event.stopPropagation(); onNewChat() }}
      ><Plus size={13} /></button>
    </div>
  )
}

function ConversationRow({ title, active, project, time, onOpen, onRemove }: {
  title: string; active: boolean; project?: boolean; time: string; onOpen: () => void; onRemove: () => void
}) {
  return (
    <div
      className={cn('cx-nav-item sidebar-nav-button sidebar-conversation', { active, 'sidebar-conversation--project': project })}
      role="button"
      tabIndex={0}
      title={title}
      onClick={onOpen}
      onKeyDown={event => {
        if (event.key === 'Enter') onOpen()
        if (event.key === ' ') { event.preventDefault(); onOpen() }
      }}
    >
      <span className="cx-nav-label">{title}</span>
      {time && <span className="sidebar-conversation-time" aria-hidden="true">{time}</span>}
      <button
        className="cx-iconbtn cx-iconbtn--sm sidebar-remove-conversation"
        onClick={event => { event.stopPropagation(); onRemove() }}
      ><X size={13} /></button>
    </div>
  )
}

// ── local persistence (same keys as the Vue shell) ─────────────────────────────────
function readHistoryView(): HistoryView {
  try { return localStorage.getItem('fengyu-sidebar-history-view') === 'chats' ? 'chats' : 'projects' }
  catch { return 'projects' }
}
function persistHistoryView(view: HistoryView) {
  try { localStorage.setItem('fengyu-sidebar-history-view', view) } catch { /* ignore */ }
}
function readTaskSort(): 'updated' | 'created' {
  try { return localStorage.getItem('fengyu-sidebar-task-sort') === 'created' ? 'created' : 'updated' }
  catch { return 'updated' }
}
function persistTaskSort(sort: 'updated' | 'created') {
  try { localStorage.setItem('fengyu-sidebar-task-sort', sort) } catch { /* ignore */ }
}
function loadCollapsedProjects(): Set<string> {
  try {
    const raw = localStorage.getItem('fengyu-sidebar-collapsed-projects')
    return new Set(raw ? (JSON.parse(raw) as string[]) : [])
  } catch { return new Set() }
}
function persistCollapsedProjects(set: Set<string>) {
  try { localStorage.setItem('fengyu-sidebar-collapsed-projects', JSON.stringify([...set])) } catch { /* ignore */ }
}
