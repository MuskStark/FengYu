import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'

const REDACT_KEYS = ['FENGYU_GITHUB_TOKEN', 'GITHUB_TOKEN', 'FENGYU_TOKEN', 'FENGYU_DB_PASSWORD', 'NPM_TOKEN', 'NODE_AUTH_TOKEN']
const MAX_DIAGNOSTIC = 4096

/**
 * Start a JSON-RPC 2.0 worker child process and return a client whose
 * `invoke` sends newline-delimited requests and awaits matching responses.
 *
 * For a Java worker the child is `java -jar <jar>`; for tests an alternative
 * executable (e.g. node) can be supplied via `java` + `javaArgs` with `jar=null`.
 *
 * @param {{ jar: string | null, java?: string, javaArgs?: string[], cwd?: string, onStderr?: (line: string) => void }} options
 * @returns {Promise<WorkerClient>}
 */
export async function startWorker({ jar, java = 'java', javaArgs = [], cwd, onStderr }) {
  const command = java
  const args = jar ? ['-jar', jar, ...javaArgs] : javaArgs
  const child = spawn(command, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] })

  const pending = new Map()
  let nextId = 1
  let exited = false

  const stdout = createInterface({ input: child.stdout })
  stdout.on('line', (line) => {
    let message
    try {
      message = JSON.parse(line)
    } catch {
      rejectAll(new Error('invalid JSON-RPC stdout: ' + truncate(line)))
      return
    }
    const id = String(message.id ?? '')
    const entry = pending.get(id)
    if (!entry) return
    pending.delete(id)
    clearTimeout(entry.timer)
    if (entry.signal && entry.abort) entry.signal.removeEventListener('abort', entry.abort)
    if (message.error) {
      entry.reject(new Error(`worker error ${message.error.code}: ${message.error.message}`))
    } else {
      entry.resolve(message.result)
    }
  })

  const stderrLines = []
  child.stderr.on('data', (chunk) => {
    const text = chunk.toString()
    for (const line of text.split(/\r?\n/)) {
      if (!line) continue
      const redacted = redact(line)
      stderrLines.push(redacted)
      onStderr?.(redacted)
    }
  })

  child.on('exit', (code, signal) => {
    exited = true
    rejectAll(new Error(`worker exited (code=${code}, signal=${signal})`))
  })

  function rejectAll(error) {
    for (const [, entry] of pending) {
      clearTimeout(entry.timer)
      if (entry.signal && entry.abort) entry.signal.removeEventListener('abort', entry.abort)
      entry.reject(error)
    }
    pending.clear()
  }

  return {
    invoke(method, params = {}, options = {}) {
      if (exited) return Promise.reject(new Error('worker has exited'))
      const id = String(nextId++)
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id)
          reject(new Error(`worker request timed out: ${method}`))
        }, options.timeoutMs ?? 30_000)
        const abort = options.signal ? () => {
          pending.delete(id)
          clearTimeout(timer)
          reject(new DOMException('Aborted', 'AbortError'))
        } : undefined
        if (options.signal && abort) {
          if (options.signal.aborted) { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); return }
          options.signal.addEventListener('abort', abort, { once: true })
        }
        pending.set(id, { resolve, reject, timer, signal: options.signal, abort })
        try {
          child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n')
        } catch (e) {
          pending.delete(id)
          clearTimeout(timer)
          reject(new Error(`failed to write to worker: ${e.message}`))
        }
      })
    },
    restart(newJar) {
      return Promise.reject(new Error('restart not implemented on this client; rebuild via dev'))
    },
    async close() {
      if (exited) return
      exited = true
      try { child.stdin.end() } catch { /* ignore */ }
      try { child.kill('SIGTERM') } catch { /* ignore */ }
      // Force-kill after a short grace so lingering children never keep the
      // event loop alive (tests rely on a prompt exit).
      setTimeout(() => { try { child.kill('SIGKILL') } catch { /* ignore */ } }, 500).unref?.()
    },
    child() { return child },
  }
}

function redact(line) {
  let out = line
  for (const key of REDACT_KEYS) {
    const re = new RegExp(`${key}=[^\\s;|&]+`, 'gi')
    out = out.replace(re, `${key}=***`)
  }
  return truncate(out)
}

function truncate(line) {
  return line.length > MAX_DIAGNOSTIC ? line.slice(0, MAX_DIAGNOSTIC) + '…' : line
}

/**
 * @typedef {Object} WorkerClient
 * @property {(method: string, params?: object, options?: { signal?: AbortSignal, timeoutMs?: number }) => Promise<unknown>} invoke
 * @property {(jar: string) => Promise<void>} restart
 * @property {() => Promise<void>} close
 * @property {() => import('node:child_process').ChildProcess} child
 */
