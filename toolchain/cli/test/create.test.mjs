import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createPlugin } from '../src/create.mjs'
import { detectProject } from '../src/project.mjs'

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
  await assert.rejects(fs.stat(path.join(root, 'fengyu.plugin.json')))
  assert.ok(await fs.stat(path.join(root, 'worker/pom.xml')))
  assert.ok(await fs.stat(path.join(root, 'mvnw')))
  // v2 manifest: no backend.command string; a backend + rpc.methods.hello declares the worker.
  const manifest = await fs.readFile(path.join(root, 'manifest.json'), 'utf8')
  assert.match(manifest, /"schemaVersion":\s*2/)
  assert.match(manifest, /"rpc"[\s\S]*"hello"/)
  // init regenerates the typed RPC client + Java records from rpc.methods (T2-02 generator).
  assert.ok(await fs.stat(path.join(root, 'ui-src/src/generated/fengyu-rpc.ts')))
  assert.ok(await fs.stat(path.join(root, 'worker/src/main/java/com/example/hello_world/generated/PluginMethods.java')))
  assert.ok(await fs.stat(path.join(root, 'worker/src/main/java/com/example/hello_world/generated/HelloInput.java')))
  // Production entry delegates to the shared handler factory (HelloWorldWorker.create()).
  const javaDir = path.join(root, 'worker/src/main/java/com/example/hello_world')
  const workerMain = path.join(javaDir, 'HelloWorldWorkerMain.java')
  assert.match(await fs.readFile(workerMain, 'utf8'), /HelloWorldWorker\.create\(\)\.run\(\)/)
  // Handler registration lives in its own factory class, shared with the dev entry point.
  const workerFactory = path.join(javaDir, 'HelloWorldWorker.java')
  assert.match(await fs.readFile(workerFactory, 'utf8'), /new JsonRpcWorker\(\)/)
  // IDE-debug entry point is scaffolded under test sources (never in the shaded JAR).
  const devMain = path.join(root, 'worker/src/test/java/com/example/hello_world/PluginDevMain.java')
  assert.match(await fs.readFile(devMain, 'utf8'), /PluginDevServer\.builder/)
  // The devkit is declared test-scope so it stays out of the production JAR.
  assert.match(await fs.readFile(path.join(root, 'worker/pom.xml'), 'utf8'), /fengyu-plugin-devkit[\s\S]*<scope>test<\/scope>/)
  // New plugins inherit the official responsive page and progress contract.
  const app = await fs.readFile(path.join(root, 'ui-src/src/App.vue'), 'utf8')
  assert.match(app, /FyPluginShell/)
  assert.match(app, /FyPluginPage/)
  assert.match(app, /FyProgress/)
})

test('full scaffold preserves the Maven wrapper mode and resolves the worker artifact', async () => {
  await createPlugin(root, 'com.example.demo', { install: false, run: async () => {} })

  if (process.platform !== 'win32') {
    const mode = (await fs.stat(path.join(root, 'mvnw'))).mode & 0o777
    assert.equal(mode, 0o755)
  }

  const project = await detectProject(root)
  assert.equal(project.kind, 'standard')
  assert.equal(project.config.worker.root, path.join(root, 'worker'))
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
  const app = await fs.readFile(path.join(root, 'src/App.vue'), 'utf8')
  assert.match(app, /FyPluginShell/)
  assert.match(app, /FyPluginPage/)
  assert.match(app, /useFengYuNotify/)
})

test('uiOnly install runs npm in the project root', async () => {
  const calls = []
  await createPlugin(root, 'com.example.ui', { uiOnly: true, run: async (...args) => { calls.push(args) } })
  assert.equal(calls[0][2]?.cwd, root)
})

test('--no-install keeps a complete scaffold without invoking npm', async () => {
  const run = async () => assert.fail('runner must not execute')
  await createPlugin(root, 'com.example.demo', { install: false, run })
  await assert.rejects(fs.stat(path.join(root, 'fengyu.plugin.json')))
  assert.ok(await fs.stat(path.join(root, 'ui-src/src/App.vue')))
})

test('install failure preserves generated files', async () => {
  await assert.rejects(
    () => createPlugin(root, 'com.example.demo', { run: async () => { throw new Error('npm failed') } }),
    /Scaffold created.*npm install/s,
  )
  assert.ok(await fs.stat(path.join(root, 'manifest.json')))
})

test('rejects a plugin id that fails the canonical manifest pattern', async () => {
  // The manifest schema requires ^[a-z0-9]+(?:[.-][a-z0-9]+)+$. Validate BEFORE creating any files so
  // a half-baked project (bad Java package name, manifest that can't pass build) is never produced.
  await assert.rejects(
    () => createPlugin(root, 'Bad-ID-UPPER', { install: false }),
    /plugin id/i,
  )
  await assert.rejects(fs.stat(root)) // nothing was scaffolded
})

test('rejects a single-segment id (must have at least one separator)', async () => {
  await assert.rejects(() => createPlugin(root, 'demo', { install: false }), /plugin id/i)
  await assert.rejects(() => createPlugin(root, 'com.example.-leading-sep', { install: false }), /plugin id/i)
})
