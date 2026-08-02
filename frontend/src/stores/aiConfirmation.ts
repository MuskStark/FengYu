import { api } from '@/api/client'
import type { PluginInvokeResult } from '@/api/types'

export type ConfirmationStatus = 'pending' | 'submitting' | 'approved' | 'rejected' | 'error'

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
}

type InvokePlugin = (id: string, method: string, params: Record<string, unknown>) => Promise<PluginInvokeResult>
type ResolveHost = (id: string, approved: boolean) => Promise<PluginInvokeResult>

export function parseToolConfirmation(payload: Record<string, unknown>): ToolConfirmation | null {
  if (payload.phase === 'approval_required') {
    const confirmationId = string(payload.approvalId)
    const expiresAt = string(payload.expiresAt)
    const toolName = string(payload.name)
    if (!confirmationId || !expiresAt || !toolName) return null
    const args = isRecord(payload.arguments) ? payload.arguments : {}
    const summary = [
      { label: 'Tool', value: toolName },
      ...Object.entries(args).map(([label, value]) => ({
        label,
        value: typeof value === 'string' ? value : (JSON.stringify(value) ?? String(value)),
      })),
    ]
    return {
      source: 'host', pluginId: '', confirmationId, approveMethod: '', rejectMethod: '',
      toolCallId: string(payload.id) || confirmationId, toolName,
      expiresAt, summary, status: 'pending',
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
    invoke: InvokePlugin = api.pluginInvoke,
    resolveHost: ResolveHost = api.resolveAiToolApproval): Promise<void> {
  if (item.status !== 'pending') return
  item.status = 'submitting'
  try {
    item.result = item.source === 'host'
      ? await resolveHost(item.confirmationId, approve)
      : await invoke(item.pluginId, approve ? item.approveMethod : item.rejectMethod,
          { confirmationId: item.confirmationId })
    if (item.result.ok === false) {
      throw new Error(typeof item.result.error === 'string'
        ? item.result.error : 'Approval request could not be resolved')
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
