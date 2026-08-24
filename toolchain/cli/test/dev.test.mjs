import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { devPlugin } from '../src/dev.mjs'
import { createPlugin } from '../src/create.mjs'
import { runCommand } from '../src/commands.mjs'

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-dev-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

async function scaffold({ uiPkg = {}, nodeModules = false } = {}) {
  const dir = path.join(base, `d-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  await fs.mkdir(path.join(dir, 'ui-src'), { recursive: true })
  await fs.writeFile(
    path.join(dir, 'manifest.json'),
    JSON.stringify({
      schemaVersion: 2, id: 'com.example.d', name: 'd', description: 'd', version: '1.0.0',
      author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
    }),
  )
  await fs.writeFile(
    path.join(dir, 'ui-src/package.json'),
    JSON.stringify({ scripts: { dev: 'vite' }, dependencies: {}, ...uiPkg }),
  )
  if (nodeModules) await fs.mkdir(path.join(dir, 'ui-src/node_modules'), { recursive: true })
  return dir
}

/** A run() that records whether it was reached; the diagnostic must throw first. */
function mustNotRun() {
  return async () => { throw new Error('run() should not be reached when deps are missing') }
}

test('dev surfaces an actionable diagnostic when UI deps are not installed', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-ui': '^1.3.0' } },
    nodeModules: false,
  })
  await assert.rejects(
    () => devPlugin(dir, { run: mustNotRun() }),
    (err) => /not installed/i.test(err.message) && /npm install/.test(err.fix),
  )
})

test('dev proceeds to npm run dev once dependencies are present', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-ui': '^1.3.0' } },
    nodeModules: true,
  })
  let called = false
  await devPlugin(dir, {
    run: async (command, args, options) => {
      called = true
      assert.equal(command, 'npm')
      assert.deepEqual(args, ['run', 'dev'])
      assert.equal(options.cwd, path.join(dir, 'ui-src'))
      return { code: 0 }
    },
  })
  assert.equal(called, true)
})

test('code-first dev generates the manifest consumed by Vite before starting it', async () => {
  const dir = path.join(base, `python-${Date.now()}`)
  await createPlugin(dir, 'com.example.python-dev', { install: false, runtime: 'python' })
  await fs.mkdir(path.join(dir, 'ui-src/node_modules'), { recursive: true })
  let viteStarted = false
  await devPlugin(dir, {
    run: async (command, args, options) => {
      if (command === 'python3') return runCommand(command, args, options)
      assert.equal(command, 'npm')
      assert.deepEqual(args, ['run', 'dev'])
      viteStarted = true
      return { code: 0 }
    },
  })
  const manifestPath = path.join(dir, 'target/fengyu-manifest/manifest.json')
  const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
  assert.ok(manifest.rpc.methods.hello)
  assert.equal(manifest.rpc.methods.hello.inputSchema.properties.name.type, 'string')
  assert.match(await fs.readFile(path.join(dir, 'ui-src/vite.config.ts'), 'utf8'),
    /target\/fengyu-manifest\/manifest\.json/)
  assert.equal(viteStarted, true)
})

test('dev requires a Vite UI source tree', async () => {
  const dir = path.join(base, `empty-${Date.now()}`)
  await fs.mkdir(dir, { recursive: true })
  await assert.rejects(
    () => devPlugin(dir, { run: mustNotRun() }),
    (err) => /ui-src\/package\.json/i.test(err.message),
  )
})
