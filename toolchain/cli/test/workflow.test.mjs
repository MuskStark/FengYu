import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const ci = await fs.readFile(path.join(repo, '.github/workflows/toolchain-ci.yml'), 'utf8')
const release = await fs.readFile(path.join(repo, '.github/workflows/toolchain-release.yml'), 'utf8')

test('toolchain CI covers the active app branch and all tooling runtimes', () => {
  assert.match(ci, /branches: \[[^\]]*4\.0\.0[^\]]*\]/)
  assert.match(ci, /cd toolchain\/dev && yarn install --immutable && yarn test/)
  assert.match(ci, /\.\/mvnw -pl toolchain\/devkit-java -am test/)
  assert.match(ci, /scripts\/check-plugin-dependency-boundaries\.sh/)
})

test('toolchain release tests Java contracts and builds every official plugin', () => {
  assert.match(release, /\.\/mvnw -pl toolchain\/devkit-java -am install/)
  for (const plugin of ['markdown', 'excel', 'email', 'offlinepython']) {
    assert.match(
      release,
      new RegExp(`build OfficialPlugins/plugin-${plugin}`),
    )
  }
})
