import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { loadBuildConfig, resolveInside } from '../src/config.mjs'

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-config-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

const validConfig = {
  schemaVersion: 1,
  ui: {
    root: 'ui-src', output: 'dist',
    prepare: [['npm', '--prefix', '../x', 'ci'], ['npm', '--prefix', '../x', 'run', 'build']],
    install: ['npm', 'ci'], test: ['npm', 'test'], build: ['npm', 'run', 'build'],
  },
  worker: {
    root: 'worker', test: ['maven', 'test'],
    build: ['maven', 'package', '-DskipTests'],
    artifact: 'target/worker.jar', mainClass: 'com.example.WorkerMain',
  },
  package: { outputDirectory: 'dist-package' },
}

async function writeConfig(root, config) {
  await fs.mkdir(path.join(root, 'ui-src'), { recursive: true })
  await fs.mkdir(path.join(root, 'worker/target'), { recursive: true })
  await fs.writeFile(path.join(root, 'fengyu.plugin.json'), JSON.stringify(config))
}

test('valid declared config normalizes commands and preserves prepare order', async () => {
  const root = path.join(base, 'valid')
  await writeConfig(root, validConfig)
  const cfg = await loadBuildConfig(root)
  assert.equal(cfg.schemaVersion, 1)
  assert.deepEqual(cfg.ui.prepare, [['npm', '--prefix', '../x', 'ci'], ['npm', '--prefix', '../x', 'run', 'build']])
  assert.deepEqual(cfg.ui.install, ['npm', 'ci'])
  assert.equal(cfg.worker.mainClass, 'com.example.WorkerMain')
  assert.deepEqual(cfg.package.resources, [])
})

test('ui.output escaping the root is rejected with the field path', async () => {
  const root = path.join(base, 'escape-ui')
  await writeConfig(root, { ...validConfig, ui: { ...validConfig.ui, output: '../escape' } })
  await assert.rejects(() => loadBuildConfig(root), /ui\.output.*escapes plugin root/)
})

test('package resource to escaping the root is rejected', async () => {
  const root = path.join(base, 'escape-pkg')
  await writeConfig(root, {
    ...validConfig,
    package: { outputDirectory: 'dist-package', resources: [{ from: 'assets', to: '../escape' }] },
  })
  await assert.rejects(() => loadBuildConfig(root), /package.*to.*escapes plugin root/)
})

test('package resource destinations remain archive-relative', async () => {
  const root = path.join(base, 'relative-pkg')
  await writeConfig(root, {
    ...validConfig,
    package: { outputDirectory: 'dist-package', resources: [{ from: 'assets', to: 'runtime-assets' }] },
  })
  await fs.mkdir(path.join(root, 'assets'), { recursive: true })
  const cfg = await loadBuildConfig(root)
  assert.equal(cfg.package.resources[0].to, 'runtime-assets')
})

for (const [name, unsafe] of [
  ['absolute', '/absolute'],
  ['parent', '../escape'],
  ['nested parent', 'a/../../escape'],
  ['drive letter', 'C:\\escape'],
  ['backslash', 'a\\b'],
]) {
  test(`rejects unsafe runtime resource destination: ${name}`, async () => {
    const root = path.join(base, `unsafe-pkg-${name.replace(/\s+/g, '-')}`)
    await writeConfig(root, {
      ...validConfig,
      package: { outputDirectory: 'dist-package', resources: [{ from: 'assets', to: unsafe }] },
    })
    await assert.rejects(() => loadBuildConfig(root), /package\.resources\[0\]\.to/)
  })
}

test('symlink that escapes the plugin root is rejected', async () => {
  const root = path.join(base, 'symlink')
  await writeConfig(root, validConfig)
  // ui-src/link -> ../../outside
  await fs.symlink(path.resolve(root, '../../outside'), path.join(root, 'ui-src/link'))
  await assert.rejects(() => resolveInside(root, 'ui-src/link', 'ui-src/link'), /escapes plugin root/)
})

test('loadBuildConfig returns null when no fengyu.plugin.json exists', async () => {
  const root = path.join(base, 'none')
  await fs.mkdir(root, { recursive: true })
  assert.equal(await loadBuildConfig(root), null)
})

test('empty command arrays are rejected', async () => {
  const root = path.join(base, 'empty-cmd')
  await writeConfig(root, { ...validConfig, ui: { ...validConfig.ui, install: [] } })
  await assert.rejects(() => loadBuildConfig(root), /ui\.install/)
})
