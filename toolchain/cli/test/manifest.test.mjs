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

test('aiTools idempotent is optional boolean retry metadata', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.retry', name: 'Retry', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    rpc: { methods: { write: { inputSchema: { type: 'object' } } } },
    aiTools: [
      { name: 'safe_write', method: 'write', effect: 'write', idempotent: true, description: 'd' },
    ],
  }
  assert.deepEqual(validateManifestObject(manifest), [])
  manifest.aiTools[0].idempotent = 'yes'
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /idempotent|boolean/i.test(e)), errors.join('\n'))
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

test('backend worker-tree resource limits are bounded', () => {
  const manifest = {
    schemaVersion: 2, id: 'com.example.resources', name: 'Resources', description: 'd',
    version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    backend: { runtime: 'go', protocolVersion: 1, resources: { memoryMb: 256, maxProcesses: 4 } },
    rpc: { methods: { render: { inputSchema: { type: 'object' } } } },
  }
  assert.deepEqual(validateManifestObject(manifest), [])
  manifest.backend.resources.memoryMb = 32
  manifest.backend.resources.maxProcesses = 65
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('memoryMb')), errors.join('\n'))
  assert.ok(errors.some((e) => e.includes('maxProcesses')), errors.join('\n'))
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

// --- flowNodes cross-validation (descriptor v2 vs the tool surface) --------

const renderTool = () => ({
  name: 'example_render',
  description: 'render',
  effect: 'read',
  method: 'render',
})

test('flowNodes referencing an unknown tool is rejected', async () => {
  const manifest = await readFixture('complete.json')
  manifest.flowNodes = [{ tool: 'ghost_tool', label: 'Ghost', inputs: [] }]
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => /flowNodes\[ghost_tool\].tool references unknown AI tool/.test(e)), errors.join('\n'))
})

test('flowNode input naming a parameter the worker does not accept is rejected', async () => {
  const manifest = await readFixture('complete.json')
  const params = Object.keys(manifest.rpc.methods.render.inputSchema.properties ?? {})
  assert.ok(params.length, 'fixture render method must declare parameters for this test')
  manifest.flowNodes = [{
    tool: 'example_render',
    label: 'Render',
    inputs: [{ name: params[0], widget: 'text' }, { name: 'not_a_param', widget: 'text' }],
  }]
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) =>
    /flowNodes\[example_render\].inputs\[not_a_param\] is not a parameter of render/.test(e)), errors.join('\n'))
  assert.ok(!errors.some((e) => e.includes(`inputs[${params[0]}]`)), errors.join('\n'))
})

test('flowNode inputs against a parameter-less tool are rejected', async () => {
  // A method with inputSchema but no properties is a real shape (e.g. email_accounts_list):
  // declaring any input for it used to slip through the cross-check silently.
  const manifest = await readFixture('complete.json')
  delete manifest.rpc.methods.render.inputSchema.properties
  manifest.flowNodes = [{
    tool: 'example_render',
    label: 'Render',
    inputs: [{ name: 'not_a_param', widget: 'text' }],
  }]
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) =>
    /flowNodes\[example_render\].inputs\[not_a_param\] is not a parameter of render/.test(e)), errors.join('\n'))
})

test('flowNode widget/type pairs that cannot be satisfied are rejected', async () => {
  const manifest = await readFixture('complete.json')
  const param = Object.keys(manifest.rpc.methods.render.inputSchema.properties)[0]
  manifest.flowNodes = [{
    tool: 'example_render',
    label: 'Render',
    inputs: [
      { name: param, widget: 'number', type: 'string' },
      { name: param, widget: 'switch', type: 'number' },
      { name: param, widget: 'text', type: 'boolean' },
      // text binding an array/object reference is legal (exact refs keep their type)
      { name: param, widget: 'text', type: 'array' },
    ],
  }]
  const errors = validateManifestObject(manifest)
  assert.equal(errors.filter((e) => e.includes('cannot produce type')).length, 3, errors.join('\n'))
})

test('flowNode select options accept {value,label} pairs', async () => {
  const manifest = await readFixture('complete.json')
  const param = Object.keys(manifest.rpc.methods.render.inputSchema.properties)[0]
  manifest.flowNodes = [{
    tool: 'example_render',
    label: 'Render',
    inputs: [{
      name: param,
      widget: 'select',
      options: [{ value: 'a', label: '甲' }, 'b'],
    }],
  }]
  assert.deepEqual(validateManifestObject(manifest), [])
})

test('official plugin manifests pass the flowNodes cross-check', async () => {
  // The email plugin's history: a `body` input the worker never accepted shipped
  // silently until this check existed — pin every official manifest to zero errors.
  const official = path.resolve(__dirname, '../../../OfficialPlugins')
  for (const plugin of await fs.readdir(official)) {
    const manifestPath = path.join(official, plugin, 'manifest.json')
    try {
      await fs.access(manifestPath)
    } catch {
      continue
    }
    const manifest = JSON.parse(await fs.readFile(manifestPath, 'utf8'))
    const errors = validateManifestObject(manifest)
    assert.deepEqual(errors, [], `${plugin}: ${errors.join('\n')}`)
  }
})
