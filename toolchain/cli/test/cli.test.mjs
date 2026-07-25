import test from 'node:test'
import assert from 'node:assert/strict'
import { main } from '../src/cli.mjs'

/**
 * Regression (P2-3): an unknown subcommand (e.g. `fengyu plugin frobnicate`) used to print usage
 * and exit 0, so a typo in CI / a release script / an IDE task would look like success. Only an
 * explicit --help/-h is a successful no-op; missing group/command and unknown commands must throw
 * (the bin wrapper maps a thrown error to a non-zero process exit code).
 */
test('unknown subcommand throws instead of exiting 0', async () => {
  await assert.rejects(() => main(['plugin', 'frobnicate', '.']), /unknown command/i)
})

test('missing group throws', async () => {
  await assert.rejects(() => main([]), /usage|command/i)
})

test('non-plugin group throws', async () => {
  await assert.rejects(() => main(['bogus', 'whatever']), /usage|command/i)
})

test('explicit --help resolves (success) and prints usage', async () => {
  // --help anywhere on the line is a successful no-op that prints usage.
  await main(['plugin', '--help'])
  await main(['plugin', 'build', '--help'])
})
