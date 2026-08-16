import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const expected = '24.18.0'
const workflowDir = fileURLToPath(new URL('../.github/workflows/', import.meta.url))
const workflows = readdirSync(workflowDir)
  .filter((name) => /\.ya?ml$/.test(name))
  .sort()

function yamlSteps(source) {
  const lines = source.split(/\r?\n/)
  const steps = []
  for (let start = 0; start < lines.length; start += 1) {
    const match = /^(\s*)-\s+/.exec(lines[start])
    if (!match) continue
    const indent = match[1].length
    let end = start + 1
    while (end < lines.length) {
      const next = /^(\s*)-\s+/.exec(lines[end])
      if (next && next[1].length <= indent) break
      end += 1
    }
    steps.push(lines.slice(start, end).join('\n'))
  }
  return steps
}

test(`all GitHub Actions setup-node steps pin Node ${expected}`, () => {
  const setupSteps = []
  for (const name of workflows) {
    const source = readFileSync(`${workflowDir}/${name}`, 'utf8')
    for (const step of yamlSteps(source)) {
      if (step.includes('uses: actions/setup-node@')) setupSteps.push({ name, step })
    }
  }

  assert.ok(setupSteps.length > 0, 'No actions/setup-node steps found')
  for (const { name, step } of setupSteps) {
    const versions = [...step.matchAll(/node-version:\s*['"]?([^,\s}'"]+)/g)].map(
      (match) => match[1],
    )
    assert.deepEqual(versions, [expected], `${name} has an unpinned setup-node step:\n${step}`)
  }
})

test('plugin-cli package requires the pinned Node baseline', () => {
  const pkg = JSON.parse(
    readFileSync(new URL('../toolchain/cli/package.json', import.meta.url), 'utf8'),
  )
  // The npm lockfile mirrored engines.node; yarn.lock does not, so package.json is the pin.
  assert.equal(pkg.engines.node, `>=${expected}`)
})

test('README documents the pinned Node baseline', () => {
  const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8')
  assert.match(readme, /Node 24\.18\.0/)
})
