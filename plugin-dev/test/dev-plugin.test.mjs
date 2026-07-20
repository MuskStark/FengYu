import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { FileRefRegistry } from '../dist/file-refs.js'
import { simulatorHtml } from '../dist/simulator-html.js'
import { fengyuPluginDev } from '../dist/index.js'
import { createServer } from 'vite'

// ---------- FileRefRegistry: path registration + recursive resolution ----------

test('FileRefRegistry: register and resolve a file ref to its path', () => {
  const refs = new FileRefRegistry()
  const ref = refs.register('/abs/path/to/data.csv', 'file', 'read')
  assert.equal(ref.kind, 'file')
  assert.equal(ref.name, 'data.csv')
  assert.equal(ref.access, 'read')
  assert.equal(ref.id.startsWith('ref_'), true)
  // Resolve a payload that carries the ref by id
  const out = refs.resolve({ file: ref, other: 'keep' })
  assert.equal(out.file, '/abs/path/to/data.csv')
  assert.equal(out.other, 'keep')
})

test('FileRefRegistry: nested arrays and objects are recursed', () => {
  const refs = new FileRefRegistry()
  const a = refs.register('/in/dir', 'directory', 'read')
  const b = refs.register('/out/file.txt', 'file', 'write')
  const out = refs.resolve({ items: [a, { nested: b }], untouched: 42 })
  assert.deepEqual(out, { items: ['/in/dir', { nested: '/out/file.txt' }], untouched: 42 })
})

test('FileRefRegistry: ref-looking object without registration is left intact', () => {
  const refs = new FileRefRegistry()
  const orphan = { id: 'ref_orphan', kind: 'file', name: 'x' }
  const out = refs.resolve({ file: orphan })
  assert.deepEqual(out.file, orphan)
})

test('FileRefRegistry: empty path is rejected', () => {
  const refs = new FileRefRegistry()
  assert.throws(() => refs.register('   ', 'file', 'read'), /path is required/)
})

// ---------- simulatorHtml: structural assertions ----------

test('simulatorHtml: contains iframe with sandbox and the postMessage bridge', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: { id: 'com.example.x' } })
  assert.match(html, /<iframe[^>]*sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"/)
  assert.match(html, /f\.src=iframeSrc/)
  assert.match(html, /source:'fengyu-host'/)
  assert.match(html, /fetch\('\/__fengyu\/rpc'/)
  assert.match(html, /\/__fengyu\/ref/)
  assert.match(html, /com\.example\.x/)
})

test('simulatorHtml: handles null manifest without producing NaN/undefined artifacts', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: null })
  assert.doesNotMatch(html, /NaN|undefinedundefined/)
  assert.match(html, /<iframe/)
})

// ---------- fengyuPluginDev: Vite middleware end-to-end (mock mode) ----------

/**
 * Drive Vite's connect middleware stack directly (no port binding). Returns a minimal Response
 * shape with status, headers, body, and a json() helper.
 */
function handleDirect(server) {
  return (path, init = {}) => new Promise((resolve, reject) => {
    const socket = new net.Socket()
    const req = new http.IncomingMessage(socket)
    req.url = path
    req.method = init.method ?? 'GET'
    req.headers = { 'content-type': 'application/json', ...(init.headers ?? {}) }
    if (init.body !== undefined && init.body !== null) {
      const buf = Buffer.from(typeof init.body === 'string' ? init.body : JSON.stringify(init.body))
      // IncomingMessage needs the body pushed before 'end' fires; emulate a complete request.
      req.push(buf)
      req.push(null)
    } else {
      req.push(null)
    }
    const res = new http.ServerResponse(req)
    const chunks = []
    res.write = (chunk) => { chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)); return true }
    const finish = () => {
      const body = Buffer.concat(chunks).toString('utf8')
      resolve({
        status: res.statusCode,
        headers: res.getHeaders(),
        body,
        json() { try { return JSON.parse(body) } catch { return null } },
      })
    }
    res.end = (chunk) => {
      if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
      finish()
    }
    server.middlewares.handle(req, res, () => resolve({ status: 404, headers: {}, body: '', json() { return null } }))
  })
}

async function withDevServer(options, fn) {
  const server = await createServer({
    root: process.cwd(),
    logLevel: 'error',
    server: { middlewareMode: true },
    plugins: [fengyuPluginDev(options)],
  })
  server.middlewares.handleDirect = handleDirect(server)
  try {
    await fn(server)
  } finally {
    await server.close()
  }
}

test('fengyuPluginDev: GET /__fengyu returns the simulator HTML', async () => {
  await withDevServer({ manifest: { id: 'com.example.test' }, mockWorker: true }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu')
    assert.equal(res.status, 200)
    assert.match(res.headers['content-type'] ?? '', /text\/html/)
    assert.match(res.body, /<iframe/)
    assert.match(res.body, /com\.example\.test/)
  })
})

test('fengyuPluginDev: POST /__fengyu/rpc with mockWorker returns devMock envelope', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'req-1', method: 'hello', params: { name: 'Ada' } },
    })
    assert.equal(res.status, 200)
    const json = res.json()
    assert.equal(json.id, 'req-1')
    assert.equal(json.result.devMock, true)
    assert.equal(json.result.method, 'hello')
    assert.deepEqual(json.result.params, { name: 'Ada' })
  })
})

test('fengyuPluginDev: FileRef round-trip — register then rpc.invoke carries the resolved path', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const refRes = await server.middlewares.handleDirect('/__fengyu/ref', {
      method: 'POST',
      body: { path: '/abs/data.csv', kind: 'file', access: 'read' },
    })
    assert.equal(refRes.status, 200)
    const ref = refRes.json()
    assert.equal(ref.kind, 'file')
    assert.equal(ref.name, 'data.csv')

    const rpcRes = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'r1', method: 'process', params: { file: ref } },
    })
    const rpc = rpcRes.json()
    assert.equal(rpc.result.params.file, '/abs/data.csv')
    assert.equal(rpc.result.devMock, true)
  })
})

test('fengyuPluginDev: /__fengyu/ref rejects empty path', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu/ref', {
      method: 'POST',
      body: { path: '   ', kind: 'file' },
    })
    assert.equal(res.status, 400)
    assert.match(res.json().error, /path is required/)
  })
})

test('fengyuPluginDev: unknown path falls through to Vite (404 from next())', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const res = await server.middlewares.handleDirect('/some/random/path')
    assert.equal(res.status, 404)
  })
})

test('fengyuPluginDev: no workerEndpoint defaults to mock mode (UI-only)', async () => {
  await withDevServer({ manifest: {} }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: '1', method: 'ping', params: {} },
    })
    assert.equal(res.status, 200)
    assert.equal(res.json().result.devMock, true)
  })
})
