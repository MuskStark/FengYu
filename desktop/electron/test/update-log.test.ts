import { afterEach, describe, expect, it, vi } from 'vitest'

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

import { logUpdate, updateLogPath } from '../src/updater/update-log'

afterEach(() => vi.clearAllMocks())

describe('update.log rotation (A3: append-only file must stay bounded)', () => {
  it('renames the oversized file to a single archive before appending', () => {
    mockStat.mockReturnValue({ size: 3 * 1024 * 1024 })
    logUpdate('step')
    expect(mockRename).toHaveBeenCalledWith(String.raw`\rt\logs\update.log`, String.raw`\rt\logs\update.old.log`)
    expect(mockAppend).toHaveBeenCalledWith(String.raw`\rt\logs\update.log`, expect.stringContaining('step'), 'utf8')
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
  it('stays in the runtime log directory with win32 separators (baked into replace scripts)', () => {
    expect(updateLogPath()).toBe(String.raw`\rt\logs\update.log`)
  })
})
