import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { detectProject } from '../src/project.mjs'
import { checkPlugin } from '../src/check.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(__dirname, '../../..')

// The official plugins moved to the store repository; the committed smoke fixture
// (scripts/fixtures/smoke-plugin) is this repo's pinned CLI-project subject. It must
// satisfy the full project contract a third-party manifest-first project faces:
// schema v2 manifest, no legacy config files, and a clean `checkPlugin` pass.
const FIXTURE = path.resolve(repo, 'scripts/fixtures/smoke-plugin')

test('smoke fixture is a standard manifest-first CLI project that passes full check', async () => {
  const project = await detectProject(FIXTURE)
  assert.equal(project.kind, 'standard')
  assert.equal(project.manifestMode, 'manifest-first')
  // Static prebuilt UI: no package.json UI lifecycle, output is the committed ui/ dir.
  assert.equal(project.config.ui.root, null)
  assert.equal(project.config.ui.output, path.join(FIXTURE, 'ui'))
  // Conventional Java worker under worker/pom.xml.
  assert.equal(project.config.worker.runtime, 'java')
  assert.equal(project.config.worker.root, path.join(FIXTURE, 'worker'))

  const manifest = JSON.parse(await fs.readFile(path.join(FIXTURE, 'manifest.json'), 'utf8'))
  assert.equal(manifest.schemaVersion, 2, 'fixture must be schemaVersion 2')
  assert.equal(manifest.id, 'dev.fengyu.smoke')
  await assert.rejects(fs.stat(path.join(FIXTURE, 'fengyu.plugin.json')))
  await checkPlugin(FIXTURE) // throws on any manifest/drift/consistency regression
})

test('fixture file-ref inputs declare FengYu formats and access', async () => {
  const manifest = JSON.parse(await fs.readFile(path.join(FIXTURE, 'manifest.json'), 'utf8'))
  for (const [methodName, method] of Object.entries(manifest.rpc?.methods ?? {})) {
    for (const [propertyName, property] of Object.entries(method.inputSchema?.properties ?? {})) {
      if (!/fengyu\s+(file|directory)ref/i.test(property.description ?? '')) continue
      assert.ok(
        property.format === 'fengyu-file' || property.format === 'fengyu-directory',
        `smoke-plugin.${methodName}.${propertyName} must declare a FengYu file format`,
      )
      assert.ok(
        property['x-fengyu-file-access'] === 'read'
          || property['x-fengyu-file-access'] === 'read-write',
        `smoke-plugin.${methodName}.${propertyName} must declare file access`,
      )
    }
  }
})

test('the official plugins tree is gone from this repository', async () => {
  // The plugins are store-distributed and their sources live in the store repository —
  // this repo must never grow the tree back silently.
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins')))
})
