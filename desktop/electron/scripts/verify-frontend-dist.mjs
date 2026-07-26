#!/usr/bin/env node
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const file = path.resolve(process.argv[2] ?? 'frontend-dist/index.html')
const html = fs.readFileSync(file, 'utf8')

assert.doesNotMatch(
  html,
  /(?:src|href)="\/(?!\/)/,
  `${file} contains a root-relative asset URL that cannot load through file://`,
)
assert.match(
  html,
  /"vue"\s*:\s*"\.\/vendor\/vue\.esm-browser\.prod\.js"/,
  `${file} must resolve the shared Vue module relative to index.html`,
)
assert.match(
  html,
  /(?:src|href)="\.\/assets\//,
  `${file} does not contain a relative Vite asset URL`,
)

console.log(`Electron frontend asset paths verified: ${file}`)
