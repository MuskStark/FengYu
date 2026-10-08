import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for the system tray (src/desktop/tray.ts): the menu/click handlers capture
 * the main window for the tray's lifetime, which can outlive the window (an update
 * install destroys all windows before app.quit()), so every entry point must tolerate a
 * destroyed window; and createTray must return null — never throw, never a dead tray —
 * when no usable tray exists, so main.ts can fall back to "close quits".
 */

const { mockTrayCtor, mockMenuTemplate, mockCreateFromPath, trayShouldThrow } = vi.hoisted(() => ({
  mockTrayCtor: vi.fn(),
  // Filled by the Menu mock below; unknown[] so the factory can push into it.
  mockMenuTemplate: [] as unknown[],
  mockCreateFromPath: vi.fn(),
  trayShouldThrow: { value: false },
}))

vi.mock('electron', () => ({
  app: { isPackaged: false, quit: vi.fn() },
  Menu: {
    buildFromTemplate: vi.fn((template: unknown[]) => {
      mockMenuTemplate.length = 0
      mockMenuTemplate.push(...template)
      return {}
    }),
  },
  nativeImage: { createFromPath: mockCreateFromPath },
  Tray: class {
    constructor(...args: unknown[]) {
      mockTrayCtor(...args)
      if (trayShouldThrow.value) throw new Error('no tray host')
    }
    setToolTip = vi.fn()
    setContextMenu = vi.fn()
    on = vi.fn()
  },
}))

import { createTray } from '../src/desktop/tray'

function fakeImage(empty = false) {
  return {
    isEmpty: () => empty,
    addRepresentation: vi.fn(),
    setTemplateImage: vi.fn(),
    toPNG: () => Buffer.from([]),
    resize: () => fakeImage(empty),
  }
}

function fakeWindow(destroyed = false) {
  return {
    isDestroyed: () => destroyed,
    show: vi.fn(),
    hide: vi.fn(),
    focus: vi.fn(),
    isVisible: () => false,
  } as unknown as Electron.BrowserWindow
}

function menuItem(label: string): { click?: () => void } {
  const item = mockMenuTemplate.find(
    (entry) => (entry as { label?: string }).label === label,
  ) as { click?: () => void } | undefined
  if (!item) throw new Error(`menu item not found: ${label}`)
  return item
}

describe('system tray', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    trayShouldThrow.value = false
    mockMenuTemplate.length = 0
    mockCreateFromPath.mockReturnValue(fakeImage(false))
  })

  it('builds the Show/Hide/Quit menu on a healthy tray', () => {
    const tray = createTray(fakeWindow(), vi.fn())

    expect(tray).not.toBeNull()
    expect(menuItem('Show').click).toBeTypeOf('function')
    expect(menuItem('Hide').click).toBeTypeOf('function')
    expect(menuItem('Quit').click).toBeTypeOf('function')
  })

  it('menu actions are no-ops after the window is destroyed', () => {
    const win = fakeWindow(true) // destroyed: update install already tore the window down
    createTray(win, vi.fn())

    expect(() => menuItem('Show').click!()).not.toThrow()
    expect(() => menuItem('Hide').click!()).not.toThrow()
    expect(win.show).not.toHaveBeenCalled()
    expect(win.hide).not.toHaveBeenCalled()
  })

  it('Show focuses the live window', () => {
    const win = fakeWindow(false)
    createTray(win, vi.fn())

    menuItem('Show').click!()
    expect(win.show).toHaveBeenCalledOnce()
    expect(win.focus).toHaveBeenCalledOnce()
  })

  it('Quit still runs the teardown + quit path (independent of the window)', () => {
    const onQuit = vi.fn()
    createTray(fakeWindow(true), onQuit)

    menuItem('Quit').click!()
    expect(onQuit).toHaveBeenCalledOnce()
  })

  it('returns null when the tray host rejects the icon', () => {
    trayShouldThrow.value = true
    const log = vi.fn()

    const tray = createTray(fakeWindow(), vi.fn(), log)

    expect(tray).toBeNull()
    expect(log).toHaveBeenCalledWith(expect.stringContaining('system tray unavailable'))
  })

  it('returns null when every icon source comes up empty', () => {
    mockCreateFromPath.mockReturnValue(fakeImage(true))
    const log = vi.fn()

    const tray = createTray(fakeWindow(), vi.fn(), log)

    expect(tray).toBeNull()
    expect(log).toHaveBeenCalledWith(expect.stringContaining('no readable tray icon'))
  })
})
