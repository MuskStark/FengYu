import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'

test('local consumer credentials do not leak into tooling package tests', async () => {
  const script = await fs.readFile(new URL('../../../scripts/plugin-tooling-local-smoke.sh', import.meta.url), 'utf8')
  assert.doesNotMatch(script, /^export (?:FENGYU_GITHUB_TOKEN|GITHUB_ACTOR)=/m)
  assert.match(
    script,
    /FENGYU_GITHUB_TOKEN="local-smoke-placeholder" GITHUB_ACTOR="fengyu-local-smoke" \\\n+\s+npm exec .* fengyu build \./,
  )
})
