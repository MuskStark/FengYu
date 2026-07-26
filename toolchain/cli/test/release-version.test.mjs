import test from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { resolveToolingVersion, verifyRepositoryVersion } from '../scripts/resolve-tooling-version.mjs'

const execFileAsync = promisify(execFile)
const cliRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = path.resolve(cliRoot, '../..')
const toolingVersion = JSON.parse(await fs.readFile(path.join(cliRoot, 'package.json'), 'utf8')).version

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

test('rejects semantic versions with leading zeroes', () => {
  for (const version of ['01.0.0', '1.01.0', '1.0.01', '1.0.0-01']) {
    assert.throws(() => resolveToolingVersion({ inputVersion: version }), /semantic version/)
  }
})

test('verifies every repository tooling package from the repository root', async () => {
  await assert.doesNotReject(verifyRepositoryVersion(repoRoot, toolingVersion))
})

test('release resolver CLI writes the validated version', async () => {
  const output = path.join(await fs.mkdtemp(path.join(os.tmpdir(), 'fy-tooling-version-')), 'output')
  await execFileAsync(process.execPath, [
    path.join(cliRoot, 'scripts/resolve-tooling-version.mjs'),
    '--input', toolingVersion,
    '--ref', `plugin-tooling-v${toolingVersion}`,
    '--github-output', output,
  ])
  assert.equal(await fs.readFile(output, 'utf8'), `version=${toolingVersion}\n`)
})
