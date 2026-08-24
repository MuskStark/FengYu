import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

import { detectManifestMode, readCodeFirstSources } from '../src/manifest-source.mjs'
import { mergeCodeFirstManifest, canonicalJson, IR_FORMAT_VERSION } from '../src/manifest-compiler.mjs'
import { validateManifestObject } from '../src/manifest.mjs'

const BASE = {
  schemaVersion: 2,
  id: 'fan.summer.excel',
  name: 'Excel Splitter',
  description: 'Split workbooks',
  version: '1.0.0',
  author: 'FengYu',
  icon: 'file-excel',
  category: 'file',
  ui: { entry: 'ui/index.html' },
  backend: { runtime: 'java', protocolVersion: 1 },
  permissions: ['files.read'],
}

function contract() {
  return {
    formatVersion: IR_FORMAT_VERSION,
    pluginId: 'fan.summer.excel',
    rpc: {
      methods: {
        analyze: {
          description: 'Analyze a workbook.',
          inputSchema: {
            type: 'object',
            properties: {
              sourceFile: { type: 'string' },
              session: { type: 'string', nullable: true },
            },
            required: ['sourceFile'],
          },
          outputSchema: {
            type: 'object',
            properties: {
              success: { type: 'boolean' },
              sheets: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    name: { type: 'string' },
                    columns: { type: 'array', items: { type: 'object', properties: { header: { type: 'string' } } } },
                  },
                },
              },
            },
            required: ['success'],
          },
        },
        excel_complex_config: {
          description: 'Configure splits.',
          inputSchema: {
            type: 'object',
            properties: {
              filePath: { type: 'string' },
              password: { type: 'string', 'x-fengyu-sensitive': true },
            },
          },
          outputSchema: {
            type: 'object',
            properties: {
              success: { type: 'boolean' },
              output: { type: 'object', properties: { dir: { type: 'string' } } },
            },
            required: ['success'],
          },
        },
      },
    },
    aiTools: [
      {
        name: 'excel_complex_config',
        description: 'Configure splits.',
        method: 'excel_complex_config',
        effect: 'write',
      },
    ],
  }
}

function manifestWith(parts = {}) {
  const merged = mergeCodeFirstManifest({
    base: { ...BASE },
    contract: parts.contract ?? contract(),
    flowOverlay: parts.flowOverlay ?? null,
    i18n: parts.i18n ?? null,
  })
  assert.equal(merged.errors.length, 0, `unexpected merge errors: ${merged.errors.join('; ')}`)
  return merged.manifest
}

// ── Mode detection ─────────────────────────────────────────────────────────

test('detectManifestMode rejects having both authoring sources', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'fengyu-both-'))
  await fs.writeFile(path.join(dir, 'manifest.json'), '{}')
  await fs.writeFile(path.join(dir, 'manifest.base.json'), '{}')
  const { mode, error } = await detectManifestMode(dir)
  assert.equal(mode, 'none')
  assert.match(error, /exactly one authoring source/)
})

test('readCodeFirstSources rejects a base that declares rpc', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'fengyu-base-rpc-'))
  await fs.writeFile(path.join(dir, 'manifest.base.json'), JSON.stringify({ ...BASE, rpc: { methods: {} } }))
  const { errors } = await readCodeFirstSources(dir)
  assert.ok(errors.some((e) => e.includes('must not declare "rpc"')))
})

test('readCodeFirstSources rejects out-of-bounds flow overlay fields', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'fengyu-overlay-'))
  await fs.writeFile(path.join(dir, 'manifest.base.json'), JSON.stringify(BASE))
  await fs.mkdir(path.join(dir, 'manifest'), { recursive: true })
  await fs.writeFile(path.join(dir, 'manifest/flow-nodes.json'), JSON.stringify({ flowNodes: [], permissions: [] }))
  const { errors } = await readCodeFirstSources(dir)
  assert.ok(errors.some((e) => e.includes('permissions')))
})

// ── Merge ──────────────────────────────────────────────────────────────────

test('merge validates IR formatVersion and pluginId against the base', () => {
  const badVersion = mergeCodeFirstManifest({ base: BASE, contract: { ...contract(), formatVersion: 99 } })
  assert.ok(badVersion.errors.some((e) => e.includes('formatVersion')))
  const badId = mergeCodeFirstManifest({ base: BASE, contract: { ...contract(), pluginId: 'other.plugin' } })
  assert.ok(badId.errors.some((e) => e.includes('pluginId')))
})

test('merge without a contract IR fails (no silent empty rpc)', () => {
  const { errors } = mergeCodeFirstManifest({ base: BASE, contract: null })
  assert.ok(errors.some((e) => e.includes('generated contract is missing')))
})

test('merge assembles rpc/aiTools/flowNodes/i18n from disjoint sources', () => {
  const manifest = manifestWith({
    flowOverlay: { flowNodes: [{ tool: 'excel_complex_config', outputs: [] }] },
    i18n: { zh: { name: 'Excel 拆分' } },
  })
  assert.deepEqual(Object.keys(manifest.rpc.methods).sort(), ['analyze', 'excel_complex_config'])
  assert.equal(manifest.aiTools.length, 1)
  assert.equal(manifest.flowNodes.length, 1)
  assert.equal(manifest.i18n.zh.name, 'Excel 拆分')
})

test('canonicalJson is deterministic and key-sorted', () => {
  const a = { b: 1, a: { d: [3, { z: 1, y: 2 }], c: 2 } }
  const b = { a: { c: 2, d: [3, { y: 2, z: 1 }] }, b: 1 }
  assert.equal(canonicalJson(a), canonicalJson(b))
  assert.ok(canonicalJson(a).endsWith('\n'))
})

// ── Flow UI overlay vs RPC schema ──────────────────────────────────────────

function manifestWithOutput(output, contractOverride) {
  return manifestWith({
    contract: contractOverride,
    flowOverlay: { flowNodes: [{ tool: 'excel_complex_config', outputs: [output] }] },
  })
}

test('a display-only overlay for a real result field passes validation', () => {
  const manifest = manifestWithOutput({ name: 'success', title: 'Success' })
  assert.equal(validateManifestObject(manifest).length, 0)
})

test('output overlay cannot invent a field absent from the worker result', () => {
  const manifest = manifestWithOutput({ name: 'sourceFile', title: 'Source file' })
  assert.ok(validateManifestObject(manifest).some((e) => e.includes('is not a result field')))
})

test('flow overlays reject executable schema fields', () => {
  const manifest = manifestWith({ flowOverlay: { flowNodes: [{
    tool: 'excel_complex_config',
    inputs: [{ name: 'filePath', widget: 'text', type: 'number', required: true, default: 1 }],
    outputs: [{ name: 'success', type: 'boolean' }],
  }] } })
  const errors = validateManifestObject(manifest)
  assert.ok(errors.filter((e) => e.includes('must NOT have additional properties')).length >= 2, errors.join('\n'))
})

test('duplicate output names fail', () => {
  const manifest = manifestWith({
    flowOverlay: {
      flowNodes: [{
        tool: 'excel_complex_config',
        outputs: [
          { name: 'success' },
          { name: 'success' },
        ],
      }],
    },
  })
  assert.ok(validateManifestObject(manifest).some((e) => e.includes('more than once')))
})

// ── Context feeds validation (§8.3) ────────────────────────────────────────

function manifestWithContext(context, rowFields = []) {
  return manifestWith({
    flowOverlay: {
      flowNodes: [{
        tool: 'excel_complex_config',
        inputs: [
          { name: 'filePath', widget: 'text', context },
          ...(rowFields.length ? [{
            name: 'entries', widget: 'rows',
            fields: rowFields.map((f) => ({ name: f, widget: 'text' })),
          }] : []),
        ],
      }],
    },
  })
}

const GOOD_CONTEXT = {
  method: 'analyze',
  params: { sourceFile: '{{value}}' },
  sessionScope: 'node',
  feeds: {
    sheets: { list: 'sheets', item: 'name' },
    columns: { list: 'sheets', key: 'name', items: 'columns', itemField: 'header' },
  },
}

test('valid analyze context with flat and keyed feeds passes', () => {
  const manifest = manifestWithContext(GOOD_CONTEXT)
  assert.deepEqual(validateManifestObject(manifest), [])
})

test('context method must exist in rpc.methods', () => {
  const errors = validateManifestObject(manifestWithContext({ ...GOOD_CONTEXT, method: 'missing' }))
  assert.ok(errors.some((e) => e.includes('unknown rpc method')))
})

test('context params must name real parameters', () => {
  const errors = validateManifestObject(
    manifestWithContext({ ...GOOD_CONTEXT, params: { bogus: '{{value}}' } }))
  assert.ok(errors.some((e) => e.includes('not a parameter')))
})

test('sessionScope=node requires a session parameter', () => {
  // GOOD_CONTEXT targets analyze, whose schema accepts `session` — legal as-is.
  assert.equal(validateManifestObject(manifestWithContext(GOOD_CONTEXT)).length, 0)
  // Drop `session` from the method's schema and the same context must be rejected.
  const manifest = manifestWithContext(GOOD_CONTEXT)
  delete manifest.rpc.methods.analyze.inputSchema.properties.session
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('session')), errors.join('; '))
})

test('feed list must resolve to an array field', () => {
  const errors = validateManifestObject(
    manifestWithContext({ ...GOOD_CONTEXT, feeds: { bad: { list: 'success', item: 'x' } } }))
  assert.ok(errors.some((e) => e.includes('must be an array')))
})

test('optionsFromContext must reference a declared feed', () => {
  const manifest = manifestWithContext(GOOD_CONTEXT, ['sheetName'])
  manifest.flowNodes[0].inputs[1].fields[0].optionsFromContext = { set: 'nope' }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('no input of this node declares')))
})

test('keyedBy must reference a row field or node input', () => {
  const manifest = manifestWithContext(GOOD_CONTEXT, ['sheetName'])
  manifest.flowNodes[0].inputs[1].fields[0].optionsFromContext = {
    set: 'sheets', keyedBy: 'unknownField',
  }
  assert.ok(validateManifestObject(manifest).some((e) => e.includes('keyedBy references')))
})

test('validation errors carry the IR origin of the referenced rpc method', async () => {
  const { annotateWithOrigins } = await import('../src/manifest-compiler.mjs')
  const annotated = annotateWithOrigins(
    ['manifest.flowNodes[x].outputs[y] -> unknown result field'],
    { 'rpc.methods.excel_complex_config': 'ExcelContract.java:28' },
  )
  // No rpc.methods mention in this error -> unchanged.
  assert.equal(annotated[0].split('\n').length, 1)
  const annotated2 = annotateWithOrigins(
    ['rpc.methods[analyze].timeoutSeconds must be between 1 and 600 seconds'],
    { 'rpc.methods.analyze': 'ExcelContract.java:12' },
  )
  assert.match(annotated2[0], /RPC source: ExcelContract\.java:12/)
})
