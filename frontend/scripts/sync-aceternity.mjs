#!/usr/bin/env node
/**
 * `sync-aceternity.mjs` — vendor Aceternity UI components into the main app.
 *
 * The Aceternity license (https://ui.aceternity.com/licence) permits using
 * their components inside end products but forbids redistributing their
 * source files. This repository is public, so vendored sources are never
 * committed: they are fetched at dev/build time into `src/aceternity/`
 * (gitignored), and only their sha256 pins (`aceternity.manifest.json`) plus
 * our own wrapper layer live in git. Built artifacts embed the components —
 * the permitted "use inside an end product".
 *
 * Modeled on the plugin-side `toolchain/cli/src/add.mjs` (`fengyu add`), with
 * the main-app differences: manifest-driven and hash-pinned for reproducible
 * CI, unattended (no interactive ack), never installs dependencies (a
 * `yarn add` is a reviewed change), and stamps `@ts-nocheck` so upstream
 * sources pass our tsconfig as black boxes while keeping their export types.
 *
 * Usage:
 *   node scripts/sync-aceternity.mjs              # sync everything in the manifest
 *   node scripts/sync-aceternity.mjs --add <slug>     # vendor + pin a new component
 *   node scripts/sync-aceternity.mjs --update <slug>  # deliberately re-pin upstream changes
 * Flags: --force (overwrite files this script does not own), --help.
 * Env: ACETERNITY_REGISTRY_DIR=<dir> reads <dir>/<slug>.json before hitting the
 *      network — for offline work or manual downloads (no repo mirror, on purpose).
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const ACETERNITY_REGISTRY_BASE = 'https://ui.aceternity.com/registry/'
const FETCH_TIMEOUT_MS = 15_000
const STATE_FILE = '.sync-state.json'
const DEFAULT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/** Registry `dependencies` entries map onto our package.json like this. */
const DEP_ALIASES = { 'framer-motion': 'motion' }

/**
 * Accept `sidebar` or `aceternity/sidebar`; reject anything path-like so the
 * name can only ever name a registry slug (same rule as `fengyu add`).
 */
export function normalizeName(input) {
  const name = String(input).replace(/^aceternity\//, '')
  if (!/^[a-z0-9][a-z0-9-]*$/.test(name)) {
    throw new Error(`invalid component name '${input}' — use the registry slug, e.g. --add aurora-background`)
  }
  return name
}

export function registryUrl(name) {
  return `${ACETERNITY_REGISTRY_BASE}${name}.json`
}

export function sha256Hex(text) {
  return createHash('sha256').update(text).digest('hex')
}

/**
 * Registry file paths look like `components/ui/sidebar.tsx`. Strip the shared
 * leading directories and validate every segment: the payload is upstream
 * JSON, so a hostile or malformed path must never escape src/aceternity/.
 */
export function relativeUnderAceternity(registryPath) {
  const segments = String(registryPath).split('/').filter(Boolean)
  while (segments.length > 1 && ['components', 'ui', 'registry'].includes(segments[0])) segments.shift()
  if (segments.length === 0 || segments.some((segment) => segment === '.' || segment === '..' || !/^[A-Za-z0-9._-]+$/.test(segment))) {
    throw new Error(`unsupported registry file path '${registryPath}'`)
  }
  return segments.join('/')
}

/**
 * Registry sources assume a shadcn workspace. This app HAS the `@` alias and
 * `cn` at `@/lib/utils`, so that import stays; sibling fetches land flat next
 * to each other; `framer-motion` is the old name of the `motion` package we
 * already ship (imports map 1:1 onto `motion/react`).
 */
export function adaptImports(content) {
  let adapted = false
  const adapt = (pattern, build) => {
    content = content.replace(pattern, (match, quote, captured) => {
      adapted = true
      return build(captured)
    })
  }
  adapt(/(["'])@\/components\/ui\/([^"']+)\1/g, (name) => `'./${name}'`)
  adapt(/(["'])@\/components\/([^"']+)\1/g, (name) => `'./${name}'`)
  // `@/lib/utils` stays: this app has the same alias and an identical `cn`.
  adapt(/(["'])@\/lib\/(?!utils\1)([^"']+)\1/g, (name) => `'./lib/${name}'`)
  adapt(/(["'])framer-motion\1/g, () => `'motion/react'`)
  return { content, adapted }
}

/**
 * Prepend the provenance header. The `@ts-nocheck` pragma must sit before any
 * statement to take effect, so the header goes ABOVE a `"use client"`
 * directive — leading comments do not break the directive prologue.
 */
export function stampSource(content, url, fetchedOn, importsAdapted) {
  const header = [
    '// @ts-nocheck — vendored upstream source; treated as a black box (export types stay available).',
    `// Vendored into the FengYu frontend by \`scripts/sync-aceternity.mjs\` — source: ${url}`,
    '// © Aceternity UI (ui.aceternity.com), governed by the Aceternity license: fine inside an',
    '// end product, but never redistribute the source files — this directory is gitignored on',
    `// purpose and must never be committed. Fetched ${fetchedOn}.`,
    ...(importsAdapted
      ? ["// Imports adapted for this app's layout: @/components/* → './*', @/lib/* → './lib/*', framer-motion → 'motion/react' (@/lib/utils resolves as-is)."]
      : []),
  ].join('\n')
  return `${header}\n${content}`
}

function manualFallback(name, url, cause) {
  const error = new Error(
    `could not fetch '${name}' from the Aceternity registry (${cause.message}) — no mirror is provided ` +
      `on purpose; fetch it manually from ${url} (or copy it from ui.aceternity.com) into a local ` +
      'directory and re-run with ACETERNITY_REGISTRY_DIR=<that directory>',
  )
  error.cause = cause
  return error
}

/** registryDependencies entries may be registry slugs or full registry URLs. */
export function dependencySlug(entry) {
  const value = String(entry)
  if (/^[a-z0-9][a-z0-9-]*$/.test(value)) return value
  if (/^https?:\/\//.test(value)) {
    const base = value.replace(/\.json$/, '')
    const slug = base.slice(base.lastIndexOf('/') + 1)
    return /^[a-z0-9][a-z0-9-]*$/.test(slug) ? slug : null
  }
  return null
}

/**
 * Wrap the network fetch with an optional local-first override: with
 * ACETERNITY_REGISTRY_DIR set, `<dir>/<slug>.json` is read before the network.
 */
function makeFetchImpl(env, fetchImpl) {
  const dir = env.ACETERNITY_REGISTRY_DIR
  if (!dir) return fetchImpl
  return async (url, init) => {
    const slug = String(url).slice(ACETERNITY_REGISTRY_BASE.length).replace(/\.json$/, '')
    try {
      const text = await fs.readFile(path.join(dir, `${slug}.json`), 'utf8')
      return { ok: true, status: 200, text: async () => text, json: async () => JSON.parse(text) }
    } catch {
      // not mirrored locally — fall through to the network
    }
    return fetchImpl(url, init)
  }
}

async function fetchRegistry(name, fetchImpl, seen) {
  const url = registryUrl(name)
  let response
  let text
  try {
    response = await fetchImpl(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) })
    text = await response.text()
  } catch (error) {
    throw manualFallback(name, url, error)
  }
  if (!response.ok) throw manualFallback(name, url, new Error(`HTTP ${response.status}`))
  let registry
  try {
    registry = JSON.parse(text)
  } catch (error) {
    throw manualFallback(name, url, error)
  }
  if (!Array.isArray(registry.files) || registry.files.length === 0) {
    throw manualFallback(name, url, new Error('registry payload has no files'))
  }
  seen.set(name, { registry, url, text })
  for (const dependency of registry.registryDependencies ?? []) {
    const slug = dependencySlug(dependency)
    if (slug && !seen.has(slug)) await fetchRegistry(slug, fetchImpl, seen)
  }
  return seen
}

/**
 * Registry payloads declare npm dependencies. We never install anything: a
 * missing-but-referenced dependency is an error (fix = a reviewed `yarn add`),
 * a declared-but-unreferenced one is reported and ignored (upstream declares
 * deps its demo imports, not the component itself).
 */
export function missingDependencies(fetched, pkg) {
  const present = new Set([...Object.keys(pkg.dependencies ?? {}), ...Object.keys(pkg.devDependencies ?? {})])
  const declared = new Set()
  for (const { registry } of fetched.values()) {
    for (const dependency of registry.dependencies ?? []) declared.add(DEP_ALIASES[dependency] ?? dependency)
  }
  const missing = []
  const unusedDeclared = []
  for (const dep of declared) {
    if (present.has(dep)) continue
    const referenced = [...fetched.values()].some(({ registry }) =>
      registry.files.some((file) => new RegExp(`(["'])${dep.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\1`).test(String(file.content ?? ''))),
    )
    ;(referenced ? missing : unusedDeclared).push(dep)
  }
  return { missing, unusedDeclared }
}

async function readJsonOr(file, fallback) {
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'))
  } catch (error) {
    if (error?.code === 'ENOENT') return fallback
    throw error
  }
}

async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true })
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`)
}

async function fileSha(file) {
  return sha256Hex(await fs.readFile(file, 'utf8'))
}

/** A manifest entry is satisfied when the applied state matches the pin and every file on disk still hashes to what we wrote. */
async function isUpToDate(slug, pin, state, vendorDir) {
  const applied = state.components?.[slug]
  if (!applied || applied.registrySha256 !== pin.sha256) return false
  const expected = Object.entries(applied.files ?? {})
  if (expected.length === 0) return false
  for (const [relative, sha] of expected) {
    try {
      if ((await fileSha(path.join(vendorDir, relative))) !== sha) return false
    } catch {
      return false
    }
  }
  return true
}

/**
 * Plan every write first, then write — a partially updated vendor tree would
 * silently mix upstream versions. Files this script has recorded are freely
 * updated; a file on disk that no state entry owns may be a manual drop
 * (e.g. a purchased component) and is never clobbered without --force.
 */
async function planAndWrite(fetched, vendorDir, state, { force, fetchedOn }) {
  const owned = new Set()
  for (const applied of Object.values(state.components ?? {})) {
    for (const relative of Object.keys(applied.files ?? {})) owned.add(relative)
  }
  const writes = []
  const recorded = new Map()
  for (const [component, { registry, url }] of fetched) {
    for (const file of registry.files) {
      const relative = relativeUnderAceternity(file.path)
      const target = path.join(vendorDir, relative)
      if (!recorded.has(relative)) {
        const { content, adapted } = adaptImports(String(file.content ?? ''))
        writes.push({ relative, target, content: stampSource(content, url, fetchedOn, adapted) })
      }
      recorded.set(relative, component)
    }
  }
  for (const write of writes) {
    let exists = true
    try {
      await fs.access(write.target)
    } catch {
      exists = false
    }
    if (exists && !owned.has(write.relative) && !force) {
      throw new Error(
        `src/aceternity/${write.relative} exists but is not owned by this script (a manual drop?) — ` +
          'move it aside or re-run with --force to overwrite',
      )
    }
  }
  for (const write of writes) {
    await fs.mkdir(path.dirname(write.target), { recursive: true })
    await fs.writeFile(write.target, write.content)
  }
  // Re-point state: every fetched slug records the files it contributed (first fetcher wins on shared files).
  const perSlug = new Map()
  for (const [relative, component] of recorded) {
    const write = writes.find((candidate) => candidate.relative === relative)
    if (!perSlug.has(component)) perSlug.set(component, {})
    perSlug.get(component)[relative] = sha256Hex(write.content)
  }
  for (const [component, files] of perSlug) {
    state.components[component] = { registrySha256: sha256Hex(fetched.get(component).text), files }
  }
  return writes.length
}

function sortManifest(manifest) {
  const components = {}
  for (const slug of Object.keys(manifest.components ?? {}).sort()) components[slug] = manifest.components[slug]
  return { components }
}

/**
 * @param {string} [root] - frontend root (defaults to this script's parent)
 * @param {{ mode?: 'sync'|'add'|'update', slugs?: string[], force?: boolean,
 *           fetchImpl?: typeof fetch, env?: object, now?: Date }} [options]
 * @returns {Promise<{ cached: string[], fetched: string[], pinned: string[], filesWritten: number }>}
 */
export async function syncAceternity(root = DEFAULT_ROOT, { mode = 'sync', slugs = [], force = false, fetchImpl = fetch, env = process.env, now = new Date() } = {}) {
  const manifestPath = path.join(root, 'aceternity.manifest.json')
  const vendorDir = path.join(root, 'src', 'aceternity')
  const manifest = await readJsonOr(manifestPath, { components: {} })
  manifest.components ??= {}
  const pkg = await readJsonOr(path.join(root, 'package.json'), {})
  const state = await readJsonOr(path.join(vendorDir, STATE_FILE), { components: {} })
  state.components ??= {}
  const impl = makeFetchImpl(env, fetchImpl)
  const fetchedOn = now.toISOString().slice(0, 10)
  const report = { cached: [], fetched: [], pinned: [], filesWritten: 0 }

  const targets = []
  if (mode === 'sync') {
    for (const [slug, pin] of Object.entries(manifest.components)) {
      if (await isUpToDate(slug, pin, state, vendorDir)) {
        report.cached.push(slug)
        continue
      }
      targets.push(slug)
    }
  } else {
    for (const input of slugs) {
      const slug = normalizeName(input)
      if (mode === 'add' && manifest.components[slug]) throw new Error(`'${slug}' is already in the manifest — use --update ${slug} to re-pin`)
      if (mode === 'update' && !manifest.components[slug]) throw new Error(`'${slug}' is not in the manifest — use --add ${slug}`)
      if (!targets.includes(slug)) targets.push(slug)
    }
  }

  // Fetch every target subtree into ONE map first, verify globally, and only
  // then write — a mid-run failure (bad dep, drift) must never leave a
  // half-updated vendor tree whose files no state entry owns.
  const allFetched = new Map()
  for (const slug of targets) {
    await fetchRegistry(slug, impl, allFetched)
  }
  for (const [name, { text }] of allFetched) {
    const pin = manifest.components[name]
    if (pin && mode === 'sync' && sha256Hex(text) !== pin.sha256) {
      throw new Error(`'${name}' upstream drifted from the pinned sha256 — review the change, then re-pin with --update ${name}`)
    }
  }
  const { missing, unusedDeclared } = missingDependencies(allFetched, pkg)
  if (missing.length > 0) {
    const note = unusedDeclared.length > 0 ? ` (declared upstream but unreferenced, ignored: ${unusedDeclared.join(', ')})` : ''
    throw new Error(`registry declares dependencies missing from package.json: ${missing.join(', ')} — run \`yarn add ${missing.join(' ')}\`, commit, then re-run${note}`)
  }
  if (targets.length > 0) {
    report.filesWritten += await planAndWrite(allFetched, vendorDir, state, { force, fetchedOn })
    report.fetched.push(...targets)
    if (mode !== 'sync') {
      for (const [name, { text }] of allFetched) {
        const previous = manifest.components[name]
        manifest.components[name] = { sha256: sha256Hex(text), pinnedAt: fetchedOn, ...(previous ? { note: previous.note } : {}) }
        if (!report.pinned.includes(name)) report.pinned.push(name)
      }
    }
  }

  if (report.fetched.length > 0) await writeJson(path.join(vendorDir, STATE_FILE), state)
  if (mode !== 'sync' && report.pinned.length > 0) await writeJson(manifestPath, sortManifest(manifest))
  return report
}

function parseArgs(argv) {
  const options = { mode: 'sync', slugs: [], force: false, help: false }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--add' || argument === '--update') {
      options.mode = argument.slice(2)
      options.slugs.push(argv[++index])
    } else if (argument === '--force') options.force = true
    else if (argument === '--help' || argument === '-h') options.help = true
    else throw new Error(`unknown argument '${argument}' — see --help`)
  }
  if ((options.mode === 'add' || options.mode === 'update') && options.slugs.some((slug) => !slug)) {
    throw new Error(`--${options.mode} needs a component slug`)
  }
  return options
}

const HELP = `Usage: node scripts/sync-aceternity.mjs [--add <slug> | --update <slug>] [--force]

Vendors Aceternity UI components from https://ui.aceternity.com/registry/ into
src/aceternity/ (gitignored — their license forbids committing the sources).
aceternity.manifest.json pins each component's registry payload by sha256.

  (no flags)        sync every manifest entry (offline no-op when up to date)
  --add <slug>      vendor a new component and pin it (plus its registryDependencies)
  --update <slug>   deliberately re-pin after upstream changes
  --force           overwrite files in src/aceternity/ this script does not own

Env: ACETERNITY_REGISTRY_DIR=<dir> reads <dir>/<slug>.json before the network.`

export async function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv)
  if (options.help) {
    console.log(HELP)
    return
  }
  const report = await syncAceternity(DEFAULT_ROOT, options)
  if (report.cached.length > 0) console.log(`[aceternity] up to date (no network): ${report.cached.join(', ')}`)
  if (report.fetched.length > 0) {
    console.log(`[aceternity] fetched: ${report.fetched.join(', ')} (${report.filesWritten} files)`)
    console.log('[aceternity] sources are gitignored on purpose — never commit src/aceternity/')
  }
  if (report.pinned.length > 0) console.log(`[aceternity] pinned in aceternity.manifest.json: ${report.pinned.join(', ')} (commit this file)`)
  if (report.cached.length + report.fetched.length === 0) console.log('[aceternity] manifest is empty — vendor components with --add <slug>')
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`[aceternity] ${error.message}`)
    process.exitCode = 1
  })
}
