import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPlugin } from '../src/create.mjs'
import { buildPlugin } from '../src/build.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const staticFixture = path.resolve(__dirname, 'fixtures/static-plugin')

let base
let root

// Each test gets a fresh, isolated Vue/Vite project root so the failure test's
// "no .fyp in dist-package" assertion isn't polluted by a prior test's archive.
test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-build-'))
})

test.beforeEach(async () => {
  root = path.join(base, `plugin-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  // Scaffold a Vue/Vite project with no install; detectProject() returns 'vue-vite'.
  // The per-test injected `run` simulates `npm run build` by writing ui/index.html.
  await createPlugin(root, 'com.example.demo', { install: false, run: async () => {} })
})

test.after(async () => {
  await fs.rm(base, { recursive: true, force: true }).catch(() => {})
})

test('Vue build runs before validation and packaging', async () => {
  const order = []
  const result = await buildPlugin(root, {
    run: async () => {
      order.push('frontend')
      await fs.mkdir(path.join(root, 'ui'), { recursive: true })
      await fs.writeFile(path.join(root, 'ui/index.html'), '<div id="app"></div>')
    },
    hooks: { onValidate: () => order.push('validate'), onPackage: () => order.push('package') },
  })
  assert.deepEqual(order, ['frontend', 'validate', 'package'])
  assert.ok(result.output.endsWith('.fyp'))
})

test('failed frontend build emits no fyp', async () => {
  await assert.rejects(
    () => buildPlugin(root, { run: async () => { throw Object.assign(new Error('vite failed'), { code: 2 }) } }),
    /vite failed/,
  )
  assert.deepEqual(
    (await fs.readdir(path.join(root, 'dist-package')).catch(() => [])).filter((x) => x.endsWith('.fyp')),
    [],
  )
})

test('legacy static plugin skips npm build', async () => {
  const out = path.join(base, `legacy-${Date.now()}.fyp`)
  await buildPlugin(staticFixture, { out, run: async () => assert.fail('must not run npm') })
  assert.ok(await fs.stat(out))
})
