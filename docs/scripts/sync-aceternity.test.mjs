import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { syncAceternity } from './sync-aceternity.mjs'

/**
 * Compact docs-side port of the frontend's sync-aceternity test suite: the
 * mechanism is a twin, so these pin the load-bearing behaviors only
 * (offline no-op, drift guard, add/update lifecycle, ownership rules).
 */

function tempRoot() {
  return fs.mkdtemp(path.join(import.meta.dirname, '.sync-test-'))
}

function registryPayload(files = { 'components/ui/thing.tsx': 'export const Thing = () => null' }) {
  return {
    files: Object.entries(files).map(([file, content]) => ({ path: file, content })),
    dependencies: ['clsx'],
    registryDependencies: [],
  }
}

function fakeFetch(payloads) {
  const calls = []
  const impl = async (url) => {
    calls.push(String(url))
    const slug = String(url).split('/').pop().replace(/\.json$/, '')
    const payload = payloads[slug]
    if (!payload) throw new Error(`unexpected fetch: ${url}`)
    return { ok: true, status: 200, text: async () => JSON.stringify(payload), json: async () => payload }
  }
  impl.calls = calls
  return impl
}

const BASE_PKG = { name: 'docs-test', dependencies: { clsx: '*' } }

test('a synced tree skips the network entirely (offline-friendly)', async () => {
  const root = await tempRoot()
  await fs.writeFile(path.join(root, 'package.json'), JSON.stringify(BASE_PKG))
  const fetchImpl = fakeFetch({ thing: registryPayload() })
  const first = await syncAceternity(root, { mode: 'add', slugs: ['thing'], fetchImpl, env: {} })
  assert.deepEqual(first.pinned, ['thing'])
  assert.equal(first.filesWritten, 1)

  const failing = async () => {
    throw new Error('network must not be touched')
  }
  const second = await syncAceternity(root, { fetchImpl: failing, env: {} })
  assert.deepEqual(second.cached, ['thing'])
  assert.equal(second.filesWritten, 0)
  await fs.rm(root, { recursive: true, force: true })
})

test('upstream drift against the pin is a hard error until --update', async () => {
  const root = await tempRoot()
  await fs.writeFile(path.join(root, 'package.json'), JSON.stringify(BASE_PKG))
  const v1 = fakeFetch({ thing: registryPayload() })
  await syncAceternity(root, { mode: 'add', slugs: ['thing'], fetchImpl: v1, env: {} })
  // A synced tree is an offline no-op — drift is only observable once the
  // vendored file no longer matches the recorded state (tamper → refetch).
  await fs.writeFile(path.join(root, 'src/aceternity/thing.tsx'), 'tampered')

  const v2 = fakeFetch({ thing: registryPayload({ 'components/ui/thing.tsx': 'export const Thing2 = () => null' }) })
  await assert.rejects(
    syncAceternity(root, { fetchImpl: v2, env: {} }),
    /upstream drifted from the pinned sha256.*--update thing/s,
  )
  const updated = await syncAceternity(root, { mode: 'update', slugs: ['thing'], fetchImpl: v2, env: {} })
  assert.deepEqual(updated.pinned, ['thing'])
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'aceternity.manifest.json'), 'utf8'))
  assert.ok(manifest.components.thing.sha256)
  await fs.rm(root, { recursive: true, force: true })
})

test('vendored files carry the provenance + @ts-nocheck stamp and never the raw upstream', async () => {
  const root = await tempRoot()
  await fs.writeFile(path.join(root, 'package.json'), JSON.stringify(BASE_PKG))
  const fetchImpl = fakeFetch({ thing: registryPayload() })
  await syncAceternity(root, { mode: 'add', slugs: ['thing'], fetchImpl, env: {} })
  const vendored = await fs.readFile(path.join(root, 'src/aceternity/thing.tsx'), 'utf8')
  assert.match(vendored, /^\/\/ @ts-nocheck/)
  assert.match(vendored, /Vendored into the FengYu docs site/)
  assert.match(vendored, /never be committed/)
  await fs.rm(root, { recursive: true, force: true })
})

test('an unowned file in src/aceternity is never clobbered without --force', async () => {
  const root = await tempRoot()
  await fs.writeFile(path.join(root, 'package.json'), JSON.stringify(BASE_PKG))
  await fs.mkdir(path.join(root, 'src/aceternity'), { recursive: true })
  await fs.writeFile(path.join(root, 'src/aceternity/thing.tsx'), 'manual drop (paid component)')
  const fetchImpl = fakeFetch({ thing: registryPayload() })
  await assert.rejects(
    syncAceternity(root, { mode: 'add', slugs: ['thing'], fetchImpl, env: {} }),
    /not owned by this script.*--force/s,
  )
  const forced = await syncAceternity(root, { mode: 'add', slugs: ['thing'], fetchImpl, env: {}, force: true })
  assert.equal(forced.filesWritten, 1)
  await fs.rm(root, { recursive: true, force: true })
})

test('the shipped manifest pins exactly the docs site’s vendored set', async () => {
  const manifest = JSON.parse(await fs.readFile(path.join(import.meta.dirname, '..', 'aceternity.manifest.json'), 'utf8'))
  assert.deepEqual(Object.keys(manifest.components).sort(), ['encrypted-text', 'magnetic-button', 'text-generate-effect'])
})
