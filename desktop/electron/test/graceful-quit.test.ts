import { describe, it, expect, vi, beforeEach } from 'vitest'
import { EventEmitter } from 'node:events'
import type { ChildProcess } from 'node:child_process'
import type { BackendChild } from '../src/backend/supervisor'

/**
 * Unit tests for the before-quit graceful-shutdown handler (`src/desktop/graceful-quit.ts`).
 *
 * The handler implements the preventDefault-and-wait pattern: first quit → SIGTERM, wait for
 * the child's exit event (capped), forceKill backstop, app.quit() again; second quit passes
 * through. Follows the fake-ChildProcess style of spawn.test.ts.
 */

const { quit } = vi.hoisted(() => ({ quit: vi.fn() }))

vi.mock('electron', () => ({ app: { quit: quit } }))

import { createGracefulQuitHandler, markUpdateInstallRestart } from '../src/desktop/graceful-quit'

interface FakeChild {
  proc: ChildProcess
  child: BackendChild
}

function fakeChild(alive = true): FakeChild {
  const proc = new EventEmitter() as unknown as ChildProcess
  Object.assign(proc, {
    pid: 4242,
    exitCode: alive ? null : 0,
    signalCode: null,
  })
  const child: BackendChild = {
    process: proc,
    kill: vi.fn(),
    forceKill: vi.fn(),
  }
  return { proc, child }
}

function makeHandler(child: BackendChild | null, graceMs = 2_000) {
  const onTeardown = vi.fn()
  const onFirstQuit = createGracefulQuitHandler({
    getChild: () => child,
    onTeardown,
    graceMs,
    quit,
  })
  return { onFirstQuit, onTeardown }
}

const event = () => ({ preventDefault: vi.fn() })

beforeEach(() => {
  quit.mockClear()
})

describe('createGracefulQuitHandler — graceful path', () => {
  it('prevents the first quit, SIGTERMs the tree, and re-quits after the child exits', async () => {
    const { proc, child } = fakeChild()
    const { onFirstQuit, onTeardown } = makeHandler(child, 60_000)

    const e = event()
    onFirstQuit(e)

    expect(e.preventDefault).toHaveBeenCalledOnce()
    expect(onTeardown).toHaveBeenCalledOnce()
    expect(child.kill).toHaveBeenCalledOnce() // graceful SIGTERM tree-kill
    expect(quit).not.toHaveBeenCalled() // quit is held until the backend exits
    expect(child.forceKill).not.toHaveBeenCalled()

    proc.emit('exit', 0, null)
    await vi.waitFor(() => expect(quit).toHaveBeenCalledOnce())
    expect(child.forceKill).toHaveBeenCalledOnce() // backstop (no-op on a exited tree)
  })

  it('force-kills and re-quits when the child ignores the grace deadline', async () => {
    const { child } = fakeChild()
    const { onFirstQuit } = makeHandler(child, 20)

    const e = event()
    onFirstQuit(e)
    expect(e.preventDefault).toHaveBeenCalledOnce()

    await vi.waitFor(() => expect(quit).toHaveBeenCalledOnce())
    expect(child.kill).toHaveBeenCalledOnce()
    expect(child.forceKill).toHaveBeenCalledOnce()
  })

  it('lets a re-issued quit pass through without repeating the sequence', async () => {
    const { child } = fakeChild()
    const { onFirstQuit, onTeardown } = makeHandler(child, 60_000)

    onFirstQuit(event()) // first: prevented, sequence starts
    const second = event()
    onFirstQuit(second) // second (post-grace app.quit()): must NOT be prevented

    expect(second.preventDefault).not.toHaveBeenCalled()
    expect(onTeardown).toHaveBeenCalledOnce()
    expect(child.kill).toHaveBeenCalledOnce()
  })
})

describe('createGracefulQuitHandler — skip-the-wait paths', () => {
  it('allows an immediate quit when no backend was spawned (dev connect mode)', () => {
    const { onFirstQuit, onTeardown } = makeHandler(null)

    const e = event()
    onFirstQuit(e)

    expect(e.preventDefault).not.toHaveBeenCalled()
    expect(onTeardown).toHaveBeenCalledOnce()
    expect(quit).not.toHaveBeenCalled() // nothing to wait for — this quit simply proceeds
  })

  it('allows an immediate quit when the backend already exited', () => {
    const { child } = fakeChild(false) // exitCode set: already gone
    const { onFirstQuit } = makeHandler(child)

    const e = event()
    onFirstQuit(e)

    expect(e.preventDefault).not.toHaveBeenCalled()
    expect(child.kill).not.toHaveBeenCalled()
    expect(quit).not.toHaveBeenCalled()
  })

  it('force-kills immediately (no grace window) for an update install-restart', async () => {
    // markUpdateInstallRestart sets a one-way module flag — reload a fresh module so it does
    // not leak into the other tests in this file.
    vi.resetModules()
    const fresh = await import('../src/desktop/graceful-quit')
    const { child } = fakeChild()
    const onTeardown = vi.fn()
    const onFirstQuit = fresh.createGracefulQuitHandler({
      getChild: () => child,
      onTeardown,
      graceMs: 60_000,
      quit,
    })

    fresh.markUpdateInstallRestart()
    const e = event()
    onFirstQuit(e)

    expect(e.preventDefault).not.toHaveBeenCalled() // quit proceeds now, undelayed
    expect(child.kill).not.toHaveBeenCalled() // no graceful wait is attempted
    expect(child.forceKill).toHaveBeenCalledOnce()
    expect(quit).not.toHaveBeenCalled()
    // Give the (absent) grace chain a chance to misfire: nothing further may happen.
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(child.forceKill).toHaveBeenCalledOnce()
    expect(quit).not.toHaveBeenCalled()
  })
})
