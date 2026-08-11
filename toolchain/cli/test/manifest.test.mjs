import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import {
  validateManifestObject,
  validateManifestText,
  validateProjectManifest,
} from '../src/manifest.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
// toolchain/cli/test -> toolchain/cli -> toolchain -> spec/test-fixtures
const fixtures = path.resolve(__dirname, '../../spec/test-fixtures')
const readFixture = async (name) => JSON.parse(await fs.readFile(path.join(fixtures, name), 'utf8'))
const readFixtureText = async (name) => fs.readFile(path.join(fixtures, name), 'utf8')

// --- valid manifests --------------------------------------------------------

test('minimal ui-only v2 manifest has no errors', async () => {
  const manifest = await readFixture('minimal.json')
  assert.deepEqual(validateManifestObject(manifest), [])
})

test('complete v2 manifest (backend + rpc + aiTools + i18n) has no errors', async () => {
  const manifest = await readFixture('complete.json')
  assert.deepEqual(validateManifestObject(manifest), [])
})

// --- the five frozen rejection rules ---------------------------------------

test('rejects an unknown root property (additionalProperties: false)', async () => {
  const manifest = await readFixture('illegal-extra-field.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /legacyCommand|additional prop/i.test(e)), errors.join('\n'))
})

test('rejects inputSchema written as an escaped JSON string', async () => {
  const manifest = await readFixture('illegal-schema-string.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('inputSchema')), errors.join('\n'))
})

test('rejects a duplicate rpc.methods key (raw-text duplicate-key scan)', async () => {
  // JSON.parse silently keeps the last value for a duplicated key, so this must
  // go through the text-aware validator that scans for duplicate keys.
  const text = await readFixtureText('illegal-duplicate-method.json')
  const errors = validateManifestText(text)
  assert.ok(errors.some((e) => /duplicate key.*render/i.test(e)), errors.join('\n'))
})

test('rejects an aiTools.method that is not declared in rpc.methods', async () => {
  const manifest = await readFixture('illegal-dangling-ai-method.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /unknown method.*renderGhost/i.test(e)), errors.join('\n'))
})

test('rejects an invalid i18n locale key', async () => {
  const manifest = await readFixture('illegal-i18n-key.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /en_US|i18n|additional prop/i.test(e)), errors.join('\n'))
})

// --- semantic cross-checks --------------------------------------------------

test('duplicate aiTools names are rejected', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.dup', name: 'Dup', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    rpc: { methods: { render: { inputSchema: { type: 'object' } } } },
    aiTools: [
      { name: 'a', method: 'render', effect: 'read', description: 'd' },
      { name: 'a', method: 'render', effect: 'read', description: 'd' },
    ],
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('duplicate AI tool name: a')), errors.join('\n'))
})

test('a declared backend requires at least one rpc.methods entry', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.emptyworker', name: 'Empty', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    backend: { callTimeoutSeconds: 30 },
    rpc: { methods: {} },
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /backend requires at least one rpc\.methods/i.test(e)), errors.join('\n'))
})

test('rpc.methods timeoutSeconds outside [1, 600] is rejected', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.timeout', name: 'Timeout', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    backend: {},
    rpc: { methods: { slow: { inputSchema: { type: 'object' }, timeoutSeconds: 0 } } },
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('timeoutSeconds') && e.includes('600')), errors.join('\n'))
})

test('backend.callTimeoutSeconds outside [1, 600] is rejected', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.timeout', name: 'Timeout', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    backend: { callTimeoutSeconds: 9999 },
    rpc: { methods: { render: { inputSchema: { type: 'object' } } } },
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('backend.callTimeoutSeconds')), errors.join('\n'))
})

test('i18n aiTools override referencing an unknown tool is rejected', async () => {
  const manifest = await readFixture('complete.json')
  manifest.i18n.zh.aiTools.ghost_tool = { name: 'Ghost' }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /i18n\[zh\].aiTools references unknown tool: ghost_tool/i.test(e)), errors.join('\n'))
})

// --- project-root entry point ----------------------------------------------

test('validateProjectManifest resolves a v2 fixture from a real project root', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-manifest-'))
  try {
    const manifest = await readFixture('complete.json')
    await fs.writeFile(path.join(root, 'manifest.json'), JSON.stringify(manifest))
    await fs.mkdir(path.join(root, 'ui'), { recursive: true })
    await fs.writeFile(path.join(root, 'ui/index.html'), '<html></html>')
    await fs.mkdir(path.join(root, 'backend'), { recursive: true })
    await fs.writeFile(path.join(root, 'backend/worker.jar'), Buffer.from('PK'))
    assert.equal((await validateProjectManifest(root)).length, 0)
  } finally {
    await fs.rm(root, { recursive: true, force: true }).catch(() => {})
  }
})
