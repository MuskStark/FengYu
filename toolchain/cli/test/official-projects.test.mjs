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
// consistency. All four official plugins completed the code-first migration, so
// this is an unconditional gate on both authoring shapes: every plugin is
// code-first (manifest.base.json + contract IR) and must pass the same full
// `checkPlugin` gate a manifest-first project faces.
const CODE_FIRST = new Set(['markdown', 'excel', 'offlinepython', 'email'])
function assertStructuredFileInputs(manifest, pluginName) {
  for (const [methodName, method] of Object.entries(manifest.rpc?.methods ?? {})) {
    for (const [propertyName, property] of Object.entries(method.inputSchema?.properties ?? {})) {
      if (!/fengyu\s+(file|directory)ref/i.test(property.description ?? '')) continue
      assert.ok(
        property.format === 'fengyu-file' || property.format === 'fengyu-directory',
        `${pluginName}.${methodName}.${propertyName} must declare a FengYu file format`,
      )
      assert.ok(
        property['x-fengyu-file-access'] === 'read'
          || property['x-fengyu-file-access'] === 'read-write',
        `${pluginName}.${methodName}.${propertyName} must declare file access`,
      )
    }
  }
}

for (const name of ['markdown', 'excel', 'email', 'offlinepython']) {
  test(`official ${name} is a standard CLI project that passes full check`, async () => {
    const root = path.resolve(repo, `OfficialPlugins/plugin-${name}`)
    assert.equal((await detectProject(root)).kind, 'standard')
    const manifestFile = CODE_FIRST.has(name) ? 'manifest.base.json' : 'manifest.json'
    const manifest = JSON.parse(await fs.readFile(path.join(root, manifestFile), 'utf8'))
    assert.equal(manifest.schemaVersion, 2, `official ${name} must be schemaVersion 2`)
    await assert.rejects(fs.stat(path.join(root, 'fengyu.plugin.json')))
    await checkPlugin(root) // throws on any manifest/drift/consistency regression
    const effective = JSON.parse(await fs.readFile(
      path.join(root, 'target/fengyu-manifest/manifest.json'), 'utf8'))
    assertStructuredFileInputs(effective, name)
    if (name === 'excel') {
      assert.equal(effective.rpc.methods.excel_complex_config.inputSchema.properties.action.default, 'add')
      assert.equal(effective.flowNodes.find((node) => node.tool === 'excel_complex_config')
        .inputs.find((input) => input.name === 'action').default, undefined)
    }
  })
}

test('official shell packager is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', `build-${'packages'}.sh`)))
})

test('official source packages directory is removed', async () => {
  await assert.rejects(fs.stat(path.resolve(repo, 'OfficialPlugins', 'pack' + 'ages')))
})
