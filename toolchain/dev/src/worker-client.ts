import net from 'node:net'
import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

/**
 * A minimal newline-delimited JSON-RPC 2.0 client that connects to a loopback TCP server
 * (fengyu-plugin-devkit's `PluginDevServer`). One line per request, one line per response,
 * matched by `id`.
 *
 * This is the development twin of the production stdio client that used to live in
 * `plugin-cli/src/worker.mjs`. It speaks the SAME JSON-RPC framing; only the transport differs
 * (TCP vs the worker's stdin/stdout). The dev server drives the worker's
 * `JsonRpcWorker.serve(LineFramedSocketTransport)` loop, so handler breakpoints fire directly
 * under the IDE — no JDWP remote attach.
 *
 * The connection is lazy and self-healing: a failed connect (dev server not started yet) is
 * surfaced as a per-call rejection so the simulator can expose the real failure. Each call opens
 * a fresh socket; this keeps the protocol stateless and lets the dev server's per-connection
 * virtual threads clean up naturally.
 */
export interface WorkerClientOptions {
  host: string
  port: number
  /** Per-call timeout. Defaults to 30s, matching the production host's callTimeoutSeconds range. */
  timeoutMs?: number
}

export interface WorkerClient {
  invoke(method: string, params: Record<string, unknown>, options?: { timeoutMs?: number }): Promise<unknown>
}

export function createWorkerClient(options: WorkerClientOptions): WorkerClient {
  const defaultTimeout = options.timeoutMs ?? 30_000
  return {
    invoke(method, params, callOptions = {}) {
      return invokeOnce({
        host: options.host,
        port: options.port,
        method,
        params,
        timeoutMs: callOptions.timeoutMs ?? defaultTimeout,
      })
    },
  }
}

interface InvokeOnceArgs {
  host: string
  port: number
  method: string
  params: Record<string, unknown>
  timeoutMs: number
}

/**
 * The per-session token PluginDevServer writes to ~/.fengyu/dev-token-<port> on every start.
 * Every connection must lead with `AUTH <token>` — loopback binding alone left the dev RPC
 * surface open to any local process. Undefined when no token file exists (pre-auth devkit);
 * connections then skip the handshake and rely on the server being an older build.
 */
function devTokenFor(port: number): string | undefined {
  try {
    return readFileSync(join(homedir(), '.fengyu', `dev-token-${port}`), 'utf8').trim()
  } catch {
    return undefined
  }
}

function invokeOnce(args: InvokeOnceArgs): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const id = Math.random().toString(36).slice(2)
    let settled = false
    let timer: NodeJS.Timeout | undefined
    const socket = net.createConnection({ host: args.host, port: args.port })

    const cleanup = () => {
      if (timer) clearTimeout(timer)
      socket.removeAllListeners()
      socket.destroy()
    }
    const fail = (error: Error) => {
      if (settled) return
      settled = true
      cleanup()
      reject(error)
    }
    const done = (value: unknown) => {
      if (settled) return
      settled = true
      cleanup()
      resolve(value)
    }

    timer = setTimeout(() => fail(new Error(`dev worker request timed out: ${args.method}`)), args.timeoutMs)

    socket.once('error', (err) => fail(new Error(`dev worker connect failed (${args.host}:${args.port}): ${err.message}. Start PluginDevMain in your IDE, or set mockWorker:true to stub responses.`)))
    socket.once('connect', () => {
      const token = devTokenFor(args.port)
      if (token) socket.write(`AUTH ${token}\n`)
      socket.write(JSON.stringify({ jsonrpc: '2.0', id, method: args.method, params: args.params }) + '\n')
    })

    let buffer = ''
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      const newline = buffer.indexOf('\n')
      if (newline === -1) return
      const line = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 1)
      let message: { id?: string; result?: unknown; error?: { code: number; message: string } }
      try {
        message = JSON.parse(line)
      } catch {
        fail(new Error('dev worker returned a non-JSON line: ' + line.slice(0, 200)))
        return
      }
      if (message.id !== id) return // not our response; ignore (shouldn't happen on a fresh socket)
      if (message.error) fail(new Error(`worker error ${message.error.code}: ${message.error.message}`))
      else done(message.result)
    })
  })
}

/**
 * Probe whether a dev server is reachable. Resolves true on a successful TCP connect,
 * false otherwise. Cheap (opens + immediately closes a socket), used to decide whether to
 * report a precise connection error on the first request.
 */
export function probeWorker(host: string, port: number, timeoutMs = 500): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host, port })
    const timer = setTimeout(() => {
      socket.destroy()
      resolve(false)
    }, timeoutMs)
    socket.once('connect', () => {
      clearTimeout(timer)
      socket.destroy()
      resolve(true)
    })
    socket.once('error', () => {
      clearTimeout(timer)
      socket.destroy()
      resolve(false)
    })
  })
}
