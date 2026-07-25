import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { validateManifestObject, validateProjectManifest } from '../src/manifest.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
// toolchain/cli/test -> toolchain/cli -> toolchain -> spec/fixtures
const fixtures = path.resolve(__dirname, '../../spec/fixtures')
const readFixture = async (name) => JSON.parse(await fs.readFile(path.join(fixtures, name), 'utf8'))

test('valid full manifest (database + network.email permissions) has no errors', async () => {
  const manifest = await readFixture('valid-full.json')
  assert.deepEqual(validateManifestObject(manifest), [])
})

test('valid ui-only manifest has no errors', async () => {
  const manifest = await readFixture('valid-ui-only.json')
  assert.deepEqual(validateManifestObject(manifest), [])
})

test('unknown permission is rejected', async () => {
  const manifest = await readFixture('invalid-permission.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('permissions')), errors.join('\n'))
})

test('ai tool inputSchema must be a json object', async () => {
  const manifest = await readFixture('invalid-ai-schema.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('inputSchema')), errors.join('\n'))
})

test('duplicate ai tool names and methods are rejected', () => {
  const manifest = {
    schemaVersion: 1, id: 'com.example.dup', name: 'Dup', version: '1.0.0',
    ui: { entry: 'ui/index.html' }, aiTools: [
      { name: 'a', method: 'm1', inputSchema: '{"type":"object"}' },
      { name: 'a', method: 'm2', inputSchema: '{"type":"object"}' },
      { name: 'b', method: 'm1', inputSchema: '{"type":"object"}' },
    ],
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('duplicate AI tool name: a')), errors.join('\n'))
  assert.ok(errors.some((e) => e.includes('duplicate AI tool method: m1')), errors.join('\n'))
})

test('validateProjectManifest resolves fixtures from a real project root', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-manifest-'))
  try {
    const manifest = await readFixture('valid-full.json')
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

test('ai tool timeoutSeconds outside [1, 600] is rejected', async () => {
  const manifest = await readFixture('invalid-timeout.json')
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('timeoutSeconds') && e.includes('600')), errors.join('\n'))
})

test('backend.callTimeoutSeconds outside [1, 600] is rejected', () => {
  const manifest = {
    schemaVersion: 1, id: 'com.example.timeout', name: 'Timeout',
    description: 'd', version: '1.0.0', author: 'a', icon: 'i', category: 'c',
    ui: { entry: 'ui/index.html' },
    backend: { command: 'java -jar backend/worker.jar', protocol: 'json-rpc-2.0', callTimeoutSeconds: 0 },
  }
  const errors = validateManifestObject(manifest)
  assert.ok(errors.some((e) => e.includes('backend.callTimeoutSeconds')), errors.join('\n'))
})
