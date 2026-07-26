import { EventEmitter } from 'node:events'
import type { ChildProcess } from 'node:child_process'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBackendChild } from '../src/backend/spawn'

function fakeProcess() {
  const proc = new EventEmitter() as ChildProcess
  Object.assign(proc, {
    exitCode: null,
    signalCode: null,
    killed: false,
    kill: vi.fn(function (this: ChildProcess) {
      // Reproduce Node's semantics: killed becomes true as soon as a signal is
      // successfully sent, before the process has necessarily exited.
      this.killed = true
      return true
    }),
  })
  return proc
}

describe('backend child shutdown', () => {
  afterEach(() => vi.useRealTimers())

  it('hard-kills a process that remains alive after SIGTERM', () => {
    vi.useFakeTimers()
    const proc = fakeProcess()
    const child = createBackendChild(proc, 50)

    child.kill()
    expect(proc.kill).toHaveBeenCalledWith('SIGTERM')
    vi.advanceTimersByTime(50)
    expect(proc.kill).toHaveBeenLastCalledWith('SIGKILL')
  })

  it('cancels the hard kill after the process exits', () => {
    vi.useFakeTimers()
    const proc = fakeProcess()
    const child = createBackendChild(proc, 50)

    child.kill()
    proc.exitCode = 0
    proc.emit('exit', 0, null)
    vi.advanceTimersByTime(50)
    expect(proc.kill).toHaveBeenCalledTimes(1)
  })
})
