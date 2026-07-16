import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveFrontendVersion } from '../build/release-version.mjs'

test('uses package version outside release CI', () => {
  assert.equal(resolveFrontendVersion('4.0.0', {}), '4.0.0')
})
test('uses release version in CI', () => {
  assert.equal(resolveFrontendVersion('4.0.0', { FENGYU_RELEASE_VERSION: '4.0.0-alpha.1' }), '4.0.0-alpha.1')
})
test('rejects malformed release override', () => {
  assert.throws(() => resolveFrontendVersion('4.0.0', { FENGYU_RELEASE_VERSION: 'alpha' }), /Invalid/)
})
