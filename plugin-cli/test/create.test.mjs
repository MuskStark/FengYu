import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createPlugin } from '../src/create.mjs'
import { validate } from '../src/manifest.mjs'

let base
let root

test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-create-'))
})

test.beforeEach(async () => {
  root = path.join(base, `plugin-${Date.now()}-${Math.random().toString(36).slice(2)}`)
})

test('create renders the activated Vue Codex template', async () => {
  const calls = []; const run = async (...args) => calls.push(args)
  await createPlugin(root, 'com.example.demo', { run })
  const pkg = JSON.parse(await fs.readFile(path.join(root, 'package.json'), 'utf8'))
  assert.equal(pkg.dependencies['@fengyu/plugin-ui'], '^1.0.0')
  assert.equal(pkg.dependencies.vuetify, '^3.9.3')
  assert.match(await fs.readFile(path.join(root, 'src/main.ts'), 'utf8'), /createFengYuVuetify/)
  assert.deepEqual(calls[0].slice(0, 2), ['npm', ['install']])
})

test('--no-install keeps a complete scaffold without invoking npm', async () => {
  const run = async () => assert.fail('runner must not execute')
  await createPlugin(root, 'com.example.demo', { install: false, run })
  assert.equal((await validate(root)).length, 1) // ui/index.html is produced by build
  assert.ok(await fs.stat(path.join(root, 'src/App.vue')))
})

test('install failure preserves generated files', async () => {
  await assert.rejects(() => createPlugin(root, 'com.example.demo', { run: async () => { throw new Error('npm failed') } }), /Scaffold created.*npm install/s)
  assert.ok(await fs.stat(path.join(root, 'manifest.json')))
})
