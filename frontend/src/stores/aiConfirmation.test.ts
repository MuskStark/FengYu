import { describe, expect, it, vi } from 'vitest'
import { actOnConfirmation, parseToolConfirmation } from './aiConfirmation'

describe('AI tool confirmations', () => {
  it('parses a safe confirmation_required result', () => {
    const result = parseToolConfirmation({ phase: 'result', output: JSON.stringify({
      confirmation_required: true,
      confirmation: { pluginId: 'fan.summer.email', confirmationId: 'c1',
        approveMethod: 'confirm_send', rejectMethod: 'reject_send',
        expiresAt: '2026-07-13T12:30:00Z', summary: [{ label: 'Recipients', value: '12' }] },
    }) })
    expect(result?.confirmationId).toBe('c1')
    expect(result?.summary).toEqual([{ label: 'Recipients', value: '12' }])
  })

  it('submits a pending confirmation only once', async () => {
    const item = parseToolConfirmation({ phase: 'result', output: JSON.stringify({
      confirmation_required: true,
      confirmation: { pluginId: 'fan.summer.email', confirmationId: 'c1',
        approveMethod: 'confirm_send', rejectMethod: 'reject_send',
        expiresAt: '2026-07-13T12:30:00Z', summary: [] },
    }) })!
    const invoke = vi.fn().mockResolvedValue({ success: true })
    await Promise.all([actOnConfirmation(item, true, invoke), actOnConfirmation(item, true, invoke)])
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(item.status).toBe('approved')
  })

  it('parses and resolves a host tool approval before execution', async () => {
    const item = parseToolConfirmation({
      phase: 'approval_required',
      approvalId: 'approval-1',
      name: 'execute_command',
      arguments: { command: 'pwd', workingDirectory: '/tmp' },
      expiresAt: '2026-07-13T12:30:00Z',
    })!
    const invoke = vi.fn()
    const resolveHost = vi.fn().mockResolvedValue({ ok: true })

    await actOnConfirmation(item, true, invoke, resolveHost)

    expect(item.source).toBe('host')
    expect(item.summary).toContainEqual({ label: 'command', value: 'pwd' })
    expect(resolveHost).toHaveBeenCalledWith('approval-1', true)
    expect(invoke).not.toHaveBeenCalled()
    expect(item.status).toBe('approved')
  })
})
