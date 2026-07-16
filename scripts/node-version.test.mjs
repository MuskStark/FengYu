import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const expected = '24.18.0'
const workflows = [
  '.github/workflows/fengyu-release.yml',
  '.github/workflows/plugin-tooling-release.yml',
  '.github/workflows/plugin-tooling.yml',
  '.github/workflows/docs.yml',
]

for (const path of workflows) {
  test(`${path} pins Node ${expected}`, () => {
    const source = readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
    const versions = [...source.matchAll(/node-version:\s*['"]?([^,\s}'"]+)/g)].map(
      (match) => match[1],
    )
    assert.ok(versions.length > 0, `No node-version entries found in ${path}`)
    assert.deepEqual([...new Set(versions)], [expected])
  })
}

test('plugin-cli requires the pinned Node baseline', () => {
  const pkg = JSON.parse(
    readFileSync(new URL('../plugin-cli/package.json', import.meta.url), 'utf8'),
  )
  assert.equal(pkg.engines.node, `>=${expected}`)
})

test('README documents the pinned Node baseline', () => {
  const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8')
  assert.match(readme, /Node 24\.18\.0/)
})
