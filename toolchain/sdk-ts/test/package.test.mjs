import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'

test('build uses the package-local TypeScript compiler', async () => {
  const pkg = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'))
  assert.equal(pkg.scripts.build, 'tsc -p tsconfig.json')
  // The contract is a pinned caret range on a maintained TypeScript major (5.x, 6.x, …) —
  // not one hardcoded major, which broke the toolchain's own gate when the dependency moved
  // from ^5 to ^6 without this test noticing anything but the version bump itself.
  assert.match(pkg.devDependencies.typescript, /^\^\d+\.\d+\.\d+$/)
  assert.doesNotMatch(JSON.stringify(pkg.scripts), /frontend\/node_modules/)
})
