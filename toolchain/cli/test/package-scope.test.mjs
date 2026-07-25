import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'

const readJson = async (url) => JSON.parse(await fs.readFile(url, 'utf8'))

test('published tooling and generated consumers use the @infinia scope', async () => {
  const cli = await readJson(new URL('../package.json', import.meta.url))
  const sdk = await readJson(new URL('../../sdk-ts/package.json', import.meta.url))
  const ui = await readJson(new URL('../../ui/package.json', import.meta.url))
  const template = await fs.readFile(new URL('../templates/vue-java/ui-src/package.json.tpl', import.meta.url), 'utf8')
  const workflow = await fs.readFile(new URL('../../../.github/workflows/toolchain-release.yml', import.meta.url), 'utf8')

  assert.equal(cli.name, '@infinia/plugin-cli')
  assert.equal(sdk.name, '@infinia/plugin-sdk')
  assert.equal(ui.name, '@infinia/plugin-ui')
  assert.equal(ui.peerDependencies['@infinia/plugin-sdk'], '^1.0.0')
  assert.match(template, /@infinia\/plugin-sdk/)
  assert.match(template, /@infinia\/plugin-ui/)
  assert.match(workflow, /npm view @infinia\/plugin-cli@\$VERSION/)
})
