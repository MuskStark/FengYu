import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { detectProject } from '../src/project.mjs'
import { validateProjectManifest } from '../src/manifest.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(__dirname, '../..')

for (const name of ['markdown', 'excel', 'email']) {
  test(`official ${name} is a CLI project with a valid runtime manifest`, async () => {
    const root = path.resolve(repo, `OfficialPlugins/plugin-${name}`)
    assert.equal((await detectProject(root)).kind, 'declared')
    assert.equal((await validateProjectManifest(root)).length, 0)
    // The plugin-local manifest must exist (migrated out of packages/).
    assert.ok(await fs.stat(path.join(root, 'manifest.json')))
    assert.ok(await fs.stat(path.join(root, 'fengyu.plugin.json')))
  })
}

test('official shell packager is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', `build-${'packages'}.sh`)))
})

test('official source packages directory is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', 'pack' + 'ages')))
})
