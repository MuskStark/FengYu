import test from 'node:test'
import assert from 'node:assert/strict'
import { once } from 'node:events'
import os from 'node:os'
import path from 'node:path'
import { startWorker } from '../src/worker.mjs'

/** A Node fixture child that speaks newline JSON-RPC over stdio. */
function fixtureScript() {
  return `
    import { createInterface } from 'node:readline'
    const rl = createInterface({ input: process.stdin })
    rl.on('line', (line) => {
      let req
      try { req = JSON.parse(line) } catch { process.stderr.write('not-json\\n'); return }
      if (req.method === 'never') {
        return
      } else if (req.method === 'hello') {
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, result: { message: 'Hello, ' + (req.params?.name ?? '') } }) + '\\n')
      } else if (req.method === 'fail') {
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, error: { code: -32000, message: 'boom' } }) + '\\n')
      } else if (req.method === 'noise') {
        process.stdout.write('this is not json\\n')
      } else {
        process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, error: { code: -32601, message: 'unknown' } }) + '\\n')
      }
    })
    process.stdin.on('end', () => process.exit(0))
  `
}

async function startFixture() {
  return startWorker({
    jar: null,
    java: process.execPath,
    javaArgs: ['-e', fixtureScript()],
    cwd: process.cwd(),
  })
}

test('invoke returns the worker result', async () => {
  const client = await startFixture()
  try {
    const result = await client.invoke('hello', { name: 'Ada' })
    assert.deepEqual(result, { message: 'Hello, Ada' })
  } finally {
    await client.close()
  }
})

test('json-rpc error rejects with the code', async () => {
  const client = await startFixture()
  try {
    await assert.rejects(() => client.invoke('fail', {}), /worker error -32000/)
  } finally {
    await client.close()
  }
})

test('non-json stdout rejects as invalid JSON-RPC', async () => {
  const client = await startFixture()
  try {
    await assert.rejects(() => client.invoke('noise', {}), /invalid JSON-RPC stdout/)
  } finally {
    await client.close()
  }
})

test('abort cancels a pending request', async () => {
  const client = await startFixture()
  try {
    const controller = new AbortController()
    const p = client.invoke('hello', { name: 'Ada' }, { signal: controller.signal })
    controller.abort()
    await assert.rejects(p, /Aborted|abort/i)
  } finally {
    await client.close()
  }
})

test('close terminates the child exactly once', async () => {
  const client = await startFixture()
  let kills = 0
  const child = client.child()
  const origKill = child.kill.bind(child)
  child.kill = (sig) => { kills++; return origKill(sig) }
  await client.close()
  await client.close()
  assert.equal(kills, 1)
})

test('close waits until a SIGTERM-resistant child has exited', async () => {
  let ready
  const readyLine = new Promise((resolve) => { ready = resolve })
  const client = await startWorker({
    jar: null,
    java: process.execPath,
    javaArgs: ['-e', "process.on('SIGTERM', () => {}); process.stderr.write('ready\\n'); setInterval(() => {}, 1000)"],
    onStderr: (line) => { if (line === 'ready') ready() },
  })
  await readyLine
  const child = client.child()
  const started = Date.now()
  await client.close()
  assert.ok(Date.now() - started >= 400)
  assert.notEqual(child.signalCode, null)
  assert.throws(() => process.kill(child.pid, 0), /ESRCH/)
})

test('child exit rejects pending requests', async () => {
  const client = await startFixture()
  // Send a request, then kill the child before it can answer. The pending
  // request must reject with an exit/error, never hang or resolve.
  const p = client.invoke('hello', { name: 'Ada' })
  client.child().kill('SIGKILL')
  await assert.rejects(p, /exited|closed|EOF/i)
  // Ensure the client is cleaned up.
  await client.close().catch(() => {})
})

test('startWorker rejects when the executable cannot spawn', async () => {
  await assert.rejects(
    () => startWorker({ jar: 'missing.jar', java: path.join(os.tmpdir(), 'missing-fengyu-java') }),
    /ENOENT|spawn/,
  )
})

test('timeout removes the abort listener', async () => {
  const client = await startFixture()
  const controller = new AbortController()
  let removes = 0
  const remove = controller.signal.removeEventListener.bind(controller.signal)
  controller.signal.removeEventListener = (...args) => { removes++; return remove(...args) }
  try {
    await assert.rejects(
      () => client.invoke('never', {}, { signal: controller.signal, timeoutMs: 5 }),
      /timed out/,
    )
    assert.equal(removes, 1)
  } finally {
    await client.close()
  }
})
