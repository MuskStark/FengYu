import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'

/**
 * Docs-side style guard — mirrors the frontend's discipline (AGENTS.md
 * "Public styles only"): component styling lives in the sheets under
 * src/styles/, no static inline style objects in components, colors are
 * defined only in the foundation layer, and the gitignored Aceternity tree
 * stays registered with Tailwind.
 */

const SRC = path.join(import.meta.dirname, '..', 'src')

async function walk(dir, predicate, found = []) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    // 'aceternity' is vendored upstream source (exempt by the house rule);
    // '.content' is generated JSON.
    if (entry.isDirectory() && (entry.name === 'aceternity' || entry.name === '.content')) continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) await walk(full, predicate, found)
    else if (predicate(full)) found.push(full)
  }
  return found
}

/** Extract every `style={{...}}` block via balanced-brace scan (frontend style-guard port). */
function extractStyleBlocks(source) {
  const blocks = []
  const re = /style=\{\{/g
  let match
  while ((match = re.exec(source))) {
    const start = match.index + match[0].length - 1
    let depth = 1
    let i = start + 1
    let inString = null
    while (i < source.length && depth > 0) {
      const c = source[i]
      if (inString) {
        if (c === '\\') i += 1
        else if (c === inString) inString = null
      } else if (c === "'" || c === '"' || c === '`') inString = c
      else if (c === '{') depth += 1
      else if (c === '}') depth -= 1
      i += 1
    }
    blocks.push(source.slice(start + 1, i - 1))
  }
  return blocks
}

function splitTopLevel(text) {
  const parts = []
  let depth = 0
  let inString = null
  let current = ''
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i]
    if (inString) {
      current += c
      if (c === '\\') current += text[++i] ?? ''
      else if (c === inString) inString = null
      continue
    }
    if (c === "'" || c === '"' || c === '`') {
      inString = c
      current += c
      continue
    }
    if (c === '(' || c === '{' || c === '[') depth += 1
    if (c === ')' || c === '}' || c === ']') depth -= 1
    if (c === ',' && depth === 0) {
      parts.push(current.trim())
      current = ''
      continue
    }
    current += c
  }
  if (current.trim()) parts.push(current.trim())
  return parts.filter(Boolean)
}

const LITERAL_VALUE = /^(['"`])(?:[^\\`]|\\.)*\1$|^-?\d+(\.\d+)?(px|%|em|rem|vh|vw|s|ms|deg|fr)?$/i

/** A block is "static" when every property value is a string/number literal — runtime-computed values (motion scroll fills, measured geometry) are allowed. */
function isStaticStyleBlock(content) {
  const props = splitTopLevel(content)
  if (!props.length) return false
  for (const prop of props) {
    if (prop.startsWith('...')) return false
    const colon = prop.indexOf(':')
    if (colon < 0) return false
    const value = prop.slice(colon + 1).trim()
    if (value.includes('${')) return false
    if (!LITERAL_VALUE.test(value)) return false
  }
  return true
}

test('no static inline style objects in components', async () => {
  const files = await walk(SRC, (file) => /\.(tsx|ts)$/.test(file))
  const offenders = []
  for (const file of files) {
    const source = await fs.readFile(file, 'utf8')
    for (const block of extractStyleBlocks(source)) {
      if (isStaticStyleBlock(block)) offenders.push(path.relative(SRC, file))
    }
  }
  assert.deepEqual(offenders, [], 'static style={{...}} objects are not allowed — put the rule in a sheet under src/styles/')
})

test('stylesheets exist only under src/styles/', async () => {
  const offenders = await walk(SRC, (file) => file.endsWith('.css') && !file.includes(`${path.sep}styles${path.sep}`))
  assert.deepEqual(offenders.map((file) => path.relative(SRC, file)), [])
})

test('hard-coded hex colors appear only in the foundation layer', async () => {
  const stylesDir = path.join(SRC, 'styles')
  const offenders = []
  for (const file of await walk(stylesDir, (f) => f.endsWith('.css'))) {
    const rel = path.relative(stylesDir, file)
    if (rel.startsWith('foundation/') || rel === 'index.css') continue
    const source = await fs.readFile(file, 'utf8')
    const hexes = [...source.matchAll(/#[0-9a-fA-F]{3,8}\b/g)]
    if (hexes.length > 0) offenders.push(`${rel} (${hexes.length} hex literals)`)
  }
  assert.deepEqual(offenders, [], 'hex colors belong in foundation tokens; derive from var(--color-*) elsewhere')
})

test('index.css imports the foundation + sheets and registers the vendored Aceternity sources', async () => {
  const source = await fs.readFile(path.join(SRC, 'styles', 'index.css'), 'utf8')
  assert.match(source, /@import\s+'tailwindcss'/)
  for (const sheet of ['./foundation/tokens.css', './foundation/aceternity.css', './chrome.css', './prose.css']) {
    assert.match(source, new RegExp(`@import\\s+'${sheet.replace(/\//g, '\\/')}'`), `missing @import for ${sheet}`)
  }
  assert.match(source, /@source\s+'\.\.\/aceternity'/, 'the gitignored vendored tree must stay registered with Tailwind')
})

test('the display font is self-hosted with its OFL license beside it', async () => {
  const fonts = path.join(SRC, 'assets', 'fonts')
  await fs.access(path.join(fonts, 'BricolageGrotesque-latin-var.woff2'))
  const license = await fs.readFile(path.join(fonts, 'OFL.txt'), 'utf8')
  assert.match(license, /SIL Open Font License/)
})
