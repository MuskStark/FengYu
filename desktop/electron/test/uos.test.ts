import { describe, expect, it, vi } from 'vitest'

vi.mock('electron', () => ({ app: { isPackaged: false, getAppPath: vi.fn(), getPath: vi.fn() } }))

import { isUosBuild } from '../src/desktop/uos'

describe('isUosBuild', () => {
  it('matches only the linux platform with fengyu.uos baked true', () => {
    expect(isUosBuild({ fengyu: { uos: true } }, 'linux')).toBe(true)
    expect(isUosBuild({ fengyu: { uos: false } }, 'linux')).toBe(false)
    expect(isUosBuild({ fengyu: {} }, 'linux')).toBe(false)
    expect(isUosBuild({}, 'linux')).toBe(false)
    // Never on other platforms, even if the metadata leaked in.
    expect(isUosBuild({ fengyu: { uos: true } }, 'darwin')).toBe(false)
    expect(isUosBuild({ fengyu: { uos: true } }, 'win32')).toBe(false)
  })

  it('fails safe on malformed metadata (wrong shape or absent fengyu block)', () => {
    expect(isUosBuild({ fengyu: 'uos' }, 'linux')).toBe(false)
    expect(isUosBuild({ fengyu: { uos: 'true' } }, 'linux')).toBe(false)
    expect(isUosBuild(null as unknown as Record<string, unknown>, 'linux')).toBe(false)
  })
})
