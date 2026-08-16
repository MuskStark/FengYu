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
  assert.equal(ui.peerDependencies['@infinia/plugin-sdk'], '^2.0.0')
  assert.match(template, /@infinia\/plugin-sdk/)
  assert.match(template, /@infinia\/plugin-ui/)
  // The consumer-smoke job must re-resolve the freshly-published CLI (and plugin-dev) from the
  // registry before scaffolding — no file: deps. The resolve gate is now a two-package loop
  // (@infinia/plugin-cli @infinia/plugin-dev), so assert both the loop covers the CLI by name and
  // that it gates on `npm view` against the version variable.
  assert.match(workflow, /for pkg in [^\n]*@infinia\/plugin-cli[^\n]*@infinia\/plugin-dev/,
    'consumer-smoke must wait on @infinia/plugin-cli (and plugin-dev) to resolve from the registry')
  assert.match(workflow, /npm view "\$pkg@\$VERSION"/,
    'consumer-smoke must gate on npm view of the published version, not a file: dep')
})
