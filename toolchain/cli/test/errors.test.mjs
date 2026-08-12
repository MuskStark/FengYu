import test from 'node:test'
import assert from 'node:assert/strict'
import { FengYuCliError, renderError, stripAnsi, isInteractive } from '../src/errors.mjs'

test('renderError formats a structured problem/file/fix block', () => {
  const out = renderError(new FengYuCliError('UI deps missing', { file: 'ui-src/package.json', fix: 'npm install' }))
  assert.match(out, /^fengyu: UI deps missing/)
  assert.match(out, /  file: ui-src\/package\.json/)
  assert.match(out, /  fix:  npm install/)
})

test('renderError renders a plain Error as just the problem line (no file/fix)', () => {
  const out = renderError(new Error('boom'))
  assert.equal(out, 'fengyu: boom')
  assert.doesNotMatch(out, /file:|fix:/)
})

test('renderError never emits ANSI color, even from an interactive-looking message', () => {
  const out = renderError(new FengYuCliError('\u001b[31mred problem\u001b[0m', { fix: '\u001b[32mgreen\u001b[0m' }))
  assert.equal(out.indexOf('\u001b'), -1, `expected no ANSI escapes, got: ${JSON.stringify(out)}`)
  assert.match(out, /red problem/)
})

test('stripAnsi removes color escapes', () => {
  assert.equal(stripAnsi('\u001b[1mhi\u001b[0m'), 'hi')
  assert.equal(stripAnsi('plain'), 'plain')
})

test('FengYuCliError is an Error carrying optional metadata', () => {
  const err = new FengYuCliError('p', { file: 'f', fix: 'x' })
  assert.ok(err instanceof Error)
  assert.equal(err.name, 'FengYuCliError')
  assert.equal(err.message, 'p')
  assert.equal(err.file, 'f')
  assert.equal(err.fix, 'x')
})

test('isInteractive is true only for a TTY without CI/NO_COLOR', () => {
  const ci = process.env.CI
  const noColor = process.env.NO_COLOR
  delete process.env.CI
  delete process.env.NO_COLOR
  try {
    assert.equal(isInteractive({ isTTY: true }), true)
    assert.equal(isInteractive({ isTTY: false }), false)
    assert.equal(isInteractive(undefined), false)
    process.env.CI = '1'
    assert.equal(isInteractive({ isTTY: true }), false, 'CI disables color')
    delete process.env.CI
    process.env.NO_COLOR = '1'
    assert.equal(isInteractive({ isTTY: true }), false, 'NO_COLOR disables color')
  } finally {
    if (ci === undefined) delete process.env.CI
    else process.env.CI = ci
    if (noColor === undefined) delete process.env.NO_COLOR
    else process.env.NO_COLOR = noColor
  }
})
