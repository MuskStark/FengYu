import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'

test('build uses the package-local TypeScript compiler', async () => {
  const pkg = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.scripts.build, 'tsc -p tsconfig.json')
  assert.match(pkg.devDependencies.typescript, /^\^?5\./)
  assert.doesNotMatch(JSON.stringify(pkg.scripts), /frontend\/node_modules/)
})
