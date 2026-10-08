import { describe, expect, it } from 'vitest'
import {
  confirmationRetryable,
  dismissConfirmation,
  parseToolConfirmation,
  retryConfirmation,
  type ToolConfirmation,
} from './aiConfirmation'

function failedConfirmation(overrides: Partial<ToolConfirmation> = {}): ToolConfirmation {
  return {
    source: 'host',
    pluginId: '',
    confirmationId: 'c1',
    toolCallId: 'c1',
    toolName: 'workspace_exec',
    approveMethod: '',
    rejectMethod: '',
    expiresAt: new Date(Date.now() + 60_000).toISOString(),
    summary: [],
    status: 'error',
    error: 'boom',
    ...overrides,
  }
}

describe('confirmation error-card recovery', () => {
  it('retry re-arms only error cards whose gate is still open', () => {
    const item = failedConfirmation()
    expect(retryConfirmation(item)).toBe(true)
    expect(item.status).toBe('pending')
    expect(item.error).toBeUndefined()

    const expired = failedConfirmation({ expiresAt: new Date(Date.now() - 1_000).toISOString() })
    expect(retryConfirmation(expired)).toBe(false)
    expect(expired.status).toBe('error')

    const pending = failedConfirmation({ status: 'pending' })
    expect(retryConfirmation(pending)).toBe(false)
  })

  it('dismiss retires an error card; other states are untouched', () => {
    const item = failedConfirmation()
    expect(dismissConfirmation(item)).toBe(true)
    expect(item.status).toBe('dismissed')

    const pending = failedConfirmation({ status: 'pending' })
    expect(dismissConfirmation(pending)).toBe(false)
    expect(pending.status).toBe('pending')
  })

  it('confirmationRetryable reflects the gate clock', () => {
    expect(confirmationRetryable(failedConfirmation())).toBe(true)
    expect(confirmationRetryable(failedConfirmation({ expiresAt: '' }))).toBe(false)
    expect(confirmationRetryable(failedConfirmation({
      expiresAt: new Date(Date.now() - 1).toISOString(),
    }))).toBe(false)
  })

  it('parseToolConfirmation still builds pending host cards (existing surface)', () => {
    const card = parseToolConfirmation({
      phase: 'approval_required', approvalId: 'a1', id: 't1',
      name: 'workspace_exec', expiresAt: new Date(Date.now() + 30_000).toISOString(),
      arguments: { command: 'ls' },
    })
    expect(card).toMatchObject({ source: 'host', status: 'pending', toolName: 'workspace_exec' })
    expect(parseToolConfirmation({ phase: 'result', output: 'nope' })).toBeNull()
  })
})
