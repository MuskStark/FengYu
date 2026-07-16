import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCli } from '../src/args.mjs'

test('option values are not project paths', () => {
  const parsed = parseCli(['plugin', 'build', '.', '--out', 'dist/custom.fyp', '--skip-tests'])
  assert.deepEqual(parsed.positionals, ['.'])
  assert.equal(parsed.options.out, 'dist/custom.fyp')
  assert.equal(parsed.options.skipTests, true)
})

test('create reads directory and id independently', () => {
  const parsed = parseCli(['plugin', 'create', 'demo', '--id', 'com.example.demo', '--ui-only'])
  assert.deepEqual(parsed.positionals, ['demo'])
  assert.equal(parsed.options.id, 'com.example.demo')
  assert.equal(parsed.options.uiOnly, true)
})

test('build defaults install flag to true', () => {
  const parsed = parseCli(['plugin', 'create', 'demo', '--id', 'com.example.demo'])
  assert.equal(parsed.options.install, true)
})

test('--no-install clears the install flag', () => {
  const parsed = parseCli(['plugin', 'create', 'demo', '--id', 'com.example.demo', '--no-install'])
  assert.equal(parsed.options.install, false)
})

test('group and command are split from positionals', () => {
  const parsed = parseCli(['plugin', 'dev', '.'])
  assert.equal(parsed.group, 'plugin')
  assert.equal(parsed.command, 'dev')
  assert.deepEqual(parsed.positionals, ['.'])
})

test('unknown options are rejected', () => {
  assert.throws(() => parseCli(['plugin', 'build', '.', '--bogus']), /unknown option --bogus/)
})

test('value options require a following value', () => {
  assert.throws(() => parseCli(['plugin', 'build', '.', '--out']), /--out requires a value/)
})
