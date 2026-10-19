import { rpc } from './sdk'
import type { SendResult } from './stores/compose'

/** Task states that leave the worker's delivery loop; anything else means "still on the wire". */
const TERMINAL_STATUSES = new Set(['COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'REJECTED', 'EXPIRED'])
const POLL_INTERVAL_MS = 2000
const RECORDS_PAGE_SIZE = 100

/** Poll interval knob so tests can shorten the 2s production cadence. */
export const deliveryPolling = { intervalMs: POLL_INTERVAL_MS }

function delay(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)) }

/** Mid-flight delivery snapshot from email_send_status; total is 0 when unknown. */
export interface DeliveryProgress { sent: number; total: number; status: string }

/**
 * A confirm_send whose delivery outgrows its server-side grace window answers SENDING and keeps
 * sending on a worker background thread — a large-attachment batch must never sit on one RPC
 * longer than the call timeout, or the host kills the worker mid-send. Poll the task to a
 * terminal state, reporting each poll's sentCount/totalCount through {@code onProgress} (the
 * progress bar), then rebuild the same SendResult shape the synchronous path returns from the
 * per-message sent log.
 */
export async function pollDelivery(confirmationId: string, onProgress?: (progress: DeliveryProgress) => void): Promise<SendResult> {
  for (;;) {
    await delay(deliveryPolling.intervalMs)
    const status = await rpc.email_send_status({ confirmationId })
    if (!status.send) throw new Error(status.summary)
    if (onProgress) onProgress({ sent: status.send.sentCount, total: status.send.totalCount, status: status.send.status })
    if (!TERMINAL_STATUSES.has(status.send.status)) continue
    return { ...await resultFromSentLog(confirmationId), status: status.send.status }
  }
}

async function resultFromSentLog(confirmationId: string): Promise<Omit<SendResult, 'status'>> {
  const failedRecipients: string[] = []
  let succeeded = 0, failed = 0
  for (let offset = 0; ; offset += RECORDS_PAGE_SIZE) {
    const page = await rpc.email_send_records_query({ confirmationId, offset, limit: RECORDS_PAGE_SIZE })
    const messages = page.messages ?? []
    for (const message of messages) {
      if (message.status === 'FAILED') {
        // The synchronous result counts recipients, not messages; mirror that from the logged
        // primary recipients of each failed message.
        const recipients = recipientCount(message.recipientsJson)
        failed += recipients
        failedRecipients.push(...recipientsOf(message.recipientsJson))
      } else if (message.status === 'SUCCESS') succeeded++
    }
    if (messages.length < RECORDS_PAGE_SIZE) break
  }
  return { succeeded, failed, failedRecipients }
}

function recipientsOf(recipientsJson?: string): string[] {
  if (!recipientsJson) return []
  try {
    const parsed = JSON.parse(recipientsJson) as { to?: string[] }
    return Array.isArray(parsed.to) ? parsed.to : []
  } catch { return [] }
}

function recipientCount(recipientsJson?: string): number { return recipientsOf(recipientsJson).length }
