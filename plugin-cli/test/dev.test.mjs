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
  // Scaffold a Vue/Vite project with no install; detection only needs the files.
  await createPlugin(generatedRoot, 'com.example.demo', { install: false, run: async () => {} })
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
