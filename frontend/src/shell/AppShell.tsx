import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router-dom'
import { PanelLeftClose, PanelLeftOpen, PanelRight } from 'lucide-react'
import Sidebar from './Sidebar'
import NotificationToasts from './NotificationToasts'
import BackgroundExecutionIndicator from './BackgroundExecutionIndicator'
import AppDialogHost from './AppDialogHost'
import { getPlatform } from '@/platform'
import { checkNavigationGuard } from '@/lib/navGuard'
import { useSettingsStore } from '@/stores/settings'
import { useAiSessionStore } from '@/stores/aiSession'
import { useConnectionStore } from '@/stores/connection'
import { useNotificationsStore } from '@/stores/notifications'
import { useUpdateStore } from '@/stores/update'
import { cn } from '@/lib/utils'
import {
  SIDEBAR_AUTO_COLLAPSE_VIEWPORT,
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_MAX_WIDTH_RATIO,
  SIDEBAR_MIN_WIDTH,
  clampSidebarWidth,
  isNewTaskShortcut,
  isToggleSidebarShortcut,
  persistSidebarWidth,
  readPersistedSidebarWidth,
} from './sidebar-layout'

/**
 * Desktop-first shell — full React port of the Vue AppShell structure:
 * - The settings route is a FULL-PAGE surface (its own master-detail with a back button),
 *   so the sidebar, its resizer, and every collapse control unmount there.
 * - ZCode-style collapse: the persisted desktop setting wins; the browser shell
 *   additionally auto-collapses below SIDEBAR_AUTO_COLLAPSE_VIEWPORT (off on macOS so the
 *   native title-bar toggle is always reversible). Collapsed = full retract (width 0 +
 *   fade), reversible via the mac title-bar toggle or the non-mac floating corner handle.
 * - Remembered width: persisted locally (cosmetic, unlike the backend-owned collapse flag)
 *   and re-clamped whenever the viewport shrinks below half of it. Drag past the minimum
 *   snaps to collapse; the separator is keyboard-operable (←/→ 16px steps).
 * - One live notification stream + the update probe live HERE (not in Sidebar) so they keep
 *   running on the settings route where the sidebar is unmounted. Same for the background
 *   execution indicator and the status bar's health poll.
 */
export default function AppShell({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const location = useLocation()
  const settingsRoute = location.pathname === '/settings'
  const macTitleBar = getPlatform().os === 'darwin'

  const collapsedSetting = useSettingsStore(state => state.sidebarCollapsed)
  const setSidebarCollapsed = useSettingsStore(state => state.setSidebarCollapsed)
  const loadSettings = useSettingsStore(state => state.load)
  const loadAi = useSettingsStore(state => state.loadAi)
  const tick = useConnectionStore(state => state.tick)
  const initNotifications = useNotificationsStore(state => state.init)
  const checkForUpdate = useUpdateStore(state => state.check)
  const startPeriodicChecks = useUpdateStore(state => state.startPeriodicChecks)

  // ── shell-owned streams & probes (survive the sidebar unmounting on /settings) ──
  useEffect(() => {
    // Backend-owned preferences (theme / language / sidebar shape / AI models) must land at
    // boot — nothing else applies them until the settings page is opened.
    void loadSettings().catch(() => { /* offline: keep pre-paint defaults */ })
    void loadAi().catch(() => { /* offline: model selector stays unconfigured */ })
    initNotifications()
    void checkForUpdate()
    startPeriodicChecks()
  }, [loadSettings, loadAi, initNotifications, checkForUpdate, startPeriodicChecks])

  useEffect(() => {
    const timer = window.setInterval(() => void tick(), 5000)
    void tick()
    return () => window.clearInterval(timer)
  }, [tick])

  // ── viewport tracking (Vue: vuetify useDisplay) ──
  const [viewportWidth, setViewportWidth] = useState(() => (typeof window === 'undefined' ? 1440 : window.innerWidth))
  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth)
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  const autoCollapse = viewportWidth < SIDEBAR_AUTO_COLLAPSE_VIEWPORT
  const sidebarCollapsed = collapsedSetting || (!macTitleBar && autoCollapse)

  // ── remembered expanded width ──
  const [sidebarWidth, setSidebarWidth] = useState(() =>
    clampSidebarWidth(readPersistedSidebarWidth() ?? SIDEBAR_DEFAULT_WIDTH, viewportWidth))
  useEffect(() => {
    setSidebarWidth(width => clampSidebarWidth(width, viewportWidth))
  }, [viewportWidth])

  const toggleSidebar = () => {
    void setSidebarCollapsed(!collapsedSetting)
  }

  /** ⌘B / Ctrl+B toggles the sidebar; ⌘N / Ctrl+N starts a new conversation (ZCode newTask). */
  const settingsRouteRef = useRef(settingsRoute)
  settingsRouteRef.current = settingsRoute
  const toggleRef = useRef(toggleSidebar)
  toggleRef.current = toggleSidebar
  const navigate = useNavigate()
  const navigateRef = useRef(navigate)
  navigateRef.current = navigate
  const newChat = useAiSessionStore(state => state.newChat)
  const newChatRef = useRef(newChat)
  newChatRef.current = newChat
  useEffect(() => {
    const onKeydown = (event: KeyboardEvent) => {
      if (settingsRouteRef.current || event.repeat || event.isComposing) return
      if (isToggleSidebarShortcut(event)) {
        event.preventDefault()
        toggleRef.current()
        return
      }
      if (isNewTaskShortcut(event)) {
        event.preventDefault()
        // The unsaved-work guard may show the in-app discard dialog before switching.
        void (async () => {
          if (!(await checkNavigationGuard())) return
          newChatRef.current()
          navigateRef.current('/')
        })()
      }
    }
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  }, [])

  // ── drag-to-resize separator (pointer drag + keyboard arrows) ──
  const [sidebarResizing, setSidebarResizing] = useState(false)
  const dragState = useRef({ active: false, startX: 0, startWidth: 0 })

  const onResizerPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    dragState.current = { active: true, startX: event.clientX, startWidth: sidebarWidth }
    setSidebarResizing(true)
    event.currentTarget.setPointerCapture(event.pointerId)
    event.preventDefault()
  }
  const onResizerPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!dragState.current.active) return
    const max = Math.max(SIDEBAR_MIN_WIDTH, window.innerWidth * SIDEBAR_MAX_WIDTH_RATIO)
    // Below the minimum the sidebar keeps shrinking (drag-to-collapse preview); the snap
    // decision happens on release.
    setSidebarWidth(Math.max(0, Math.min(dragState.current.startWidth + event.clientX - dragState.current.startX, max)))
  }
  const onResizerPointerUp = () => {
    if (!dragState.current.active) return
    dragState.current.active = false
    setSidebarResizing(false)
    setSidebarWidth(width => {
      if (width < SIDEBAR_MIN_WIDTH) {
        // Dragged past the minimum width → collapse; restore a sane width for the next expand.
        const restored = Math.max(dragState.current.startWidth, SIDEBAR_MIN_WIDTH)
        persistSidebarWidth(restored)
        toggleSidebar()
        return restored
      }
      persistSidebarWidth(width)
      return width
    })
  }
  const onResizerPointerCancel = () => {
    if (!dragState.current.active) return
    dragState.current.active = false
    setSidebarResizing(false)
    setSidebarWidth(width => {
      const clamped = clampSidebarWidth(width, window.innerWidth)
      persistSidebarWidth(clamped)
      return clamped
    })
  }
  const onResizerKeydown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const step = event.key === 'ArrowLeft' ? -16 : 16
    setSidebarWidth(width => {
      const next = clampSidebarWidth(width + step, window.innerWidth)
      persistSidebarWidth(next)
      return next
    })
  }

  const maxAria = Math.max(SIDEBAR_MIN_WIDTH, Math.round(viewportWidth * SIDEBAR_MAX_WIDTH_RATIO))

  return (
    <div
      className={cn('fx-shell',
        macTitleBar && 'mac-titlebar',
        settingsRoute && 'settings-shell',
        sidebarCollapsed && 'sidebar-collapsed')}
    >
      {macTitleBar && (
        // Single 48px titlebar overlay (ZCode density): traffic lights, the sidebar toggle,
        // and the sidebar brand share one row instead of stacking a flow bar + a sidebar
        // strip + a brand row. Width tracks the sidebar so the main area's own header
        // controls stay clickable; drag-to-move lives here (the toggle opts out via no-drag).
        <div
          className="fx-windowbar"
          style={settingsRoute ? undefined : { width: sidebarCollapsed ? 0 : sidebarWidth }}
        >
          {/* Sidebar toggle rides the title bar (Vue shell-window-controls parity): the only
              always-reversible collapse control on macOS, right of the traffic lights. */}
          {!settingsRoute && (
            <button
              className="cx-iconbtn cx-iconbtn--sm fx-windowbar__toggle"
              title={sidebarCollapsed ? t('sidebar.expand') : t('sidebar.collapse')}
              aria-label={sidebarCollapsed ? t('sidebar.expand') : t('sidebar.collapse')}
              onClick={toggleSidebar}
            >
              {sidebarCollapsed ? <PanelLeftOpen size={17} /> : <PanelLeftClose size={17} />}
            </button>
          )}
          <span className="fx-windowbar__drag" />
        </div>
      )}
      <div className="fx-body">
        {/* Collapsed-corners handle: without a persistent top bar (non-macOS), this floating
            button is what keeps "collapse" reversible on every route. */}
        {sidebarCollapsed && !macTitleBar && !settingsRoute && (
          <button
            className="cx-iconbtn cx-iconbtn--sm shell-sidebar-handle"
            title={t('sidebar.expand')}
            aria-label={t('sidebar.expand')}
            onClick={toggleSidebar}
          ><PanelRight size={16} /></button>
        )}
        {!settingsRoute && (
          <Sidebar
            collapsed={sidebarCollapsed}
            width={sidebarWidth}
            resizing={sidebarResizing}
            macTitleBar={macTitleBar}
          />
        )}
        {!settingsRoute && !sidebarCollapsed && (
          <div
            className={cn('shell-sidebar-resizer', sidebarResizing && 'resizing')}
            role="separator"
            tabIndex={0}
            aria-orientation="vertical"
            aria-label={t('sidebar.resize')}
            aria-valuemin={SIDEBAR_MIN_WIDTH}
            aria-valuenow={Math.round(sidebarWidth)}
            aria-valuemax={maxAria}
            onPointerDown={onResizerPointerDown}
            onPointerMove={onResizerPointerMove}
            onPointerUp={onResizerPointerUp}
            onPointerCancel={onResizerPointerCancel}
            onKeyDown={onResizerKeydown}
          />
        )}
        <main className="fx-main">{children}</main>
      </div>
      <NotificationToasts />
      <BackgroundExecutionIndicator />
      <AppDialogHost />
    </div>
  )
}
