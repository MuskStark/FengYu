import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createPlugin } from '../src/create.mjs'

let base
let root

test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-create-'))
})

test.beforeEach(async () => {
  root = path.join(base, `plugin-${Date.now()}-${Math.random().toString(36).slice(2)}`)
})

test.after(async () => {
  await fs.rm(base, { recursive: true, force: true }).catch(() => {})
})

test('create defaults to Vue plus Java worker', async () => {
  const calls = []
  const run = async (...args) => calls.push(args)
  await createPlugin(root, 'com.example.hello-world', { install: false, run })
  assert.ok(await fs.stat(path.join(root, 'fengyu.plugin.json')))
  assert.ok(await fs.stat(path.join(root, 'worker/pom.xml')))
  assert.ok(await fs.stat(path.join(root, 'mvnw')))
  assert.match(await fs.readFile(path.join(root, 'manifest.json'), 'utf8'), /backend\/worker\.jar/)
  const workerMain = path.join(root, 'worker/src/main/java/com/example/hello_world/HelloWorldWorkerMain.java')
  assert.match(await fs.readFile(workerMain, 'utf8'), /new JsonRpcWorker/)
})

test('full template install runs npm inside ui-src', async () => {
  const calls = []
  await createPlugin(root, 'com.example.demo', { run: async (...args) => { calls.push(args) } })
  assert.deepEqual(calls[0].slice(0, 2), ['npm', ['install']])
  assert.equal(calls[0][2]?.cwd, path.join(root, 'ui-src'))
})

test('uiOnly retains the lightweight template', async () => {
  await createPlugin(root, 'com.example.ui', { install: false, uiOnly: true, run: async () => {} })
  await assert.rejects(fs.stat(path.join(root, 'worker/pom.xml')))
  assert.ok(await fs.stat(path.join(root, 'src/App.vue')))
})

test('uiOnly install runs npm in the project root', async () => {
  const calls = []
  await createPlugin(root, 'com.example.ui', { uiOnly: true, run: async (...args) => { calls.push(args) } })
  assert.equal(calls[0][2]?.cwd, root)
})

test('--no-install keeps a complete scaffold without invoking npm', async () => {
  const run = async () => assert.fail('runner must not execute')
  await createPlugin(root, 'com.example.demo', { install: false, run })
  assert.ok(await fs.stat(path.join(root, 'fengyu.plugin.json')))
  assert.ok(await fs.stat(path.join(root, 'ui-src/src/App.vue')))
})

test('install failure preserves generated files', async () => {
  await assert.rejects(
    () => createPlugin(root, 'com.example.demo', { run: async () => { throw new Error('npm failed') } }),
    /Scaffold created.*npm install/s,
  )
  assert.ok(await fs.stat(path.join(root, 'manifest.json')))
})
