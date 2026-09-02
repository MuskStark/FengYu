#!/usr/bin/env node
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
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

// The baked CSP must actually admit the inline scripts. The import map is the
// only inline script in the build; when its 'sha256-…' token does not match the
// script content, Chromium blocks the map, `vue` fails to resolve, and the
// packaged app white-screens (the 4.0.0-rc.1 Windows defect: the builder hashed
// raw CRLF bytes, which Chromium normalizes to LF before applying the hash).
const cspTag = html.match(/<meta[^>]*http-equiv="Content-Security-Policy"[^>]*>/)?.[0] ?? ''
assert.ok(cspTag, `${file} must bake a Content-Security-Policy meta tag`)
const allowedHashes = new Set(
  [...cspTag.matchAll(/'sha256-([A-Za-z0-9+/=]+)'/g)].map((m) => m[1]),
)
assert.ok(
  allowedHashes.size > 0,
  `${file}: script-src carries no 'sha256-…' token for the inline import map`,
)

// Hash inline scripts the way Chromium does: after normalizing CRLF/CR to LF.
const inlineScripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)].filter(
  ([, attrs]) => !/\bsrc\s*=/.test(attrs),
)
assert.ok(
  inlineScripts.length > 0,
  `${file}: expected the shared-Vue import map as an inline script`,
)
for (const [tag, , body] of inlineScripts) {
  const hash = createHash('sha256').update(body.replace(/\r\n?/g, '\n')).digest('base64')
  assert.ok(
    allowedHashes.has(hash),
    `${file}: no CSP 'sha256-${hash}' token for ${tag} — Chromium would block it ` +
      `and white-screen the app`,
  )
}

console.log(`Electron frontend asset paths + CSP hashes verified: ${file}`)
