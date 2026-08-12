import fs from 'node:fs/promises'
import fsSync from 'node:fs'
import path from 'node:path'

const LOCKFILES = ['package-lock.json', 'npm-shrinkwrap.json', 'yarn.lock', 'pnpm-lock.yaml']

/**
 * Normalize an npm version spec (or Maven version string) to its leading
 * X.Y.Z core, or {@code null} when the spec is a local/workspace link or
 * otherwise not a concrete resolvable version. {@code file:}, {@code workspace:},
 * {@code link:}, git/https URLs, relative paths, {@code *}, {@code latest}, and
 * empty values all return null — so an in-repo plugin whose {@code @infinia/*}
 * deps point at sibling toolchain dirs is not falsely flagged, and a real
 * consumer plugin's pinned versions are compared on their X.Y.Z core regardless
 * of {@code ^}/{@code ~} range syntax.
 *
 * @param {string} spec
 * @returns {string|null} the {@code X.Y.Z} core, or null when unresolvable
 */
export function normalizeSemver(spec) {
  if (typeof spec !== 'string') return null
  const s = spec.trim()
  if (!s) return null
  if (/^(file:|workspace:|link:|github:|git\+|git:|https?:|\.\/|\.\.\/)/i.test(s)) return null
  if (/^(latest|\*|x|next)$/i.test(s)) return null
  const m = s.match(/(\d+\.\d+\.\d+)/)
  return m ? m[1] : null
}

/**
 * Collect concrete toolchain versions declared in a UI package.json. Only
 * {@code @infinia/*} dependencies with resolvable versions are returned; links
 * and unresolvable specs are dropped.
 *
 * @param {object} pkg parsed package.json
 * @returns {Record<string, string>} dep name → normalized {@code X.Y.Z}
 */
export function collectNpmToolingVersions(pkg) {
  const out = {}
  for (const depType of ['dependencies', 'devDependencies']) {
    const deps = pkg?.[depType] ?? {}
    for (const [name, spec] of Object.entries(deps)) {
      if (!name.startsWith('@infinia/')) continue
      const v = normalizeSemver(spec)
      if (v) out[name] = v
    }
  }
  return out
}

/**
 * Extract the {@code fengyu-plugin-sdk} version from a worker pom, resolving a
 * {@code ${property}} reference against the pom's own {@code <properties>}
 * block. Returns {@code null} when the version is absent (managed by a parent),
 * an unresolvable property, or not a concrete semver. This keeps the check
 * conservative: only locally-resolvable concrete versions participate.
 *
 * @param {string} pomText raw pom.xml contents
 * @returns {string|null} normalized {@code X.Y.Z}, or null
 */
export function extractMavenSdkVersion(pomText) {
  if (typeof pomText !== 'string') return null
  const props = new Map()
  const propBlock = pomText.match(/<properties>([\s\S]*?)<\/properties>/)?.[1] ?? ''
  for (const m of propBlock.matchAll(/<([\w.-]+)>\s*([^<]+?)\s*<\/\1>/g)) {
    props.set(m[1], m[2].trim())
  }
  for (const d of pomText.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const block = d[1]
    if (!/<artifactId>\s*fengyu-plugin-sdk\s*<\/artifactId>/.test(block)) continue
    const raw = block.match(/<version>\s*([^<]+?)\s*<\/version>/)?.[1]
    if (!raw) return null // managed by parent dependencyManagement → not locally resolvable
    const resolved = raw.startsWith('${') ? props.get(raw.slice(2, -1).trim()) : raw
    return resolved ? normalizeSemver(resolved) : null
  }
  return null
}

/**
 * A committed lockfile is required for reproducible UI builds — but only once
 * dependencies have actually been installed. A freshly scaffolded project
 * (from {@code fengyu init} with no install) has neither {@code node_modules}
 * nor a lockfile and must pass {@code check} immediately, so the rule fires
 * only when {@code node_modules} is present without any recognized lockfile.
 *
 * @param {{ kind: string, root: string, config: object }} project
 * @returns {Promise<string[]>} error messages (empty = ok)
 */
export async function checkLockfile(project) {
  const errors = []
  const uiRoot = project.config?.ui?.root
  if (!uiRoot) return errors
  let pkg
  try {
    pkg = JSON.parse(await fs.readFile(path.join(uiRoot, 'package.json'), 'utf8'))
  } catch {
    return errors // detectProject already guarantees package.json exists
  }
  const hasDeps =
    Object.keys(pkg.dependencies ?? {}).length || Object.keys(pkg.devDependencies ?? {}).length
  if (!hasDeps) return errors
  if (!fsSync.existsSync(path.join(uiRoot, 'node_modules'))) return errors
  const hasLock = LOCKFILES.some((name) => fsSync.existsSync(path.join(uiRoot, name)))
  if (!hasLock) {
    const rel = path.relative(project.root, uiRoot) || '.'
    errors.push(
      `ui dependencies are installed (node_modules present) without a committed lockfile; ` +
        `run \`npm install\` in ${rel} to generate package-lock.json for reproducible builds`,
    )
  }
  return errors
}

/**
 * Verify the plugin's toolchain dependencies agree on a single X.Y.Z across
 * npm ({@code @infinia/*}) and Maven ({@code fengyu-plugin-sdk}). Only
 * concrete, locally-resolvable versions participate; workspace {@code file:}
 * links and parent-managed Maven versions are skipped, so in-repo plugins
 * (whose versions are governed by the monorepo) and fresh scaffolds (whose
 * @infinia/* ranges all share the toolingVersion) pass cleanly. A consumer
 * plugin that pins e.g. {@code plugin-sdk@1.3.0} but {@code plugin-ui@1.2.0}
 * fails here, before the mismatch reaches the host or the generator.
 *
 * @param {{ kind: string, root: string, config: object }} project
 * @returns {Promise<string[]>} error messages (empty = ok)
 */
export async function checkToolchainVersionConsistency(project) {
  const versions = new Map() // normalized X.Y.Z → [source labels]
  const add = (v, src) => {
    const arr = versions.get(v) ?? []
    arr.push(src)
    versions.set(v, arr)
  }
  const uiRoot = project.config?.ui?.root
  if (uiRoot) {
    try {
      const pkg = JSON.parse(await fs.readFile(path.join(uiRoot, 'package.json'), 'utf8'))
      for (const [name, v] of Object.entries(collectNpmToolingVersions(pkg))) add(v, name)
    } catch {
      /* package.json absence is reported elsewhere */
    }
  }
  const workerRoot = project.config?.worker?.root
  if (workerRoot) {
    try {
      const pom = await fs.readFile(path.join(workerRoot, 'pom.xml'), 'utf8')
      const v = extractMavenSdkVersion(pom)
      if (v) add(v, 'fengyu-plugin-sdk (Maven)')
    } catch {
      /* pom.xml absence is reported elsewhere */
    }
  }
  if (versions.size > 1) {
    const detail = [...versions.entries()]
      .map(([v, srcs]) => `  ${v}  ← ${srcs.join(', ')}`)
      .join('\n')
    return [
      `toolchain dependency versions are inconsistent across npm/Maven:\n${detail}\n` +
        `  pin @infinia/* and fengyu-plugin-sdk to the same release`,
    ]
  }
  return []
}
