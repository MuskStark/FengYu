import {
  createContext,
  createElement,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import type { FengYuClient } from '@infinia/plugin-sdk'

export type FyNotificationTone = 'info' | 'success' | 'warning' | 'error'

export interface FyNotification {
  id: number
  message: string
  tone: FyNotificationTone
  timeout: number
}

export interface FyNotificationOptions {
  tone?: FyNotificationTone
  /** Local fallback duration in milliseconds. Use -1 for a persistent notice. */
  timeout?: number
}

let notificationSequence = 0

type Listener = () => void

/**
 * Notification store: delivered to the host via `client.notify`, mirrored into
 * a local queue when the host rejects (`false`) or throws. One queue per
 * client lets any notifier in the tree feed the single {@link NotifyHost}
 * mounted by the app root.
 */
class NotificationQueue {
  readonly items: FyNotification[] = []
  private listeners = new Set<Listener>()

  subscribe = (listener: Listener) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }
  getSnapshot = () => this.items
  private emit() {
    this.listeners.forEach((listener) => listener())
  }
  push(message: string, options: FyNotificationOptions = {}) {
    notificationSequence += 1
    this.items.push({
      id: notificationSequence,
      message,
      tone: options.tone ?? 'info',
      timeout: options.timeout ?? 5_000,
    })
    this.emit()
  }
  dismiss(id: number) {
    const index = this.items.findIndex((item) => item.id === id)
    if (index >= 0) {
      this.items.splice(index, 1)
      this.emit()
    }
  }
}

const clientQueues = new WeakMap<FengYuClient, NotificationQueue>()
const standaloneQueue = new NotificationQueue()

function queueFor(client?: FengYuClient): NotificationQueue {
  if (!client) return standaloneQueue
  const existing = clientQueues.get(client)
  if (existing) return existing
  const queue = new NotificationQueue()
  clientQueues.set(client, queue)
  return queue
}

/**
 * Deliver a notification to the host, falling back to the local queue when
 * the host rejects or throws. Cancellation/thrown errors are not propagated:
 * the message is kept locally so the plugin UI can still surface it.
 */
export async function sendFengYuNotification(
  client: FengYuClient | undefined,
  message: string,
  queue: NotificationQueue = queueFor(client),
  options: FyNotificationOptions = {},
): Promise<void> {
  if (!client) {
    queue.push(message, options)
    return
  }
  try {
    const accepted = await client.notify(message)
    if (!accepted) queue.push(message, options)
  } catch {
    queue.push(message, options)
  }
}

export interface FengYuNotifyApi {
  notify: (message: string, options?: FyNotificationOptions) => Promise<void>
  dismiss: (id: number) => void
  messages: FyNotification[]
}

const NotifyContext = createContext<FengYuNotifyApi | null>(null)
NotifyContext.displayName = 'FengYuNotify'

/**
 * Notification helper bound to the ambient {@link FengYuClient}: `notify`
 * forwards to the host and mirrors rejected/thrown messages into the local
 * queue rendered by {@link NotifyHost}.
 */
export function useFengYuNotify(): FengYuNotifyApi {
  const api = useContext(NotifyContext)
  if (api) return api
  // Outside a provider (e.g. unit tests driving a component directly):
  // fall back to the standalone queue so notify() still works.
  const queue = queueFor(undefined)
  return {
    notify: (message, options) => sendFengYuNotification(undefined, message, queue, options),
    dismiss: (id) => queue.dismiss(id),
    messages: queue.items,
  }
}

/** Installs the shared notify API; renders the Infinia toast host. */
export function NotifyProvider({ client, children }: { client?: FengYuClient; children: ReactNode }) {
  const queue = useMemo(() => queueFor(client), [client])
  const [tick, setTick] = useState(0)
  useEffect(() => {
    const unsubscribe = queue.subscribe(() => setTick((value) => value + 1))
    return () => { unsubscribe() }
  }, [queue])
  void tick
  const api = useMemo<FengYuNotifyApi>(
    () => ({
      notify: (message, options) => sendFengYuNotification(client, message, queue, options),
      dismiss: (id) => queue.dismiss(id),
      messages: queue.getSnapshot(),
    }),
    [client, queue],
  )
  return createElement(NotifyContext.Provider, { value: api }, children, <NotifyHost key="host" queue={queue} />)
}

function toneColor(tone: FyNotificationTone): string {
  if (tone === 'success') return 'var(--c-success)'
  if (tone === 'warning') return 'var(--c-warning)'
  if (tone === 'error') return 'var(--c-danger)'
  return 'var(--c-ink-2)'
}

/** Bottom-right fallback snackbar queue (host-rejected notifications). */
export function NotifyHost({ queue }: { queue: NotificationQueue }) {
  const [, setTick] = useState(0)
  useEffect(() => {
    const unsubscribe = queue.subscribe(() => setTick((value) => value + 1))
    return () => { unsubscribe() }
  }, [queue])
  const items = queue.getSnapshot()
  if (items.length === 0) return null
  return (
    <div
      data-notify-host=""
      aria-live="polite"
      className="fixed bottom-10 right-4 z-50 flex w-[320px] flex-col gap-2"
    >
      {items.map((item) => (
        <ToastCard key={item.id} item={item} onDismiss={() => queue.dismiss(item.id)} />
      ))}
    </div>
  )
}

function ToastCard({ item, onDismiss }: { item: FyNotification; onDismiss: () => void }) {
  useEffect(() => {
    if (item.timeout < 0) return
    const timer = window.setTimeout(onDismiss, item.timeout)
    return () => window.clearTimeout(timer)
  }, [item.timeout, onDismiss])
  return (
    <div
      data-notify-item=""
      className="flex items-start gap-2.5 rounded-xl border border-line bg-raised px-3.5 py-3 text-[13px] text-ink shadow-[0_12px_28px_rgba(24,24,27,0.14)]"
    >
      <span className="mt-1.5 inline-block size-1.5 shrink-0 rounded-full" style={{ background: toneColor(item.tone) }} />
      <span className="min-w-0 flex-1 break-words">{item.message}</span>
      <button
        type="button"
        aria-label="Dismiss"
        data-action="dismiss-notification"
        className="-mr-1 -mt-0.5 shrink-0 rounded-md px-1 text-ink-3 transition-colors hover:text-ink"
        onClick={onDismiss}
      >
        ×
      </button>
    </div>
  )
}
