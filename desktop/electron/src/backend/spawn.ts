import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolveJava } from './runtime-layout-helpers'
import type { RuntimeLayout } from './runtime-layout'
import { parseFengyuPort } from './handshake'
import type { BackendChild } from './supervisor'

export interface SpawnOptions {
  layout: RuntimeLayout
  token: string
  requestedPort: number
  shouldCancel?: () => boolean
  onLine?: (line: string) => void
  deadlineMs?: number
  pollIntervalMs?: number
}

export interface SpawnedBackend {
  child: BackendChild
  port: number
}

/**
 * Spawn the Java backend and read the bound port from stdout (`FENGYU_PORT=<n>`).
 * Mirrors Rust `spawn_backend`. 30s deadline, cancellable.
 *
 * The Java executable is resolved by `resolveJava`: bundled jre/bin/java for the
 * with-JRE variant, else PATH lookup (caller handles the not-found error).
 */
export async function spawnBackend(opts: SpawnOptions): Promise<SpawnedBackend> {
  const { layout, token, requestedPort, shouldCancel = () => false } = opts
  const deadlineMs = opts.deadlineMs ?? 30_000
  const pollIntervalMs = opts.pollIntervalMs ?? 200

  if (!existsSync(layout.jar)) {
    throw new Error(
      `FengYu jar not found at ${layout.jar}. Build it and stage it at desktop/electron/resources/binaries/FengYu.jar (see desktop/README.md).`,
    )
  }
  const javaBin = resolveJava(layout)

  const args = [
    `-Dfengyu.plugins.official-directory=${layout.plugins}`,
    '-cp',
    layout.jar,
    'fan.summer.fengyu.HeadlessLauncher',
    `--port=${requestedPort}`,
    `--token=${token}`,
  ]

  const proc = spawn(javaBin, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })

  const child: BackendChild = {
    process: proc,
    kill() {
      if (!proc.killed) {
        proc.kill('SIGTERM')
        // SIGKILL fallback after grace
        setTimeout(() => {
          if (!proc.killed) proc.kill('SIGKILL')
        }, 5_000)
      }
    },
  }

  // Reject fast if java couldn't even start (e.g. ENOENT — wrong PATH).
  await new Promise<void>((resolve, reject) => {
    proc.once('error', reject)
    setImmediate(resolve) // give spawn a tick to emit error
  }).catch((err) => {
    throw new Error(`failed to spawn java: ${err.message}`)
  })

  let port: number
  try {
    port = await readPort(proc, { deadlineMs, pollIntervalMs, shouldCancel, onLine: opts.onLine })
  } catch (err) {
    child.kill()
    throw err
  }

  return { child, port }
}

async function readPort(
  proc: ChildProcess,
  opts: {
    deadlineMs: number
    pollIntervalMs: number
    shouldCancel: () => boolean
    onLine?: (line: string) => void
  },
): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const { deadlineMs, pollIntervalMs, shouldCancel, onLine } = opts
    const deadline = Date.now() + deadlineMs
    let buffer = ''

    const onStdout = (chunk: Buffer) => {
      buffer += chunk.toString('utf8')
      let nl: number
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl)
        buffer = buffer.slice(nl + 1)
        onLine?.(line)
        const port = parseFengyuPort(line)
        if (port !== null) {
          cleanup()
          resolve(port)
        }
      }
    }
    const onStdoutClose = () => {
      cleanup()
      reject(new Error('backend exited before reporting FENGYU_PORT'))
    }
    const poll = setInterval(() => {
      if (shouldCancel()) {
        cleanup()
        reject(new Error('backend startup cancelled'))
      } else if (Date.now() >= deadline) {
        cleanup()
        reject(new Error('backend did not report FENGYU_PORT within 30s'))
      }
    }, pollIntervalMs)
    const cleanup = () => {
      proc.stdout?.off('data', onStdout)
      proc.stdout?.off('end', onStdoutClose)
      clearInterval(poll)
    }
    proc.stdout?.on('data', onStdout)
    proc.stdout?.on('end', onStdoutClose)
  })
}
