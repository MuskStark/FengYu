import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveToolingVersion } from '../scripts/resolve-tooling-version.mjs'

test('resolves a tooling tag to a semantic version', () => {
  assert.equal(resolveToolingVersion({ refName: 'plugin-tooling-v1.0.0' }), '1.0.0')
})

test('manual input takes precedence', () => {
  assert.equal(resolveToolingVersion({ inputVersion: '1.0.1', refName: 'plugin-tooling-v1.0.0' }), '1.0.1')
})

test('rejects unrelated or malformed tags', () => {
  assert.throws(() => resolveToolingVersion({ refName: 'v1.0.0' }), /plugin-tooling-v/)
  assert.throws(() => resolveToolingVersion({ refName: 'plugin-tooling-vlatest' }), /semantic version/)
})
