/**
 * Tests for scripts/sync-aceternity.mjs — the main app's Aceternity vendoring
 * channel. Mirrors toolchain/cli/test/add.test.mjs's approach: inject a stub
 * `fetchImpl`, run against a tmpdir frontend skeleton, pin the contract that
 * keeps the vendored sources out of the public repo while keeping builds
 * reproducible (sha256-pinned) and offline-friendly once synced.
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { syncAceternity, sha256Hex, normalizeName } from './sync-aceternity.mjs'

const NOW = new Date('2026-10-10T00:00:00Z')

async function scaffold() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-aceternity-'))
  await fs.mkdir(path.join(root, 'src'), { recursive: true })
  await fs.writeFile(
    path.join(root, 'package.json'),
    JSON.stringify({ name: 'fengyu-frontend', dependencies: { motion: '^12.38.0', clsx: '^2.0.0', 'tailwind-merge': '^3.0.0' } }, null, 2),
  )
  await fs.writeFile(path.join(root, 'aceternity.manifest.json'), JSON.stringify({ components: {} }, null, 2))
  return root
}

/** Registry stub: slug → payload object; text is re-serialized per call so tests can mutate payloads to simulate drift. */
function registry(entries) {
  const calls = []
  const impl = async (url) => {
    calls.push(String(url))
    const slug = String(url).slice('https://ui.aceternity.com/registry/'.length).replace(/\.json$/, '')
    const payload = entries[slug]
    if (!payload) return { ok: false, status: 404, text: async () => 'not found' }
    const text = JSON.stringify(payload)
    return { ok: true, status: 200, text: async () => text, json: async () => JSON.parse(text) }
  }
  return { impl, calls }
}

const AURORA_FILES = [
  { path: 'components/ui/aurora-background.tsx', content: '"use client";\nimport React, { ReactNode } from "react";\nimport { cn } from "@/lib/utils";\nimport { Stars } from "@/components/ui/stars";\nimport { motion } from "framer-motion";\nexport function AuroraBackground({ children }: { children?: ReactNode }) { return (<main><div className={cn("relative", "bg-slate-950")}>{children}{<Stars />}</div></main>) }\n' },
]

test('add pins the component and its registryDependencies, stamps provenance, writes state', async () => {
  const root = await scaffold()
  const entries = {
    'aurora-background': { files: AURORA_FILES, dependencies: ['motion'], registryDependencies: ['stars'] },
    stars: { files: [{ path: 'components/ui/stars.tsx', content: '"use client";\nexport function Stars() { return null }\n' }] },
  }
  const { impl } = registry(entries)
  const report = await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })

  assert.deepEqual([...report.pinned].sort(), ['aurora-background', 'stars'])
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'aceternity.manifest.json'), 'utf8'))
  assert.deepEqual(Object.keys(manifest.components).sort(), ['aurora-background', 'stars'])
  assert.equal(manifest.components["aurora-background"].sha256, sha256Hex(JSON.stringify(entries["aurora-background"])))
  assert.equal(manifest.components["aurora-background"].pinnedAt, '2026-10-10')

  const vendored = await fs.readFile(path.join(root, 'src/aceternity/aurora-background.tsx'), 'utf8')
  const lines = vendored.split('\n')
  assert.match(lines[0], /@ts-nocheck/)
  assert.ok(vendored.includes('source: https://ui.aceternity.com/registry/aurora-background.json'))
  assert.ok(vendored.includes('never be committed'))
  // "use client" stays a directive: header comments above, directive immediately after.
  const useClientIndex = lines.findIndex((line) => /^["']use client["'];?$/.test(line))
  assert.ok(useClientIndex > 0 && useClientIndex <= 7, '"use client" must remain near the top, below the stamp')
  // Scaffold adaptation: upstream's <main> must land as a plain <div> (the app
  // shell already owns the main landmark) and the stamp must record the rewrite.
  assert.ok(!vendored.includes('<main>'), 'nested <main> would duplicate the a11y landmark')
  assert.ok(vendored.includes('<div>') && vendored.includes('</div>'))
  assert.ok(vendored.includes('Scaffold adapted for this app'))
  assert.ok(await fs.stat(path.join(root, 'src/aceternity/.sync-state.json')))
})

test('imports are adapted for the app layout (cn alias kept, siblings flattened, framer-motion → motion/react)', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['framer-motion'] } }
  const { impl } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })

  const vendored = await fs.readFile(path.join(root, 'src/aceternity/aurora-background.tsx'), 'utf8')
  assert.ok(vendored.includes(`import { cn } from "@/lib/utils"`), '@/lib/utils must resolve natively, untouched')
  assert.ok(vendored.includes(`from './stars'`), '@/components/ui/* flattens to sibling import')
  assert.ok(vendored.includes(`from 'motion/react'`), 'framer-motion rewrites onto the motion package we already ship')
  assert.ok(vendored.includes('Imports adapted'))
})

test('a synced tree skips the network entirely (offline-friendly)', async () => {
  const root = await scaffold()
  const entries = {
    'aurora-background': { files: AURORA_FILES, dependencies: ['motion'], registryDependencies: ['stars'] },
    stars: { files: [{ path: 'components/ui/stars.tsx', content: 'export function Stars() { return null }\n' }] },
  }
  const { impl } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })

  const offline = async () => {
    throw new Error('network must not be touched when the tree is up to date')
  }
  const report = await syncAceternity(root, { fetchImpl: offline, now: NOW })
  assert.deepEqual([...report.cached].sort(), ['aurora-background', 'stars'])
  assert.equal(report.filesWritten, 0)
})

test('tampered vendor files are detected and restored from the pinned payload', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] } }
  const { impl, calls } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })
  calls.length = 0

  const target = path.join(root, 'src/aceternity/aurora-background.tsx')
  await fs.appendFile(target, '// local tamper\n')
  const report = await syncAceternity(root, { fetchImpl: impl, now: NOW })
  assert.deepEqual(report.fetched, ['aurora-background'])
  assert.equal(calls.length, 1, 'only the tampered subtree is re-fetched')
  const restored = await fs.readFile(target, 'utf8')
  assert.ok(!restored.includes('local tamper'))
})

test('upstream drift from the pin fails sync with a re-pin instruction', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] } }
  const { impl } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })

  // Simulate a fresh CI checkout: vendor tree + pins, no applied-state yet, so
  // sync must re-fetch — where the pin check catches the drifted payload.
  await fs.rm(path.join(root, 'src/aceternity/.sync-state.json'))
  entries["aurora-background"] = { files: [{ ...AURORA_FILES[0], content: '"use client";\nexport function New() { return null }\n' }], dependencies: ['motion'] }
  await assert.rejects(() => syncAceternity(root, { fetchImpl: impl, now: NOW }), /drifted from the pinned sha256.*--update aurora-background/s)
})

test('update deliberately re-pins upstream changes', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] } }
  const { impl } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })

  entries["aurora-background"] = { files: [{ ...AURORA_FILES[0], content: '"use client";\nexport function V2() { return null }\n' }], dependencies: ['motion'] }
  await syncAceternity(root, { mode: 'update', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })
  const manifest = JSON.parse(await fs.readFile(path.join(root, 'aceternity.manifest.json'), 'utf8'))
  assert.equal(manifest.components["aurora-background"].sha256, sha256Hex(JSON.stringify(entries["aurora-background"])))
  const onDisk = await fs.readFile(path.join(root, 'src/aceternity/aurora-background.tsx'), 'utf8')
  assert.ok(onDisk.includes('V2'))
  // and the re-pinned tree is again a network-free no-op
  const offline = async () => {
    throw new Error('network touched after update')
  }
  const report = await syncAceternity(root, { fetchImpl: offline, now: NOW })
  assert.deepEqual(report.cached, ['aurora-background'])
})

test('referenced-but-missing dependencies error with a yarn add hint; unreferenced declarations are ignored', async () => {
  const root = await scaffold()
  const entries = {
    globe: { files: [{ path: 'components/ui/globe.tsx', content: 'import * as THREE from "three";\nexport function Globe() { return null }\n' }], dependencies: ['three'] },
    loader: { files: [{ path: 'components/ui/loader.tsx', content: 'export function Loader() { return null }\n' }], dependencies: ['@tabler/icons-react'] },
  }
  const { impl } = registry(entries)
  await assert.rejects(
    () => syncAceternity(root, { mode: 'add', slugs: ['globe'], fetchImpl: impl, now: NOW }),
    /missing from package.json: three.*yarn add three/s,
  )
  // declared but never imported upstream: fine, nothing to install
  const report = await syncAceternity(root, { mode: 'add', slugs: ['loader'], fetchImpl: impl, now: NOW })
  assert.deepEqual(report.pinned, ['loader'])
})

test('registry paths cannot escape src/aceternity', async () => {
  const root = await scaffold()
  const entries = { evil: { files: [{ path: '../../package.json', content: '{}' }] } }
  const { impl } = registry(entries)
  await assert.rejects(() => syncAceternity(root, { mode: 'add', slugs: ['evil'], fetchImpl: impl, now: NOW }), /unsupported registry file path/)
})

test('files this script does not own are never clobbered without --force', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] } }
  const { impl } = registry(entries)
  const manual = path.join(root, 'src/aceternity/aurora-background.tsx')
  await fs.mkdir(path.dirname(manual), { recursive: true })
  await fs.writeFile(manual, '// manual pro-component drop\n')
  await assert.rejects(
    () => syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW }),
    /not owned by this script.*--force/s,
  )
  assert.equal(await fs.readFile(manual, 'utf8'), '// manual pro-component drop\n')
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW, force: true })
  assert.ok((await fs.readFile(manual, 'utf8')).includes('@ts-nocheck'))
})

test('add refuses a pinned slug; names are locked to registry slugs', async () => {
  const root = await scaffold()
  const entries = { 'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] } }
  const { impl } = registry(entries)
  await syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW })
  await assert.rejects(() => syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: impl, now: NOW }), /already in the manifest.*--update/)
  await assert.rejects(() => syncAceternity(root, { mode: 'add', slugs: ['Not/A-Slug'], fetchImpl: impl, now: NOW }), /invalid component name/)
  assert.equal(normalizeName('aceternity/sidebar'), 'sidebar')
})

test('a failing multi-component run writes nothing (all-or-nothing)', async () => {
  const root = await scaffold()
  const entries = {
    'aurora-background': { files: AURORA_FILES, dependencies: ['motion'] },
    globe: { files: [{ path: 'components/ui/globe.tsx', content: 'import * as THREE from "three";\nexport function Globe() { return null }\n' }], dependencies: ['three'] },
  }
  const { impl } = registry(entries)
  await assert.rejects(
    () => syncAceternity(root, { mode: 'add', slugs: ['aurora-background', 'globe'], fetchImpl: impl, now: NOW }),
    /missing from package.json: three/,
  )
  // No half-updated tree: even the valid aurora-background must not be on disk after the failed run.
  await assert.rejects(() => fs.readdir(path.join(root, 'src/aceternity')), /ENOENT/)
})

test('fetch failures point at the upstream URL — there is no mirror on purpose', async () => {
  const root = await scaffold()
  const failing = async () => {
    throw new Error('ECONNREFUSED')
  }
  await assert.rejects(
    () => syncAceternity(root, { mode: 'add', slugs: ['aurora-background'], fetchImpl: failing, now: NOW }),
    (error) => {
      assert.ok(error.message.includes('https://ui.aceternity.com/registry/aurora-background.json'))
      assert.ok(error.message.includes('ACETERNITY_REGISTRY_DIR'))
      return true
    },
  )
})
