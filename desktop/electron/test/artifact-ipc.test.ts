import { describe, expect, it, vi, beforeEach } from 'vitest'

/**
 * Saved-chat-artifact IPC contract: the renderer passes ONLY an artifact id, the handler
 * resolves the path through the backend registry, reveal/open errors surface as failures
 * (never as success), and executable/script artifacts reveal instead of launching.
 */
const electron = vi.hoisted(() => {
  return {
    showItemInFolder: vi.fn(),
    openPath: vi.fn(),
    handle: null as
      | ((event: unknown, artifactId: unknown) => Promise<void>)
      | null,
    channels: [] as string[],
  }
})

vi.mock('electron', () => ({
  shell: {
    showItemInFolder: (...args: unknown[]) => electron.showItemInFolder(...args),
    openPath: (...args: unknown[]) => electron.openPath(...args),
  },
  ipcMain: {
    handle: vi.fn((channel: string, handler: typeof electron.handle) => {
      electron.channels.push(channel)
      electron.channels.push(`__handler:${channel}`)
      ;(electron as unknown as Record<string, unknown>)[`handler:${channel}`] = handler
    }),
  },
}))

import { isArtifactOpenAllowed, registerArtifactIpc } from '../src/ipc/artifact'

const resolver = vi.fn()

beforeEach(() => {
  electron.channels.length = 0
  vi.clearAllMocks()
  registerArtifactIpc(resolver)
})

describe('artifact ipc', () => {
  it('registers the reveal and open channels', () => {
    expect(electron.channels).toContain('artifact:reveal')
    expect(electron.channels).toContain('artifact:open')
  })

  it('reveals via the backend-resolved path', async () => {
    resolver.mockResolvedValue({ path: '/Users/me/Desktop/报表.xlsx' })
    await handler('artifact:reveal')(null, 'art_1')
    expect(resolver).toHaveBeenCalledWith('art_1')
    expect(electron.showItemInFolder).toHaveBeenCalledWith('/Users/me/Desktop/报表.xlsx')
  })

  it('opens a regular file with the default application', async () => {
    resolver.mockResolvedValue({ path: '/tmp/report.pdf' })
    electron.openPath.mockResolvedValue('')
    await handler('artifact:open')(null, 'art_2')
    expect(electron.openPath).toHaveBeenCalledWith('/tmp/report.pdf')
  })

  it('never launches script or executable artifacts — it reveals them instead', async () => {
    resolver.mockResolvedValue({ path: '/tmp/generated/tool.sh' })
    electron.showItemInFolder.mockResolvedValue('')
    await handler('artifact:open')(null, 'art_3')
    expect(electron.openPath).not.toHaveBeenCalled()
    expect(electron.showItemInFolder).toHaveBeenCalledWith('/tmp/generated/tool.sh')

    expect(isArtifactOpenAllowed('/tmp/a.exe')).toBe(false)
    expect(isArtifactOpenAllowed('/tmp/a.sh')).toBe(false)
    expect(isArtifactOpenAllowed('/tmp/a.app')).toBe(false)
    expect(isArtifactOpenAllowed('/tmp/report.xlsx')).toBe(true)
  })

  it('rejects malformed ids and resolver failures without touching the shell', async () => {
    await expect(handler('artifact:reveal')(null, '')).rejects.toThrow()
    await expect(handler('artifact:reveal')(null, 42)).rejects.toThrow()

    resolver.mockRejectedValue(new Error('Artifact has not been saved yet'))
    await expect(handler('artifact:open')(null, 'art_missing')).rejects.toThrow(
      'Artifact has not been saved yet',
    )
    expect(electron.openPath).not.toHaveBeenCalled()
    expect(electron.showItemInFolder).not.toHaveBeenCalled()
  })
})

function handler(channel: string): (event: unknown, artifactId: unknown) => Promise<void> {
  const stored = (electron as unknown as Record<string, unknown>)[`handler:${channel}`]
  if (typeof stored !== 'function') throw new Error(`no handler registered for ${channel}`)
  return stored as (event: unknown, artifactId: unknown) => Promise<void>
}
