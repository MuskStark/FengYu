import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'
import net from 'node:net'

/**
 * Dev-only frontend (Vite) launcher. Mirrors what the old Tauri shell did via
 * `beforeDevCommand: "cd ../frontend && npm run dev"`: spawn the Vite dev server and wait until
 * it's listening before the BrowserWindow tries to load `http://localhost:5173`.
 *
 * Idempotent: if Vite is already reachable, no process is spawned. The returned stop() function
 * terminates the spawned Vite (a no-op when nothing was spawned).
 */

export interface StartDevFrontendOptions {
  /** Absolute path to the repo root (where `frontend/` lives). */
  repoRoot: string
  /** Vite port (default 5173). */
  port?: number
  /** Per-attempt TCP connect timeout + overall deadline (ms). */
  deadlineMs?: number
  /** logger.info sink (optional). */
  log?: (msg: string) => void
  /** Returns true once the app is genuinely quitting — suppresses the unexpected-exit warning. */
  isQuitting?: () => boolean
}

export interface DevFrontendHandle {
  /** The spawned Vite ChildProcess, or null when one was already running. */
  process: ChildProcess | null
  /** Kill the spawned Vite (no-op if none was spawned). */
  stop(): void
}

/**
 * True when something is listening on `port`. Vite 6 defaults to an IPv6 `localhost (::1)` bind,
 * so we probe BOTH `127.0.0.1` (IPv4) and `localhost` (resolves to either family) — a single
 * success means it's up.
 */
export function isPortListening(port: number): Promise<boolean> {
  const probe = (host: string) =>
    new Promise<boolean>((resolve) => {
      const sock = net.connect({ port, host })
      sock.once('connect', () => {
        sock.destroy()
        resolve(true)
      })
      sock.once('error', () => {
        sock.destroy()
        resolve(false)
      })
    })
  return Promise.all([probe('127.0.0.1'), probe('localhost')]).then(([v4, lh]) => v4 || lh)
}

/**
 * Spawn `npm run dev` in `frontend/` and wait until Vite is listening on `port`. If Vite is
 * already up, returns immediately with process=null. Resolves once ready; rejects on timeout
 * or spawn failure.
 */
export async function startDevFrontend(opts: StartDevFrontendOptions): Promise<DevFrontendHandle> {
  const { repoRoot, port = 5173, deadlineMs = 60_000, log = console.log, isQuitting = () => false } = opts
  const frontendDir = join(repoRoot, 'frontend')

  // Already running? Don't double-spawn.
  if (await isPortListening(port)) {
    log(`[desktop] dev frontend already running on :${port} (not spawning)`)
    return { process: null, stop: () => {} }
  }

  if (!existsSync(join(frontendDir, 'package.json'))) {
    throw new Error(`frontend not found at ${frontendDir} (expected repo root with a frontend/ dir)`)
  }

  log(`[desktop] dev: starting Vite frontend (vite in ${frontendDir})`)
  // Spawn the `vite` binary directly (NOT `npm run dev`). Going through npm makes vite a grandchild
  // of the shell, which is fragile: when the npm wrapper exits (it sometimes does after handing off,
  // or on certain signals), the vite grandchild can be orphaned or killed, leaving the window's SPA
  // unable to lazy-load route modules ("Failed to fetch dynamically imported module") while the shell
  // keeps running. Spawning vite directly keeps it a direct child we fully control.
  //
  // NOT detached: as a plain direct child, vite stays alive for the shell's whole lifetime (fixing
  // the mid-session death) AND is automatically cleaned up when the shell exits — on macOS/Linux the
  // child gets SIGHUP when its parent dies; on Windows it dies with the parent process. This means
  // even a SIGKILL of the shell (which bypasses before-quit/will-quit JS handlers) still takes vite
  // down, which a detached process group would NOT. The stop() in will-quit is the graceful path
  // (flush + clean exit); the parent-death path is the backstop for forceful kills.
  //
  // --strictPort: fail hard if :5173 is taken instead of silently moving to another port (the shell
  // waits specifically for :5173 and create-window loads :5173, so a silent port move would leave
  // the window loading a dead URL).
  // --host 127.0.0.1: force an IPv4 loopback bind. Vite 6 otherwise defaults to IPv6 localhost (::1),
  // which the shell's readiness probe and the BrowserWindow's `http://127.0.0.1:5173` load can miss.
  const viteBin = join(frontendDir, 'node_modules', '.bin', 'vite')
  const cmd = existsSync(viteBin) ? viteBin : 'vite'
  const child = spawn(
    cmd,
    ['--host', '127.0.0.1', '--port', String(port), '--strictPort'],
    {
      cwd: frontendDir,
      stdio: ['ignore', 'pipe', 'pipe'],
      shell: process.platform === 'win32',
    },
  )
  child.stdout?.on('data', (d) => log(`[vite] ${d.toString().trimEnd()}`))
  child.stderr?.on('data', (d) => log(`[vite] ${d.toString().trimEnd()}`))
  child.once('exit', (code, signal) => {
    if (!isQuitting()) {
      log(`[desktop] WARNING: dev frontend (vite) exited unexpectedly (code=${code} signal=${signal}). ` +
        'Lazy-loaded routes will fail. Restart the shell or run `npm run dev` in frontend/ manually.')
    }
  })

  // Wait for Vite to bind. Abort fast if the npm process dies first.
  const deadline = Date.now() + deadlineMs
  await new Promise<void>((resolve, reject) => {
    const onExit = (code: number | null) => {
      cleanup()
      reject(new Error(`frontend (npm run dev) exited with code ${code} before binding :${port}`))
    }
    const poll = setInterval(async () => {
      if (await isPortListening(port)) {
        cleanup()
        log(`[desktop] dev frontend ready on :${port}`)
        resolve()
      } else if (Date.now() >= deadline) {
        cleanup()
        reject(new Error(`frontend (Vite) did not bind :${port} within ${deadlineMs}ms`))
      }
    }, 300)
    const cleanup = () => {
      clearInterval(poll)
      child.off('exit', onExit)
    }
    child.once('exit', onExit)
  })

  return {
    process: child,
    stop: () => {
      if (child.killed || !child.pid) return
      // Graceful SIGTERM so vite can flush. As a non-detached direct child, vite is ALSO reaped
      // automatically when the shell dies (SIGHUP on POSIX, parent-death on Windows), which covers
      // the forceful-kill path that bypasses these JS handlers.
      try {
        child.kill('SIGTERM')
      } catch {
        /* already gone */
      }
    },
  }
}
