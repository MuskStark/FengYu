import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPlugin } from '../src/create.mjs'
import { detectProject, dev } from '../src/dev.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const staticFixture = path.resolve(__dirname, 'fixtures/static-plugin')

let base
let generatedRoot

test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-dev-'))
  generatedRoot = path.join(base, `plugin-${Date.now()}`)
  // Scaffold a UI-only Vue/Vite project with no install; detection only needs the files.
  await createPlugin(generatedRoot, 'com.example.demo', { install: false, uiOnly: true, run: async () => {} })
})

test.after(async () => {
  await fs.rm(base, { recursive: true, force: true }).catch(() => {})
})

test('detectProject recognizes generated Vite and legacy static projects', async () => {
  assert.equal((await detectProject(generatedRoot)).kind, 'vue-vite')
  assert.equal((await detectProject(staticFixture)).kind, 'static')
})

test('Vue dev starts the project dev script and points the simulator iframe at it', async () => {
  const calls = []
  const server = await dev(generatedRoot, 4173, {
    run: async (...args) => calls.push(args),
    uiPort: 5173,
    open: false,
  })
  try {
    assert.deepEqual(calls[0].slice(0, 2), ['npm', ['run', 'dev', '--', '--host', '127.0.0.1', '--port', '5173']])
    const html = await fetch('http://127.0.0.1:4173/__fengyu').then((r) => r.text())
    assert.match(html, /http:\/\/127\.0\.0\.1:5173/)
  } finally {
    await server.close()
  }
})

test('static dev serves the simulator pointing at the manifest UI entry', async () => {
  const server = await dev(staticFixture, 4174, { open: false })
  try {
    const html = await fetch('http://127.0.0.1:4174/__fengyu').then((r) => r.text())
    // The static simulator iframe points at the manifest ui.entry path.
    assert.match(html, /\/ui\/index\.html/)
    // The production-shaped host.ready environment is embedded for the simulator to send.
    assert.match(html, /host\.ready/)
    assert.match(html, /files\.open/)
  } finally {
    await server.close()
  }
})

/** Build a declared worker project whose artifact already exists (no build). */
async function makeDeclaredWorkerProject() {
  const root = path.join(base, `worker-${Date.now()}`)
  await fs.mkdir(root, { recursive: true })
  await fs.writeFile(path.join(root, 'manifest.json'), JSON.stringify({
    schemaVersion: 1, id: 'com.example.worker', name: 'Worker', version: '1.0.0',
    ui: { entry: 'ui/index.html' },
    backend: { command: 'java -jar backend/worker.jar', protocol: 'json-rpc-2.0' },
  }))
  await fs.writeFile(path.join(root, 'fengyu.plugin.json'), JSON.stringify({
    schemaVersion: 1,
    ui: { root: 'ui-src', output: 'dist', install: ['npm', 'ci'], test: ['npm', 'test'], build: ['npm', 'run', 'build'] },
    worker: { root: 'worker', test: ['maven', 'test'], build: ['maven', 'package', '-DskipTests'], artifact: 'worker/worker.jar', mainClass: 'com.example.WorkerMain' },
    package: { outputDirectory: 'dist-package' },
  }))
  // Artifact exists so dev skips the initial build.
  await fs.mkdir(path.join(root, 'worker'), { recursive: true })
  await fs.writeFile(path.join(root, 'worker/worker.jar'), Buffer.from('PK'))
  await fs.writeFile(path.join(root, 'worker/mvnw'), '#!/bin/sh\n', { mode: 0o755 })
  return root
}

test('declared worker dev forwards rpc.invoke to the worker via /__rpc', async () => {
  const root = await makeDeclaredWorkerProject()
  const invoked = []
  const fakeClient = {
    invoke: async (method, params) => {
      invoked.push({ method, params })
      if (method === 'hello') return { message: 'Hello, ' + (params.name ?? '') }
      throw new Error('unknown method')
    },
    close: async () => {},
  }
  const server = await dev(root, 4180, {
    startWorkerImpl: async () => fakeClient,
    run: async () => { throw new Error('build must not run; artifact exists') },
    open: false,
  })
  try {
    const html = await fetch('http://127.0.0.1:4180/__fengyu').then((r) => r.text())
    assert.match(html, /\/__rpc/)
    const res = await fetch('http://127.0.0.1:4180/__rpc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: '1', method: 'hello', params: { name: 'Ada' } }),
    })
    const json = await res.json()
    assert.deepEqual(json, { id: '1', result: { message: 'Hello, Ada' } })
    assert.deepEqual(invoked, [{ method: 'hello', params: { name: 'Ada' } }])
  } finally {
    await server.close()
  }
})

test('declared dev rejects RPC while rebuilding and closes a replacement created during shutdown', async () => {
  const root = await makeDeclaredWorkerProject()
  let releaseBuild
  const buildStarted = new Promise((resolve) => {
    releaseBuild = { started: resolve }
  })
  let finishBuild
  const buildGate = new Promise((resolve) => { finishBuild = resolve })
  const clients = []
  const startWorkerImpl = async () => {
    const client = {
      closeCalls: 0,
      invoke: async () => ({ ok: true }),
      close: async () => { client.closeCalls++ },
    }
    clients.push(client)
    return client
  }
  const server = await dev(root, 4181, {
    startWorkerImpl,
    run: async () => { releaseBuild.started(); await buildGate },
    open: false,
  })
  let closing
  try {
    await fs.writeFile(path.join(root, 'worker/Changed.java'), 'class Changed {}')
    await Promise.race([
      buildStarted,
      new Promise((_, reject) => {
        const timer = setTimeout(() => reject(new Error('rebuild did not start')), 3000)
        timer.unref?.()
      }),
    ])

    const rpc = await fetch('http://127.0.0.1:4181/__rpc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: '1', method: 'hello', params: {} }),
    }).then((response) => response.json())
    assert.match(rpc.error, /worker rebuilding/)

    closing = server.close()
    finishBuild()
    await closing
    assert.equal(clients.length, 2)
    assert.equal(clients.every((client) => client.closeCalls === 1), true)
  } finally {
    finishBuild()
    if (!closing) await server.close()
  }
})

test('declared dev queues exactly one follow-up rebuild for changes during a build', async () => {
  const root = await makeDeclaredWorkerProject()
  const buildReleases = []
  const buildStarts = []
  let buildCount = 0
  const clients = []
  const server = await dev(root, 4182, {
    startWorkerImpl: async () => {
      const client = {
        closeCalls: 0,
        invoke: async () => ({ ok: true }),
        close: async () => { client.closeCalls++ },
      }
      clients.push(client)
      return client
    },
    run: async () => {
      const index = buildCount++
      buildStarts[index]?.()
      await new Promise((resolve) => { buildReleases[index] = resolve })
    },
    open: false,
  })
  try {
    const firstStarted = new Promise((resolve) => { buildStarts[0] = resolve })
    await fs.writeFile(path.join(root, 'worker/First.java'), 'class First {}')
    await Promise.race([
      firstStarted,
      new Promise((_, reject) => setTimeout(() => reject(new Error('first rebuild did not start')), 3000)),
    ])

    const secondStarted = new Promise((resolve) => { buildStarts[1] = resolve })
    await fs.writeFile(path.join(root, 'worker/Second.java'), 'class Second {}')
    await new Promise((resolve) => setTimeout(resolve, 400))
    buildReleases[0]()
    await Promise.race([
      secondStarted,
      new Promise((_, reject) => setTimeout(() => reject(new Error('follow-up rebuild did not start')), 3000)),
    ])
    buildReleases[1]()

    await new Promise((resolve) => setTimeout(resolve, 100))
    assert.equal(buildCount, 2)
  } finally {
    for (const release of buildReleases) release?.()
    await server.close()
  }
  assert.equal(clients.length, 3)
  assert.equal(clients.every((client) => client.closeCalls === 1), true)
})
