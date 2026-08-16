import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Unit tests for the second-instance window targeting (`src/desktop/single-instance.ts`).
 *
 * The race this guards: before the splash's URL resolves, webContents.getURL() returns '' (and
 * isLoading() is true), so an unresolved frameless splash must not be mistaken for the main
 * window and shown+focused. Where main.ts provides its explicit main-window reference, that
 * wins over any URL heuristic.
 */

const { mockWindows, mockOn, mockQuit, mockRequestLock } = vi.hoisted(() => ({
  mockWindows: [] as unknown[],
  mockOn: vi.fn(),
  mockQuit: vi.fn(),
  mockRequestLock: vi.fn(() => true),
}))

vi.mock('electron', () => ({
  app: {
    requestSingleInstanceLock: mockRequestLock,
    on: mockOn,
    quit: mockQuit,
  },
  BrowserWindow: {
    getAllWindows: vi.fn(() => mockWindows),
  },
}))

import { acquireSingleInstanceLock } from '../src/desktop/single-instance'

interface FakeWin {
  isDestroyed: () => boolean
  webContents: { getURL: () => string; isLoading: () => boolean }
}

function fakeWin(url: string, isLoading = false): FakeWin {
  return {
    isDestroyed: () => false,
    webContents: { getURL: () => url, isLoading: () => isLoading },
  }
}

function fireSecondInstance(): void {
  const registration = mockOn.mock.calls.find(([event]) => event === 'second-instance')
  expect(registration).toBeDefined()
  ;(registration![1] as () => void)()
}

beforeEach(() => {
  mockWindows.length = 0
  vi.clearAllMocks()
  mockRequestLock.mockReturnValue(true)
})

describe('acquireSingleInstanceLock', () => {
  it('quits (and returns false) when the lock is not acquired', () => {
    mockRequestLock.mockReturnValue(false)
    const gotLock = acquireSingleInstanceLock(() => {})
    expect(gotLock).toBe(false)
    expect(mockQuit).toHaveBeenCalledOnce()
  })

  it('does not treat an unresolved splash (empty URL / still loading) as the main window', () => {
    mockWindows.push(fakeWin('', true)) // splash before its URL resolves
    mockWindows.push(fakeWin('')) // a window that settled on no URL yet
    const onSecondInstance = vi.fn()

    const gotLock = acquireSingleInstanceLock(onSecondInstance)
    expect(gotLock).toBe(true)
    fireSecondInstance()

    // Neither unresolved window is shown+focused — the exact bug the heuristic must avoid.
    expect(onSecondInstance).not.toHaveBeenCalled()
  })

  it('skips the resolved splash and focuses the main window', () => {
    const splash = fakeWin('file:///app/resources/splash.html?lang=en')
    const main = fakeWin('http://127.0.0.1:5173/')
    mockWindows.push(splash, main)
    const onSecondInstance = vi.fn()

    acquireSingleInstanceLock(onSecondInstance)
    fireSecondInstance()

    expect(onSecondInstance).toHaveBeenCalledTimes(1)
    expect(onSecondInstance).toHaveBeenCalledWith(main)
  })

  it('prefers the explicit main-window reference over the URL heuristic', () => {
    const explicit = fakeWin('file:///app/frontend-dist/index.html')
    const other = fakeWin('http://127.0.0.1:5173/')
    mockWindows.push(other) // heuristic would find this first
    const onSecondInstance = vi.fn()

    acquireSingleInstanceLock(onSecondInstance, () => (explicit as unknown) as Electron.BrowserWindow)
    fireSecondInstance()

    expect(onSecondInstance).toHaveBeenCalledTimes(1)
    expect(onSecondInstance).toHaveBeenCalledWith(explicit)
  })

  it('ignores a destroyed explicit reference and falls back to the heuristic', () => {
    const destroyed = { ...fakeWin('http://127.0.0.1:5173/'), isDestroyed: () => true }
    const main = fakeWin('http://127.0.0.1:5173/')
    mockWindows.push(main)
    const onSecondInstance = vi.fn()

    acquireSingleInstanceLock(onSecondInstance, () => (destroyed as unknown) as Electron.BrowserWindow)
    fireSecondInstance()

    expect(onSecondInstance).toHaveBeenCalledTimes(1)
    expect(onSecondInstance).toHaveBeenCalledWith(main)
  })
})
