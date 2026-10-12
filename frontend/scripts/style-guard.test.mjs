// Style guard — contract tests for the public style system (AGENTS.md "Working
// rules"). Run by `yarn test:unit` (scripts/*.test.mjs glob), or directly:
//   node --test scripts/style-guard.test.mjs
//
// What it pins:
//   1. Structure — every stylesheet lives under src/styles/ in exactly one of
//      the three layers (foundation/ common/ features/) or is the index.css
//      entry. No per-component stylesheets anywhere else.
//   2. Scrub — the zai.css / codex.css / theme-zai-* upstream names stay gone
//      (renamed to fengyu.css / kit.css / theme-fengyu-*).
//   3. No static inline styles — a style={{...}} block whose every property
//      value is a literal is a violation; runtime-computed values are allowed.
//      Vendored upstream sources (src/aceternity/, gitignored) are exempt.
import { test } from 'node:test'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import assert from 'node:assert/strict'

const SRC = join(import.meta.dirname, '..', 'src')
const FRONTEND = join(import.meta.dirname, '..')
const VENDORED = join(SRC, 'aceternity') // upstream sources — exempt from all rules
const LAYERS = ['foundation', 'common', 'features']

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry)
    if (statSync(path).isDirectory()) {
      if (path === VENDORED) continue
      walk(path, out)
    } else {
      out.push(path)
    }
  }
  return out
}

const files = walk(SRC)

// ── 1. structure ────────────────────────────────────────────────────────────

test('all stylesheets live in src/styles/{foundation,common,features}', () => {
  const css = files.filter(f => f.endsWith('.css'))
  assert.ok(css.length > 0, 'no css files found — scan is broken')
  for (const path of css) {
    const rel = relative(join(SRC, 'styles'), path)
    assert.ok(
      rel.startsWith('..') ? false : rel === 'index.css' || LAYERS.some(l => rel.startsWith(`${l}/`)),
      `${relative(FRONTEND, path)} is outside the three style layers — shared styles belong in src/styles/`,
    )
  }
})

test('src/styles root holds only the index.css entry; layers hold the sheets', () => {
  const stylesDir = join(SRC, 'styles')
  const entries = readdirSync(stylesDir).filter(e => e !== '.DS_Store')
  assert.deepEqual(
    entries.sort(),
    [...LAYERS, 'index.css'].sort(),
    `src/styles root must contain exactly index.css + the layer dirs, found: ${entries.join(', ')}`,
  )
})

// ── 2. upstream-name scrub ─────────────────────────────────────────────────

const SCRUB_PATTERNS = [/theme-zai/, /zai\.css/, /codex\.css/, /Zai design/]

test('upstream style names stay scrubbed (fengyu.css / kit.css / theme-fengyu-*)', () => {
  const offenders = []
  for (const path of files.filter(f => /\.(tsx?|css|html)$/.test(f))) {
    if (path.endsWith('.html') && !path.endsWith('index.html')) continue
    const src = readFileSync(path, 'utf8')
    for (const pattern of SCRUB_PATTERNS) {
      if (pattern.test(src)) offenders.push(`${relative(FRONTEND, path)} matches ${pattern}`)
    }
  }
  const indexHtml = readFileSync(join(FRONTEND, 'index.html'), 'utf8')
  for (const pattern of SCRUB_PATTERNS) {
    if (pattern.test(indexHtml)) offenders.push(`index.html matches ${pattern}`)
  }
  assert.deepEqual(offenders, [])
})

// ── 3. no static inline styles ──────────────────────────────────────────────

/** Extract every `style={{...}}` block via balanced-brace scan. */
function extractBlocks(source) {
  const blocks = []
  const re = /style=\{\{/g
  let match
  while ((match = re.exec(source))) {
    const start = match.index + match[0].length - 1 // at the second '{'
    let depth = 1
    let i = start + 1
    let inString = null
    while (i < source.length && depth > 0) {
      const c = source[i]
      if (inString) {
        if (c === '\\') i++
        else if (c === inString) inString = null
      } else if (c === '\'' || c === '"' || c === '`') inString = c
      else if (c === '{') depth++
      else if (c === '}') depth--
      i++
    }
    const line = source.slice(0, match.index).split('\n').length
    blocks.push({ line, content: source.slice(start + 1, i - 1) })
  }
  return blocks
}

function splitTopLevel(text) {
  const parts = []
  let depth = 0
  let inString = null
  let current = ''
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inString) {
      current += c
      if (c === '\\') current += text[++i] ?? ''
      else if (c === inString) inString = null
      continue
    }
    if (c === '\'' || c === '"' || c === '`') { inString = c; current += c; continue }
    if (c === '(' || c === '{' || c === '[') { depth++; current += c; continue }
    if (c === ')' || c === '}' || c === ']') { depth--; current += c; continue }
    if (c === ',' && depth === 0) { parts.push(current.trim()); current = ''; continue }
    current += c
  }
  if (current.trim()) parts.push(current.trim())
  return parts.filter(Boolean)
}

const LITERAL_VALUE = /^(['"`])(?:[^\\`]|\\.)*\1$|^-?\d+(\.\d+)?(px|%|em|rem|vh|vw|s|ms|deg|fr)?$/i

/** A block is "static" when every property value is a string/number literal —
 *  nothing runtime-computed. Unparsable blocks are conservatively allowed. */
function isStaticBlock(content) {
  const props = splitTopLevel(content)
  if (!props.length) return false
  for (const prop of props) {
    if (prop.startsWith('...')) return false // spread — value unknowable here
    const colon = prop.indexOf(':')
    if (colon < 0) return false
    const value = prop.slice(colon + 1).trim()
    if (value.includes('${')) return false
    if (!LITERAL_VALUE.test(value)) return false
  }
  return true
}

test('no static inline style={{}} blocks in app components (vendored exempt)', () => {
  const offenders = []
  for (const path of files.filter(f => f.endsWith('.tsx'))) {
    const source = readFileSync(path, 'utf8')
    for (const block of extractBlocks(source)) {
      if (isStaticBlock(block.content)) {
        offenders.push(`${relative(FRONTEND, path)}:${block.line} { ${block.content.replace(/\s+/g, ' ')} }`)
      }
    }
  }
  assert.deepEqual(
    offenders,
    [],
    'Static inline styles must move to public styles (Tailwind utilities / sheet classes);\n' +
      'only runtime-computed values may stay inline. Offenders:\n' + offenders.join('\n'),
  )
})
