import { describe, expect, it, vi, beforeEach } from 'vitest'

// Capture the ipcMain.on channel + handler the module registers against.
// vi.mock is hoisted above imports, so the capture object must be hoisted too.
const electron = vi.hoisted(() => ({
  on: null as ((channel: string, handler: (event: unknown, raw: unknown) => void) => void) | null,
}))

vi.mock('electron', () => ({
  ipcMain: {
    on: vi.fn((channel: string, handler: (event: unknown, raw: unknown) => void) => {
      electron.on = (_channel, raw) => handler(_channel, raw)
    }),
  },
}))

import { registerPerfIpc } from '../src/ipc/perf'
import { markMainWindowLoad, markMainLaunchWhenReady } from '../src/desktop/launch-marks'

const info = vi.fn()

function report(raw: unknown): void {
  if (!electron.on) throw new Error('perf IPC handler was not registered')
  electron.on('perf:launch-report', raw)
}

beforeEach(() => {
  info.mockClear()
  registerPerfIpc({ info })
  // Give T2/T3 real values so the logged line exercises the merge path.
  markMainLaunchWhenReady()
  markMainWindowLoad()
})

describe('registerPerfIpc (perf:launch-report)', () => {
  it('logs one merged T0–T6 line for a valid renderer payload', () => {
    report({ rendererStart: 1000, reactCommit: 1100, inputReady: 1500 })
    expect(info).toHaveBeenCalledTimes(1)
    const line = info.mock.calls[0][0] as string
    expect(line).toContain('[perf] launch')
    expect(line).toContain('t4=1000')
    expect(line).toContain('t5=1100')
    expect(line).toContain('t6=1500')
    expect(line).toContain('bootGate=400ms')
  })

  it('drops payloads with non-numeric marks without logging', () => {
    report({ rendererStart: '1000', reactCommit: 1100, inputReady: 1500 })
    report({ rendererStart: 1000, reactCommit: Number.NaN, inputReady: 1500 })
    report({ rendererStart: 1000 })
    report(null)
    expect(info).not.toHaveBeenCalled()
  })
})
