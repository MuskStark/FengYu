import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { addRegistryComponent, ACETERNITY_REGISTRY_BASE } from '../src/add.mjs'
import { parseCli } from '../src/args.mjs'

let base

test.before(async () => {
  base = await fs.mkdtemp(path.join(os.tmpdir(), 'fy-add-'))
})

test.after(async () => {
  await fs.rm(base, { recursive: true, force: true }).catch(() => {})
})

function registryJson({ files, dependencies = [], registryDependencies = [] }) {
  return { ok: true, json: async () => ({ name: 'x', type: 'registry:ui', files, dependencies, registryDependencies }) }
}

function sidebarPayload() {
  return {
    files: [{ path: 'components/ui/sidebar.tsx', content: '"use client";\nimport { motion } from "motion/react";\nexport function Sidebar() { return null }\n' }],
    dependencies: ['motion', '@tabler/icons-react'],
  }
}

async function scaffoldUi() {
  const root = path.join(base, `plugin-${Date.now()}-${Math.random().toString(36).slice(2)}`)
  await fs.mkdir(path.join(root, 'ui-src'), { recursive: true })
  await fs.writeFile(
    path.join(root, 'ui-src', 'package.json'),
    JSON.stringify({ name: 'com.example.x-ui', dependencies: { motion: '^12.0.0' } }),
  )
  return root
}

test('happy path: stamps provenance after "use client", installs only missing deps, writes ack marker', async () => {
  const root = await scaffoldUi()
  const urls = []
  const installs = []
  const result = await addRegistryComponent(root, 'sidebar', {
    yes: true, interactive: false,
    fetchImpl: async (url) => { urls.push(url); return registryJson(sidebarPayload()) },
    run: async (command, args) => { installs.push([command, args]) },
  })
  const file = await fs.readFile(path.join(root, 'ui-src', 'src', 'aceternity', 'sidebar.tsx'), 'utf8')
  assert.match(file, /^"use client";/)
  assert.match(file, new RegExp(`// Vendored into this plugin by \`fengyu add\` — source: ${ACETERNITY_REGISTRY_BASE}sidebar\\.json`))
  assert.match(file, /do not commit them to a public repository/)
  assert.deepEqual(urls, [`${ACETERNITY_REGISTRY_BASE}sidebar.json`])
  // motion already in package.json — only the missing dep gets installed
  assert.deepEqual(installs, [['npm', ['install', '@tabler/icons-react', '--save']]])
  assert.deepEqual(result.files, ['src/aceternity/sidebar.tsx'])
  assert.deepEqual(result.installed, ['@tabler/icons-react'])
  await assert.doesNotReject(fs.stat(path.join(root, 'ui-src', '.fengyu-aceternity-ack')))
})

test('license gate: without --yes in a non-TTY it refuses before fetching or writing anything', async () => {
  const root = await scaffoldUi()
  let fetched = 0
  await assert.rejects(
    () => addRegistryComponent(root, 'sidebar', {
      interactive: false,
      fetchImpl: async () => { fetched++; return registryJson(sidebarPayload()) },
    }),
    /acknowledgment required[\s\S]*--yes/,
  )
  assert.equal(fetched, 0)
  await assert.rejects(fs.stat(path.join(root, 'ui-src', 'src', 'aceternity', 'sidebar.tsx')))
  await assert.rejects(fs.stat(path.join(root, 'ui-src', '.fengyu-aceternity-ack')))
})

test('ack marker is sticky: the second add skips the license gate', async () => {
  const root = await scaffoldUi()
  await addRegistryComponent(root, 'sidebar', {
    yes: true, interactive: false,
    fetchImpl: async () => registryJson(sidebarPayload()),
    run: async () => {},
  })
  // no `yes` this time — the marker from the first run must carry it
  await addRegistryComponent(root, 'meteors', {
    interactive: false,
    fetchImpl: async () => registryJson({ files: [{ path: 'components/ui/meteors.tsx', content: 'export function Meteors() { return null }\n' }] }),
    run: async () => {},
  })
  await assert.doesNotReject(fs.stat(path.join(root, 'ui-src', 'src', 'aceternity', 'meteors.tsx')))
})

test('existing file: refuses without --force (nothing overwritten, not even sibling files)', async () => {
  const root = await scaffoldUi()
  const target = path.join(root, 'ui-src', 'src', 'aceternity', 'sidebar.tsx')
  await fs.mkdir(path.dirname(target), { recursive: true })
  await fs.writeFile(target, 'local edits')
  const payload = {
    files: [
      { path: 'components/ui/meteors.tsx', content: 'first' },
      { path: 'components/ui/sidebar.tsx', content: 'upstream overwrite' },
    ],
  }
  await assert.rejects(
    () => addRegistryComponent(root, 'multi', {
      yes: true, interactive: false,
      fetchImpl: async () => registryJson(payload),
      run: async () => {},
    }),
    /already exists — re-run with --force/,
  )
  // phase-2 planning refused before any write: the sibling file must not exist either
  assert.equal(await fs.readFile(target, 'utf8'), 'local edits')
  await assert.rejects(fs.stat(path.join(root, 'ui-src', 'src', 'aceternity', 'meteors.tsx')))
  // --force replaces upstream (stamped with the provenance header) and keeps going
  await addRegistryComponent(root, 'multi', {
    yes: true, force: true, interactive: false,
    fetchImpl: async () => registryJson(payload),
    run: async () => {},
  })
  assert.match(await fs.readFile(target, 'utf8'), /upstream overwrite$/)
})

test('network failure and HTTP errors fall back to manual-fetch instructions, never a mirror', async () => {
  const root = await scaffoldUi()
  await assert.rejects(
    () => addRegistryComponent(root, 'sidebar', {
      yes: true, interactive: false,
      fetchImpl: async () => { throw new Error('ECONNREFUSED') },
      run: async () => {},
    }),
    new RegExp(`could not fetch 'sidebar'[\\s\\S]*no mirror[\\s\\S]*manually[\\s\\S]*${ACETERNITY_REGISTRY_BASE}sidebar\\.json`),
  )
  await assert.rejects(
    () => addRegistryComponent(root, 'sidebar', {
      yes: true, interactive: false,
      fetchImpl: async () => ({ ok: false, status: 404 }),
      run: async () => {},
    }),
    /HTTP 404[\s\S]*manually/,
  )
})

test('registryDependencies: slugs and full URLs both recurse from the upstream registry only', async () => {
  const root = await scaffoldUi()
  const urls = []
  const fetchImpl = async (url) => {
    urls.push(url)
    if (url.endsWith('sidebar.json')) {
      return registryJson({ ...sidebarPayload(), registryDependencies: ['spotlight-button', `${ACETERNITY_REGISTRY_BASE}use-mobile.json`] })
    }
    const slug = path.basename(url, '.json')
    return registryJson({ files: [{ path: `components/ui/${slug}.tsx`, content: `export const ${slug} = 1\n` }] })
  }
  const result = await addRegistryComponent(root, 'sidebar', {
    yes: true, interactive: false, fetchImpl, run: async () => {},
  })
  assert.deepEqual(urls, [
    `${ACETERNITY_REGISTRY_BASE}sidebar.json`,
    `${ACETERNITY_REGISTRY_BASE}spotlight-button.json`,
    `${ACETERNITY_REGISTRY_BASE}use-mobile.json`,
  ])
  assert.deepEqual(result.components, ['sidebar', 'spotlight-button', 'use-mobile'])
  assert.ok(result.files.includes('src/aceternity/use-mobile.tsx'))
})

test('component names are locked to registry slugs (no paths, no case tricks)', async () => {
  const root = await scaffoldUi()
  for (const bad of ['../evil', 'Sidebar', 'foo bar', 'a/b/c', '.']) {
    await assert.rejects(() => addRegistryComponent(root, bad, {
      yes: true, interactive: false, fetchImpl: async () => registryJson(sidebarPayload()), run: async () => {},
    }), /invalid component name/)
  }
  // the aceternity/ prefix is accepted as the canonical spelling of a slug
  const urls = []
  await addRegistryComponent(root, 'aceternity/sidebar', {
    yes: true, interactive: false,
    fetchImpl: async (url) => { urls.push(url); return registryJson(sidebarPayload()) },
    run: async () => {},
  })
  assert.deepEqual(urls, [`${ACETERNITY_REGISTRY_BASE}sidebar.json`])
})

test('registry file paths cannot escape src/aceternity', async () => {
  const root = await scaffoldUi()
  await assert.rejects(
    () => addRegistryComponent(root, 'evil', {
      yes: true, interactive: false,
      fetchImpl: async () => registryJson({ files: [{ path: '../../package.json', content: 'pwned' }] }),
      run: async () => {},
    }),
    /unsupported registry file path/,
  )
  await assert.rejects(fs.stat(path.join(root, 'package.json')))
})

test('without a UI project the command points at fengyu init', async () => {
  const root = path.join(base, `empty-${Date.now()}`)
  await fs.mkdir(root, { recursive: true })
  await assert.rejects(
    () => addRegistryComponent(root, 'sidebar', { yes: true, interactive: false, fetchImpl: async () => registryJson(sidebarPayload()) }),
    /no plugin UI project found[\s\S]*fengyu init/,
  )
})

test('--no-install reports the pending deps instead of running npm', async () => {
  const root = await scaffoldUi()
  const installs = []
  const result = await addRegistryComponent(root, 'sidebar', {
    yes: true, interactive: false, install: false,
    fetchImpl: async () => registryJson(sidebarPayload()),
    run: async (...args) => { installs.push(args) },
  })
  assert.equal(installs.length, 0)
  assert.deepEqual(result.installed, [])
  assert.deepEqual(result.pending, ['@tabler/icons-react'])
})

test('nested registry file paths keep their inner layout under src/aceternity', async () => {
  const root = await scaffoldUi()
  await addRegistryComponent(root, 'multi', {
    yes: true, interactive: false,
    fetchImpl: async () => registryJson({
      files: [
        { path: 'components/ui/sidebar.tsx', content: 'a' },
        { path: 'lib/use-mobile.tsx', content: 'b' },
      ],
    }),
    run: async () => {},
  })
  await assert.doesNotReject(fs.stat(path.join(root, 'ui-src', 'src', 'aceternity', 'sidebar.tsx')))
  await assert.doesNotReject(fs.stat(path.join(root, 'ui-src', 'src', 'aceternity', 'lib', 'use-mobile.tsx')))
})

test('shadcn-style imports are adapted for the FengYu scaffold (and --raw-imports keeps them)', async () => {
  const root = await scaffoldUi()
  const payload = {
    files: [{
      path: 'components/ui/spotlight.tsx',
      content: '"use client";\nimport { cn } from "@/lib/utils";\nimport { Card } from "@/components/ui/card";\nimport { useThing } from "@/lib/use-thing";\nexport const X = cn\n',
    }],
  }
  await addRegistryComponent(root, 'spotlight', {
    yes: true, interactive: false,
    fetchImpl: async () => registryJson(payload),
    run: async () => {},
  })
  const file = await fs.readFile(path.join(root, 'ui-src', 'src', 'aceternity', 'spotlight.tsx'), 'utf8')
  assert.match(file, /from '@infinia\/plugin-ui'/)
  assert.match(file, /from '\.\/card'/)
  assert.match(file, /from '\.\/lib\/use-thing'/)
  assert.match(file, /Imports adapted for the FengYu scaffold/)
  // --raw-imports leaves the upstream source untouched
  const rawRoot = await scaffoldUi()
  await addRegistryComponent(rawRoot, 'spotlight', {
    yes: true, interactive: false, adapt: false,
    fetchImpl: async () => registryJson(payload),
    run: async () => {},
  })
  const rawFile = await fs.readFile(path.join(rawRoot, 'ui-src', 'src', 'aceternity', 'spotlight.tsx'), 'utf8')
  assert.match(rawFile, /"@\/lib\/utils"/)
  assert.doesNotMatch(rawFile, /Imports adapted/)
})

test('args: --yes and --force parse as flags for the add command', async () => {
  const parsed = parseCli(['add', 'sidebar', './plugin', '--yes', '--force', '--no-install'])
  assert.equal(parsed.command, 'add')
  assert.deepEqual(parsed.positionals, ['sidebar', './plugin'])
  assert.equal(parsed.options.yes, true)
  assert.equal(parsed.options.force, true)
  assert.equal(parsed.options.install, false)
})
