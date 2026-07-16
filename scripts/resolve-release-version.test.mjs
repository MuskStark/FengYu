import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveReleaseVersion } from './resolve-release-version.mjs'

for (const [tag, version, prerelease] of [
  ['v4.0.0', '4.0.0', false],
  ['v4.0.0-alpha.1', '4.0.0-alpha.1', true],
  ['v4.0.0-beta.2', '4.0.0-beta.2', true],
  ['v4.0.0-rc.3', '4.0.0-rc.3', true],
]) {
  test(`resolves ${tag}`, () => {
    assert.deepEqual(resolveReleaseVersion(tag), {
      tag, version, appVersion: '4.0.0', prerelease,
    })
  })
}

for (const tag of ['4.0.0', 'v4', 'v4.0.0-alpha', 'v4.0.0-preview.1']) {
  test(`rejects ${tag}`, () => assert.throws(() => resolveReleaseVersion(tag), /Invalid release tag/))
}
