import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'

/**
 * A normalized, validated build configuration parsed from `fengyu.plugin.json`.
 * This is a build-time orchestration contract; it never ships inside the .fyp.
 * @typedef {Object} BuildConfig
 * @property {number} schemaVersion
 * @property {{ root: string, output: string, prepare: string[][], install: string[], test: string[], build: string[] } | null} ui
 * @property {{ root: string, test: string[], build: string[], artifact: string, mainClass: string } | null} worker
 * @property {{ outputDirectory: string, resources: Array<{ from: string, to: string }> }} package
 */

/**
 * Resolve `relative` against `root` and reject anything that escapes the plugin
 * root. Uses both lexical resolution and the real path of the nearest existing
 * ancestor, so symlink escapes are caught even before the target exists.
 *
 * @param {string} root - absolute plugin root
 * @param {string} relative - a configured path relative to the root
 * @param {string} field - dotted config field name, used in error messages
 * @returns {Promise<string>} the safe, resolved absolute path
 */
export async function resolveInside(root, relative, field) {
  const rootAbs = path.resolve(root)
  // The root itself may not exist yet (e.g. ui-src before install); fall back to
  // the lexical path. realpath is only needed when comparing symlinked targets.
  let rootReal = rootAbs
  try {
    rootReal = await fs.realpath(rootAbs)
  } catch {
    /* root does not exist yet — use the lexical path */
  }
  const target = path.resolve(rootAbs, relative)
  if (target !== rootAbs && !target.startsWith(rootAbs + path.sep)) {
    throw new Error(`${field || 'path'} "${relative}" escapes plugin root`)
  }
  // Walk the path segment-by-segment from the root, resolving every symlink as
  // it is encountered. This catches a symlink whose target points outside the
  // root even when the final path segment does not exist yet. The comparison is
  // done against the realpath of the root itself (e.g. on macOS /var → /private/var).
  const relativeSegments = path.relative(rootAbs, target).split(path.sep).filter(Boolean)
  let cursor = rootReal
  for (const segment of relativeSegments) {
    const candidate = path.join(cursor, segment)
    let stat
    try {
      stat = await fs.lstat(candidate)
    } catch {
      // Nothing else exists from here onward; the remaining segments are lexical
      // children of `candidate`, which is already inside the root, so we are safe.
      break
    }
    cursor = stat.isSymbolicLink()
      ? path.resolve(path.dirname(candidate), await fs.readlink(candidate))
      : candidate
    if (cursor !== rootReal && !cursor.startsWith(rootReal + path.sep)) {
      throw new Error(`${field || 'path'} "${relative}" escapes plugin root`)
    }
  }
  return target
}

function requireCommandArray(value, field) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`${field} must be a non-empty command array`)
  }
  for (const token of value) {
    if (typeof token !== 'string' || token.length === 0) {
      throw new Error(`${field} must contain only non-empty strings`)
    }
  }
  return [...value]
}

function requireRuntimePath(value, field) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${field} must be a non-empty relative runtime path`)
  }
  if (value.includes('\\') || path.posix.isAbsolute(value) || /^[A-Za-z]:/.test(value)) {
    throw new Error(`${field} "${value}" must be a POSIX relative runtime path`)
  }
  const normalized = path.posix.normalize(value)
  if (normalized === '.' || normalized === '..' || normalized.startsWith('../')) {
    throw new Error(`${field} "${value}" escapes plugin root`)
  }
  if (normalized === 'manifest.json') {
    throw new Error(`${field} must not overwrite manifest.json`)
  }
  return normalized
}

async function normalizeUi(raw, root) {
  if (raw == null) return null
  if (typeof raw !== 'object') throw new Error('ui must be an object')
  const rootResolved = await resolveInside(root, raw.root ?? '.', 'ui.root')
  // ui.output is relative to ui.root (where Vite writes its outDir), not the project root.
  const output = await resolveInside(rootResolved, raw.output ?? 'dist', 'ui.output')
  const prepare = []
  for (let i = 0; i < (raw.prepare ?? []).length; i++) {
    prepare.push(requireCommandArray(raw.prepare[i], `ui.prepare[${i}]`))
  }
  return {
    root: rootResolved,
    output,
    prepare,
    install: requireCommandArray(raw.install, 'ui.install'),
    test: requireCommandArray(raw.test, 'ui.test'),
    build: requireCommandArray(raw.build, 'ui.build'),
  }
}

async function normalizeWorker(raw, root) {
  if (raw == null) return null
  if (typeof raw !== 'object') throw new Error('worker must be an object')
  const rootResolved = await resolveInside(root, raw.root ?? '.', 'worker.root')
  return {
    root: rootResolved,
    test: requireCommandArray(raw.test, 'worker.test'),
    build: requireCommandArray(raw.build, 'worker.build'),
    artifact: await resolveInside(root, raw.artifact, 'worker.artifact'),
    mainClass: typeof raw.mainClass === 'string' && raw.mainClass.length > 0 ? raw.mainClass : (() => { throw new Error('worker.mainClass is required') })(),
  }
}

async function normalizePackage(raw, root) {
  if (raw == null) return { outputDirectory: 'dist-package', resources: [] }
  if (typeof raw !== 'object') throw new Error('package must be an object')
  const outputDirectory = raw.outputDirectory ?? 'dist-package'
  await resolveInside(root, outputDirectory, 'package.outputDirectory')
  const resources = []
  for (let i = 0; i < (raw.resources ?? []).length; i++) {
    const entry = raw.resources[i]
    if (typeof entry !== 'object') throw new Error(`package.resources[${i}] must be an object`)
    const from = await resolveInside(root, entry.from, `package.resources[${i}].from`)
    const to = requireRuntimePath(entry.to, `package.resources[${i}].to`)
    resources.push({ from, to })
  }
  return { outputDirectory, resources }
}

/**
 * Parse and validate `fengyu.plugin.json` from a plugin root.
 * @param {string} root - plugin project root
 * @returns {Promise<BuildConfig | null>} normalized config, or null when no config file exists
 */
export async function loadBuildConfig(root) {
  const rootAbs = path.resolve(root)
  const file = path.join(rootAbs, 'fengyu.plugin.json')
  let raw
  try {
    raw = JSON.parse(await fs.readFile(file, 'utf8'))
  } catch (e) {
    if (e.code === 'ENOENT') return null
    throw new Error(`fengyu.plugin.json: ${e.message}`)
  }
  const ui = await normalizeUi(raw.ui, rootAbs)
  const worker = await normalizeWorker(raw.worker, rootAbs)
  const pkg = await normalizePackage(raw.package, rootAbs)
  return { schemaVersion: raw.schemaVersion ?? 1, ui, worker, package: pkg }
}
