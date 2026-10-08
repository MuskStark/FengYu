/**
 * `fengyu add <name>` — fetch an Aceternity UI component from the official
 * ui.aceternity.com registry into this plugin's ui-src.
 *
 * The Aceternity license permits using their components inside end products
 * but forbids redistributing their source files. This command therefore never
 * bundles, mirrors, caches, or proxies those files: every fetch is a direct,
 * user-initiated download from the upstream registry, each written file is
 * stamped with its provenance, and the first run must acknowledge the
 * upstream license. Fetched files must stay out of public repositories.
 */
import fs from 'node:fs/promises'
import path from 'node:path'
import readline from 'node:readline/promises'
import { runCommand, resolveCommand, spawnSpec } from './commands.mjs'

export const ACETERNITY_REGISTRY_BASE = 'https://ui.aceternity.com/registry/'

const ACK_MARKER = '.fengyu-aceternity-ack'
const FETCH_TIMEOUT_MS = 15_000

const LICENSE_NOTICE = [
  'Aceternity UI components are fetched from ui.aceternity.com under the Aceternity license:',
  '  - free to use, modify, and ship inside your plugin (an end product you may distribute)',
  '  - forbidden to redistribute the source files — keep them out of public repositories',
  'License: https://ui.aceternity.com/licence',
].join('\n')

/**
 * Accept `sidebar` or `aceternity/sidebar`; reject anything path-like so the
 * name can only ever name a registry slug.
 */
function normalizeName(input) {
  const name = input.replace(/^aceternity\//, '')
  if (!/^[a-z0-9][a-z0-9-]*$/.test(name)) {
    throw new Error(`invalid component name '${input}' — use the registry slug, e.g. 'fengyu add sidebar'`)
  }
  return name
}

function registryUrl(name) {
  return `${ACETERNITY_REGISTRY_BASE}${name}.json`
}

async function exists(target) {
  return fs.stat(target).then(() => true, () => false)
}

/** A plugin UI lives in `ui-src/` (full template) or the project root (--ui-only). */
async function resolveUiRoot(projectRoot) {
  const dir = path.resolve(projectRoot)
  for (const candidate of [path.join(dir, 'ui-src'), dir]) {
    if (await exists(path.join(candidate, 'package.json'))) return candidate
  }
  throw new Error(
    'no plugin UI project found — expected ui-src/package.json (or a root package.json for a --ui-only scaffold); run fengyu init first',
  )
}

/**
 * Registry file paths look like `components/ui/sidebar.tsx`. Strip the shared
 * leading directories and validate every segment: the payload is upstream
 * JSON, so a hostile or malformed path must never escape src/aceternity/.
 */
function relativeUnderAceternity(registryPath) {
  const segments = String(registryPath).split('/').filter(Boolean)
  while (segments.length > 1 && ['components', 'ui', 'registry'].includes(segments[0])) segments.shift()
  if (segments.length === 0 || segments.some((segment) => segment === '.' || segment === '..' || !/^[A-Za-z0-9._-]+$/.test(segment))) {
    throw new Error(`unsupported registry file path '${registryPath}'`)
  }
  return segments.join('/')
}

/**
 * Registry sources assume a shadcn workspace (`@/lib/utils`, `@/components/…`).
 * FengYu scaffolds have no `@` alias: point those at the kit's `cn` export and
 * at the sibling files fetched into src/aceternity/.
 */
function adaptImports(content) {
  let adapted = false
  const adapt = (pattern, build) => {
    content = content.replace(pattern, (match, quote, captured) => {
      adapted = true
      return build(captured)
    })
  }
  adapt(/(["'])@\/lib\/utils\1/, () => "'@infinia/plugin-ui'")
  adapt(/(["'])@\/components\/ui\/([^"']+)\1/, (name) => `'./${name}'`)
  adapt(/(["'])@\/components\/([^"']+)\1/, (name) => `'./${name}'`)
  adapt(/(["'])@\/lib\/([^"']+)\1/, (name) => `'./lib/${name}'`)
  return { content, adapted }
}

/** Prepend the provenance header, keeping a `"use client"` directive first. */
function stampSource(content, url, fetchedOn, importsAdapted) {
  const header = [
    `// Vendored into this plugin by \`fengyu add\` — source: ${url}`,
    '// © Aceternity UI (ui.aceternity.com). These files are governed by the',
    '// Aceternity license: fine inside an end product, but never redistribute',
    `// the source files (do not commit them to a public repository). Fetched ${fetchedOn}.`,
    ...(importsAdapted
      ? ["// Imports adapted for the FengYu scaffold: @/lib/utils → '@infinia/plugin-ui', @/components/* → './*'."]
      : []),
  ].join('\n')
  const lines = content.split('\n')
  if (/^["']use (client|server)["'];?$/.test((lines[0] ?? '').trim())) {
    lines.splice(1, 0, '', header)
    return lines.join('\n')
  }
  return `${header}\n${content}`
}

async function ensureAcknowledged(uiRoot, { yes, interactive }) {
  const marker = path.join(uiRoot, ACK_MARKER)
  if (await exists(marker)) return
  const tty = interactive ?? (process.stdin.isTTY && process.stdout.isTTY)
  if (yes !== true) {
    if (!tty) {
      throw new Error(`license acknowledgment required — review the notice and re-run with --yes\n\n${LICENSE_NOTICE}`)
    }
    console.log(LICENSE_NOTICE)
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
    const answer = (await rl.question('Acknowledge the Aceternity license? [y/N] ')).trim().toLowerCase()
    rl.close()
    if (answer !== 'y' && answer !== 'yes') throw new Error('not acknowledged — nothing was fetched or written')
  }
  await fs.writeFile(marker, `${new Date().toISOString()}\n`)
}

function manualFallback(name, url, cause) {
  const error = new Error(
    `could not fetch '${name}' from the Aceternity registry (${cause.message}) — ` +
      `no mirror is provided on purpose; fetch it manually from ${url} ` +
      '(or copy it from ui.aceternity.com) into ui-src/src/aceternity/',
  )
  error.cause = cause
  return error
}

/** registryDependencies entries may be registry slugs or full registry URLs. */
function dependencySlug(entry) {
  const value = String(entry)
  if (/^[a-z0-9][a-z0-9-]*$/.test(value)) return value
  if (/^https?:\/\//.test(value)) {
    const base = value.replace(/\.json$/, '')
    const slug = base.slice(base.lastIndexOf('/') + 1)
    return /^[a-z0-9][a-z0-9-]*$/.test(slug) ? slug : null
  }
  return null
}

async function fetchRegistry(name, fetchImpl, seen) {
  const url = registryUrl(name)
  let response
  try {
    response = await fetchImpl(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) })
  } catch (error) {
    throw manualFallback(name, url, error)
  }
  if (!response.ok) throw manualFallback(name, url, new Error(`HTTP ${response.status}`))
  let registry
  try {
    registry = await response.json()
  } catch (error) {
    throw manualFallback(name, url, error)
  }
  if (!Array.isArray(registry.files) || registry.files.length === 0) {
    throw manualFallback(name, url, new Error('registry payload has no files'))
  }
  seen.set(name, { registry, url })
  for (const dependency of registry.registryDependencies ?? []) {
    const slug = dependencySlug(dependency)
    if (slug && !seen.has(slug)) await fetchRegistry(slug, fetchImpl, seen)
  }
  return seen
}

/**
 * Install missing registry-declared dependencies WITHOUT a shell: the names come
 * from upstream registry JSON, so they must never reach `cmd.exe /c` (runCommand's
 * Windows default). Resolves through the same {@link resolveCommand}+{@link spawnSpec}
 * pair build/dev use, which pins `shell: false` for every platform.
 */
export async function npmInstallRegistryDeps(run, uiRoot, missing) {
  // The names come from registry JSON and reach `cmd.exe /d /s /c npm.cmd install ...`
  // on Windows, where spawn's own quoting covers whitespace/quotes but NOT cmd's `&`,
  // `|`, `^`, `%` separators. Pin every name to the npm package grammar (scoped or
  // plain, npm's own validation shape) so a hostile value fails HERE, before any spawn.
  for (const name of missing) {
    if (!/^(?:@[a-z0-9-*~][a-z0-9-*._~]*\/)?[a-z0-9-*~][a-z0-9-*._~]*$/i.test(name)) {
      throw new Error(`Refusing to install a name outside the npm package grammar: ${JSON.stringify(name)}`)
    }
  }
  const resolved = await resolveCommand(['npm', 'install', ...missing, '--save'], uiRoot)
  const spec = spawnSpec(resolved)
  await run(spec.command, spec.args, { cwd: uiRoot, env: resolved.env, shell: spec.shell })
}

/**
 * @param {string} projectRoot - plugin project root (ui-src inside, or ui-only root)
 * @param {string} nameInput - registry slug, `sidebar` or `aceternity/sidebar`
 * @param {{ yes?: boolean, force?: boolean, install?: boolean, fetchImpl?: typeof fetch,
 *           run?: (command: string, args: string[], options?: object) => Promise<unknown>,
 *           interactive?: boolean }} [options]
 * @returns {Promise<{ uiRoot: string, components: string[], files: string[], installed: string[], pending: string[] }>}
 */
export async function addRegistryComponent(projectRoot, nameInput, {
  yes = false, force = false, install = true, adapt = true,
  fetchImpl = fetch, run = runCommand, interactive,
} = {}) {
  const name = normalizeName(nameInput)
  const uiRoot = await resolveUiRoot(projectRoot)
  await ensureAcknowledged(uiRoot, { yes, interactive })

  // Phase 1 — fetch the component (and any registryDependencies) upstream.
  const fetched = await fetchRegistry(name, fetchImpl, new Map())

  // Phase 2 — plan every write; refuse before touching disk if a target
  // exists (partial overwrites would silently mix upstream and local edits).
  const fetchedOn = new Date().toISOString().slice(0, 10)
  const writes = []
  for (const [component, { registry, url }] of fetched) {
    for (const file of registry.files) {
      const relative = relativeUnderAceternity(file.path)
      const target = path.join(uiRoot, 'src', 'aceternity', relative)
      if (!force && (await exists(target))) {
        throw new Error(`src/aceternity/${relative} (from '${component}') already exists — re-run with --force to overwrite`)
      }
      const source = file.content ?? ''
      const { content, adapted: importsAdapted } = adapt
        ? adaptImports(source)
        : { content: source, adapted: false }
      writes.push({ relative, target, content: stampSource(content, url, fetchedOn, importsAdapted) })
    }
  }

  // Phase 3 — write with the provenance stamp.
  for (const write of writes) {
    await fs.mkdir(path.dirname(write.target), { recursive: true })
    await fs.writeFile(write.target, write.content)
  }

  // Phase 4 — dependencies listed by the registry payloads.
  const dependencies = new Set()
  for (const { registry } of fetched.values()) {
    for (const dependency of registry.dependencies ?? []) dependencies.add(dependency)
  }
  const pkgPath = path.join(uiRoot, 'package.json')
  const pkg = JSON.parse(await fs.readFile(pkgPath, 'utf8'))
  const missing = [...dependencies].filter(
    (dependency) => pkg.dependencies?.[dependency] === undefined && pkg.devDependencies?.[dependency] === undefined,
  )
  if (missing.length > 0 && install) {
    try {
      await npmInstallRegistryDeps(run, uiRoot, missing)
    } catch (error) {
      throw new Error(`npm install failed — run it manually: cd ${uiRoot} && npm install ${missing.join(' ')}`, { cause: error })
    }
  }

  return {
    uiRoot,
    components: [...fetched.keys()],
    files: writes.map((write) => `src/aceternity/${write.relative}`),
    installed: install ? missing : [],
    pending: install ? [] : missing,
  }
}
