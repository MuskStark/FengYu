// Contract test for the library build output: one ES bundle, one prebuilt
// stylesheet (the Tailwind library build), and type declarations.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const dist = resolve(import.meta.dirname, '..', 'dist')

test('dist/index.js exists as the ES entry', () => {
  assert.ok(existsSync(resolve(dist, 'index.js')), 'dist/index.js missing')
})

test('dist/index.d.ts exists', () => {
  assert.ok(existsSync(resolve(dist, 'index.d.ts')), 'dist/index.d.ts missing')
})

test('dist/plugin-ui.css exists and carries the Infinia tokens', () => {
  const cssPath = resolve(dist, 'plugin-ui.css')
  assert.ok(existsSync(cssPath), 'dist/plugin-ui.css missing')
  const css = readFileSync(cssPath, 'utf8')
  assert.match(css, /--c-gold:\s*#eab04b/, 'light gold token missing')
  assert.match(css, /--c-input-border-focused:\s*#885400/, 'light focused input border token missing')
  assert.match(css, /--c-input-border-focused:\s*#f6bd60/, 'dark focused input border token missing')
  assert.match(css, /\.dark\s*\{/, 'dark palette block missing')
  assert.match(css, /infinia-active-pill/, 'selection signature rule missing')
})

test('bundle does not inline react or the SDK', () => {
  const bundle = readFileSync(resolve(dist, 'index.js'), 'utf8')
  assert.match(bundle, /from\s*"react"/, 'react should stay external')
  assert.match(bundle, /@infinia\/plugin-sdk/, 'SDK should stay external')
})
