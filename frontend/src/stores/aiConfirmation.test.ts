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
})
