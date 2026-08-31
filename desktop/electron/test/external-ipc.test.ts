import { beforeEach, describe, expect, it, vi } from 'vitest'

const handlers = new Map<string, (...args: unknown[]) => unknown>()
const openExternal = vi.fn()

vi.mock('electron', () => ({
  ipcMain: {
    handle: vi.fn((channel: string, handler: (...args: unknown[]) => unknown) => {
      handlers.set(channel, handler)
    }),
  },
  shell: { openExternal },
}))

beforeEach(() => {
  vi.clearAllMocks()
  handlers.clear()
})

describe('external:open', () => {
  it('opens http(s) URLs in the system browser', async () => {
    const { registerExternalIpc } = await import('../src/ipc/external')
    registerExternalIpc()

    await handlers.get('external:open')!({},
      'http://localhost:8080/oauth2/authorize?scope=openid+offline_access')

    expect(openExternal).toHaveBeenCalledWith(
      'http://localhost:8080/oauth2/authorize?scope=openid+offline_access',
    )
  })

  it.each(['file:///etc/passwd', 'javascript:alert(1)', 'not a URL'])(
    'rejects unsafe external URL %s',
    async (url) => {
      const { registerExternalIpc } = await import('../src/ipc/external')
      registerExternalIpc()

      await expect(handlers.get('external:open')!({}, url)).rejects.toThrow()
      expect(openExternal).not.toHaveBeenCalled()
    },
  )
})
