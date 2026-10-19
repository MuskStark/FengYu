import { afterEach, describe, expect, it, vi } from 'vitest'
import { join } from 'node:path'

const { mockAppend, mockStat, mockRename, mockMkdir } = vi.hoisted(() => ({
  mockAppend: vi.fn(),
  mockStat: vi.fn(),
  mockRename: vi.fn(),
  mockMkdir: vi.fn(),
}))

vi.mock('node:fs', () => ({
  appendFileSync: mockAppend,
  statSync: mockStat,
  renameSync: mockRename,
  mkdirSync: mockMkdir,
}))
vi.mock('../src/desktop/runtime-paths', () => ({ runtimeRoot: () => '/rt' }))

import { logUpdate, updateLogPath, windowsUpdateLogPath } from '../src/updater/update-log'

// Native separators: the real writes go to the platform's own log directory. (The old
// win32-join-on-every-platform form wrote literal-backslash filenames on macOS/Linux.)
const nativeLog = join('/rt', 'logs', 'update.log')
const nativeArchive = join('/rt', 'logs', 'update.old.log')

afterEach(() => vi.clearAllMocks())

describe('update.log rotation (A3: append-only file must stay bounded)', () => {
  it('renames the oversized file to a single archive before appending', () => {
    mockStat.mockReturnValue({ size: 3 * 1024 * 1024 })
    logUpdate('step')
    expect(mockRename).toHaveBeenCalledWith(nativeLog, nativeArchive)
    expect(mockAppend).toHaveBeenCalledWith(nativeLog, expect.stringContaining('step'), 'utf8')
  })

  it('leaves a small file alone and never throws when the file does not exist yet', () => {
    mockStat.mockReturnValue({ size: 100 })
    logUpdate('ok')
    expect(mockRename).not.toHaveBeenCalled()
    mockStat.mockImplementation(() => {
      throw new Error('ENOENT')
    })
    expect(() => logUpdate('first write')).not.toThrow()
    expect(mockAppend).toHaveBeenCalled()
  })

  it('keeps appending when the rename races a holder (Windows replace script)', () => {
    mockStat.mockReturnValue({ size: 3 * 1024 * 1024 })
    mockRename.mockImplementation(() => {
      throw new Error('EPERM')
    })
    expect(() => logUpdate('locked')).not.toThrow()
    expect(mockAppend).toHaveBeenCalled()
  })
})

describe('updateLogPath', () => {
  it('uses native separators so macOS/Linux writes land in the real log directory', () => {
    expect(updateLogPath()).toBe(nativeLog)
  })

  it('windowsUpdateLogPath keeps win32 separators for baking into the replace script', () => {
    expect(windowsUpdateLogPath()).toBe(String.raw`\rt\logs\update.log`)
  })
})
