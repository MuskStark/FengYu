import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  generateTypescript,
  generateJava,
  generatedFilesFor,
  writeGenerated,
  checkDrift,
  hasRpc,
} from '../src/generate.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const specFixtures = path.resolve(__dirname, '../../spec/test-fixtures')
const goldenDir = path.resolve(__dirname, 'fixtures/golden/complete')
const readSpec = async (name) => JSON.parse(await fs.readFile(path.join(specFixtures, name), 'utf8'))
const readGolden = async (name) => fs.readFile(path.join(goldenDir, name), 'utf8')

// --- byte-for-byte determinism ---------------------------------------------

test('two consecutive TS generations are byte-for-byte identical', async () => {
  const m = await readSpec('complete.json')
  assert.equal(generateTypescript(m), generateTypescript(JSON.parse(JSON.stringify(m))))
})

test('two consecutive Java generations are byte-for-byte identical', async () => {
  const m = await readSpec('complete.json')
  const a = JSON.stringify(generateJava(m))
  const b = JSON.stringify(generateJava(JSON.parse(JSON.stringify(m))))
  assert.equal(a, b)
})

test('generation is independent of rpc.methods key order', () => {
  const ordered = {
    schemaVersion: 2, id: 'com.example.order', name: 'o', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: {
      alpha: { inputSchema: { type: 'object', properties: { x: { type: 'string' } } } },
      beta: { inputSchema: { type: 'object', properties: { y: { type: 'integer' } } } },
    } },
  }
  const reversed = JSON.parse(JSON.stringify(ordered))
  reversed.rpc.methods = { beta: ordered.rpc.methods.beta, alpha: ordered.rpc.methods.alpha }
  assert.equal(generateTypescript(ordered), generateTypescript(reversed))
  assert.equal(JSON.stringify(generateJava(ordered)), JSON.stringify(generateJava(reversed)))
})

// --- golden output (pins the emitted contract) -----------------------------

test('TS output matches the committed golden fengyu-rpc.ts', async () => {
  const m = await readSpec('complete.json')
  assert.equal(generateTypescript(m), await readGolden('fengyu-rpc.ts'))
})

test('Java output matches the committed golden files', async () => {
  const m = await readSpec('complete.json')
  const files = generateJava(m)
  assert.ok(files.length >= 3) // PluginMethods + at least one Input/Output per method
  for (const f of files) {
    assert.equal(f.content, await readGolden(f.name), `golden mismatch: ${f.name}`)
  }
})

// --- empty / no-rpc manifests ----------------------------------------------

test('a manifest with no rpc.methods produces no generated files', () => {
  const m = { schemaVersion: 2, id: 'com.example.empty', name: 'e', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' } }
  assert.equal(hasRpc(m), false)
  assert.equal(generateTypescript(m), null)
  assert.deepEqual(generateJava(m), [])
  assert.deepEqual(generatedFilesFor({ root: '/x', config: { ui: { root: '/x' }, worker: { root: '/x' } } }, m), [])
})

test('a method without outputSchema resolves its return type to unknown', () => {
  const m = { schemaVersion: 2, id: 'com.example.fire', name: 'f', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: { ping: { inputSchema: { type: 'object' } } } } }
  const ts = generateTypescript(m)
  assert.match(ts, /Promise<unknown>/)
  // Only the Input record is emitted — no PingOutput.
  const names = generateJava(m).map((f) => f.name)
  assert.ok(names.includes('PingInput.java'))
  assert.ok(!names.some((n) => n === 'PingOutput.java'))
})

// --- unsupported subset is rejected ----------------------------------------

test('an unsupported JSON-schema type is rejected, not coerced', () => {
  const m = { schemaVersion: 2, id: 'com.example.bad', name: 'b', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: { m: { inputSchema: { type: 'object', properties: { x: { type: 'anyOf' } } } } } } }
  assert.throws(() => generateTypescript(m), /unsupported schema type/)
  assert.throws(() => generateJava(m), /unsupported schema type/)
})

// --- drift behaviour (check = read-only) -----------------------------------

async function makeProjectDir(manifest) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-gen-'))
  await fs.writeFile(path.join(root, 'manifest.json'), JSON.stringify(manifest))
  await fs.mkdir(path.join(root, 'ui-src/src'), { recursive: true })
  await fs.writeFile(path.join(root, 'ui-src/package.json'), '{"scripts":{"dev":"vite"}}')
  await fs.mkdir(path.join(root, 'worker/src/main/java'), { recursive: true })
  await fs.writeFile(path.join(root, 'worker/pom.xml'), '<project/>')
  return root
}

test('writeGenerated then checkDrift reports no errors', async () => {
  const m = await readSpec('complete.json')
  const root = await makeProjectDir(m)
  try {
    const project = { root, config: { ui: { root: path.join(root, 'ui-src') }, worker: { root: path.join(root, 'worker') } } }
    const written = await writeGenerated(project, m)
    assert.ok(written.some((p) => p.endsWith('fengyu-rpc.ts')))
    assert.ok(written.some((p) => p.endsWith('PluginMethods.java')))
    assert.deepEqual(await checkDrift(project, m), [])
  } finally {
    await fs.rm(root, { recursive: true, force: true }).catch(() => {})
  }
})

test('a stale generated file is reported by checkDrift', async () => {
  const m = await readSpec('complete.json')
  const root = await makeProjectDir(m)
  try {
    const project = { root, config: { ui: { root: path.join(root, 'ui-src') }, worker: { root: path.join(root, 'worker') } } }
    await writeGenerated(project, m)
    const tsPath = path.join(root, 'ui-src/src/generated/fengyu-rpc.ts')
    await fs.writeFile(tsPath, '// tampered\n')
    const errors = await checkDrift(project, m)
    assert.ok(errors.some((e) => /stale/.test(e)), errors.join('\n'))
  } finally {
    await fs.rm(root, { recursive: true, force: true }).catch(() => {})
  }
})

test('a missing generated file is reported by checkDrift', async () => {
  const m = await readSpec('complete.json')
  const root = await makeProjectDir(m)
  try {
    const project = { root, config: { ui: { root: path.join(root, 'ui-src') }, worker: { root: path.join(root, 'worker') } } }
    await writeGenerated(project, m)
    await fs.rm(path.join(root, 'ui-src/src/generated/fengyu-rpc.ts'))
    const errors = await checkDrift(project, m)
    assert.ok(errors.some((e) => /missing/.test(e)), errors.join('\n'))
  } finally {
    await fs.rm(root, { recursive: true, force: true }).catch(() => {})
  }
})

// --- resolved paths --------------------------------------------------------

test('generatedFilesFor resolves TS and Java paths from the project model', () => {
  const m = { schemaVersion: 2, id: 'fan.summer.demo', name: 'd', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: { render: { inputSchema: { type: 'object' } } } } }
  const project = { root: '/proj', config: { ui: { root: '/proj/ui-src' }, worker: { root: '/proj/worker' } } }
  const paths = generatedFilesFor(project, m).map((f) => f.path)
  assert.ok(paths.includes(path.join('/proj/ui-src/src/generated/fengyu-rpc.ts')))
  assert.ok(paths.includes(path.join('/proj/worker/src/main/java/fan/summer/demo/generated/PluginMethods.java')))
  assert.ok(paths.includes(path.join('/proj/worker/src/main/java/fan/summer/demo/generated/RenderInput.java')))
})

// --- regressions: array-of-object nested records + underscore method names (T2-P2 canary) ---

test('Java emits a nested record declaration for array-of-object fields (not just the reference)', () => {
  const m = { schemaVersion: 2, id: 'fan.summer.demo', name: 'd', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: {
      analyze: { inputSchema: { type: 'object' }, outputSchema: { type: 'object', properties: {
        rows: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' } } } },
        cols: { type: 'array', items: { type: 'object', properties: { idx: { type: 'integer' } } } },
      } } },
    } } }
  const files = generateJava(m)
  const out = files.find((f) => f.name === 'AnalyzeOutput.java').content
  // Both array-of-object fields get a record declaration named after the field (no collision),
  // AND a List<> reference — the bug was the declaration being dropped, leaving only the reference.
  assert.match(out, /List<AnalyzeOutputRows> rows/, 'rows field references the nested record type')
  assert.match(out, /public record AnalyzeOutputRows\(/, 'rows nested record is DECLARED (regression: was dropped)')
  assert.match(out, /List<AnalyzeOutputCols> cols/, 'cols field references its own nested record type')
  assert.match(out, /public record AnalyzeOutputCols\(/, 'cols nested record is DECLARED — no name collision with rows')
})

test('Java emits a real enum for a closed string-enum field (constant == wire value, no @SerializedName)', () => {
  const m = { schemaVersion: 2, id: 'fan.summer.demo', name: 'd', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: {
      configure: { inputSchema: { type: 'object', properties: {
        mode: { type: 'string', enum: ['BY_SHEET', 'BY_COLUMN', 'COMPLEX'] },
        dup: { type: 'string', enum: ['merge', 'skip', 'overwrite'] },
      } } },
    } } }
  const input = generateJava(m).find((f) => f.name === 'ConfigureInput.java').content
  // The field type is the nested enum, and the enum is DECLARED with constants equal to the wire
  // values (so Enum.name()/toString()/String.valueOf all round-trip the wire value with no alias).
  assert.match(input, /ConfigureInputMode mode/, 'enum-typed field references the nested enum type')
  assert.match(input, /public enum ConfigureInputMode \{/, 'UPPER enum is DECLARED')
  assert.match(input, /BY_SHEET,\s*BY_COLUMN,\s*COMPLEX/, 'UPPER constants equal their wire values')
  assert.match(input, /ConfigureInputDup dup/, 'lowercase-wire enum field references its nested enum type')
  assert.match(input, /public enum ConfigureInputDup \{/, 'lowercase enum is DECLARED')
  assert.match(input, /merge,\s*skip,\s*overwrite/,
    'lowercase constants equal their wire values (valid Java identifiers)')
  // The enum constants are bare identifiers (no @SerializedName aliasing) — proven by the bare
  // constant matches above. (The record FIELDS still carry @SerializedName for their JSON keys,
  // which is correct and unrelated to enum aliasing.)
})

test('Java falls back to String when an enum value is not a valid Java identifier', () => {
  const m = { schemaVersion: 2, id: 'fan.summer.demo', name: 'd', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: {
      configure: { inputSchema: { type: 'object', properties: {
        mode: { type: 'string', enum: ['by-sheet', 'by-column'] }, // hyphens are not valid identifiers
      } } },
    } } }
  const input = generateJava(m).find((f) => f.name === 'ConfigureInput.java').content
  assert.match(input, /String mode/, 'non-identifier enum values fall back to String (no aliasing/trap)')
  assert.doesNotMatch(input, /public enum/, 'no enum emitted for non-identifier values')
})

test('pascal() handles underscore method names: excel_complex_config → ExcelComplexConfig', () => {
  const m = { schemaVersion: 2, id: 'fan.summer.demo', name: 'd', description: 'd', version: '1.0.0',
    author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' }, backend: {},
    rpc: { methods: { excel_complex_config: { inputSchema: { type: 'object' } } } } }
  const names = generateJava(m).map((f) => f.name)
  assert.ok(names.includes('ExcelComplexConfigInput.java'), `got ${names.join(',')}`)
  assert.ok(names.includes('ExcelComplexConfigOutput.java') === false, 'no Output file when outputSchema absent')
  const ts = generateTypescript(m)
  assert.match(ts, /ExcelComplexConfigInput/, 'TS type name is PascalCase')
})
