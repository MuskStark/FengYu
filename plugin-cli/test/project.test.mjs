import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createPlugin } from '../src/create.mjs'
import { detectProject } from '../src/project.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const staticFixture = path.resolve(__dirname, 'fixtures/static-plugin')

let base
let generatedRoot

test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-project-'))
  generatedRoot = path.join(base, `plugin-${Date.now()}`)
  // Scaffold a UI-only Vue/Vite project with no install; detection only needs the files.
  await createPlugin(generatedRoot, 'com.example.demo', { install: false, uiOnly: true, run: async () => {} })
})

test.after(async () => {
  await fs.rm(base, { recursive: true, force: true }).catch(() => {})
})

// detectProject was previously exercised by dev.test.mjs; with `fengyu plugin dev` removed,
// project classification is still load-bearing for `build` (declared/vue-vite/static dispatch),
// so the contract is kept here.
test('detectProject recognizes generated Vite and legacy static projects', async () => {
  assert.equal((await detectProject(generatedRoot)).kind, 'vue-vite')
  assert.equal((await detectProject(staticFixture)).kind, 'static')
})
