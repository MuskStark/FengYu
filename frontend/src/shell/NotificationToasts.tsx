import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { X } from 'lucide-react'
import { useToastStore } from '@/stores/toasts'
import { useNotificationsStore } from '@/stores/notifications'
import { cn } from '@/lib/utils'
import { checkNavigationGuard } from '@/lib/navGuard'

/** Bottom-right transient toasts — the React port of NotificationToasts. */
export default function NotificationToasts() {
  const { t } = useTranslation()
  const toasts = useToastStore(state => state.toasts)
  const dismiss = useToastStore(state => state.dismiss)
  const markRead = useNotificationsStore(state => state.markRead)
  const navigate = useNavigate()
  if (toasts.length === 0) return null

  return (
    <div className="fx-toasts" aria-live="polite">
      {toasts.map(toast => (
        <div
          key={toast.id}
          className={cn('fx-toast', `fx-toast--${toast.level}`)}
          onClick={() => {
            if (toast.notificationId !== undefined) void markRead(toast.notificationId)
            const link = toast.link
            dismiss(toast.id)
            if (link) void (async () => {
              if (await checkNavigationGuard()) navigate(link.to)
            })
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 600 }}>{toast.title}</div>
            {toast.body && <div className="cx-muted">{toast.body}</div>}
          </div>
          <button
            className="cx-iconbtn cx-iconbtn--sm"
            aria-label={t('common.close')}
            onClick={event => { event.stopPropagation(); dismiss(toast.id) }}
          ><X size={13} /></button>
        </div>
      ))}
    </div>
  )
}
