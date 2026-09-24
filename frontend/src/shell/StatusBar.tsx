import { useTranslation } from 'react-i18next'
import { useConnectionStore, type ConnectionState } from '@/stores/connection'
import { cn } from '@/lib/utils'

const STATE_LABELS: Record<ConnectionState, string> = {
  connecting: 'status.connecting',
  connected: 'status.connected',
  reconnecting: 'status.reconnecting',
  offline: 'status.offline',
  restarting: 'status.restarting',
}

/** Bottom connectivity strip — the Vue StatusBar's five-state machine. */
export default function StatusBar() {
  const { t } = useTranslation()
  const state = useConnectionStore(store => store.state)
  return (
    <footer className={cn('fx-statusbar', {
      'fx-statusbar--connected': state === 'connected',
      'fx-statusbar--offline': state === 'offline',
    })} role="status">
      <span className="fx-statusbar__dot" aria-hidden="true" />
      <span>{t(STATE_LABELS[state])}</span>
    </footer>
  )
}
