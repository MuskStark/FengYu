import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { BellOff, CheckCircle2, Info, AlertTriangle, XCircle, X } from 'lucide-react'
import { useNotificationsStore, displayTitle } from '@/stores/notifications'
import { cn } from '@/lib/utils'
import { checkNavigationGuard } from '@/lib/navGuard'
import type { AppNotification } from '@/services/types'

const LEVEL_ICONS = {
  info: Info,
  success: CheckCircle2,
  warning: AlertTriangle,
  error: XCircle,
} as const

/** Compact relative timestamp, matching the shell's density. */
function useRelativeTime() {
  const { t, i18n } = useTranslation()
  return (iso: string): string => {
    const then = new Date(iso).getTime()
    if (!Number.isFinite(then)) return ''
    const minutes = Math.round((Date.now() - then) / 60_000)
    if (minutes < 1) return t('notifications.justNow')
    if (minutes < 60) return t('notifications.minutesAgo', { n: minutes })
    const hours = Math.round(minutes / 60)
    if (hours < 24) return t('notifications.hoursAgo', { n: hours })
    return new Intl.DateTimeFormat(i18n.language, { month: 'short', day: 'numeric' }).format(new Date(then))
  }
}

/**
 * Persisted notification center — React port of the Vue shell panel. Anchored to
 * .sidebar-account exactly like the account menu it opens from; the panel replaces the
 * menu in place. The parent (Sidebar) owns `open`.
 */
export default function NotificationCenter({ open, rail, onClose }: { open: boolean; rail?: boolean; onClose: () => void }) {
  const { t } = useTranslation()
  const relativeTime = useRelativeTime()
  const navigate = useNavigate()
  const items = useNotificationsStore(state => state.items)
  const unreadCount = useNotificationsStore(state => state.unreadCount)
  const markRead = useNotificationsStore(state => state.markRead)
  const markAllRead = useNotificationsStore(state => state.markAllRead)
  const remove = useNotificationsStore(state => state.remove)
  const panel = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      if (!panel.current?.contains(event.target as Node)) onClose()
    }
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeydown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeydown)
    }
  }, [open, onClose])

  if (!open) return null

  const activate = (n: AppNotification) => {
    void markRead(n.id)
    const link = n.link
    if (link) {
      onClose()
      void (async () => {
        if (await checkNavigationGuard()) navigate(link)
      })
    }
  }

  return (
    <div
      ref={panel}
      className={cn('notification-panel', rail && 'rail')}
      role="menu"
      aria-label={t('notifications.title')}
    >
      <div className="notification-panel-header">
        <span className="notification-panel-title">{t('notifications.title')}</span>
        {unreadCount > 0 && (
          <button className="cx-btn cx-btn--text cx-btn--sm" onClick={() => void markAllRead()}>
            {t('notifications.markAllRead')}
          </button>
        )}
      </div>

      {items.length === 0 ? (
        <div className="notification-empty">
          <BellOff size={26} aria-hidden="true" />
          <span>{t('notifications.empty')}</span>
        </div>
      ) : (
        <div className="notification-list">
          {items.map(n => {
            const Icon = LEVEL_ICONS[n.level] ?? LEVEL_ICONS.info
            return (
              <div
                key={n.id}
                className={cn('notification-item', !n.read && 'unread')}
                role="menuitem"
                tabIndex={0}
                onClick={() => activate(n)}
                onKeyDown={event => { if (event.key === 'Enter') activate(n) }}
              >
                <Icon size={16} className="notification-item-icon" aria-hidden="true" />
                <div className="notification-item-copy">
                  <div className="notification-item-title">
                    <span>{displayTitle(n)}</span>
                    {!n.read && <span className="notification-unread-dot" aria-hidden="true" />}
                  </div>
                  {n.body && <div className="notification-item-body">{n.body}</div>}
                  <div className="notification-item-time">{relativeTime(n.createdAt)}</div>
                </div>
                <button
                  className="cx-iconbtn cx-iconbtn--sm notification-item-remove"
                  aria-label={t('notifications.delete')}
                  onClick={event => { event.stopPropagation(); void remove(n.id) }}
                ><X size={13} /></button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
