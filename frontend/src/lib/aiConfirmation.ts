import { i18n } from '@/i18n'
import { services } from '@/services'
import type { PluginInvokeResult } from '@/services/types'

export type ConfirmationStatus = 'pending' | 'submitting' | 'approved' | 'rejected' | 'error' | 'dismissed'

export interface ConfirmationSummaryRow { label: string; value: string }

export interface ToolConfirmation {
  source: 'plugin' | 'host'
  pluginId: string
  confirmationId: string
  toolCallId: string
  toolName: string
  approveMethod: string
  rejectMethod: string
  expiresAt: string
  summary: ConfirmationSummaryRow[]
  status: ConfirmationStatus
  result?: PluginInvokeResult
  error?: string
  /** Sandbox-escape request: the fence denied the command, approve = run it unfenced once. */
  sandboxEscape?: boolean
}

type InvokePlugin = (id: string, method: string, params: Record<string, unknown>) => Promise<PluginInvokeResult>

export function parseToolConfirmation(payload: Record<string, unknown>): ToolConfirmation | null {
  if (payload.phase === 'approval_required') {
    const confirmationId = string(payload.approvalId)
    const expiresAt = string(payload.expiresAt)
    const toolName = string(payload.name)
    if (!confirmationId || !expiresAt || !toolName) return null
    const args = isRecord(payload.arguments) ? payload.arguments : {}
    const sandboxEscape = args.escape === true
    const summary = [
      { label: 'Tool', value: toolName },
      ...Object.entries(args)
        .filter(([label]) => !(sandboxEscape && (label === 'escape' || label === 'denialReason')))
        .map(([label, value]) => ({
          label,
          value: typeof value === 'string' ? value : (JSON.stringify(value) ?? String(value)),
        })),
    ]
    if (sandboxEscape && typeof args.denialReason === 'string' && args.denialReason) {
      summary.push({ label: 'denialReason', value: args.denialReason })
    }
    return {
      source: 'host', pluginId: '', confirmationId, approveMethod: '', rejectMethod: '',
      toolCallId: string(payload.id) || confirmationId, toolName,
      expiresAt, summary, status: 'pending', sandboxEscape,
    }
  }
  if (payload.phase !== 'result' || typeof payload.output !== 'string') return null
  let envelope: unknown
  try { envelope = JSON.parse(payload.output) } catch { return null }
  if (!isRecord(envelope) || envelope.confirmation_required !== true || !isRecord(envelope.confirmation)) return null
  const value = envelope.confirmation
  const pluginId = string(value.pluginId)
  const confirmationId = string(value.confirmationId)
  const approveMethod = string(value.approveMethod)
  const rejectMethod = string(value.rejectMethod)
  const expiresAt = string(value.expiresAt)
  if (!pluginId || !confirmationId || !approveMethod || !rejectMethod || !expiresAt) return null
  const summary = Array.isArray(value.summary)
    ? value.summary.flatMap((row) => isRecord(row) && string(row.label) && string(row.value)
      ? [{ label: string(row.label), value: string(row.value) }] : [])
    : []
  return {
    source: 'plugin', pluginId, confirmationId, approveMethod, rejectMethod,
    toolCallId: string(payload.id) || confirmationId, toolName: string(payload.name),
    expiresAt, summary, status: 'pending',
  }
}

export async function actOnConfirmation(item: ToolConfirmation, approve: boolean,
    options?: { always?: boolean; feedback?: string },
    invoke: InvokePlugin = (id, method, params) => services.plugin.invoke(id, method, params, {
      callId: crypto.randomUUID(),
    }),
    resolveHost: (id: string, approved: boolean, hostOptions?: { always?: boolean; feedback?: string }) =>
      Promise<PluginInvokeResult> =
      (approvalId, approved, hostOptions) =>
        services.chat.resolveToolApproval(approvalId, approved, hostOptions)): Promise<void> {
  if (item.status !== 'pending') return
  item.status = 'submitting'
  try {
    item.result = item.source === 'host'
      ? await resolveHost(item.confirmationId, approve,
          { always: options?.always, feedback: options?.feedback })
      : await invoke(item.pluginId, approve ? item.approveMethod : item.rejectMethod,
          { confirmationId: item.confirmationId })
    if (item.result.ok === false) {
      throw new Error(typeof item.result.error === 'string'
        ? item.result.error : i18n.global.t('aichat.confirmationResolveFailed'))
    }
    item.status = approve ? 'approved' : 'rejected'
  } catch (error) {
    item.status = 'error'
    item.error = error instanceof Error ? error.message : String(error)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function string(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

// ── error-card recovery (the composer's failure affordances) ─────────────────

/** Whether a failed confirmation's gate is still open (a retry can still land). */
export function confirmationRetryable(item: ToolConfirmation, now = Date.now()): boolean {
  const expiresAt = Date.parse(item.expiresAt)
  return Number.isFinite(expiresAt) && now < expiresAt
}

/**
 * Re-arm a failed confirmation for another resolve: only valid from the error state
 * (the pending/submitting cards own the live flow) and only while the gate has not
 * expired. Returns whether the card changed; the caller republishes the store.
 */
export function retryConfirmation(item: ToolConfirmation, now = Date.now()): boolean {
  if (item.status !== 'error' || !confirmationRetryable(item, now)) return false
  item.status = 'pending'
  item.error = undefined
  return true
}

/** Retire a failed confirmation's card entirely (dismissed cards leave the composer). */
export function dismissConfirmation(item: ToolConfirmation): boolean {
  if (item.status !== 'error') return false
  item.status = 'dismissed'
  return true
}
