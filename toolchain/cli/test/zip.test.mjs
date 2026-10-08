import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { collect, writeZip } from '../src/zip.mjs'
import { inspectArchive, readArchiveEntry } from '../src/archive.mjs'

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-zip-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

test('collect keeps a resource directory legitimately named dist (and nested content)', async () => {
  const root = path.join(base, 'dist-resource')
  await fs.mkdir(path.join(root, 'resources', 'dist', 'assets'), { recursive: true })
  await fs.writeFile(path.join(root, 'resources', 'dist', 'bundle.js'), 'js')
  await fs.writeFile(path.join(root, 'resources', 'dist', 'assets', 'logo.svg'), '<svg/>')
  const entries = await collect(root)
  assert.deepEqual(entries.map((entry) => entry.name).sort(),
    ['resources/dist/assets/logo.svg', 'resources/dist/bundle.js'])
})

test('collect still drops the staging-forbidden entries at any depth', async () => {
  const root = path.join(base, 'forbidden')
  for (const forbidden of ['node_modules/pkg/index.js', '.git/config', 'worker/target/App.class', 'ui-src/src/App.tsx']) {
    await fs.mkdir(path.dirname(path.join(root, forbidden)), { recursive: true })
    await fs.writeFile(path.join(root, forbidden), 'x')
  }
  // The mirror of manifest.mjs FORBIDDEN_RUNTIME_ENTRIES — keep both lists in sync.
  await fs.mkdir(path.join(root, 'keep'), { recursive: true })
  await fs.writeFile(path.join(root, 'keep', 'data.txt'), 'keep')
  const entries = await collect(root)
  assert.deepEqual(entries.map((entry) => entry.name), ['keep/data.txt'])
})

test('writeZip produces a yauzl-readable archive of everything collect kept', async () => {
  const root = path.join(base, 'roundtrip')
  await fs.mkdir(path.join(root, 'resources', 'dist'), { recursive: true })
  await fs.writeFile(path.join(root, 'manifest.json'), '{}')
  await fs.writeFile(path.join(root, 'resources', 'dist', 'bundle.js'), 'js payload')
  const output = path.join(base, 'roundtrip.fyp')
  const result = await writeZip(root, output)
  assert.equal(result.files, 2)
  const { entries } = await inspectArchive(output)
  assert.deepEqual(entries.map((entry) => entry.name).sort(), ['manifest.json', 'resources/dist/bundle.js'])
  assert.equal((await readArchiveEntry(output, 'resources/dist/bundle.js')).toString(), 'js payload')
})
