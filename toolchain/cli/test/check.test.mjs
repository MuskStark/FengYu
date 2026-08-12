import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { checkPlugin } from '../src/check.mjs'

let base
test.before(async () => { base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-check-')) })
test.after(async () => { await fs.rm(base, { recursive: true, force: true }).catch(() => {}) })

const baseManifest = {
  schemaVersion: 2, id: 'com.example.c', name: 'c', description: 'd', version: '1.0.0',
  author: 'a', icon: 'i', category: 'c', ui: { entry: 'ui/index.html' },
}

/** Scaffold a ui-source plugin. Overrides merge into manifest / ui package.json. */
async function scaffold({ manifest = {}, uiPkg = {}, nodeModules = false, lockfile = false } = {}) {
  const dir = path.join(base, `c-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  await fs.mkdir(path.join(dir, 'ui-src'), { recursive: true })
  await fs.writeFile(path.join(dir, 'manifest.json'), JSON.stringify({ ...baseManifest, ...manifest }))
  await fs.writeFile(
    path.join(dir, 'ui-src/package.json'),
    JSON.stringify({ scripts: { dev: 'vite' }, dependencies: {}, ...uiPkg }),
  )
  if (nodeModules) await fs.mkdir(path.join(dir, 'ui-src/node_modules'), { recursive: true })
  if (lockfile) await fs.writeFile(path.join(dir, 'ui-src/package-lock.json'), '{}')
  return dir
}

test('check passes a clean ui-source plugin (fresh scaffold, no install)', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-ui': '^1.3.0' } },
  })
  const result = await checkPlugin(dir)
  assert.equal(result.ui, 'source')
  assert.equal(result.worker, false)
})

test('check fails when deps are installed without a committed lockfile', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-ui': '^1.3.0' } },
    nodeModules: true,
  })
  await assert.rejects(() => checkPlugin(dir), /lockfile/)
})

test('check passes once a lockfile is committed alongside node_modules', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-ui': '^1.3.0' } },
    nodeModules: true,
    lockfile: true,
  })
  await checkPlugin(dir) // does not throw
})

test('check fails on inconsistent @infinia/* versions', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-sdk': '^1.3.0', '@infinia/plugin-ui': '^1.2.0' } },
  })
  await assert.rejects(() => checkPlugin(dir), /inconsistent/)
})

test('check fails on duplicate permissions', async () => {
  const dir = await scaffold({ manifest: { permissions: ['files.read', 'files.read'] } })
  await assert.rejects(() => checkPlugin(dir), /duplicate permission: files\.read/)
})

test('check error is structured (carries the manifest file path)', async () => {
  const dir = await scaffold({
    uiPkg: { dependencies: { '@infinia/plugin-sdk': '^1.3.0', '@infinia/plugin-ui': '^1.2.0' } },
  })
  await assert.rejects(
    () => checkPlugin(dir),
    (err) => err.file === path.join(dir, 'manifest.json') && /inconsistent/.test(err.message),
  )
})
