import { EventEmitter } from 'node:events'
import type { ChildProcess } from 'node:child_process'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBackendChild, spawnBackend } from '../src/backend/spawn'
import type { RuntimeLayout } from '../src/backend/runtime-layout'
import { runtimeRoot } from '../src/desktop/runtime-paths'

// Module mocks for spawnBackend tests. vi.hoisted lets the factories reference
// the mock fns despite vi.mock being hoisted above the imports.
const { mockSpawn, mockExistsSync, mockResolveJava } = vi.hoisted(() => ({
  mockSpawn: vi.fn(),
  mockExistsSync: vi.fn(),
  mockResolveJava: vi.fn(),
}))

vi.mock('node:child_process', () => ({ spawn: mockSpawn }))
vi.mock('node:fs', () => ({ existsSync: mockExistsSync }))
vi.mock('../src/backend/runtime-layout-helpers', () => ({ resolveJava: mockResolveJava }))

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

// Build a fake spawned ChildProcess whose stdout/stderr are EventEmitters, so a
// test can emit lines the way readPort consumes them.
function fakeSpawnedProcess() {
  const proc = new EventEmitter() as unknown as ChildProcess
  Object.assign(proc, {
    stdout: new EventEmitter(),
    stderr: new EventEmitter(),
    exitCode: null,
    signalCode: null,
    killed: false,
    kill: vi.fn(),
  })
  return proc
}

const FAKE_LAYOUT: RuntimeLayout = {
  jar: '/fake/FengYu.jar',
  plugins: '/fake/plugins',
}

// Emit a stdout line after readPort has had a chance to attach its listeners.
// spawnBackend awaits one setImmediate (the spawn-error wait) before readPort
// attaches, so we nest two: the first lets that wait resolve, the second emits.
function emitStdoutLine(proc: ChildProcess, line: string) {
  setImmediate(() =>
    setImmediate(() => (proc.stdout as EventEmitter).emit('data', Buffer.from(line))),
  )
}

describe('backend child shutdown', () => {
  afterEach(() => vi.useRealTimers())

  it('signals the whole process tree (SIGTERM, then SIGKILL) via tree-kill', () => {
    vi.useFakeTimers()
    const proc = fakeProcess()
    Object.assign(proc, { pid: 4242 })
    const treeKillFn = vi.fn()
    const child = createBackendChild(proc, 50, treeKillFn)

    child.kill()
    // Graceful path: the entire backend tree (backend JVM + worker grandchildren) gets SIGTERM.
    expect(treeKillFn).toHaveBeenCalledWith(4242, 'SIGTERM')
    expect(proc.kill).not.toHaveBeenCalled()

    vi.advanceTimersByTime(50)
    // Escalation: the tree that has not exited by the deadline gets SIGKILL.
    expect(treeKillFn).toHaveBeenLastCalledWith(4242, 'SIGKILL')
  })

  it('cancels the hard kill after the process exits', () => {
    vi.useFakeTimers()
    const proc = fakeProcess()
    Object.assign(proc, { pid: 4242 })
    const treeKillFn = vi.fn()
    const child = createBackendChild(proc, 50, treeKillFn)

    child.kill()
    proc.exitCode = 0
    proc.emit('exit', 0, null)
    vi.advanceTimersByTime(50)
    // Only the graceful SIGTERM call; no SIGKILL escalation once the tree exited.
    expect(treeKillFn).toHaveBeenCalledTimes(1)
    expect(treeKillFn).toHaveBeenLastCalledWith(4242, 'SIGTERM')
  })

  it('is idempotent — a second kill() is a no-op', () => {
    const proc = fakeProcess()
    Object.assign(proc, { pid: 4242 })
    const treeKillFn = vi.fn()
    const child = createBackendChild(proc, 50, treeKillFn)

    child.kill()
    child.kill()
    expect(treeKillFn).toHaveBeenCalledTimes(1)
  })

  it('skips an already-exited backend', () => {
    const proc = fakeProcess()
    Object.assign(proc, { pid: 4242, exitCode: 0 })
    const treeKillFn = vi.fn()
    const child = createBackendChild(proc, 50, treeKillFn)

    child.kill()
    expect(treeKillFn).not.toHaveBeenCalled()
  })
})

describe('spawnBackend', () => {
  afterEach(() => vi.clearAllMocks())

  it('invokes onProgress with port-ready when FENGYU_PORT is parsed', async () => {
    const proc = fakeSpawnedProcess()
    mockSpawn.mockReturnValue(proc)
    mockExistsSync.mockReturnValue(true)
    mockResolveJava.mockReturnValue('/fake/jre/bin/java')
    emitStdoutLine(proc, 'FENGYU_PORT=24056\n')

    const onProgress = vi.fn()
    const result = await spawnBackend({
      layout: FAKE_LAYOUT,
      token: 't',
      requestedPort: 24056,
      onProgress,
    })

    expect(result.port).toBe(24056)
    const args = mockSpawn.mock.calls[0][1] as string[]
    const options = mockSpawn.mock.calls[0][2]
    expect(args).toContain(`-Dfengyu.runtime.dir=${runtimeRoot()}`)
    expect(args.some(arg => arg.startsWith('--token='))).toBe(false)
    expect(options).toMatchObject({
      cwd: runtimeRoot(),
      env: expect.objectContaining({ FENGYU_AUTH_TOKEN: 't' }),
    })
    expect(onProgress).toHaveBeenCalledOnce()
    expect(onProgress).toHaveBeenCalledWith('port-ready')
  })
})
