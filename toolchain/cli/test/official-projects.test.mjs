import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { detectProject } from '../src/project.mjs'
import { checkPlugin } from '../src/check.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(__dirname, '../../..')

// Every plugin that ships through OfficialPlugins must satisfy the CLI's full
// project contract: schema v2 manifest, generated-code drift, lockfile/version
// consistency. All four official plugins completed the T2-P1..P4 migration, so
// this is an unconditional gate (no transitional schemaVersion pinning remains).
for (const name of ['markdown', 'excel', 'email', 'offlinepython']) {
  test(`official ${name} is a standard CLI project that passes full check`, async () => {
    const root = path.resolve(repo, `OfficialPlugins/plugin-${name}`)
    assert.equal((await detectProject(root)).kind, 'standard')
    const manifest = JSON.parse(await fs.readFile(path.join(root, 'manifest.json'), 'utf8'))
    assert.equal(manifest.schemaVersion, 2, `official ${name} must be schemaVersion 2`)
    await assert.rejects(fs.stat(path.join(root, 'fengyu.plugin.json')))
    await checkPlugin(root) // throws on any manifest/drift/consistency regression
  })
}

test('official shell packager is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', `build-${'packages'}.sh`)))
})

test('official source packages directory is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', 'pack' + 'ages')))
})
