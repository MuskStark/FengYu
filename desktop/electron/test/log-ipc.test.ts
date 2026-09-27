import { afterEach, describe, expect, it, vi } from 'vitest'

const { mockOn, loggerMock } = vi.hoisted(() => ({
  mockOn: vi.fn(),
  loggerMock: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('electron', () => ({ ipcMain: { on: mockOn } }))

import { registerLogIpc } from '../src/ipc/log'

afterEach(() => vi.clearAllMocks())

describe('log:renderer forwarding (A2: SPA errors land in desktop.log)', () => {
  it('routes levels, falls back unknown levels to info, and truncates oversized messages', () => {
    registerLogIpc(loggerMock as never)
    expect(mockOn).toHaveBeenCalledWith('log:renderer', expect.any(Function))
    const handler = mockOn.mock.calls[0][1] as (event: unknown, payload: {
      level?: string
      message?: string
    }) => void

    handler(null, { level: 'error', message: 'boom' })
    handler(null, { level: 'warn', message: 'careful' })
    handler(null, { level: 'info', message: 'hi' })
    handler(null, { level: 'bogus', message: 'falls back to info' })
    handler(null, { level: 'error', message: 'x'.repeat(5000) })

    expect(loggerMock.error).toHaveBeenCalledWith('[renderer] boom')
    expect(loggerMock.warn).toHaveBeenCalledWith('[renderer] careful')
    expect(loggerMock.info).toHaveBeenCalledWith('[renderer] hi')
    expect(loggerMock.info).toHaveBeenCalledWith('[renderer] falls back to info')
    const forwarded = (loggerMock.error as unknown as { mock: { calls: string[][] } })
      .mock.calls[1][0]
    expect(forwarded.endsWith('…[truncated]')).toBe(true)
    expect(forwarded.length).toBeLessThanOrEqual('[renderer] '.length + 2000 + '…[truncated]'.length)
  })
})
