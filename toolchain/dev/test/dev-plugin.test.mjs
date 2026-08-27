import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { promises as fs } from 'node:fs'
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

// G5: every value baked into the shell's inline <script> must go through safeJson — a
// crafted manifest (or iframe src) containing `</script>` would otherwise close the tag
// and inject markup into the dev page.
test('simulatorHtml: escapes every inline-script interpolation against tag breakout', () => {
  const html = simulatorHtml({
    iframeSrc: '/"></iframe><script>alert("iframe")</script>',
    manifest: { id: '</script><script>alert("manifest")</script>' },
  })
  assert.ok(!html.includes('alert("iframe")'), 'iframeSrc must be escaped')
  assert.ok(!html.includes('alert("manifest")'), 'manifest and derived env must be escaped')
  assert.match(html, /\\u003c/)
})

test('simulatorHtml: contains iframe with sandbox and the postMessage bridge', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: { id: 'com.example.x' } })
  assert.match(html, /<iframe[^>]*sandbox="allow-scripts allow-forms allow-downloads allow-same-origin"/)
  assert.match(html, /f\.src=iframeSrc/)
  assert.match(html, /hostSource/)
  // Protocol version comes from the shared PROTOCOL_VERSION constant (T2-05 → 3.0.0), never a
  // hardcoded literal. If this fails, the simulator is shipping a stale protocol version.
  assert.match(html, /3\.0\.0/)
  assert.doesNotMatch(html, /"protocolVersion":"2\./)
  assert.match(html, /fetch\('\/__fengyu\/rpc'/)
  assert.match(html, /\/__fengyu\/ref/)
  assert.match(html, /type=['"]file['"]/)
  assert.match(html, /files\.workspaceDirectory/)
  assert.match(html, /files\.export/)
  assert.match(html, /com\.example\.x/)
})

test('simulatorHtml: mock host.ready environment mirrors the production HostEnvironment shape', () => {
  // The simulator's env must carry every field the production PluginView.vue handshake emits
  // (post-T2-05): protocolVersion, pluginId, pluginVersion, permissions, theme, locale,
  // platform, capabilities — built from the shared constants + parsed manifest, not hardcoded.
  const html = simulatorHtml({
    iframeSrc: '/',
    manifest: { id: 'com.example.x', version: '1.2.3', permissions: ['files.read', 'files.write'] },
  })
  // The env object is serialized into the browser script as JSON; assert its full shape.
  const envMatch = html.match(/const env=(\{.*?\});/)
  assert.ok(envMatch, 'env object should be present in the simulator script')
  const env = JSON.parse(envMatch[1])
  assert.equal(env.protocolVersion, '3.0.0')
  assert.equal(env.pluginId, 'com.example.x')
  assert.equal(env.pluginVersion, '1.2.3')
  assert.deepEqual(env.permissions, ['files.read', 'files.write'])
  assert.equal(env.theme, 'dark')
  assert.equal(env.locale, 'en')
  assert.equal(env.platform, 'web')
  assert.ok(Array.isArray(env.capabilities) && env.capabilities.includes('rpc.invoke'))
})

test('simulatorHtml: defaults pluginId/pluginVersion/permissions when the manifest omits them', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: { id: 'com.example.x' } })
  const env = JSON.parse(html.match(/const env=(\{.*?\});/)[1])
  assert.equal(env.pluginId, 'com.example.x')
  assert.equal(env.pluginVersion, '0.0.0-dev')
  assert.deepEqual(env.permissions, [])
})

test('simulatorHtml: handles null manifest without producing NaN/undefined artifacts', () => {
  const html = simulatorHtml({ iframeSrc: '/', manifest: null })
  assert.doesNotMatch(html, /NaN|undefinedundefined/)
  assert.match(html, /<iframe/)
  const env = JSON.parse(html.match(/const env=(\{.*?\});/)[1])
  assert.equal(env.pluginId, 'dev-plugin')
  assert.deepEqual(env.permissions, [])
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
      const buf = Buffer.isBuffer(init.body)
        ? init.body
        : Buffer.from(typeof init.body === 'string' ? init.body : JSON.stringify(init.body))
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

test('fengyuPluginDev: a missing generated manifest is an actionable error, not dev-plugin defaults', async () => {
  await withDevServer({ manifest: './missing-generated-manifest.json', mockWorker: true }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu')
    assert.equal(res.status, 500)
    assert.match(res.json().error, /cannot load generated plugin manifest/)
    assert.match(res.json().error, /fengyu generate/)
    assert.doesNotMatch(res.body, /dev-plugin/)
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

test('fengyuPluginDev: browser file upload returns a ref that resolves to the uploaded snapshot', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const upload = await server.middlewares.handleDirect('/__fengyu/files/upload?name=data.csv', {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: Buffer.from('name,value\nAda,42\n'),
    })
    assert.equal(upload.status, 200)
    const ref = upload.json()
    assert.equal(ref.name, 'data.csv')
    assert.equal(ref.kind, 'file')
    assert.equal(ref.access, 'read')
    assert.equal(ref.size, 18)

    const rpc = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'upload-rpc', method: 'read', params: { file: ref } },
    })
    const snapshot = rpc.json().result.params.file
    assert.equal(await fs.readFile(snapshot, 'utf8'), 'name,value\nAda,42\n')
  })
})

test('fengyuPluginDev: directory upload preserves relative paths and supports read-write workspaces', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const start = await server.middlewares.handleDirect('/__fengyu/files/directory/start', {
      method: 'POST',
      body: { name: 'project', access: 'read-write' },
    })
    const { uploadId } = start.json()

    const file = await server.middlewares.handleDirect(
      `/__fengyu/files/directory/file?uploadId=${encodeURIComponent(uploadId)}&path=src%2Fmain.txt`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/octet-stream' },
        body: Buffer.from('hello workspace'),
      },
    )
    assert.equal(file.status, 204)

    const finish = await server.middlewares.handleDirect('/__fengyu/files/directory/finish', {
      method: 'POST',
      body: { uploadId },
    })
    const ref = finish.json()
    assert.equal(ref.name, 'project')
    assert.equal(ref.kind, 'directory')
    assert.equal(ref.access, 'read-write')

    const rpc = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'dir-rpc', method: 'open', params: { directory: ref } },
    })
    const directory = rpc.json().result.params.directory
    assert.equal(await fs.readFile(`${directory}/src/main.txt`, 'utf8'), 'hello workspace')
  })
})

test('fengyuPluginDev: directory upload rejects path traversal', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const start = await server.middlewares.handleDirect('/__fengyu/files/directory/start', {
      method: 'POST',
      body: { name: 'project', access: 'read' },
    })
    const { uploadId } = start.json()
    const file = await server.middlewares.handleDirect(
      `/__fengyu/files/directory/file?uploadId=${encodeURIComponent(uploadId)}&path=..%2Fsecret.txt`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/octet-stream' },
        body: Buffer.from('nope'),
      },
    )
    assert.equal(file.status, 400)
    assert.match(file.json().error, /relative path/)
  })
})

test('fengyuPluginDev: output directory can be exported as a zip download', async () => {
  await withDevServer({ manifest: {}, mockWorker: true }, async (server) => {
    const output = await server.middlewares.handleDirect('/__fengyu/files/output', { method: 'POST' })
    const ref = output.json()
    assert.equal(ref.kind, 'directory')
    assert.equal(ref.access, 'write')

    const rpc = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'output-rpc', method: 'write', params: { directory: ref } },
    })
    const directory = rpc.json().result.params.directory
    await fs.writeFile(`${directory}/result.txt`, 'done')

    const exported = await server.middlewares.handleDirect(`/__fengyu/files/export/${encodeURIComponent(ref.id)}`)
    assert.equal(exported.status, 200)
    assert.match(exported.headers['content-type'] ?? '', /application\/zip/)
    assert.match(exported.headers['content-disposition'] ?? '', /plugin-output\.zip/)
    assert.equal(Buffer.from(exported.body, 'binary').subarray(0, 2).toString(), 'PK')
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

test('fengyuPluginDev: explicit unreachable worker surfaces an error and never returns devMock', async () => {
  const unavailable = net.createServer()
  await new Promise((resolve) => unavailable.listen(0, '127.0.0.1', resolve))
  const address = unavailable.address()
  const port = typeof address === 'object' && address ? address.port : 0
  await new Promise((resolve, reject) => unavailable.close((err) => err ? reject(err) : resolve()))

  await withDevServer({ manifest: {}, workerEndpoint: { host: '127.0.0.1', port } }, async (server) => {
    const res = await server.middlewares.handleDirect('/__fengyu/rpc', {
      method: 'POST',
      body: { id: 'real-worker', method: 'ping', params: {} },
    })
    assert.equal(res.status, 200)
    const json = res.json()
    assert.equal(json.id, 'real-worker')
    assert.equal(json.result, undefined)
    assert.match(json.error, /dev worker unavailable/)
    assert.doesNotMatch(res.body, /devMock/)
  })
})
