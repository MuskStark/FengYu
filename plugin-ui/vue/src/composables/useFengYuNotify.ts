import { ref, type Ref } from 'vue'
import type { FengYuClient } from '@fengyu/plugin-sdk'

/**
 * Deliver a notification to the host, falling back to a local queue when the
 * host rejects (returns `false`) or throws. Cancellation/thrown errors are not
 * propagated: the message is kept locally so the plugin UI can still surface it.
 */
export async function sendFengYuNotification(client: FengYuClient, message: string, local: Ref<string[]>) {
  try {
    const accepted = await client.notify(message)
    if (!accepted) local.value.push(message)
  } catch {
    local.value.push(message)
  }
}

/**
 * Notification helper bound to a {@link FengYuClient}. `notify` forwards to the
 * host and mirrors any rejected/thrown message into `localMessages`, which a
 * {@link FyNotificationCenter} renders as a fallback snackbar queue.
 */
export function useFengYuNotify(client: FengYuClient): {
  notify: (message: string) => Promise<void>
  localMessages: Ref<string[]>
} {
  const localMessages = ref<string[]>([])
  const notify = (message: string) => sendFengYuNotification(client, message, localMessages)
  return { notify, localMessages }
}
