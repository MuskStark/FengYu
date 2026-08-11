import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCli } from '../src/args.mjs'

test('parses the flat build command without treating option values as paths', () => {
  const parsed = parseCli(['build', '.', '--out', 'dist/custom.fyp', '--skip-tests'])
  assert.equal(parsed.command, 'build')
  assert.deepEqual(parsed.positionals, ['.'])
  assert.equal(parsed.options.out, 'dist/custom.fyp')
  assert.equal(parsed.options.skipTests, true)
})

test('parses init options', () => {
  const parsed = parseCli(['init', 'demo', '--id', 'com.example.demo', '--ui-only', '--no-install'])
  assert.equal(parsed.command, 'init')
  assert.deepEqual(parsed.positionals, ['demo'])
  assert.equal(parsed.options.id, 'com.example.demo')
  assert.equal(parsed.options.uiOnly, true)
  assert.equal(parsed.options.install, false)
})

test('unknown and valueless options are rejected', () => {
  assert.throws(() => parseCli(['build', '--bogus']), /unknown option/)
  assert.throws(() => parseCli(['build', '--out', '--skip-tests']), /requires a value/)
})
