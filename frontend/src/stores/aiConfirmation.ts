import { api } from '@/api/client'
import type { PluginInvokeResult } from '@/api/types'

export type ConfirmationStatus = 'pending' | 'submitting' | 'approved' | 'rejected' | 'error'

export interface ConfirmationSummaryRow { label: string; value: string }

export interface ToolConfirmation {
  pluginId: string
  confirmationId: string
  approveMethod: string
  rejectMethod: string
  expiresAt: string
  summary: ConfirmationSummaryRow[]
  status: ConfirmationStatus
  result?: PluginInvokeResult
  error?: string
}

type InvokePlugin = (id: string, method: string, params: Record<string, unknown>) => Promise<PluginInvokeResult>

export function parseToolConfirmation(payload: Record<string, unknown>): ToolConfirmation | null {
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
  return { pluginId, confirmationId, approveMethod, rejectMethod, expiresAt, summary, status: 'pending' }
}

export async function actOnConfirmation(item: ToolConfirmation, approve: boolean,
    invoke: InvokePlugin = api.pluginInvoke): Promise<void> {
  if (item.status !== 'pending') return
  item.status = 'submitting'
  try {
    item.result = await invoke(item.pluginId, approve ? item.approveMethod : item.rejectMethod,
      { confirmationId: item.confirmationId })
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
