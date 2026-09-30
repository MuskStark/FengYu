import { ref, type Ref } from 'vue'
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
const clientQueues = new WeakMap<FengYuClient, Ref<FyNotification[]>>()
const standaloneQueue = ref<FyNotification[]>([])

function queueFor(client?: FengYuClient): Ref<FyNotification[]> {
  if (!client) return standaloneQueue
  const existing = clientQueues.get(client)
  if (existing) return existing
  const queue = ref<FyNotification[]>([])
  clientQueues.set(client, queue)
  return queue
}

function enqueue(local: Ref<FyNotification[]>, message: string, options: FyNotificationOptions = {}): void {
  notificationSequence += 1
  local.value.push({
    id: notificationSequence,
    message,
    tone: options.tone ?? 'info',
    timeout: options.timeout ?? 5_000,
  })
}

/**
 * Deliver a notification to the host, falling back to a local queue when the
 * host rejects (returns `false`) or throws. Cancellation/thrown errors are not
 * propagated: the message is kept locally so the plugin UI can still surface it.
 */
export async function sendFengYuNotification(
  client: FengYuClient | undefined,
  message: string,
  local: Ref<FyNotification[]> = queueFor(client),
  options: FyNotificationOptions = {},
) {
  if (!client) {
    enqueue(local, message, options)
    return
  }
  try {
    const accepted = await client.notify(message)
    if (!accepted) enqueue(local, message, options)
  } catch {
    enqueue(local, message, options)
  }
}

/**
 * Notification helper bound to a {@link FengYuClient}. `notify` forwards to the
 * host and mirrors any rejected/thrown message into `localMessages`, which a
 * {@link FyNotificationCenter} renders as a fallback snackbar queue.
 */
export function useFengYuNotify(client?: FengYuClient): {
  notify: (message: string, options?: FyNotificationOptions) => Promise<void>
  localMessages: Ref<FyNotification[]>
} {
  // One queue per client lets a notifier created deep in a plugin feed the
  // single FyNotificationCenter mounted by the shell.
  const localMessages = queueFor(client)
  const notify = (message: string, options?: FyNotificationOptions) =>
    sendFengYuNotification(client, message, localMessages, options)
  return { notify, localMessages }
}
